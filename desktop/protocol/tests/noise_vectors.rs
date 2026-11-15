mod common;

use manifold_protocol::crypto::{KeyPair, from_hex, open, seal};
use manifold_protocol::noise::{Handshake, Pattern};
use std::collections::HashMap;

struct Vector {
    fields: HashMap<String, String>,
    messages: Vec<(Vec<u8>, Vec<u8>)>,
}

impl Vector {
    fn bytes(&self, key: &str) -> Vec<u8> {
        from_hex(&self.fields[key]).unwrap()
    }

    fn key(&self, key: &str) -> KeyPair {
        KeyPair::from_private(self.bytes(key).try_into().unwrap())
    }
}

fn vector(name: &str) -> Vector {
    let text = common::fixture("noise_vectors.txt");
    let block = text.split("\n\n").find(|block| block.lines().next().is_some_and(|first| first.contains(name))).expect("the vector");
    let mut fields = HashMap::new();
    let mut messages = Vec::new();
    for line in block.lines().skip(1).filter(|line| !line.trim().is_empty()) {
        let (key, value) = line.split_once('=').unwrap();
        if key == "message" {
            let (payload, sent) = value.split_once(',').unwrap();
            messages.push((from_hex(payload).unwrap(), from_hex(sent).unwrap()));
        } else {
            fields.insert(key.to_string(), value.to_string());
        }
    }
    Vector { fields, messages }
}

fn run(pattern: Pattern) {
    let vector = vector(pattern.protocol_name());
    let init_static = vector.key("init_static");
    let resp_static = vector.key("resp_static");
    let prologue = vector.bytes("init_prologue");
    let remote = (pattern == Pattern::Ik).then_some(resp_static.public);
    let mut initiator = Handshake::with_fixed_ephemeral(pattern, true, &init_static, remote.as_ref().map(|it| &it[..]), &prologue, &vector.bytes("init_ephemeral"));
    let mut responder = Handshake::with_fixed_ephemeral(pattern, false, &resp_static, None, &prologue, &vector.bytes("resp_ephemeral"));

    let handshake_messages = if pattern == Pattern::Xx { 3 } else { 2 };
    for index in 0..handshake_messages {
        let (payload, expected) = &vector.messages[index];
        let (writer, reader) = if index % 2 == 0 { (&mut initiator, &mut responder) } else { (&mut responder, &mut initiator) };
        let sent = writer.write_message(payload).unwrap();
        assert_eq!(&sent, expected, "handshake message {index}");
        assert_eq!(&reader.read_message(&sent).unwrap(), payload, "payload {index}");
    }

    assert!(initiator.complete() && responder.complete());
    assert_eq!(initiator.handshake_hash(), vector.bytes("handshake_hash"));
    assert_eq!(responder.handshake_hash(), vector.bytes("handshake_hash"));
    assert_eq!(initiator.remote_static(), Some(resp_static.public));
    assert_eq!(responder.remote_static(), Some(init_static.public));

    let from_initiator = initiator.split();
    let from_responder = responder.split();
    assert_eq!(from_initiator.send, from_responder.receive);
    assert_eq!(from_initiator.receive, from_responder.send);

    let mut counters = [0u64, 0];
    for (index, (payload, expected)) in vector.messages.iter().enumerate().skip(handshake_messages) {
        let side = index % 2;
        let (send, receive) = if side == 0 { (&from_initiator.send, &from_responder.receive) } else { (&from_responder.send, &from_initiator.receive) };
        let sealed = seal(send, counters[side], &[], payload);
        assert_eq!(&sealed, expected, "transport message {index}");
        assert_eq!(open(receive, counters[side], &[], &sealed).as_ref(), Some(payload));
        counters[side] += 1;
    }
}

#[test]
fn xx_matches_the_official_noise_vector() {
    run(Pattern::Xx);
}

#[test]
fn ik_matches_the_official_noise_vector() {
    run(Pattern::Ik);
}

#[test]
fn a_low_order_ephemeral_is_refused() {
    let responder_keys = KeyPair::generate();
    let mut responder = Handshake::new(Pattern::Xx, false, &responder_keys, None, b"");
    let mut message = vec![0u8; 32];
    message[0] = 1;
    assert!(responder.read_message(&message).is_err());
}
