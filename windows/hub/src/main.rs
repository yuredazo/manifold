#![windows_subsystem = "windows"]

mod capture;
mod discovery;
mod hub;
mod player;
mod sharing;
mod store;
mod system;
mod tray;
mod ui;
mod update;
mod watching;

use hub::{Command, Hub, Input};
use slint::ComponentHandle;
use slint::winit_030::WinitWindowAccessor;
use slint::winit_030::winit::platform::windows::{CornerPreference, WindowAttributesExtWindows};
use slint::winit_030::winit::raw_window_handle::{HasWindowHandle, RawWindowHandle};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{Sender, channel};
use windows::Win32::Foundation::{ERROR_ALREADY_EXISTS, GetLastError, HWND, LPARAM, WPARAM};
use windows::Win32::System::LibraryLoader::GetModuleHandleW;
use windows::Win32::System::Threading::CreateMutexW;
use windows::Win32::UI::WindowsAndMessaging::{ICON_BIG, ICON_SMALL, LoadIconW, SendMessageW, SetForegroundWindow, WM_SETICON};
use windows::core::{PCWSTR, w};

slint::include_modules!();

// A hub started hidden has no window to bring back but the tray icon, so it always has one.
static STARTED_HIDDEN: AtomicBool = AtomicBool::new(false);

fn native_window(app: &AppWindow) -> Option<HWND> {
    app.window().with_winit_window(|window| match window.window_handle().ok()?.as_raw() {
        RawWindowHandle::Win32(handle) => Some(HWND(handle.hwnd.get() as *mut _)),
        _ => None,
    })?
}

fn bring_up(app: &AppWindow) {
    let _ = app.show();
    if let Some(window) = native_window(app) {
        unsafe {
            let instance = GetModuleHandleW(None).unwrap_or_default();
            if let Ok(icon) = LoadIconW(Some(instance.into()), PCWSTR(1 as _)) {
                SendMessageW(window, WM_SETICON, Some(WPARAM(ICON_BIG as usize)), Some(LPARAM(icon.0 as isize)));
                SendMessageW(window, WM_SETICON, Some(WPARAM(ICON_SMALL as usize)), Some(LPARAM(icon.0 as isize)));
            }
            let _ = SetForegroundWindow(window);
        }
    }
}

fn quit(out: &Sender<Input>) {
    tray::remove();
    let _ = out.send(Input::Command(Command::Quit));
    let _ = slint::quit_event_loop();
}

fn close_window(app: &AppWindow, out: &Sender<Input>) {
    if ui::close_to_tray() || STARTED_HIDDEN.load(Ordering::Relaxed) {
        let _ = app.hide();
    } else {
        quit(out);
    }
}

fn main() -> Result<(), slint::PlatformError> {
    // Two hubs would fight over the UDP port and the identity file, so a second launch only brings up the first.
    // The name is the Flutter hub's too, so the two never run at once. The handle stays open for the life of the process.
    let _single = unsafe { CreateMutexW(None, true, w!("Local\\dev.mkzk.manifold.hub")) };
    if unsafe { GetLastError() } == ERROR_ALREADY_EXISTS {
        tray::show_running_hub();
        return Ok(());
    }
    update::clean_up();
    update::remove_flutter_leftovers();
    let hidden = std::env::args().any(|arg| arg == system::HIDDEN_FLAG);
    STARTED_HIDDEN.store(hidden, Ordering::Relaxed);

    // Without a frame Windows drops the shadow and the rounded corners unless asked for them.
    slint::BackendSelector::new()
        .with_winit_window_attributes_hook(|attributes| attributes.with_undecorated_shadow(true).with_corner_preference(CornerPreference::Round))
        .select()?;
    let app = AppWindow::new()?;

    let (out, inputs) = channel();
    let weak = app.as_weak();
    let hub = Hub::new(store::Storage::open(), out.clone(), inputs, move |snapshot| {
        let _ = weak.upgrade_in_event_loop(move |app| {
            if snapshot.restart {
                tray::remove();
                let _ = slint::quit_event_loop();
                return;
            }
            tray::sync(snapshot.close_to_tray || STARTED_HIDDEN.load(Ordering::Relaxed), snapshot.paused);
            ui::apply(&app, snapshot);
        });
    });
    let network = std::thread::Builder::new().name("network".into()).spawn(move || hub.run()).expect("network thread");
    ui::bind(&app, out.clone());

    let weak = app.as_weak();
    app.on_drag(move || {
        if let Some(app) = weak.upgrade() {
            app.window().with_winit_window(|window| window.drag_window());
        }
    });
    let weak = app.as_weak();
    app.on_minimize(move || {
        if let Some(app) = weak.upgrade() {
            app.window().set_minimized(true);
        }
    });
    let (weak, sender) = (app.as_weak(), out.clone());
    app.on_close_window(move || {
        if let Some(app) = weak.upgrade() {
            close_window(&app, &sender);
        }
    });
    let (weak, sender) = (app.as_weak(), out.clone());
    app.window().on_close_requested(move || {
        if let Some(app) = weak.upgrade() {
            close_window(&app, &sender);
        }
        slint::CloseRequestResponse::KeepWindowShown
    });

    let (show, exit, pause) = (app.as_weak(), out.clone(), out.clone());
    tray::start(tray::Actions {
        show: Box::new(move || {
            let _ = show.upgrade_in_event_loop(|app| bring_up(&app));
        }),
        pause: Box::new(move |paused| {
            let _ = pause.send(Input::Command(Command::Pause(paused)));
        }),
        exit: Box::new(move || {
            let exit = exit.clone();
            let _ = slint::invoke_from_event_loop(move || quit(&exit));
        }),
    });
    tray::sync(hidden, false);

    if !hidden {
        bring_up(&app);
    }
    slint::run_event_loop_until_quit()?;
    let _ = out.send(Input::Command(Command::Quit));
    let _ = network.join();
    Ok(())
}
