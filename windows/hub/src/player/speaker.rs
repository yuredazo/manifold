use std::collections::VecDeque;
use std::mem::ManuallyDrop;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;
use windows::Win32::Foundation::{CloseHandle, WAIT_OBJECT_0};
use windows::Win32::Media::Audio::*;
use windows::Win32::Media::MediaFoundation::*;
use windows::Win32::System::Com::{CLSCTX_ALL, CLSCTX_INPROC_SERVER, COINIT_MULTITHREADED, CoCreateInstance, CoInitializeEx};
use windows::Win32::System::Threading::{CreateEventW, WaitForSingleObject};
use windows::core::Result;

const RATE: u32 = 48_000;
const CHANNELS: u16 = 2;
// Past this much on top of the target the sound is cut back, so a stall never leaves it late for good.
const OVERFLOW_MS: usize = 150;

pub struct AacDecoder {
    transform: IMFTransform,
    output_size: u32,
}

impl AacDecoder {
    pub fn new() -> Result<Self> {
        unsafe { MFStartup(MF_VERSION, MFSTARTUP_LITE)? };
        let transform: IMFTransform = unsafe { CoCreateInstance(&CLSID_MSAACDecMFT, None, CLSCTX_INPROC_SERVER)? };
        unsafe {
            let input = MFCreateMediaType()?;
            input.SetGUID(&MF_MT_MAJOR_TYPE, &MFMediaType_Audio)?;
            input.SetGUID(&MF_MT_SUBTYPE, &MFAudioFormat_AAC)?;
            input.SetUINT32(&MF_MT_AUDIO_SAMPLES_PER_SECOND, RATE)?;
            input.SetUINT32(&MF_MT_AUDIO_NUM_CHANNELS, CHANNELS as u32)?;
            input.SetUINT32(&MF_MT_AAC_PAYLOAD_TYPE, 0)?;
            input.SetUINT32(&MF_MT_AAC_AUDIO_PROFILE_LEVEL_INDICATION, 0x29)?;
            // The part of HEAACWAVEINFO after WAVEFORMATEX, then the AudioSpecificConfig: AAC-LC, 48 kHz, two channels.
            input.SetBlob(&MF_MT_USER_DATA, &[0, 0, 0xFE, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x11, 0x90])?;
            transform.SetInputType(0, &input, 0)?;
            let mut index = 0;
            loop {
                let media = transform.GetOutputAvailableType(0, index)?;
                if media.GetGUID(&MF_MT_SUBTYPE)? == MFAudioFormat_PCM && media.GetUINT32(&MF_MT_AUDIO_BITS_PER_SAMPLE)? == 16 {
                    transform.SetOutputType(0, &media, 0)?;
                    break;
                }
                index += 1;
            }
            let info = transform.GetOutputStreamInfo(0)?;
            transform.ProcessMessage(MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, 0)?;
            Ok(Self { transform, output_size: info.cbSize.max(8192) })
        }
    }

    pub fn decode(&mut self, frame: &[u8], out: &mut Vec<i16>) -> Result<()> {
        unsafe {
            let buffer = MFCreateMemoryBuffer(frame.len() as u32)?;
            let mut target = std::ptr::null_mut();
            buffer.Lock(&mut target, None, None)?;
            std::ptr::copy_nonoverlapping(frame.as_ptr(), target, frame.len());
            buffer.Unlock()?;
            buffer.SetCurrentLength(frame.len() as u32)?;
            let sample = MFCreateSample()?;
            sample.AddBuffer(&buffer)?;
            if let Err(error) = self.transform.ProcessInput(0, &sample, 0)
                && error.code() != MF_E_NOTACCEPTING
            {
                return Err(error);
            }
            loop {
                let output = MFCreateSample()?;
                output.AddBuffer(&MFCreateMemoryBuffer(self.output_size)?)?;
                let mut buffers = [MFT_OUTPUT_DATA_BUFFER { dwStreamID: 0, pSample: ManuallyDrop::new(Some(output)), dwStatus: 0, pEvents: ManuallyDrop::new(None) }];
                let mut status = 0u32;
                let result = self.transform.ProcessOutput(0, &mut buffers, &mut status);
                let sample = ManuallyDrop::take(&mut buffers[0].pSample);
                ManuallyDrop::drop(&mut buffers[0].pEvents);
                match result {
                    Ok(()) => {
                        let Some(sample) = sample else { continue };
                        let pcm = sample.ConvertToContiguousBuffer()?;
                        let mut start = std::ptr::null_mut();
                        let mut length = 0u32;
                        pcm.Lock(&mut start, None, Some(&mut length))?;
                        out.extend_from_slice(std::slice::from_raw_parts(start as *const i16, length as usize / 2));
                        pcm.Unlock()?;
                    }
                    Err(error) if error.code() == MF_E_TRANSFORM_NEED_MORE_INPUT => return Ok(()),
                    Err(error) => return Err(error),
                }
            }
        }
    }
}

struct Queue {
    samples: VecDeque<i16>,
    // Silent until the target is buffered again, so an empty queue refills instead of crackling sample by sample.
    primed: bool,
    target: usize,
}

pub struct Speaker {
    queue: Arc<Mutex<Queue>>,
    stop: Arc<AtomicBool>,
    thread: Option<JoinHandle<()>>,
}

impl Speaker {
    pub fn new(target_ms: usize) -> Self {
        let target = target_ms * RATE as usize / 1000 * CHANNELS as usize;
        let queue = Arc::new(Mutex::new(Queue { samples: VecDeque::new(), primed: false, target }));
        let stop = Arc::new(AtomicBool::new(false));
        let (shared, stopping) = (queue.clone(), stop.clone());
        let thread = std::thread::Builder::new().name("speaker".into()).spawn(move || {
            if let Err(error) = render(&shared, &stopping) {
                eprintln!("speaker: {error}");
            }
        });
        Self { queue, stop, thread: thread.ok() }
    }

    pub fn push(&self, pcm: &[i16]) {
        let mut queue = self.queue.lock().unwrap();
        queue.samples.extend(pcm);
        let limit = queue.target + OVERFLOW_MS * RATE as usize / 1000 * CHANNELS as usize;
        if queue.samples.len() > limit {
            let excess = queue.samples.len() - queue.target;
            queue.samples.drain(..excess - excess % CHANNELS as usize);
        }
    }

    pub fn clear(&self) {
        let mut queue = self.queue.lock().unwrap();
        queue.samples.clear();
        queue.primed = false;
    }
}

impl Drop for Speaker {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        if let Some(thread) = self.thread.take() {
            let _ = thread.join();
        }
    }
}

fn render(queue: &Mutex<Queue>, stop: &AtomicBool) -> Result<()> {
    unsafe {
        let _ = CoInitializeEx(None, COINIT_MULTITHREADED);
        let devices: IMMDeviceEnumerator = CoCreateInstance(&MMDeviceEnumerator, None, CLSCTX_ALL)?;
        let device = devices.GetDefaultAudioEndpoint(eRender, eConsole)?;
        let client: IAudioClient = device.Activate(CLSCTX_ALL, None)?;
        let format = WAVEFORMATEX {
            wFormatTag: 1,
            nChannels: CHANNELS,
            nSamplesPerSec: RATE,
            nAvgBytesPerSec: RATE * CHANNELS as u32 * 2,
            nBlockAlign: CHANNELS * 2,
            wBitsPerSample: 16,
            cbSize: 0,
        };
        let flags = AUDCLNT_STREAMFLAGS_EVENTCALLBACK | AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM | AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY;
        client.Initialize(AUDCLNT_SHAREMODE_SHARED, flags, 300_000, 0, &format, None)?;
        let event = CreateEventW(None, false, false, None)?;
        client.SetEventHandle(event)?;
        let output: IAudioRenderClient = client.GetService()?;
        let frames = client.GetBufferSize()?;
        client.Start()?;
        while !stop.load(Ordering::Relaxed) {
            if WaitForSingleObject(event, 200) != WAIT_OBJECT_0 {
                continue;
            }
            let free = frames - client.GetCurrentPadding()?;
            if free == 0 {
                continue;
            }
            let target = output.GetBuffer(free)? as *mut i16;
            let wanted = free as usize * CHANNELS as usize;
            let slots = std::slice::from_raw_parts_mut(target, wanted);
            let mut queue = queue.lock().unwrap();
            if !queue.primed && queue.samples.len() >= queue.target.max(wanted) {
                queue.primed = true;
            }
            let filled = if queue.primed { queue.samples.len().min(wanted) } else { 0 };
            for (slot, sample) in slots.iter_mut().zip(queue.samples.drain(..filled)) {
                *slot = sample;
            }
            slots[filled..].fill(0);
            if queue.primed && filled < wanted {
                queue.primed = false;
            }
            drop(queue);
            output.ReleaseBuffer(free, 0)?;
        }
        let _ = client.Stop();
        let _ = CloseHandle(event);
    }
    Ok(())
}
