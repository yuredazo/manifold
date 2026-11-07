use crate::hub::Input;
use std::ffi::{CStr, CString, c_char, c_void};
use std::sync::OnceLock;
use std::sync::mpsc::{Receiver, Sender, channel};

#[derive(Clone, Copy, PartialEq, Eq, Debug, Hash)]
pub enum ShareKind {
    Window,
    Display,
    Camera,
    Spout,
}

#[derive(Clone, Debug)]
pub struct Shareable {
    pub handle: i64,
    // The device name, such as \\.\DISPLAY1, for a display, the camera's name for a camera, and the sender's name for Spout.
    pub title: String,
    pub process: String,
    pub window_class: String,
    pub width: i32,
    pub height: i32,
    pub kind: ShareKind,
    pub primary: bool,
    pub device: String,
}

impl Shareable {
    pub fn picture_only(&self) -> bool {
        matches!(self.kind, ShareKind::Camera | ShareKind::Spout)
    }

    pub fn label(&self) -> String {
        if self.kind == ShareKind::Display { display_label(&self.title) } else { self.title.clone() }
    }
}

pub fn display_label(device_name: &str) -> String {
    let digits: String = device_name.chars().rev().take_while(char::is_ascii_digit).collect::<Vec<_>>().into_iter().rev().collect();
    if digits.is_empty() { "Display".into() } else { format!("Display {digits}") }
}

#[derive(Clone, Debug)]
pub struct StartRequest {
    pub handle: i64,
    pub width: i32,
    pub height: i32,
    pub bitrate_kbps: i32,
    pub audio: bool,
    pub picture: bool,
    pub cursor: bool,
    pub client_only: bool,
    pub alpha: bool,
    pub fps: i32,
    pub device: String,
}

pub enum Captured {
    // timestamp is in 100 ns units on the clock both capturers share.
    Video { handle: i64, timestamp: i64, keyframe: bool, data: Vec<u8> },
    Audio { handle: i64, timestamp: i64, data: Vec<u8> },
    Closed(i64),
    Started { handle: i64, ok: bool },
    Listed { purpose: ListPurpose, entries: Vec<Shareable> },
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum ListPurpose {
    Check,
    Picker,
}

enum Job {
    Start(StartRequest),
    Stop(i64),
    Bitrate(i64, i32),
    Keyframe(i64),
    List(Vec<ShareKind>, ListPurpose),
}

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

static OUT: OnceLock<Sender<Input>> = OnceLock::new();

fn emit(captured: Captured) {
    if let Some(out) = OUT.get() {
        let _ = out.send(Input::Captured(captured));
    }
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

fn run(jobs: Receiver<Job>) {
    unsafe {
        let _ = windows::Win32::System::Com::CoInitializeEx(None, windows::Win32::System::Com::COINIT_MULTITHREADED);
    }
    for job in jobs {
        match job {
            Job::Start(request) => {
                let ok = start(&request);
                emit(Captured::Started { handle: request.handle, ok });
            }
            Job::Stop(handle) => unsafe { capture_stop(handle) },
            Job::Bitrate(handle, kbps) => unsafe { capture_bitrate(handle, kbps) },
            Job::Keyframe(handle) => unsafe { capture_keyframe(handle) },
            Job::List(kinds, purpose) => {
                let entries = kinds.into_iter().flat_map(list).collect();
                emit(Captured::Listed { purpose, entries });
            }
        }
    }
}

// Every native call runs on one thread, in order, so a camera that takes a second to open never holds up the network.
pub struct Capture {
    jobs: Sender<Job>,
}

impl Capture {
    pub fn new(out: Sender<Input>) -> Self {
        let _ = OUT.set(out);
        unsafe { capture_set_sinks(on_video, on_audio, on_closed) };
        let (jobs, receiver) = channel();
        std::thread::Builder::new().name("capture".into()).spawn(move || run(receiver)).expect("capture thread");
        Self { jobs }
    }

    pub fn start(&self, request: StartRequest) {
        let _ = self.jobs.send(Job::Start(request));
    }

    pub fn stop(&self, handle: i64) {
        let _ = self.jobs.send(Job::Stop(handle));
    }

    pub fn bitrate(&self, handle: i64, kbps: i32) {
        let _ = self.jobs.send(Job::Bitrate(handle, kbps));
    }

    pub fn keyframe(&self, handle: i64) {
        let _ = self.jobs.send(Job::Keyframe(handle));
    }

    pub fn list(&self, kinds: Vec<ShareKind>, purpose: ListPurpose) {
        let _ = self.jobs.send(Job::List(kinds, purpose));
    }
}
