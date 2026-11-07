mod decoder;
mod render;
mod speaker;

use crate::hub::Input;
use decoder::{Decoder, Picture};
use render::Renderer;
use speaker::{AacDecoder, Speaker};
use std::cell::Cell;
use std::sync::Once;
use std::sync::mpsc::{Receiver, Sender, TryRecvError, channel};
use windows::Win32::Foundation::{CloseHandle, HANDLE, HWND, LPARAM, LRESULT, RECT, WPARAM};
use windows::Win32::Graphics::Gdi::{BLACK_BRUSH, GetStockObject, HBRUSH, ValidateRect};
use windows::Win32::System::Com::{COINIT_MULTITHREADED, CoInitializeEx};
use windows::Win32::System::LibraryLoader::GetModuleHandleW;
use windows::Win32::System::Threading::{CreateEventW, INFINITE, SetEvent};
use windows::Win32::UI::HiDpi::GetDpiForSystem;
use windows::Win32::UI::WindowsAndMessaging::*;
use windows::core::{HSTRING, PCWSTR, w};

const CLASS: PCWSTR = w!("ManifoldViewer");
const MAX_WIDTH: f64 = 960.0;
const MAX_HEIGHT: f64 = 720.0;

pub enum PlayerInput {
    Video { data: Vec<u8>, keyframe: bool },
    Audio(Vec<u8>),
    Retitle(String),
    Resumed,
    Close,
}

pub struct PlayerConfig {
    pub stream_id: u16,
    pub title: String,
    pub width: u32,
    pub height: u32,
    pub alpha: bool,
    pub sound_only: bool,
    pub with_audio: bool,
    pub audio_buffer_ms: usize,
}

struct Wake(HANDLE);

unsafe impl Send for Wake {}
unsafe impl Sync for Wake {}

impl Drop for Wake {
    fn drop(&mut self) {
        unsafe {
            let _ = CloseHandle(self.0);
        }
    }
}

pub struct PlayerHandle {
    inputs: Sender<PlayerInput>,
    wake: std::sync::Arc<Wake>,
}

impl PlayerHandle {
    pub fn send(&self, input: PlayerInput) {
        if self.inputs.send(input).is_ok() {
            unsafe {
                let _ = SetEvent(self.wake.0);
            }
        }
    }
}

impl Drop for PlayerHandle {
    fn drop(&mut self) {
        self.send(PlayerInput::Close);
    }
}

pub fn spawn(config: PlayerConfig, events: Sender<Input>) -> PlayerHandle {
    let (inputs, receiver) = channel();
    let wake = std::sync::Arc::new(Wake(unsafe { CreateEventW(None, false, false, None) }.unwrap_or_default()));
    let waker = wake.clone();
    let _ = std::thread::Builder::new().name(format!("player {}", config.stream_id)).spawn(move || {
        let stream_id = config.stream_id;
        if let Err(error) = run(config, receiver, &waker) {
            eprintln!("player: {error}");
        }
        let _ = events.send(Input::PlayerClosed(stream_id));
    });
    PlayerHandle { inputs, wake }
}

thread_local! {
    static CLOSED: Cell<bool> = const { Cell::new(false) };
    static RESIZED: Cell<bool> = const { Cell::new(false) };
    static EXPOSED: Cell<bool> = const { Cell::new(false) };
}

extern "system" fn window_proc(window: HWND, message: u32, wparam: WPARAM, lparam: LPARAM) -> LRESULT {
    match message {
        WM_CLOSE => {
            CLOSED.set(true);
            LRESULT(0)
        }
        WM_SIZE => {
            RESIZED.set(true);
            LRESULT(0)
        }
        WM_PAINT => {
            unsafe {
                let _ = ValidateRect(Some(window), None);
            }
            EXPOSED.set(true);
            LRESULT(0)
        }
        WM_ERASEBKGND => LRESULT(1),
        _ => unsafe { DefWindowProcW(window, message, wparam, lparam) },
    }
}

fn open_window(config: &PlayerConfig) -> windows::core::Result<HWND> {
    static REGISTER: Once = Once::new();
    let instance = unsafe { GetModuleHandleW(None)? };
    REGISTER.call_once(|| unsafe {
        let class = WNDCLASSEXW {
            cbSize: std::mem::size_of::<WNDCLASSEXW>() as u32,
            lpfnWndProc: Some(window_proc),
            hInstance: instance.into(),
            hIcon: LoadIconW(Some(instance.into()), PCWSTR(1 as _)).unwrap_or_default(),
            hCursor: LoadCursorW(None, IDC_ARROW).unwrap_or_default(),
            hbrBackground: HBRUSH(GetStockObject(BLACK_BRUSH).0),
            lpszClassName: CLASS,
            ..Default::default()
        };
        RegisterClassExW(&class);
    });
    let dpi = unsafe { GetDpiForSystem() } as f64 / 96.0;
    let fit = (MAX_WIDTH / config.width as f64).min(MAX_HEIGHT / config.height as f64).min(1.0);
    let mut frame = RECT { left: 0, top: 0, right: (config.width as f64 * fit * dpi) as i32, bottom: (config.height as f64 * fit * dpi) as i32 };
    unsafe { AdjustWindowRectEx(&mut frame, WS_OVERLAPPEDWINDOW, false, WINDOW_EX_STYLE(0))? };
    let window = unsafe {
        CreateWindowExW(
            WINDOW_EX_STYLE(0),
            CLASS,
            &HSTRING::from(config.title.as_str()),
            WS_OVERLAPPEDWINDOW,
            CW_USEDEFAULT,
            CW_USEDEFAULT,
            frame.right - frame.left,
            frame.bottom - frame.top,
            None,
            None,
            Some(instance.into()),
            None,
        )?
    };
    unsafe {
        let _ = ShowWindow(window, SW_SHOWNORMAL);
    }
    Ok(window)
}

struct Screen {
    decoder: Decoder,
    renderer: Renderer,
    window: HWND,
    // Pictures before the first keyframe cannot be decoded, and one after an error waits for the next.
    waiting_for_keyframe: bool,
}

fn run(config: PlayerConfig, inputs: Receiver<PlayerInput>, wake: &Wake) -> windows::core::Result<()> {
    unsafe {
        let _ = CoInitializeEx(None, COINIT_MULTITHREADED);
    }
    let mut picture = if config.sound_only {
        None
    } else {
        let window = open_window(&config)?;
        let renderer = Renderer::new(window, config.alpha)?;
        let decoder = Decoder::new(Some(&renderer.device)).or_else(|_| Decoder::new(None))?;
        Some(Screen { decoder, renderer, window, waiting_for_keyframe: true })
    };
    let speaker = config.with_audio.then(|| Speaker::new(config.audio_buffer_ms));
    let mut sound = if config.with_audio { AacDecoder::new().ok() } else { None };
    let mut decoded: Vec<Picture> = Vec::new();
    let mut pcm: Vec<i16> = Vec::new();
    loop {
        unsafe { MsgWaitForMultipleObjects(Some(&[wake.0]), false, INFINITE, QS_ALLINPUT) };
        let mut message = MSG::default();
        while unsafe { PeekMessageW(&mut message, None, 0, 0, PM_REMOVE) }.as_bool() {
            unsafe {
                let _ = TranslateMessage(&message);
                DispatchMessageW(&message);
            }
        }
        if CLOSED.get() {
            break;
        }
        loop {
            match inputs.try_recv() {
                Ok(PlayerInput::Video { data, keyframe }) => {
                    let Some(shown) = picture.as_mut() else { continue };
                    if shown.waiting_for_keyframe && !keyframe {
                        continue;
                    }
                    shown.waiting_for_keyframe = false;
                    if shown.decoder.decode(&data, keyframe, &mut decoded).is_err() {
                        shown.waiting_for_keyframe = true;
                    }
                }
                Ok(PlayerInput::Audio(frame)) => {
                    if let (Some(decoder), Some(speaker)) = (sound.as_mut(), speaker.as_ref()) {
                        pcm.clear();
                        if decoder.decode(&frame, &mut pcm).is_ok() {
                            speaker.push(&pcm);
                        }
                    }
                }
                Ok(PlayerInput::Retitle(title)) => {
                    if let Some(shown) = &picture {
                        unsafe {
                            let _ = SetWindowTextW(shown.window, &HSTRING::from(title.as_str()));
                        }
                    }
                }
                Ok(PlayerInput::Resumed) => {
                    if let Some(speaker) = &speaker {
                        speaker.clear();
                    }
                }
                Ok(PlayerInput::Close) | Err(TryRecvError::Disconnected) => {
                    CLOSED.set(true);
                    break;
                }
                Err(TryRecvError::Empty) => break,
            }
        }
        if CLOSED.get() {
            break;
        }
        let Some(shown) = picture.as_mut() else { continue };
        let mut redraw = EXPOSED.replace(false);
        if RESIZED.replace(false) {
            let _ = shown.renderer.resize();
            redraw = true;
        }
        // Only the newest picture is drawn, so a burst after a stall does not pile up behind the display's refresh.
        if let Some(latest) = decoded.pop() {
            decoded.clear();
            if shown.renderer.upload(&latest).is_ok() {
                redraw = true;
            }
        }
        if redraw {
            let _ = shown.renderer.draw();
        }
    }
    if let Some(shown) = picture {
        unsafe {
            let _ = DestroyWindow(shown.window);
        }
    }
    Ok(())
}
