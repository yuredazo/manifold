use super::{HIDDEN_FLAG, LocalTime};
use std::ffi::c_void;
use std::net::Ipv4Addr;
use std::os::windows::process::CommandExt;
use std::process::Command;
use windows::Win32::Foundation::{CloseHandle, GlobalFree, HANDLE, HGLOBAL, HWND};
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
use windows::Win32::Security::{GetTokenInformation, TOKEN_ELEVATION, TOKEN_QUERY, TokenElevation};
use windows::Win32::System::Threading::{GetCurrentProcess, OpenProcess, OpenProcessToken, PROCESS_SYNCHRONIZE, WaitForSingleObject};
use windows::Win32::UI::Shell::ShellExecuteW;
use windows::Win32::UI::WindowsAndMessaging::{IsWindow, SW_SHOWNORMAL};
use windows::core::{HSTRING, PCWSTR, w};

const CF_UNICODETEXT: u32 = 13;
const CREATE_NO_WINDOW: u32 = 0x0800_0000;
pub const WAIT_FLAG: &str = "--wait-for";
const TASK_NAME: &str = "Manifold";

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

pub fn local_time() -> LocalTime {
    let time = unsafe { windows::Win32::System::SystemInformation::GetLocalTime() };
    LocalTime {
        year: time.wYear as u32,
        month: time.wMonth as u32,
        day: time.wDay as u32,
        hour: time.wHour as u32,
        minute: time.wMinute as u32,
        second: time.wSecond as u32,
        millisecond: time.wMilliseconds as u32,
    }
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

pub fn is_elevated() -> bool {
    unsafe {
        let mut token = HANDLE::default();
        if OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &mut token).is_err() {
            return false;
        }
        let mut elevation = TOKEN_ELEVATION::default();
        let mut size = 0u32;
        let read = GetTokenInformation(
            token,
            TokenElevation,
            Some(&mut elevation as *mut TOKEN_ELEVATION as *mut c_void),
            size_of::<TOKEN_ELEVATION>() as u32,
            &mut size,
        );
        let _ = CloseHandle(token);
        read.is_ok() && elevation.TokenIsElevated != 0
    }
}

// Starts this copy again as administrator, after the UAC prompt. The new copy waits for this one to exit, since only
// one hub runs at a time. False when the prompt was refused.
pub fn start_elevated(hidden: bool) -> bool {
    let Ok(exe) = std::env::current_exe() else { return false };
    let arguments = format!("{WAIT_FLAG} {}{}", std::process::id(), if hidden { format!(" {HIDDEN_FLAG}") } else { String::new() });
    let started = unsafe { ShellExecuteW(None, w!("runas"), &HSTRING::from(exe.as_os_str()), &HSTRING::from(arguments), PCWSTR::null(), SW_SHOWNORMAL) };
    started.0 as isize > 32
}

pub fn wait_for_exit(process_id: u32) {
    unsafe {
        if let Ok(process) = OpenProcess(PROCESS_SYNCHRONIZE, false, process_id) {
            WaitForSingleObject(process, 15_000);
            let _ = CloseHandle(process);
        }
    }
}

const RUN_KEY: PCWSTR = w!("Software\\Microsoft\\Windows\\CurrentVersion\\Run");
const RUN_VALUE: PCWSTR = w!("Manifold");

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
// As administrator the hub starts from Task Scheduler instead, since Windows starts nothing elevated from the Run key.
pub fn autostart_enabled(as_admin: bool) -> bool {
    if as_admin {
        return task_starts_this_copy();
    }
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

pub fn set_autostart(on: bool, as_admin: bool) {
    if as_admin {
        set_run_key(false);
        if on { create_task() } else { delete_task() }
    } else {
        delete_task();
        set_run_key(on);
    }
}

fn set_run_key(on: bool) {
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

fn schtasks(arguments: &[&str]) -> Option<String> {
    let output = Command::new("schtasks.exe").args(arguments).creation_flags(CREATE_NO_WINDOW).output().ok()?;
    output.status.success().then(|| String::from_utf8_lossy(&output.stdout).into_owned())
}

fn task_starts_this_copy() -> bool {
    let exe = std::env::current_exe().map(|path| path.display().to_string()).unwrap_or_default();
    schtasks(&["/Query", "/TN", TASK_NAME, "/XML"]).is_some_and(|xml| xml.contains(&escape_xml(&exe)))
}

fn delete_task() {
    let _ = schtasks(&["/Delete", "/F", "/TN", TASK_NAME]);
}

fn escape_xml(text: &str) -> String {
    text.replace('&', "&amp;").replace('<', "&lt;").replace('>', "&gt;").replace('"', "&quot;")
}

// Runs at sign-in for this user only, with the highest rights the user has, with no time limit and at normal priority:
// Task Scheduler's defaults would stop the hub after three days and run it below normal priority.
fn create_task() {
    let Ok(exe) = std::env::current_exe() else { return };
    let user = format!("{}\\{}", std::env::var("USERDOMAIN").unwrap_or_default(), std::env::var("USERNAME").unwrap_or_default());
    let xml = format!(
        r#"<?xml version="1.0" encoding="UTF-16"?>
<Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
  <RegistrationInfo><Description>Starts the Manifold hub as administrator when you sign in.</Description></RegistrationInfo>
  <Triggers><LogonTrigger><Enabled>true</Enabled><UserId>{user}</UserId></LogonTrigger></Triggers>
  <Principals><Principal id="Author"><UserId>{user}</UserId><LogonType>InteractiveToken</LogonType><RunLevel>HighestAvailable</RunLevel></Principal></Principals>
  <Settings>
    <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
    <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>
    <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>
    <ExecutionTimeLimit>PT0S</ExecutionTimeLimit>
    <Priority>5</Priority>
  </Settings>
  <Actions Context="Author"><Exec><Command>{exe}</Command><Arguments>{HIDDEN_FLAG}</Arguments></Exec></Actions>
</Task>
"#,
        user = escape_xml(&user),
        exe = escape_xml(&exe.display().to_string()),
    );
    // schtasks reads a task file as UTF-16 with a byte order mark.
    let mut bytes = vec![0xFF, 0xFE];
    bytes.extend(xml.encode_utf16().flat_map(u16::to_le_bytes));
    let file = std::env::temp_dir().join("manifold-task.xml");
    if std::fs::write(&file, bytes).is_ok() {
        let _ = schtasks(&["/Create", "/F", "/TN", TASK_NAME, "/XML", &file.display().to_string()]);
        let _ = std::fs::remove_file(&file);
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
