#[cfg(target_os = "linux")]
mod linux;
#[cfg(windows)]
mod windows;

#[cfg(target_os = "linux")]
use self::linux::Backend;
#[cfg(windows)]
use self::windows::Backend;

use crate::hub::Input;
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
        if self.kind == ShareKind::Display && cfg!(windows) { display_label(&self.title) } else { self.title.clone() }
    }
}

pub fn display_label(device_name: &str) -> String {
    let digits: String = device_name.chars().rev().take_while(char::is_ascii_digit).collect::<Vec<_>>().into_iter().rev().collect();
    if digits.is_empty() { "Display".into() } else { format!("Display {digits}") }
}

#[derive(Clone, Debug)]
#[cfg_attr(not(windows), allow(dead_code))]
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

static OUT: OnceLock<Sender<Input>> = OnceLock::new();

fn emit(captured: Captured) {
    if let Some(out) = OUT.get() {
        let _ = out.send(Input::Captured(captured));
    }
}

fn run(jobs: Receiver<Job>) {
    let mut backend = Backend::new();
    for job in jobs {
        match job {
            Job::Start(request) => {
                let ok = backend.start(&request);
                emit(Captured::Started { handle: request.handle, ok });
            }
            Job::Stop(handle) => backend.stop(handle),
            Job::Bitrate(handle, kbps) => backend.bitrate(handle, kbps),
            Job::Keyframe(handle) => backend.keyframe(handle),
            Job::List(kinds, purpose) => {
                let entries = kinds.into_iter().flat_map(|kind| backend.list(kind)).collect();
                emit(Captured::Listed { purpose, entries });
            }
        }
    }
}

// Every capture call runs on one thread, in order, so a camera that takes a second to open never holds up the network.
pub struct Capture {
    jobs: Sender<Job>,
}

impl Capture {
    pub fn new(out: Sender<Input>) -> Self {
        let _ = OUT.set(out);
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
