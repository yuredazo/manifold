#[cfg(target_os = "linux")]
mod linux;
#[cfg(windows)]
mod windows;

use crate::capture::ShareKind;
use crate::hub::Input as HubInput;
use manifold_protocol::control::{Input, MouseButton, PadState};
use std::collections::hash_map::Entry;
use std::collections::{BTreeMap, HashMap, HashSet};
use std::sync::mpsc::Sender;
use std::time::{Duration, Instant};

#[cfg(target_os = "linux")]
use self::linux as desktop;
#[cfg(windows)]
use self::windows as desktop;
use desktop::{PadBus, PadDevice};

const FULL: f64 = 65535.0;
// A gap this long in the keyboard order means a message that will never come; the rest are applied anyway.
const MAX_WAITING_KEYS: usize = 64;
const LEFT_ALT: u16 = 0xE2;
const RIGHT_ALT: u16 = 0xE6;
// XInput reads four controllers at most.
const MAX_PADS: u8 = 4;
const DRIVER_RETRY: Duration = Duration::from_secs(5);

#[cfg_attr(not(windows), allow(dead_code))]
pub struct Target {
    pub kind: ShareKind,
    pub handle: i64,
    pub client_only: bool,
    pub stream_width: i32,
    pub stream_height: i32,
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct Rect {
    pub left: i32,
    pub top: i32,
    pub right: i32,
    pub bottom: i32,
}

impl Target {
    // The capture fits the source into the stream and centres it. A point on a black bar stops at the window's edge,
    // as a real mouse stops at the edge of the screen.
    fn to_screen(&self, x: u16, y: u16) -> Option<(i32, i32)> {
        let source = desktop::source(self)?;
        let (width, height) = ((source.right - source.left) as f64, (source.bottom - source.top) as f64);
        let (stream_width, stream_height) = (self.stream_width as f64, self.stream_height as f64);
        if width < 1.0 || height < 1.0 || stream_width < 1.0 || stream_height < 1.0 {
            return None;
        }
        let scale = (stream_width / width).min(stream_height / height);
        let (fitted_width, fitted_height) = (width * scale, height * scale);
        let u = ((x as f64 / FULL * stream_width - (stream_width - fitted_width) / 2.0) / fitted_width).clamp(0.0, 1.0);
        let v = ((y as f64 / FULL * stream_height - (stream_height - fitted_height) / 2.0) / fitted_height).clamp(0.0, 1.0);
        Some(((source.left + (u * width) as i32).min(source.right - 1), (source.top + (v * height) as i32).min(source.bottom - 1)))
    }

    pub fn cursor_captured(&self) -> bool {
        desktop::cursor_captured(self)
    }

    fn bring_forward(&self) {
        if self.kind == ShareKind::Window {
            desktop::bring_forward(self);
        }
    }
}

struct Pad {
    seq: u32,
    state: PadState,
    device: PadDevice,
    rumble: Option<(u8, u8)>,
}

fn plug(bus: PadBus, rumbles: Sender<HubInput>, device_key: String, stream_id: u16, pad: u8) -> Option<PadDevice> {
    bus.plug(move |large, small| {
        let _ = rumbles.send(HubInput::Rumble { device_key: device_key.clone(), stream_id, pad, large, small });
    })
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
    fn set_pad(&mut self, seq: u32, index: u8, state: PadState, target: &Target, plug: impl FnOnce() -> Option<PadDevice>) {
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
        pad.device.update(&state);
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
                    desktop::key(usage, down);
                }
                KeyEvent::Text(text) => {
                    target.bring_forward();
                    desktop::type_text(&text);
                }
            }
        }
    }
}

pub struct RemoteInput {
    gates: HashMap<(String, u16), Gate>,
    pad_bus: Option<PadBus>,
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

    // Retried now and then, so installing the driver or getting access to it works without restarting the hub.
    fn pad_bus(&mut self) -> Option<PadBus> {
        if self.pad_bus.is_none() && self.bus_tried_at.is_none_or(|at| at.elapsed() >= DRIVER_RETRY) {
            self.bus_tried_at = Some(Instant::now());
            self.pad_bus = PadBus::connect();
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
                if let Some((x, y)) = target.to_screen(x, y) {
                    desktop::move_to(x, y);
                }
            }
            Input::Motion { dx, dy, .. } => desktop::move_by(dx as i32, dy as i32),
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
                        Some((x, y)) => desktop::move_to(x, y),
                        None if down => return,
                        None => {}
                    }
                }
                if down {
                    target.bring_forward();
                }
                gate.pressed[slot] = down;
                desktop::press(button, down);
            }
            Input::Scroll { dx, dy, .. } => desktop::scroll(dx as i32, dy as i32),
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
        pad.device.update(&PadState::default());
    }
    for usage in std::mem::take(&mut gate.keys) {
        desktop::key(usage, false);
    }
    for button in MouseButton::ALL {
        let slot = button as usize - 1;
        if std::mem::take(&mut gate.pressed[slot]) {
            desktop::press(button, false);
        }
    }
}
