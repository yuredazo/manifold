use crate::session::MAX_PAYLOAD;
use crate::wire::Address;

pub const MAX_NAME_LENGTH: usize = 64;
pub const MAX_DIMENSION: u16 = 8192;
pub const MAX_FEEDS: usize = 64;
pub const MAX_NACK_INDEXES: usize = 200;
pub const MAX_ADDRESSES: usize = 16;
pub const DEFAULT_FPS: u8 = 30;
pub const MAX_FPS: u8 = 60;
pub const MAX_ALPHA_WIDTH: u16 = 3840;
pub const MAX_ALPHA_HEIGHT: u16 = 2048;

// Length is counted in UTF-16 units, as the other hubs count it.
pub fn is_valid_name(name: &str) -> bool {
    let trimmed = name.trim();
    let length = trimmed.encode_utf16().count();
    length > 0 && length <= MAX_NAME_LENGTH && !trimmed.chars().any(|c| (c as u32) <= 0x1F || (0x7F..=0x9F).contains(&(c as u32)))
}

// sound_only has no picture and travels in bit 1 of the audio byte, so an older hub still reads "has audio".
#[derive(Clone, PartialEq, Eq, Debug, Default)]
pub struct FeedInfo {
    pub name: String,
    pub width: u16,
    pub height: u16,
    pub fps: u8,
    pub has_audio: bool,
    pub sound_only: bool,
    pub has_alpha: bool,
    pub title: String,
}

impl FeedInfo {
    pub fn new(name: &str, width: u16, height: u16, fps: u8, has_audio: bool) -> Self {
        Self { name: name.to_string(), width, height, fps, has_audio, ..Default::default() }
    }

    pub fn label(&self) -> &str {
        if self.title.is_empty() { &self.name } else { &self.title }
    }
}

#[derive(Clone, PartialEq, Eq, Debug)]
pub struct Subscribe {
    pub stream_id: u16,
    pub feed: String,
    pub width: u16,
    pub height: u16,
    pub bitrate_kbps: u16,
    pub audio: bool,
    pub fps: u8,
    pub alpha: bool,
}

impl Subscribe {
    pub fn new(stream_id: u16, feed: &str, width: u16, height: u16, bitrate_kbps: u16) -> Self {
        Self { stream_id, feed: feed.to_string(), width, height, bitrate_kbps, audio: false, fps: DEFAULT_FPS, alpha: false }
    }
}

// A reason this version does not know reads as Failed.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Refusal {
    NotShared = 1,
    NotFound = 2,
    Busy = 3,
    Failed = 4,
}

impl Refusal {
    fn of(code: u8) -> Self {
        match code {
            1 => Self::NotShared,
            2 => Self::NotFound,
            3 => Self::Busy,
            _ => Self::Failed,
        }
    }
}

#[derive(Clone, PartialEq, Eq, Debug)]
pub enum Control {
    Ping,
    PairConfirm,
    PairReject,
    Bye,
    FeedList(Vec<FeedInfo>),
    Subscribe(Subscribe),
    SubscribeRefused { stream_id: u16, reason: Refusal },
    Unsubscribe(u16),
    KeyframeRequest(u16),
    TimeRequest(i64),
    TimeReply(i64),
    // Empty indexes means the whole frame.
    Nack { stream_id: u16, frame_id: i32, indexes: Vec<u16> },
    // jitter_ms10 is in tenths of a millisecond.
    StreamReport { stream_id: u16, window_ms: u16, fragments: u16, resend_requests: u16, lost_frames: u16, jitter_ms10: u16 },
    SenderStats { stream_id: u16, bitrate_kbps: u16, fps10: u16, encode_ms10: u16, send_ms10: u16 },
    // Every address the sender listens on, so the other side can still reach it from another network, such as over a VPN.
    Addresses(Vec<Address>),
}

#[derive(Clone, PartialEq, Eq, Debug)]
pub enum Decoded {
    Ack(i32),
    Message(i32, Control),
    // A header this version can read with a kind or body it cannot. It is still acknowledged, or its sender would resend it until it gave up on the link.
    Unrecognized(i32),
}

const ACK: u8 = 0;
const PING: u8 = 1;
const PAIR_CONFIRM: u8 = 2;
const PAIR_REJECT: u8 = 3;
const BYE: u8 = 4;
const FEED_LIST: u8 = 5;
const SUBSCRIBE: u8 = 6;
const UNSUBSCRIBE: u8 = 7;
const KEYFRAME_REQUEST: u8 = 8;
const TIME_REQUEST: u8 = 9;
const TIME_REPLY: u8 = 10;
const SENDER_STATS: u8 = 11;
const NACK: u8 = 12;
const STREAM_REPORT: u8 = 13;
const SUBSCRIBE_REFUSED: u8 = 14;
const ADDRESSES: u8 = 15;
const AUDIO_BIT: u8 = 1;
const SOUND_ONLY_BIT: u8 = 2;
const ALPHA_BIT: u8 = 4;

// Pings and measurements are never acknowledged or resent: a late measurement is worth nothing.
pub fn needs_ack(control: &Control) -> bool {
    !matches!(control, Control::Ping | Control::TimeRequest(_) | Control::TimeReply(_) | Control::SenderStats { .. } | Control::Nack { .. } | Control::StreamReport { .. })
}

pub fn encode_ack(id: i32) -> Vec<u8> {
    let mut out = vec![ACK];
    out.extend_from_slice(&id.to_be_bytes());
    out
}

fn clamp16(value: u16) -> [u8; 2] {
    value.to_be_bytes()
}

pub fn encode(id: i32, control: &Control) -> Vec<u8> {
    let (kind, body): (u8, Vec<u8>) = match control {
        Control::Ping => (PING, vec![]),
        Control::PairConfirm => (PAIR_CONFIRM, vec![]),
        Control::PairReject => (PAIR_REJECT, vec![]),
        Control::Bye => (BYE, vec![]),
        Control::FeedList(feeds) => (FEED_LIST, encode_feeds(feeds)),
        Control::Subscribe(request) => (SUBSCRIBE, encode_subscribe(request)),
        Control::SubscribeRefused { stream_id, reason } => (SUBSCRIBE_REFUSED, [&stream_id.to_be_bytes()[..], &[*reason as u8]].concat()),
        Control::Unsubscribe(stream_id) => (UNSUBSCRIBE, stream_id.to_be_bytes().to_vec()),
        Control::KeyframeRequest(stream_id) => (KEYFRAME_REQUEST, stream_id.to_be_bytes().to_vec()),
        Control::TimeRequest(sent_at) => (TIME_REQUEST, sent_at.to_be_bytes().to_vec()),
        Control::TimeReply(sent_at) => (TIME_REPLY, sent_at.to_be_bytes().to_vec()),
        Control::SenderStats { stream_id, bitrate_kbps, fps10, encode_ms10, send_ms10 } => {
            (SENDER_STATS, [clamp16(*stream_id), clamp16(*bitrate_kbps), clamp16(*fps10), clamp16(*encode_ms10), clamp16(*send_ms10)].concat())
        }
        Control::Nack { stream_id, frame_id, indexes } => (NACK, encode_nack(*stream_id, *frame_id, indexes)),
        Control::StreamReport { stream_id, window_ms, fragments, resend_requests, lost_frames, jitter_ms10 } => (
            STREAM_REPORT,
            [clamp16(*stream_id), clamp16(*window_ms), clamp16(*fragments), clamp16(*resend_requests), clamp16(*lost_frames), clamp16(*jitter_ms10)].concat(),
        ),
        Control::Addresses(addresses) => (ADDRESSES, encode_addresses(addresses)),
    };
    let mut out = Vec::with_capacity(5 + body.len());
    out.push(kind);
    out.extend_from_slice(&id.to_be_bytes());
    out.extend_from_slice(&body);
    out
}

// A request for more pieces than fit is a request for the whole frame.
fn encode_nack(stream_id: u16, frame_id: i32, indexes: &[u16]) -> Vec<u8> {
    let indexes = if indexes.len() > MAX_NACK_INDEXES { &[][..] } else { indexes };
    let mut out = Vec::with_capacity(8 + 2 * indexes.len());
    out.extend_from_slice(&stream_id.to_be_bytes());
    out.extend_from_slice(&frame_id.to_be_bytes());
    out.extend_from_slice(&(indexes.len() as u16).to_be_bytes());
    for index in indexes {
        out.extend_from_slice(&index.to_be_bytes());
    }
    out
}

fn encode_subscribe(request: &Subscribe) -> Vec<u8> {
    let name = request.feed.as_bytes();
    assert!(name.len() <= 255, "feed name too long");
    let with_fps = request.alpha || request.fps != DEFAULT_FPS;
    let mut out = Vec::with_capacity(12 + name.len());
    for value in [request.stream_id, request.width, request.height, request.bitrate_kbps] {
        out.extend_from_slice(&value.to_be_bytes());
    }
    out.push(name.len() as u8);
    out.extend_from_slice(name);
    out.push(request.audio as u8);
    if with_fps {
        out.push(request.fps.clamp(1, MAX_FPS));
    }
    if request.alpha {
        out.push(1);
    }
    out
}

fn encode_feeds(feeds: &[FeedInfo]) -> Vec<u8> {
    let mut encoded: Vec<Vec<u8>> = Vec::new();
    let mut titles: Vec<Vec<u8>> = Vec::new();
    let mut used = 2;
    for feed in feeds.iter().take(MAX_FEEDS) {
        let name = feed.name.as_bytes();
        if name.len() > 255 {
            continue;
        }
        let mut entry = Vec::with_capacity(7 + name.len());
        entry.push(name.len() as u8);
        entry.extend_from_slice(name);
        entry.extend_from_slice(&feed.width.to_be_bytes());
        entry.extend_from_slice(&feed.height.to_be_bytes());
        entry.push(feed.fps);
        entry.push((if feed.has_audio { AUDIO_BIT } else { 0 }) | (if feed.sound_only { SOUND_ONLY_BIT } else { 0 }) | (if feed.has_alpha { ALPHA_BIT } else { 0 }));
        if used + entry.len() > MAX_PAYLOAD - 5 {
            break;
        }
        used += entry.len();
        encoded.push(entry);
        titles.push(if feed.title == feed.name { vec![] } else { feed.title.as_bytes().to_vec() });
    }
    // Entries are never shortened for a title. A title that does not fit is left out, and so is the whole section when none is left.
    let mut with_titles = used + titles.len();
    for title in titles.iter_mut() {
        if title.len() > 255 || with_titles + title.len() > MAX_PAYLOAD - 5 {
            title.clear();
        } else {
            with_titles += title.len();
        }
    }
    let mut out = (encoded.len() as u16).to_be_bytes().to_vec();
    for entry in &encoded {
        out.extend_from_slice(entry);
    }
    if titles.iter().any(|title| !title.is_empty()) {
        for title in &titles {
            out.push(title.len() as u8);
            out.extend_from_slice(title);
        }
    }
    out
}

// IPv4 only: four address bytes and a port each. Anything else is left out.
fn encode_addresses(addresses: &[Address]) -> Vec<u8> {
    let mut usable: Vec<([u8; 4], u16)> = Vec::new();
    for address in addresses {
        let Some(octets) = ipv4(&address.host) else { continue };
        if usable.contains(&(octets, address.port)) {
            continue;
        }
        usable.push((octets, address.port));
        if usable.len() == MAX_ADDRESSES {
            break;
        }
    }
    let mut out = vec![usable.len() as u8];
    for (octets, port) in usable {
        out.extend_from_slice(&octets);
        out.extend_from_slice(&port.to_be_bytes());
    }
    out
}

fn ipv4(host: &str) -> Option<[u8; 4]> {
    let parts: Vec<&str> = host.split('.').collect();
    if parts.len() != 4 || parts.iter().any(|part| part.is_empty() || part.len() > 3 || !part.bytes().all(|b| b.is_ascii_digit())) {
        return None;
    }
    let mut out = [0u8; 4];
    for (slot, part) in out.iter_mut().zip(parts) {
        *slot = part.parse::<u16>().ok().filter(|value| *value <= 255)? as u8;
    }
    Some(out)
}

struct Reader<'a> {
    bytes: &'a [u8],
    at: usize,
}

impl<'a> Reader<'a> {
    fn has_more(&self) -> bool {
        self.at < self.bytes.len()
    }

    fn take(&mut self, count: usize) -> Option<&'a [u8]> {
        let part = self.bytes.get(self.at..self.at + count)?;
        self.at += count;
        Some(part)
    }

    fn u8(&mut self) -> Option<u8> {
        Some(self.take(1)?[0])
    }

    fn u16(&mut self) -> Option<u16> {
        Some(u16::from_be_bytes(self.take(2)?.try_into().ok()?))
    }

    fn i32(&mut self) -> Option<i32> {
        Some(i32::from_be_bytes(self.take(4)?.try_into().ok()?))
    }

    fn i64(&mut self) -> Option<i64> {
        Some(i64::from_be_bytes(self.take(8)?.try_into().ok()?))
    }

    fn string(&mut self, length: usize) -> Option<String> {
        Some(String::from_utf8_lossy(self.take(length)?).into_owned())
    }
}

// Input from the other device is parsed defensively: nothing panics.
pub fn decode(bytes: &[u8]) -> Option<Decoded> {
    if bytes.len() < 5 {
        return None;
    }
    let id = i32::from_be_bytes(bytes[1..5].try_into().ok()?);
    let mut reader = Reader { bytes, at: 5 };
    let control = match bytes[0] {
        ACK => return Some(Decoded::Ack(id)),
        PING => Some(Control::Ping),
        PAIR_CONFIRM => Some(Control::PairConfirm),
        PAIR_REJECT => Some(Control::PairReject),
        BYE => Some(Control::Bye),
        FEED_LIST => read_feeds(&mut reader).map(Control::FeedList),
        SUBSCRIBE => read_subscribe(&mut reader).map(Control::Subscribe),
        SUBSCRIBE_REFUSED => (|| Some(Control::SubscribeRefused { stream_id: reader.u16()?, reason: Refusal::of(reader.u8()?) }))(),
        UNSUBSCRIBE => reader.u16().map(Control::Unsubscribe),
        KEYFRAME_REQUEST => reader.u16().map(Control::KeyframeRequest),
        TIME_REQUEST => reader.i64().map(Control::TimeRequest),
        TIME_REPLY => reader.i64().map(Control::TimeReply),
        NACK => read_nack(&mut reader),
        STREAM_REPORT => (|| {
            Some(Control::StreamReport {
                stream_id: reader.u16()?,
                window_ms: reader.u16()?,
                fragments: reader.u16()?,
                resend_requests: reader.u16()?,
                lost_frames: reader.u16()?,
                jitter_ms10: reader.u16()?,
            })
        })(),
        SENDER_STATS => (|| {
            Some(Control::SenderStats { stream_id: reader.u16()?, bitrate_kbps: reader.u16()?, fps10: reader.u16()?, encode_ms10: reader.u16()?, send_ms10: reader.u16()? })
        })(),
        ADDRESSES => read_addresses(&mut reader),
        _ => None,
    };
    Some(control.map_or(Decoded::Unrecognized(id), |control| Decoded::Message(id, control)))
}

fn read_nack(reader: &mut Reader) -> Option<Control> {
    let stream_id = reader.u16()?;
    let frame_id = reader.i32()?;
    let count = reader.u16()? as usize;
    if count > MAX_NACK_INDEXES {
        return None;
    }
    let indexes = (0..count).map(|_| reader.u16()).collect::<Option<Vec<_>>>()?;
    Some(Control::Nack { stream_id, frame_id, indexes })
}

// A request with a name or a size the hub would not accept from a local app is not a request.
fn read_subscribe(reader: &mut Reader) -> Option<Subscribe> {
    let stream_id = reader.u16()?;
    let width = reader.u16()?;
    let height = reader.u16()?;
    let bitrate_kbps = reader.u16()?;
    let length = reader.u8()? as usize;
    let name = reader.string(length)?;
    if !is_valid_name(&name) || !(1..=MAX_DIMENSION).contains(&width) || !(1..=MAX_DIMENSION).contains(&height) || bitrate_kbps < 1 {
        return None;
    }
    // Older senders of this message stop after the name, or after the audio byte.
    let audio = reader.has_more() && reader.u8()? != 0;
    let fps = if reader.has_more() { reader.u8()?.clamp(1, MAX_FPS) } else { DEFAULT_FPS };
    let alpha = reader.has_more() && reader.u8()? & 1 != 0;
    Some(Subscribe { stream_id, feed: name.trim().to_string(), width, height, bitrate_kbps, audio, fps, alpha })
}

fn read_feeds(reader: &mut Reader) -> Option<Vec<FeedInfo>> {
    let count = (reader.u16()? as usize).min(MAX_FEEDS);
    let mut entries: Vec<Option<FeedInfo>> = Vec::with_capacity(count);
    for _ in 0..count {
        let length = reader.u8()? as usize;
        let name = reader.string(length)?;
        let width = reader.u16()?;
        let height = reader.u16()?;
        let fps = reader.u8()?;
        let flags = reader.u8()?;
        entries.push((is_valid_name(&name) && width <= MAX_DIMENSION && height <= MAX_DIMENSION).then(|| FeedInfo {
            name: name.trim().to_string(),
            width,
            height,
            fps,
            has_audio: flags & AUDIO_BIT != 0,
            sound_only: flags & SOUND_ONLY_BIT != 0,
            has_alpha: flags & ALPHA_BIT != 0,
            title: String::new(),
        }));
    }
    // Hubs from before titles end here. A damaged tail only costs the titles, never the list.
    let mut titled = reader.has_more();
    let mut feeds = Vec::new();
    for entry in entries {
        let mut title = String::new();
        if titled {
            match reader.u8().and_then(|length| reader.string(length as usize)) {
                Some(text) => title = text.trim().to_string(),
                None => titled = false,
            }
            if entry.as_ref().is_some_and(|feed| feed.name == title) || !is_valid_name(&title) {
                title.clear();
            }
        }
        if let Some(mut feed) = entry {
            feed.title = title;
            feeds.push(feed);
        }
    }
    Some(feeds)
}

// Addresses no other device could send to, such as loopback or multicast, are dropped rather than dialed.
fn read_addresses(reader: &mut Reader) -> Option<Control> {
    let count = reader.u8()? as usize;
    if count > MAX_ADDRESSES {
        return None;
    }
    let mut addresses: Vec<Address> = Vec::new();
    for _ in 0..count {
        let octets = reader.take(4)?;
        let port = reader.u16()?;
        let reachable = port != 0 && octets[0] != 0 && octets[0] != 127 && octets[0] < 224 && !(octets[0] == 169 && octets[1] == 254);
        let address = Address::new(format!("{}.{}.{}.{}", octets[0], octets[1], octets[2], octets[3]), port);
        if reachable && !addresses.contains(&address) {
            addresses.push(address);
        }
    }
    Some(Control::Addresses(addresses))
}
