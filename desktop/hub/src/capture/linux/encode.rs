use super::grab::Image;
use ffmpeg_next::codec::context::Context;
use ffmpeg_next::codec::encoder;
use ffmpeg_next::codec::packet::Packet;
use ffmpeg_next::ffi;
use ffmpeg_next::util::format::sample::Type as Layout;
use ffmpeg_next::util::format::{Pixel, Sample};
use ffmpeg_next::{ChannelLayout, Dictionary, color, frame, picture};

pub const SAMPLE_RATE: i32 = 48_000;
pub const SAMPLES_PER_FRAME: usize = 1024;
const AUDIO_BITRATE: usize = 128_000;
const TICKS_PER_SECOND: i32 = 10_000_000;
const MIN_BITRATE_KBPS: i32 = 200;
const MAX_BITRATE_KBPS: i32 = 20_000;

pub struct EncodedVideo {
    pub timestamp: i64,
    pub keyframe: bool,
    pub data: Vec<u8>,
}

// NVENC first, then x264 on the processor. Both in Baseline, which never reorders frames, with the sequence and picture
// parameters in front of every keyframe so a decoder can join late.
const ENCODERS: [(&str, &[(&str, &str)]); 2] = [
    ("h264_nvenc", &[("preset", "p1"), ("tune", "ull"), ("rc", "cbr"), ("zerolatency", "1"), ("delay", "0"), ("forced-idr", "1"), ("profile", "baseline")]),
    ("libx264", &[("preset", "veryfast"), ("tune", "zerolatency"), ("forced-idr", "1"), ("profile", "baseline")]),
];

pub struct VideoEncoder {
    encoder: encoder::video::Encoder,
    frame: frame::Video,
    scaler: Option<Scaler>,
}

impl VideoEncoder {
    pub fn open(width: u32, height: u32, frame_rate: i32, bitrate_kbps: i32) -> Option<Self> {
        let encoder = ENCODERS.iter().find_map(|(name, options)| open_video(name, options, width, height, frame_rate, bitrate_kbps))?;
        let mut frame = frame::Video::new(Pixel::YUV420P, width, height);
        paint_black(&mut frame);
        Some(Self { encoder, frame, scaler: None })
    }

    pub fn set_bitrate(&mut self, kbps: i32) {
        let bits = kbps.clamp(MIN_BITRATE_KBPS, MAX_BITRATE_KBPS) as i64 * 1000;
        // Both encoders compare these with what they were opened with on the next frame and reconfigure.
        unsafe {
            let context = self.encoder.as_mut_ptr();
            (*context).bit_rate = bits;
            (*context).rc_max_rate = bits;
            (*context).rc_buffer_size = (bits / 2) as i32;
        }
    }

    pub fn draw(&mut self, image: &Image) {
        let (width, height) = (self.frame.width(), self.frame.height());
        if self.scaler.as_ref().is_none_or(|scaler| !scaler.fits(image)) {
            self.scaler = Scaler::new(image, width, height);
            unsafe { ffi::av_frame_make_writable(self.frame.as_mut_ptr()) };
            paint_black(&mut self.frame);
        }
        let Some(scaler) = &mut self.scaler else { return };
        unsafe {
            ffi::av_frame_make_writable(self.frame.as_mut_ptr());
            scaler.scale(image, self.frame.as_mut_ptr());
        }
    }

    pub fn encode(&mut self, timestamp: i64, keyframe: bool) -> Vec<EncodedVideo> {
        self.frame.set_pts(Some(timestamp));
        self.frame.set_kind(if keyframe { picture::Type::I } else { picture::Type::None });
        if self.encoder.send_frame(&self.frame).is_err() {
            return vec![];
        }
        let mut out = Vec::new();
        let mut packet = Packet::empty();
        while self.encoder.receive_packet(&mut packet).is_ok() {
            if let Some(data) = packet.data() {
                out.push(EncodedVideo { timestamp: packet.pts().unwrap_or(timestamp), keyframe: packet.is_key(), data: data.to_vec() });
            }
        }
        out
    }
}

fn open_video(name: &str, options: &[(&str, &str)], width: u32, height: u32, frame_rate: i32, bitrate_kbps: i32) -> Option<encoder::video::Encoder> {
    let codec = encoder::find_by_name(name)?;
    let mut video = Context::new_with_codec(codec).encoder().video().ok()?;
    let bits = bitrate_kbps.clamp(MIN_BITRATE_KBPS, MAX_BITRATE_KBPS) as usize * 1000;
    video.set_width(width);
    video.set_height(height);
    video.set_format(Pixel::YUV420P);
    video.set_time_base((1, TICKS_PER_SECOND));
    video.set_frame_rate(Some((frame_rate, 1)));
    video.set_gop(frame_rate as u32 * 2);
    video.set_max_b_frames(0);
    video.set_bit_rate(bits);
    video.set_max_bit_rate(bits);
    video.set_colorspace(color::Space::BT709);
    video.set_color_range(color::Range::MPEG);
    video.set_color_primaries(color::Primaries::BT709);
    video.set_color_transfer_characteristic(color::TransferCharacteristic::BT709);
    unsafe { (*video.as_mut_ptr()).rc_buffer_size = (bits / 2) as i32 };
    let mut dictionary = Dictionary::new();
    for (key, value) in options {
        dictionary.set(key, value);
    }
    video.open_with(dictionary).ok()
}

fn paint_black(frame: &mut frame::Video) {
    for (plane, value) in [(0, 16u8), (1, 128), (2, 128)] {
        frame.data_mut(plane).fill(value);
    }
}

// swscale in FFmpeg 4.4 takes 11 to 14 ms for one 1080p frame on one core, so bands of the picture convert side by side.
// Converting them straight into the frame left dark pixels where bands meet, so each fills a padded buffer of its own.
const BANDS: u32 = 4;
const PADDING: usize = 256;

struct Band {
    context: *mut ffi::SwsContext,
    source_top: u32,
    source_rows: u32,
    target_top: usize,
    width: usize,
    rows: usize,
    scratch: Vec<u8>,
}

// A context is only ever used by one thread at a time: scale waits for every band before it returns.
unsafe impl Send for Band {}

impl Band {
    fn new(context: *mut ffi::SwsContext, source_top: u32, source_rows: u32, target_top: usize, width: usize, rows: usize) -> Self {
        let mut band = Self { context, source_top, source_rows, target_top, width, rows, scratch: Vec::new() };
        band.scratch = vec![0; band.plane_offsets()[2] + band.strides()[2] * rows / 2 + PADDING];
        band
    }

    fn strides(&self) -> [usize; 3] {
        let luma = (self.width + PADDING).next_multiple_of(64);
        let chroma = (self.width / 2 + PADDING).next_multiple_of(64);
        [luma, chroma, chroma]
    }

    fn plane_offsets(&self) -> [usize; 3] {
        let strides = self.strides();
        let luma = PADDING;
        let blue = luma + strides[0] * self.rows + PADDING;
        [luma, blue, blue + strides[1] * self.rows / 2 + PADDING]
    }

    unsafe fn convert(&mut self, image: &Image, frame: &Target) {
        let offsets = self.plane_offsets();
        let strides = self.strides();
        let base = self.scratch.as_mut_ptr();
        let widths = [self.width, self.width / 2, self.width / 2];
        let heights = [self.rows, self.rows / 2, self.rows / 2];
        unsafe {
            let planes = [base.add(offsets[0]), base.add(offsets[1]), base.add(offsets[2]), std::ptr::null_mut()];
            let scratch_strides = [strides[0] as i32, strides[1] as i32, strides[2] as i32, 0];
            let source = [image.pixels.as_ptr().add(self.source_top as usize * image.stride), std::ptr::null(), std::ptr::null(), std::ptr::null()];
            let source_strides = [image.stride as i32, 0, 0, 0];
            ffi::sws_scale(self.context, source.as_ptr(), source_strides.as_ptr(), 0, self.source_rows as i32, planes.as_ptr(), scratch_strides.as_ptr());
            for plane in 0..3 {
                let shift = (plane > 0) as usize;
                let (left, top) = (frame.left >> shift, (frame.top + self.target_top) >> shift);
                for row in 0..heights[plane] {
                    let from = base.add(offsets[plane] + row * strides[plane]);
                    let to = frame.planes[plane].add((top + row) * frame.strides[plane] as usize + left);
                    std::ptr::copy_nonoverlapping(from, to, widths[plane]);
                }
            }
        }
    }
}

struct Target {
    planes: [*mut u8; 3],
    strides: [i32; 4],
    left: usize,
    top: usize,
}

unsafe impl Send for Target {}
unsafe impl Sync for Target {}

struct Scaler {
    bands: Vec<Band>,
    source: (u32, u32),
    offset: (usize, usize),
}

impl Scaler {
    fn new(image: &Image, width: u32, height: u32) -> Option<Self> {
        let scale = (width as f64 / image.width as f64).min(height as f64 / image.height as f64);
        let even = |length: u32, limit: u32| (((length as f64 * scale / 2.0).round() as u32) * 2).clamp(2, limit);
        let (fitted_width, fitted_height) = (even(image.width, width), even(image.height, height));
        let offset = ((((width - fitted_width) / 2) & !1) as usize, (((height - fitted_height) / 2) & !1) as usize);
        let edge = |band: u32| (fitted_height * band / BANDS) & !1;
        let mut bands = Vec::new();
        for band in 0..BANDS {
            let (target_top, target_bottom) = (edge(band), if band + 1 == BANDS { fitted_height } else { edge(band + 1) });
            let source_top = (target_top as f64 / scale).round() as u32;
            let source_bottom = if band + 1 == BANDS { image.height } else { ((target_bottom as f64 / scale).round() as u32).min(image.height) };
            if target_bottom <= target_top || source_bottom <= source_top {
                continue;
            }
            let context = unsafe {
                ffi::sws_getContext(
                    image.width as i32,
                    (source_bottom - source_top) as i32,
                    ffi::AVPixelFormat::AV_PIX_FMT_BGRA,
                    fitted_width as i32,
                    (target_bottom - target_top) as i32,
                    ffi::AVPixelFormat::AV_PIX_FMT_YUV420P,
                    ffi::SWS_BILINEAR,
                    std::ptr::null_mut(),
                    std::ptr::null_mut(),
                    std::ptr::null(),
                )
            };
            if context.is_null() {
                return None;
            }
            // The X server hands out full range RGB; the stream is limited range BT.709, as the Windows encoder writes it.
            unsafe {
                let bt709 = ffi::sws_getCoefficients(ffi::SWS_CS_ITU709);
                ffi::sws_setColorspaceDetails(context, bt709, 1, bt709, 0, 0, 1 << 16, 1 << 16);
            }
            let (width, rows) = (fitted_width as usize, (target_bottom - target_top) as usize);
            bands.push(Band::new(context, source_top, source_bottom - source_top, target_top as usize, width, rows));
        }
        Some(Self { bands, source: (image.width, image.height), offset })
    }

    fn fits(&self, image: &Image) -> bool {
        self.source == (image.width, image.height)
    }

    unsafe fn scale(&mut self, image: &Image, frame: *mut ffi::AVFrame) {
        let target = unsafe {
            Target {
                planes: [(*frame).data[0], (*frame).data[1], (*frame).data[2]],
                strides: [(*frame).linesize[0], (*frame).linesize[1], (*frame).linesize[2], 0],
                left: self.offset.0,
                top: self.offset.1,
            }
        };
        std::thread::scope(|scope| {
            for band in &mut self.bands {
                let target = &target;
                scope.spawn(move || unsafe { band.convert(image, target) });
            }
        });
    }
}

impl Drop for Scaler {
    fn drop(&mut self) {
        for band in &self.bands {
            unsafe { ffi::sws_freeContext(band.context) };
        }
    }
}

pub struct AudioEncoder {
    encoder: encoder::audio::Encoder,
    frame: frame::Audio,
    samples_sent: i64,
}

impl AudioEncoder {
    pub fn open() -> Option<Self> {
        let codec = encoder::find_by_name("aac")?;
        let mut audio = Context::new_with_codec(codec).encoder().audio().ok()?;
        audio.set_rate(SAMPLE_RATE);
        audio.set_format(Sample::F32(Layout::Planar));
        audio.set_channel_layout(ChannelLayout::STEREO);
        audio.set_bit_rate(AUDIO_BITRATE);
        audio.set_time_base((1, SAMPLE_RATE));
        let encoder = audio.open_with(Dictionary::new()).ok()?;
        let frame = frame::Audio::new(Sample::F32(Layout::Planar), SAMPLES_PER_FRAME, ChannelLayout::STEREO);
        Some(Self { encoder, frame, samples_sent: 0 })
    }

    pub fn encode(&mut self, interleaved: &[f32]) -> Vec<(i64, Vec<u8>)> {
        unsafe { ffi::av_frame_make_writable(self.frame.as_mut_ptr()) };
        for channel in 0..2 {
            let target = self.frame.plane_mut::<f32>(channel);
            for (sample, value) in target.iter_mut().zip(interleaved.iter().skip(channel).step_by(2)) {
                *sample = *value;
            }
        }
        self.frame.set_pts(Some(self.samples_sent));
        self.samples_sent += SAMPLES_PER_FRAME as i64;
        if self.encoder.send_frame(&self.frame).is_err() {
            return vec![];
        }
        let mut out = Vec::new();
        let mut packet = Packet::empty();
        while self.encoder.receive_packet(&mut packet).is_ok() {
            if let (Some(data), Some(position)) = (packet.data(), packet.pts()) {
                out.push((position, data.to_vec()));
            }
        }
        out
    }
}
