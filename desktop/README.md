# Manifold hub for Windows and Linux

The desktop hub pairs with the Manifold hub on an Android phone. On Windows it plays the feeds a paired device shares, each in its own window, and shares windows, displays, cameras and Spout senders of this PC. On Linux it shares windows and displays and takes remote control from a phone (see [Linux](#linux)). It is one Rust executable with a [Slint](https://slint.dev) interface.

The protocol is described in [NETWORK.md](../docs/NETWORK.md) and the code in [ARCHITECTURE.md](../docs/ARCHITECTURE.md).

## Requirements

- 64-bit Windows 10 or 11. Sharing one application's sound needs Windows 10 build 20348 or later, and hiding the pointer needs version 2004 or later; older builds share without them.
- Controllers sent from a phone need the [ViGEmBus](https://github.com/nefarius/ViGEmBus/releases/latest) driver. Controlling a program that runs as administrator needs the hub to run as administrator too (see [Remote control](../README.md#remote-control)).

To install a release, unzip `manifold-hub-windows-v<version>.zip` from the [releases page](https://github.com/yuredazo/manifold/releases) into a folder you can write to and run `manifold_hub.exe`. It is not code signed, so SmartScreen may warn the first time.

## Build and run

On Windows you need Rust stable, Visual Studio with the "Desktop development with C++" workload, and Python 3 to package a release. From this folder:

```sh
cargo test -p manifold-protocol
cargo run -p manifold-hub
cargo build --release -p manifold-hub
./package.ps1 -Out manifold-hub-windows.zip
```

The first build downloads the Spout 2.007.017 sources with `curl` (a pinned archive checked by SHA-256) and unpacks them with `tar`, so it needs internet access. The release build is `target/release/manifold_hub.exe`. `package.ps1` zips it with a `licenses` folder: Manifold's licence, Spout's BSD 2-Clause licence, and the licence of every Rust crate linked in, which `notices.py` collects from the cargo registry. Linux is under [Build and run on Linux](#build-and-run-on-linux).

| Path | What it is |
| --- | --- |
| `protocol` | The network protocol, a port of the Android hub's `network/protocol` and `network/stream`. |
| `hub/src` | The app: network thread, sharing, watching, players, storage, tray and updater. `capture`, `remote` and `system` each have a Windows and a Linux part. |
| `hub/ui` | The Slint interface, its icons and the logo the app icon is drawn from. |
| `hub/native` | C++ that captures windows, displays, cameras and Spout senders and encodes H.264 and AAC, called through `bridge.cpp`. |

## Pairing with a phone

1. On the phone, open Devices, tap Pair a device and choose "Let another device pair with this one".
2. In this app, open Devices and click "Pair a device". Click the phone under Nearby, or type the address the phone shows under Allow connections and click Pair.
3. Confirm the six digit code on both screens.

It works the other way round too, with "Allow pairing for two minutes" on this side. Once paired, the devices find each other again on their own while both have Allow connections on, which is remembered across restarts.

Click a paired device to open its settings. All switches start off:

| Switch | Effect |
| --- | --- |
| Show its feeds here | What the device shares appears on the Watch page. |
| Windows and displays | The device can watch the windows and displays you share. |
| Cameras | The device can watch cameras you share, and its feed list shows them. Separate from the windows switch. |
| Spout senders | The device can watch Spout senders you share. Separate from the other two. |
| Control PC | The device can control the windows and displays it watches (needs "Windows and displays"). |
| Share clipboard | While the device controls a share, text copied on either side can be pasted on the other, up to 64 KB (needs "Control PC"). On Linux only the device's copies arrive for now. |

Unpair asks first; the device then has to pair again before it can connect.

## Watching

The Watch page lists the feeds of every paired device that is online and whose feeds you show. Click one to open it in its own window, and click it again or close the window to stop, which also ends the stream on the other device. A sound-only feed plays without a window.

Pictures are decoded by the H.264 decoder that ships with Windows, on the graphics card when it can, and drawn with Direct3D 11; a feed with transparency is drawn over a checkerboard. Sound goes through Windows' AAC decoder to the default output behind a buffer set by "Delay when watching" (60, 120 or 300 ms). It is not synchronized with the picture and plays slightly behind it.

## Sharing

On the Share page, "Share a window, display or camera" opens a picker with the open windows, the connected displays, and the available cameras and Spout senders.

- A window is shared with its application's sound, if Windows allows it. A display is shared with everything the PC plays except Manifold.
- "Share the picture", switched off, shares only the sound of a window or of the whole PC. Such a feed is named `<title> (sound)`, or `PC sound` for a display.
- "Hide the title bar" shares only the inside of a window. Apps that draw their own title bar, such as browsers, keep it.
- "Show the pointer", switched off, leaves the pointer out of a window or display.
- The frame rate is 30 unless set to 24, 45 or 60.
- Cameras are shared without sound and are hidden from the picker until "Offer cameras" is on in Settings; switching it off withdraws any that are shared. Windows must allow desktop apps to use the camera.
- Programs that output through Spout (TouchDesigner, Resolume, VTube Studio and others) appear as Spout senders, without sound. "Keep transparency", on by default, sends their alpha too.

A source is captured only while a device watches it. Windows 11 draws a yellow outline around a captured window or display, and the hub asks Windows to leave it off; earlier versions keep it.

Shares are remembered in `%APPDATA%\Manifold\shares.json` and found again after a restart or when the source comes back. A window is found the way OBS does it: same program, then the exact title, then any window of the same class, so a browser window is still found after its page changes. A display is found by its device name, such as `\\.\DISPLAY2`, a camera by its device id or else its name when it moved to another USB port, and a Spout sender by its name. Until the source is found the share shows as waiting and is not offered to devices. A feed keeps the name it was shared with, while the title sent with it follows the window, checked every two seconds.

"Stop all sharing", on the Share page and in the tray menu, withdraws every feed and stops every capture. The shares stay listed and Resume offers them again; this is not remembered across a restart. The Share page also lists recent activity, such as who started or stopped watching what.

## The tray and startup

Closing the window exits the app unless "Keep running in the tray" is on in Settings, which hides it so the hub keeps listening. A click on the tray icon brings the window back; a right click offers Stop all sharing (or Resume sharing) and Exit.

"Start with Windows" adds `manifold_hub.exe --hidden` to the per-user Run key, so the hub starts at sign-in as a tray icon. It counts as on only while the entry points at this copy of the app. Only one hub runs per Windows session, because two would compete for UDP port 47200, and starting a second one brings the first window forward, even from the tray.

## UAC prompts and the lock screen

UAC prompts, the lock screen and the sign-in screen show on Windows' secure desktop, which ordinary programs can neither capture nor send input to. "Show UAC prompts and lock screen" in Settings installs a Windows service, "Manifold secure desktop", after one UAC prompt (none when the hub already runs as administrator). It copies the hub to `C:\Program Files\Manifold Secure Desktop`, which only administrators can change, and runs that copy as SYSTEM. The service starts a helper in the signed-in session that, while the secure desktop is up, captures it with Desktop Duplication and passes the phone's input to it, and the hub streams the picture as usual. A display share shows its own display; a window share shows the main display, where UAC prompts appear.

While the switch is on, any program running as you can talk to the helper, so a program you run could answer a UAC prompt. Microsoft does not treat UAC as a security boundary, but leave the switch off on a PC where it matters. Switching it off removes the service and its folder. The hub talks only to a helper running as SYSTEM.

The service keeps running the copy it was installed from, so after the hub updates, Settings says "Switch off and on to update the service".

Not supported yet: the pointer is not drawn on the secure desktop, Ctrl+Alt+Del cannot be sent, and only the session at the PC's own screen is followed, not Remote Desktop.

## Updates and files

Settings checks GitHub for a newer release and can install it, as described in the [main README](../README.md#updates); the folder the app runs from must be writable. The hub replaced a Flutter app of the same name in 1.5.0 and reads its files, so pairings and shares carried over, and on its first start it deletes the Flutter build's files from its folder by name.

Everything the app stores is in `%APPDATA%\Manifold`: the paired devices (`devices.txt`), the shares (`shares.json`), the Settings switches, a log of why links went down (`network.log`, newest lines kept), and the identity key (`identity.key`), encrypted with Windows data protection for the signed-in user. Other programs running as the same user can read the other files. Settings > About > Acknowledgements lists the open source parts and the Slint credit its licence asks for.

## Linux

The Linux build shares displays and windows with sound, and a phone watching them can control them with its mouse, keyboard and controllers, as on Windows. It does not play feeds from other devices, so it has no Watch page, and it has no cameras, Spout, tray or "Run as administrator".

It needs an X11 session; X11 capture and input do not reach Wayland windows. It has been tested on Ubuntu 22.04 with XFCE on an NVIDIA RTX 3060, headless, with FFmpeg 4.4.

| Part | How it works on Linux |
| --- | --- |
| Displays | Each RandR monitor is a display. A screen with no monitor connected, as on a cloud GPU, is offered whole as "Screen". |
| Windows | The windows the window manager lists, captured through the Composite extension, so a covered window is still captured whole. |
| Picture | Copied with MIT-SHM, converted with FFmpeg's swscale in four bands at once, and encoded to H.264 with NVENC, or x264 when NVENC cannot open. |
| Sound | What the default output plays, recorded from PulseAudio (or PipeWire's PulseAudio server) and encoded to AAC. A window is shared with the whole PC's sound. |
| Mouse and keyboard | Sent through XTEST, keys by physical position as on Windows. |
| Controllers | Virtual Xbox 360 controllers made with uinput, with rumble sent back to the phone. |

On that machine, sharing a 1920 x 1080 screen at 30 frames a second, a frame takes 3.7 ms to copy, 5.5 ms to convert and 1.5 ms to encode with NVENC, and the hub uses about 65% of one core.

### Build and run on Linux

On Ubuntu 22.04 these are the build packages, and the run-time library the window needed on top:

```sh
sudo apt-get install build-essential pkg-config clang libclang-dev libavcodec-dev libavformat-dev libavutil-dev libswscale-dev libpulse-dev libxkbcommon-dev libfontconfig1-dev libx11-dev libxcursor-dev libxi-dev libxrandr-dev
sudo apt-get install libxkbcommon-x11-0
```

Then, from this folder, with Rust stable:

```sh
cargo build --release -p manifold-hub
./target/release/manifold_hub
```

FFmpeg is your system's, linked at run time. `curl` looks up the public address and checks for updates, `xdg-open` opens links, and one of `wl-copy`, `xclip` or `xsel` copies text. Updates are not installed in place: Settings shows a newer version and links to its release page. "Start on login" writes `~/.config/autostart/manifold.desktop`, which starts the hub with no window; opening Manifold again brings the window up, since only one hub runs per user.

On a machine used only from another device, turn off the screen saver and screen lock. When XFCE's screen saver starts on a machine with no screen it takes every click and key without showing anything, so the desktop looks frozen from the phone. For XFCE: `xset s off -dpms`, stop `xfce4-screensaver`, and hide it from autostart with a `~/.config/autostart/xfce4-screensaver.desktop` containing `Hidden=true`.

### Controllers on Linux

Controllers need write access to `/dev/uinput`, which only root has by default; without it the Share page says so once a phone sends a controller. A udev rule hands the device to the `input` group:

```sh
echo 'KERNEL=="uinput", SUBSYSTEM=="misc", MODE="0660", GROUP="input", OPTIONS+="static_node=uinput"' | sudo tee /etc/udev/rules.d/60-manifold-uinput.rules
sudo udevadm control --reload-rules
sudo udevadm trigger --name-match=uinput
sudo usermod -aG input "$USER"
```

Sign out and in for the group to apply. Members of `input` can read every keyboard and mouse on the machine, so add only accounts you trust. The hub tries again every 5 seconds while a controller is in use, so it needs no restart.

### Files on Linux

Everything is in `~/.config/manifold` (or `$XDG_CONFIG_HOME/manifold`) under the same names as on Windows, in a folder only your account can open. The identity key is plain hex there, because Linux has no per-user encryption like Windows data protection that works without a desktop keyring.
