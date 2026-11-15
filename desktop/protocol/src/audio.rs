pub const HEADER_LENGTH: usize = 6;
pub const ADTS_HEADER: usize = 7;

// AAC-LC at 48 kHz in two channels is the only format, so it is not sent along.
#[derive(Clone, PartialEq, Eq, Debug)]
pub struct AudioPacket {
    pub stream_id: u16,
    pub timestamp: i32,
    pub frame: Vec<u8>,
}

pub fn encode(stream_id: u16, timestamp: i32, frame: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(HEADER_LENGTH + frame.len());
    out.extend_from_slice(&stream_id.to_be_bytes());
    out.extend_from_slice(&timestamp.to_be_bytes());
    out.extend_from_slice(frame);
    out
}

pub fn decode(payload: &[u8]) -> Option<AudioPacket> {
    if payload.len() <= HEADER_LENGTH {
        return None;
    }
    Some(AudioPacket {
        stream_id: u16::from_be_bytes([payload[0], payload[1]]),
        timestamp: i32::from_be_bytes(payload[2..6].try_into().ok()?),
        frame: payload[HEADER_LENGTH..].to_vec(),
    })
}

// MPEG-4 AAC-LC, 48 kHz, two channels, no CRC, one frame per header.
pub fn adts_wrap(frame: &[u8]) -> Vec<u8> {
    let length = ADTS_HEADER + frame.len();
    let mut out = vec![0xFF, 0xF1, (1 << 6) | (3 << 2), (2 << 6) | (length >> 11) as u8, ((length >> 3) & 0xFF) as u8, (((length & 7) << 5) | 0x1F) as u8, 0xFC];
    out.extend_from_slice(frame);
    out
}
