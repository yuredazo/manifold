use crate::system;
use std::ffi::{CStr, CString, c_char};

const SERVICE_FLAG: &str = "--secure-service";
const HELPER_FLAG: &str = "--secure-helper";
const INSTALL_FLAG: &str = "--install-secure";
const REMOVE_FLAG: &str = "--remove-secure";

// The layout of secure_link::Input in native/secure/secure_link.h.
#[repr(C, packed)]
pub struct RawInput {
    pub kind: u32,
    pub dx: i32,
    pub dy: i32,
    pub data: u32,
    pub flags: u32,
    pub scan: u16,
}

unsafe extern "C" {
    fn secure_service_run() -> i32;
    fn secure_helper_run(version: *const c_char) -> i32;
    fn secure_install() -> i32;
    fn secure_uninstall() -> i32;
    fn secure_service_state() -> i32;
    fn secure_desktop_showing() -> i32;
    fn secure_send_input(inputs: *const RawInput, count: i32);
    fn secure_helper_version(out: *mut c_char, size: i32);
}

// The service, its helper and the elevated install steps are this same program, started with a flag.
pub fn run_mode(arguments: &[String]) -> Option<i32> {
    Some(match arguments.get(1)?.as_str() {
        SERVICE_FLAG => unsafe { secure_service_run() },
        HELPER_FLAG => {
            let version = CString::new(env!("CARGO_PKG_VERSION")).unwrap_or_default();
            unsafe { secure_helper_run(version.as_ptr()) }
        }
        INSTALL_FLAG => unsafe { secure_install() },
        REMOVE_FLAG => unsafe { secure_uninstall() },
        _ => return None,
    })
}

pub fn installed() -> bool {
    unsafe { secure_service_state() != 0 }
}

pub fn showing() -> bool {
    unsafe { secure_desktop_showing() != 0 }
}

pub fn send(inputs: &[RawInput]) {
    unsafe { secure_send_input(inputs.as_ptr(), inputs.len() as i32) };
}

// The service keeps running the copy it was installed from, so after an update of the hub it is left behind until
// switched off and on again.
pub fn outdated() -> bool {
    let mut buffer = [0 as c_char; 32];
    unsafe { secure_helper_version(buffer.as_mut_ptr(), buffer.len() as i32) };
    let version = unsafe { CStr::from_ptr(buffer.as_ptr()) }.to_string_lossy();
    !version.is_empty() && version != env!("CARGO_PKG_VERSION")
}

// Installing or removing a service takes administrator rights, so a hub without them runs a copy of itself that has
// them, behind the UAC prompt, and waits for it.
pub fn set_installed(on: bool) -> bool {
    if system::is_elevated() {
        return unsafe { if on { secure_install() } else { secure_uninstall() } } == 0;
    }
    system::run_elevated_and_wait(if on { INSTALL_FLAG } else { REMOVE_FLAG }) == Some(0)
}
