use std::mem::ManuallyDrop;
use windows::Win32::Graphics::Direct3D11::{ID3D11Device, ID3D11Texture2D};
use windows::Win32::Media::MediaFoundation::*;
use windows::Win32::System::Com::{CLSCTX_INPROC_SERVER, CoCreateInstance};
use windows::core::{GUID, Interface, Result};

const FRAME_DURATION: i64 = 333_333;

#[derive(Clone, Copy, Debug)]
pub struct Format {
    pub coded: (u32, u32),
    // What is meant to be seen: a 1080 line picture is decoded 1088 lines tall.
    pub shown: (u32, u32),
    pub kr: f32,
    pub kb: f32,
    pub full_range: bool,
    pub pitch: u32,
}

pub enum PictureData {
    Texture { texture: ID3D11Texture2D, index: u32 },
    Memory { bytes: Vec<u8>, pitch: u32 },
}

pub struct Picture {
    pub format: Format,
    pub data: PictureData,
}

pub struct Decoder {
    transform: IMFTransform,
    _manager: Option<IMFDXGIDeviceManager>,
    provides_samples: bool,
    output_size: u32,
    time: i64,
    format: Option<Format>,
}

fn frame_size(media: &IMFMediaType, key: &GUID) -> Option<(u32, u32)> {
    let packed = unsafe { media.GetUINT64(key) }.ok()?;
    Some(((packed >> 32) as u32, packed as u32))
}

fn format_of(media: &IMFMediaType) -> Format {
    let coded = frame_size(media, &MF_MT_FRAME_SIZE).unwrap_or((16, 16));
    let mut area = MFVideoArea::default();
    let mut read = 0u32;
    let has_aperture = unsafe {
        media.GetBlob(
            &MF_MT_MINIMUM_DISPLAY_APERTURE,
            std::slice::from_raw_parts_mut(&mut area as *mut MFVideoArea as *mut u8, std::mem::size_of::<MFVideoArea>()),
            Some(&mut read),
        )
    }
    .is_ok();
    let shown = if has_aperture && area.Area.cx > 0 && area.Area.cy > 0 { (area.Area.cx as u32, area.Area.cy as u32) } else { coded };
    let matrix = unsafe { media.GetUINT32(&MF_MT_YUV_MATRIX) }.unwrap_or(MFVideoTransferMatrix_BT709.0 as u32);
    // Standard definition pictures say BT.601 when they say nothing, high definition ones BT.709.
    let (kr, kb) = if matrix == MFVideoTransferMatrix_BT601.0 as u32 || (matrix == 0 && coded.1 < 720) { (0.299, 0.114) } else { (0.2126, 0.0722) };
    let range = unsafe { media.GetUINT32(&MF_MT_VIDEO_NOMINAL_RANGE) }.unwrap_or(MFNominalRange_16_235.0 as u32);
    let pitch = unsafe { media.GetUINT32(&MF_MT_DEFAULT_STRIDE) }.map(|stride| (stride as i32).unsigned_abs()).unwrap_or(coded.0);
    Format { coded, shown, kr, kb, full_range: range == MFNominalRange_0_255.0 as u32, pitch }
}

impl Decoder {
    pub fn new(device: Option<&ID3D11Device>) -> Result<Self> {
        unsafe { MFStartup(MF_VERSION, MFSTARTUP_LITE)? };
        let transform: IMFTransform = unsafe { CoCreateInstance(&CLSID_MSH264DecoderMFT, None, CLSCTX_INPROC_SERVER)? };
        unsafe {
            let attributes = transform.GetAttributes()?;
            attributes.SetUINT32(&MF_LOW_LATENCY, 1)?;
        }
        let mut manager = None;
        if let Some(device) = device {
            let mut token = 0u32;
            let mut created = None;
            unsafe { MFCreateDXGIDeviceManager(&mut token, &mut created)? };
            let created = created.unwrap();
            unsafe { created.ResetDevice(device, token)? };
            if unsafe { transform.ProcessMessage(MFT_MESSAGE_SET_D3D_MANAGER, created.as_raw() as usize) }.is_ok() {
                manager = Some(created);
            }
        }
        unsafe {
            let input = MFCreateMediaType()?;
            input.SetGUID(&MF_MT_MAJOR_TYPE, &MFMediaType_Video)?;
            input.SetGUID(&MF_MT_SUBTYPE, &MFVideoFormat_H264)?;
            input.SetUINT32(&MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive.0 as u32)?;
            transform.SetInputType(0, &input, 0)?;
        }
        let mut decoder = Self { _manager: manager, transform, provides_samples: false, output_size: 0, time: 0, format: None };
        decoder.choose_output()?;
        unsafe {
            decoder.transform.ProcessMessage(MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, 0)?;
            decoder.transform.ProcessMessage(MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0)?;
        }
        Ok(decoder)
    }

    fn choose_output(&mut self) -> Result<()> {
        let mut index = 0;
        loop {
            let media = unsafe { self.transform.GetOutputAvailableType(0, index)? };
            if unsafe { media.GetGUID(&MF_MT_SUBTYPE)? } == MFVideoFormat_NV12 {
                unsafe { self.transform.SetOutputType(0, &media, 0)? };
                self.format = Some(format_of(&media));
                break;
            }
            index += 1;
        }
        let info = unsafe { self.transform.GetOutputStreamInfo(0)? };
        self.provides_samples = info.dwFlags & (MFT_OUTPUT_STREAM_PROVIDES_SAMPLES.0 as u32 | MFT_OUTPUT_STREAM_CAN_PROVIDE_SAMPLES.0 as u32) != 0;
        self.output_size = info.cbSize;
        Ok(())
    }

    pub fn decode(&mut self, annex_b: &[u8], keyframe: bool, out: &mut Vec<Picture>) -> Result<()> {
        let sample = unsafe {
            let buffer = MFCreateMemoryBuffer(annex_b.len() as u32)?;
            let mut target = std::ptr::null_mut();
            buffer.Lock(&mut target, None, None)?;
            std::ptr::copy_nonoverlapping(annex_b.as_ptr(), target, annex_b.len());
            buffer.Unlock()?;
            buffer.SetCurrentLength(annex_b.len() as u32)?;
            let sample = MFCreateSample()?;
            sample.AddBuffer(&buffer)?;
            sample.SetSampleTime(self.time)?;
            sample.SetSampleDuration(FRAME_DURATION)?;
            if keyframe {
                sample.SetUINT32(&MFSampleExtension_CleanPoint, 1)?;
            }
            sample
        };
        self.time += FRAME_DURATION;
        loop {
            match unsafe { self.transform.ProcessInput(0, &sample, 0) } {
                Ok(()) => break,
                Err(error) if error.code() == MF_E_NOTACCEPTING => self.drain(out)?,
                Err(error) => return Err(error),
            }
        }
        self.drain(out)
    }

    fn drain(&mut self, out: &mut Vec<Picture>) -> Result<()> {
        loop {
            let allocated = if self.provides_samples {
                None
            } else {
                unsafe {
                    let sample = MFCreateSample()?;
                    sample.AddBuffer(&MFCreateMemoryBuffer(self.output_size.max(1))?)?;
                    Some(sample)
                }
            };
            let mut buffers = [MFT_OUTPUT_DATA_BUFFER { dwStreamID: 0, pSample: ManuallyDrop::new(allocated), dwStatus: 0, pEvents: ManuallyDrop::new(None) }];
            let mut status = 0u32;
            let result = unsafe { self.transform.ProcessOutput(0, &mut buffers, &mut status) };
            let sample = unsafe { ManuallyDrop::take(&mut buffers[0].pSample) };
            unsafe { ManuallyDrop::drop(&mut buffers[0].pEvents) };
            match result {
                Ok(()) => {
                    if let Some(sample) = sample {
                        out.push(self.picture(&sample)?);
                    }
                }
                Err(error) if error.code() == MF_E_TRANSFORM_NEED_MORE_INPUT => return Ok(()),
                Err(error) if error.code() == MF_E_TRANSFORM_STREAM_CHANGE => self.choose_output()?,
                Err(error) => return Err(error),
            }
        }
    }

    fn picture(&self, sample: &IMFSample) -> Result<Picture> {
        let format = self.format.unwrap();
        let buffer = unsafe { sample.GetBufferByIndex(0)? };
        if let Ok(surface) = buffer.cast::<IMFDXGIBuffer>() {
            let mut raw = std::ptr::null_mut();
            unsafe { surface.GetResource(&ID3D11Texture2D::IID, &mut raw)? };
            let texture = unsafe { ID3D11Texture2D::from_raw(raw) };
            let index = unsafe { surface.GetSubresourceIndex()? };
            return Ok(Picture { format, data: PictureData::Texture { texture, index } });
        }
        let rows = format.coded.1 as usize * 3 / 2;
        if let Ok(planar) = buffer.cast::<IMF2DBuffer>() {
            let mut start = std::ptr::null_mut();
            let mut pitch = 0i32;
            unsafe { planar.Lock2D(&mut start, &mut pitch)? };
            let pitch = pitch.unsigned_abs();
            let bytes = unsafe { std::slice::from_raw_parts(start, pitch as usize * rows) }.to_vec();
            unsafe { planar.Unlock2D()? };
            return Ok(Picture { format, data: PictureData::Memory { bytes, pitch } });
        }
        let mut start = std::ptr::null_mut();
        let mut length = 0u32;
        unsafe { buffer.Lock(&mut start, None, Some(&mut length))? };
        let bytes = unsafe { std::slice::from_raw_parts(start, length as usize) }.to_vec();
        unsafe { buffer.Unlock()? };
        Ok(Picture { format, data: PictureData::Memory { bytes, pitch: format.pitch } })
    }
}
