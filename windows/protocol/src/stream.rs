use std::collections::VecDeque;

// Recovered losses do not count: a radio loses packets at any bitrate.
pub struct RateControl {
    pub min_kbps: u32,
    pub max_kbps: u32,
    pub kbps: u32,
    clean_reports: u32,
    changed_at: i64,
}

const CLEAN_BELOW: f64 = 0.03;
const BACK_OFF_FACTOR: f64 = 0.85;
const BACK_OFF_EVERY_NS: i64 = 1_000_000_000;
const RAISE_EVERY_NS: i64 = 1_500_000_000;
const RAISE_FACTOR: f64 = 1.08;
const RAISE_STEP_KBPS: u32 = 50;
const CLEAN_REPORTS_TO_RAISE: u32 = 3;

impl RateControl {
    pub fn new(min_kbps: u32, max_kbps: u32) -> Self {
        Self { min_kbps, max_kbps, kbps: max_kbps, clean_reports: 0, changed_at: -(1 << 60) }
    }

    pub fn on_report(&mut self, fragments: u16, resend_requests: u16, lost_frames: u16, now: i64) -> Option<u32> {
        let requested = resend_requests as f64;
        let share = requested / ((fragments as f64 + requested).max(1.0));
        let before = self.kbps;
        if lost_frames > 0 {
            self.clean_reports = 0;
            if now - self.changed_at >= BACK_OFF_EVERY_NS {
                self.kbps = ((self.kbps as f64 * BACK_OFF_FACTOR) as u32).clamp(self.min_kbps, self.max_kbps);
                self.changed_at = now;
            }
        } else if share < CLEAN_BELOW {
            self.clean_reports += 1;
            if self.clean_reports >= CLEAN_REPORTS_TO_RAISE && now - self.changed_at >= RAISE_EVERY_NS {
                self.kbps = ((self.kbps as f64 * RAISE_FACTOR) as u32 + RAISE_STEP_KBPS).clamp(self.min_kbps, self.max_kbps);
                self.clean_reports = 0;
                self.changed_at = now;
            }
        }
        (self.kbps != before).then_some(self.kbps)
    }
}

struct Kept {
    frame_id: i32,
    stored_at: i64,
    fragments: Vec<Vec<u8>>,
    size: usize,
    answered: u32,
}

const MAX_ANSWERS: u32 = 6;

// A frame is answered only so often: a receiver that keeps asking is on a link that will not deliver it.
pub struct RetransmitStore {
    pub keep_for: i64,
    pub keep_bytes: usize,
    frames: VecDeque<Kept>,
    bytes: usize,
}

impl Default for RetransmitStore {
    fn default() -> Self {
        Self { keep_for: 1_000_000_000, keep_bytes: 4 << 20, frames: VecDeque::new(), bytes: 0 }
    }
}

impl RetransmitStore {
    pub fn remember(&mut self, frame_id: i32, fragments: Vec<Vec<u8>>, now: i64) {
        let size = fragments.iter().map(Vec::len).sum();
        self.frames.push_back(Kept { frame_id, stored_at: now, fragments, size, answered: 0 });
        self.bytes += size;
        while let Some(first) = self.frames.front() {
            if now - first.stored_at <= self.keep_for && self.bytes <= self.keep_bytes {
                break;
            }
            self.bytes -= first.size;
            self.frames.pop_front();
        }
    }

    pub fn fragments(&mut self, frame_id: i32, indexes: &[u16]) -> Vec<Vec<u8>> {
        let Some(kept) = self.frames.iter_mut().rev().find(|kept| kept.frame_id == frame_id) else { return vec![] };
        if kept.answered >= MAX_ANSWERS {
            return vec![];
        }
        kept.answered += 1;
        if indexes.is_empty() {
            return kept.fragments.clone();
        }
        indexes.iter().filter_map(|index| kept.fragments.get(*index as usize).cloned()).collect()
    }
}

const FIRST_WAIT_MS: i64 = 500;
const MAX_WAIT_MS: i64 = 2000;

pub struct KeyframeRequests {
    last_at: i64,
    asked: u32,
}

impl Default for KeyframeRequests {
    fn default() -> Self {
        Self { last_at: -(1 << 60), asked: 0 }
    }
}

impl KeyframeRequests {
    pub fn due(&mut self, now_ms: i64, needed: bool, repairing: bool) -> bool {
        if !needed {
            self.asked = 0;
            return false;
        }
        if repairing {
            return false;
        }
        let wait = if self.asked == 0 { 0 } else { (FIRST_WAIT_MS << (self.asked - 1).min(10)).min(MAX_WAIT_MS) };
        if now_ms - self.last_at < wait {
            return false;
        }
        self.last_at = now_ms;
        self.asked += 1;
        true
    }
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum StreamProfile {
    LowLatency,
    Balanced,
    Smooth,
}

impl StreamProfile {
    pub fn of(id: &str) -> Self {
        match id {
            "low" => Self::LowLatency,
            "smooth" => Self::Smooth,
            _ => Self::Balanced,
        }
    }

    pub fn id(self) -> &'static str {
        match self {
            Self::LowLatency => "low",
            Self::Balanced => "balanced",
            Self::Smooth => "smooth",
        }
    }

    pub fn hold_ms(self) -> u32 {
        match self {
            Self::LowLatency => 120,
            Self::Balanced => 300,
            Self::Smooth => 900,
        }
    }

    pub fn keyframe_wait_ms(self) -> u32 {
        match self {
            Self::LowLatency => 800,
            Self::Balanced => 1500,
            Self::Smooth => 2500,
        }
    }

    pub fn bitrate_percent(self) -> u32 {
        match self {
            Self::LowLatency => 70,
            Self::Balanced => 100,
            Self::Smooth => 140,
        }
    }
}
