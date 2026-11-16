use super::{Rect, Target};
use crate::capture::ShareKind;
use crate::system::x11::{self, Desktop};
use evdev::uinput::VirtualDevice;
use evdev::{AbsInfo, AbsoluteAxisCode, AttributeSet, BusType, EventSummary, EventType, FFEffectCode, FFEffectKind, InputEvent, InputId, KeyCode, UInputCode, UinputAbsSetup};
use manifold_protocol::control::{MouseButton, PadState};
use std::collections::HashMap;
use std::os::fd::AsRawFd;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, Instant};
use x11rb::CURRENT_TIME;
use x11rb::NONE;
use x11rb::connection::Connection;
use x11rb::protocol::xfixes::ConnectionExt as _;
use x11rb::protocol::xproto::{
    BUTTON_PRESS_EVENT, BUTTON_RELEASE_EVENT, ClientMessageEvent, ConnectionExt as _, EventMask, KEY_PRESS_EVENT, KEY_RELEASE_EVENT, MOTION_NOTIFY_EVENT,
};
use x11rb::protocol::xtest::ConnectionExt as _;

const WHEEL_STEP: i32 = 120;

static SCROLL_REST: Mutex<(i32, i32)> = Mutex::new((0, 0));
static SPARE_KEY: OnceLock<Option<u8>> = OnceLock::new();

fn desktop() -> Option<&'static Desktop> {
    x11::shared()
}

fn fake(desktop: &Desktop, kind: u8, detail: u8, x: i16, y: i16, root: u32) {
    let _ = desktop.connection.xtest_fake_input(kind, detail, CURRENT_TIME, root, x, y, 0);
}

fn flush(desktop: &Desktop) {
    let _ = desktop.connection.flush();
    desktop.discard_events();
}

pub fn source(target: &Target) -> Option<Rect> {
    let desktop = desktop()?;
    if target.kind == ShareKind::Display { desktop.monitor(target.handle) } else { desktop.window_rect(target.handle as u32) }
}

// A game that turns its camera with the mouse shows an empty pointer while it does, under X as under Windows.
pub fn cursor_captured(target: &Target) -> bool {
    let Some(desktop) = desktop() else { return false };
    if target.kind == ShareKind::Window && desktop.active_window() != Some(target.handle as u32) {
        return false;
    }
    let image = desktop.connection.xfixes_get_cursor_image().ok().and_then(|cookie| cookie.reply().ok());
    image.is_some_and(|image| image.cursor_image.iter().all(|pixel| pixel >> 24 == 0))
}

// Asked as a pager would, which window managers honour without the focus-stealing rules they apply to applications.
pub fn bring_forward(target: &Target) {
    let Some(desktop) = desktop() else { return };
    let window = target.handle as u32;
    if desktop.active_window() == Some(window) {
        return;
    }
    let message = ClientMessageEvent::new(32, window, desktop.atoms._NET_ACTIVE_WINDOW, [2, CURRENT_TIME, 0, 0, 0]);
    let mask = EventMask::SUBSTRUCTURE_REDIRECT | EventMask::SUBSTRUCTURE_NOTIFY;
    let _ = desktop.connection.send_event(false, desktop.root, mask, message);
    flush(desktop);
}

pub fn move_to(x: i32, y: i32) {
    let Some(desktop) = desktop() else { return };
    fake(desktop, MOTION_NOTIFY_EVENT, 0, x as i16, y as i16, desktop.root);
    flush(desktop);
}

pub fn move_by(dx: i32, dy: i32) {
    let Some(desktop) = desktop() else { return };
    fake(desktop, MOTION_NOTIFY_EVENT, 1, dx as i16, dy as i16, NONE);
    flush(desktop);
}

fn button(desktop: &Desktop, number: u8, down: bool) {
    fake(desktop, if down { BUTTON_PRESS_EVENT } else { BUTTON_RELEASE_EVENT }, number, 0, 0, NONE);
}

pub fn press(button_pressed: MouseButton, down: bool) {
    let Some(desktop) = desktop() else { return };
    let number = match button_pressed {
        MouseButton::Left => 1,
        MouseButton::Middle => 2,
        MouseButton::Right => 3,
        MouseButton::Back => 8,
        MouseButton::Forward => 9,
    };
    button(desktop, number, down);
    flush(desktop);
}

pub fn scroll(dx: i32, dy: i32) {
    let Some(desktop) = desktop() else { return };
    let (notches_x, notches_y) = {
        let mut rest = SCROLL_REST.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
        rest.0 += dx;
        rest.1 += dy;
        let notches = (rest.0 / WHEEL_STEP, rest.1 / WHEEL_STEP);
        rest.0 -= notches.0 * WHEEL_STEP;
        rest.1 -= notches.1 * WHEEL_STEP;
        notches
    };
    for (count, positive, negative) in [(notches_y, 4, 5), (notches_x, 7, 6)] {
        let number = if count > 0 { positive } else { negative };
        for _ in 0..count.unsigned_abs() {
            button(desktop, number, true);
            button(desktop, number, false);
        }
    }
    flush(desktop);
}

// The X server numbers keys as the kernel does, plus 8, so a game reading WASD gets the same keys on any layout.
pub fn key(usage: u16, down: bool) {
    let (Some(desktop), Some(code)) = (desktop(), kernel_key(usage)) else { return };
    fake(desktop, if down { KEY_PRESS_EVENT } else { KEY_RELEASE_EVENT }, (code + 8) as u8, 0, 0, NONE);
    flush(desktop);
}

// A key with nothing on it is lent each character in turn, since X can only type what some key produces.
fn spare_key(desktop: &Desktop) -> Option<u8> {
    *SPARE_KEY.get_or_init(|| {
        let setup = desktop.connection.setup();
        let (first, last) = (setup.min_keycode, setup.max_keycode);
        let mapping = desktop.connection.get_keyboard_mapping(first, last - first + 1).ok()?.reply().ok()?;
        let per_key = mapping.keysyms_per_keycode as usize;
        (first..=last).rev().find(|code| mapping.keysyms[(*code - first) as usize * per_key..][..per_key].iter().all(|keysym| *keysym == 0))
    })
}

pub fn type_text(text: &str) {
    let Some(desktop) = desktop() else { return };
    for character in text.chars() {
        match character {
            '\n' => {
                key(0x28, true);
                key(0x28, false);
            }
            '\t' => {
                key(0x2B, true);
                key(0x2B, false);
            }
            c if c.is_control() => {}
            c => {
                let Some(code) = spare_key(desktop) else { return };
                let keysym = if (0x20..=0x7E).contains(&(c as u32)) || (0xA0..=0xFF).contains(&(c as u32)) { c as u32 } else { 0x0100_0000 | c as u32 };
                let _ = desktop.connection.change_keyboard_mapping(1, code, 2, &[keysym, keysym]);
                let _ = desktop.connection.get_input_focus().map(|cookie| cookie.reply());
                fake(desktop, KEY_PRESS_EVENT, code, 0, 0, NONE);
                fake(desktop, KEY_RELEASE_EVENT, code, 0, 0, NONE);
                let _ = desktop.connection.get_input_focus().map(|cookie| cookie.reply());
            }
        }
    }
    flush(desktop);
}

// USB HID keyboard usages to the kernel's key codes, from the table in drivers/hid/hid-input.c.
fn kernel_key(usage: u16) -> Option<u16> {
    const LETTERS: [u16; 26] = [30, 48, 46, 32, 18, 33, 34, 35, 23, 36, 37, 38, 50, 49, 24, 25, 16, 19, 31, 20, 22, 47, 17, 45, 21, 44];
    const DIGITS: [u16; 10] = [2, 3, 4, 5, 6, 7, 8, 9, 10, 11];
    const FROM_ENTER: [u16; 28] = [28, 1, 14, 15, 57, 12, 13, 26, 27, 43, 43, 39, 40, 41, 51, 52, 53, 58, 59, 60, 61, 62, 63, 64, 65, 66, 67, 68];
    const FROM_F11: [u16; 27] = [87, 88, 99, 70, 119, 110, 102, 104, 111, 107, 109, 106, 105, 108, 103, 69, 98, 55, 74, 78, 96, 79, 80, 81, 75, 76, 77];
    const KEYPAD_REST: [u16; 6] = [71, 72, 73, 82, 83, 86];
    const MODIFIERS: [u16; 8] = [29, 42, 56, 125, 97, 54, 100, 126];
    Some(match usage {
        0x04..=0x1D => LETTERS[(usage - 0x04) as usize],
        0x1E..=0x27 => DIGITS[(usage - 0x1E) as usize],
        0x28..=0x43 => FROM_ENTER[(usage - 0x28) as usize],
        0x44..=0x5E => FROM_F11[(usage - 0x44) as usize],
        0x5F..=0x64 => KEYPAD_REST[(usage - 0x5F) as usize],
        0x65 => 127,
        0xE0..=0xE7 => MODIFIERS[(usage - 0xE0) as usize],
        _ => return None,
    })
}

#[derive(Clone)]
pub struct PadBus;

impl PadBus {
    // The kernel's uinput device makes the pads. It belongs to root unless a udev rule hands it to the user.
    pub fn connect() -> Option<Self> {
        std::fs::OpenOptions::new().write(true).open("/dev/uinput").ok().map(|_| Self)
    }

    pub fn plug(&self, rumble: impl Fn(u8, u8) + Send + 'static) -> Option<PadDevice> {
        let device = Arc::new(Mutex::new(build_pad().ok()?));
        let stopped = Arc::new(AtomicBool::new(false));
        let (listening, flag) = (device.clone(), stopped.clone());
        std::thread::Builder::new().name("pad rumble".into()).spawn(move || listen_for_rumble(listening, flag, rumble)).ok()?;
        Some(PadDevice { device, stopped })
    }
}

// The name and ids of a wired Xbox 360 controller, which Steam and SDL map without asking.
fn build_pad() -> std::io::Result<VirtualDevice> {
    let keys = AttributeSet::from_iter([
        KeyCode::BTN_SOUTH,
        KeyCode::BTN_EAST,
        KeyCode::BTN_NORTH,
        KeyCode::BTN_WEST,
        KeyCode::BTN_TL,
        KeyCode::BTN_TR,
        KeyCode::BTN_SELECT,
        KeyCode::BTN_START,
        KeyCode::BTN_MODE,
        KeyCode::BTN_THUMBL,
        KeyCode::BTN_THUMBR,
    ]);
    let stick = AbsInfo::new(0, -32768, 32767, 16, 128, 0);
    let trigger = AbsInfo::new(0, 0, 255, 0, 0, 0);
    let hat = AbsInfo::new(0, -1, 1, 0, 0, 0);
    let mut builder = VirtualDevice::builder()?
        .name("Microsoft X-Box 360 pad")
        .input_id(InputId::new(BusType::BUS_USB, 0x045E, 0x028E, 0x0110))
        .with_keys(&keys)?
        .with_ff(&AttributeSet::from_iter([FFEffectCode::FF_RUMBLE]))?
        .with_ff_effects_max(16);
    for (axis, info) in [
        (AbsoluteAxisCode::ABS_X, stick),
        (AbsoluteAxisCode::ABS_Y, stick),
        (AbsoluteAxisCode::ABS_RX, stick),
        (AbsoluteAxisCode::ABS_RY, stick),
        (AbsoluteAxisCode::ABS_Z, trigger),
        (AbsoluteAxisCode::ABS_RZ, trigger),
        (AbsoluteAxisCode::ABS_HAT0X, hat),
        (AbsoluteAxisCode::ABS_HAT0Y, hat),
    ] {
        builder = builder.with_absolute_axis(&UinputAbsSetup::new(axis, info))?;
    }
    let device = builder.build()?;
    let flags = unsafe { libc::fcntl(device.as_raw_fd(), libc::F_GETFL) };
    unsafe { libc::fcntl(device.as_raw_fd(), libc::F_SETFL, flags | libc::O_NONBLOCK) };
    Ok(device)
}

struct Playing {
    motors: (u8, u8),
    until: Option<Instant>,
}

fn listen_for_rumble(device: Arc<Mutex<VirtualDevice>>, stopped: Arc<AtomicBool>, rumble: impl Fn(u8, u8)) {
    let fd = device.lock().map(|device| device.as_raw_fd()).unwrap_or(-1);
    let mut effects: HashMap<i16, ((u8, u8), u16)> = HashMap::new();
    let mut playing: Option<Playing> = None;
    while !stopped.load(Ordering::Relaxed) {
        let mut poll = libc::pollfd { fd, events: libc::POLLIN, revents: 0 };
        let ready = unsafe { libc::poll(&mut poll, 1, 50) } > 0;
        if playing.as_ref().is_some_and(|playing| playing.until.is_some_and(|until| Instant::now() >= until)) {
            playing = None;
            rumble(0, 0);
        }
        if !ready {
            continue;
        }
        let Ok(mut device) = device.lock() else { return };
        let events: Vec<InputEvent> = match device.fetch_events() {
            Ok(events) => events.collect(),
            Err(_) => continue,
        };
        for event in events {
            match event.destructure() {
                EventSummary::UInput(event, UInputCode::UI_FF_UPLOAD, ..) => {
                    let Ok(mut upload) = device.process_ff_upload(event) else { continue };
                    let effect = upload.effect();
                    if let FFEffectKind::Rumble { strong_magnitude, weak_magnitude } = effect.kind {
                        effects.insert(upload.effect_id(), (((strong_magnitude >> 8) as u8, (weak_magnitude >> 8) as u8), effect.replay.length));
                    }
                    upload.set_retval(0);
                }
                EventSummary::UInput(event, UInputCode::UI_FF_ERASE, ..) => {
                    if let Ok(erase) = device.process_ff_erase(event) {
                        effects.remove(&(erase.effect_id() as i16));
                    }
                }
                EventSummary::ForceFeedback(_, effect, value) => {
                    let Some(&(motors, length)) = effects.get(&(effect.0 as i16)) else { continue };
                    if value > 0 {
                        let until = (length > 0).then(|| Instant::now() + Duration::from_millis(length as u64));
                        playing = Some(Playing { motors, until });
                        rumble(motors.0, motors.1);
                    } else if playing.as_ref().is_some_and(|playing| playing.motors == motors) {
                        playing = None;
                        rumble(0, 0);
                    }
                }
                _ => {}
            }
        }
    }
}

pub struct PadDevice {
    device: Arc<Mutex<VirtualDevice>>,
    stopped: Arc<AtomicBool>,
}

impl PadDevice {
    pub fn update(&mut self, state: &PadState) {
        const BUTTONS: [(u16, KeyCode); 11] = [
            (0x1000, KeyCode::BTN_SOUTH),
            (0x2000, KeyCode::BTN_EAST),
            (0x4000, KeyCode::BTN_NORTH),
            (0x8000, KeyCode::BTN_WEST),
            (0x0100, KeyCode::BTN_TL),
            (0x0200, KeyCode::BTN_TR),
            (0x0020, KeyCode::BTN_SELECT),
            (0x0010, KeyCode::BTN_START),
            (0x0400, KeyCode::BTN_MODE),
            (0x0040, KeyCode::BTN_THUMBL),
            (0x0080, KeyCode::BTN_THUMBR),
        ];
        let held = |bit: u16| (state.buttons & bit != 0) as i32;
        let mut events: Vec<InputEvent> = BUTTONS.iter().map(|(bit, code)| InputEvent::new(EventType::KEY.0, code.0, held(*bit))).collect();
        // XInput counts up as positive and evdev counts down as positive.
        let flip = |value: i16| -(value as i32).max(-32767);
        for (axis, value) in [
            (AbsoluteAxisCode::ABS_X, state.left_x as i32),
            (AbsoluteAxisCode::ABS_Y, flip(state.left_y)),
            (AbsoluteAxisCode::ABS_RX, state.right_x as i32),
            (AbsoluteAxisCode::ABS_RY, flip(state.right_y)),
            (AbsoluteAxisCode::ABS_Z, state.left_trigger as i32),
            (AbsoluteAxisCode::ABS_RZ, state.right_trigger as i32),
            (AbsoluteAxisCode::ABS_HAT0X, held(0x0008) - held(0x0004)),
            (AbsoluteAxisCode::ABS_HAT0Y, held(0x0002) - held(0x0001)),
        ] {
            events.push(InputEvent::new(EventType::ABSOLUTE.0, axis.0, value));
        }
        if let Ok(mut device) = self.device.lock() {
            let _ = device.emit(&events);
        }
    }
}

impl Drop for PadDevice {
    fn drop(&mut self) {
        self.stopped.store(true, Ordering::Relaxed);
    }
}
