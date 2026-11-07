use sha2::{Digest, Sha256};
use std::path::{Path, PathBuf};
use std::process::Command;

// Pinned by hash, so a changed download fails the build instead of changing the app.
const SPOUT_URL: &str = "https://github.com/leadedge/Spout2/archive/f49e2f469f8cb25f559a6eaa61a3f5b8173fc100.tar.gz";
const SPOUT_SHA256: &str = "9d93cadc7fea63d3e8b26384da8f8f23982a06a07adb0363d75630a99ab1f8f1";
const SPOUT_FOLDER: &str = "Spout2-f49e2f469f8cb25f559a6eaa61a3f5b8173fc100";

const NATIVE: &[&str] = &[
    "native/bridge.cpp",
    "native/capture/window_capturer.cpp",
    "native/capture/camera_capturer.cpp",
    "native/capture/frame_pump.cpp",
    "native/capture/nv12_converter.cpp",
    "native/capture/spout_capturer.cpp",
    "native/audio/app_audio_capturer.cpp",
    "native/encode/h264_encoder.cpp",
    "native/encode/aac_encoder.cpp",
    "native/encode/mft.cpp",
];

const SPOUT: &[&str] = &[
    "SpoutDirectX/SpoutDX/SpoutDX.cpp",
    "SpoutGL/SpoutCopy.cpp",
    "SpoutGL/SpoutDirectX.cpp",
    "SpoutGL/SpoutFrameCount.cpp",
    "SpoutGL/SpoutSenderNames.cpp",
    "SpoutGL/SpoutSharedMemory.cpp",
    "SpoutGL/SpoutUtils.cpp",
];

const LIBRARIES: &[&str] = &[
    "d3d11", "d3dcompiler", "dxgi", "mfplat", "mf", "mfreadwrite", "mfuuid", "strmiids", "wmcodecdspuuid", "mmdevapi", "windowsapp", "winmm",
    "dwmapi", "version", "comctl32", "comdlg32", "shell32", "ole32", "uuid", "user32", "advapi32",
];

fn spout_sdk(out: &Path) -> PathBuf {
    let sdk = out.join(SPOUT_FOLDER).join("SPOUTSDK");
    if sdk.exists() {
        return sdk;
    }
    let archive = out.join("spout2.tar.gz");
    let fetched = Command::new("curl").args(["-sSfL", "-o"]).arg(&archive).arg(SPOUT_URL).status().expect("curl is needed to fetch Spout");
    assert!(fetched.success(), "could not download Spout from {SPOUT_URL}");
    let bytes = std::fs::read(&archive).unwrap();
    let digest: String = Sha256::digest(&bytes).iter().map(|byte| format!("{byte:02x}")).collect();
    assert_eq!(digest, SPOUT_SHA256, "the Spout download does not match its pinned hash");
    let unpacked = Command::new("tar").arg("-xzf").arg(&archive).arg("-C").arg(out).status().expect("tar is needed to unpack Spout");
    assert!(unpacked.success(), "could not unpack Spout");
    sdk
}

const ICON_SIZES: [u32; 8] = [16, 20, 24, 32, 40, 48, 64, 256];

// Every size the shell asks for, as PNG entries in one .ico, drawn from the same white logo the window shows.
fn write_icon(out: &Path) -> PathBuf {
    let svg = std::fs::read("ui/logo.svg").unwrap();
    let tree = resvg::usvg::Tree::from_data(&svg, &resvg::usvg::Options::default()).unwrap();
    let images: Vec<Vec<u8>> = ICON_SIZES
        .iter()
        .map(|&size| {
            let mut pixmap = resvg::tiny_skia::Pixmap::new(size, size).unwrap();
            // The mark spans 16 to 112 of its 128 unit box; a 12 unit margin keeps it the size of other taskbar icons.
            let scale = size as f32 / 104.0;
            resvg::render(&tree, resvg::tiny_skia::Transform::from_row(scale, 0.0, 0.0, scale, -12.0 * scale, -12.0 * scale), &mut pixmap.as_mut());
            pixmap.encode_png().unwrap()
        })
        .collect();
    let mut ico = vec![0, 0, 1, 0, ICON_SIZES.len() as u8, 0];
    let mut offset = 6 + 16 * ICON_SIZES.len();
    for (size, png) in ICON_SIZES.iter().zip(&images) {
        let side = if *size >= 256 { 0 } else { *size as u8 };
        ico.extend_from_slice(&[side, side, 0, 0, 1, 0, 32, 0]);
        ico.extend_from_slice(&(png.len() as u32).to_le_bytes());
        ico.extend_from_slice(&(offset as u32).to_le_bytes());
        offset += png.len();
    }
    for png in &images {
        ico.extend_from_slice(png);
    }
    let icon = out.join("manifold.ico");
    std::fs::write(&icon, ico).unwrap();
    let script = out.join("app.rc");
    // rc reads the path as a C string, so its backslashes are doubled.
    std::fs::write(&script, format!("1 ICON \"{}\"\n", icon.display().to_string().replace('\\', "\\\\"))).unwrap();
    script
}

fn main() {
    let out = PathBuf::from(std::env::var("OUT_DIR").unwrap());
    let sdk = spout_sdk(&out);

    let mut build = cc::Build::new();
    build
        .cpp(true)
        .std("c++17")
        .flag("/EHsc")
        .flag("/utf-8")
        .warnings(false)
        .include("native")
        .include(sdk.join("SpoutDirectX/SpoutDX"))
        .include(sdk.join("SpoutGL"))
        .define("UNICODE", None)
        .define("_UNICODE", None)
        .define("NOMINMAX", None)
        // C++/WinRT at C++17 includes <experimental/coroutine>, which Visual Studio 2026 turns into an error.
        .define("_SILENCE_EXPERIMENTAL_COROUTINE_DEPRECATION_WARNINGS", None);
    build.files(NATIVE);
    build.files(SPOUT.iter().map(|file| sdk.join(file)));
    build.compile("manifold_native");
    for library in LIBRARIES {
        println!("cargo:rustc-link-lib={library}");
    }
    println!("cargo:rerun-if-changed=native");

    embed_resource::compile(write_icon(&out), embed_resource::NONE).manifest_optional().unwrap();
    println!("cargo:rerun-if-changed=ui/logo.svg");
    slint_build::compile("ui/app.slint").unwrap();
}
