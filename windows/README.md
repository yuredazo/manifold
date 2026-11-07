# Manifold hub for Windows

The Windows hub pairs with the Manifold hub on an Android phone. It plays the feeds that a paired device shares, each in a window of its own, and shares windows, displays, cameras and Spout senders of this PC with paired devices. It is one executable written in Rust, with a [Slint](https://slint.dev) interface and Windows' own capture, encoding and decoding.

The protocol it speaks is described in [../docs/NETWORK.md](../docs/NETWORK.md), and the code is described in [../docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md).

## Requirements

- 64-bit Windows 10 or 11. Sharing the sound of one application needs Windows 10 build 20348 or later; on older builds windows are shared without sound. Hiding the mouse pointer needs Windows 10 version 2004 or later.
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
| `hub/src` | The app: the network thread, sharing, watching, the player windows, storage, the tray and the updater. |
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

## Updates

Settings checks GitHub for a newer release and can install it. How that works is in the [main README](../README.md#updates). The updater needs the folder the app runs from to be writable.

The hub replaced a Flutter app of the same name in 1.5.0 and keeps its files, so pairings and shares carry over. The 1.4.0 updater installs it like any update; on its first start the new hub deletes the files the Flutter build left in its folder, by name.

## Files

Everything the app stores is in `%APPDATA%\Manifold`: the paired devices (`devices.txt`), the shares (`shares.json`), the switches from Settings, a log of why links went down (`network.log`, newest lines kept), and the identity key (`identity.key`), which is encrypted with Windows data protection for the signed-in user. Other programs running as the same user can read the other files.

Settings > About > Acknowledgements lists the open source parts the app is built on, and the Slint credit its licence asks for.
