use crate::capture::ShareKind;
use crate::hub::Input as HubInput;
use manifold_protocol::control::{Input, MouseButton, PadState};
use std::collections::hash_map::Entry;
use std::collections::{BTreeMap, HashMap, HashSet};
use std::sync::Arc;
use std::sync::mpsc::Sender;
use std::time::{Duration, Instant};
use vigem_client::{Client, TargetId, XButtons, XGamepad, Xbox360Wired};
use windows::Win32::Foundation::{HWND, POINT, RECT};
use windows::Win32::Graphics::Dwm::{DWMWA_EXTENDED_FRAME_BOUNDS, DwmGetWindowAttribute};
use windows::Win32::Graphics::Gdi::{ClientToScreen, GetMonitorInfoW, HMONITOR, MONITORINFO};
use windows::Win32::UI::Input::KeyboardAndMouse::{
    INPUT, INPUT_0, INPUT_KEYBOARD, INPUT_MOUSE, KEYBD_EVENT_FLAGS, KEYBDINPUT, KEYEVENTF_EXTENDEDKEY, KEYEVENTF_KEYUP, KEYEVENTF_SCANCODE, KEYEVENTF_UNICODE,
    MOUSE_EVENT_FLAGS, MOUSEEVENTF_ABSOLUTE, MOUSEEVENTF_HWHEEL, MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP, MOUSEEVENTF_MIDDLEDOWN,
    MOUSEEVENTF_MIDDLEUP, MOUSEEVENTF_MOVE, MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP, MOUSEEVENTF_VIRTUALDESK, MOUSEEVENTF_WHEEL, MOUSEEVENTF_XDOWN, MOUSEEVENTF_XUP,
    MOUSEINPUT, SendInput,
};
use windows::Win32::UI::WindowsAndMessaging::{
    CURSOR_SHOWING, CURSORINFO, GA_ROOT, GetAncestor, GetClientRect, GetClipCursor, GetCursorInfo, GetForegroundWindow, GetSystemMetrics, IsIconic, IsWindow,
    SM_CXVIRTUALSCREEN, SM_CYVIRTUALSCREEN, SM_XVIRTUALSCREEN, SM_YVIRTUALSCREEN, SetForegroundWindow, XBUTTON1, XBUTTON2,
};

// The tag native/bridge.cpp puts on a display's handle to tell it from a window's.
const MONITOR_TAG: i64 = 1 << 62;
const FULL: f64 = 65535.0;
// A gap this long in the keyboard order means a message that will never come; the rest are applied anyway.
const MAX_WAITING_KEYS: usize = 64;
const EXTENDED: u16 = 0xE000;
const LEFT_ALT: u16 = 0xE2;
const RIGHT_ALT: u16 = 0xE6;
// XInput reads four controllers at most.
const MAX_PADS: u8 = 4;
const DRIVER_RETRY: Duration = Duration::from_secs(5);

pub struct Target {
    pub kind: ShareKind,
    pub handle: i64,
    pub client_only: bool,
    pub stream_width: i32,
    pub stream_height: i32,
}

impl Target {
    fn window(&self) -> HWND {
        HWND(self.handle as isize as *mut _)
    }

    fn source(&self) -> Option<RECT> {
        unsafe {
            if self.kind == ShareKind::Display {
                let mut info = MONITORINFO { cbSize: size_of::<MONITORINFO>() as u32, ..Default::default() };
                return GetMonitorInfoW(HMONITOR((self.handle & !MONITOR_TAG) as isize as *mut _), &mut info).as_bool().then_some(info.rcMonitor);
            }
            let window = self.window();
            if !IsWindow(Some(window)).as_bool() || IsIconic(window).as_bool() {
                return None;
            }
            if self.client_only {
                let mut client = RECT::default();
                let mut origin = POINT::default();
                GetClientRect(window, &mut client).ok()?;
                ClientToScreen(window, &mut origin).as_bool().then_some(RECT { left: origin.x, top: origin.y, right: origin.x + client.right, bottom: origin.y + client.bottom })
            } else {
                let mut frame = RECT::default();
                DwmGetWindowAttribute(window, DWMWA_EXTENDED_FRAME_BOUNDS, &mut frame as *mut RECT as *mut _, size_of::<RECT>() as u32).ok()?;
                Some(frame)
            }
        }
    }

    // The capture fits the source into the stream and centres it. A point on a black bar stops at the window's edge,
    // as a real mouse stops at the edge of the screen.
    fn to_screen(&self, x: u16, y: u16) -> Option<POINT> {
        let source = self.source()?;
        let (width, height) = ((source.right - source.left) as f64, (source.bottom - source.top) as f64);
        let (stream_width, stream_height) = (self.stream_width as f64, self.stream_height as f64);
        if width < 1.0 || height < 1.0 || stream_width < 1.0 || stream_height < 1.0 {
            return None;
        }
        let scale = (stream_width / width).min(stream_height / height);
        let (fitted_width, fitted_height) = (width * scale, height * scale);
        let u = ((x as f64 / FULL * stream_width - (stream_width - fitted_width) / 2.0) / fitted_width).clamp(0.0, 1.0);
        let v = ((y as f64 / FULL * stream_height - (stream_height - fitted_height) / 2.0) / fitted_height).clamp(0.0, 1.0);
        Some(POINT {
            x: (source.left + (u * width) as i32).min(source.right - 1),
            y: (source.top + (v * height) as i32).min(source.bottom - 1),
        })
    }

    // A game that turns its camera with the mouse confines the cursor or hides it while its window is in front.
    // Windows also hides the cursor while someone types, but the next injected move shows it again.
    pub fn cursor_captured(&self) -> bool {
        unsafe {
            if self.kind == ShareKind::Window && GetAncestor(GetForegroundWindow(), GA_ROOT) != self.window() {
                return false;
            }
            let mut info = CURSORINFO { cbSize: size_of::<CURSORINFO>() as u32, ..Default::default() };
            let hidden = GetCursorInfo(&mut info).is_ok() && info.flags.0 & CURSOR_SHOWING.0 == 0;
            let left = GetSystemMetrics(SM_XVIRTUALSCREEN);
            let top = GetSystemMetrics(SM_YVIRTUALSCREEN);
            let screen = RECT { left, top, right: left + GetSystemMetrics(SM_CXVIRTUALSCREEN), bottom: top + GetSystemMetrics(SM_CYVIRTUALSCREEN) };
            let mut clip = RECT::default();
            let clipped = GetClipCursor(&mut clip).is_ok() && clip != screen;
            hidden || clipped
        }
    }

    // Windows keeps a background process from taking the foreground, but this one has just sent input, which lets it.
    fn bring_forward(&self) {
        if self.kind != ShareKind::Window {
            return;
        }
        unsafe {
            if GetForegroundWindow() != self.window() {
                let _ = SetForegroundWindow(self.window());
            }
        }
    }
}

struct Pad {
    seq: u32,
    state: PadState,
    device: Xbox360Wired<Arc<Client>>,
    rumble: Option<(u8, u8)>,
}

fn plug(bus: Arc<Client>, rumbles: Sender<HubInput>, device_key: String, stream_id: u16, pad: u8) -> Option<Xbox360Wired<Arc<Client>>> {
    let mut device = Xbox360Wired::new(bus, TargetId::XBOX360_WIRED);
    device.plugin().ok()?;
    device.wait_ready().ok()?;
    // The thread ends by itself when the pad is unplugged.
    if let Ok(request) = device.request_notification() {
        request.spawn_thread(move |_, note| {
            let _ = rumbles.send(HubInput::Rumble { device_key: device_key.clone(), stream_id, pad, large: note.large_motor, small: note.small_motor });
        });
    }
    Some(device)
}

enum KeyEvent {
    Key { usage: u16, down: bool },
    Text(String),
}

struct Gate {
    pointer: u32,
    buttons: [u32; 5],
    floor: u32,
    pressed: [bool; 5],
    next_key: u32,
    waiting: BTreeMap<u32, KeyEvent>,
    keys: HashSet<u16>,
    pads: HashMap<u8, Pad>,
}

impl Default for Gate {
    fn default() -> Self {
        Self {
            pointer: 0,
            buttons: [0; 5],
            floor: 0,
            pressed: [false; 5],
            next_key: 1,
            waiting: BTreeMap::new(),
            keys: HashSet::new(),
            pads: HashMap::new(),
        }
    }
}

impl Gate {
    fn set_pad(&mut self, seq: u32, index: u8, state: PadState, target: &Target, plug: impl FnOnce() -> Option<Xbox360Wired<Arc<Client>>>) {
        if index >= MAX_PADS || seq <= self.floor || self.pads.get(&index).is_some_and(|pad| seq <= pad.seq) {
            return;
        }
        let pad = match self.pads.entry(index) {
            Entry::Occupied(entry) => entry.into_mut(),
            Entry::Vacant(entry) => {
                let Some(device) = plug() else { return };
                entry.insert(Pad { seq: 0, state: PadState::default(), device, rumble: None })
            }
        };
        if state.buttons & !pad.state.buttons != 0 {
            target.bring_forward();
        }
        pad.seq = seq;
        pad.state = state;
        let _ = pad.device.update(&XGamepad {
            buttons: XButtons::from(state.buttons),
            left_trigger: state.left_trigger,
            right_trigger: state.right_trigger,
            thumb_lx: state.left_x,
            thumb_ly: state.left_y,
            thumb_rx: state.right_x,
            thumb_ry: state.right_y,
        });
    }

    fn queue_key(&mut self, seq: u32, event: KeyEvent, target: &Target) {
        if seq < self.next_key {
            return;
        }
        self.waiting.insert(seq, event);
        if self.waiting.len() > MAX_WAITING_KEYS {
            self.next_key = *self.waiting.keys().next().unwrap_or(&self.next_key);
        }
        while let Some(event) = self.waiting.remove(&self.next_key) {
            self.next_key += 1;
            match event {
                KeyEvent::Key { usage, down } => {
                    if down {
                        target.bring_forward();
                        self.keys.insert(usage);
                    } else if !self.keys.remove(&usage) {
                        continue;
                    }
                    key(usage, down);
                }
                KeyEvent::Text(text) => {
                    target.bring_forward();
                    type_text(&text);
                }
            }
        }
    }
}

pub struct RemoteInput {
    gates: HashMap<(String, u16), Gate>,
    pad_bus: Option<Arc<Client>>,
    bus_tried_at: Option<Instant>,
    rumbles: Sender<HubInput>,
}

impl RemoteInput {
    pub fn new(rumbles: Sender<HubInput>) -> Self {
        Self { gates: HashMap::new(), pad_bus: None, bus_tried_at: None, rumbles }
    }

    pub fn set_rumble(&mut self, device_key: &str, stream_id: u16, index: u8, large: u8, small: u8) -> bool {
        let pad = self.gates.get_mut(&(device_key.to_string(), stream_id)).and_then(|gate| gate.pads.get_mut(&index));
        pad.map(|pad| pad.rumble = Some((large, small))).is_some()
    }

    pub fn rumbles(&self) -> Vec<(String, u16, u8, u8, u8)> {
        let mut out = Vec::new();
        for ((device_key, stream_id), gate) in &self.gates {
            for (index, pad) in &gate.pads {
                if let Some((large, small)) = pad.rumble {
                    out.push((device_key.clone(), *stream_id, *index, large, small));
                }
            }
        }
        out
    }

    pub fn holds_alt(&self, device_key: &str, stream_id: u16) -> bool {
        self.gates.get(&(device_key.to_string(), stream_id)).is_some_and(|gate| gate.keys.contains(&LEFT_ALT) || gate.keys.contains(&RIGHT_ALT))
    }

    pub fn pads_need_driver(&self) -> bool {
        self.pad_bus.is_none() && self.bus_tried_at.is_some()
    }

    // Retried now and then, so installing ViGEmBus works without restarting the hub.
    fn pad_bus(&mut self) -> Option<Arc<Client>> {
        if self.pad_bus.is_none() && self.bus_tried_at.is_none_or(|at| at.elapsed() >= DRIVER_RETRY) {
            self.bus_tried_at = Some(Instant::now());
            self.pad_bus = Client::connect().ok().map(Arc::new);
        }
        self.pad_bus.clone()
    }

    pub fn apply(&mut self, device_key: &str, target: &Target, input: &Input) {
        let bus = if matches!(input, Input::Gamepad { .. }) { self.pad_bus() } else { None };
        let gate = self.gates.entry((device_key.to_string(), input.stream_id())).or_default();
        match *input {
            Input::Pointer { seq, x, y, .. } => {
                if seq <= gate.pointer {
                    return;
                }
                gate.pointer = seq;
                if let Some(point) = target.to_screen(x, y) {
                    move_to(point);
                }
            }
            Input::Motion { dx, dy, .. } => send(MOUSEEVENTF_MOVE, dx as i32, dy as i32, 0),
            Input::Button { seq, button, down, at, .. } => {
                let slot = button as usize - 1;
                if seq <= gate.floor || seq <= gate.buttons[slot] {
                    return;
                }
                gate.buttons[slot] = seq;
                if gate.pressed[slot] == down {
                    return;
                }
                if let Some((x, y)) = at {
                    match target.to_screen(x, y) {
                        Some(point) => move_to(point),
                        None if down => return,
                        None => {}
                    }
                }
                if down {
                    target.bring_forward();
                }
                gate.pressed[slot] = down;
                press(button, down);
            }
            Input::Scroll { dx, dy, .. } => {
                if dy != 0 {
                    send(MOUSEEVENTF_WHEEL, 0, 0, dy as i32);
                }
                if dx != 0 {
                    send(MOUSEEVENTF_HWHEEL, 0, 0, dx as i32);
                }
            }
            Input::Key { seq, usage, down, .. } => gate.queue_key(seq, KeyEvent::Key { usage, down }, target),
            Input::Text { seq, ref text, .. } => gate.queue_key(seq, KeyEvent::Text(text.clone()), target),
            Input::Release { seq, key_seq, .. } => {
                gate.floor = gate.floor.max(seq);
                gate.next_key = gate.next_key.max(key_seq.saturating_add(1));
                gate.waiting.retain(|waiting, _| *waiting > key_seq);
                release(gate);
            }
            Input::Gamepad { stream_id, seq, pad, state } => {
                let rumbles = self.rumbles.clone();
                gate.set_pad(seq, pad, state, target, || plug(bus?, rumbles, device_key.to_string(), stream_id, pad));
            }
        }
    }

    pub fn keep(&mut self, still: impl Fn(&str, u16) -> bool) {
        self.gates.retain(|(device_key, stream_id), gate| {
            let keep = still(device_key, *stream_id);
            if !keep {
                release(gate);
            }
            keep
        });
    }

    pub fn forget(&mut self, device_key: &str, stream_id: u16) {
        self.keep(|key, stream| !(key == device_key && stream == stream_id));
    }
}

fn release(gate: &mut Gate) {
    for pad in gate.pads.values_mut() {
        pad.state = PadState::default();
        let _ = pad.device.update(&XGamepad::default());
    }
    for usage in std::mem::take(&mut gate.keys) {
        key(usage, false);
    }
    for button in MouseButton::ALL {
        let slot = button as usize - 1;
        if std::mem::take(&mut gate.pressed[slot]) {
            press(button, false);
        }
    }
}

fn press(button: MouseButton, down: bool) {
    let (flags, data) = match (button, down) {
        (MouseButton::Left, true) => (MOUSEEVENTF_LEFTDOWN, 0),
        (MouseButton::Left, false) => (MOUSEEVENTF_LEFTUP, 0),
        (MouseButton::Right, true) => (MOUSEEVENTF_RIGHTDOWN, 0),
        (MouseButton::Right, false) => (MOUSEEVENTF_RIGHTUP, 0),
        (MouseButton::Middle, true) => (MOUSEEVENTF_MIDDLEDOWN, 0),
        (MouseButton::Middle, false) => (MOUSEEVENTF_MIDDLEUP, 0),
        (MouseButton::Back, true) => (MOUSEEVENTF_XDOWN, XBUTTON1 as i32),
        (MouseButton::Back, false) => (MOUSEEVENTF_XUP, XBUTTON1 as i32),
        (MouseButton::Forward, true) => (MOUSEEVENTF_XDOWN, XBUTTON2 as i32),
        (MouseButton::Forward, false) => (MOUSEEVENTF_XUP, XBUTTON2 as i32),
    };
    send(flags, 0, 0, data);
}

// Keys go in by scan code, the physical key, so a game reading WASD gets the same keys on any layout.
fn key(usage: u16, down: bool) {
    let Some(code) = scan_code(usage) else { return };
    let mut flags = KEYEVENTF_SCANCODE;
    if code & EXTENDED != 0 {
        flags |= KEYEVENTF_EXTENDEDKEY;
    }
    if !down {
        flags |= KEYEVENTF_KEYUP;
    }
    send_key(0, code & 0xFF, flags);
}

fn type_text(text: &str) {
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
                let mut units = [0u16; 2];
                for unit in c.encode_utf16(&mut units) {
                    send_key(*unit, 0, KEYEVENTF_UNICODE);
                    send_key(*unit, 0, KEYEVENTF_UNICODE | KEYEVENTF_KEYUP);
                }
            }
        }
    }
}

fn send_key(unicode: u16, scan: u16, flags: KEYBD_EVENT_FLAGS) {
    let scan = if flags.contains(KEYEVENTF_UNICODE) { unicode } else { scan };
    let input = INPUT { r#type: INPUT_KEYBOARD, Anonymous: INPUT_0 { ki: KEYBDINPUT { wVk: Default::default(), wScan: scan, dwFlags: flags, time: 0, dwExtraInfo: 0 } } };
    unsafe {
        SendInput(&[input], size_of::<INPUT>() as i32);
    }
}

// USB HID keyboard usages to set 1 scan codes, with 0xE0 in the high byte for the extended keys.
fn scan_code(usage: u16) -> Option<u16> {
    const LETTERS: [u16; 26] = [
        0x1E, 0x30, 0x2E, 0x20, 0x12, 0x21, 0x22, 0x23, 0x17, 0x24, 0x25, 0x26, 0x32, 0x31, 0x18, 0x19, 0x10, 0x13, 0x1F, 0x14, 0x16, 0x2F, 0x11, 0x2D, 0x15, 0x2C,
    ];
    Some(match usage {
        0x04..=0x1D => LETTERS[(usage - 0x04) as usize],
        0x1E..=0x27 => usage - 0x1E + 0x02,
        0x28 => 0x1C,
        0x29 => 0x01,
        0x2A => 0x0E,
        0x2B => 0x0F,
        0x2C => 0x39,
        0x2D => 0x0C,
        0x2E => 0x0D,
        0x2F => 0x1A,
        0x30 => 0x1B,
        0x31 | 0x32 => 0x2B,
        0x33 => 0x27,
        0x34 => 0x28,
        0x35 => 0x29,
        0x36 => 0x33,
        0x37 => 0x34,
        0x38 => 0x35,
        0x39 => 0x3A,
        0x3A..=0x43 => usage - 0x3A + 0x3B,
        0x44 => 0x57,
        0x45 => 0x58,
        0x46 => EXTENDED | 0x37,
        0x47 => 0x46,
        0x49 => EXTENDED | 0x52,
        0x4A => EXTENDED | 0x47,
        0x4B => EXTENDED | 0x49,
        0x4C => EXTENDED | 0x53,
        0x4D => EXTENDED | 0x4F,
        0x4E => EXTENDED | 0x51,
        0x4F => EXTENDED | 0x4D,
        0x50 => EXTENDED | 0x4B,
        0x51 => EXTENDED | 0x50,
        0x52 => EXTENDED | 0x48,
        0x53 => 0x45,
        0x54 => EXTENDED | 0x35,
        0x55 => 0x37,
        0x56 => 0x4A,
        0x57 => 0x4E,
        0x58 => EXTENDED | 0x1C,
        0x59 => 0x4F,
        0x5A => 0x50,
        0x5B => 0x51,
        0x5C => 0x4B,
        0x5D => 0x4C,
        0x5E => 0x4D,
        0x5F => 0x47,
        0x60 => 0x48,
        0x61 => 0x49,
        0x62 => 0x52,
        0x63 => 0x53,
        0x64 => 0x56,
        0x65 => EXTENDED | 0x5D,
        0xE0 => 0x1D,
        0xE1 => 0x2A,
        0xE2 => 0x38,
        0xE3 => EXTENDED | 0x5B,
        0xE4 => EXTENDED | 0x1D,
        0xE5 => 0x36,
        0xE6 => EXTENDED | 0x38,
        0xE7 => EXTENDED | 0x5C,
        _ => return None,
    })
}

// Absolute positions are given in 0..65535 across every display together.
fn move_to(point: POINT) {
    let (left, top, width, height) = unsafe {
        (GetSystemMetrics(SM_XVIRTUALSCREEN), GetSystemMetrics(SM_YVIRTUALSCREEN), GetSystemMetrics(SM_CXVIRTUALSCREEN), GetSystemMetrics(SM_CYVIRTUALSCREEN))
    };
    if width < 2 || height < 2 {
        return;
    }
    let x = ((point.x - left) as f64 * FULL / (width - 1) as f64).round() as i32;
    let y = ((point.y - top) as f64 * FULL / (height - 1) as f64).round() as i32;
    send(MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK, x, y, 0);
}

// Windows drops input aimed at a program running as administrator unless this one is too.
fn send(flags: MOUSE_EVENT_FLAGS, dx: i32, dy: i32, data: i32) {
    let input = INPUT { r#type: INPUT_MOUSE, Anonymous: INPUT_0 { mi: MOUSEINPUT { dx, dy, mouseData: data as u32, dwFlags: flags, time: 0, dwExtraInfo: 0 } } };
    unsafe {
        SendInput(&[input], size_of::<INPUT>() as i32);
    }
}
