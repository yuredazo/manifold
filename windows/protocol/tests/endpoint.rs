use manifold_protocol::control::{Control, FeedInfo, Refusal, Subscribe};
use manifold_protocol::crypto::{KeyPair, to_hex};
use manifold_protocol::devices::{Device, DeviceBook, Identity};
use manifold_protocol::endpoint::{
    CONFIRM_TIMEOUT_MS, Endpoint, Event, HANDSHAKE_TIMEOUT_MS, LINK_TIMEOUT_MS, MAX_HANDSHAKE_RESENDS, PAIRING_WINDOW_MS, PROBE_INTERVAL_MS,
};
use manifold_protocol::session::TYPE_DATA;
use manifold_protocol::video::{Frame, FrameBuffer, split};
use manifold_protocol::wire::{Address, TYPE_HELLO};
use std::collections::{HashMap, VecDeque};
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Arc, Mutex};

struct Node {
    address: Address,
    identity: Identity,
    stored: Arc<Mutex<Option<String>>>,
    endpoint: Endpoint,
    events: Vec<Event>,
}

impl Node {
    fn public_key(&self) -> String {
        to_hex(&self.identity.keys.public)
    }

    fn device(&self, other: &Node) -> Device {
        self.endpoint.book().find(&other.public_key()).cloned().expect("paired")
    }

    fn count(&self, matches: impl Fn(&Event) -> bool) -> usize {
        self.events.iter().filter(|event| matches(event)).count()
    }

    fn codes(&self) -> Vec<String> {
        self.events.iter().filter_map(|event| if let Event::PairingCode { code, .. } = event { Some(code.clone()) } else { None }).collect()
    }

    fn paired(&self) -> Vec<Device> {
        self.events.iter().filter_map(|event| if let Event::Paired(device) = event { Some(device.clone()) } else { None }).collect()
    }

    fn failures(&self) -> Vec<String> {
        self.events.iter().filter_map(|event| if let Event::PairingFailed(reason) = event { Some(reason.clone()) } else { None }).collect()
    }

    fn logs(&self) -> Vec<String> {
        self.events.iter().filter_map(|event| if let Event::Log(text) = event { Some(text.clone()) } else { None }).collect()
    }

    fn links_up(&self) -> Vec<Device> {
        self.events.iter().filter_map(|event| if let Event::LinkUp(device, _) = event { Some(device.clone()) } else { None }).collect()
    }

    fn links_down(&self) -> usize {
        self.count(|event| matches!(event, Event::LinkDown(_)))
    }

    fn feeds(&self) -> Vec<Vec<FeedInfo>> {
        self.events.iter().filter_map(|event| if let Event::Feeds(_, feeds) = event { Some(feeds.clone()) } else { None }).collect()
    }

    fn video(&self) -> Vec<(u16, Vec<u8>)> {
        self.events.iter().filter_map(|event| if let Event::Video(_, stream, fragment) = event { Some((*stream, fragment.clone())) } else { None }).collect()
    }
}

type DropRule = Box<dyn FnMut(usize, &[u8]) -> bool>;

struct Sent {
    from: Address,
    to: Address,
    bytes: Vec<u8>,
}

struct World {
    now: Arc<AtomicI64>,
    nodes: Vec<Node>,
    routes: HashMap<Address, usize>,
    log: Vec<Sent>,
    queue: VecDeque<(Address, Address, Vec<u8>)>,
    drop_if: DropRule,
    cut: bool,
    sent: usize,
}

const ALPHA: usize = 0;
const BETA: usize = 1;

impl World {
    fn new() -> Self {
        Self {
            now: Arc::new(AtomicI64::new(1_000_000)),
            nodes: Vec::new(),
            routes: HashMap::new(),
            log: Vec::new(),
            queue: VecDeque::new(),
            drop_if: Box::new(|_, _| false),
            cut: false,
            sent: 0,
        }
    }

    fn add(&mut self, name: &str, host: &str) -> usize {
        let identity = Identity { keys: KeyPair::generate(), name: name.to_string() };
        let stored = Arc::new(Mutex::new(None));
        let address = Address::new(host, 4000);
        let endpoint = self.endpoint(&identity, &stored);
        self.nodes.push(Node { address: address.clone(), identity, stored, endpoint, events: Vec::new() });
        self.routes.insert(address, self.nodes.len() - 1);
        self.nodes.len() - 1
    }

    fn endpoint(&self, identity: &Identity, stored: &Arc<Mutex<Option<String>>>) -> Endpoint {
        let text = stored.lock().unwrap().clone();
        let saving = stored.clone();
        let book = DeviceBook::new(text.as_deref(), move |text| *saving.lock().unwrap() = Some(text.to_string()));
        let (clock, wall) = (self.now.clone(), self.now.clone());
        Endpoint::new(identity.clone(), book, move || clock.load(Ordering::SeqCst), move || wall.load(Ordering::SeqCst))
    }

    // A restart: the endpoint forgets everything except what its book saved.
    fn restart(&mut self, node: usize) {
        let endpoint = self.endpoint(&self.nodes[node].identity, &self.nodes[node].stored);
        self.nodes[node].endpoint = endpoint;
    }

    fn collect(&mut self, node: usize) {
        let from = self.nodes[node].address.clone();
        for (to, bytes) in self.nodes[node].endpoint.take_outgoing() {
            self.log.push(Sent { from: from.clone(), to: to.clone(), bytes: bytes.clone() });
            self.queue.push_back((from.clone(), to, bytes));
        }
        let events = self.nodes[node].endpoint.take_events();
        self.nodes[node].events.extend(events);
    }

    fn with(&mut self, node: usize, action: impl FnOnce(&mut Endpoint)) {
        action(&mut self.nodes[node].endpoint);
        self.collect(node);
    }

    fn pump(&mut self) {
        while let Some((from, to, bytes)) = self.queue.pop_front() {
            let index = self.sent;
            self.sent += 1;
            if self.cut || (self.drop_if)(index, &bytes) {
                continue;
            }
            if let Some(&node) = self.routes.get(&to) {
                self.nodes[node].endpoint.on_datagram(from, &bytes);
                self.collect(node);
            }
        }
    }

    fn advance(&mut self, ms: i64) {
        let mut left = ms;
        while left > 0 {
            self.now.fetch_add(100, Ordering::SeqCst);
            for node in 0..self.nodes.len() {
                self.nodes[node].endpoint.tick();
                self.collect(node);
            }
            self.pump();
            left -= 100;
        }
    }

    fn pk(&self, node: usize) -> String {
        self.nodes[node].public_key()
    }

    fn address(&self, node: usize) -> Address {
        self.nodes[node].address.clone()
    }

    fn linked_both(&self, a: usize, b: usize) -> bool {
        self.nodes[a].endpoint.is_linked(&self.pk(b)) && self.nodes[b].endpoint.is_linked(&self.pk(a))
    }

    fn dial(&mut self, from: usize, to: usize, addresses: Vec<Address>) {
        let device = self.nodes[from].device(&self.nodes[to]);
        self.with(from, |endpoint| endpoint.connect(&device, addresses));
    }

    fn pair(&mut self, initiator: usize, responder: usize) {
        self.with(responder, |endpoint| endpoint.open_pairing(PAIRING_WINDOW_MS));
        let address = self.address(responder);
        self.with(initiator, |endpoint| endpoint.pair(address));
        self.pump();
    }
}

fn unpaired_world() -> World {
    let mut world = World::new();
    world.add("Alpha", "10.0.0.1");
    world.add("Beta", "10.0.0.2");
    world
}

fn paired_world() -> World {
    let mut world = unpaired_world();
    world.pair(ALPHA, BETA);
    world.with(ALPHA, |endpoint| endpoint.confirm_pairing(true));
    world.with(BETA, |endpoint| endpoint.confirm_pairing(true));
    world.pump();
    world
}

#[test]
fn two_owners_pair_by_comparing_the_same_code() {
    let mut world = unpaired_world();
    world.pair(ALPHA, BETA);

    let codes = world.nodes[ALPHA].codes();
    assert_eq!(codes.len(), 1);
    assert_eq!(codes, world.nodes[BETA].codes());
    assert!(codes[0].len() == 6 && codes[0].chars().all(|c| c.is_ascii_digit()));
    assert!(world.nodes[ALPHA].paired().is_empty() && world.nodes[BETA].paired().is_empty(), "nothing is saved before both confirm");

    world.with(ALPHA, |endpoint| endpoint.confirm_pairing(true));
    world.pump();
    assert!(world.nodes[ALPHA].paired().is_empty() && world.nodes[BETA].paired().is_empty(), "one confirmation is not enough");

    world.with(BETA, |endpoint| endpoint.confirm_pairing(true));
    world.pump();

    assert_eq!(world.nodes[ALPHA].paired()[0].name, "Beta");
    assert_eq!(world.nodes[BETA].paired()[0].name, "Alpha");
    assert_eq!(world.nodes[ALPHA].paired()[0].fingerprint(), world.nodes[BETA].identity.fingerprint());
    assert_eq!(world.nodes[BETA].paired()[0].fingerprint(), world.nodes[ALPHA].identity.fingerprint());
    assert!(world.linked_both(ALPHA, BETA));
}

#[test]
fn a_newly_paired_device_has_nothing_switched_on() {
    let world = paired_world();
    let device = world.nodes[ALPHA].device(&world.nodes[BETA]);
    assert!(!device.receive && !device.send);
}

#[test]
fn different_pairings_give_different_codes() {
    assert_ne!(paired_world().nodes[ALPHA].codes(), paired_world().nodes[ALPHA].codes());
}

#[test]
fn a_device_that_declines_ends_the_pairing_for_both() {
    let mut world = unpaired_world();
    world.pair(ALPHA, BETA);

    world.with(BETA, |endpoint| endpoint.confirm_pairing(false));
    world.with(ALPHA, |endpoint| endpoint.confirm_pairing(true));
    world.pump();

    assert_eq!(world.nodes[BETA].failures(), vec!["declined"]);
    assert_eq!(world.nodes[ALPHA].failures().len(), 1);
    assert!(world.nodes[ALPHA].endpoint.book().devices().is_empty() && world.nodes[BETA].endpoint.book().devices().is_empty());
}

#[test]
fn pairing_is_ignored_while_the_other_device_has_it_closed() {
    let mut world = unpaired_world();
    let address = world.address(BETA);
    world.with(ALPHA, |endpoint| endpoint.pair(address));
    world.pump();
    world.advance(HANDSHAKE_TIMEOUT_MS + 1000);

    assert_eq!(world.nodes[ALPHA].failures(), vec!["no answer"]);
    assert!(world.nodes[BETA].codes().is_empty());
    assert_eq!(world.log.len(), 1 + MAX_HANDSHAKE_RESENDS as usize);
}

#[test]
fn the_window_for_pairing_closes() {
    let mut world = unpaired_world();
    world.with(BETA, |endpoint| endpoint.open_pairing(5000));
    world.advance(6000);
    let address = world.address(BETA);
    world.with(ALPHA, |endpoint| endpoint.pair(address));
    world.pump();

    assert!(world.nodes[BETA].codes().is_empty());
}

#[test]
fn a_pairing_nobody_confirms_ends_and_saves_nothing() {
    let mut world = unpaired_world();
    world.pair(ALPHA, BETA);
    world.with(ALPHA, |endpoint| endpoint.confirm_pairing(true));

    world.advance(CONFIRM_TIMEOUT_MS + 1000);

    assert_eq!(world.nodes[BETA].failures(), vec!["timed out"]);
    assert!(world.nodes[ALPHA].endpoint.book().devices().is_empty() && world.nodes[BETA].endpoint.book().devices().is_empty());
}

#[test]
fn pairing_survives_lost_packets() {
    let mut world = unpaired_world();
    world.with(BETA, |endpoint| endpoint.open_pairing(PAIRING_WINDOW_MS));
    let mut seen = std::collections::HashSet::new();
    world.drop_if = Box::new(move |_, bytes| bytes[0] != TYPE_DATA && seen.insert(bytes[0] as u32 * 31 + bytes[1] as u32));

    let address = world.address(BETA);
    world.with(ALPHA, |endpoint| endpoint.pair(address));
    world.advance(8000);

    assert_eq!(world.nodes[ALPHA].codes(), world.nodes[BETA].codes());
    assert_eq!(world.nodes[ALPHA].codes().len(), 1);
}

#[test]
fn paired_devices_reconnect_after_a_restart() {
    let mut world = paired_world();
    world.restart(ALPHA);
    world.restart(BETA);
    assert!(!world.nodes[ALPHA].endpoint.is_linked(&world.pk(BETA)));

    let address = world.address(BETA);
    world.dial(ALPHA, BETA, vec![address]);
    world.pump();

    assert!(world.linked_both(ALPHA, BETA));
    assert_eq!(world.nodes[BETA].links_up().last().unwrap().name, "Alpha");
}

#[test]
fn a_peer_that_restarts_and_dials_in_replaces_its_old_link_and_the_old_one_is_reported_down() {
    let mut world = paired_world();
    assert_eq!(world.nodes[ALPHA].links_up().len(), 1);

    world.restart(BETA);
    let address = world.address(ALPHA);
    world.dial(BETA, ALPHA, vec![address]);
    world.pump();

    assert_eq!(world.nodes[ALPHA].links_down(), 1);
    assert_eq!(world.nodes[ALPHA].links_up().len(), 2);
    assert!(world.linked_both(ALPHA, BETA));
}

#[test]
fn pairing_again_while_linked_reports_the_old_link_down() {
    let mut world = paired_world();
    world.pair(ALPHA, BETA);
    world.with(ALPHA, |endpoint| endpoint.confirm_pairing(true));
    world.with(BETA, |endpoint| endpoint.confirm_pairing(true));
    world.pump();

    assert_eq!(world.nodes[ALPHA].links_down(), 1);
    assert_eq!(world.nodes[BETA].links_down(), 1);
    assert_eq!(world.nodes[ALPHA].links_up().len(), 2);
}

#[test]
fn the_window_for_pairing_closes_once_pairing_succeeds() {
    let mut world = paired_world();
    let stranger = world.add("Stranger", "10.0.0.9");
    let before = world.nodes[BETA].codes().len();

    let address = world.address(BETA);
    world.with(stranger, |endpoint| endpoint.pair(address));
    world.pump();

    assert_eq!(world.nodes[BETA].codes().len(), before);
}

#[test]
fn the_round_trip_is_measured_once_the_link_has_been_up_for_a_second() {
    let mut world = paired_world();
    assert!(world.nodes[ALPHA].endpoint.rtt_ms(&world.pk(BETA)).is_none());

    world.advance(PROBE_INTERVAL_MS + 500);

    assert!(world.nodes[ALPHA].endpoint.rtt_ms(&world.pk(BETA)).is_some());
    assert!(world.nodes[BETA].endpoint.rtt_ms(&world.pk(ALPHA)).is_some());
    assert!(world.nodes[ALPHA].endpoint.rtt_ms(&"00".repeat(32)).is_none(), "a device that is not linked has no round trip");
}

#[test]
fn a_request_to_send_fragments_again_reaches_the_publisher() {
    let mut world = paired_world();
    let beta = world.pk(BETA);
    world.with(ALPHA, |endpoint| endpoint.send_to(&beta, &Control::Nack { stream_id: 4, frame_id: 77, indexes: vec![1, 5] }));
    world.pump();

    assert!(world.nodes[BETA].events.iter().any(|event| matches!(event, Event::Nack { stream_id: 4, frame_id: 77, indexes, .. } if indexes == &vec![1, 5])));
}

#[test]
fn sender_stats_reach_the_watching_device() {
    let mut world = paired_world();
    let alpha = world.pk(ALPHA);
    world.with(BETA, |endpoint| endpoint.send_to(&alpha, &Control::SenderStats { stream_id: 3, bitrate_kbps: 2000, fps10: 300, encode_ms10: 80, send_ms10: 5 }));
    world.pump();

    assert!(world.nodes[ALPHA].events.iter().any(|event| matches!(event, Event::SenderStats { stream_id: 3, bitrate_kbps: 2000, fps10: 300, encode_ms10: 80, send_ms10: 5, .. })));
}

#[test]
fn a_device_that_was_never_paired_gets_no_answer() {
    let mut world = paired_world();
    let stranger = world.add("Stranger", "10.0.0.9");
    let before = world.log.len();
    let beta = Device::new(&world.pk(BETA), "Beta", None);
    let address = world.address(BETA);

    world.with(stranger, |endpoint| endpoint.connect(&beta, vec![address]));
    world.advance(7000);

    assert!(!world.nodes[BETA].endpoint.is_linked(&world.pk(stranger)));
    assert_eq!(world.nodes[stranger].count(|event| matches!(event, Event::ConnectFailed(_))), 1);
    let (beta_address, stranger_address) = (world.address(BETA), world.address(stranger));
    assert!(!world.log[before..].iter().any(|sent| sent.from == beta_address && sent.to == stranger_address), "the hub stayed silent");
}

#[test]
fn a_recorded_hello_cannot_be_replayed() {
    let mut world = paired_world();
    world.restart(ALPHA);
    world.restart(BETA);
    let mark = world.log.len();
    let address = world.address(BETA);
    world.dial(ALPHA, BETA, vec![address]);
    world.pump();
    let alpha_address = world.address(ALPHA);
    let hello = world.log[mark..].iter().find(|sent| sent.from == alpha_address && sent.bytes[0] == TYPE_HELLO).map(|sent| sent.bytes.clone()).unwrap();
    let beta_address = world.address(BETA);
    let answers = |world: &World| world.log.iter().filter(|sent| sent.from == beta_address && sent.bytes[0] == TYPE_HELLO).count();
    let before = answers(&world);

    world.with(BETA, |endpoint| endpoint.on_datagram(alpha_address, &hello));
    world.pump();

    assert_eq!(answers(&world), before, "no second answer");
}

#[test]
fn a_silent_link_is_dropped_on_both_sides() {
    let mut world = paired_world();
    world.cut = true;
    world.advance(LINK_TIMEOUT_MS + 2000);

    assert!(!world.nodes[ALPHA].endpoint.is_linked(&world.pk(BETA)));
    assert!(!world.nodes[BETA].endpoint.is_linked(&world.pk(ALPHA)));
    assert_eq!(world.nodes[ALPHA].links_down(), 1);
    assert_eq!(world.nodes[BETA].links_down(), 1);
    let logs = world.nodes[ALPHA].logs();
    assert_eq!(logs.len(), 1);
    assert!(logs[0].starts_with("link to Beta down: nothing heard for 15."), "{}", logs[0]);
}

#[test]
fn a_few_seconds_without_packets_do_not_drop_a_link_even_with_a_message_still_waiting_for_its_answer() {
    let mut world = paired_world();
    let beta = world.pk(BETA);
    world.with(ALPHA, |endpoint| endpoint.send_to(&beta, &Control::KeyframeRequest(1)));

    world.cut = true;
    world.advance(8000);
    world.cut = false;
    world.advance(3000);

    assert!(world.linked_both(ALPHA, BETA));
    assert_eq!(world.nodes[ALPHA].links_down() + world.nodes[BETA].links_down(), 0);
    assert_eq!(world.nodes[BETA].count(|event| matches!(event, Event::KeyframeRequest(_, 1))), 1);
}

#[test]
fn a_link_that_is_heard_again_after_a_few_seconds_says_so_once_and_a_quiet_one_does_not() {
    let mut world = paired_world();
    world.advance(10_000);
    let resumed = |node: &Node| node.count(|event| matches!(event, Event::LinkResumed(_)));
    assert_eq!(resumed(&world.nodes[ALPHA]), 0);

    world.cut = true;
    world.advance(5000);
    world.cut = false;
    world.advance(3000);

    assert_eq!(resumed(&world.nodes[ALPHA]), 1);
    assert_eq!(resumed(&world.nodes[BETA]), 1);
}

#[test]
fn an_idle_link_stays_up_on_pings() {
    let mut world = paired_world();
    world.advance(60_000);
    assert!(world.linked_both(ALPHA, BETA));
    assert_eq!(world.nodes[ALPHA].links_down(), 0);
}

#[test]
fn feed_lists_arrive_once_even_when_packets_are_lost_or_repeated() {
    let mut world = paired_world();
    let mut dropped = 0;
    world.drop_if = Box::new(move |_, bytes| {
        let drop = bytes[0] == TYPE_DATA && dropped < 2;
        if bytes[0] == TYPE_DATA {
            dropped += 1;
        }
        drop
    });
    let offered = vec![FeedInfo::new("alpha", 720, 1080, 30, false), FeedInfo::new("camera", 1280, 720, 30, true)];
    let beta = world.pk(BETA);
    let sent = offered.clone();
    world.with(ALPHA, |endpoint| endpoint.send_to(&beta, &Control::FeedList(sent)));
    world.advance(3000);

    assert_eq!(world.nodes[BETA].feeds(), vec![offered]);
}

#[test]
fn saying_goodbye_makes_the_other_side_drop_the_link_at_once() {
    let mut world = paired_world();
    let beta = world.pk(BETA);
    world.with(ALPHA, |endpoint| endpoint.disconnect(&beta));
    world.pump();

    assert!(!world.nodes[BETA].endpoint.is_linked(&world.pk(ALPHA)));
    assert_eq!(world.nodes[BETA].links_down(), 1);
    assert_eq!(world.nodes[BETA].logs(), vec!["link to Alpha down: the other device said goodbye"]);
    assert_eq!(world.nodes[ALPHA].logs(), vec!["link to Beta down: closed from this device"]);
}

#[test]
fn unpairing_forgets_the_device_and_it_cannot_reconnect() {
    let mut world = paired_world();
    let alpha = world.pk(ALPHA);
    world.with(BETA, |endpoint| endpoint.unpair(&alpha));
    world.pump();
    let address = world.address(BETA);
    world.dial(ALPHA, BETA, vec![address]);
    world.advance(7000);

    assert!(world.nodes[BETA].endpoint.book().devices().is_empty());
    assert!(!world.nodes[BETA].endpoint.is_linked(&alpha));
}

#[test]
fn pairing_again_keeps_what_was_switched_on() {
    let mut world = paired_world();
    let (alpha, beta) = (world.pk(ALPHA), world.pk(BETA));
    world.with(ALPHA, |endpoint| endpoint.book_mut().update(&beta, |device| Device { receive: true, ..device.clone() }));
    world.with(ALPHA, |endpoint| endpoint.disconnect(&beta));
    world.with(BETA, |endpoint| endpoint.disconnect(&alpha));
    world.pump();

    world.pair(ALPHA, BETA);
    world.with(ALPHA, |endpoint| endpoint.confirm_pairing(true));
    world.with(BETA, |endpoint| endpoint.confirm_pairing(true));
    world.pump();

    assert!(world.nodes[ALPHA].device(&world.nodes[BETA]).receive);
}

#[test]
fn two_devices_dialing_at_the_same_time_end_up_with_one_working_link() {
    let mut world = paired_world();
    world.restart(ALPHA);
    world.restart(BETA);
    let (to_beta, to_alpha) = (world.address(BETA), world.address(ALPHA));
    world.dial(ALPHA, BETA, vec![to_beta]);
    world.dial(BETA, ALPHA, vec![to_alpha]);
    world.pump();
    world.advance(2000);
    let offered = vec![FeedInfo::new("alpha", 720, 1080, 30, false)];
    let (alpha, beta) = (world.pk(ALPHA), world.pk(BETA));
    let sent = offered.clone();
    world.with(ALPHA, |endpoint| endpoint.send_to(&beta, &Control::FeedList(sent.clone())));
    world.with(BETA, |endpoint| endpoint.send_to(&alpha, &Control::FeedList(sent)));
    world.advance(1000);

    assert_eq!(world.nodes[BETA].feeds(), vec![offered.clone()]);
    assert_eq!(world.nodes[ALPHA].feeds(), vec![offered]);
    assert!(world.linked_both(ALPHA, BETA));
}

#[test]
fn a_device_is_not_dialed_twice() {
    let mut world = paired_world();
    world.restart(ALPHA);
    let address = world.address(BETA);
    world.dial(ALPHA, BETA, vec![address.clone()]);
    let sent = world.log.len();
    world.dial(ALPHA, BETA, vec![address]);

    assert_eq!(world.log.len(), sent);
    assert!(world.nodes[ALPHA].endpoint.is_dialing(&world.pk(BETA)));
}

#[test]
fn a_subscription_reaches_the_publisher_unchanged() {
    let mut world = paired_world();
    let request = Subscribe::new(7, "alpha", 720, 1080, 2500);
    let beta = world.pk(BETA);
    let sent = request.clone();
    world.with(ALPHA, |endpoint| {
        endpoint.send_to(&beta, &Control::Subscribe(sent));
        endpoint.send_to(&beta, &Control::KeyframeRequest(7));
        endpoint.send_to(&beta, &Control::Unsubscribe(7));
    });
    world.pump();

    let node = &world.nodes[BETA];
    assert!(node.events.iter().any(|event| matches!(event, Event::Subscribe(_, got) if *got == request)));
    assert_eq!(node.count(|event| matches!(event, Event::KeyframeRequest(_, 7))), 1);
    assert_eq!(node.count(|event| matches!(event, Event::Unsubscribe(_, 7))), 1);
}

#[test]
fn a_refused_subscription_is_answered_with_its_reason() {
    let mut world = paired_world();
    let alpha = world.pk(ALPHA);
    world.with(BETA, |endpoint| endpoint.send_to(&alpha, &Control::SubscribeRefused { stream_id: 7, reason: Refusal::NotShared }));
    world.pump();

    assert_eq!(world.nodes[ALPHA].count(|event| matches!(event, Event::SubscribeRefused { stream_id: 7, reason: Refusal::NotShared, .. })), 1);
}

#[test]
fn video_crosses_the_link_and_is_rebuilt_into_the_same_frame() {
    let mut world = paired_world();
    let original = Frame { id: 9, timestamp: 90000, keyframe: true, encoded: (0..40_000u32).map(|i| (i * 7) as u8).collect() };
    let alpha = world.pk(ALPHA);
    let fragments = split(&original);
    world.with(BETA, |endpoint| endpoint.send_video(&alpha, 12, &fragments));
    world.pump();

    let received = world.nodes[ALPHA].video();
    assert!(received.iter().all(|(stream, _)| *stream == 12));
    let mut buffer = FrameBuffer::default();
    let rebuilt: Vec<_> = received.iter().flat_map(|(_, fragment)| buffer.add(fragment, 0)).collect();
    assert_eq!(rebuilt.len(), 1);
    assert_eq!(rebuilt[0].frame.encoded, original.encoded);
    assert!(rebuilt[0].frame.keyframe);
}

#[test]
fn audio_crosses_the_link_with_its_stream_and_timestamp() {
    let mut world = paired_world();
    let frame: Vec<u8> = (0..340u32).map(|i| i as u8).collect();
    let alpha = world.pk(ALPHA);
    let sent = frame.clone();
    world.with(BETA, |endpoint| {
        endpoint.send_audio(&alpha, 12, 90000, &sent);
        endpoint.send_audio(&alpha, 12, 91875, &sent);
    });
    world.pump();

    let audio: Vec<(u16, i32, Vec<u8>)> =
        world.nodes[ALPHA].events.iter().filter_map(|event| if let Event::Audio(_, stream, timestamp, frame) = event { Some((*stream, *timestamp, frame.clone())) } else { None }).collect();
    assert_eq!(audio.iter().map(|it| it.0).collect::<Vec<_>>(), vec![12, 12]);
    assert_eq!(audio.iter().map(|it| it.1).collect::<Vec<_>>(), vec![90000, 91875]);
    assert_eq!(audio[0].2, frame);
    assert!(world.nodes[ALPHA].video().is_empty(), "audio is not mistaken for video");
}

#[test]
fn audio_for_a_device_that_is_not_linked_goes_nowhere() {
    let mut world = paired_world();
    let before = world.log.len();
    world.with(BETA, |endpoint| endpoint.send_audio(&"00".repeat(32), 1, 0, &[0; 100]));
    assert_eq!(world.log.len(), before);
}

#[test]
fn video_for_a_device_that_is_not_linked_goes_nowhere() {
    let mut world = paired_world();
    let before = world.log.len();
    world.with(BETA, |endpoint| endpoint.send_video(&"00".repeat(32), 1, &[vec![0; 100]]));
    assert_eq!(world.log.len(), before);
}

#[test]
fn dialing_every_address_links_through_the_one_that_answers() {
    let mut world = paired_world();
    world.restart(ALPHA);
    world.restart(BETA);
    let address = world.address(BETA);
    world.dial(ALPHA, BETA, vec![Address::new("192.168.9.9", 47200), address]);
    world.pump();

    assert!(world.linked_both(ALPHA, BETA));
}

#[test]
fn a_hello_that_arrives_twice_brings_up_one_working_link() {
    let mut world = paired_world();
    world.restart(ALPHA);
    world.restart(BETA);
    let second_path = Address::new("100.64.0.2", 4000);
    world.routes.insert(second_path.clone(), BETA);
    let before = world.nodes[BETA].links_up().len();

    let address = world.address(BETA);
    world.dial(ALPHA, BETA, vec![address, second_path]);
    world.pump();
    let beta = world.pk(BETA);
    world.with(ALPHA, |endpoint| endpoint.send_to(&beta, &Control::FeedList(vec![FeedInfo::new("alpha", 720, 1080, 30, false)])));
    world.pump();

    assert_eq!(world.nodes[BETA].links_up().len(), before + 1);
    assert_eq!(world.nodes[BETA].feeds().last().unwrap()[0].name, "alpha");
}

#[test]
fn addresses_reach_the_linked_device() {
    let mut world = paired_world();
    let tailscale = Address::new("100.77.195.122", 47200);
    let beta = world.pk(BETA);
    let sent = tailscale.clone();
    world.with(ALPHA, |endpoint| endpoint.send_to(&beta, &Control::Addresses(vec![sent])));
    world.pump();

    assert!(world.nodes[BETA].events.iter().any(|event| matches!(event, Event::Addresses(_, addresses) if addresses == &vec![tailscale.clone()])));
}
