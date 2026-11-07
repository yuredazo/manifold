use crate::crypto::{TAG_LENGTH, open, seal};
use crate::noise::TransportKeys;

pub const TYPE_DATA: u8 = 3;
pub const HEADER_LENGTH: usize = 13;
pub const MAX_DATAGRAM: usize = 1200;
pub const MAX_PAYLOAD: usize = MAX_DATAGRAM - HEADER_LENGTH - TAG_LENGTH - 1;
const REKEY_LIMIT: u64 = 1 << 40;

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum StreamKind {
    Control = 1,
    Video = 2,
    Audio = 3,
}

impl StreamKind {
    fn of(id: u8) -> Option<Self> {
        match id {
            1 => Some(Self::Control),
            2 => Some(Self::Video),
            3 => Some(Self::Audio),
            _ => None,
        }
    }
}

// Remembers which packet counters have been seen, so a recorded packet cannot be played back.
pub struct ReplayWindow {
    seen: Vec<bool>,
    newest: Option<u64>,
}

impl ReplayWindow {
    pub fn new(size: usize) -> Self {
        Self { seen: vec![false; size], newest: None }
    }

    pub fn accept(&mut self, counter: u64) -> bool {
        let size = self.seen.len() as u64;
        let slot = (counter % size) as usize;
        match self.newest {
            Some(newest) if counter <= newest => {
                if newest - counter >= size || self.seen[slot] {
                    return false;
                }
                self.seen[slot] = true;
                true
            }
            newest => {
                let first = newest.map_or(0, |it| it + 1);
                if counter - first >= size {
                    self.seen.fill(false);
                } else {
                    for c in first..=counter {
                        self.seen[(c % size) as usize] = false;
                    }
                }
                self.newest = Some(counter);
                self.seen[slot] = true;
                true
            }
        }
    }
}

// The counter is the nonce and must never repeat, so sealing stops long before it could run out.
pub struct Session {
    pub local_index: i32,
    pub remote_index: i32,
    keys: TransportKeys,
    send_counter: u64,
    window: ReplayWindow,
}

impl Session {
    pub fn new(local_index: i32, remote_index: i32, keys: TransportKeys) -> Self {
        Self { local_index, remote_index, keys, send_counter: 0, window: ReplayWindow::new(1024) }
    }

    pub fn seal(&mut self, stream: StreamKind, payload: &[u8]) -> Vec<u8> {
        assert!(payload.len() <= MAX_PAYLOAD, "a payload of {} bytes does not fit one datagram", payload.len());
        assert!(self.send_counter < REKEY_LIMIT, "this session has sent too many packets and must be replaced");
        let counter = self.send_counter;
        self.send_counter += 1;
        let mut header = Vec::with_capacity(HEADER_LENGTH);
        header.push(TYPE_DATA);
        header.extend_from_slice(&self.remote_index.to_le_bytes());
        header.extend_from_slice(&counter.to_le_bytes());
        let mut plain = Vec::with_capacity(payload.len() + 1);
        plain.push(stream as u8);
        plain.extend_from_slice(payload);
        let body = seal(&self.keys.send, counter, &header, &plain);
        header.extend_from_slice(&body);
        header
    }

    pub fn open(&mut self, datagram: &[u8]) -> Option<(StreamKind, Vec<u8>)> {
        if datagram.len() < HEADER_LENGTH + TAG_LENGTH + 1 || datagram[0] != TYPE_DATA {
            return None;
        }
        let header = &datagram[..HEADER_LENGTH];
        if i32::from_le_bytes(header[1..5].try_into().ok()?) != self.local_index {
            return None;
        }
        let counter = u64::from_le_bytes(header[5..13].try_into().ok()?);
        let plain = open(&self.keys.receive, counter, header, &datagram[HEADER_LENGTH..])?;
        let stream = StreamKind::of(plain[0])?;
        if !self.window.accept(counter) {
            return None;
        }
        Some((stream, plain[1..].to_vec()))
    }
}
