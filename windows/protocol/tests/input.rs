use manifold_protocol::control::{Control, Decoded, FeedInfo, Input, MouseButton, PadState, decode, encode, needs_ack};
use manifold_protocol::devices::{Device, DeviceBook};
use std::sync::{Arc, Mutex};

fn round_trip(control: Control) -> Control {
    match decode(&encode(5, &control)) {
        Some(Decoded::Message(5, decoded)) => decoded,
        other => panic!("{control:?} came back as {other:?}"),
    }
}

#[test]
fn every_input_message_reads_back_as_written() {
    let messages = [
        Input::Pointer { stream_id: 3, seq: 1, x: 0, y: 65535 },
        Input::Motion { stream_id: 3, dx: -32768, dy: 32767 },
        Input::Button { stream_id: 3, seq: 2, button: MouseButton::Forward, down: true, at: Some((1, 2)) },
        Input::Button { stream_id: 3, seq: 3, button: MouseButton::Middle, down: false, at: None },
        Input::Scroll { stream_id: 3, dx: 120, dy: -240 },
        Input::Release { stream_id: 3, seq: u32::MAX, key_seq: 12 },
        Input::Key { stream_id: 3, seq: 4, usage: 0xE3, down: false },
        Input::Text { stream_id: 3, seq: 5, text: "caf\u{e9} \u{1f600}".into() },
        Input::Gamepad {
            stream_id: 3,
            seq: 6,
            pad: 3,
            state: PadState { buttons: 0xF3FF, left_trigger: 1, right_trigger: 254, left_x: -32768, left_y: 32767, right_x: -2, right_y: 2 },
        },
    ];
    for input in messages {
        assert_eq!(round_trip(Control::Input(input.clone())), Control::Input(input));
    }
}

#[test]
fn only_presses_scrolls_and_releases_are_resent() {
    assert!(!needs_ack(&Control::Input(Input::Pointer { stream_id: 1, seq: 1, x: 0, y: 0 })));
    assert!(!needs_ack(&Control::Input(Input::Motion { stream_id: 1, dx: 1, dy: 1 })));
    assert!(!needs_ack(&Control::Input(Input::Gamepad { stream_id: 1, seq: 1, pad: 0, state: PadState::default() })));
    assert!(!needs_ack(&Control::Rumble { stream_id: 1, pad: 0, large: 1, small: 1 }));
    assert!(needs_ack(&Control::Input(Input::Button { stream_id: 1, seq: 1, button: MouseButton::Left, down: true, at: None })));
    assert!(needs_ack(&Control::Input(Input::Scroll { stream_id: 1, dx: 0, dy: 120 })));
    assert!(needs_ack(&Control::Input(Input::Release { stream_id: 1, seq: 1, key_seq: 0 })));
    assert!(needs_ack(&Control::Input(Input::Key { stream_id: 1, seq: 1, usage: 4, down: true })));
    assert!(needs_ack(&Control::Input(Input::Text { stream_id: 1, seq: 1, text: "a".into() })));
}

#[test]
fn a_button_from_a_newer_hub_is_acknowledged_and_ignored() {
    let mut bytes = encode(7, &Control::Input(Input::Button { stream_id: 1, seq: 1, button: MouseButton::Left, down: true, at: None }));
    bytes[11] = 9;
    assert_eq!(decode(&bytes), Some(Decoded::Unrecognized(7)));
}

#[test]
fn a_cut_short_input_message_is_not_read() {
    let bytes = encode(7, &Control::Input(Input::Pointer { stream_id: 1, seq: 1, x: 5, y: 5 }));
    assert_eq!(decode(&bytes[..bytes.len() - 1]), Some(Decoded::Unrecognized(7)));
}

#[test]
fn text_too_long_for_one_message_is_cut_at_a_character() {
    let text = "\u{e9}".repeat(150);
    let Control::Input(Input::Text { text: sent, .. }) = round_trip(Control::Input(Input::Text { stream_id: 1, seq: 1, text })) else { panic!() };
    assert_eq!(sent, "\u{e9}".repeat(100));
}

#[test]
fn a_release_from_before_key_ordering_reads_as_superseding_nothing() {
    let bytes = encode(7, &Control::Input(Input::Release { stream_id: 2, seq: 9, key_seq: 0 }));
    assert_eq!(decode(&bytes[..bytes.len() - 4]), Some(Decoded::Message(7, Control::Input(Input::Release { stream_id: 2, seq: 9, key_seq: 0 }))));
}

#[test]
fn the_control_flag_survives_a_feed_list() {
    let feeds = vec![FeedInfo { controllable: true, ..FeedInfo::new("desk", 1920, 1080, 30, false) }, FeedInfo::new("camera", 1280, 720, 30, false)];
    assert_eq!(round_trip(Control::FeedList(feeds.clone())), Control::FeedList(feeds));
}

#[test]
fn control_is_saved_and_an_older_book_reads_as_off() {
    let key = "ab".repeat(32);
    let saved = Arc::new(Mutex::new(String::new()));
    let sink = saved.clone();
    let mut book = DeviceBook::new(None, move |text| *sink.lock().unwrap() = text.to_string());
    book.put(Device { send: true, control: true, ..Device::new(&key, "Phone", None) });
    let text = saved.lock().unwrap().clone();
    assert!(DeviceBook::new(Some(&text), |_| {}).find(&key).unwrap().control);

    let older = format!("{key}\tPhone\t\ttrue\ttrue\tfalse\tfalse");
    assert!(!DeviceBook::new(Some(&older), |_| {}).find(&key).unwrap().control);
}
