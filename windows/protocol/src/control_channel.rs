use crate::control::{Control, Decoded, decode, encode, encode_ack, needs_ack};
use crate::session::ReplayWindow;
use std::collections::BTreeMap;

pub const RESEND_AFTER_MS: i64 = 400;
pub const MAX_ATTEMPTS: u32 = 8;

struct Waiting {
    bytes: Vec<u8>,
    sent_at: i64,
    attempts: u32,
}

pub struct Received {
    pub reply: Option<Vec<u8>>,
    pub message: Option<Control>,
}

pub struct Tick {
    pub resend: Vec<Vec<u8>>,
    pub failed: bool,
}

// Returns what to transmit instead of sending it, so the endpoint decides how bytes leave.
pub struct ControlChannel {
    next_id: i32,
    waiting: BTreeMap<i32, Waiting>,
    seen: ReplayWindow,
    newest_feed_list: i32,
    failed: bool,
    // Pairing gives up fast. A link that is up is judged by silence, so it retries for as long.
    pub give_up_after: u32,
}

impl Default for ControlChannel {
    fn default() -> Self {
        Self { next_id: 1, waiting: BTreeMap::new(), seen: ReplayWindow::new(256), newest_feed_list: 0, failed: false, give_up_after: MAX_ATTEMPTS }
    }
}

impl ControlChannel {
    pub fn send(&mut self, control: &Control, now: i64) -> Vec<u8> {
        let id = self.next_id;
        self.next_id += 1;
        let bytes = encode(id, control);
        if needs_ack(control) {
            self.waiting.insert(id, Waiting { bytes: bytes.clone(), sent_at: now, attempts: 1 });
        }
        bytes
    }

    pub fn receive(&mut self, payload: &[u8]) -> Received {
        let nothing = Received { reply: None, message: None };
        match decode(payload) {
            None => nothing,
            Some(Decoded::Ack(id)) => {
                self.waiting.remove(&id);
                nothing
            }
            Some(Decoded::Unrecognized(id)) => Received { reply: Some(encode_ack(id)), message: None },
            Some(Decoded::Message(id, control)) => {
                // The ack is sent again for a repeat: the first one may have been lost.
                let reply = needs_ack(&control).then(|| encode_ack(id));
                if id < 0 || !self.seen.accept(id as u64) {
                    return Received { reply, message: None };
                }
                if matches!(control, Control::FeedList(_)) {
                    if id < self.newest_feed_list {
                        return Received { reply, message: None };
                    }
                    self.newest_feed_list = id;
                }
                Received { reply, message: Some(control) }
            }
        }
    }

    pub fn tick(&mut self, now: i64) -> Tick {
        let mut resend = Vec::new();
        if self.failed {
            return Tick { resend, failed: false };
        }
        for message in self.waiting.values_mut() {
            if now - message.sent_at < RESEND_AFTER_MS {
                continue;
            }
            if message.attempts >= self.give_up_after {
                self.failed = true;
                return Tick { resend, failed: true };
            }
            message.attempts += 1;
            message.sent_at = now;
            resend.push(message.bytes.clone());
        }
        Tick { resend, failed: false }
    }
}
