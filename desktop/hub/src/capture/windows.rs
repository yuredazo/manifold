use super::{Captured, ShareKind, Shareable, StartRequest, emit};
use std::ffi::{CStr, CString, c_char, c_void};

#[repr(C)]
struct RawEntry {
    handle: i64,
    title: *const c_char,
    process: *const c_char,
    window_class: *const c_char,
    device: *const c_char,
    width: i32,
    height: i32,
    primary: i32,
}

#[repr(C)]
struct RawStart {
    handle: i64,
    width: i32,
    height: i32,
    bitrate_kbps: i32,
    audio: i32,
    picture: i32,
    cursor: i32,
    client_only: i32,
    alpha: i32,
    fps: i32,
    device: *const c_char,
}

type EntrySink = extern "C" fn(*mut c_void, *const RawEntry);

unsafe extern "C" {
    fn capture_set_sinks(
        video: extern "C" fn(i64, i64, i32, *const u8, usize),
        audio: extern "C" fn(i64, i64, *const u8, usize),
        closed: extern "C" fn(i64),
    );
    fn capture_list_windows(sink: EntrySink, context: *mut c_void);
    fn capture_list_displays(sink: EntrySink, context: *mut c_void);
    fn capture_list_cameras(sink: EntrySink, context: *mut c_void);
    fn capture_list_spouts(sink: EntrySink, context: *mut c_void);
    fn capture_start(request: *const RawStart) -> i32;
    fn capture_stop(handle: i64);
    fn capture_bitrate(handle: i64, kbps: i32);
    fn capture_keyframe(handle: i64);
}

extern "C" fn on_video(handle: i64, timestamp: i64, keyframe: i32, data: *const u8, size: usize) {
    let data = unsafe { std::slice::from_raw_parts(data, size) }.to_vec();
    emit(Captured::Video { handle, timestamp, keyframe: keyframe != 0, data });
}

extern "C" fn on_audio(handle: i64, timestamp: i64, data: *const u8, size: usize) {
    let data = unsafe { std::slice::from_raw_parts(data, size) }.to_vec();
    emit(Captured::Audio { handle, timestamp, data });
}

extern "C" fn on_closed(handle: i64) {
    emit(Captured::Closed(handle));
}

fn text(pointer: *const c_char) -> String {
    if pointer.is_null() { String::new() } else { unsafe { CStr::from_ptr(pointer) }.to_string_lossy().into_owned() }
}

struct Collecting {
    kind: ShareKind,
    found: Vec<Shareable>,
}

extern "C" fn collect(context: *mut c_void, entry: *const RawEntry) {
    let collecting = unsafe { &mut *(context as *mut Collecting) };
    let entry = unsafe { &*entry };
    collecting.found.push(Shareable {
        handle: entry.handle,
        title: text(entry.title),
        process: text(entry.process),
        window_class: text(entry.window_class),
        width: entry.width,
        height: entry.height,
        kind: collecting.kind,
        primary: entry.primary != 0,
        device: text(entry.device),
    });
}

fn list(kind: ShareKind) -> Vec<Shareable> {
    let mut collecting = Collecting { kind, found: Vec::new() };
    let context = &mut collecting as *mut Collecting as *mut c_void;
    unsafe {
        match kind {
            ShareKind::Window => capture_list_windows(collect, context),
            ShareKind::Display => capture_list_displays(collect, context),
            ShareKind::Camera => capture_list_cameras(collect, context),
            ShareKind::Spout => capture_list_spouts(collect, context),
        }
    }
    collecting.found
}

fn start(request: &StartRequest) -> bool {
    let device = CString::new(request.device.replace('\0', "")).unwrap_or_default();
    let raw = RawStart {
        handle: request.handle,
        width: request.width,
        height: request.height,
        bitrate_kbps: request.bitrate_kbps,
        audio: request.audio as i32,
        picture: request.picture as i32,
        cursor: request.cursor as i32,
        client_only: request.client_only as i32,
        alpha: request.alpha as i32,
        fps: request.fps,
        device: device.as_ptr(),
    };
    unsafe { capture_start(&raw) != 0 }
}

pub struct Backend;

impl Backend {
    pub fn new() -> Self {
        unsafe {
            let _ = ::windows::Win32::System::Com::CoInitializeEx(None, ::windows::Win32::System::Com::COINIT_MULTITHREADED);
            capture_set_sinks(on_video, on_audio, on_closed);
        }
        Self
    }

    pub fn list(&mut self, kind: ShareKind) -> Vec<Shareable> {
        list(kind)
    }

    pub fn start(&mut self, request: &StartRequest) -> bool {
        start(request)
    }

    pub fn stop(&mut self, handle: i64) {
        unsafe { capture_stop(handle) }
    }

    pub fn bitrate(&mut self, handle: i64, kbps: i32) {
        unsafe { capture_bitrate(handle, kbps) }
    }

    pub fn keyframe(&mut self, handle: i64) {
        unsafe { capture_keyframe(handle) }
    }
}
