use manifold_protocol::crypto::{KEY_LENGTH, KeyPair, from_hex, to_hex};
use manifold_protocol::devices::{DeviceBook, Identity};
use manifold_protocol::stream::StreamProfile;
use std::io::Write;
use std::path::PathBuf;
use windows::Win32::Foundation::{HLOCAL, LocalFree};
use windows::Win32::System::SystemInformation::{ComputerNameDnsHostname, GetComputerNameExW};
use windows::Win32::Security::Cryptography::{CRYPT_INTEGER_BLOB, CRYPTPROTECT_UI_FORBIDDEN, CryptProtectData, CryptUnprotectData};

const IDENTITY_FILE: &str = "identity.key";
const SEALED_PREFIX: &str = "dpapi:";

// Same folder and file names as the Flutter hub, so switching hubs keeps the identity, pairings and shares.
// Files are readable by programs running as the same user, so anything secret is sealed before it is written.
#[derive(Clone)]
pub struct Storage {
    directory: PathBuf,
}

impl Storage {
    pub fn open() -> Self {
        let base = std::env::var_os("APPDATA").map(PathBuf::from).unwrap_or_else(std::env::temp_dir);
        let directory = base.join("Manifold");
        let _ = std::fs::create_dir_all(&directory);
        Self { directory }
    }

    pub fn read(&self, name: &str) -> Option<String> {
        std::fs::read_to_string(self.directory.join(name)).ok()
    }

    pub fn write(&self, name: &str, text: &str) {
        let target = self.directory.join(name);
        let temporary = self.directory.join(format!("{name}.tmp"));
        let written = std::fs::File::create(&temporary).and_then(|mut file| {
            file.write_all(text.as_bytes())?;
            file.sync_all()
        });
        if written.is_ok() {
            let _ = std::fs::rename(&temporary, &target);
        }
    }

    pub fn append(&self, name: &str, line: &str) {
        const MAX_BYTES: u64 = 100_000;
        let path = self.directory.join(name);
        if let Ok(mut file) = std::fs::OpenOptions::new().create(true).append(true).open(&path) {
            let _ = writeln!(file, "{line}");
        }
        if std::fs::metadata(&path).map(|meta| meta.len()).unwrap_or(0) <= MAX_BYTES {
            return;
        }
        let text = std::fs::read_to_string(&path).unwrap_or_default();
        let cut = text[text.len() / 2..].find('\n').map(|at| text.len() / 2 + at + 1);
        let _ = std::fs::write(&path, cut.map(|at| &text[at..]).unwrap_or(""));
    }

    pub fn load_identity(&self) -> Identity {
        Identity { keys: self.load_keys(), name: computer_name() }
    }

    // 1.0.0 of the Flutter hub wrote the key as plain hex.
    fn load_keys(&self) -> KeyPair {
        let stored = self.read(IDENTITY_FILE).map(|text| text.trim().to_string());
        let private = match stored.as_deref() {
            Some(text) if text.starts_with(SEALED_PREFIX) => from_hex(&text[SEALED_PREFIX.len()..]).and_then(|sealed| unprotect(&sealed)),
            Some(text) => {
                let plain = from_hex(text);
                if let Some(key) = plain.as_ref().filter(|key| key.len() == KEY_LENGTH) {
                    self.keep(key);
                }
                plain
            }
            None => None,
        };
        match private.and_then(|key| <[u8; KEY_LENGTH]>::try_from(key).ok()) {
            Some(key) => KeyPair::from_private(key),
            None => {
                let keys = KeyPair::generate();
                self.keep(&keys.private);
                keys
            }
        }
    }

    fn keep(&self, private: &[u8]) {
        if let Some(sealed) = protect(private) {
            self.write(IDENTITY_FILE, &format!("{SEALED_PREFIX}{}", to_hex(&sealed)));
        }
    }

    pub fn load_devices(&self) -> DeviceBook {
        let saver = self.clone();
        DeviceBook::new(self.read("devices.txt").as_deref(), move |text| saver.write("devices.txt", text))
    }
}

// The host name as gethostname gives it, which is what the Flutter hub showed, case and all.
fn computer_name() -> String {
    let mut buffer = [0u16; 256];
    let mut size = buffer.len() as u32;
    match unsafe { GetComputerNameExW(ComputerNameDnsHostname, Some(windows::core::PWSTR(buffer.as_mut_ptr())), &mut size) } {
        Ok(()) => String::from_utf16_lossy(&buffer[..size as usize]),
        Err(_) => std::env::var("COMPUTERNAME").unwrap_or_else(|_| "Windows PC".to_string()),
    }
}

fn protect(plain: &[u8]) -> Option<Vec<u8>> {
    let input = CRYPT_INTEGER_BLOB { cbData: plain.len() as u32, pbData: plain.as_ptr() as *mut u8 };
    let mut output = CRYPT_INTEGER_BLOB::default();
    unsafe { CryptProtectData(&input, None, None, None, None, CRYPTPROTECT_UI_FORBIDDEN, &mut output) }.ok()?;
    Some(take_blob(output))
}

fn unprotect(sealed: &[u8]) -> Option<Vec<u8>> {
    let input = CRYPT_INTEGER_BLOB { cbData: sealed.len() as u32, pbData: sealed.as_ptr() as *mut u8 };
    let mut output = CRYPT_INTEGER_BLOB::default();
    unsafe { CryptUnprotectData(&input, None, None, None, None, CRYPTPROTECT_UI_FORBIDDEN, &mut output) }.ok()?;
    Some(take_blob(output))
}

fn take_blob(blob: CRYPT_INTEGER_BLOB) -> Vec<u8> {
    let bytes = unsafe { std::slice::from_raw_parts(blob.pbData, blob.cbData as usize) }.to_vec();
    unsafe { LocalFree(Some(HLOCAL(blob.pbData.cast()))) };
    bytes
}

const CHECK_KEY: &str = "updates.check";
const TRAY_KEY: &str = "close.tray";
const CAMERAS_KEY: &str = "share.cameras";
const PROFILE_KEY: &str = "stream.profile";
pub const LISTENING_KEY: &str = "listening";

pub struct Settings {
    storage: Storage,
    pub check_on_launch: bool,
    pub close_to_tray: bool,
    // Off by default: it keeps cameras out of the Share list altogether, so one cannot be shared by mistake.
    pub offer_cameras: bool,
    pub stream_profile: StreamProfile,
}

impl Settings {
    pub fn load(storage: &Storage) -> Self {
        Self {
            storage: storage.clone(),
            check_on_launch: storage.read(CHECK_KEY).as_deref() != Some("off"),
            close_to_tray: storage.read(TRAY_KEY).as_deref() == Some("on"),
            offer_cameras: storage.read(CAMERAS_KEY).as_deref() == Some("on"),
            stream_profile: StreamProfile::of(storage.read(PROFILE_KEY).as_deref().unwrap_or("")),
        }
    }

    pub fn set_check_on_launch(&mut self, on: bool) {
        self.check_on_launch = on;
        self.storage.write(CHECK_KEY, if on { "on" } else { "off" });
    }

    pub fn set_close_to_tray(&mut self, on: bool) {
        self.close_to_tray = on;
        self.storage.write(TRAY_KEY, if on { "on" } else { "off" });
    }

    pub fn set_offer_cameras(&mut self, on: bool) {
        self.offer_cameras = on;
        self.storage.write(CAMERAS_KEY, if on { "on" } else { "off" });
    }

    pub fn set_stream_profile(&mut self, profile: StreamProfile) {
        self.stream_profile = profile;
        self.storage.write(PROFILE_KEY, profile.id());
    }
}
