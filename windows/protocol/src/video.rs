use crate::session::MAX_PAYLOAD;
use std::cmp::Ordering;
use std::collections::{BTreeMap, HashMap};

pub const STREAM_ID_LENGTH: usize = 2;
pub const FRAGMENT_HEADER: usize = 13;
pub const MAX_CHUNK: usize = MAX_PAYLOAD - STREAM_ID_LENGTH - FRAGMENT_HEADER;
pub const MAX_FRAGMENTS: usize = 4096;
const FLAG_KEYFRAME: u8 = 1;

// One encoded frame, rebuilt from its fragments. timestamp counts 90 kHz ticks.
#[derive(Clone, PartialEq, Eq, Debug)]
pub struct Frame {
    pub id: i32,
    pub timestamp: i32,
    pub keyframe: bool,
    pub encoded: Vec<u8>,
}

pub fn encode_packet(stream_id: u16, fragment: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(STREAM_ID_LENGTH + fragment.len());
    out.extend_from_slice(&stream_id.to_be_bytes());
    out.extend_from_slice(fragment);
    out
}

pub fn stream_of(payload: &[u8]) -> Option<u16> {
    (payload.len() > STREAM_ID_LENGTH).then(|| u16::from_be_bytes([payload[0], payload[1]]))
}

pub fn fragment_of(payload: &[u8]) -> &[u8] {
    &payload[STREAM_ID_LENGTH..]
}

pub fn split(frame: &Frame) -> Vec<Vec<u8>> {
    let size = frame.encoded.len();
    let count = if size == 0 { 1 } else { size.div_ceil(MAX_CHUNK) };
    assert!(count <= MAX_FRAGMENTS, "a frame of {size} bytes is too large");
    (0..count)
        .map(|index| {
            let from = index * MAX_CHUNK;
            let to = (from + MAX_CHUNK).min(size);
            let mut out = Vec::with_capacity(FRAGMENT_HEADER + (to - from));
            out.extend_from_slice(&frame.id.to_be_bytes());
            out.extend_from_slice(&frame.timestamp.to_be_bytes());
            out.push(if frame.keyframe { FLAG_KEYFRAME } else { 0 });
            out.extend_from_slice(&(index as u16).to_be_bytes());
            out.extend_from_slice(&(count as u16).to_be_bytes());
            out.extend_from_slice(&frame.encoded[from..to]);
            out
        })
        .collect()
}

// Frame ids wrap around, so they compare by distance. Pending frames stay within a small window, which keeps this a consistent order.
#[derive(Clone, Copy, PartialEq, Eq, Hash, Debug)]
struct Seq(i32);

impl Ord for Seq {
    fn cmp(&self, other: &Self) -> Ordering {
        self.0.wrapping_sub(other.0).cmp(&0)
    }
}

impl PartialOrd for Seq {
    fn partial_cmp(&self, other: &Self) -> Option<Ordering> {
        Some(self.cmp(other))
    }
}

#[derive(Clone, PartialEq, Eq, Debug)]
pub struct Assembled {
    pub frame: Frame,
    pub assembly_time: i64,
}

#[derive(Clone, PartialEq, Eq, Debug)]
pub struct MissingFragments {
    pub frame_id: i32,
    pub indexes: Vec<u16>,
}

pub struct Poll {
    pub frames: Vec<Assembled>,
    pub missing: Vec<MissingFragments>,
}

// Far enough back that the time since it is large, yet far enough from the limit that subtracting cannot overflow.
const LONG_AGO: i64 = -(1 << 60);
const MAX_AHEAD: i32 = 120;
const MAX_REQUESTS: u32 = 6;
const MAX_KEYFRAME_REQUESTS: u32 = 14;

#[derive(Clone, Copy)]
struct Nacks {
    last_at: i64,
    count: u32,
}

impl Default for Nacks {
    fn default() -> Self {
        Self { last_at: LONG_AGO, count: 0 }
    }
}

struct Partial {
    id: i32,
    timestamp: i32,
    keyframe: bool,
    first_at: i64,
    last_at: i64,
    chunks: Vec<Option<Vec<u8>>>,
    received: usize,
    nacks: Nacks,
}

impl Partial {
    fn complete(&self) -> bool {
        self.received == self.chunks.len()
    }
}

// Times are in nanoseconds.
pub struct FrameBuffer {
    pending: BTreeMap<Seq, Partial>,
    whole_frame_nacks: HashMap<i32, Nacks>,
    next_id: i32,
    started: bool,
    pub max_wait: i64,
    pub keyframe_max_wait: i64,
    pub reorder_wait: i64,
    pub nack_interval: i64,
    pub needs_keyframe: bool,
    pub lost_frames: u64,
    pub requested_fragments: u64,
}

impl Default for FrameBuffer {
    fn default() -> Self {
        Self {
            pending: BTreeMap::new(),
            whole_frame_nacks: HashMap::new(),
            next_id: 0,
            started: false,
            max_wait: 50_000_000,
            keyframe_max_wait: 1_500_000_000,
            reorder_wait: 4_000_000,
            nack_interval: 15_000_000,
            needs_keyframe: true,
            lost_frames: 0,
            requested_fragments: 0,
        }
    }
}

impl FrameBuffer {
    pub fn add(&mut self, fragment: &[u8], now: i64) -> Vec<Assembled> {
        if fragment.len() < FRAGMENT_HEADER {
            return vec![];
        }
        let id = i32::from_be_bytes(fragment[0..4].try_into().unwrap());
        let timestamp = i32::from_be_bytes(fragment[4..8].try_into().unwrap());
        let flags = fragment[8];
        let index = u16::from_be_bytes([fragment[9], fragment[10]]) as usize;
        let count = u16::from_be_bytes([fragment[11], fragment[12]]) as usize;
        if count == 0 || count > MAX_FRAGMENTS || index >= count {
            return vec![];
        }
        if !self.started {
            self.started = true;
            self.next_id = id;
        }
        let ahead = id.wrapping_sub(self.next_id);
        if ahead < 0 {
            return vec![];
        }
        if ahead > MAX_AHEAD {
            self.restart_at(id);
        }
        let partial = self.pending.entry(Seq(id)).or_insert_with(|| Partial {
            id,
            timestamp,
            keyframe: flags & FLAG_KEYFRAME != 0,
            first_at: now,
            last_at: now,
            chunks: vec![None; count],
            received: 0,
            nacks: Nacks::default(),
        });
        if count != partial.chunks.len() || partial.chunks[index].is_some() {
            return vec![];
        }
        partial.chunks[index] = Some(fragment[FRAGMENT_HEADER..].to_vec());
        partial.received += 1;
        partial.last_at = now;
        self.whole_frame_nacks.remove(&id);
        self.release()
    }

    pub fn repairing_keyframe(&self) -> bool {
        self.pending.values().any(|partial| partial.keyframe && !partial.complete())
    }

    pub fn need_keyframe(&mut self) {
        self.needs_keyframe = true;
    }

    pub fn poll(&mut self, now: i64) -> Poll {
        let mut frames = Vec::new();
        if self.needs_keyframe {
            self.skip_to_keyframe();
            frames.extend(self.release());
        }
        while !self.pending.is_empty() {
            let head = self.pending.get(&Seq(self.next_id)).or_else(|| self.pending.values().next()).unwrap();
            // Giving up on a keyframe costs another one, the biggest frame there is, so it is waited for longer.
            let wait = if head.keyframe { self.max_wait.max(self.keyframe_max_wait) } else { self.max_wait };
            if now - head.first_at < wait {
                break;
            }
            self.pending.remove(&Seq(self.next_id));
            self.whole_frame_nacks.remove(&self.next_id);
            self.next_id = self.next_id.wrapping_add(1);
            self.lost_frames += 1;
            self.needs_keyframe = true;
            frames.extend(self.release());
        }
        let missing = self.missing_at(now);
        Poll { frames, missing }
    }

    fn skip_to_keyframe(&mut self) {
        let next = self.next_id;
        let Some(keyframe) = self.pending.iter().find(|(key, partial)| partial.keyframe && key.0 != next && key.0.wrapping_sub(next) > 0).map(|(key, _)| key.0) else {
            return;
        };
        while self.next_id != keyframe {
            self.pending.remove(&Seq(self.next_id));
            self.whole_frame_nacks.remove(&self.next_id);
            self.next_id = self.next_id.wrapping_add(1);
            self.lost_frames += 1;
        }
    }

    fn release(&mut self) -> Vec<Assembled> {
        let mut out = Vec::new();
        while self.pending.get(&Seq(self.next_id)).is_some_and(Partial::complete) {
            let head = self.pending.remove(&Seq(self.next_id)).unwrap();
            self.next_id = self.next_id.wrapping_add(1);
            if head.keyframe {
                self.needs_keyframe = false;
            }
            if self.needs_keyframe {
                continue;
            }
            let encoded = head.chunks.into_iter().flatten().flatten().collect();
            out.push(Assembled { frame: Frame { id: head.id, timestamp: head.timestamp, keyframe: head.keyframe, encoded }, assembly_time: head.last_at - head.first_at });
        }
        out
    }

    fn missing_at(&mut self, now: i64) -> Vec<MissingFragments> {
        let Some(newest) = self.pending.keys().next_back().map(|key| key.0) else { return vec![] };
        let mut missing = Vec::new();
        let mut id = self.next_id;
        while id.wrapping_sub(newest) <= 0 {
            let (reorder_wait, nack_interval) = (self.reorder_wait, self.nack_interval);
            if let Some(partial) = self.pending.get_mut(&Seq(id)) {
                if !partial.complete() && now - partial.last_at >= reorder_wait && due(&mut partial.nacks, now, nack_interval, partial.keyframe) {
                    let indexes: Vec<u16> = partial.chunks.iter().enumerate().filter(|(_, chunk)| chunk.is_none()).map(|(index, _)| index as u16).collect();
                    self.requested_fragments += indexes.len() as u64;
                    missing.push(MissingFragments { frame_id: id, indexes });
                }
            } else {
                let after_first_at = self.pending.range(Seq(id.wrapping_add(1))..).next().map(|(_, after)| after.first_at);
                let nacks = self.whole_frame_nacks.entry(id).or_default();
                if after_first_at.is_some_and(|first_at| now - first_at >= reorder_wait) && due(nacks, now, nack_interval, false) {
                    missing.push(MissingFragments { frame_id: id, indexes: vec![] });
                }
            }
            id = id.wrapping_add(1);
        }
        missing
    }

    fn restart_at(&mut self, id: i32) {
        self.lost_frames += self.pending.len() as u64;
        self.pending.clear();
        self.whole_frame_nacks.clear();
        self.next_id = id;
        self.needs_keyframe = true;
    }
}

// Each ask waits twice as long as the one before, so one bad stretch of radio does not use them all up. A keyframe is asked for
// at an even pace for as long as it is waited for, since nothing else can start the picture again.
fn due(nacks: &mut Nacks, now: i64, interval: i64, keyframe: bool) -> bool {
    let wait = if keyframe { interval } else { interval << (nacks.count.saturating_sub(1)).min(30) };
    let limit = if keyframe { MAX_KEYFRAME_REQUESTS } else { MAX_REQUESTS };
    if nacks.count >= limit || now - nacks.last_at < wait {
        return false;
    }
    nacks.last_at = now;
    nacks.count += 1;
    true
}
