# Manifold hub for Windows

A Flutter app for Windows that pairs with the Manifold hub on an Android phone. It plays the feeds a paired device shares, each in a window of its own, and shares windows of this PC with paired devices, with the sound of the application if Windows allows it.

## Run

You need Flutter (CI uses 3.44 stable) and Visual Studio with the "Desktop development with C++" workload.

```
flutter pub get
flutter test
flutter run -d windows
flutter build windows --release
```

The release build is in `build/windows/x64/runner/Release`. Capturing the sound of one application needs Windows 10 build 20348 or later; on older builds windows are shared without sound.

## Pairing with a phone

On the phone, open Devices in the Manifold hub, switch on Accept connections and tap Allow pairing. In this app, open Devices, switch on Accept connections, choose "Pair with a device" and type the address the phone shows. Both screens show a six digit code; confirm it on both. Then switch on "Watch its feeds" to see what the phone shares on the Watch page, and "Let it watch my windows" to let it see windows you share on the Share page. Unpair, in the menu of a device, asks for confirmation first.

Shared windows are remembered in `%APPDATA%\Manifold\shares.json`. After a restart, or when a window closes and opens again, the share finds its window the way OBS does: same program, then the exact title, then any window of the same type. Until one is found the share shows "waiting for the window".

## Closing, the tray and updates

Closing the window exits the app. In About, "Keep running in the tray when the window is closed" hides the window instead, so the hub keeps listening for paired devices. A click on the tray icon shows the window again, and right click then Exit quits. Only one hub runs per Windows session, since two would compete for the same UDP port; starting a second one brings the first window forward, even from the tray.

About also checks GitHub for a newer release and can install it. How that works is in the [main README](../README.md#updates). The updater needs the folder the app runs from to be writable. Both switches are stored in `%APPDATA%\Manifold`.

## Layout

`lib/net` is a Dart port of the Kotlin `net` package in `hub/`. The two are tied together by `test/fixtures/compat.txt`, which holds bytes the Kotlin code produced and that both test suites check. `windows/runner` is the native side: window capture, the H.264 and AAC encoders, and the player windows. The protocol is described in [../docs/NETWORK.md](../docs/NETWORK.md).
