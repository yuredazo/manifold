use std::sync::OnceLock;
use std::sync::atomic::{AtomicBool, AtomicIsize, Ordering};
use windows::Win32::Foundation::{HWND, LPARAM, LRESULT, POINT, WPARAM};
use windows::Win32::System::LibraryLoader::GetModuleHandleW;
use windows::Win32::UI::Shell::{NIF_ICON, NIF_MESSAGE, NIF_TIP, NIM_ADD, NIM_DELETE, NOTIFYICONDATAW, Shell_NotifyIconW};
use windows::Win32::UI::WindowsAndMessaging::*;
use windows::core::{PCWSTR, w};

// A hidden window that is never shown: it owns the tray icon, and a second launch finds it by class to bring this one up.
const CLASS: PCWSTR = w!("ManifoldHubTray");
const WM_TRAY: u32 = WM_APP + 1;
const WM_SHOW: u32 = WM_APP + 2;
const WM_SYNC: u32 = WM_APP + 3;
const SHOW_ITEM: usize = 1;
const PAUSE_ITEM: usize = 2;
const EXIT_ITEM: usize = 3;

pub struct Actions {
    pub show: Box<dyn Fn() + Send + Sync>,
    pub pause: Box<dyn Fn(bool) + Send + Sync>,
    pub exit: Box<dyn Fn() + Send + Sync>,
}

static ACTIONS: OnceLock<Actions> = OnceLock::new();
static WINDOW: AtomicIsize = AtomicIsize::new(0);
static WANTED: AtomicBool = AtomicBool::new(false);
static SHOWN: AtomicBool = AtomicBool::new(false);
static PAUSED: AtomicBool = AtomicBool::new(false);
static RESTARTED: OnceLock<u32> = OnceLock::new();

fn window() -> Option<HWND> {
    let raw = WINDOW.load(Ordering::Acquire);
    (raw != 0).then_some(HWND(raw as *mut _))
}

fn icon_data(window: HWND) -> NOTIFYICONDATAW {
    let mut data = NOTIFYICONDATAW { cbSize: std::mem::size_of::<NOTIFYICONDATAW>() as u32, hWnd: window, uID: 1, ..Default::default() };
    data.uFlags = NIF_ICON | NIF_MESSAGE | NIF_TIP;
    data.uCallbackMessage = WM_TRAY;
    unsafe {
        let instance = GetModuleHandleW(None).unwrap_or_default();
        let size = GetSystemMetrics(SM_CXSMICON);
        data.hIcon = LoadImageW(Some(instance.into()), PCWSTR(1 as _), IMAGE_ICON, size, size, LR_DEFAULTCOLOR).map(|icon| HICON(icon.0)).unwrap_or_default();
    }
    for (slot, unit) in data.szTip.iter_mut().zip("Manifold".encode_utf16()) {
        *slot = unit;
    }
    data
}

fn sync_icon(window: HWND, again: bool) {
    let wanted = WANTED.load(Ordering::Relaxed);
    let shown = SHOWN.load(Ordering::Relaxed);
    unsafe {
        if wanted && (!shown || again) {
            SHOWN.store(Shell_NotifyIconW(NIM_ADD, &icon_data(window)).as_bool(), Ordering::Relaxed);
        } else if !wanted && shown {
            let _ = Shell_NotifyIconW(NIM_DELETE, &icon_data(window));
            SHOWN.store(false, Ordering::Relaxed);
        }
    }
}

fn menu(window: HWND) {
    let Some(actions) = ACTIONS.get() else { return };
    unsafe {
        let Ok(popup) = CreatePopupMenu() else { return };
        let pause = if PAUSED.load(Ordering::Relaxed) { w!("Resume sharing") } else { w!("Stop all sharing") };
        let _ = AppendMenuW(popup, MF_STRING, SHOW_ITEM, w!("Show Manifold"));
        let _ = AppendMenuW(popup, MF_STRING, PAUSE_ITEM, pause);
        let _ = AppendMenuW(popup, MF_SEPARATOR, 0, PCWSTR::null());
        let _ = AppendMenuW(popup, MF_STRING, EXIT_ITEM, w!("Exit"));
        let mut at = POINT::default();
        let _ = GetCursorPos(&mut at);
        // The menu only closes on a click elsewhere while its owner is in front.
        let _ = SetForegroundWindow(window);
        let chosen = TrackPopupMenu(popup, TPM_RETURNCMD | TPM_RIGHTBUTTON, at.x, at.y, None, window, None).0 as usize;
        let _ = DestroyMenu(popup);
        match chosen {
            SHOW_ITEM => (actions.show)(),
            PAUSE_ITEM => (actions.pause)(!PAUSED.load(Ordering::Relaxed)),
            EXIT_ITEM => (actions.exit)(),
            _ => {}
        }
    }
}

extern "system" fn window_proc(window: HWND, message: u32, wparam: WPARAM, lparam: LPARAM) -> LRESULT {
    match message {
        WM_SYNC => sync_icon(window, false),
        WM_SHOW => {
            if let Some(actions) = ACTIONS.get() {
                (actions.show)();
            }
        }
        WM_TRAY => match lparam.0 as u32 & 0xFFFF {
            WM_LBUTTONUP => {
                if let Some(actions) = ACTIONS.get() {
                    (actions.show)();
                }
            }
            WM_RBUTTONUP | WM_CONTEXTMENU => menu(window),
            _ => {}
        },
        _ if RESTARTED.get() == Some(&message) => {
            // Explorer restarted and forgot every tray icon.
            SHOWN.store(false, Ordering::Relaxed);
            sync_icon(window, true);
        }
        _ => return unsafe { DefWindowProcW(window, message, wparam, lparam) },
    }
    LRESULT(0)
}

pub fn start(actions: Actions) {
    let _ = ACTIONS.set(actions);
    let _ = std::thread::Builder::new().name("tray".into()).spawn(|| unsafe {
        let _ = RESTARTED.set(RegisterWindowMessageW(w!("TaskbarCreated")));
        let instance = GetModuleHandleW(None).unwrap_or_default();
        let class = WNDCLASSW { lpfnWndProc: Some(window_proc), hInstance: instance.into(), lpszClassName: CLASS, ..Default::default() };
        RegisterClassW(&class);
        let Ok(window) = CreateWindowExW(WINDOW_EX_STYLE(0), CLASS, w!("Manifold"), WS_OVERLAPPED, 0, 0, 0, 0, None, None, Some(instance.into()), None) else {
            return;
        };
        WINDOW.store(window.0 as isize, Ordering::Release);
        sync_icon(window, false);
        let mut message = MSG::default();
        while GetMessageW(&mut message, None, 0, 0).as_bool() {
            let _ = TranslateMessage(&message);
            DispatchMessageW(&message);
        }
    });
}

pub fn sync(wanted: bool, paused: bool) {
    PAUSED.store(paused, Ordering::Relaxed);
    if WANTED.swap(wanted, Ordering::Relaxed) == wanted {
        return;
    }
    if let Some(window) = window() {
        unsafe {
            let _ = PostMessageW(Some(window), WM_SYNC, WPARAM(0), LPARAM(0));
        }
    }
}

// Removed before the process ends, since Windows keeps a dead process's icon until the mouse passes over it.
pub fn remove() {
    WANTED.store(false, Ordering::Relaxed);
    if let Some(window) = window() {
        unsafe { SendMessageW(window, WM_SYNC, None, None) };
    }
}

pub fn show_running_hub() -> bool {
    let Ok(window) = (unsafe { FindWindowW(CLASS, PCWSTR::null()) }) else { return false };
    unsafe { PostMessageW(Some(window), WM_SHOW, WPARAM(0), LPARAM(0)) }.is_ok()
}
