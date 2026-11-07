use crate::capture::{Capture, ListPurpose, ShareKind, Shareable, StartRequest};
use crate::hub::{local_clock, now_ns};
use manifold_protocol::control::{Control, DEFAULT_FPS, FeedInfo, MAX_ALPHA_HEIGHT, MAX_ALPHA_WIDTH, MAX_NAME_LENGTH, Refusal, Subscribe};
use manifold_protocol::devices::Device;
use manifold_protocol::endpoint::Endpoint;
use manifold_protocol::stream::{RateControl, RetransmitStore};
use manifold_protocol::video::{self, Frame};
use serde_json::{Map, Value, json};
use std::collections::HashSet;

const MAX_STREAMS_PER_DEVICE: usize = 4;
const MAX_STREAMS: usize = 8;
const MIN_SIZE: i32 = 160;
const MAX_SIZE: i32 = 3840;
const KEYFRAME_COALESCE_NS: i64 = 400_000_000;
const MIN_BITRATE_KBPS: u32 = 500;
const MAX_BITRATE_KBPS: u32 = 20_000;
const FLOOR_BITRATE_KBPS: u32 = 200;
const CHECK_EVERY_MS: i64 = 2000;
const MAX_SAVED_SHARES: usize = 32;
const MAX_SAVED_TEXT: usize = 512;
const MAX_ACTIVITY: usize = 30;

pub const SHARE_FRAME_RATES: [i32; 4] = [24, DEFAULT_FPS as i32, 45, 60];

pub struct Watcher {
    pub device_key: String,
    pub stream_id: u16,
    wants_sound: bool,
    rate: RateControl,
}

impl Watcher {
    fn is_stream(&self, device_key: &str, stream_id: u16) -> bool {
        self.device_key == device_key && self.stream_id == stream_id
    }
}

pub struct SharedWindow {
    // What devices subscribe by. It is fixed when the window is shared, however the window's title changes after that.
    pub feed_name: String,
    pub shown_title: String,
    seen_title: String,
    // What the window was called when it was shared, kept so it still finds the window after the title changes.
    title: String,
    pub process: String,
    window_class: String,
    pub with_audio: bool,
    pub kind: ShareKind,
    pub sound_only: bool,
    pub show_cursor: bool,
    // Crops a window to its client area. Shares saved before the option existed keep their frame.
    pub hide_frame: bool,
    pub with_alpha: bool,
    pub frame_rate: i32,
    capturing_alpha: bool,
    // The same camera on another USB port has another id, so the name is the fallback when matching.
    device: String,
    pub handle: i64,
    pub width: i32,
    pub height: i32,
    pub watchers: Vec<Watcher>,
    store: RetransmitStore,
    capturing: bool,
    last_keyframe_at: i64,
    next_frame: i32,
}

impl SharedWindow {
    pub fn present(&self) -> bool {
        self.handle != 0
    }

    pub fn label(&self) -> &str {
        if self.shown_title.is_empty() { &self.feed_name } else { &self.shown_title }
    }

    fn is_watched_by(&self, device_key: &str, stream_id: u16) -> bool {
        self.watchers.iter().any(|watcher| watcher.is_stream(device_key, stream_id))
    }

    fn new(feed_name: String, title: String, process: String, window_class: String, with_audio: bool, kind: ShareKind) -> Self {
        Self {
            feed_name,
            shown_title: String::new(),
            seen_title: String::new(),
            title,
            process,
            window_class,
            with_audio,
            kind,
            sound_only: false,
            show_cursor: true,
            hide_frame: false,
            with_alpha: false,
            frame_rate: DEFAULT_FPS as i32,
            capturing_alpha: false,
            device: String::new(),
            handle: 0,
            width: 1280,
            height: 720,
            watchers: Vec::new(),
            store: RetransmitStore::default(),
            capturing: false,
            last_keyframe_at: -(1 << 60),
            next_frame: 0,
        }
    }
}

fn cut_utf16(text: &str, units: usize) -> String {
    let taken: Vec<u16> = text.encode_utf16().take(units).collect();
    String::from_utf16_lossy(&taken)
}

pub fn feed_label_for(window: &Shareable, sound_only: bool) -> String {
    // Every display plays the same sound, so one that is shared alone is named for the computer.
    if sound_only && window.kind == ShareKind::Display {
        return "PC sound".into();
    }
    const SUFFIX: &str = " (sound)";
    let room = MAX_NAME_LENGTH - 4 - if sound_only { SUFFIX.len() } else { 0 };
    let cleaned: String = window.label().chars().map(|c| if (c as u32) < 0x20 || c as u32 == 0x7F { ' ' } else { c }).collect();
    let mut base = cleaned.trim().to_string();
    if base.is_empty() {
        base = window.process.clone();
    }
    if base.encode_utf16().count() > room {
        base = cut_utf16(&base, room).trim_end().to_string();
    }
    if sound_only { format!("{base}{SUFFIX}") } else { base }
}

// Finds again a window that was shared before, the way OBS does: the same executable is required, then an exact title
// wins, and otherwise any window of the same class. The title alone would lose a browser window whenever the page changes.
fn find_window<'a>(share: &SharedWindow, candidates: &[&'a Shareable]) -> Option<&'a Shareable> {
    let same_app: Vec<&&Shareable> = candidates.iter().filter(|window| window.process.eq_ignore_ascii_case(&share.process)).collect();
    if let Some(window) = same_app.iter().find(|window| window.title == share.title) {
        return Some(window);
    }
    if share.window_class.is_empty() {
        return None;
    }
    same_app.into_iter().find(|window| window.window_class == share.window_class).copied()
}

fn find_share<'a>(share: &SharedWindow, open: &[&'a Shareable]) -> Option<&'a Shareable> {
    let candidates: Vec<&Shareable> = open.iter().filter(|entry| entry.kind == share.kind).copied().collect();
    match share.kind {
        ShareKind::Display | ShareKind::Spout => candidates.into_iter().find(|entry| entry.title == share.title),
        ShareKind::Camera => candidates
            .iter()
            .find(|entry| entry.device == share.device)
            .or_else(|| candidates.iter().find(|entry| entry.title == share.title))
            .copied(),
        ShareKind::Window => find_window(share, &candidates),
    }
}

fn encode_shares(shares: &[SharedWindow]) -> String {
    let list: Vec<Value> = shares
        .iter()
        .map(|window| {
            let mut entry = Map::new();
            entry.insert("name".into(), json!(window.feed_name));
            entry.insert("title".into(), json!(window.title));
            entry.insert("process".into(), json!(window.process));
            entry.insert("class".into(), json!(window.window_class));
            entry.insert("audio".into(), json!(window.with_audio));
            entry.insert("display".into(), json!(window.kind == ShareKind::Display));
            if window.kind == ShareKind::Camera {
                entry.insert("camera".into(), json!(true));
                entry.insert("device".into(), json!(window.device));
            }
            if window.kind == ShareKind::Spout {
                entry.insert("spout".into(), json!(true));
                if !window.with_alpha {
                    entry.insert("alpha".into(), json!(false));
                }
            }
            if window.sound_only {
                entry.insert("soundOnly".into(), json!(true));
            }
            if !window.show_cursor {
                entry.insert("cursor".into(), json!(false));
            }
            if window.kind == ShareKind::Window {
                entry.insert("titleBar".into(), json!(!window.hide_frame));
            }
            if window.frame_rate != DEFAULT_FPS as i32 {
                entry.insert("fps".into(), json!(window.frame_rate));
            }
            Value::Object(entry)
        })
        .collect();
    Value::Array(list).to_string()
}

// The file sits where other programs of the same user can write to it, so anything odd in it is skipped rather than trusted.
fn decode_shares(text: Option<&str>) -> Vec<SharedWindow> {
    let Some(Value::Array(entries)) = text.and_then(|text| serde_json::from_str::<Value>(text).ok()) else { return vec![] };
    let mut shares: Vec<SharedWindow> = Vec::new();
    for entry in entries.iter().filter_map(Value::as_object).take(MAX_SAVED_SHARES) {
        let field = |key: &str| entry.get(key).and_then(Value::as_str).map(str::to_string);
        let (Some(name), Some(title), Some(process), Some(window_class)) = (field("name"), field("title"), field("process"), field("class")) else {
            continue;
        };
        if [&name, &title, &process, &window_class].iter().any(|value| value.len() > MAX_SAVED_TEXT) || name.is_empty() {
            continue;
        }
        if shares.iter().any(|share| share.feed_name == name) {
            continue;
        }
        let flag = |key: &str| entry.get(key).and_then(Value::as_bool);
        let device = field("device");
        if device.as_ref().is_some_and(|device| device.len() > MAX_SAVED_TEXT) {
            continue;
        }
        let camera = flag("camera") == Some(true);
        let spout = !camera && flag("spout") == Some(true);
        let sound_only = flag("soundOnly") == Some(true) && !camera && !spout;
        let kind = if camera {
            ShareKind::Camera
        } else if spout {
            ShareKind::Spout
        } else if flag("display") == Some(true) {
            ShareKind::Display
        } else {
            ShareKind::Window
        };
        let fps = entry.get("fps").and_then(Value::as_i64).map(|fps| fps as i32).filter(|fps| SHARE_FRAME_RATES.contains(fps));
        let mut window = SharedWindow::new(name, title, process, window_class, (flag("audio") == Some(true) || sound_only) && !camera && !spout, kind);
        window.sound_only = sound_only;
        window.show_cursor = flag("cursor") != Some(false);
        window.hide_frame = kind == ShareKind::Window && flag("titleBar") == Some(false);
        window.with_alpha = spout && flag("alpha") != Some(false);
        window.frame_rate = if !camera && !sound_only { fps.unwrap_or(DEFAULT_FPS as i32) } else { DEFAULT_FPS as i32 };
        window.device = device.unwrap_or_default();
        shares.push(window);
    }
    shares
}

pub struct ShareOptions {
    pub audio: bool,
    pub sound_only: bool,
    pub show_cursor: bool,
    pub hide_frame: bool,
    pub alpha: bool,
    pub frame_rate: i32,
}

#[derive(Clone)]
pub struct ActivityEntry {
    pub time: String,
    pub device: String,
    pub feed: String,
    pub started: bool,
}

pub struct Sharing {
    pub shared: Vec<SharedWindow>,
    pub paused: bool,
    pub offer_cameras: bool,
    pub problem: Option<String>,
    pub activity: Vec<ActivityEntry>,
    capture: Capture,
    save: Box<dyn FnMut(&str) + Send>,
    last_check: i64,
    checking: bool,
    checked_kinds: Vec<ShareKind>,
    watched: HashSet<(String, String)>,
    pub feeds_changed: bool,
    pub changed: bool,
}

impl Sharing {
    pub fn new(capture: Capture, saved: Option<&str>, save: impl FnMut(&str) + Send + 'static) -> Self {
        Self {
            shared: decode_shares(saved),
            paused: false,
            offer_cameras: false,
            problem: None,
            activity: Vec::new(),
            capture,
            save: Box::new(save),
            last_check: -CHECK_EVERY_MS,
            checking: false,
            checked_kinds: Vec::new(),
            watched: HashSet::new(),
            feeds_changed: false,
            changed: false,
        }
    }

    fn touched(&mut self, endpoint: &Endpoint) {
        self.changed = true;
        let now: HashSet<(String, String)> =
            self.shared.iter().flat_map(|window| window.watchers.iter().map(|watcher| (watcher.device_key.clone(), window.feed_name.clone()))).collect();
        let name = |key: &str| endpoint.book().find(key).map(|device| device.name.clone()).unwrap_or_else(|| "A device".into());
        let started: Vec<(String, String)> = now.difference(&self.watched).cloned().collect();
        let stopped: Vec<(String, String)> = self.watched.difference(&now).cloned().collect();
        for (key, feed) in started {
            self.record(name(&key), feed, true);
        }
        for (key, feed) in stopped {
            self.record(name(&key), feed, false);
        }
        self.watched = now;
    }

    fn record(&mut self, device: String, feed: String, started: bool) {
        self.activity.insert(0, ActivityEntry { time: local_clock(), device, feed, started });
        self.activity.truncate(MAX_ACTIVITY);
    }

    fn allowed(window: &SharedWindow, device: &Device) -> bool {
        match window.kind {
            ShareKind::Camera => device.send_camera,
            ShareKind::Spout => device.send_spout,
            ShareKind::Window | ShareKind::Display => device.send,
        }
    }

    pub fn feeds_for(&self, device: &Device) -> Vec<FeedInfo> {
        self.shared
            .iter()
            .filter(|window| !self.paused && window.present() && Self::allowed(window, device))
            .map(|window| {
                if window.sound_only {
                    FeedInfo { name: window.feed_name.clone(), has_audio: true, sound_only: true, title: window.shown_title.clone(), ..Default::default() }
                } else {
                    FeedInfo {
                        name: window.feed_name.clone(),
                        width: window.width.clamp(0, u16::MAX as i32) as u16,
                        height: window.height.clamp(0, u16::MAX as i32) as u16,
                        fps: window.frame_rate as u8,
                        has_audio: window.with_audio,
                        has_alpha: window.with_alpha,
                        title: window.shown_title.clone(),
                        ..Default::default()
                    }
                }
            })
            .collect()
    }

    // A feed that does not exist is judged as a window, so asking for it is answered the same as before.
    fn permits(&self, device: &Device, feed: &str) -> bool {
        if self.paused {
            return false;
        }
        match self.named(feed) {
            Some(index) => Self::allowed(&self.shared[index], device),
            None => device.send,
        }
    }

    pub fn enforce(&mut self, endpoint: &Endpoint) {
        let devices: Vec<Device> = endpoint.book().devices().to_vec();
        let mut changed = false;
        for device in &devices {
            for index in 0..self.shared.len() {
                if Self::allowed(&self.shared[index], device) {
                    continue;
                }
                let before = self.shared[index].watchers.len();
                self.shared[index].watchers.retain(|watcher| watcher.device_key != device.public_key);
                if self.shared[index].watchers.len() != before {
                    changed = true;
                    self.stop_capture_if_idle(index);
                }
            }
        }
        if changed {
            self.touched(endpoint);
        }
    }

    pub fn set_paused(&mut self, paused: bool, endpoint: &Endpoint) {
        if paused == self.paused {
            return;
        }
        self.paused = paused;
        if paused {
            for index in 0..self.shared.len() {
                self.shared[index].watchers.clear();
                self.stop_capture(index);
            }
        }
        self.touched(endpoint);
        self.feeds_changed = true;
    }

    pub fn set_offer_cameras(&mut self, on: bool, endpoint: &Endpoint) {
        if on == self.offer_cameras {
            return;
        }
        self.offer_cameras = on;
        if !on {
            for index in 0..self.shared.len() {
                if self.shared[index].kind == ShareKind::Camera {
                    self.lose(index, endpoint);
                }
            }
        }
        self.changed = true;
    }

    pub fn list_for_picker(&self) {
        let mut kinds = vec![ShareKind::Display, ShareKind::Spout];
        if self.offer_cameras {
            kinds.push(ShareKind::Camera);
        }
        kinds.push(ShareKind::Window);
        self.capture.list(kinds, ListPurpose::Picker);
    }

    // A window that is being watched says so itself, but one that nobody watches is only noticed by asking.
    pub fn tick(&mut self, now_ms: i64, endpoint: &Endpoint) {
        if self.shared.is_empty() || self.checking || now_ms - self.last_check < CHECK_EVERY_MS {
            return;
        }
        self.last_check = now_ms;
        for index in 0..self.shared.len() {
            let window = &self.shared[index];
            if window.present() && window.kind == ShareKind::Window && !crate::system::window_alive(window.handle) {
                self.lose(index, endpoint);
            }
        }
        let mut kinds: Vec<ShareKind> = Vec::new();
        for window in &self.shared {
            if !kinds.contains(&window.kind) && (window.kind != ShareKind::Camera || self.offer_cameras) {
                kinds.push(window.kind);
            }
        }
        if kinds.is_empty() {
            return;
        }
        self.checking = true;
        self.checked_kinds = kinds.clone();
        self.capture.list(kinds, ListPurpose::Check);
    }

    pub fn on_listed(&mut self, entries: &[Shareable], endpoint: &Endpoint) {
        self.checking = false;
        let listed = std::mem::take(&mut self.checked_kinds);
        for index in 0..self.shared.len() {
            let window = &self.shared[index];
            if window.present() && window.kind != ShareKind::Window && listed.contains(&window.kind) && !entries.iter().any(|entry| entry.handle == window.handle) {
                self.lose(index, endpoint);
            }
        }
        let mut retitled = false;
        for window in self.shared.iter_mut().filter(|window| window.present() && window.kind == ShareKind::Window) {
            if let Some(entry) = entries.iter().find(|entry| entry.handle == window.handle) {
                retitled |= Self::retitle(window, entry);
            }
        }
        let mut found = false;
        let mut taken: HashSet<i64> = self.shared.iter().filter(|window| window.present()).map(|window| window.handle).collect();
        for window in self.shared.iter_mut().filter(|window| !window.present()) {
            let open: Vec<&Shareable> = entries.iter().filter(|entry| !taken.contains(&entry.handle)).collect();
            let Some(matched) = find_share(window, &open) else { continue };
            window.handle = matched.handle;
            window.device = matched.device.clone();
            window.width = matched.width;
            window.height = matched.height;
            if window.kind == ShareKind::Window {
                Self::retitle(window, matched);
            }
            taken.insert(matched.handle);
            found = true;
        }
        if retitled || found {
            self.changed = true;
            self.feeds_changed = true;
        }
    }

    // A browser changes its title with every page, so devices are told the new one while the feed keeps its name.
    fn retitle(window: &mut SharedWindow, entry: &Shareable) -> bool {
        let title = feed_label_for(entry, window.sound_only);
        if title == window.seen_title {
            return false;
        }
        window.seen_title = title.clone();
        let shown = if title == window.feed_name { String::new() } else { title };
        if shown == window.shown_title {
            return false;
        }
        window.shown_title = shown;
        true
    }

    fn lose(&mut self, index: usize, endpoint: &Endpoint) {
        if !self.shared[index].present() {
            return;
        }
        self.shared[index].watchers.clear();
        self.stop_capture(index);
        self.shared[index].handle = 0;
        self.touched(endpoint);
        self.feeds_changed = true;
    }

    pub fn share(&mut self, window: &Shareable, options: ShareOptions) {
        if self.by_handle(window.handle).is_some() {
            return;
        }
        let sound_alone = options.sound_only && !window.picture_only();
        let base = feed_label_for(window, sound_alone);
        let mut name = base.clone();
        let mut copy = 2;
        while self.named(&name).is_some() {
            name = format!("{base} ({copy})");
            copy += 1;
        }
        let mut entry = SharedWindow::new(
            name,
            window.title.clone(),
            window.process.clone(),
            window.window_class.clone(),
            (options.audio || sound_alone) && !window.picture_only(),
            window.kind,
        );
        entry.handle = window.handle;
        entry.width = window.width;
        entry.height = window.height;
        entry.sound_only = sound_alone;
        entry.show_cursor = options.show_cursor;
        entry.hide_frame = window.kind == ShareKind::Window && options.hide_frame;
        entry.with_alpha = window.kind == ShareKind::Spout && options.alpha;
        entry.frame_rate = if window.kind == ShareKind::Camera || sound_alone { DEFAULT_FPS as i32 } else { options.frame_rate };
        entry.device = window.device.clone();
        entry.seen_title = base;
        self.shared.push(entry);
        self.save_all();
        self.changed = true;
        self.feeds_changed = true;
    }

    pub fn unshare(&mut self, feed_name: &str, endpoint: &Endpoint) {
        let Some(index) = self.named(feed_name) else { return };
        self.shared[index].watchers.clear();
        self.stop_capture(index);
        self.shared.remove(index);
        self.save_all();
        self.touched(endpoint);
        self.feeds_changed = true;
    }

    pub fn on_subscribe(&mut self, device: &Device, request: &Subscribe, endpoint: &mut Endpoint) {
        let current = endpoint.book().find(&device.public_key).cloned();
        let refusal = match current {
            Some(current) if self.permits(&current, &request.feed) => self.subscribe(&device.public_key, request, endpoint),
            _ => Some(Refusal::NotShared),
        };
        if let Some(reason) = refusal {
            endpoint.send_to(&device.public_key, &Control::SubscribeRefused { stream_id: request.stream_id, reason });
        }
    }

    fn subscribe(&mut self, device_key: &str, request: &Subscribe, endpoint: &Endpoint) -> Option<Refusal> {
        let index = self.named(&request.feed).filter(|&index| !self.paused && self.shared[index].present());
        let Some(index) = index else { return Some(Refusal::NotFound) };
        let replacing = self.shared[index].is_watched_by(device_key, request.stream_id);
        let alpha = request.alpha && self.shared[index].with_alpha;
        if self.shared[index].capturing && self.shared[index].capturing_alpha != alpha {
            if self.shared[index].watchers.iter().any(|watcher| !watcher.is_stream(device_key, request.stream_id)) {
                return Some(Refusal::Busy);
            }
            self.stop_capture(index);
        }
        let all: Vec<&Watcher> = self.shared.iter().flat_map(|window| window.watchers.iter()).collect();
        if !replacing && (all.len() >= MAX_STREAMS || all.iter().filter(|watcher| watcher.device_key == device_key).count() >= MAX_STREAMS_PER_DEVICE) {
            return Some(Refusal::Busy);
        }
        let max_kbps = (request.bitrate_kbps as u32).clamp(MIN_BITRATE_KBPS, MAX_BITRATE_KBPS);
        let window = &mut self.shared[index];
        window.watchers.retain(|watcher| !watcher.is_stream(device_key, request.stream_id));
        window.watchers.push(Watcher {
            device_key: device_key.to_string(),
            stream_id: request.stream_id,
            wants_sound: request.audio && window.with_audio,
            rate: RateControl::new(FLOOR_BITRATE_KBPS, max_kbps),
        });
        if window.capturing {
            self.capture.keyframe(window.handle);
            self.touched(endpoint);
            return None;
        }
        window.capturing = true;
        window.capturing_alpha = alpha;
        let (max_width, max_height) = if alpha { (MAX_ALPHA_WIDTH as i32, MAX_ALPHA_HEIGHT as i32) } else { (MAX_SIZE, MAX_SIZE) };
        let device = match window.kind {
            ShareKind::Camera => window.device.clone(),
            ShareKind::Spout => window.title.clone(),
            _ => String::new(),
        };
        self.capture.start(StartRequest {
            handle: window.handle,
            width: (request.width as i32).clamp(MIN_SIZE, max_width),
            height: (request.height as i32).clamp(MIN_SIZE, max_height),
            bitrate_kbps: max_kbps as i32,
            audio: window.with_audio,
            picture: !window.sound_only,
            cursor: window.show_cursor,
            client_only: window.hide_frame,
            alpha,
            fps: window.frame_rate,
            device,
        });
        self.touched(endpoint);
        None
    }

    pub fn on_started(&mut self, handle: i64, ok: bool, endpoint: &mut Endpoint) {
        if ok {
            return;
        }
        let Some(index) = self.by_handle(handle) else { return };
        let window = &mut self.shared[index];
        if !window.capturing {
            return;
        }
        window.capturing = false;
        for watcher in window.watchers.drain(..) {
            endpoint.send_to(&watcher.device_key, &Control::SubscribeRefused { stream_id: watcher.stream_id, reason: Refusal::Failed });
        }
        let feed = &window.feed_name;
        self.problem = Some(match window.kind {
            ShareKind::Camera => {
                format!("Windows would not open \"{feed}\". Another app may be using it, or camera access for desktop apps is off in Windows settings.")
            }
            ShareKind::Spout => format!("Spout would not hand over \"{feed}\". The program may have stopped sending, or it draws on another graphics card."),
            _ => format!("Windows would not let \"{feed}\" be captured. It may be protected, or minimized to nothing."),
        });
        self.touched(endpoint);
    }

    pub fn on_closed(&mut self, handle: i64, endpoint: &Endpoint) {
        if let Some(index) = self.by_handle(handle) {
            self.lose(index, endpoint);
        }
    }

    pub fn unsubscribe(&mut self, device_key: &str, stream_id: u16, endpoint: &Endpoint) {
        let mut changed = false;
        for index in 0..self.shared.len() {
            let before = self.shared[index].watchers.len();
            self.shared[index].watchers.retain(|watcher| !watcher.is_stream(device_key, stream_id));
            if self.shared[index].watchers.len() != before {
                self.stop_capture_if_idle(index);
                changed = true;
            }
        }
        if changed {
            self.touched(endpoint);
        }
    }

    pub fn resend(&mut self, device_key: &str, stream_id: u16, frame_id: i32, indexes: &[u16], endpoint: &mut Endpoint) {
        for window in &mut self.shared {
            if !window.is_watched_by(device_key, stream_id) {
                continue;
            }
            let fragments = window.store.fragments(frame_id, indexes);
            if !fragments.is_empty() {
                endpoint.send_video(device_key, stream_id, &fragments);
            }
        }
    }

    pub fn on_report(&mut self, device_key: &str, stream_id: u16, fragments: u16, resend_requests: u16, lost_frames: u16) {
        let now = now_ns();
        for window in &mut self.shared {
            let mut lowered = false;
            for watcher in window.watchers.iter_mut().filter(|watcher| watcher.is_stream(device_key, stream_id)) {
                lowered |= watcher.rate.on_report(fragments, resend_requests, lost_frames, now).is_some();
            }
            // One encoder serves every watcher of a window, so it has to suit the one with the poorest link.
            if lowered && window.capturing {
                let kbps = window.watchers.iter().map(|watcher| watcher.rate.kbps).min().unwrap_or(FLOOR_BITRATE_KBPS);
                self.capture.bitrate(window.handle, kbps as i32);
            }
        }
    }

    // A second request while the first keyframe is still on its way would only make another one.
    pub fn request_keyframe(&mut self, device_key: &str, stream_id: u16) {
        let now = now_ns();
        for window in &mut self.shared {
            if !window.is_watched_by(device_key, stream_id) || now - window.last_keyframe_at < KEYFRAME_COALESCE_NS {
                continue;
            }
            window.last_keyframe_at = now;
            self.capture.keyframe(window.handle);
        }
    }

    pub fn drop_device(&mut self, device_key: &str, endpoint: &Endpoint) {
        let mut changed = false;
        for index in 0..self.shared.len() {
            let before = self.shared[index].watchers.len();
            self.shared[index].watchers.retain(|watcher| watcher.device_key != device_key);
            if self.shared[index].watchers.len() != before {
                changed = true;
                self.stop_capture_if_idle(index);
            }
        }
        if changed {
            self.touched(endpoint);
        }
    }

    pub fn drop_all(&mut self, endpoint: &Endpoint) {
        for index in 0..self.shared.len() {
            self.shared[index].watchers.clear();
            self.stop_capture(index);
        }
        self.touched(endpoint);
    }

    pub fn on_video(&mut self, handle: i64, timestamp: i64, keyframe: bool, encoded: Vec<u8>, endpoint: &mut Endpoint) {
        let Some(index) = self.by_handle(handle) else { return };
        let window = &mut self.shared[index];
        if window.watchers.is_empty() {
            return;
        }
        let id = window.next_frame;
        window.next_frame = id.wrapping_add(1);
        let fragments = video::split(&Frame { id, timestamp: ticks(timestamp), keyframe, encoded });
        for watcher in &window.watchers {
            endpoint.send_video(&watcher.device_key, watcher.stream_id, &fragments);
        }
        window.store.remember(id, fragments, now_ns());
    }

    pub fn on_audio(&mut self, handle: i64, timestamp: i64, frame: &[u8], endpoint: &mut Endpoint) {
        let Some(index) = self.by_handle(handle) else { return };
        for watcher in self.shared[index].watchers.iter().filter(|watcher| watcher.wants_sound) {
            endpoint.send_audio(&watcher.device_key, watcher.stream_id, ticks(timestamp), frame);
        }
    }

    pub fn dismiss_problem(&mut self) {
        self.problem = None;
        self.changed = true;
    }

    fn named(&self, name: &str) -> Option<usize> {
        self.shared.iter().position(|window| window.feed_name == name)
    }

    fn by_handle(&self, handle: i64) -> Option<usize> {
        self.shared.iter().position(|window| window.present() && window.handle == handle)
    }

    fn save_all(&mut self) {
        let text = encode_shares(&self.shared);
        (self.save)(&text);
    }

    fn stop_capture_if_idle(&mut self, index: usize) {
        if self.shared[index].watchers.is_empty() {
            self.stop_capture(index);
        }
    }

    fn stop_capture(&mut self, index: usize) {
        let window = &mut self.shared[index];
        if !window.capturing {
            return;
        }
        window.capturing = false;
        self.capture.stop(window.handle);
    }
}

// The capturers count in 100 ns, the wire in 90 kHz.
fn ticks(timestamp: i64) -> i32 {
    (timestamp * 9 / 1000) as i32
}
