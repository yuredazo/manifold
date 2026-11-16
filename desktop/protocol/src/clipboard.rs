use crate::control::{CLIPBOARD_CHUNK, Control, MAX_CLIPBOARD_BYTES, MAX_CLIPBOARD_PARTS};

// None when the text is over the size limit; such a copy is not sent at all.
pub fn parts(stream_id: u16, seq: u32, text: &str) -> Option<Vec<Control>> {
    let bytes = text.as_bytes();
    if bytes.is_empty() || bytes.len() > MAX_CLIPBOARD_BYTES {
        return None;
    }
    let chunks: Vec<&[u8]> = bytes.chunks(CLIPBOARD_CHUNK).collect();
    let count = chunks.len() as u16;
    Some(
        chunks
            .into_iter()
            .enumerate()
            .map(|(part, data)| Control::Clipboard { stream_id, seq, part: part as u16, count, data: data.to_vec() })
            .collect(),
    )
}

// Parts can arrive out of order or twice. A newer copy replaces one still being put together.
#[derive(Default)]
pub struct Assembler {
    seq: u32,
    received: Vec<Option<Vec<u8>>>,
}

impl Assembler {
    pub fn add(&mut self, seq: u32, part: u16, count: u16, data: Vec<u8>) -> Option<String> {
        if count == 0 || count as usize > MAX_CLIPBOARD_PARTS || part >= count {
            return None;
        }
        if seq != self.seq || self.received.len() != count as usize {
            self.seq = seq;
            self.received = vec![None; count as usize];
        }
        self.received[part as usize] = Some(data);
        if self.received.iter().any(Option::is_none) {
            return None;
        }
        let bytes: Vec<u8> = std::mem::take(&mut self.received).into_iter().flatten().flatten().collect();
        if bytes.len() > MAX_CLIPBOARD_BYTES {
            return None;
        }
        String::from_utf8(bytes).ok()
    }
}
