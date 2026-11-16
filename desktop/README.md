# Manifold hub for Windows and Linux

The desktop hub pairs with the Manifold hub on an Android phone. On Windows it plays the feeds that a paired device shares, each in a window of its own, and shares windows, displays, cameras and Spout senders of this PC with paired devices. It is one executable written in Rust, with a [Slint](https://slint.dev) interface and Windows' own capture, encoding and decoding. The Linux build shares windows and displays and takes remote control from a phone; it is described under [Linux](#linux).

The protocol it speaks is described in [../docs/NETWORK.md](../docs/NETWORK.md), and the code is described in [../docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md).

## Requirements

- 64-bit Windows 10 or 11. Sharing the sound of one application needs Windows 10 build 20348 or later; on older builds windows are shared without sound. Hiding the mouse pointer needs Windows 10 version 2004 or later.
- Controllers sent from a phone need the [ViGEmBus](https://github.com/nefarius/ViGEmBus/releases/latest) driver. Controlling a program that runs as administrator needs the hub to run as administrator too. See "Remote control" in the [main README](../README.md#remote-control).
- To build it: Rust stable, Visual Studio with the "Desktop development with C++" workload (for the capture code and the Windows SDK), and Python 3 to package a release.

To install a released build, unzip `manifold-hub-windows-v<version>.zip` from the [releases page](https://github.com/yuredazo/manifold/releases) into a folder you can write to and run `manifold_hub.exe`. The build is not code signed, so SmartScreen may warn the first time.

## Build and run

From this folder:

```
cargo test -p manifold-protocol
cargo run -p manifold-hub
cargo build --release -p manifold-hub
./package.ps1 -Out manifold-hub-windows.zip
```

The first build downloads the Spout 2.007.017 sources with `curl` (a pinned archive, checked by SHA-256) and unpacks them with `tar`, so it needs internet access. The release build is `target/release/manifold_hub.exe`. `package.ps1` puts it in a zip with a `licenses` folder: Manifold's licence, Spout's BSD 2-Clause licence, and the licence texts of every Rust crate linked into the app, which `notices.py` collects from the cargo registry.

| Path | What it is |
| --- | --- |
| `protocol` | The network protocol, a port of the Android hub's `network.protocol` and `network.stream` packages. |
| `hub/src` | The app: the network thread, sharing, watching, the player windows, storage, the tray and the updater. `capture`, `remote` and `system` each have a `windows.rs` and a `linux` part; `x11.rs` is Linux only. |
| `hub/ui` | The Slint interface, its icons and the logo the app icon is drawn from. |
| `hub/native` | C++ that captures windows, displays, cameras and Spout senders and encodes H.264 and AAC, called through `bridge.cpp`. |

## Pairing with a phone

1. On the phone, open Devices in the Manifold hub, tap Pair a device and choose "Let another device pair with this one".
2. In this app, open Devices and click "Pair a device". The phone appears under Nearby; click it. If it does not appear, type the address the phone shows under Allow connections and click Pair.
3. Both screens show a six digit code. Confirm it on both.

The same steps work the other way round, with "Allow pairing for two minutes" on this side. Once paired, the two devices find each other again on their own, as long as both have Allow connections on. The switch is remembered across restarts.

Click a paired device to open its settings. These switches are there, and all start off.

| Switch | Effect |
| --- | --- |
| Show its feeds here | What the device shares appears on the Watch page. |
| Windows and displays | The device can watch the windows and displays you share on the Share page. |
| Cameras | The device can watch cameras you share. Separate from the windows switch. |
| Spout senders | The device can watch Spout senders you share. Separate from the other two. |

Unpair asks for confirmation first. The device has to be paired again before it can connect.

## Watching

The Watch page lists the feeds of every paired device that is online and that you chose to watch. Click one to open it in a window of its own, and click it again, or close the window, to stop. Closing the window ends the stream on the other device. A feed that is only sound plays without a window.

Pictures are decoded by the H.264 decoder that ships with Windows, on the graphics card when it can, and drawn with Direct3D 11. A feed with transparency is drawn over a checkerboard. Sound is decoded by Windows' AAC decoder and played on the default output, behind a buffer that follows "Delay when watching": 60, 120 or 300 ms. Sound is not synchronized with the picture and plays slightly behind it.

## Sharing

On the Share page, "Share a window, display or camera" opens a picker with the open windows, the connected displays, and the cameras and Spout senders that are available.

- A window is shared with the sound of its application, if Windows allows it.
- A display is shared with the sound of everything the PC plays except Manifold.
- "Share the picture", when switched off, shares only the sound of a window or of the whole PC, which another hub can play.
- "Hide the title bar" shares only the inside of a window. Apps that draw their own title bar inside the window, such as browsers, keep it.
- "Show the pointer", when switched off, leaves the mouse pointer out of the picture of a window or display.
- A camera is shared without sound. Cameras are hidden from the picker until "Offer cameras" is switched on in Settings, and switching it off again withdraws any that are shared. A camera is opened only while a device watches it, and Windows must allow desktop apps to use the camera.
- Programs that output through Spout (TouchDesigner, Resolume and others) appear as Spout senders, without sound, and are read only while a device watches them. "Keep transparency" sends their alpha too.

Windows 11 draws a yellow outline around a window or display while it is captured, and the hub asks Windows to leave it off. Earlier Windows versions keep the outline. A feed is captured only while a device is watching it. Shares are remembered in `%APPDATA%\Manifold\shares.json`. After a restart, or when a window closes and opens again, the share finds its window the way OBS does: same program, then the exact title, then any window of the same type. Until one is found the share shows "waiting for the window".

"Stop all sharing", on the Share page and in the tray menu, withdraws every feed, ends every stream and stops every capture. The shares stay listed and Resume offers them again. It is not remembered across a restart. The Share page also lists recent activity, such as which device started or stopped watching what.

## Closing, the tray and startup

Closing the window exits the app. In Settings, "Keep running in the tray" hides the window instead, so the hub keeps listening for paired devices. A click on the tray icon shows the window again. A right click offers Stop all sharing (Resume sharing once stopped) and Exit.

"Start with Windows" adds `manifold_hub.exe --hidden` to the per-user Run key, so the hub starts at sign-in with no window and a tray icon. It counts as on only while the entry points at this copy of the app.

Only one hub runs per Windows session, because two would compete for UDP port 47200. Starting a second one brings the first window forward, even from the tray.

## UAC prompts and the lock screen

UAC prompts, the lock screen and the sign-in screen show on Windows' secure desktop, which ordinary programs can neither capture nor send input to. "Show UAC prompts and lock screen" in Settings installs a Windows service named "Manifold secure desktop", after one UAC prompt, or none when the hub already runs as administrator. It copies the hub to `C:\Program Files\Manifold Secure Desktop`, which only administrators can change, and runs that copy as SYSTEM. The service starts a helper in the signed-in session. While the secure desktop is up, the helper captures it with Desktop Duplication and passes the phone's input to it, and the hub streams the picture as usual. A display share shows its own display; a window share shows the main display, where UAC prompts appear.

While the switch is on, any program running as you can talk to the helper, so a program you run could answer a UAC prompt. Microsoft does not treat UAC as a security boundary, but leave the switch off on a PC where it matters. Switching it off removes the service and its folder.

The service keeps running the copy it was installed from. After the hub updates, Settings says "Switch off and on to update the service".

Not supported yet: the pointer is not drawn on the secure desktop, Ctrl+Alt+Del cannot be sent, and Remote Desktop sessions are not followed, only the one at the PC's own screen.

## Updates

Settings checks GitHub for a newer release and can install it. How that works is in the [main README](../README.md#updates). The updater needs the folder the app runs from to be writable.

The hub replaced a Flutter app of the same name in 1.5.0 and keeps its files, so pairings and shares carry over. The 1.4.0 updater installs it like any update; on its first start the new hub deletes the files the Flutter build left in its folder, by name.

## Files

Everything the app stores is in `%APPDATA%\Manifold`: the paired devices (`devices.txt`), the shares (`shares.json`), the switches from Settings, a log of why links went down (`network.log`, newest lines kept), and the identity key (`identity.key`), which is encrypted with Windows data protection for the signed-in user. Other programs running as the same user can read the other files.

Settings > About > Acknowledgements lists the open source parts the app is built on, and the Slint credit its licence asks for.

## Linux

The Linux build shares displays and windows with sound, and a paired phone that watches them can control them with its mouse, keyboard and controllers, as on Windows. It does not play feeds from other devices, so it has no Watch page, and it has no cameras, Spout, tray or "Run as administrator".

It needs an X11 session. Wayland sessions are not supported yet: X11 capture and input do not reach Wayland windows. It has been tested on Ubuntu 22.04 with XFCE on an NVIDIA RTX 3060, headless, with FFmpeg 4.4.

| Part | How it works on Linux |
| --- | --- |
| Displays | Each RandR monitor is a display. A screen with no monitor connected, as on a cloud GPU, is offered whole as "Screen". |
| Windows | Windows the window manager lists, captured through the Composite extension, so a covered window is still captured whole. |
| Picture | Copied with MIT-SHM, converted with FFmpeg's swscale in four bands at once, and encoded to H.264 with NVENC, or with x264 on the processor when NVENC cannot open. |
| Sound | What the default output plays, recorded from PulseAudio (or PipeWire's PulseAudio server) and encoded to AAC. A window is shared with the whole PC's sound. |
| Mouse and keyboard | Sent through the XTEST extension. Keys go in by physical position, as on Windows. |
| Controllers | Virtual Xbox 360 controllers made with uinput, with rumble sent back to the phone. |

Measured on that machine, sharing a 1920 x 1080 screen at 30 frames a second: a frame takes 3.7 ms to copy, 5.5 ms to convert and 1.5 ms to encode with NVENC, and the hub uses about 65% of one core.

### Build and run on Linux

On Ubuntu 22.04 these are the packages it was built with, and the run-time library the window needed on top:

```sh
sudo apt-get install build-essential pkg-config clang libclang-dev libavcodec-dev libavformat-dev libavutil-dev libswscale-dev libpulse-dev libxkbcommon-dev libfontconfig1-dev libx11-dev libxcursor-dev libxi-dev libxrandr-dev
sudo apt-get install libxkbcommon-x11-0
```

Then, from this folder, with Rust stable installed:

```sh
cargo build --release -p manifold-hub
./target/release/manifold_hub
```

The FFmpeg libraries are your system's, linked at run time. `curl` is used to look up the public address and check for updates, `xdg-open` to open links, and one of `wl-copy`, `xclip` or `xsel` to copy text. Updates are not installed in place on Linux: Settings shows a newer version and links to its release page.

On a machine that is only used from another device, turn off the screen saver and screen lock. When XFCE's screen saver starts on a machine with no screen, it takes every click and key press without showing anything, so the desktop looks frozen from the phone. For XFCE: `xset s off -dpms`, stop `xfce4-screensaver`, and hide it from autostart with a `~/.config/autostart/xfce4-screensaver.desktop` that contains `Hidden=true`.

"Start on login" writes `~/.config/autostart/manifold.desktop`, which starts the hub with no window. Opening Manifold again brings the window up, since only one hub runs per user.

### Controllers on Linux

Controllers need write access to `/dev/uinput`, which only root has by default. Without it, the Share page says so once a phone sends a controller. A udev rule hands the device to the `input` group:

```sh
echo 'KERNEL=="uinput", SUBSYSTEM=="misc", MODE="0660", GROUP="input", OPTIONS+="static_node=uinput"' | sudo tee /etc/udev/rules.d/60-manifold-uinput.rules
sudo udevadm control --reload-rules
sudo udevadm trigger --name-match=uinput
sudo usermod -aG input "$USER"
```

Sign out and in again for the group to apply. Members of `input` can also read every keyboard and mouse on the machine, so add only accounts you trust. The hub tries again every 5 seconds while a controller is in use, so it does not need a restart.

### Files on Linux

Everything the app stores is in `~/.config/manifold` (or `$XDG_CONFIG_HOME/manifold`), with the same file names as on Windows. The folder is readable only by your account. The identity key is stored there as plain hex, because Linux has no per-user encryption like Windows data protection that works without a desktop keyring.
