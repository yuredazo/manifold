# Manifold hub for Windows

The Windows hub is a Flutter app that pairs with the Manifold hub on an Android phone. It plays the feeds that a paired device shares, each in a window of its own, and shares windows, displays, cameras and Spout senders of this PC with paired devices.

The protocol it speaks is described in [../docs/NETWORK.md](../docs/NETWORK.md), and the code is described in [../docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md).

## Requirements

- 64-bit Windows. Sharing the sound of one application needs Windows 10 build 20348 or later; on older builds windows are shared without sound. Hiding the mouse pointer needs Windows 10 version 2004 or later.
- To build it: Flutter (CI uses 3.44 stable) and Visual Studio with the "Desktop development with C++" workload.

To install a released build, unzip `manifold-hub-windows-v<version>.zip` from the [releases page](https://github.com/yuredazo/manifold/releases) into a folder you can write to and run `manifold_hub.exe`. The build is not code signed, so SmartScreen may warn the first time.

## Build and run

```
flutter pub get
flutter test
flutter run -d windows
flutter build windows --release
```

The first configure downloads the Spout 2.007.017 sources (a pinned archive, checked by SHA-256), so it needs internet access. The release build is in `build/windows/x64/runner/Release`, and its `licenses` folder carries Spout's BSD 2-Clause license.

## Pairing with a phone

1. On the phone, open Devices in the Manifold hub, tap Pair a device and choose "Let another device pair with this one".
2. In this app, open Devices and click "Pair a device". The phone appears in the list; click it. If it does not appear, choose "Enter its address" and type the address the phone shows under Accept connections.
3. Both screens show a six digit code. Confirm it on both.

The same steps work the other way round. Once paired, the two devices find each other again on their own, as long as both have Accept connections on and the address has not changed. The switch is remembered across restarts.

Click a paired device to open its settings. These switches are there, and all start off.

| Switch | Effect |
| --- | --- |
| Watch its feeds | What the device shares appears on the Watch page. |
| Let it watch my windows | The device can watch the windows and displays you share on the Share page. |
| Let it watch my camera | The device can watch cameras you share. Separate from the windows switch. |
| Let it watch my Spout senders | The device can watch Spout senders you share. Separate from the other two. |

Unpair removes the device and asks for confirmation first. The device has to be paired again before it can connect.

## Watching

The Watch page lists the feeds of every paired device that is online and that you chose to watch. Click one to open it in a window of its own, and click it again, or close the window, to stop. Closing the window ends the stream on the other device. A feed that is only sound plays without a window.

## Sharing

On the Share page, "Share" opens a picker with the open windows, the connected displays, and the cameras and Spout senders that are available.

- A window is shared with the sound of its application, if Windows allows it.
- A display is shared with the sound of everything the PC plays except Manifold.
- The Picture chip, when switched off, shares only the sound of a window or of the whole PC, which another hub can play.
- The Pointer chip, when switched off, leaves the mouse pointer out of the picture of a window or display.
- A camera is shared without sound. Cameras are hidden from the picker until "Offer cameras in the Share list" is switched on in About, and switching it off again withdraws any that are shared. A camera is opened only while a device watches it, and Windows must allow desktop apps to use the camera.
- Programs that output through Spout (TouchDesigner, Resolume and others) appear as Spout senders, without sound, and are read only while a device watches them.

A feed is captured only while a device is watching it. Shares are remembered in `%APPDATA%\Manifold\shares.json`. After a restart, or when a window closes and opens again, the share finds its window the way OBS does: same program, then the exact title, then any window of the same type. Until one is found the share shows "waiting for the window".

"Stop all sharing", on the Share page and in the tray menu, withdraws every feed, ends every stream and stops every capture. The shares stay listed and Resume offers them again. It is not remembered across a restart. The Share page also lists recent activity, such as which device started or stopped watching what.

## Closing, the tray and startup

Closing the window exits the app. In About, "Keep running in the tray when the window is closed" hides the window instead, so the hub keeps listening for paired devices. A click on the tray icon shows the window again. A right click offers Stop all sharing (Resume sharing once stopped) and Exit.

"Start with Windows, hidden in the tray" adds `manifold_hub.exe --hidden` to the per-user Run key, so the hub starts at sign-in with no window and a tray icon. It counts as on only while the entry points at this copy of the app.

Only one hub runs per Windows session, because two would compete for UDP port 47200. Starting a second one brings the first window forward, even from the tray.

## Updates

About checks GitHub for a newer release and can install it. How that works is in the [main README](../README.md#updates). The updater needs the folder the app runs from to be writable.

## Files

Everything the app stores is in `%APPDATA%\Manifold`: the paired devices (`devices.txt`), the shares (`shares.json`), the switches from About, and the identity key (`identity.key`), which is encrypted with Windows data protection for the signed-in user. Other programs running as the same user can read the other files.
