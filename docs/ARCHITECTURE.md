# Architecture

How the source is organized, how the parts are wired together, and where new code belongs. What the hubs do is in [PROTOCOL.md](PROTOCOL.md) (apps and the hub on one phone) and [NETWORK.md](NETWORK.md) (hubs on different devices).

## Data flow

On one phone the hub only introduces the two apps. Once a subscription is delivered, the sender draws into the receiver's `Surface` through the system's buffer queue, and no frame passes through the hub.

```text
sender app --draws into--> Surface owned by the receiver app
                 ^
                 | the hub hands the Surface to the sender, then steps aside
```

Between devices the hub is in the stream. To publish a local feed it subscribes to it as a receiver of its own, with the `Surface` of an H.264 encoder, and sends the encoder's output over UDP. To receive, it registers the remote feed as a sender, so a local app subscribes to it like any other, and decodes into that app's `Surface`.

```text
sender app -> encoder Surface (hub A) -> UDP -> decoder (hub B) -> receiver app's Surface
```

## Repository layout

| Directory | Contents |
| --- | --- |
| `sdk` | The Android library: `Manifold`, `ManifoldSender`, `ManifoldReceiver`, the internal `ManifoldConnection` that keeps the hub binding alive, and the AIDL files. |
| `hub` | The Android hub app. |
| `desktop` | A Cargo workspace with the protocol crate and the Windows and Linux hub. Its native Windows capture code is C++. |
| `fixtures` | Bytes the Android hub produces and the official Noise test vectors. The Kotlin and the Rust tests both read them. |
| `probe` | The cross-process test app. It depends only on the SDK. |
| `docs` | Protocol, network and architecture notes. |

## Android hub

The code is in `hub/src/main/kotlin/dev/mkzk/manifold/hub/`, one package per feature. Tests are under `hub/src/test` in the same packages.

| Package | What it does |
| --- | --- |
| `app` | `HubApp`, `HubGraph`, the activity, the tab shell and the theme. |
| `broker` | The service apps bind to and the `Registry` behind it (senders, receivers, subscriptions, limits), the access book (Ask, Allowed or Blocked per app) and the notification for pending requests. The Apps tab is in `broker/ui`. |
| `live` | The Live tab and the full-screen feed preview. `live/control` holds the remote control on top of a preview: mouse, keyboard, d-pad, gamepad and the on-screen game buttons with their editor. |
| `network` | The link to other devices. `Network` owns the socket and its thread and routes events to `NetworkPublisher` (feeds this device sends) and `RemoteFeeds` (feeds it receives). `NetworkService` keeps it alive in the background and `Discovery` finds devices open for pairing. The Devices tab is in `network/ui`. |
| `network/protocol` | Noise handshakes, sessions, packet formats, control messages, the paired-device list and `Endpoint`, the state machine that ties them together. No Android code. |
| `network/stream` | Media on top of the protocol: rebuilding frames and asking for missing pieces, bitrate control, the playout clock and stream statistics. |
| `network/codec` | `MediaCodec` wrappers for H.264 and AAC, the alpha packing passes, the audio pacer and the sample clock. |
| `gl` | The EGL context that the alpha passes and the screen share draw with, and `SurfaceFanout`, which draws one picture into each watcher's surface. |
| `screen` | Sharing the phone's screen as a feed: capture, sound capture and the foreground service. |
| `update` | Finding a release, downloading it and handing it to the system installer. |
| `settings` | The Settings tab, its update card, About and the acknowledgements. |
| `notify` | Notification channel setup shared by the services. |
| `ui` | The panels, rows, sheets and icon badges every screen is built from, so sizes and spacing are defined once. |

`HubApp.onCreate` builds one `HubGraph`, which creates the `Registry`, `Network`, `Updater` and `ScreenShare` and hands each what it needs. Components the system creates (services, receivers, the activity) reach the graph with `context.hubGraph`. Everything else gets its dependencies as constructor or composable parameters, so a class can be built in a unit test without the app.

Dependency rules, which the compiler does not enforce because `internal` is per module, so check imports in review:

- `broker` imports only `app` (for `hubGraph`), `notify` and `ui`. `network`, `screen` and `live` use the `Registry` the way an app does, by registering senders and receivers.
- `network/protocol` imports no other package and no Android class. `stream` and `codec` import `protocol`.
- Only `app` imports `live`, `settings` and the tab screens.

The `Registry` takes one lock for every change and publishes a snapshot as a `StateFlow` that the screens read. `Network` runs on its own `HandlerThread` and is the only code that touches its `Endpoint` and the device list. Encoders and decoders have their own threads and hand results to the network thread.

## Desktop hub

`desktop` is a Cargo workspace. `protocol` (crate `manifold-protocol`) holds the network protocol and its tests. `hub` (crate `manifold-hub`, executable `manifold_hub`) is the app for Windows and Linux.

| Path | What it does |
| --- | --- |
| `protocol/src` | A Rust port of the Android hub's `network/protocol` and `network/stream`. `Endpoint` does no I/O: it is fed datagrams and ticks, and queues datagrams to send and events to report. |
| `hub/src/hub.rs` | `Hub`, the network thread. It owns the UDP socket, the `Endpoint`, pairing, `Sharing`, `Watching`, `Discovery` and the settings, and publishes a `Snapshot` for the window. |
| `hub/src/sharing.rs` | Offers windows, displays, cameras and Spout senders to other devices, saves the shares and finds a saved share again. |
| `hub/src/capture` | One worker thread for capture calls. `windows.rs` calls the C++ in `native/bridge.cpp`; `linux` grabs X11 with MIT-SHM and encodes with FFmpeg. |
| `hub/src/remote` | Applies a phone's mouse, keyboard and controller input: `SendInput` and ViGEmBus on Windows, XTEST and uinput on Linux. |
| `hub/src/watching.rs`, `hub/src/player` | Rebuild frames and play each feed in its own window with Media Foundation decoders, Direct3D 11 and WASAPI. Windows only; `no_player.rs` stands in on Linux. |
| `hub/src/secure.rs`, `hub/native/secure` | The optional SYSTEM service and helper that show UAC prompts and the lock screen. |
| `hub/src/ui.rs`, `hub/ui` | The Slint window. `ui.rs` turns a `Snapshot` into window properties and window callbacks into commands. |
| `hub/src/system` | Per-platform helpers: addresses, HTTPS, clipboard, start at login, elevation. `x11.rs` is the shared X11 connection on Linux. |
| `hub/src/store.rs`, `tray.rs`, `update.rs`, `discovery.rs` | App data and the identity key, the Windows tray icon, the updater and the pairing beacon. |
| `hub/native` | C++ capture and encoding for Windows: window, display, camera and Spout capturers, each a `FrameSource` that hands NV12 pictures to a `FramePump`, which encodes the newest one at up to the share's frame rate and repeats it while the source is still. Window and Spout capture scale and convert color on the GPU with `Nv12Converter`. Sound of one application comes from process loopback. H.264 and AAC use Media Foundation transforms. |

The window runs on the main thread and the network on its own. Everything that reaches the network thread arrives on one channel of `Input`: datagrams from a receive thread, commands from the window, encoded frames from capture, and results from the updater and address lookups. Only the network thread touches the `Endpoint`, `Sharing` and `Watching`. Each player window has its own thread and gets frames on a channel.

## The two protocol implementations

The Kotlin `network/protocol` package and the Rust `protocol` crate implement the same packet format, written separately. `fixtures/compat.txt` holds bytes the Kotlin code produced, and a Kotlin test and a Rust test both compare their output with it, so the two cannot drift apart without a failing test. Both check their handshakes against `fixtures/noise_vectors.txt`, the official Noise test vectors.

## Adding a feature

Android hub: create a package and take what it needs as constructor parameters, not from a global. If the system creates part of it, declare it in `AndroidManifest.xml` with its full package path and read the graph with `context.hubGraph`. Build it in `HubGraph` and pass it to the screens from `HubShell`.

Desktop hub: create a module under `hub/src`. If it needs network events, give `Hub` a field for it and call it from `Hub::event`. Put what the window shows into `Snapshot`, and turn window callbacks into `Command`s in `ui.rs`.
