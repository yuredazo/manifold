use crate::audio;
use crate::control::{Control, FeedInfo, MAX_NAME_LENGTH, Refusal, Subscribe};
use crate::control_channel::{ControlChannel, RESEND_AFTER_MS};
use crate::crypto::{from_hex, to_hex};
use crate::devices::{Device, DeviceBook, Identity};
use crate::noise::{Handshake, Pattern};
use crate::session::{Session, StreamKind, TYPE_DATA};
use crate::video;
use crate::wire::{Address, HandshakePacket, TYPE_HELLO, TYPE_PAIR, data_receiver, decode_handshake, encode_handshake};
use std::collections::HashMap;

pub const PAIRING_WINDOW_MS: i64 = 120_000;
pub const HANDSHAKE_TIMEOUT_MS: i64 = 10_000;
pub const HANDSHAKE_RESEND_MS: i64 = 1_000;
pub const MAX_HANDSHAKE_RESENDS: u32 = 5;
pub const CONFIRM_TIMEOUT_MS: i64 = 120_000;
pub const PING_INTERVAL_MS: i64 = 3_000;
pub const LINK_TIMEOUT_MS: i64 = 15_000;
// Pings and probes arrive every one to three seconds.
pub const RESUME_AFTER_SILENCE_MS: i64 = 2_500;
pub const PROBE_INTERVAL_MS: i64 = 1_000;
// Resent until the link would time out anyway.
const ESTABLISHED_ATTEMPTS: u32 = (LINK_TIMEOUT_MS / RESEND_AFTER_MS) as u32;
const RTT_SAMPLES: usize = 9;
const PROLOGUE: &[u8] = b"manifold-net/1";

#[derive(Clone, PartialEq, Debug)]
pub enum Event {
    PairingCode { address: Address, remote_name: String, code: String },
    Paired(Device),
    PairingFailed(String),
    LinkUp(Device, Address),
    LinkDown(Device),
    // Heard again after a silence. Nothing is lost, but a stream has a hole in its timeline.
    LinkResumed(Device),
    ConnectFailed(Device),
    Feeds(Device, Vec<FeedInfo>),
    Addresses(Device, Vec<Address>),
    Subscribe(Device, Subscribe),
    SubscribeRefused { device: Device, stream_id: u16, reason: Refusal },
    Unsubscribe(Device, u16),
    KeyframeRequest(Device, u16),
    Nack { device: Device, stream_id: u16, frame_id: i32, indexes: Vec<u16> },
    StreamReport { device: Device, stream_id: u16, window_ms: u16, fragments: u16, resend_requests: u16, lost_frames: u16, jitter_ms10: u16 },
    SenderStats { device: Device, stream_id: u16, bitrate_kbps: u16, fps10: u16, encode_ms10: u16, send_ms10: u16 },
    Video(Device, u16, Vec<u8>),
    // timestamp counts 90 kHz ticks on the same clock as the picture's.
    Audio(Device, u16, i32, Vec<u8>),
    Log(String),
}

struct Link {
    public_key: String,
    address: Address,
    session: Session,
    control: ControlChannel,
    heard: i64,
    pinged: i64,
    probed: i64,
    // Negative until the first reply.
    rtt_ms: f64,
    rtt_samples: Vec<f64>,
}

struct Pairing {
    initiator: bool,
    address: Address,
    handshake: Handshake,
    local_index: i32,
    started_at: i64,
    remote_index: i32,
    last_sent: Option<Vec<u8>>,
    last_sent_at: i64,
    resends: u32,
    link: Option<i32>,
    remote_name: String,
    code: String,
    heard_data: bool,
    local_confirmed: bool,
    remote_confirmed: bool,
}

struct Dialing {
    device: Device,
    addresses: Vec<Address>,
    index: i32,
    handshake: Handshake,
    sent_at: i64,
    attempts: u32,
}

// No socket or clock of its own, so it can be tested without a network. Callers drain outgoing datagrams and events after each call.
pub struct Endpoint {
    identity: Identity,
    book: DeviceBook,
    clock: Box<dyn Fn() -> i64 + Send>,
    // Stamps each hello. It has to keep growing across restarts, which the clock does not.
    wall_clock: Box<dyn Fn() -> i64 + Send>,
    pairing_open_until: i64,
    pairing: Option<Pairing>,
    dialing: HashMap<String, Dialing>,
    links: HashMap<i32, Link>,
    current: HashMap<String, i32>,
    last_hello: HashMap<String, i64>,
    outgoing: Vec<(Address, Vec<u8>)>,
    events: Vec<Event>,
}

impl Endpoint {
    pub fn new(identity: Identity, book: DeviceBook, clock: impl Fn() -> i64 + Send + 'static, wall_clock: impl Fn() -> i64 + Send + 'static) -> Self {
        Self {
            identity,
            book,
            clock: Box::new(clock),
            wall_clock: Box::new(wall_clock),
            pairing_open_until: 0,
            pairing: None,
            dialing: HashMap::new(),
            links: HashMap::new(),
            current: HashMap::new(),
            last_hello: HashMap::new(),
            outgoing: Vec::new(),
            events: Vec::new(),
        }
    }

    pub fn identity(&self) -> &Identity {
        &self.identity
    }

    pub fn book(&self) -> &DeviceBook {
        &self.book
    }

    pub fn book_mut(&mut self) -> &mut DeviceBook {
        &mut self.book
    }

    pub fn take_outgoing(&mut self) -> Vec<(Address, Vec<u8>)> {
        std::mem::take(&mut self.outgoing)
    }

    pub fn take_events(&mut self) -> Vec<Event> {
        std::mem::take(&mut self.events)
    }

    pub fn open_pairing(&mut self, duration_ms: i64) {
        self.pairing_open_until = (self.clock)() + duration_ms;
    }

    pub fn close_pairing(&mut self) {
        self.pairing_open_until = 0;
    }

    pub fn pairing_open(&self) -> bool {
        (self.clock)() <= self.pairing_open_until
    }

    pub fn is_pairing(&self) -> bool {
        self.pairing.is_some()
    }

    pub fn pair(&mut self, address: Address) {
        assert!(self.pairing.is_none(), "a pairing is already in progress");
        let mut handshake = Handshake::new(Pattern::Xx, true, &self.identity.keys, None, PROLOGUE);
        let first = handshake.write_message(&[]).expect("the first XX message");
        let local_index = self.new_index();
        let mut attempt = new_pairing(true, address, handshake, local_index, (self.clock)());
        self.send_handshake(&mut attempt, HandshakePacket { kind: TYPE_PAIR, step: 0, sender_index: local_index, receiver_index: 0, message: first });
        self.pairing = Some(attempt);
    }

    pub fn confirm_pairing(&mut self, accept: bool) {
        let Some(link) = self.pairing.as_ref().and_then(|current| current.link) else { return };
        if accept {
            self.pairing.as_mut().unwrap().local_confirmed = true;
            self.send_control(link, &Control::PairConfirm);
            self.complete_pairing_if_both();
        } else {
            self.send_control(link, &Control::PairReject);
            self.end_pairing("declined");
        }
    }

    pub fn cancel_pairing(&mut self) {
        self.end_pairing("cancelled");
    }

    // The same hello goes to every address at once. The other side answers the first copy that arrives and drops the rest as replays.
    pub fn connect(&mut self, device: &Device, addresses: Vec<Address>) {
        if addresses.is_empty() || self.current.contains_key(&device.public_key) || self.dialing.contains_key(&device.public_key) {
            return;
        }
        let Some(key) = from_hex(&device.public_key) else { return };
        let index = self.new_index();
        let handshake = self.hello_handshake(&key);
        let mut attempt = Dialing { device: device.clone(), addresses, index, handshake, sent_at: (self.clock)(), attempts: 1 };
        self.send_hello(&mut attempt);
        self.dialing.insert(device.public_key.clone(), attempt);
    }

    pub fn disconnect(&mut self, public_key: &str) {
        self.dialing.remove(public_key);
        let Some(&index) = self.current.get(public_key) else { return };
        self.send_control(index, &Control::Bye);
        self.drop_link(index, "closed from this device");
    }

    pub fn unpair(&mut self, public_key: &str) {
        self.disconnect(public_key);
        self.book.remove(public_key);
    }

    pub fn send_to(&mut self, public_key: &str, control: &Control) {
        if let Some(&index) = self.current.get(public_key) {
            self.send_control(index, control);
        }
    }

    // Video is not resent when lost: a late frame is no use, and the receiver asks for a keyframe instead.
    pub fn send_video(&mut self, public_key: &str, stream_id: u16, fragments: &[Vec<u8>]) {
        let Some(link) = self.current.get(public_key).and_then(|index| self.links.get_mut(index)) else { return };
        for fragment in fragments {
            let sealed = link.session.seal(StreamKind::Video, &video::encode_packet(stream_id, fragment));
            self.outgoing.push((link.address.clone(), sealed));
        }
    }

    // Like video, audio is not resent: a late frame would only be heard as a glitch.
    pub fn send_audio(&mut self, public_key: &str, stream_id: u16, timestamp: i32, frame: &[u8]) {
        let Some(link) = self.current.get(public_key).and_then(|index| self.links.get_mut(index)) else { return };
        let sealed = link.session.seal(StreamKind::Audio, &audio::encode(stream_id, timestamp, frame));
        self.outgoing.push((link.address.clone(), sealed));
    }

    pub fn rtt_ms(&self, public_key: &str) -> Option<f64> {
        let link = self.links.get(self.current.get(public_key)?)?;
        (link.rtt_ms >= 0.0).then_some(link.rtt_ms)
    }

    pub fn is_linked(&self, public_key: &str) -> bool {
        self.current.contains_key(public_key)
    }

    pub fn is_dialing(&self, public_key: &str) -> bool {
        self.dialing.contains_key(public_key)
    }

    pub fn on_datagram(&mut self, from: Address, datagram: &[u8]) {
        match datagram.first() {
            Some(&TYPE_PAIR) | Some(&TYPE_HELLO) => {
                let Some(packet) = decode_handshake(datagram) else { return };
                match (packet.kind, packet.step) {
                    (TYPE_PAIR, 0) => self.started_by_other(from, packet),
                    (TYPE_PAIR, 1) => self.answered_by_other(from, packet),
                    (TYPE_PAIR, 2) => self.finished_by_other(from, packet),
                    (TYPE_HELLO, 0) => self.hello_received(from, packet),
                    (TYPE_HELLO, 1) => self.hello_answered(from, packet),
                    _ => {}
                }
            }
            Some(&TYPE_DATA) => self.on_data(from, datagram),
            _ => {}
        }
    }

    fn started_by_other(&mut self, from: Address, packet: HandshakePacket) {
        if let Some(current) = &self.pairing {
            // The same request again means our answer was lost.
            if !current.initiator && current.link.is_none() && current.remote_index == packet.sender_index && let Some(last) = current.last_sent.clone() {
                self.outgoing.push((from, last));
            }
            return;
        }
        if (self.clock)() > self.pairing_open_until {
            return;
        }
        let mut handshake = Handshake::new(Pattern::Xx, false, &self.identity.keys, None, PROLOGUE);
        if handshake.read_message(&packet.message).is_err() {
            return;
        }
        let local_index = self.new_index();
        let mut attempt = new_pairing(false, from, handshake, local_index, (self.clock)());
        attempt.remote_index = packet.sender_index;
        let Ok(reply) = attempt.handshake.write_message(&name_bytes(&self.identity.name)) else { return };
        let remote_index = attempt.remote_index;
        self.send_handshake(&mut attempt, HandshakePacket { kind: TYPE_PAIR, step: 1, sender_index: local_index, receiver_index: remote_index, message: reply });
        self.pairing = Some(attempt);
    }

    fn answered_by_other(&mut self, from: Address, packet: HandshakePacket) {
        let name = name_bytes(&self.identity.name);
        let Some(current) = self.pairing.as_mut() else { return };
        if !current.initiator || current.link.is_some() || packet.receiver_index != current.local_index {
            return;
        }
        let Ok(payload) = current.handshake.read_message(&packet.message) else { return };
        current.address = from;
        current.remote_index = packet.sender_index;
        current.remote_name = read_name(&payload);
        let Ok(third) = current.handshake.write_message(&name) else { return };
        let header = HandshakePacket { kind: TYPE_PAIR, step: 2, sender_index: current.local_index, receiver_index: current.remote_index, message: third };
        let mut attempt = self.pairing.take().unwrap();
        self.send_handshake(&mut attempt, header);
        self.pairing = Some(attempt);
        self.establish_pairing();
    }

    fn finished_by_other(&mut self, from: Address, packet: HandshakePacket) {
        let Some(current) = self.pairing.as_mut() else { return };
        if current.initiator || current.link.is_some() || packet.receiver_index != current.local_index || packet.sender_index != current.remote_index {
            return;
        }
        let Ok(payload) = current.handshake.read_message(&packet.message) else { return };
        current.address = from;
        current.remote_name = read_name(&payload);
        self.establish_pairing();
    }

    fn establish_pairing(&mut self) {
        let Some(current) = self.pairing.as_mut() else { return };
        let Some(key) = current.handshake.remote_static() else {
            self.end_pairing("the other device sent no key");
            return;
        };
        let session = Session::new(current.local_index, current.remote_index, current.handshake.split());
        current.code = pairing_code(&current.handshake.handshake_hash());
        current.link = Some(current.local_index);
        let (address, name, code, index) = (current.address.clone(), current.remote_name.clone(), current.code.clone(), current.local_index);
        let link = self.new_link(to_hex(&key), address.clone(), session);
        self.links.insert(index, link);
        self.events.push(Event::PairingCode { address, remote_name: name, code });
    }

    // Unknown keys and replays get no answer at all.
    fn hello_received(&mut self, from: Address, packet: HandshakePacket) {
        let mut handshake = Handshake::new(Pattern::Ik, false, &self.identity.keys, None, PROLOGUE);
        let Ok(payload) = handshake.read_message(&packet.message) else { return };
        let Some(remote) = handshake.remote_static() else { return };
        let Some(device) = self.book.find(&to_hex(&remote)).cloned() else { return };
        if payload.len() < 8 {
            return;
        }
        // Both devices dialing at once would leave each holding a different session. The device
        // with the smaller key keeps its own attempt; the other drops its own and answers.
        if self.dialing.contains_key(&device.public_key) {
            if to_hex(&self.identity.keys.public) < device.public_key {
                return;
            }
            self.dialing.remove(&device.public_key);
        }
        // Each hello carries a time that must be newer than the last, so a recorded one is useless.
        let sent_at = i64::from_be_bytes(payload[..8].try_into().unwrap());
        if sent_at <= *self.last_hello.get(&device.public_key).unwrap_or(&i64::MIN) {
            return;
        }
        self.last_hello.insert(device.public_key.clone(), sent_at);
        let Ok(reply) = handshake.write_message(&[]) else { return };
        let index = self.new_index();
        let session = Session::new(index, packet.sender_index, handshake.split());
        self.outgoing.push((from.clone(), encode_handshake(&HandshakePacket { kind: TYPE_HELLO, step: 1, sender_index: index, receiver_index: packet.sender_index, message: reply })));
        self.bring_up(&device.public_key, from, session, index);
    }

    fn hello_answered(&mut self, from: Address, packet: HandshakePacket) {
        let Some(key) = self.dialing.iter().find(|(_, attempt)| attempt.index == packet.receiver_index).map(|(key, _)| key.clone()) else { return };
        let attempt = self.dialing.get_mut(&key).unwrap();
        if attempt.handshake.read_message(&packet.message).is_err() {
            return;
        }
        let mut attempt = self.dialing.remove(&key).unwrap();
        let session = Session::new(attempt.index, packet.sender_index, attempt.handshake.split());
        self.bring_up(&key, from, session, attempt.index);
    }

    fn bring_up(&mut self, public_key: &str, address: Address, session: Session, index: i32) {
        // A peer that restarts dials in again before the old link has timed out. Whatever was
        // running over the old link has to hear that it ended, because the peer forgot it.
        if let Some(&old) = self.current.get(public_key) {
            self.drop_link(old, "the other device dialed in again");
        }
        let mut link = self.new_link(public_key.to_string(), address.clone(), session);
        link.control.give_up_after = ESTABLISHED_ATTEMPTS;
        self.links.insert(index, link);
        self.current.insert(public_key.to_string(), index);
        if let Some(device) = self.book.find(public_key).cloned() {
            self.events.push(Event::LinkUp(device, address));
        }
    }

    fn on_data(&mut self, from: Address, datagram: &[u8]) {
        let Some(index) = data_receiver(datagram) else { return };
        let now = (self.clock)();
        let Some(link) = self.links.get_mut(&index) else { return };
        let Some((stream, payload)) = link.session.open(datagram) else { return };
        let silent_for = now - link.heard;
        link.heard = now;
        link.address = from;
        let public_key = link.public_key.clone();
        let is_current = self.current.get(&public_key) == Some(&index);
        if silent_for > RESUME_AFTER_SILENCE_MS && is_current && let Some(device) = self.book.find(&public_key).cloned() {
            self.events.push(Event::LinkResumed(device));
        }
        if let Some(current) = self.pairing.as_mut().filter(|current| current.link == Some(index)) {
            current.heard_data = true;
        }
        match stream {
            StreamKind::Control => {
                let received = self.links.get_mut(&index).unwrap().control.receive(&payload);
                if let Some(reply) = received.reply {
                    self.transmit_control(index, &reply);
                }
                if let Some(control) = received.message {
                    self.handle_control(index, control);
                }
            }
            StreamKind::Video if is_current => {
                if let (Some(device), Some(stream_id)) = (self.book.find(&public_key).cloned(), video::stream_of(&payload)) {
                    self.events.push(Event::Video(device, stream_id, video::fragment_of(&payload).to_vec()));
                }
            }
            StreamKind::Audio if is_current => {
                if let (Some(device), Some(packet)) = (self.book.find(&public_key).cloned(), audio::decode(&payload)) {
                    self.events.push(Event::Audio(device, packet.stream_id, packet.timestamp, packet.frame));
                }
            }
            _ => {}
        }
    }

    fn handle_control(&mut self, index: i32, control: Control) {
        if self.pairing.as_ref().is_some_and(|current| current.link == Some(index)) {
            match control {
                Control::PairConfirm => {
                    self.pairing.as_mut().unwrap().remote_confirmed = true;
                    self.complete_pairing_if_both();
                }
                Control::PairReject => self.end_pairing("the other device declined"),
                _ => {}
            }
            return;
        }
        let Some(public_key) = self.links.get(&index).map(|link| link.public_key.clone()) else { return };
        let Some(device) = self.book.find(&public_key).cloned() else { return };
        let event = match control {
            Control::FeedList(feeds) => Event::Feeds(device, feeds),
            Control::Addresses(addresses) => Event::Addresses(device, addresses),
            Control::Subscribe(request) => Event::Subscribe(device, request),
            Control::SubscribeRefused { stream_id, reason } => Event::SubscribeRefused { device, stream_id, reason },
            Control::Unsubscribe(stream_id) => Event::Unsubscribe(device, stream_id),
            Control::KeyframeRequest(stream_id) => Event::KeyframeRequest(device, stream_id),
            Control::TimeRequest(sent_at) => {
                self.send_control(index, &Control::TimeReply(sent_at));
                return;
            }
            Control::TimeReply(sent_at) => {
                self.measure_rtt(index, sent_at);
                return;
            }
            Control::SenderStats { stream_id, bitrate_kbps, fps10, encode_ms10, send_ms10 } => Event::SenderStats { device, stream_id, bitrate_kbps, fps10, encode_ms10, send_ms10 },
            Control::Nack { stream_id, frame_id, indexes } => Event::Nack { device, stream_id, frame_id, indexes },
            Control::StreamReport { stream_id, window_ms, fragments, resend_requests, lost_frames, jitter_ms10 } => {
                Event::StreamReport { device, stream_id, window_ms, fragments, resend_requests, lost_frames, jitter_ms10 }
            }
            Control::Bye => {
                self.drop_link(index, "the other device said goodbye");
                return;
            }
            _ => return,
        };
        self.events.push(event);
    }

    pub fn tick(&mut self) {
        let now = (self.clock)();
        if self.pairing.is_some() {
            self.tick_pairing(now);
        }
        let dialing: Vec<String> = self.dialing.keys().cloned().collect();
        for key in dialing {
            self.tick_dialing(&key, now);
        }
        let links: Vec<i32> = self.current.values().copied().collect();
        for index in links {
            self.tick_link(index, now);
        }
    }

    fn tick_pairing(&mut self, now: i64) {
        let current = self.pairing.as_ref().unwrap();
        let Some(link) = current.link else {
            if now - current.started_at > HANDSHAKE_TIMEOUT_MS {
                self.end_pairing("no answer");
            } else {
                self.resend_handshake(now);
            }
            return;
        };
        if now - current.started_at > CONFIRM_TIMEOUT_MS {
            self.end_pairing("timed out");
            return;
        }
        // The third message may have been lost, and the other side cannot continue without it.
        if current.initiator && !current.heard_data {
            self.resend_handshake(now);
        }
        self.tick_control(link, now);
    }

    fn resend_handshake(&mut self, now: i64) {
        let Some(current) = self.pairing.as_mut() else { return };
        let Some(last) = current.last_sent.clone() else { return };
        if now - current.last_sent_at < HANDSHAKE_RESEND_MS || current.resends >= MAX_HANDSHAKE_RESENDS {
            return;
        }
        current.resends += 1;
        current.last_sent_at = now;
        self.outgoing.push((current.address.clone(), last));
    }

    fn tick_dialing(&mut self, key: &str, now: i64) {
        let Some(attempt) = self.dialing.get(key) else { return };
        if now - attempt.sent_at < HANDSHAKE_RESEND_MS {
            return;
        }
        if attempt.attempts >= MAX_HANDSHAKE_RESENDS {
            let attempt = self.dialing.remove(key).unwrap();
            self.events.push(Event::ConnectFailed(attempt.device));
            return;
        }
        let Some(remote) = from_hex(key) else { return };
        // A fresh handshake each time: the other side refuses a hello it has already seen.
        let handshake = self.hello_handshake(&remote);
        let mut attempt = self.dialing.remove(key).unwrap();
        attempt.handshake = handshake;
        attempt.attempts += 1;
        attempt.sent_at = now;
        self.send_hello(&mut attempt);
        self.dialing.insert(key.to_string(), attempt);
    }

    fn measure_rtt(&mut self, index: i32, sent_at: i64) {
        let now = (self.clock)();
        let Some(link) = self.links.get_mut(&index) else { return };
        link.rtt_samples.push((now - sent_at).clamp(0, 1 << 30) as f64);
        if link.rtt_samples.len() > RTT_SAMPLES {
            link.rtt_samples.remove(0);
        }
        // The first reply after a quiet spell can be slow while the radio wakes. A median does not carry that along.
        let mut sorted = link.rtt_samples.clone();
        sorted.sort_by(f64::total_cmp);
        link.rtt_ms = sorted[sorted.len() / 2];
    }

    fn tick_link(&mut self, index: i32, now: i64) {
        self.tick_control(index, now);
        let Some(link) = self.links.get(&index) else { return };
        if self.current.get(&link.public_key) != Some(&index) {
            return;
        }
        if now - link.heard > LINK_TIMEOUT_MS {
            let silent = (now - link.heard) as f64 / 1000.0;
            self.drop_link(index, &format!("nothing heard for {silent} s"));
            return;
        }
        let (probe, ping) = (now - link.probed >= PROBE_INTERVAL_MS, now - link.pinged >= PING_INTERVAL_MS);
        if probe {
            self.links.get_mut(&index).unwrap().probed = now;
            self.send_control(index, &Control::TimeRequest(now));
        }
        if ping {
            self.links.get_mut(&index).unwrap().pinged = now;
            self.send_control(index, &Control::Ping);
        }
    }

    fn tick_control(&mut self, index: i32, now: i64) {
        let Some(link) = self.links.get_mut(&index) else { return };
        let tick = link.control.tick(now);
        let give_up_after = link.control.give_up_after;
        for bytes in tick.resend {
            self.transmit_control(index, &bytes);
        }
        if tick.failed {
            if self.pairing.as_ref().is_some_and(|current| current.link == Some(index)) {
                self.end_pairing("lost contact");
            } else {
                self.drop_link(index, &format!("a message got no answer after {give_up_after} tries"));
            }
        }
    }

    fn complete_pairing_if_both(&mut self) {
        let Some(current) = self.pairing.as_ref() else { return };
        let Some(index) = current.link else { return };
        if !current.local_confirmed || !current.remote_confirmed {
            return;
        }
        let current = self.pairing.take().unwrap();
        let public_key = self.links[&index].public_key.clone();
        // Pairing again keeps what the owner had switched on for this device.
        let known = self.book.find(&public_key).cloned().unwrap_or_else(|| Device::new(&public_key, &current.remote_name, None));
        self.book.put(Device { name: current.remote_name.clone(), address: Some(current.address.to_string()), ..known });
        // One pairing is what the owner opened the window for.
        self.pairing_open_until = 0;
        if let Some(&old) = self.current.get(&public_key) {
            self.drop_link(old, "replaced by a new pairing");
        }
        self.links.get_mut(&index).unwrap().control.give_up_after = ESTABLISHED_ATTEMPTS;
        self.current.insert(public_key.clone(), index);
        let stored = self.book.find(&public_key).cloned().unwrap();
        self.events.push(Event::Paired(stored.clone()));
        self.events.push(Event::LinkUp(stored, current.address));
    }

    fn end_pairing(&mut self, reason: &str) {
        let Some(current) = self.pairing.take() else { return };
        if let Some(index) = current.link {
            self.links.remove(&index);
        }
        self.events.push(Event::PairingFailed(reason.to_string()));
    }

    fn drop_link(&mut self, index: i32, reason: &str) {
        let Some(link) = self.links.remove(&index) else { return };
        if self.current.get(&link.public_key) != Some(&index) {
            return;
        }
        self.current.remove(&link.public_key);
        let device = self.book.find(&link.public_key).cloned().unwrap_or_else(|| Device::new(&link.public_key, "", None));
        let shown = if device.name.is_empty() { link.public_key.chars().take(8).collect() } else { device.name.clone() };
        self.events.push(Event::Log(format!("link to {shown} down: {reason}")));
        self.events.push(Event::LinkDown(device));
    }

    fn new_link(&self, public_key: String, address: Address, session: Session) -> Link {
        let now = (self.clock)();
        Link { public_key, address, session, control: ControlChannel::default(), heard: now, pinged: now, probed: 0, rtt_ms: -1.0, rtt_samples: Vec::new() }
    }

    fn send_control(&mut self, index: i32, control: &Control) {
        let now = (self.clock)();
        let Some(link) = self.links.get_mut(&index) else { return };
        let bytes = link.control.send(control, now);
        self.transmit_control(index, &bytes);
    }

    fn transmit_control(&mut self, index: i32, payload: &[u8]) {
        let Some(link) = self.links.get_mut(&index) else { return };
        let sealed = link.session.seal(StreamKind::Control, payload);
        self.outgoing.push((link.address.clone(), sealed));
    }

    fn send_handshake(&mut self, current: &mut Pairing, packet: HandshakePacket) {
        let bytes = encode_handshake(&packet);
        current.last_sent = Some(bytes.clone());
        current.last_sent_at = (self.clock)();
        current.resends = 0;
        self.outgoing.push((current.address.clone(), bytes));
    }

    fn hello_handshake(&self, remote: &[u8]) -> Handshake {
        Handshake::new(Pattern::Ik, true, &self.identity.keys, Some(remote), PROLOGUE)
    }

    fn send_hello(&mut self, attempt: &mut Dialing) {
        let mut payload = (self.wall_clock)().to_be_bytes().to_vec();
        payload.extend_from_slice(&name_bytes(&self.identity.name));
        let Ok(message) = attempt.handshake.write_message(&payload) else { return };
        let packet = encode_handshake(&HandshakePacket { kind: TYPE_HELLO, step: 0, sender_index: attempt.index, receiver_index: 0, message });
        for address in &attempt.addresses {
            self.outgoing.push((address.clone(), packet.clone()));
        }
    }

    fn new_index(&self) -> i32 {
        loop {
            let mut bytes = [0u8; 4];
            getrandom::fill(&mut bytes).expect("the system has no random source");
            let candidate = i32::from_le_bytes(bytes);
            let taken = self.links.contains_key(&candidate)
                || self.pairing.as_ref().is_some_and(|current| current.local_index == candidate)
                || self.dialing.values().any(|attempt| attempt.index == candidate);
            if candidate != 0 && !taken {
                return candidate;
            }
        }
    }
}

fn new_pairing(initiator: bool, address: Address, handshake: Handshake, local_index: i32, started_at: i64) -> Pairing {
    Pairing {
        initiator,
        address,
        handshake,
        local_index,
        started_at,
        remote_index: 0,
        last_sent: None,
        last_sent_at: 0,
        resends: 0,
        link: None,
        remote_name: String::new(),
        code: String::new(),
        heard_data: false,
        local_confirmed: false,
        remote_confirmed: false,
    }
}

fn pairing_code(handshake_hash: &[u8]) -> String {
    let number = u32::from_be_bytes(handshake_hash[..4].try_into().unwrap());
    format!("{:06}", number % 1_000_000)
}

// Names are capped in UTF-16 units, as the other hubs count them.
fn truncate_name(name: &str) -> String {
    let units: Vec<u16> = name.encode_utf16().take(MAX_NAME_LENGTH).collect();
    String::from_utf16_lossy(&units)
}

fn name_bytes(name: &str) -> Vec<u8> {
    truncate_name(name).into_bytes()
}

fn read_name(bytes: &[u8]) -> String {
    let cleaned = String::from_utf8_lossy(bytes).replace(['\t', '\n'], " ");
    let name = truncate_name(cleaned.trim());
    if name.is_empty() { "Unknown device".to_string() } else { name }
}
