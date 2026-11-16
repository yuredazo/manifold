mod encode;
mod grab;
mod sound;

use super::{Captured, ShareKind, Shareable, StartRequest, emit};
use crate::system::x11::{self, MONITOR_TAG};
use encode::VideoEncoder;
use grab::{Grab, Grabber, Source};
use std::collections::HashMap;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicI32, Ordering};
use std::sync::mpsc::{Sender, channel};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

const MAX_FRAME_RATE: i32 = 60;
const START_TIMEOUT: Duration = Duration::from_secs(10);

pub struct Controls {
    stop: AtomicBool,
    bitrate_kbps: AtomicI32,
    keyframe: AtomicBool,
}

struct Session {
    controls: Arc<Controls>,
    threads: Vec<JoinHandle<()>>,
}

impl Drop for Session {
    fn drop(&mut self) {
        self.controls.stop.store(true, Ordering::Relaxed);
        for thread in self.threads.drain(..) {
            let _ = thread.join();
        }
    }
}

pub struct Backend {
    sessions: HashMap<i64, Session>,
}

impl Backend {
    pub fn new() -> Self {
        let _ = ffmpeg_next::init();
        ffmpeg_next::log::set_level(ffmpeg_next::log::Level::Error);
        Self { sessions: HashMap::new() }
    }

    pub fn list(&mut self, kind: ShareKind) -> Vec<Shareable> {
        let Some(desktop) = x11::shared() else { return vec![] };
        match kind {
            ShareKind::Display => desktop
                .monitors()
                .into_iter()
                .map(|monitor| Shareable {
                    handle: monitor.handle,
                    title: monitor.name.clone(),
                    process: String::new(),
                    window_class: String::new(),
                    width: monitor.rect.right - monitor.rect.left,
                    height: monitor.rect.bottom - monitor.rect.top,
                    kind,
                    primary: monitor.primary,
                    device: monitor.name,
                })
                .collect(),
            ShareKind::Window => desktop
                .windows()
                .into_iter()
                .map(|window| Shareable {
                    handle: window.id as i64,
                    title: window.title,
                    process: window.process,
                    window_class: window.class,
                    width: window.width,
                    height: window.height,
                    kind,
                    primary: false,
                    device: String::new(),
                })
                .collect(),
            ShareKind::Camera | ShareKind::Spout => vec![],
        }
    }

    pub fn start(&mut self, request: &StartRequest) -> bool {
        self.sessions.remove(&request.handle);
        if !request.picture && !request.audio {
            return false;
        }
        let epoch = Instant::now();
        let controls = Arc::new(Controls { stop: AtomicBool::new(false), bitrate_kbps: AtomicI32::new(0), keyframe: AtomicBool::new(false) });
        let mut session = Session { controls: controls.clone(), threads: Vec::new() };
        if request.picture {
            let (ready, started) = channel();
            let (request, controls) = (request.clone(), controls.clone());
            let Ok(thread) = std::thread::Builder::new().name("video capture".into()).spawn(move || video(request, epoch, controls, ready)) else { return false };
            session.threads.push(thread);
            if started.recv_timeout(START_TIMEOUT) != Ok(true) {
                return false;
            }
        }
        if request.audio {
            let (handle, controls) = (request.handle, controls.clone());
            if let Ok(thread) = std::thread::Builder::new().name("sound capture".into()).spawn(move || sound::run(handle, epoch, controls)) {
                session.threads.push(thread);
            }
        }
        self.sessions.insert(request.handle, session);
        true
    }

    pub fn stop(&mut self, handle: i64) {
        self.sessions.remove(&handle);
    }

    pub fn bitrate(&mut self, handle: i64, kbps: i32) {
        if let Some(session) = self.sessions.get(&handle) {
            session.controls.bitrate_kbps.store(kbps, Ordering::Relaxed);
        }
    }

    pub fn keyframe(&mut self, handle: i64) {
        if let Some(session) = self.sessions.get(&handle) {
            session.controls.keyframe.store(true, Ordering::Relaxed);
        }
    }
}

// Copies the picture at the frame rate rather than when it changes: X has no cheap way to say a frame is new, and a
// still picture costs the encoder almost nothing.
fn video(request: StartRequest, epoch: Instant, controls: Arc<Controls>, ready: Sender<bool>) {
    let source = if request.handle & MONITOR_TAG != 0 { Source::Display(request.handle) } else { Source::Window(request.handle as u32) };
    let frame_rate = request.fps.clamp(1, MAX_FRAME_RATE);
    let (width, height) = ((request.width.max(2) & !1) as u32, (request.height.max(2) & !1) as u32);
    let opened = Grabber::open(source, request.cursor).zip(VideoEncoder::open(width, height, frame_rate, request.bitrate_kbps));
    let _ = ready.send(opened.is_some());
    let Some((mut grabber, mut encoder)) = opened else {
        eprintln!("capture: cannot start {:#x}", request.handle);
        return;
    };
    let interval = Duration::from_secs(1) / frame_rate as u32;
    let mut next = Instant::now();
    let mut drawn = false;
    while !controls.stop.load(Ordering::Relaxed) {
        match grabber.grab() {
            Grab::Fresh(image) => {
                encoder.draw(&image);
                drawn = true;
            }
            Grab::Unchanged => {}
            Grab::Gone => {
                emit(Captured::Closed(request.handle));
                return;
            }
        }
        let kbps = controls.bitrate_kbps.swap(0, Ordering::Relaxed);
        if kbps > 0 {
            encoder.set_bitrate(kbps);
        }
        if drawn {
            let keyframe = controls.keyframe.swap(false, Ordering::Relaxed);
            let timestamp = epoch.elapsed().as_nanos() as i64 / 100;
            for frame in encoder.encode(timestamp, keyframe) {
                emit(Captured::Video { handle: request.handle, timestamp: frame.timestamp, keyframe: frame.keyframe, data: frame.data });
            }
        }
        next += interval;
        let now = Instant::now();
        if next > now {
            std::thread::sleep(next - now);
        } else {
            next = now;
        }
    }
}
