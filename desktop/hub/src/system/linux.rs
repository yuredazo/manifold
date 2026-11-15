use super::{HIDDEN_FLAG, LocalTime};
use std::io::Write;
use std::net::Ipv4Addr;
use std::os::linux::net::SocketAddrExt;
use std::os::unix::net::{SocketAddr, UnixListener, UnixStream};
use std::path::PathBuf;
use std::process::{Command, Stdio};

pub fn local_time() -> LocalTime {
    let mut now = libc::timespec { tv_sec: 0, tv_nsec: 0 };
    let mut parts: libc::tm = unsafe { std::mem::zeroed() };
    unsafe {
        libc::clock_gettime(libc::CLOCK_REALTIME, &mut now);
        libc::localtime_r(&now.tv_sec, &mut parts);
    }
    LocalTime {
        year: (parts.tm_year + 1900) as u32,
        month: (parts.tm_mon + 1) as u32,
        day: parts.tm_mday as u32,
        hour: parts.tm_hour as u32,
        minute: parts.tm_min as u32,
        second: parts.tm_sec as u32,
        millisecond: (now.tv_nsec / 1_000_000) as u32,
    }
}

pub fn local_addresses() -> Vec<Ipv4Addr> {
    let mut found = Vec::new();
    let mut list: *mut libc::ifaddrs = std::ptr::null_mut();
    if unsafe { libc::getifaddrs(&mut list) } != 0 {
        return found;
    }
    let mut entry = list;
    while !entry.is_null() {
        let current = unsafe { &*entry };
        let up = current.ifa_flags & libc::IFF_UP as u32 != 0;
        if up && !current.ifa_addr.is_null() && unsafe { (*current.ifa_addr).sa_family } as i32 == libc::AF_INET {
            let address = unsafe { &*(current.ifa_addr as *const libc::sockaddr_in) };
            let ip = Ipv4Addr::from(u32::from_be(address.sin_addr.s_addr));
            if !ip.is_loopback() && !ip.is_link_local() && !found.contains(&ip) {
                found.push(ip);
            }
        }
        entry = current.ifa_next;
    }
    unsafe { libc::freeifaddrs(list) };
    found
}

pub fn window_alive(handle: i64) -> bool {
    crate::x11::shared().is_some_and(|desktop| desktop.window_exists(handle as u32))
}

fn spawn_quietly(command: &mut Command) -> Option<std::process::Child> {
    command.stdin(Stdio::piped()).stdout(Stdio::null()).stderr(Stdio::null()).spawn().ok()
}

pub fn open_url(url: &str) {
    if let Some(mut child) = spawn_quietly(Command::new("xdg-open").arg(url)) {
        std::thread::spawn(move || child.wait());
    }
}

pub fn copy_text(text: &str) {
    let helpers: [&[&str]; 3] = [&["wl-copy"], &["xclip", "-selection", "clipboard"], &["xsel", "--clipboard", "--input"]];
    for helper in helpers {
        let Some(mut child) = spawn_quietly(Command::new(helper[0]).args(&helper[1..])) else { continue };
        if let Some(mut input) = child.stdin.take() {
            let _ = input.write_all(text.as_bytes());
        }
        std::thread::spawn(move || child.wait());
        return;
    }
}

pub fn is_elevated() -> bool {
    false
}

pub fn start_elevated(_hidden: bool) -> bool {
    false
}

fn autostart_file() -> Option<PathBuf> {
    let config = std::env::var_os("XDG_CONFIG_HOME").map(PathBuf::from).or_else(|| std::env::var_os("HOME").map(|home| PathBuf::from(home).join(".config")))?;
    Some(config.join("autostart").join("manifold.desktop"))
}

// The file's string escapes are undone before Exec's own quoting, so a backslash for Exec is written twice; a backslash
// in the path itself becomes four. A literal percent sign is doubled, since Exec reads %x as a field code.
fn autostart_entry() -> Option<String> {
    let exe = std::env::current_exe().ok()?.display().to_string();
    let quoted: String = exe
        .chars()
        .map(|c| match c {
            '"' | '`' | '$' => format!("\\\\{c}"),
            '\\' => "\\\\\\\\".into(),
            '%' => "%%".into(),
            c => c.into(),
        })
        .collect();
    Some(format!("[Desktop Entry]\nType=Application\nName=Manifold\nExec=\"{quoted}\" {HIDDEN_FLAG}\nX-GNOME-Autostart-enabled=true\n"))
}

pub fn autostart_enabled(_as_admin: bool) -> bool {
    let (Some(file), Some(entry)) = (autostart_file(), autostart_entry()) else { return false };
    std::fs::read_to_string(file).is_ok_and(|text| text == entry)
}

pub fn set_autostart(on: bool, _as_admin: bool) {
    let Some(file) = autostart_file() else { return };
    if !on {
        let _ = std::fs::remove_file(file);
        return;
    }
    if let (Some(folder), Some(entry)) = (file.parent(), autostart_entry()) {
        let _ = std::fs::create_dir_all(folder);
        let _ = std::fs::write(&file, entry);
    }
}

// curl is on every desktop distribution, so the hub carries no TLS library of its own.
pub fn https_get(host: &str, path: &str, max_bytes: usize) -> Result<Vec<u8>, String> {
    let output = Command::new("curl")
        .args(["-sS", "-L", "--proto", "=https", "--proto-redir", "=https", "--max-time", "20", "-A", "Manifold", "-w", "%{stderr}%{http_code}", "--max-filesize"])
        .arg(max_bytes.to_string())
        .arg(format!("https://{host}{path}"))
        .stdin(Stdio::null())
        .output()
        .map_err(|_| "curl is needed to reach the internet".to_string())?;
    let stderr = String::from_utf8_lossy(&output.stderr);
    match stderr.get(stderr.len().saturating_sub(3)..).unwrap_or_default() {
        "200" if output.stdout.len() <= max_bytes => Ok(output.stdout),
        "200" => Err(format!("{host} sent more than expected")),
        "000" => Err(format!("could not reach {host}")),
        code => Err(format!("{host} answered {code}")),
    }
}

// Abstract sockets vanish with the process that holds them, so a crashed hub never leaves a stale lock behind.
fn instance_address() -> Option<SocketAddr> {
    let user = unsafe { libc::getuid() };
    SocketAddr::from_abstract_name(format!("dev.mkzk.manifold.hub.{user}")).ok()
}

pub fn claim_instance() -> Option<UnixListener> {
    UnixListener::bind_addr(&instance_address()?).ok()
}

pub fn show_running_hub() -> bool {
    instance_address().and_then(|address| UnixStream::connect_addr(&address).ok()).is_some()
}

pub fn on_show_requests(listener: UnixListener, show: impl Fn() + Send + 'static) {
    std::thread::spawn(move || {
        for _ in listener.incoming().flatten() {
            show();
        }
    });
}
