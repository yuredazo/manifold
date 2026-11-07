use crate::system;
use sha2::{Digest, Sha256};
use std::os::windows::process::CommandExt;
use std::path::{Path, PathBuf};
use std::process::Command;

const REPO: &str = "yuredazo/manifold";
pub const EXE_NAME: &str = "manifold_hub.exe";
const ASSET_PREFIX: &str = "manifold-hub-windows-v";
const MAX_DOWNLOAD_BYTES: usize = 300 * 1024 * 1024;
const MAX_API_BYTES: usize = 2 * 1024 * 1024;
const CREATE_NO_WINDOW: u32 = 0x0800_0000;

#[derive(Clone, Debug)]
pub struct Release {
    pub version: String,
    pub page: String,
    asset_name: String,
    asset_url: String,
    sha256: String,
}

// The same steps as the Flutter hub's swap, so either hub can install the other: wait for the running hub to exit,
// copy the new files over its folder, start it again.
const SWAP_SCRIPT: &str = r#"param([int]$ProcessId, [string]$Staging, [string]$AppDir, [string]$Log)
Wait-Process -Id $ProcessId -Timeout 60 -ErrorAction SilentlyContinue
robocopy $Staging $AppDir /E /R:20 /W:1 /NFL /NDL /NJH /NJS /NP | Out-File -FilePath $Log -Encoding utf8
if ($LASTEXITCODE -ge 8) { Add-Content -Path $Log -Value "copy failed with code $LASTEXITCODE" }
Start-Process -FilePath (Join-Path $AppDir 'manifold_hub.exe') -WorkingDirectory $AppDir
"#;

// Files a Flutter build of the hub put next to its exe. An update copies the new files over that folder and deletes
// nothing, so these stay behind; they are removed by name, and data only when it is Flutter's.
const FLUTTER_FILES: &[&str] = &[
    "d3dcompiler_47.dll",
    "dynamic_color_plugin.dll",
    "flutter_windows.dll",
    "libEGL.dll",
    "libGLESv2.dll",
    "libmpv-2.dll",
    "media_kit_libs_windows_video_plugin.dll",
    "screen_retriever_windows_plugin.dll",
    "spout_dx.lib",
    "tray_manager_plugin.dll",
    "url_launcher_windows_plugin.dll",
    "vk_swiftshader.dll",
    "vulkan-1.dll",
    "window_manager_plugin.dll",
    "zlib.dll",
];

fn install_dir() -> Option<PathBuf> {
    std::env::current_exe().ok()?.parent().map(Path::to_path_buf)
}

fn work_dir() -> PathBuf {
    std::env::temp_dir().join("manifold-update")
}

pub fn remove_flutter_leftovers() {
    let Some(dir) = install_dir() else { return };
    if !dir.join("flutter_windows.dll").is_file() {
        return;
    }
    for name in FLUTTER_FILES {
        let _ = std::fs::remove_file(dir.join(name));
    }
    let data = dir.join("data");
    if data.join("flutter_assets").is_dir() && data.join("icudtl.dat").is_file() {
        let _ = std::fs::remove_dir_all(data);
    }
}

pub fn clean_up() {
    let _ = std::fs::remove_dir_all(work_dir());
}

fn version_parts(text: &str) -> Vec<u32> {
    text.trim_start_matches('v').split(['.', '-', '+']).take(3).map(|part| part.parse().unwrap_or(0)).collect()
}

pub fn latest_release() -> Result<Option<Release>, String> {
    let body = system::https_get("api.github.com", &format!("/repos/{REPO}/releases/latest"), MAX_API_BYTES).map_err(|error| {
        // GitHub allows 60 unsigned requests an hour from one address and answers 403 or 429 after that.
        if error.ends_with("403") || error.ends_with("429") { "GitHub's hourly limit, try later".to_string() } else { error }
    })?;
    let json: serde_json::Value = serde_json::from_slice(&body).map_err(|_| "GitHub sent something unexpected".to_string())?;
    let tag = json.get("tag_name").and_then(|tag| tag.as_str()).ok_or("GitHub listed no version")?;
    if version_parts(tag) <= version_parts(env!("CARGO_PKG_VERSION")) {
        return Ok(None);
    }
    let listed = json.get("html_url").and_then(|url| url.as_str()).unwrap_or("");
    let page = if listed.starts_with(&format!("https://github.com/{REPO}/")) { listed.to_string() } else { format!("https://github.com/{REPO}/releases") };
    let download_prefix = format!("https://github.com/{REPO}/releases/download/");
    let assets = json.get("assets").and_then(|assets| assets.as_array()).map(Vec::as_slice).unwrap_or(&[]);
    // Only a zip from this repository's own releases, with the checksum GitHub computed for it.
    let asset = assets.iter().find_map(|asset| {
        let name = asset.get("name")?.as_str()?;
        let url = asset.get("browser_download_url")?.as_str()?;
        let digest = asset.get("digest")?.as_str()?.strip_prefix("sha256:")?;
        let valid_name = name.starts_with(ASSET_PREFIX) && name.ends_with(".zip") && !name.contains(['/', '\\']);
        (valid_name && url.starts_with(&download_prefix) && digest.len() == 64).then(|| (name.to_string(), url.to_string(), digest.to_lowercase()))
    });
    let (asset_name, asset_url, sha256) = asset.ok_or("the release has no Windows download")?;
    Ok(Some(Release { version: tag.trim_start_matches('v').to_string(), page, asset_name, asset_url, sha256 }))
}

fn writable(dir: &Path) -> bool {
    let probe = dir.join(".write-test");
    let written = std::fs::write(&probe, b"").is_ok();
    let _ = std::fs::remove_file(probe);
    written
}

// The caller has to exit right after: the swap waits for this process before it copies anything.
pub fn install(release: &Release, mut progress: impl FnMut(Option<f32>)) -> Result<(), String> {
    let app_dir = install_dir().ok_or("Manifold cannot find its own folder")?;
    if !writable(&app_dir) {
        return Err(format!("Manifold cannot write to {}. Download {} from the release page instead.", app_dir.display(), release.version));
    }
    let work = work_dir();
    let _ = std::fs::remove_dir_all(&work);
    std::fs::create_dir_all(&work).map_err(|error| format!("Could not prepare the update: {error}"))?;
    let bytes = system::https_get_url(&release.asset_url, MAX_DOWNLOAD_BYTES, &mut progress)?;
    let actual: String = Sha256::digest(&bytes).iter().map(|byte| format!("{byte:02x}")).collect();
    if actual != release.sha256 {
        return Err("The download does not match the checksum GitHub lists for it, so it was thrown away.".into());
    }
    let zip = work.join(&release.asset_name);
    std::fs::write(&zip, &bytes).map_err(|error| format!("Could not write the update: {error}"))?;
    let staging = work.join("staging");
    std::fs::create_dir_all(&staging).map_err(|error| format!("Could not prepare the update: {error}"))?;
    // The tar that ships with Windows 10 and later unpacks zip archives.
    let unpacked = Command::new("tar").arg("-xf").arg(&zip).arg("-C").arg(&staging).creation_flags(CREATE_NO_WINDOW).status();
    if !unpacked.is_ok_and(|status| status.success()) || !staging.join(EXE_NAME).is_file() {
        return Err("The download does not contain the Manifold app.".into());
    }
    let script = work.join("swap.ps1");
    std::fs::write(&script, SWAP_SCRIPT).map_err(|error| format!("Could not write the update: {error}"))?;
    Command::new("powershell.exe")
        .args(["-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File"])
        .arg(&script)
        .arg("-ProcessId")
        .arg(std::process::id().to_string())
        .arg("-Staging")
        .arg(&staging)
        .arg("-AppDir")
        .arg(&app_dir)
        .arg("-Log")
        .arg(work.join("update.log"))
        .creation_flags(CREATE_NO_WINDOW)
        .spawn()
        .map_err(|error| format!("Could not start the update: {error}"))?;
    Ok(())
}
