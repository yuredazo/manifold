use crate::remote::Rect;
use std::sync::OnceLock;
use x11rb::connection::Connection;
use x11rb::protocol::composite::ConnectionExt as _;
use x11rb::protocol::randr::ConnectionExt as _;
use x11rb::protocol::shm::ConnectionExt as _;
use x11rb::protocol::xfixes::ConnectionExt as _;
use x11rb::protocol::xproto::{AtomEnum, ConnectionExt as _, MapState, Window};
use x11rb::protocol::xtest::ConnectionExt as _;
use x11rb::rust_connection::RustConnection;

// Window ids are 32 bits, so a display's handle carries this tag above them, as on Windows.
pub const MONITOR_TAG: i64 = 1 << 62;

x11rb::atom_manager! {
    pub Atoms: AtomsCookie {
        _NET_CLIENT_LIST,
        _NET_ACTIVE_WINDOW,
        _NET_WM_NAME,
        _NET_WM_PID,
        _NET_WM_WINDOW_TYPE,
        _NET_WM_WINDOW_TYPE_DOCK,
        _NET_WM_WINDOW_TYPE_DESKTOP,
        UTF8_STRING,
    }
}

pub struct Desktop {
    pub connection: RustConnection,
    pub root: Window,
    pub atoms: Atoms,
}

pub struct Monitor {
    pub handle: i64,
    pub name: String,
    pub rect: Rect,
    pub primary: bool,
}

pub struct WindowInfo {
    pub id: Window,
    pub title: String,
    pub process: String,
    pub class: String,
    pub width: i32,
    pub height: i32,
}

static SHARED: OnceLock<Option<Desktop>> = OnceLock::new();

// One connection for listing and input. Captures open their own, so a frame being copied never delays a click.
pub fn shared() -> Option<&'static Desktop> {
    SHARED.get_or_init(Desktop::connect).as_ref()
}

impl Desktop {
    pub fn connect() -> Option<Self> {
        let (connection, screen) = x11rb::connect(None).ok()?;
        let root = connection.setup().roots.get(screen)?.root;
        let atoms = Atoms::new(&connection).ok()?.reply().ok()?;
        // XFixes refuses requests until the client has named its version.
        let _ = connection.xfixes_query_version(5, 0).map(|cookie| cookie.reply());
        let _ = connection.composite_query_version(0, 4).map(|cookie| cookie.reply());
        let _ = connection.randr_query_version(1, 5).map(|cookie| cookie.reply());
        let _ = connection.shm_query_version().map(|cookie| cookie.reply());
        let _ = connection.xtest_get_version(2, 2).map(|cookie| cookie.reply());
        Some(Self { connection, root, atoms })
    }

    // Errors of requests that have no reply arrive as events, and this connection never reads events otherwise.
    pub fn discard_events(&self) {
        while let Ok(Some(_)) = self.connection.poll_for_event() {}
    }

    pub fn monitors(&self) -> Vec<Monitor> {
        let reply = self.connection.randr_get_monitors(self.root, true).ok().and_then(|cookie| cookie.reply().ok());
        let found: Vec<Monitor> = reply
            .map(|reply| reply.monitors)
            .unwrap_or_default()
            .iter()
            .map(|monitor| {
                let name = self.atom_name(monitor.name).unwrap_or_else(|| "Display".into());
                let (left, top) = (monitor.x as i32, monitor.y as i32);
                Monitor {
                    handle: MONITOR_TAG | monitor.name as i64,
                    name,
                    rect: Rect { left, top, right: left + monitor.width as i32, bottom: top + monitor.height as i32 },
                    primary: monitor.primary,
                }
            })
            .collect();
        if !found.is_empty() {
            return found;
        }
        // A graphics card with no screen plugged in, as in a cloud machine, has no monitors but still a screen to draw on.
        let Some(screen) = self.connection.get_geometry(self.root).ok().and_then(|cookie| cookie.reply().ok()) else { return found };
        let rect = Rect { left: 0, top: 0, right: screen.width as i32, bottom: screen.height as i32 };
        vec![Monitor { handle: MONITOR_TAG, name: "Screen".into(), rect, primary: true }]
    }

    pub fn monitor(&self, handle: i64) -> Option<Rect> {
        self.monitors().into_iter().find(|monitor| monitor.handle == handle).map(|monitor| monitor.rect)
    }

    fn atom_name(&self, atom: u32) -> Option<String> {
        let reply = self.connection.get_atom_name(atom).ok()?.reply().ok()?;
        Some(String::from_utf8_lossy(&reply.name).into_owned())
    }

    fn property(&self, window: Window, name: u32, kind: impl Into<u32>) -> Option<Vec<u8>> {
        let reply = self.connection.get_property(false, window, name, kind, 0, 1 << 16).ok()?.reply().ok()?;
        (reply.format != 0).then_some(reply.value)
    }

    fn property32(&self, window: Window, name: u32, kind: impl Into<u32>) -> Vec<u32> {
        let reply = self.connection.get_property(false, window, name, kind, 0, 1 << 16).ok().and_then(|cookie| cookie.reply().ok());
        reply.and_then(|reply| reply.value32().map(Iterator::collect)).unwrap_or_default()
    }

    pub fn active_window(&self) -> Option<Window> {
        self.property32(self.root, self.atoms._NET_ACTIVE_WINDOW, AtomEnum::WINDOW).first().copied().filter(|window| *window != 0)
    }

    pub fn window_exists(&self, window: Window) -> bool {
        self.connection.get_window_attributes(window).ok().is_some_and(|cookie| cookie.reply().is_ok())
    }

    pub fn window_viewable(&self, window: Window) -> bool {
        let attributes = self.connection.get_window_attributes(window).ok().and_then(|cookie| cookie.reply().ok());
        attributes.is_some_and(|attributes| attributes.map_state == MapState::VIEWABLE)
    }

    pub fn window_rect(&self, window: Window) -> Option<Rect> {
        let geometry = self.connection.get_geometry(window).ok()?.reply().ok()?;
        let origin = self.connection.translate_coordinates(window, self.root, 0, 0).ok()?.reply().ok()?;
        let (left, top) = (origin.dst_x as i32, origin.dst_y as i32);
        Some(Rect { left, top, right: left + geometry.width as i32, bottom: top + geometry.height as i32 })
    }

    fn title(&self, window: Window) -> String {
        let text = self.property(window, self.atoms._NET_WM_NAME, self.atoms.UTF8_STRING).or_else(|| self.property(window, AtomEnum::WM_NAME.into(), AtomEnum::STRING));
        text.map(|bytes| String::from_utf8_lossy(&bytes).trim().to_string()).unwrap_or_default()
    }

    fn class(&self, window: Window) -> String {
        let value = self.property(window, AtomEnum::WM_CLASS.into(), AtomEnum::STRING).unwrap_or_default();
        value.split(|byte| *byte == 0).nth(1).map(|class| String::from_utf8_lossy(class).into_owned()).unwrap_or_default()
    }

    fn process(&self, window: Window) -> Option<(u32, String)> {
        let id = *self.property32(window, self.atoms._NET_WM_PID, AtomEnum::CARDINAL).first()?;
        let name = std::fs::read_to_string(format!("/proc/{id}/comm")).map(|name| name.trim().to_string()).unwrap_or_default();
        Some((id, name))
    }

    pub fn windows(&self) -> Vec<WindowInfo> {
        let own = std::process::id();
        let skipped = [self.atoms._NET_WM_WINDOW_TYPE_DOCK, self.atoms._NET_WM_WINDOW_TYPE_DESKTOP];
        self.property32(self.root, self.atoms._NET_CLIENT_LIST, AtomEnum::WINDOW)
            .into_iter()
            .filter_map(|id| {
                let (process_id, process) = self.process(id).unwrap_or_default();
                if process_id == own || self.property32(id, self.atoms._NET_WM_WINDOW_TYPE, AtomEnum::ATOM).iter().any(|kind| skipped.contains(kind)) {
                    return None;
                }
                let title = self.title(id);
                let geometry = self.connection.get_geometry(id).ok()?.reply().ok()?;
                if title.is_empty() || geometry.width < 16 || geometry.height < 16 {
                    return None;
                }
                Some(WindowInfo { id, title, process, class: self.class(id), width: geometry.width as i32, height: geometry.height as i32 })
            })
            .collect()
    }
}
