use crate::hub::Input;
use crate::system::{is_private, local_addresses};
use manifold_protocol::beacon::{self, Beacon};
use std::net::{SocketAddr, UdpSocket};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::Sender;
use std::time::Duration;

const TTL_MS: i64 = 4000;
const LIMIT: usize = 16;

#[derive(Clone, Debug, PartialEq)]
pub struct Nearby {
    pub id: String,
    pub name: String,
    pub host: String,
    pub port: u16,
}

pub struct Discovery {
    own_id: String,
    heard: Vec<(Nearby, i64)>,
    listening: Option<Arc<AtomicBool>>,
}

impl Discovery {
    pub fn new(own_id: String) -> Self {
        Self { own_id, heard: Vec::new(), listening: None }
    }

    pub fn listen(&mut self, out: Sender<Input>) {
        if self.listening.is_some() {
            return;
        }
        let Ok(socket) = UdpSocket::bind(("0.0.0.0", beacon::PORT)) else {
            eprintln!("discovery: cannot listen on port {}", beacon::PORT);
            return;
        };
        let _ = socket.set_broadcast(true);
        let _ = socket.set_read_timeout(Some(Duration::from_millis(300)));
        let stop = Arc::new(AtomicBool::new(false));
        self.listening = Some(stop.clone());
        let _ = std::thread::Builder::new().name("discovery".into()).spawn(move || {
            let mut buffer = [0u8; 512];
            while !stop.load(Ordering::Relaxed) {
                let Ok((size, from)) = socket.recv_from(&mut buffer) else { continue };
                if let (Some(found), SocketAddr::V4(from)) = (beacon::decode(&buffer[..size]), from) {
                    let _ = out.send(Input::Beacon(found, from.ip().to_string()));
                }
            }
        });
    }

    pub fn is_listening(&self) -> bool {
        self.listening.is_some()
    }

    pub fn stop(&mut self) {
        if let Some(stop) = self.listening.take() {
            stop.store(true, Ordering::Relaxed);
        }
        self.heard.clear();
    }

    pub fn hear(&mut self, found: Beacon, host: String, now_ms: i64) {
        if found.id == self.own_id || self.listening.is_none() {
            return;
        }
        self.prune(now_ms);
        let known = self.heard.iter().position(|(nearby, _)| nearby.id == found.id);
        if known.is_none() && self.heard.len() >= LIMIT {
            return;
        }
        let entry = (Nearby { id: found.id, name: found.name, host, port: found.port }, now_ms);
        match known {
            Some(index) => self.heard[index] = entry,
            None => self.heard.push(entry),
        }
    }

    pub fn current(&mut self, now_ms: i64) -> Vec<Nearby> {
        self.prune(now_ms);
        let mut list: Vec<Nearby> = self.heard.iter().map(|(nearby, _)| nearby.clone()).collect();
        list.sort_by_key(|nearby| nearby.name.to_lowercase());
        list
    }

    fn prune(&mut self, now_ms: i64) {
        self.heard.retain(|(_, at)| now_ms - at <= TTL_MS);
    }

    // Private networks only, one socket each so the beacon leaves the right adapter: a tunnel cannot carry a broadcast.
    pub fn announce(&self, name: &str, listen_port: u16) {
        let packet = beacon::encode(&Beacon { id: self.own_id.clone(), port: listen_port, name: name.to_string() });
        for local in local_addresses().into_iter().filter(|ip| is_private(*ip)) {
            let Ok(out) = UdpSocket::bind((local, 0)) else { continue };
            let _ = out.set_broadcast(true);
            let _ = out.send_to(&packet, ("255.255.255.255", beacon::PORT));
        }
    }
}
