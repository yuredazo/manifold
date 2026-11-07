use crate::crypto::{from_hex, to_hex};

pub const PORT: u16 = 47201;
pub const MAX_NAME_BYTES: usize = 64;
const MAGIC: [u8; 4] = *b"MNFD";
const VERSION: u8 = 1;
const ID_BYTES: usize = 8;
const HEADER: usize = 16;

// What a device broadcasts while it lets others pair with it. id is the first 8 bytes of its fingerprint, in hex.
#[derive(Clone, PartialEq, Eq, Debug)]
pub struct Beacon {
    pub id: String,
    pub port: u16,
    pub name: String,
}

// "MNFD" | version | port(2) | id(8) | name length | name. Nothing is authenticated; the pairing code establishes who answers.
pub fn encode(beacon: &Beacon) -> Vec<u8> {
    let id = from_hex(&beacon.id).filter(|id| id.len() == ID_BYTES).expect("the id is 8 bytes of hex");
    assert!(beacon.port != 0, "a beacon needs a port");
    let mut name = beacon.name.as_str();
    while name.len() > MAX_NAME_BYTES {
        let mut cut = name.len() - 1;
        while !name.is_char_boundary(cut) {
            cut -= 1;
        }
        name = &name[..cut];
    }
    let mut out = MAGIC.to_vec();
    out.push(VERSION);
    out.extend_from_slice(&beacon.port.to_be_bytes());
    out.extend_from_slice(&id);
    out.push(name.len() as u8);
    out.extend_from_slice(name.as_bytes());
    out
}

pub fn decode(bytes: &[u8]) -> Option<Beacon> {
    if bytes.len() < HEADER || bytes[..4] != MAGIC || bytes[4] != VERSION {
        return None;
    }
    let port = u16::from_be_bytes([bytes[5], bytes[6]]);
    let length = bytes[15] as usize;
    if port == 0 || length > MAX_NAME_BYTES || bytes.len() < HEADER + length {
        return None;
    }
    let text = std::str::from_utf8(&bytes[HEADER..HEADER + length]).ok()?;
    let name: String = text.chars().filter(|c| !matches!(*c as u32, 0x00..=0x1F | 0x7F)).collect::<String>().trim().to_string();
    if name.is_empty() {
        return None;
    }
    Some(Beacon { id: to_hex(&bytes[7..7 + ID_BYTES]), port, name })
}
