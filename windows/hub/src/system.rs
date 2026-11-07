use std::ffi::c_void;
use std::net::Ipv4Addr;
use windows::Win32::Foundation::{GlobalFree, HANDLE, HGLOBAL, HWND};
use windows::Win32::NetworkManagement::IpHelper::{
    GAA_FLAG_SKIP_ANYCAST, GAA_FLAG_SKIP_DNS_SERVER, GAA_FLAG_SKIP_MULTICAST, GetAdaptersAddresses, IP_ADAPTER_ADDRESSES_LH,
};
use windows::Win32::NetworkManagement::Ndis::IfOperStatusUp;
use windows::Win32::Networking::WinHttp::*;
use windows::Win32::Networking::WinSock::{AF_INET, SOCKADDR_IN};
use windows::Win32::System::DataExchange::{CloseClipboard, EmptyClipboard, OpenClipboard, SetClipboardData};
use windows::Win32::System::Memory::{GMEM_MOVEABLE, GlobalAlloc, GlobalLock, GlobalUnlock};
use windows::Win32::System::Registry::{
    HKEY, HKEY_CURRENT_USER, KEY_QUERY_VALUE, KEY_SET_VALUE, REG_SZ, RegCloseKey, RegDeleteValueW, RegOpenKeyExW, RegQueryValueExW, RegSetValueExW,
};
use windows::Win32::UI::Shell::ShellExecuteW;
use windows::Win32::UI::WindowsAndMessaging::{IsWindow, SW_SHOWNORMAL};
use windows::core::{HSTRING, PCWSTR, w};

const CF_UNICODETEXT: u32 = 13;

// Every IPv4 address of an adapter that is up, without loopback, so a VPN such as Tailscale is listed too.
pub fn local_addresses() -> Vec<Ipv4Addr> {
    let mut size = 16 * 1024u32;
    for _ in 0..3 {
        let mut buffer = vec![0u8; size as usize];
        let first = buffer.as_mut_ptr() as *mut IP_ADAPTER_ADDRESSES_LH;
        let flags = GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST | GAA_FLAG_SKIP_DNS_SERVER;
        let status = unsafe { GetAdaptersAddresses(AF_INET.0 as u32, flags, None, Some(first), &mut size) };
        if status == 111 {
            continue;
        }
        if status != 0 {
            return vec![];
        }
        let mut found = Vec::new();
        let mut adapter = first;
        while !adapter.is_null() {
            let current = unsafe { &*adapter };
            if current.OperStatus == IfOperStatusUp {
                let mut unicast = current.FirstUnicastAddress;
                while !unicast.is_null() {
                    let entry = unsafe { &*unicast };
                    let socket = entry.Address.lpSockaddr;
                    if !socket.is_null() && unsafe { (*socket).sa_family } == AF_INET {
                        let address = unsafe { &*(socket as *const SOCKADDR_IN) };
                        let ip = Ipv4Addr::from(u32::from_be(unsafe { address.sin_addr.S_un.S_addr }));
                        if !ip.is_loopback() && !ip.is_link_local() && !found.contains(&ip) {
                            found.push(ip);
                        }
                    }
                    unicast = entry.Next;
                }
            }
            adapter = current.Next;
        }
        return found;
    }
    vec![]
}

pub fn is_private(ip: Ipv4Addr) -> bool {
    ip.is_private()
}

pub fn window_alive(handle: i64) -> bool {
    unsafe { IsWindow(Some(HWND(handle as isize as *mut c_void))) }.as_bool()
}

pub fn open_url(url: &str) {
    unsafe { ShellExecuteW(None, w!("open"), &HSTRING::from(url), PCWSTR::null(), PCWSTR::null(), SW_SHOWNORMAL) };
}

pub fn copy_text(text: &str) {
    let units: Vec<u16> = text.encode_utf16().chain(std::iter::once(0)).collect();
    unsafe {
        if OpenClipboard(None).is_err() {
            return;
        }
        let _ = EmptyClipboard();
        if let Ok(memory) = GlobalAlloc(GMEM_MOVEABLE, units.len() * 2) {
            let target = GlobalLock(memory) as *mut u16;
            if !target.is_null() {
                std::ptr::copy_nonoverlapping(units.as_ptr(), target, units.len());
                let _ = GlobalUnlock(memory);
                if SetClipboardData(CF_UNICODETEXT, Some(HANDLE(memory.0))).is_err() {
                    let _ = GlobalFree(Some(HGLOBAL(memory.0)));
                }
            }
        }
        let _ = CloseClipboard();
    }
}

const RUN_KEY: PCWSTR = w!("Software\\Microsoft\\Windows\\CurrentVersion\\Run");
const RUN_VALUE: PCWSTR = w!("Manifold");
pub const HIDDEN_FLAG: &str = "--hidden";

fn autostart_command() -> String {
    let exe = std::env::current_exe().map(|path| path.display().to_string()).unwrap_or_default();
    format!("\"{exe}\" {HIDDEN_FLAG}")
}

fn run_key(access: windows::Win32::System::Registry::REG_SAM_FLAGS) -> Option<HKEY> {
    let mut key = HKEY::default();
    unsafe { RegOpenKeyExW(HKEY_CURRENT_USER, RUN_KEY, None, access, &mut key) }.ok().ok()?;
    Some(key)
}

// Only true while the entry starts this very copy, so a hub that was moved offers to start itself again.
pub fn autostart_enabled() -> bool {
    let Some(key) = run_key(KEY_QUERY_VALUE) else { return false };
    let mut buffer = [0u16; 1024];
    let mut size = (buffer.len() * 2) as u32;
    let read = unsafe { RegQueryValueExW(key, RUN_VALUE, None, None, Some(buffer.as_mut_ptr().cast()), Some(&mut size)) };
    unsafe {
        let _ = RegCloseKey(key);
    }
    if read.is_err() {
        return false;
    }
    let text = String::from_utf16_lossy(&buffer[..(size as usize / 2)]);
    text.trim_end_matches('\0') == autostart_command()
}

pub fn set_autostart(on: bool) {
    let Some(key) = run_key(KEY_SET_VALUE) else { return };
    unsafe {
        if on {
            let units: Vec<u16> = autostart_command().encode_utf16().chain(std::iter::once(0)).collect();
            let bytes = std::slice::from_raw_parts(units.as_ptr() as *const u8, units.len() * 2);
            let _ = RegSetValueExW(key, RUN_VALUE, None, REG_SZ, Some(bytes));
        } else {
            let _ = RegDeleteValueW(key, RUN_VALUE);
        }
        let _ = RegCloseKey(key);
    }
}

struct Internet(*mut c_void);

impl Drop for Internet {
    fn drop(&mut self) {
        if !self.0.is_null() {
            unsafe {
                let _ = WinHttpCloseHandle(self.0);
            }
        }
    }
}

pub fn https_get(host: &str, path: &str, max_bytes: usize) -> Result<Vec<u8>, String> {
    https_fetch(host, path, max_bytes, |_| {})
}

pub fn https_get_url(url: &str, max_bytes: usize, progress: impl FnMut(Option<f32>)) -> Result<Vec<u8>, String> {
    let rest = url.strip_prefix("https://").ok_or("not an https address")?;
    let (host, path) = rest.split_at(rest.find('/').unwrap_or(rest.len()));
    https_fetch(host, if path.is_empty() { "/" } else { path }, max_bytes, progress)
}

// A GET over HTTPS through WinHTTP, which Windows already has, so the hub carries no TLS library of its own.
// WinHTTP follows GitHub's redirect from a release asset to its download host by itself.
fn https_fetch(host: &str, path: &str, max_bytes: usize, mut progress: impl FnMut(Option<f32>)) -> Result<Vec<u8>, String> {
    unsafe {
        let session = Internet(WinHttpOpen(w!("Manifold"), WINHTTP_ACCESS_TYPE_AUTOMATIC_PROXY, PCWSTR::null(), PCWSTR::null(), 0));
        if session.0.is_null() {
            return Err("Windows could not start a web request".into());
        }
        let _ = WinHttpSetTimeouts(session.0, 10_000, 10_000, 20_000, 20_000);
        let connection = Internet(WinHttpConnect(session.0, &HSTRING::from(host), INTERNET_DEFAULT_HTTPS_PORT, 0));
        if connection.0.is_null() {
            return Err(format!("could not reach {host}"));
        }
        let accept = [w!("*/*"), PCWSTR::null()];
        let request = Internet(WinHttpOpenRequest(
            connection.0,
            w!("GET"),
            &HSTRING::from(path),
            PCWSTR::null(),
            PCWSTR::null(),
            accept.as_ptr(),
            WINHTTP_FLAG_SECURE,
        ));
        if request.0.is_null() {
            return Err(format!("could not reach {host}"));
        }
        WinHttpSendRequest(request.0, None, None, 0, 0, 0).map_err(|_| format!("could not reach {host}"))?;
        WinHttpReceiveResponse(request.0, std::ptr::null_mut()).map_err(|_| format!("{host} did not answer"))?;
        let mut status = 0u32;
        let mut size = 4u32;
        WinHttpQueryHeaders(
            request.0,
            WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER,
            PCWSTR::null(),
            Some(&mut status as *mut u32 as *mut c_void),
            &mut size,
            std::ptr::null_mut(),
        )
        .map_err(|_| format!("{host} sent no status"))?;
        if status != 200 {
            return Err(format!("{host} answered {status}"));
        }
        let mut total = 0u32;
        let mut size = 4u32;
        let known = WinHttpQueryHeaders(
            request.0,
            WINHTTP_QUERY_CONTENT_LENGTH | WINHTTP_QUERY_FLAG_NUMBER,
            PCWSTR::null(),
            Some(&mut total as *mut u32 as *mut c_void),
            &mut size,
            std::ptr::null_mut(),
        )
        .is_ok()
            && total > 0;
        if known && total as usize > max_bytes {
            return Err(format!("{host} sent more than expected"));
        }
        let mut body = Vec::new();
        let mut chunk = vec![0u8; 16 * 1024];
        loop {
            let mut read = 0u32;
            WinHttpReadData(request.0, chunk.as_mut_ptr().cast(), chunk.len() as u32, &mut read).map_err(|_| format!("{host} stopped answering"))?;
            if read == 0 {
                break;
            }
            body.extend_from_slice(&chunk[..read as usize]);
            if body.len() > max_bytes {
                return Err(format!("{host} sent more than expected"));
            }
            progress(known.then(|| body.len() as f32 / total as f32));
        }
        Ok(body)
    }
}
