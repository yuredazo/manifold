use crate::session::{HEADER_LENGTH, TYPE_DATA};
use std::fmt;

#[derive(Clone, PartialEq, Eq, Hash, Debug)]
pub struct Address {
    pub host: String,
    pub port: u16,
}

impl Address {
    pub fn new(host: impl Into<String>, port: u16) -> Self {
        Self { host: host.into(), port }
    }

    pub fn parse(text: &str, default_port: u16) -> Option<Self> {
        let trimmed = text.trim();
        if trimmed.is_empty() || trimmed.chars().any(char::is_whitespace) {
            return None;
        }
        let (host, port) = match trimmed.rfind(':') {
            Some(colon) => (&trimmed[..colon], trimmed[colon + 1..].parse::<u16>().ok()?),
            None => (trimmed, default_port),
        };
        if host.is_empty() || host.contains(':') || port == 0 {
            return None;
        }
        Some(Self::new(host, port))
    }
}

impl fmt::Display for Address {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}:{}", self.host, self.port)
    }
}

pub const TYPE_PAIR: u8 = 1;
pub const TYPE_HELLO: u8 = 2;
pub const HANDSHAKE_HEADER: usize = 10;

// The receiver index is 0 in the first message, before the other side has picked one.
#[derive(Clone, PartialEq, Eq, Debug)]
pub struct HandshakePacket {
    pub kind: u8,
    pub step: u8,
    pub sender_index: i32,
    pub receiver_index: i32,
    pub message: Vec<u8>,
}

pub fn encode_handshake(packet: &HandshakePacket) -> Vec<u8> {
    let mut out = Vec::with_capacity(HANDSHAKE_HEADER + packet.message.len());
    out.push(packet.kind);
    out.push(packet.step);
    out.extend_from_slice(&packet.sender_index.to_le_bytes());
    out.extend_from_slice(&packet.receiver_index.to_le_bytes());
    out.extend_from_slice(&packet.message);
    out
}

pub fn decode_handshake(datagram: &[u8]) -> Option<HandshakePacket> {
    if datagram.len() < HANDSHAKE_HEADER || (datagram[0] != TYPE_PAIR && datagram[0] != TYPE_HELLO) {
        return None;
    }
    Some(HandshakePacket {
        kind: datagram[0],
        step: datagram[1],
        sender_index: i32::from_le_bytes(datagram[2..6].try_into().ok()?),
        receiver_index: i32::from_le_bytes(datagram[6..10].try_into().ok()?),
        message: datagram[HANDSHAKE_HEADER..].to_vec(),
    })
}

pub fn data_receiver(datagram: &[u8]) -> Option<i32> {
    if datagram.len() < HEADER_LENGTH || datagram[0] != TYPE_DATA {
        return None;
    }
    Some(i32::from_le_bytes(datagram[1..5].try_into().ok()?))
}
