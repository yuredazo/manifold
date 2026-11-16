use super::{Rect, Target};
use crate::capture::ShareKind;
use crate::secure::{self, RawInput};
use manifold_protocol::control::{MouseButton, PadState};
use std::sync::Arc;
use vigem_client::{Client, TargetId, XButtons, XGamepad, Xbox360Wired};
use windows::Win32::Foundation::{HWND, POINT, RECT};
use windows::Win32::Graphics::Dwm::{DWMWA_EXTENDED_FRAME_BOUNDS, DwmGetWindowAttribute};
use windows::Win32::Graphics::Gdi::{ClientToScreen, GetMonitorInfoW, HMONITOR, MONITOR_DEFAULTTOPRIMARY, MONITORINFO, MonitorFromPoint};
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
const EXTENDED: u16 = 0xE000;

fn window(target: &Target) -> HWND {
    HWND(target.handle as isize as *mut _)
}

fn rect(rect: RECT) -> Rect {
    Rect { left: rect.left, top: rect.top, right: rect.right, bottom: rect.bottom }
}

// While a UAC prompt or the lock screen is up, a window's stream shows the main display, where the prompt appears.
pub fn source(target: &Target) -> Option<Rect> {
    unsafe {
        if target.kind == ShareKind::Window && secure::showing() {
            let mut info = MONITORINFO { cbSize: size_of::<MONITORINFO>() as u32, ..Default::default() };
            return GetMonitorInfoW(MonitorFromPoint(POINT::default(), MONITOR_DEFAULTTOPRIMARY), &mut info).as_bool().then_some(rect(info.rcMonitor));
        }
        if target.kind == ShareKind::Display {
            let mut info = MONITORINFO { cbSize: size_of::<MONITORINFO>() as u32, ..Default::default() };
            return GetMonitorInfoW(HMONITOR((target.handle & !MONITOR_TAG) as isize as *mut _), &mut info).as_bool().then_some(rect(info.rcMonitor));
        }
        let window = window(target);
        if !IsWindow(Some(window)).as_bool() || IsIconic(window).as_bool() {
            return None;
        }
        if target.client_only {
            let mut client = RECT::default();
            let mut origin = POINT::default();
            GetClientRect(window, &mut client).ok()?;
            ClientToScreen(window, &mut origin).as_bool().then_some(Rect { left: origin.x, top: origin.y, right: origin.x + client.right, bottom: origin.y + client.bottom })
        } else {
            let mut frame = RECT::default();
            DwmGetWindowAttribute(window, DWMWA_EXTENDED_FRAME_BOUNDS, &mut frame as *mut RECT as *mut _, size_of::<RECT>() as u32).ok()?;
            Some(rect(frame))
        }
    }
}

// A game that turns its camera with the mouse confines the cursor or hides it while its window is in front.
// Windows also hides the cursor while someone types, but the next injected move shows it again.
pub fn cursor_captured(target: &Target) -> bool {
    if secure::showing() {
        return false;
    }
    unsafe {
        if target.kind == ShareKind::Window && GetAncestor(GetForegroundWindow(), GA_ROOT) != window(target) {
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
pub fn bring_forward(target: &Target) {
    if secure::showing() {
        return;
    }
    unsafe {
        if GetForegroundWindow() != window(target) {
            let _ = SetForegroundWindow(window(target));
        }
    }
}

#[derive(Clone)]
pub struct PadBus(Arc<Client>);

impl PadBus {
    pub fn connect() -> Option<Self> {
        Client::connect().ok().map(|client| Self(Arc::new(client)))
    }

    pub fn plug(&self, rumble: impl Fn(u8, u8) + Send + 'static) -> Option<PadDevice> {
        let mut device = Xbox360Wired::new(self.0.clone(), TargetId::XBOX360_WIRED);
        device.plugin().ok()?;
        device.wait_ready().ok()?;
        // The thread ends by itself when the pad is unplugged.
        if let Ok(request) = device.request_notification() {
            request.spawn_thread(move |_, note| rumble(note.large_motor, note.small_motor));
        }
        Some(PadDevice(device))
    }
}

pub struct PadDevice(Xbox360Wired<Arc<Client>>);

impl PadDevice {
    pub fn update(&mut self, state: &PadState) {
        let _ = self.0.update(&XGamepad {
            buttons: XButtons::from(state.buttons),
            left_trigger: state.left_trigger,
            right_trigger: state.right_trigger,
            thumb_lx: state.left_x,
            thumb_ly: state.left_y,
            thumb_rx: state.right_x,
            thumb_ry: state.right_y,
        });
    }
}

pub fn move_by(dx: i32, dy: i32) {
    send(MOUSEEVENTF_MOVE, dx, dy, 0);
}

pub fn scroll(dx: i32, dy: i32) {
    if dy != 0 {
        send(MOUSEEVENTF_WHEEL, 0, 0, dy);
    }
    if dx != 0 {
        send(MOUSEEVENTF_HWHEEL, 0, 0, dx);
    }
}

pub fn press(button: MouseButton, down: bool) {
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
pub fn key(usage: u16, down: bool) {
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

pub fn type_text(text: &str) {
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
    if secure::showing() {
        secure::send(&[RawInput { kind: INPUT_KEYBOARD.0, dx: 0, dy: 0, data: 0, flags: flags.0, scan }]);
        return;
    }
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
pub fn move_to(x: i32, y: i32) {
    let (left, top, width, height) = unsafe {
        (GetSystemMetrics(SM_XVIRTUALSCREEN), GetSystemMetrics(SM_YVIRTUALSCREEN), GetSystemMetrics(SM_CXVIRTUALSCREEN), GetSystemMetrics(SM_CYVIRTUALSCREEN))
    };
    if width < 2 || height < 2 {
        return;
    }
    let x = ((x - left) as f64 * FULL / (width - 1) as f64).round() as i32;
    let y = ((y - top) as f64 * FULL / (height - 1) as f64).round() as i32;
    send(MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK, x, y, 0);
}

// Windows drops input aimed at a program running as administrator unless this one is too. A UAC prompt or the lock
// screen takes input only from the secure desktop helper.
fn send(flags: MOUSE_EVENT_FLAGS, dx: i32, dy: i32, data: i32) {
    if secure::showing() {
        secure::send(&[RawInput { kind: INPUT_MOUSE.0, dx, dy, data: data as u32, flags: flags.0, scan: 0 }]);
        return;
    }
    let input = INPUT { r#type: INPUT_MOUSE, Anonymous: INPUT_0 { mi: MOUSEINPUT { dx, dy, mouseData: data as u32, dwFlags: flags, time: 0, dwExtraInfo: 0 } } };
    unsafe {
        SendInput(&[input], size_of::<INPUT>() as i32);
    }
}
