use crate::hub::{Input, now_ns};
use crate::player::{self, PlayerConfig, PlayerHandle, PlayerInput};
use manifold_protocol::control::{Control, DEFAULT_FPS, FeedInfo, MAX_ALPHA_HEIGHT, MAX_ALPHA_WIDTH, MAX_DIMENSION, MAX_FPS, Subscribe};
use manifold_protocol::devices::Device;
use manifold_protocol::endpoint::Endpoint;
use manifold_protocol::stream::{KeyframeRequests, StreamProfile};
use manifold_protocol::video::FrameBuffer;
use std::sync::mpsc::Sender;

const REPORT_EVERY_NS: i64 = 500_000_000;
const UNKNOWN_RTT_MS: f64 = 20.0;
// Long enough to ride out a radio stall of about 150 ms.
const MIN_NACK_INTERVAL_NS: i64 = 10_000_000;

pub struct ViewerSession {
    pub stream_id: u16,
    pub device_key: String,
    pub feed_name: String,
    label: String,
    device_name: String,
    sound_only: bool,
    with_audio: bool,
    buffer: FrameBuffer,
    keyframes: KeyframeRequests,
    player: PlayerHandle,
    pub frames: u64,
    report_start: i64,
    report_fragments: u32,
    report_resends: u32,
    report_lost_base: u64,
}

pub struct Watching {
    pub sessions: Vec<ViewerSession>,
    last_stream_id: u16,
    events: Sender<Input>,
    pub changed: bool,
}

fn saturate(value: u64) -> u16 {
    value.min(u16::MAX as u64) as u16
}

impl Watching {
    pub fn new(events: Sender<Input>) -> Self {
        Self { sessions: Vec::new(), last_stream_id: 0, events, changed: false }
    }

    pub fn of(&self, device_key: &str, feed: &str) -> Option<&ViewerSession> {
        self.sessions.iter().find(|session| session.device_key == device_key && session.feed_name == feed)
    }

    pub fn watch(&mut self, device: &Device, feed: &FeedInfo, profile: StreamProfile, endpoint: &mut Endpoint) {
        if self.of(&device.public_key, &feed.name).is_some() {
            return;
        }
        let alpha = feed.has_alpha && !feed.sound_only;
        let (max_width, max_height) = if alpha { (MAX_ALPHA_WIDTH, MAX_ALPHA_HEIGHT) } else { (MAX_DIMENSION, MAX_DIMENSION) };
        let width = if feed.width > 0 { feed.width } else { 1280 }.clamp(1, max_width);
        let height = if feed.height > 0 { feed.height } else { 720 }.clamp(1, max_height);
        let fps = if feed.fps > 0 { feed.fps.clamp(1, MAX_FPS) } else { DEFAULT_FPS };
        let pixels = width as u64 * height as u64 * if alpha { 2 } else { 1 } * fps as u64;
        let kbps = (pixels * 7 / 100 / 1000 * profile.bitrate_percent() as u64 / 100).clamp(500, 20_000) as u16;
        self.last_stream_id = self.last_stream_id % 0xFFFF + 1;
        let stream_id = self.last_stream_id;
        let title = format!("{} · {}", feed.label(), device.name);
        let player = player::spawn(
            PlayerConfig {
                stream_id,
                title,
                width: width as u32,
                height: height as u32,
                alpha,
                sound_only: feed.sound_only,
                with_audio: feed.has_audio,
                audio_buffer_ms: audio_buffer_ms(profile),
            },
            self.events.clone(),
        );
        let mut buffer = FrameBuffer::default();
        buffer.max_wait = profile.hold_ms() as i64 * 1_000_000;
        buffer.keyframe_max_wait = profile.keyframe_wait_ms() as i64 * 1_000_000;
        self.sessions.push(ViewerSession {
            stream_id,
            device_key: device.public_key.clone(),
            feed_name: feed.name.clone(),
            label: feed.label().to_string(),
            device_name: device.name.clone(),
            sound_only: feed.sound_only,
            with_audio: feed.has_audio,
            buffer,
            keyframes: KeyframeRequests::default(),
            player,
            frames: 0,
            report_start: now_ns(),
            report_fragments: 0,
            report_resends: 0,
            report_lost_base: 0,
        });
        let request = Subscribe { stream_id, feed: feed.name.clone(), width, height, bitrate_kbps: kbps, audio: feed.has_audio, fps, alpha };
        endpoint.send_to(&device.public_key, &Control::Subscribe(request));
        if !feed.sound_only {
            endpoint.send_to(&device.public_key, &Control::KeyframeRequest(stream_id));
        }
        self.changed = true;
    }

    pub fn stop(&mut self, stream_id: u16, endpoint: &mut Endpoint) {
        let Some(index) = self.sessions.iter().position(|session| session.stream_id == stream_id) else { return };
        let session = self.sessions.remove(index);
        endpoint.send_to(&session.device_key, &Control::Unsubscribe(stream_id));
        self.changed = true;
    }

    pub fn stop_where(&mut self, endpoint: &mut Endpoint, test: impl Fn(&ViewerSession) -> bool) {
        let ids: Vec<u16> = self.sessions.iter().filter(|session| test(session)).map(|session| session.stream_id).collect();
        for id in ids {
            self.stop(id, endpoint);
        }
    }

    pub fn wants_fast_poll(&self) -> bool {
        !self.sessions.is_empty()
    }

    pub fn on_video(&mut self, device: &Device, stream_id: u16, fragment: &[u8]) {
        let Some(session) = self.sessions.iter_mut().find(|session| session.stream_id == stream_id && session.device_key == device.public_key) else {
            return;
        };
        session.report_fragments += 1;
        let assembled = session.buffer.add(fragment, now_ns());
        let first = session.frames == 0;
        for frame in assembled {
            session.player.send(PlayerInput::Video { data: frame.frame.encoded, keyframe: frame.frame.keyframe });
            session.frames += 1;
        }
        self.changed |= first && session.frames > 0;
    }

    pub fn on_audio(&mut self, device: &Device, stream_id: u16, frame: Vec<u8>) {
        let Some(session) = self.sessions.iter_mut().find(|session| session.stream_id == stream_id && session.device_key == device.public_key) else {
            return;
        };
        if !session.with_audio {
            return;
        }
        session.player.send(PlayerInput::Audio(frame));
        if session.sound_only && session.frames == 0 {
            session.frames = 1;
            self.changed = true;
        }
    }

    // Called every few milliseconds because a missing piece is only waited for a few frames.
    pub fn poll(&mut self, now: i64, profile: StreamProfile, endpoint: &mut Endpoint) {
        let mut first_frames = false;
        for session in &mut self.sessions {
            let round_trip = endpoint.rtt_ms(&session.device_key).unwrap_or(UNKNOWN_RTT_MS);
            session.buffer.max_wait = profile.hold_ms() as i64 * 1_000_000;
            session.buffer.keyframe_max_wait = profile.keyframe_wait_ms() as i64 * 1_000_000;
            session.buffer.nack_interval = ((round_trip * 1_000_000.0) as i64 * 13 / 10).max(MIN_NACK_INTERVAL_NS);
            let result = session.buffer.poll(now);
            for missing in result.missing {
                session.report_resends += if missing.indexes.is_empty() { 1 } else { missing.indexes.len() as u32 };
                endpoint.send_to(&session.device_key, &Control::Nack { stream_id: session.stream_id, frame_id: missing.frame_id, indexes: missing.indexes });
            }
            let had_frames = session.frames > 0;
            for frame in result.frames {
                session.player.send(PlayerInput::Video { data: frame.frame.encoded, keyframe: frame.frame.keyframe });
                session.frames += 1;
            }
            first_frames |= !had_frames && session.frames > 0;
            if now - session.report_start >= REPORT_EVERY_NS {
                let report = Control::StreamReport {
                    stream_id: session.stream_id,
                    window_ms: saturate(((now - session.report_start) / 1_000_000) as u64),
                    fragments: saturate(session.report_fragments as u64),
                    resend_requests: saturate(session.report_resends as u64),
                    lost_frames: saturate(session.buffer.lost_frames - session.report_lost_base),
                    jitter_ms10: 0,
                };
                endpoint.send_to(&session.device_key, &report);
                session.report_start = now;
                session.report_fragments = 0;
                session.report_resends = 0;
                session.report_lost_base = session.buffer.lost_frames;
            }
        }
        self.changed |= first_frames;
    }

    // The first request may be lost, so it is repeated while the picture waits.
    pub fn tick(&mut self, now_ms: i64, endpoint: &mut Endpoint) {
        for session in &mut self.sessions {
            if session.sound_only {
                continue;
            }
            let needed = session.buffer.needs_keyframe;
            let repairing = session.buffer.repairing_keyframe();
            if session.keyframes.due(now_ms, needed, repairing) {
                endpoint.send_to(&session.device_key, &Control::KeyframeRequest(session.stream_id));
            }
        }
    }

    pub fn on_link_resumed(&mut self, device: &Device) {
        for session in self.sessions.iter().filter(|session| session.device_key == device.public_key) {
            session.player.send(PlayerInput::Resumed);
        }
    }

    pub fn on_feeds(&mut self, device: &Device, feeds: &[FeedInfo], endpoint: &mut Endpoint) {
        self.stop_where(endpoint, |session| session.device_key == device.public_key && !feeds.iter().any(|feed| feed.name == session.feed_name));
        for session in self.sessions.iter_mut().filter(|session| session.device_key == device.public_key) {
            let Some(feed) = feeds.iter().find(|feed| feed.name == session.feed_name) else { continue };
            if feed.label() != session.label {
                session.label = feed.label().to_string();
                session.player.send(PlayerInput::Retitle(format!("{} · {}", session.label, session.device_name)));
            }
        }
    }

    pub fn on_player_closed(&mut self, stream_id: u16, endpoint: &mut Endpoint) {
        self.stop(stream_id, endpoint);
    }
}

// Sound has no frame buffer of its own, so its jitter buffer follows the same delay setting.
fn audio_buffer_ms(profile: StreamProfile) -> usize {
    match profile {
        StreamProfile::LowLatency => 60,
        StreamProfile::Balanced => 120,
        StreamProfile::Smooth => 300,
    }
}
