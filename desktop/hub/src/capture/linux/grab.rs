use crate::remote::Rect;
use crate::system::x11::Desktop;
use x11rb::connection::Connection;
use x11rb::protocol::composite::{ConnectionExt as _, Redirect};
use x11rb::protocol::shm::ConnectionExt as _;
use x11rb::protocol::xfixes::ConnectionExt as _;
use x11rb::protocol::xproto::{ConnectionExt as _, ImageFormat, Window};

const MONITOR_CHECK_FRAMES: u32 = 60;

pub enum Source {
    Display(i64),
    Window(Window),
}

pub struct Image<'a> {
    pub width: u32,
    pub height: u32,
    pub stride: usize,
    pub pixels: &'a [u8],
}

pub enum Grab<'a> {
    Fresh(Image<'a>),
    Unchanged,
    Gone,
}

struct Segment {
    id: u32,
    memory: *mut u8,
    size: usize,
}

impl Segment {
    fn new(desktop: &Desktop, size: usize) -> Option<Self> {
        let shmid = unsafe { libc::shmget(libc::IPC_PRIVATE, size, libc::IPC_CREAT | 0o600) };
        if shmid < 0 {
            return None;
        }
        let memory = unsafe { libc::shmat(shmid, std::ptr::null(), 0) };
        let id = desktop.connection.generate_id().ok();
        let attached = (memory as isize != -1).then_some(id).flatten().and_then(|id| desktop.connection.shm_attach(id, shmid as u32, false).ok()?.check().ok());
        // Marked for removal at once, so the kernel frees it when both sides have detached, even after a crash.
        unsafe { libc::shmctl(shmid, libc::IPC_RMID, std::ptr::null_mut()) };
        if memory as isize == -1 {
            return None;
        }
        if attached.is_none() {
            unsafe { libc::shmdt(memory) };
            return None;
        }
        Some(Self { id: id?, memory: memory as *mut u8, size })
    }

    fn release(&self, desktop: &Desktop) {
        let _ = desktop.connection.shm_detach(self.id);
        let _ = desktop.connection.flush();
        unsafe { libc::shmdt(self.memory as *const _) };
    }
}

pub struct Grabber {
    desktop: Desktop,
    source: Source,
    cursor: bool,
    segment: Option<Segment>,
    monitor: Option<Rect>,
    frames: u32,
}

impl Grabber {
    pub fn open(source: Source, cursor: bool) -> Option<Self> {
        let desktop = Desktop::connect()?;
        let monitor = match source {
            Source::Display(handle) => Some(desktop.monitor(handle)?),
            Source::Window(window) => {
                // Asks the server to keep drawing the window off screen too, so its picture is whole when covered.
                desktop.connection.composite_redirect_window(window, Redirect::AUTOMATIC).ok()?;
                None
            }
        };
        Some(Self { desktop, source, cursor, segment: None, monitor, frames: 0 })
    }

    fn segment(&mut self, size: usize) -> Option<&Segment> {
        if self.segment.as_ref().is_none_or(|segment| segment.size < size) {
            if let Some(old) = self.segment.take() {
                old.release(&self.desktop);
            }
            self.segment = Segment::new(&self.desktop, size);
        }
        self.segment.as_ref()
    }

    pub fn grab(&mut self) -> Grab<'_> {
        self.frames = self.frames.wrapping_add(1);
        self.desktop.discard_events();
        let (drawable, area, origin) = match self.source {
            Source::Display(handle) => {
                if self.frames.is_multiple_of(MONITOR_CHECK_FRAMES) {
                    self.monitor = self.desktop.monitor(handle);
                }
                let Some(rect) = self.monitor else { return Grab::Gone };
                (self.desktop.root, rect, (rect.left, rect.top))
            }
            Source::Window(window) => {
                if !self.desktop.window_exists(window) {
                    return Grab::Gone;
                }
                let Some(rect) = self.desktop.window_rect(window) else { return Grab::Unchanged };
                if !self.desktop.window_viewable(window) {
                    return Grab::Unchanged;
                }
                // Named again each frame: the server swaps the window's pixmap whenever it is resized or mapped again.
                let Ok(pixmap) = self.desktop.connection.generate_id() else { return Grab::Unchanged };
                if self.desktop.connection.composite_name_window_pixmap(window, pixmap).ok().and_then(|cookie| cookie.check().ok()).is_none() {
                    return Grab::Unchanged;
                }
                (pixmap, Rect { left: 0, top: 0, right: rect.right - rect.left, bottom: rect.bottom - rect.top }, (rect.left, rect.top))
            }
        };
        let (width, height) = ((area.right - area.left).max(0) as u32, (area.bottom - area.top).max(0) as u32);
        let size = width as usize * height as usize * 4;
        let copied = width >= 2 && height >= 2 && self.copy(drawable, area, size);
        if matches!(self.source, Source::Window(_)) {
            let _ = self.desktop.connection.free_pixmap(drawable);
        }
        if !copied {
            return Grab::Unchanged;
        }
        let Some(segment) = &self.segment else { return Grab::Unchanged };
        let pixels = unsafe { std::slice::from_raw_parts_mut(segment.memory, size) };
        if self.cursor {
            draw_cursor(&self.desktop, pixels, width, height, origin);
        }
        Grab::Fresh(Image { width, height, stride: width as usize * 4, pixels })
    }

    fn copy(&mut self, drawable: u32, area: Rect, size: usize) -> bool {
        let Some(segment) = self.segment(size).map(|segment| segment.id) else { return false };
        let (width, height) = ((area.right - area.left) as u16, (area.bottom - area.top) as u16);
        let reply = self
            .desktop
            .connection
            .shm_get_image(drawable, area.left as i16, area.top as i16, width, height, !0, ImageFormat::Z_PIXMAP.into(), segment, 0)
            .ok()
            .and_then(|cookie| cookie.reply().ok());
        // Depths 24 and 32 both come as four bytes a pixel, blue first.
        reply.is_some_and(|reply| matches!(reply.depth, 24 | 32) && reply.size as usize >= size)
    }
}

impl Drop for Grabber {
    fn drop(&mut self) {
        if let Some(segment) = self.segment.take() {
            segment.release(&self.desktop);
        }
    }
}

// The server leaves the pointer out of copied pictures. Its pixels are premultiplied ARGB.
fn draw_cursor(desktop: &Desktop, pixels: &mut [u8], width: u32, height: u32, origin: (i32, i32)) {
    let Some(image) = desktop.connection.xfixes_get_cursor_image().ok().and_then(|cookie| cookie.reply().ok()) else { return };
    let left = image.x as i32 - image.xhot as i32 - origin.0;
    let top = image.y as i32 - image.yhot as i32 - origin.1;
    for row in 0..image.height as i32 {
        let y = top + row;
        if y < 0 || y >= height as i32 {
            continue;
        }
        for column in 0..image.width as i32 {
            let x = left + column;
            if x < 0 || x >= width as i32 {
                continue;
            }
            let color = image.cursor_image[(row * image.width as i32 + column) as usize];
            let alpha = color >> 24;
            if alpha == 0 {
                continue;
            }
            let at = (y as usize * width as usize + x as usize) * 4;
            for (channel, shift) in [(0, 0), (1, 8), (2, 16)] {
                let over = (color >> shift) & 0xFF;
                let under = pixels[at + channel] as u32;
                pixels[at + channel] = (over + under * (255 - alpha) / 255).min(255) as u8;
            }
        }
    }
}
