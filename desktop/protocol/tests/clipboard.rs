use manifold_protocol::clipboard::{Assembler, parts};
use manifold_protocol::control::{CLIPBOARD_CHUNK, Control, Decoded, MAX_CLIPBOARD_BYTES, decode, encode};

fn put_back(assembler: &mut Assembler, controls: Vec<Control>) -> Option<String> {
    let mut done = None;
    for control in controls {
        let Some(Decoded::Message(_, Control::Clipboard { seq, part, count, data, .. })) = decode(&encode(1, &control)) else { panic!("not a clipboard part") };
        done = assembler.add(seq, part, count, data).or(done);
    }
    done
}

#[test]
fn text_longer_than_one_datagram_comes_back_whole() {
    let text = "Grüße ✓ ".repeat(CLIPBOARD_CHUNK / 4);
    let pieces = parts(2, 1, &text).unwrap();
    assert!(pieces.len() > 1);
    assert_eq!(put_back(&mut Assembler::default(), pieces), Some(text));
}

#[test]
fn parts_out_of_order_still_make_the_text() {
    let text = "x".repeat(CLIPBOARD_CHUNK * 3 + 10);
    let mut pieces = parts(2, 4, &text).unwrap();
    pieces.reverse();
    assert_eq!(put_back(&mut Assembler::default(), pieces), Some(text));
}

#[test]
fn a_copy_over_the_limit_is_not_sent() {
    assert!(parts(2, 1, &"x".repeat(MAX_CLIPBOARD_BYTES + 1)).is_none());
    assert!(parts(2, 1, "").is_none());
}

#[test]
fn a_newer_copy_replaces_one_still_arriving() {
    let mut assembler = Assembler::default();
    let old = parts(2, 1, &"o".repeat(CLIPBOARD_CHUNK * 2)).unwrap();
    let Control::Clipboard { seq, part, count, data, .. } = old[0].clone() else { unreachable!() };
    assert_eq!(assembler.add(seq, part, count, data), None);
    assert_eq!(put_back(&mut assembler, parts(2, 2, "new").unwrap()), Some("new".to_string()));
}
