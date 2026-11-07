use chacha20poly1305::aead::{Aead, KeyInit, Payload};
use chacha20poly1305::{ChaCha20Poly1305, Nonce};
use sha2::{Digest, Sha256};

pub const KEY_LENGTH: usize = 32;
pub const TAG_LENGTH: usize = 16;

#[derive(Clone)]
pub struct KeyPair {
    pub private: [u8; KEY_LENGTH],
    pub public: [u8; KEY_LENGTH],
}

impl KeyPair {
    pub fn generate() -> Self {
        let mut private = [0u8; KEY_LENGTH];
        getrandom::fill(&mut private).expect("the system has no random source");
        Self::from_private(private)
    }

    pub fn from_private(private: [u8; KEY_LENGTH]) -> Self {
        let mut base = [0u8; KEY_LENGTH];
        base[0] = 9;
        Self { private, public: x25519_dalek::x25519(private, base) }
    }
}

// A low-order point gives an all-zero secret whatever the scalar, so testing one clamped scalar tells.
pub fn is_weak_point(public: &[u8]) -> bool {
    let Ok(point) = <[u8; KEY_LENGTH]>::try_from(public) else { return true };
    x25519_dalek::x25519([1; KEY_LENGTH], point) == [0; KEY_LENGTH]
}

pub fn sha256(parts: &[&[u8]]) -> [u8; 32] {
    let mut hasher = Sha256::new();
    for part in parts {
        hasher.update(part);
    }
    hasher.finalize().into()
}

fn nonce(counter: u64) -> Nonce {
    let mut bytes = [0u8; 12];
    bytes[4..].copy_from_slice(&counter.to_le_bytes());
    Nonce::from(bytes)
}

pub fn seal(key: &[u8; KEY_LENGTH], counter: u64, associated: &[u8], plain: &[u8]) -> Vec<u8> {
    ChaCha20Poly1305::new(&(*key).into())
        .encrypt(&nonce(counter), Payload { msg: plain, aad: associated })
        .expect("sealing into a Vec cannot fail")
}

pub fn open(key: &[u8; KEY_LENGTH], counter: u64, associated: &[u8], sealed: &[u8]) -> Option<Vec<u8>> {
    if sealed.len() < TAG_LENGTH {
        return None;
    }
    ChaCha20Poly1305::new(&(*key).into()).decrypt(&nonce(counter), Payload { msg: sealed, aad: associated }).ok()
}

pub fn to_hex(bytes: &[u8]) -> String {
    bytes.iter().map(|byte| format!("{byte:02x}")).collect()
}

pub fn from_hex(text: &str) -> Option<Vec<u8>> {
    if !text.len().is_multiple_of(2) {
        return None;
    }
    (0..text.len()).step_by(2).map(|at| u8::from_str_radix(text.get(at..at + 2)?, 16).ok()).collect()
}

// The fingerprint, not an address or a name, is what identifies a device.
pub fn fingerprint_of(public: &[u8]) -> String {
    to_hex(&sha256(&[public]))
}

pub fn readable_fingerprint(fingerprint: &str) -> String {
    fingerprint.chars().take(16).collect::<Vec<_>>().chunks(4).map(|group| group.iter().collect::<String>()).collect::<Vec<_>>().join(" ")
}
