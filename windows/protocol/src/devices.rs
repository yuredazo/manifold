use crate::control::MAX_NAME_LENGTH;
use crate::crypto::{KEY_LENGTH, KeyPair, fingerprint_of, from_hex};

pub const MAX_OTHER_ADDRESSES: usize = 8;

#[derive(Clone)]
pub struct Identity {
    pub keys: KeyPair,
    pub name: String,
}

impl Identity {
    pub fn fingerprint(&self) -> String {
        fingerprint_of(&self.keys.public)
    }
}

// address is where the device was last reached; others are the rest of the addresses it told us about.
#[derive(Clone, PartialEq, Eq, Debug, Default)]
pub struct Device {
    pub public_key: String,
    pub name: String,
    pub address: Option<String>,
    pub receive: bool,
    pub send: bool,
    pub send_camera: bool,
    pub send_spout: bool,
    pub others: Vec<String>,
}

fn unique(addresses: impl IntoIterator<Item = String>) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    for address in addresses {
        if !out.contains(&address) {
            out.push(address);
        }
    }
    out.truncate(MAX_OTHER_ADDRESSES);
    out
}

impl Device {
    pub fn new(public_key: &str, name: &str, address: Option<&str>) -> Self {
        Self { public_key: public_key.to_string(), name: name.to_string(), address: address.map(str::to_string), ..Default::default() }
    }

    pub fn addresses(&self) -> Vec<String> {
        self.address.iter().cloned().chain(self.others.iter().cloned()).collect()
    }

    pub fn reached_at(&self, at: &str) -> Self {
        Self { address: Some(at.to_string()), others: unique(self.addresses().into_iter().filter(|known| known != at)), ..self.clone() }
    }

    // What the device reports comes first, and older entries, such as a public address typed by hand, stay behind it until pushed out.
    pub fn also_at(&self, told: &[String]) -> Self {
        let others = unique(told.iter().cloned().chain(self.others.iter().cloned()).filter(|known| Some(known) != self.address.as_ref()));
        Self { others, ..self.clone() }
    }

    pub fn fingerprint(&self) -> String {
        from_hex(&self.public_key).map(|key| fingerprint_of(&key)).unwrap_or_default()
    }
}

// Same tab-separated file as the Flutter hub, so a Windows PC keeps its pairings when it switches hubs.
pub struct DeviceBook {
    entries: Vec<Device>,
    save: Box<dyn FnMut(&str) + Send>,
}

impl DeviceBook {
    pub fn new(stored: Option<&str>, save: impl FnMut(&str) + Send + 'static) -> Self {
        let mut book = Self { entries: Vec::new(), save: Box::new(save) };
        for line in stored.unwrap_or("").split('\n') {
            book.parse(line);
        }
        book
    }

    pub fn devices(&self) -> &[Device] {
        &self.entries
    }

    pub fn find(&self, public_key: &str) -> Option<&Device> {
        self.entries.iter().find(|device| device.public_key == public_key)
    }

    pub fn put(&mut self, device: Device) {
        let device = Device { name: clean(&device.name), ..device };
        match self.entries.iter_mut().find(|known| known.public_key == device.public_key) {
            Some(known) => *known = device,
            None => self.entries.push(device),
        }
        self.changed();
    }

    pub fn update(&mut self, public_key: &str, change: impl FnOnce(&Device) -> Device) {
        let Some(known) = self.entries.iter_mut().find(|known| known.public_key == public_key) else { return };
        *known = change(known);
        self.changed();
    }

    pub fn remove(&mut self, public_key: &str) {
        let before = self.entries.len();
        self.entries.retain(|known| known.public_key != public_key);
        if self.entries.len() != before {
            self.changed();
        }
    }

    // The other addresses share the address field, comma separated: an older hub keeps the device but waits for it to dial in.
    fn changed(&mut self) {
        let text = self
            .entries
            .iter()
            .map(|d| [d.public_key.clone(), d.name.clone(), d.addresses().join(","), d.receive.to_string(), d.send.to_string(), d.send_camera.to_string(), d.send_spout.to_string()].join("\t"))
            .collect::<Vec<_>>()
            .join("\n");
        (self.save)(&text);
    }

    // A book from before cameras or Spout could be shared has fewer fields, and what is missing stays off.
    fn parse(&mut self, line: &str) {
        let parts: Vec<&str> = line.split('\t').collect();
        if parts.len() < 5 || parts[0].len() != 2 * KEY_LENGTH || from_hex(parts[0]).is_none() {
            return;
        }
        let addresses: Vec<String> = parts[2].split(',').filter(|address| !address.trim().is_empty()).map(str::to_string).collect();
        let device = Device {
            public_key: parts[0].to_string(),
            name: parts[1].to_string(),
            address: addresses.first().cloned(),
            others: addresses.iter().skip(1).cloned().collect(),
            receive: parts[3] == "true",
            send: parts[4] == "true",
            send_camera: parts.get(5) == Some(&"true"),
            send_spout: parts.get(6) == Some(&"true"),
        };
        self.entries.retain(|known| known.public_key != device.public_key);
        self.entries.push(device);
    }
}

fn clean(name: &str) -> String {
    let cleaned = name.replace(['\t', '\n'], " ");
    let trimmed = cleaned.trim();
    let units: Vec<u16> = trimmed.encode_utf16().take(MAX_NAME_LENGTH).collect();
    String::from_utf16_lossy(&units)
}
