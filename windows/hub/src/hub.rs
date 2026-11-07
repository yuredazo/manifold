use crate::capture::{Capture, Captured, ListPurpose, ShareKind, Shareable};
use crate::discovery::{Discovery, Nearby};
use crate::sharing::{ShareOptions, Sharing};
use crate::store::{LISTENING_KEY, Settings, Storage};
use crate::system::{self, local_addresses};
use crate::update::{self, Release};
use crate::watching::Watching;
use manifold_protocol::beacon::Beacon;
use manifold_protocol::control::{Control, FeedInfo, Refusal};
use manifold_protocol::crypto::readable_fingerprint;
use manifold_protocol::devices::Device;
use manifold_protocol::endpoint::{Endpoint, Event, PAIRING_WINDOW_MS};
use manifold_protocol::stream::StreamProfile;
use manifold_protocol::wire::Address;
use std::collections::HashMap;
use std::net::{Ipv4Addr, SocketAddr, SocketAddrV4, ToSocketAddrs, UdpSocket};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{Receiver, RecvTimeoutError, Sender};
use std::sync::{Arc, LazyLock};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

pub const LISTEN_PORT: u16 = 47200;
const TICK_MS: i64 = 250;
// A quarter second tick is too coarse to wait a couple of frames for a missing piece.
const POLL_MS: i64 = 4;
const CONNECT_EVERY_TICKS: u64 = 20;
const LATENCY_EVERY_TICKS: u64 = 4;
const ANNOUNCE_EVERY_TICKS: u64 = 4;
const PUSH_EVERY: Duration = Duration::from_millis(50);
const LINK_LOG: &str = "network.log";
const SHARES_FILE: &str = "shares.json";

static START: LazyLock<Instant> = LazyLock::new(Instant::now);

pub fn now_ns() -> i64 {
    START.elapsed().as_nanos() as i64
}

pub fn now_ms() -> i64 {
    START.elapsed().as_millis() as i64
}

pub fn local_clock() -> String {
    let time = unsafe { windows::Win32::System::SystemInformation::GetLocalTime() };
    format!("{:02}:{:02}", time.wHour, time.wMinute)
}

// The same form the Flutter hub wrote to network.log, so one log reads the same across both hubs.
fn local_timestamp() -> String {
    let time = unsafe { windows::Win32::System::SystemInformation::GetLocalTime() };
    format!(
        "{:04}-{:02}-{:02}T{:02}:{:02}:{:02}.{:03}",
        time.wYear, time.wMonth, time.wDay, time.wHour, time.wMinute, time.wSecond, time.wMilliseconds
    )
}

pub enum Input {
    Datagram(SocketAddrV4, Vec<u8>),
    Captured(Captured),
    Beacon(Beacon, String),
    PlayerClosed(u16),
    Resolved(Address, Option<Ipv4Addr>),
    PublicAddress(Option<String>),
    Update(Result<Option<Release>, String>),
    UpdateProgress(Option<f32>),
    UpdateInstalled(Result<(), String>),
    Command(Command),
}

pub enum Command {
    Listen(bool),
    OpenPairing,
    ClosePairing,
    Pair(String),
    ConfirmPairing(bool),
    CancelPairing,
    DismissPairing,
    Discover(bool),
    SetReceive(String, bool),
    SetSend(String, bool),
    SetSendCamera(String, bool),
    SetSendSpout(String, bool),
    Unpair(String),
    Watch(String, String),
    StopWatching(String, String),
    ListShareable,
    Share(Shareable, ShareOptions),
    Unshare(String),
    Pause(bool),
    DismissProblem,
    SetProfile(StreamProfile),
    SetOfferCameras(bool),
    SetCloseToTray(bool),
    SetCheckOnLaunch(bool),
    SetAutostart(bool),
    LookUpPublic,
    CheckUpdates,
    InstallUpdate,
    Quit,
}

#[derive(Clone, PartialEq, Debug)]
pub enum Pairing {
    None,
    Connecting(String),
    CodeShown { remote_name: String, code: String, confirmed: bool },
    Failed(String),
}

#[derive(Clone, Debug)]
pub enum UpdateState {
    Idle,
    Checking,
    UpToDate(String),
    Available(Release),
    Downloading(Release, Option<f32>),
    InstallFailed(String),
    Failed(String),
}

#[derive(Clone, Debug)]
pub enum PublicAddress {
    Unknown,
    Looking,
    Found(String),
    Failed,
}

#[derive(Clone)]
pub struct DeviceView {
    pub key: String,
    pub name: String,
    pub online: bool,
    pub latency: Option<i64>,
    pub receive: bool,
    pub send: bool,
    pub send_camera: bool,
    pub send_spout: bool,
    pub address: String,
    pub fingerprint: String,
    pub offered: usize,
    pub refused: Vec<String>,
}

#[derive(Clone)]
pub struct FeedView {
    pub name: String,
    pub label: String,
    pub detail: String,
    pub sound_only: bool,
    pub watching: bool,
}

#[derive(Clone)]
pub struct WatchGroup {
    pub device_key: String,
    pub name: String,
    pub online: bool,
    pub latency: Option<i64>,
    pub feeds: Vec<FeedView>,
}

#[derive(Clone)]
pub struct ShareView {
    pub feed: String,
    pub label: String,
    pub detail: String,
    pub kind: ShareKind,
    pub watching: usize,
}

#[derive(Clone)]
pub struct Snapshot {
    pub listening: bool,
    pub addresses: Vec<String>,
    pub fingerprint: String,
    pub pairing_left_ms: i64,
    pub pairing: Pairing,
    pub devices: Vec<DeviceView>,
    pub watch: Vec<WatchGroup>,
    pub shares: Vec<ShareView>,
    pub can_watch: usize,
    pub paused: bool,
    pub problem: Option<String>,
    pub activity: Vec<String>,
    pub nearby: Vec<Nearby>,
    pub picker: Option<Vec<Shareable>>,
    pub public_address: PublicAddress,
    pub profile: StreamProfile,
    pub offer_cameras: bool,
    pub close_to_tray: bool,
    pub check_on_launch: bool,
    pub autostart: bool,
    pub update: UpdateState,
    // The update is unpacked and its swap is waiting for this process to exit.
    pub restart: bool,
}

struct Listening {
    socket: UdpSocket,
    stop: Arc<AtomicBool>,
}

pub struct Hub {
    inputs: Receiver<Input>,
    out: Sender<Input>,
    storage: Storage,
    settings: Settings,
    endpoint: Endpoint,
    sharing: Sharing,
    watching: Watching,
    discovery: Discovery,
    listening: Option<Listening>,
    addresses: Vec<String>,
    resolved: HashMap<String, Ipv4Addr>,
    pairing: Pairing,
    pairing_open_until: i64,
    online: Vec<String>,
    latency: HashMap<String, i64>,
    remote_feeds: HashMap<String, Vec<FeedInfo>>,
    refused: HashMap<String, Vec<(String, Refusal)>>,
    picker: Option<Vec<Shareable>>,
    public_address: PublicAddress,
    update: UpdateState,
    restart: bool,
    autostart: bool,
    ticks: u64,
    redial_now: bool,
    dirty: bool,
    quit: bool,
    publish: Box<dyn Fn(Snapshot) + Send>,
}

impl Hub {
    pub fn new(storage: Storage, out: Sender<Input>, inputs: Receiver<Input>, publish: impl Fn(Snapshot) + Send + 'static) -> Self {
        let settings = Settings::load(&storage);
        let identity = storage.load_identity();
        let own_id: String = identity.fingerprint().chars().take(16).collect();
        let book = storage.load_devices();
        let endpoint = Endpoint::new(identity, book, now_ms, || SystemTime::now().duration_since(UNIX_EPOCH).map(|time| time.as_millis() as i64).unwrap_or(0));
        let saver = storage.clone();
        let mut sharing = Sharing::new(Capture::new(out.clone()), storage.read(SHARES_FILE).as_deref(), move |text| saver.write(SHARES_FILE, text));
        sharing.offer_cameras = settings.offer_cameras;
        Self {
            watching: Watching::new(out.clone()),
            inputs,
            out,
            storage,
            settings,
            endpoint,
            sharing,
            discovery: Discovery::new(own_id),
            listening: None,
            addresses: Vec::new(),
            resolved: HashMap::new(),
            pairing: Pairing::None,
            pairing_open_until: 0,
            online: Vec::new(),
            latency: HashMap::new(),
            remote_feeds: HashMap::new(),
            refused: HashMap::new(),
            picker: None,
            public_address: PublicAddress::Unknown,
            update: UpdateState::Idle,
            restart: false,
            autostart: system::autostart_enabled(),
            ticks: 0,
            redial_now: false,
            dirty: true,
            quit: false,
            publish: Box::new(publish),
        }
    }

    pub fn run(mut self) {
        unsafe {
            let _ = windows::Win32::System::Com::CoInitializeEx(None, windows::Win32::System::Com::COINIT_MULTITHREADED);
            windows::Win32::Media::timeBeginPeriod(1);
        }
        if self.storage.read(LISTENING_KEY).as_deref() == Some("on") {
            self.start();
        }
        if self.settings.check_on_launch {
            self.check_updates();
        }
        let mut next_tick = now_ms();
        let mut next_poll = now_ms();
        let mut pushed = Instant::now() - PUSH_EVERY;
        while !self.quit {
            let now = now_ms();
            let fast = self.watching.wants_fast_poll();
            let deadline = if fast { next_tick.min(next_poll) } else { next_tick };
            match self.inputs.recv_timeout(Duration::from_millis(deadline.saturating_sub(now).max(0) as u64)) {
                Ok(input) => self.handle(input),
                Err(RecvTimeoutError::Timeout) => {}
                Err(RecvTimeoutError::Disconnected) => break,
            }
            while let Ok(input) = self.inputs.try_recv() {
                self.handle(input);
                if self.quit {
                    break;
                }
            }
            let now = now_ms();
            if now >= next_tick {
                next_tick = now + TICK_MS;
                self.tick();
            }
            if self.watching.wants_fast_poll() && now >= next_poll {
                next_poll = now + POLL_MS;
                let profile = self.settings.stream_profile;
                self.watching.poll(now_ns(), profile, &mut self.endpoint);
            }
            self.settle();
            if (self.dirty || self.quit) && pushed.elapsed() >= PUSH_EVERY {
                pushed = Instant::now();
                self.dirty = false;
                let snapshot = self.snapshot();
                (self.publish)(snapshot);
            }
        }
        self.stop();
        self.flush();
    }

    fn handle(&mut self, input: Input) {
        match input {
            Input::Datagram(from, bytes) => self.endpoint.on_datagram(Address::new(from.ip().to_string(), from.port()), &bytes),
            Input::Captured(captured) => self.captured(captured),
            Input::Beacon(found, host) => {
                self.discovery.hear(found, host, now_ms());
                self.dirty = true;
            }
            Input::PlayerClosed(stream_id) => self.watching.on_player_closed(stream_id, &mut self.endpoint),
            Input::Resolved(address, ip) => match ip {
                Some(ip) => {
                    self.resolved.insert(address.host.clone(), ip);
                    if !self.endpoint.is_pairing() {
                        self.endpoint.pair(address);
                    }
                }
                None => self.set_pairing(Pairing::Failed(format!("could not find {}", address.host))),
            },
            Input::PublicAddress(found) => {
                self.public_address = found.map(|ip| PublicAddress::Found(format!("{ip}:{LISTEN_PORT}"))).unwrap_or(PublicAddress::Failed);
                self.dirty = true;
            }
            Input::Update(result) => {
                self.update = match result {
                    Ok(Some(release)) => UpdateState::Available(release),
                    Ok(None) => UpdateState::UpToDate(local_clock()),
                    Err(message) => UpdateState::Failed(message),
                };
                self.dirty = true;
            }
            Input::UpdateProgress(fraction) => {
                if let UpdateState::Downloading(release, _) = &self.update {
                    self.update = UpdateState::Downloading(release.clone(), fraction);
                    self.dirty = true;
                }
            }
            Input::UpdateInstalled(result) => {
                match result {
                    Ok(()) => self.restart = true,
                    Err(message) => self.update = UpdateState::InstallFailed(message),
                }
                self.dirty = true;
            }
            Input::Command(command) => {
                self.command(command);
                self.dirty = true;
            }
        }
    }

    fn captured(&mut self, captured: Captured) {
        match captured {
            Captured::Video { handle, timestamp, keyframe, data } => self.sharing.on_video(handle, timestamp, keyframe, data, &mut self.endpoint),
            Captured::Audio { handle, timestamp, data } => self.sharing.on_audio(handle, timestamp, &data, &mut self.endpoint),
            Captured::Closed(handle) => self.sharing.on_closed(handle, &self.endpoint),
            Captured::Started { handle, ok } => self.sharing.on_started(handle, ok, &mut self.endpoint),
            Captured::Listed { purpose: ListPurpose::Check, entries } => self.sharing.on_listed(&entries, &self.endpoint),
            Captured::Listed { purpose: ListPurpose::Picker, entries } => {
                let shared: Vec<i64> = self.sharing.shared.iter().map(|window| window.handle).collect();
                self.picker = Some(entries.into_iter().filter(|entry| !shared.contains(&entry.handle)).collect());
                self.dirty = true;
            }
        }
    }

    fn command(&mut self, command: Command) {
        match command {
            Command::Listen(on) => {
                if on {
                    self.start()
                } else {
                    self.stop()
                }
            }
            Command::OpenPairing => {
                self.start();
                self.endpoint.open_pairing(PAIRING_WINDOW_MS);
                self.pairing_open_until = now_ms() + PAIRING_WINDOW_MS;
            }
            Command::ClosePairing => {
                self.endpoint.close_pairing();
                self.pairing_open_until = 0;
            }
            Command::Pair(text) => self.pair(&text),
            Command::ConfirmPairing(accept) => {
                self.endpoint.confirm_pairing(accept);
                self.pairing = match std::mem::replace(&mut self.pairing, Pairing::None) {
                    Pairing::CodeShown { remote_name, code, .. } if accept => Pairing::CodeShown { remote_name, code, confirmed: true },
                    _ => Pairing::None,
                };
            }
            Command::CancelPairing => {
                self.endpoint.cancel_pairing();
                self.pairing = Pairing::None;
            }
            Command::DismissPairing => self.pairing = Pairing::None,
            Command::Discover(on) => {
                if on {
                    self.discovery.listen(self.out.clone())
                } else {
                    self.discovery.stop()
                }
            }
            Command::SetReceive(key, on) => self.update_device(&key, |device| Device { receive: on, ..device.clone() }),
            Command::SetSend(key, on) => self.update_device(&key, |device| Device { send: on, ..device.clone() }),
            Command::SetSendCamera(key, on) => self.update_device(&key, |device| Device { send_camera: on, ..device.clone() }),
            Command::SetSendSpout(key, on) => self.update_device(&key, |device| Device { send_spout: on, ..device.clone() }),
            Command::Unpair(key) => {
                self.sharing.drop_device(&key, &self.endpoint);
                self.watching.stop_where(&mut self.endpoint, |session| session.device_key == key);
                self.endpoint.unpair(&key);
                self.online.retain(|known| *known != key);
                self.remote_feeds.remove(&key);
            }
            Command::Watch(key, feed) => {
                let device = self.endpoint.book().find(&key).cloned();
                let info = self.remote_feeds.get(&key).and_then(|feeds| feeds.iter().find(|known| known.name == feed)).cloned();
                if let (Some(device), Some(info)) = (device, info) {
                    self.refused.entry(key).or_default().retain(|(name, _)| *name != feed);
                    self.watching.watch(&device, &info, self.settings.stream_profile, &mut self.endpoint);
                }
            }
            Command::StopWatching(key, feed) => self.watching.stop_where(&mut self.endpoint, |session| session.device_key == key && session.feed_name == feed),
            Command::ListShareable => {
                self.picker = None;
                self.sharing.list_for_picker();
            }
            Command::Share(window, options) => {
                self.sharing.share(&window, options);
                self.picker = None;
            }
            Command::Unshare(feed) => self.sharing.unshare(&feed, &self.endpoint),
            Command::Pause(paused) => self.sharing.set_paused(paused, &self.endpoint),
            Command::DismissProblem => self.sharing.dismiss_problem(),
            Command::SetProfile(profile) => self.settings.set_stream_profile(profile),
            Command::SetOfferCameras(on) => {
                self.settings.set_offer_cameras(on);
                self.sharing.set_offer_cameras(on, &self.endpoint);
            }
            Command::SetCloseToTray(on) => self.settings.set_close_to_tray(on),
            Command::SetCheckOnLaunch(on) => self.settings.set_check_on_launch(on),
            Command::SetAutostart(on) => {
                system::set_autostart(on);
                self.autostart = system::autostart_enabled();
            }
            // Looked up only when asked, since it tells an outside service this computer's address.
            Command::LookUpPublic => {
                self.public_address = PublicAddress::Looking;
                let out = self.out.clone();
                std::thread::spawn(move || {
                    let found = system::https_get("api.ipify.org", "/", 64)
                        .ok()
                        .and_then(|body| String::from_utf8(body).ok())
                        .map(|text| text.trim().to_string())
                        .filter(|text| text.parse::<std::net::IpAddr>().is_ok());
                    let _ = out.send(Input::PublicAddress(found));
                });
            }
            Command::CheckUpdates => self.check_updates(),
            Command::InstallUpdate => self.install_update(),
            Command::Quit => self.quit = true,
        }
    }

    fn install_update(&mut self) {
        let UpdateState::Available(release) = &self.update else { return };
        let release = release.clone();
        self.update = UpdateState::Downloading(release.clone(), None);
        let out = self.out.clone();
        std::thread::spawn(move || {
            let mut reported = -1.0f32;
            let result = update::install(&release, |fraction| {
                // A progress message per read would flood the network thread; whole percents are enough.
                let step = fraction.map(|value| (value * 100.0).floor()).unwrap_or(0.0);
                if step != reported {
                    reported = step;
                    let _ = out.send(Input::UpdateProgress(fraction));
                }
            });
            let _ = out.send(Input::UpdateInstalled(result));
        });
    }

    fn check_updates(&mut self) {
        self.update = UpdateState::Checking;
        self.dirty = true;
        let out = self.out.clone();
        std::thread::spawn(move || {
            let _ = out.send(Input::Update(update::latest_release()));
        });
    }

    fn update_device(&mut self, key: &str, change: impl FnOnce(&Device) -> Device) {
        self.endpoint.book_mut().update(key, change);
        self.devices_changed();
    }

    fn devices_changed(&mut self) {
        self.offer_to_all();
        self.sharing.enforce(&self.endpoint);
        let book: Vec<Device> = self.endpoint.book().devices().to_vec();
        self.watching.stop_where(&mut self.endpoint, |session| !book.iter().any(|device| device.public_key == session.device_key && device.receive));
    }

    fn pair(&mut self, text: &str) {
        let Some(address) = Address::parse(text.trim(), LISTEN_PORT) else {
            self.set_pairing(Pairing::Failed("that is not a usable address".into()));
            return;
        };
        self.start();
        if self.endpoint.is_pairing() {
            return;
        }
        self.pairing = Pairing::Connecting(address.to_string());
        if address.host.parse::<Ipv4Addr>().is_ok() {
            self.endpoint.pair(address);
            return;
        }
        let out = self.out.clone();
        std::thread::spawn(move || {
            let ip = (address.host.as_str(), address.port).to_socket_addrs().ok().and_then(|mut found| {
                found.find_map(|socket| match socket {
                    SocketAddr::V4(v4) => Some(*v4.ip()),
                    SocketAddr::V6(_) => None,
                })
            });
            let _ = out.send(Input::Resolved(address, ip));
        });
    }

    fn set_pairing(&mut self, pairing: Pairing) {
        self.pairing = pairing;
        self.dirty = true;
    }

    fn start(&mut self) {
        if self.listening.is_some() {
            return;
        }
        let Ok(socket) = UdpSocket::bind(("0.0.0.0", LISTEN_PORT)) else {
            eprintln!("network: port {LISTEN_PORT} is taken");
            return;
        };
        let Ok(receiver) = socket.try_clone() else { return };
        let _ = receiver.set_read_timeout(Some(Duration::from_millis(200)));
        let stop = Arc::new(AtomicBool::new(false));
        let stopping = stop.clone();
        let out = self.out.clone();
        let _ = std::thread::Builder::new().name("receive".into()).spawn(move || {
            let mut buffer = vec![0u8; 65_536];
            while !stopping.load(Ordering::Relaxed) {
                match receiver.recv_from(&mut buffer) {
                    Ok((size, SocketAddr::V4(from))) => {
                        if out.send(Input::Datagram(from, buffer[..size].to_vec())).is_err() {
                            break;
                        }
                    }
                    Ok(_) => {}
                    Err(_) => {}
                }
            }
        });
        self.listening = Some(Listening { socket, stop });
        self.addresses = own_addresses();
        self.ticks = 0;
        self.storage.write(LISTENING_KEY, "on");
        self.dirty = true;
    }

    fn stop(&mut self) {
        if self.listening.is_none() {
            return;
        }
        self.sharing.drop_all(&self.endpoint);
        self.watching.stop_where(&mut self.endpoint, |_| true);
        let keys: Vec<String> = self.endpoint.book().devices().iter().map(|device| device.public_key.clone()).collect();
        for key in keys {
            self.endpoint.disconnect(&key);
        }
        self.flush();
        if let Some(listening) = self.listening.take() {
            listening.stop.store(true, Ordering::Relaxed);
        }
        self.online.clear();
        self.remote_feeds.clear();
        self.pairing_open_until = 0;
        self.endpoint.close_pairing();
        self.pairing = Pairing::None;
        if !self.quit {
            self.storage.write(LISTENING_KEY, "off");
        }
        self.dirty = true;
    }

    fn tick(&mut self) {
        self.endpoint.tick();
        let now = now_ms();
        self.sharing.tick(now, &self.endpoint);
        self.watching.tick(now, &mut self.endpoint);
        if self.listening.is_none() {
            return;
        }
        if self.pairing_open_until != 0 && now <= self.pairing_open_until {
            if self.ticks.is_multiple_of(ANNOUNCE_EVERY_TICKS) {
                self.discovery.announce(&self.endpoint.identity().name, LISTEN_PORT);
            }
            if self.ticks.is_multiple_of(4) {
                self.dirty = true;
            }
        }
        if self.redial_now || self.ticks.is_multiple_of(CONNECT_EVERY_TICKS) {
            self.redial_now = false;
            self.connect_known_devices();
        }
        if self.ticks.is_multiple_of(LATENCY_EVERY_TICKS) {
            self.update_latency();
        }
        self.ticks += 1;
        if self.pairing_open_until != 0 && now > self.pairing_open_until {
            self.pairing_open_until = 0;
            self.dirty = true;
        }
        if self.discovery.is_listening() && self.ticks.is_multiple_of(4) {
            self.dirty = true;
        }
    }

    fn update_latency(&mut self) {
        let latency: HashMap<String, i64> =
            self.online.iter().filter_map(|key| self.endpoint.rtt_ms(key).map(|rtt| (key.clone(), rtt.round() as i64))).collect();
        if latency != self.latency {
            self.latency = latency;
            self.dirty = true;
        }
    }

    fn connect_known_devices(&mut self) {
        let devices: Vec<Device> = self.endpoint.book().devices().to_vec();
        for device in devices {
            let addresses: Vec<Address> = device.addresses().iter().filter_map(|text| Address::parse(text, LISTEN_PORT)).collect();
            if addresses.is_empty() || self.endpoint.is_linked(&device.public_key) || self.endpoint.is_dialing(&device.public_key) {
                continue;
            }
            self.endpoint.connect(&device, addresses);
        }
    }

    fn offer_feeds(&mut self, key: &str) {
        let Some(device) = self.endpoint.book().find(key).cloned() else { return };
        if !self.endpoint.is_linked(key) {
            return;
        }
        let feeds = self.sharing.feeds_for(&device);
        self.endpoint.send_to(key, &Control::FeedList(feeds));
    }

    fn offer_to_all(&mut self) {
        let keys: Vec<String> = self.endpoint.book().devices().iter().map(|device| device.public_key.clone()).collect();
        for key in keys {
            self.offer_feeds(&key);
        }
    }

    fn settle(&mut self) {
        loop {
            let events = self.endpoint.take_events();
            if events.is_empty() {
                break;
            }
            for event in events {
                self.event(event);
            }
        }
        if std::mem::take(&mut self.sharing.feeds_changed) {
            self.offer_to_all();
        }
        self.dirty |= std::mem::take(&mut self.sharing.changed) | std::mem::take(&mut self.watching.changed);
        self.flush();
    }

    fn flush(&mut self) {
        let outgoing = self.endpoint.take_outgoing();
        let Some(listening) = &self.listening else { return };
        for (to, bytes) in outgoing {
            let Some(ip) = self.resolved.get(&to.host).copied().or_else(|| to.host.parse().ok()) else { continue };
            let _ = listening.socket.send_to(&bytes, SocketAddrV4::new(ip, to.port));
        }
    }

    fn event(&mut self, event: Event) {
        match event {
            Event::PairingCode { remote_name, code, .. } => self.set_pairing(Pairing::CodeShown { remote_name, code, confirmed: false }),
            Event::Paired(_) => {
                self.pairing = Pairing::None;
                self.pairing_open_until = 0;
                self.endpoint.close_pairing();
                self.dirty = true;
            }
            Event::PairingFailed(reason) => self.set_pairing(Pairing::Failed(reason)),
            Event::LinkUp(device, address) => {
                let key = device.public_key.clone();
                if !self.online.contains(&key) {
                    self.online.push(key.clone());
                }
                let at = address.to_string();
                self.endpoint.book_mut().update(&key, |known| known.reached_at(&at));
                self.offer_feeds(&key);
                // Listed again here, since a VPN such as Tailscale may have come up after listening started.
                self.addresses = own_addresses();
                let own: Vec<Address> = self.addresses.iter().filter_map(|text| Address::parse(text, LISTEN_PORT)).collect();
                self.endpoint.send_to(&key, &Control::Addresses(own));
                self.dirty = true;
            }
            Event::LinkDown(device) => {
                let key = device.public_key;
                self.redial_now = true;
                self.online.retain(|known| *known != key);
                self.remote_feeds.remove(&key);
                self.refused.remove(&key);
                self.sharing.drop_device(&key, &self.endpoint);
                self.watching.stop_where(&mut self.endpoint, |session| session.device_key == key);
                self.dirty = true;
            }
            Event::LinkResumed(device) => self.watching.on_link_resumed(&device),
            Event::ConnectFailed(_) => {}
            Event::Feeds(device, feeds) => {
                self.refused.remove(&device.public_key);
                self.watching.on_feeds(&device, &feeds, &mut self.endpoint);
                self.remote_feeds.insert(device.public_key, feeds);
                self.dirty = true;
            }
            Event::Addresses(device, told) => {
                let told: Vec<String> = told.iter().map(Address::to_string).collect();
                self.endpoint.book_mut().update(&device.public_key, |known| known.also_at(&told));
            }
            Event::Subscribe(device, request) => self.sharing.on_subscribe(&device, &request, &mut self.endpoint),
            Event::SubscribeRefused { device, stream_id, reason } => {
                if let Some(session) = self.watching.sessions.iter().find(|session| session.stream_id == stream_id && session.device_key == device.public_key) {
                    let feed = session.feed_name.clone();
                    let list = self.refused.entry(device.public_key.clone()).or_default();
                    list.retain(|(name, _)| *name != feed);
                    list.push((feed, reason));
                    self.watching.stop(stream_id, &mut self.endpoint);
                    self.dirty = true;
                }
            }
            Event::Unsubscribe(device, stream_id) => self.sharing.unsubscribe(&device.public_key, stream_id, &self.endpoint),
            Event::KeyframeRequest(device, stream_id) => self.sharing.request_keyframe(&device.public_key, stream_id),
            Event::Nack { device, stream_id, frame_id, indexes } => self.sharing.resend(&device.public_key, stream_id, frame_id, &indexes, &mut self.endpoint),
            Event::StreamReport { device, stream_id, fragments, resend_requests, lost_frames, .. } => {
                self.sharing.on_report(&device.public_key, stream_id, fragments, resend_requests, lost_frames)
            }
            Event::SenderStats { .. } => {}
            Event::Video(device, stream_id, fragment) => self.watching.on_video(&device, stream_id, &fragment),
            Event::Audio(device, stream_id, _, frame) => self.watching.on_audio(&device, stream_id, frame),
            Event::Log(message) => self.storage.append(LINK_LOG, &format!("{} {message}", local_timestamp())),
        }
    }

    fn snapshot(&mut self) -> Snapshot {
        let now = now_ms();
        let book: Vec<Device> = self.endpoint.book().devices().to_vec();
        let devices = book
            .iter()
            .map(|device| {
                let online = self.online.contains(&device.public_key);
                DeviceView {
                    key: device.public_key.clone(),
                    name: device.name.clone(),
                    online,
                    latency: self.latency.get(&device.public_key).copied().filter(|_| online),
                    receive: device.receive,
                    send: device.send,
                    send_camera: device.send_camera,
                    send_spout: device.send_spout,
                    address: device.address.clone().unwrap_or_default(),
                    fingerprint: readable_fingerprint(&device.fingerprint()),
                    offered: self.remote_feeds.get(&device.public_key).map(Vec::len).unwrap_or(0),
                    refused: self.refused.get(&device.public_key).map(|list| list.iter().map(|(feed, reason)| refusal_text(feed, *reason)).collect()).unwrap_or_default(),
                }
            })
            .collect();
        let watch = book
            .iter()
            .filter(|device| device.receive)
            .map(|device| {
                let online = self.online.contains(&device.public_key);
                let feeds = if online { self.remote_feeds.get(&device.public_key).cloned().unwrap_or_default() } else { vec![] };
                WatchGroup {
                    device_key: device.public_key.clone(),
                    name: device.name.clone(),
                    online,
                    latency: self.latency.get(&device.public_key).copied().filter(|_| online),
                    feeds: feeds
                        .iter()
                        .map(|feed| {
                            let session = self.watching.of(&device.public_key, &feed.name);
                            FeedView {
                                name: feed.name.clone(),
                                label: feed.label().to_string(),
                                detail: feed_detail(feed, session.map(|session| session.frames)),
                                sound_only: feed.sound_only,
                                watching: session.is_some(),
                            }
                        })
                        .collect(),
                }
            })
            .collect();
        let shares = self
            .sharing
            .shared
            .iter()
            .map(|window| ShareView {
                feed: window.feed_name.clone(),
                label: window.label().to_string(),
                detail: share_detail(window),
                kind: window.kind,
                watching: window.watchers.len(),
            })
            .collect();
        Snapshot {
            listening: self.listening.is_some(),
            addresses: if self.listening.is_some() { self.addresses.clone() } else { vec![] },
            fingerprint: readable_fingerprint(&self.endpoint.identity().fingerprint()),
            pairing_left_ms: if self.pairing_open_until != 0 { (self.pairing_open_until - now).max(0) } else { 0 },
            pairing: self.pairing.clone(),
            can_watch: book.iter().filter(|device| device.send || device.send_camera || device.send_spout).count(),
            devices,
            watch,
            shares,
            paused: self.sharing.paused,
            problem: self.sharing.problem.clone(),
            activity: self
                .sharing
                .activity
                .iter()
                .take(6)
                .map(|entry| format!("{}  {} {} watching {}", entry.time, entry.device, if entry.started { "started" } else { "stopped" }, entry.feed))
                .collect(),
            nearby: {
                let known: Vec<String> = book.iter().map(|device| device.fingerprint().chars().take(16).collect()).collect();
                self.discovery.current(now).into_iter().filter(|found| !known.contains(&found.id)).collect()
            },
            picker: self.picker.clone(),
            public_address: self.public_address.clone(),
            profile: self.settings.stream_profile,
            offer_cameras: self.settings.offer_cameras,
            close_to_tray: self.settings.close_to_tray,
            check_on_launch: self.settings.check_on_launch,
            autostart: self.autostart,
            update: self.update.clone(),
            restart: self.restart,
        }
    }
}

fn own_addresses() -> Vec<String> {
    local_addresses().into_iter().map(|ip| format!("{ip}:{LISTEN_PORT}")).collect()
}

fn feed_detail(feed: &FeedInfo, frames: Option<u64>) -> String {
    match (frames, feed.sound_only) {
        (Some(0), true) => return "Waiting for the sound".into(),
        (Some(_), true) => return "Playing".into(),
        (Some(0), false) => return "Waiting for the first picture".into(),
        (Some(_), false) => return "Playing in its own window".into(),
        (None, true) => return "Sound only".into(),
        (None, false) => {}
    }
    let mut parts = Vec::new();
    if feed.width > 0 && feed.height > 0 {
        parts.push(format!("{} x {}", feed.width, feed.height));
    }
    if feed.fps > 0 {
        parts.push(format!("{} fps", feed.fps));
    }
    if feed.has_audio {
        parts.push("sound".into());
    }
    parts.join(" · ")
}

fn share_detail(window: &crate::sharing::SharedWindow) -> String {
    // A display's name already says what it is.
    let mut parts: Vec<String> = match window.kind {
        ShareKind::Display => vec![],
        ShareKind::Camera => vec!["Camera".into()],
        ShareKind::Spout => vec!["Spout".into()],
        ShareKind::Window => vec![window.process.clone()],
    };
    if window.sound_only {
        parts.push("sound only".into());
    } else {
        if window.with_audio {
            parts.push("sound".into());
        }
        parts.push(format!("{} fps", window.frame_rate));
        if window.kind == ShareKind::Spout && window.with_alpha {
            parts.push("transparent".into());
        }
        if !window.show_cursor && window.kind != ShareKind::Camera && window.kind != ShareKind::Spout {
            parts.push("no pointer".into());
        }
        if window.hide_frame {
            parts.push("no title bar".into());
        }
    }
    let kind = match window.kind {
        ShareKind::Window => "window",
        ShareKind::Display => "display",
        ShareKind::Camera => "camera",
        ShareKind::Spout => "sender",
    };
    parts.push(match (window.present(), window.watchers.len()) {
        (false, _) => format!("waiting for the {kind}"),
        (true, 0) => "nobody watching".into(),
        (true, count) => format!("{count} watching"),
    });
    parts.join(" · ")
}

fn refusal_text(feed: &str, reason: Refusal) -> String {
    match reason {
        Refusal::NotShared => format!("{feed}: that device has not allowed this one to watch. Turn it on for this PC there."),
        Refusal::NotFound => format!("{feed}: that device no longer has this feed."),
        Refusal::Busy => format!("{feed}: that device is already sending as many streams as it allows."),
        Refusal::Failed => format!("{feed}: that device could not start sending it."),
    }
}
