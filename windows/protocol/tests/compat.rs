mod common;

use manifold_protocol::control::{Control, FeedInfo, Refusal, Subscribe, encode, encode_ack};
use manifold_protocol::crypto::{from_hex, sha256, to_hex};
use manifold_protocol::noise::TransportKeys;
use manifold_protocol::session::{Session, StreamKind};
use manifold_protocol::video::{Frame, encode_packet, split};
use manifold_protocol::wire::{Address, HandshakePacket, TYPE_HELLO, TYPE_PAIR, encode_handshake};
use manifold_protocol::{audio, beacon};

fn check(name: &str, bytes: &[u8]) {
    assert_eq!(to_hex(bytes), common::compat()[name], "{name}");
}

#[test]
fn session_packets_match_the_fixture() {
    let mut sender = Session::new(1, 2, TransportKeys { send: [1; 32], receive: [2; 32] });
    check("session_control", &sender.seal(StreamKind::Control, &[0xAA, 0xBB]));
    check("session_video", &sender.seal(StreamKind::Video, &[1, 2, 3, 4]));
}

#[test]
fn a_packet_sealed_by_the_kotlin_fixture_opens_here() {
    let mut receiver = Session::new(2, 1, TransportKeys { send: [2; 32], receive: [1; 32] });
    let opened = receiver.open(&from_hex(&common::compat()["session_control"]).unwrap());
    assert_eq!(opened, Some((StreamKind::Control, vec![0xAA, 0xBB])));
}

#[test]
fn handshake_packets_match_the_fixture() {
    check("wire_hello", &encode_handshake(&HandshakePacket { kind: TYPE_HELLO, step: 1, sender_index: -5, receiver_index: 77, message: vec![1, 2, 3] }));
    check("wire_pair", &encode_handshake(&HandshakePacket { kind: TYPE_PAIR, step: 0, sender_index: 123456, receiver_index: 0, message: vec![] }));
}

#[test]
fn control_messages_match_the_fixture() {
    check("control_subscribe", &encode(3, &Control::Subscribe(Subscribe::new(7, "alpha", 720, 1080, 2500))));
    check("control_subscribe_audio", &encode(3, &Control::Subscribe(Subscribe { audio: true, ..Subscribe::new(7, "alpha", 720, 1080, 2500) })));
    check("control_subscribe_alpha", &encode(3, &Control::Subscribe(Subscribe { alpha: true, ..Subscribe::new(7, "alpha", 720, 1080, 2500) })));
    check("control_feedlist", &encode(4, &Control::FeedList(vec![FeedInfo::new("alpha", 720, 1080, 30, false), FeedInfo::new("camera", 1280, 720, 30, true)])));
    check("control_feedlist_alpha", &encode(8, &Control::FeedList(vec![FeedInfo { has_alpha: true, ..FeedInfo::new("avatar", 720, 1080, 30, false) }])));
    check("control_feedlist_sound", &encode(5, &Control::FeedList(vec![FeedInfo { sound_only: true, ..FeedInfo::new("PC sound", 0, 0, 0, true) }])));
    check(
        "control_feedlist_titles",
        &encode(6, &Control::FeedList(vec![FeedInfo { title: "Alpha: new tab".into(), ..FeedInfo::new("alpha", 720, 1080, 30, false) }, FeedInfo::new("camera", 1280, 720, 30, true)])),
    );
    check("control_keyframe", &encode(5, &Control::KeyframeRequest(9)));
    check("control_refused", &encode(7, &Control::SubscribeRefused { stream_id: 9, reason: Refusal::NotShared }));
    check("control_ack", &encode_ack(77));
    check("control_confirm", &encode(6, &Control::PairConfirm));
    check("control_addresses", &encode(9, &Control::Addresses(vec![Address::new("100.77.195.122", 47200), Address::new("192.168.0.100", 8000)])));
}

#[test]
fn audio_packets_match_the_fixture() {
    check("audiopacket", &audio::encode(12, 90000, &[1, 2, 3, 4, 5]));
}

#[test]
fn video_fragments_match_the_fixture() {
    let small = split(&Frame { id: 9, timestamp: 90000, keyframe: true, encoded: (0..100u32).map(|i| (i * 7) as u8).collect() });
    assert_eq!(small.len(), 1);
    check("fragment_small", &small[0]);
    check("videopacket_small", &encode_packet(12, &small[0]));

    let large = split(&Frame { id: -2, timestamp: -90000, keyframe: false, encoded: (0..3000u32).map(|i| (i * 31) as u8).collect() });
    assert_eq!(large.len().to_string(), common::compat()["fragment_large_count"]);
    let parts: Vec<&[u8]> = large.iter().map(Vec::as_slice).collect();
    check("fragment_large_hash", &sha256(&parts));
}

#[test]
fn beacons_match_the_fixture() {
    check("beacon", &beacon::encode(&beacon::Beacon { id: "0123456789abcdef".into(), port: 47200, name: "Pixel".into() }));
}
