# Architecture

This page describes how the source is organized, how the parts are wired together, and where new code belongs. For what the hub does, see [PROTOCOL.md](PROTOCOL.md) (apps and the hub on one phone) and [NETWORK.md](NETWORK.md) (hubs on different devices).

## Data flow

On one phone the hub only introduces the two apps. After a subscription is delivered, the sender draws into the receiver's `Surface` through the system's buffer queue, and no frame passes through the hub.

```
sender app --draws into--> Surface owned by the receiver app
                 ^
                 | the hub hands the Surface to the sender, then steps aside
```

Between devices the hub takes part in the stream. To publish a local feed, it subscribes to that feed as a receiver of its own, with the `Surface` of an H.264 encoder, and sends the encoder's output over UDP. To receive, it registers the remote feed as a sender, so a local app subscribes to it like any other, and decodes into that app's `Surface`.

```
sender app -> encoder Surface (hub A) -> UDP -> decoder (hub B) -> receiver app's Surface
```

## Repository layout

| Directory | Contents |
| --- | --- |
| `sdk` | The Android library: `Manifold`, `ManifoldSender`, `ManifoldReceiver`, an internal `ManifoldConnection` that keeps the hub binding alive, and the AIDL files. Its public API is locked by `sdk/api/sdk.api`. |
| `hub` | The Android hub app. |
| `windows` | The Windows hub: a Cargo workspace with the protocol crate and the app, whose native capture code is C++. |
| `fixtures` | Bytes the Android hub produces and the official Noise test vectors, read by the Kotlin and the Rust tests. |
| `probe` | The cross-process test app. It depends only on the SDK. |
| `docs` | Protocol, network and architecture notes. |

## Android hub

The code is in `hub/src/main/kotlin/dev/mkzk/manifold/hub/`, one package per feature. Tests are under `hub/src/test` in the same package names.

| Package | What it does |
| --- | --- |
| `app` | `HubApp`, `HubGraph`, the activity, the tab shell and the theme. |
| `broker` | The service apps bind to and the `Registry` behind it: senders, receivers, subscriptions, limits. Also the access book (Ask, Allowed, Blocked per app), the notification for pending requests, and the Live and Apps screens, with the full-screen feed preview, in `broker/ui`. |
| `network` | The link to other devices. `Network` owns the socket and its thread and routes events to `NetworkPublisher` (feeds this device sends) and `RemoteFeeds` (feeds it receives). `NetworkService` keeps it alive in the background, and `Discovery` finds devices that are open for pairing. The Devices screen is in `network/ui`. |
| `network/protocol` | Noise handshakes, sessions, packet formats, control messages, the paired-device list and `Endpoint`, the state machine that ties them together. It contains no Android code. |
| `network/stream` | What sits on top of the protocol for media: rebuilding frames and asking for missing pieces, bitrate control, the playout clock and stream statistics. |
| `network/codec` | `MediaCodec` wrappers for H.264 and AAC, the audio pacer and the sample clock. |
| `screen` | Sharing the phone's screen as a feed: capture, OpenGL drawing into each watcher's surface, sound capture, and the foreground service. |
| `update` | Looking for a release, downloading it and handing it to the system installer. |
| `settings` | The Settings screen and its update card. |
| `notify` | Notification channel setup shared by the services. |
| `ui` | The panel, rows, icon badge, switch row and sheet that every screen is built from, so type sizes and spacing are defined once. |

### Wiring

`HubApp.onCreate` builds one `HubGraph`, which creates the `Registry`, `Network`, `Updater` and `ScreenShare` and gives each the objects it needs. Android components that the system creates (services, receivers, the activity) get the graph with `context.hubGraph`. Everything else receives its dependencies as constructor or composable parameters, so a class can be created in a unit test without the app.

### Dependency rules

- `broker` does not import `network`, `screen`, `update` or `about`. `network` and `screen` depend on `broker` and use the `Registry` the way an app does, by registering senders and receivers with it.
- `about` imports `update`. Nothing imports `about`.
- `network/protocol` imports no other package of the hub and no Android classes. `stream` and `codec` import `protocol`.
- Packages import `app` only to reach `hubGraph` or `HubActivity` from a component the system creates.

Kotlin's `internal` visibility is per module, so the compiler does not enforce these rules. Check the imports in review.

### Threads

The `Registry` takes one lock for every change and publishes a snapshot of its state as a `StateFlow` that the screens read. `Network` runs on its own `HandlerThread` and is the only code that touches its `Endpoint` and the device list. Encoders and decoders have their own threads and hand results to the network thread.

## Windows hub

The Windows hub is a Cargo workspace in `windows`. `protocol` (crate `manifold-protocol`) holds the network protocol and has the tests. `hub` (crate `manifold-hub`, executable `manifold_hub.exe`) is the app.

| Path | What it does |
| --- | --- |
| `protocol/src` | A Rust port of `network.protocol` and `network.stream` in the Android hub. `Endpoint` holds the handshakes, sessions and links and does no I/O: it is fed datagrams and ticks, and it queues datagrams to send and events to report. |
| `hub/src/hub.rs` | `Hub`, the network thread. It owns the UDP socket, the `Endpoint`, the paired devices, pairing, `Sharing`, `Watching`, `Discovery` and the settings, and publishes a `Snapshot` for the window. |
| `hub/src/sharing.rs` | Offers windows, displays, cameras and Spout senders to other devices, saves the shares and matches a saved share to an open window. |
| `hub/src/capture.rs` | Talks to the C++ capture code through `native/bridge.cpp`, on one worker thread of its own. |
| `hub/src/watching.rs`, `hub/src/player` | Rebuild frames from fragments and play each feed in a window of its own: Media Foundation decoders, Direct3D 11 drawing and WASAPI sound. |
| `hub/src/ui.rs`, `hub/ui` | The Slint window. `ui.rs` turns a `Snapshot` into the window's properties and its callbacks into commands for the network thread. |
| `hub/src/store.rs`, `system.rs`, `tray.rs`, `update.rs`, `discovery.rs` | App data and the identity key, Windows helpers (addresses, HTTPS, clipboard, the Run key), the tray icon, the updater and the pairing beacon. |
| `hub/native` | C++ for capture and encoding: window, display, camera and Spout capturers, each a `FrameSource` that submits NV12 pictures to a `FramePump`, which encodes the latest one at up to the share's frame rate and repeats it while the source is still. Window and Spout capture scale and convert color on the GPU with `Nv12Converter`. Sound of one application is taken with process loopback. H.264 and AAC are encoded with Media Foundation transforms. |

### Threads

The window runs on the main thread and the network on its own. Everything that reaches the network thread arrives on one channel of `Input`: datagrams from a receive thread, commands from the window, encoded frames from the capture threads, and results from the updater and the address lookups. The network thread is the only code that touches the `Endpoint`, `Sharing` and `Watching`. Each player window has a thread of its own and is sent frames on a channel.

## The two protocol implementations

The Kotlin `protocol` package and the Rust `protocol` crate implement the same packet format, written separately. `fixtures/compat.txt` holds bytes the Kotlin code produced. A Kotlin test and a Rust test both compare their output with it, so the two implementations cannot drift apart without a failing test. `fixtures/noise_vectors.txt` holds the official Noise test vectors that the Rust handshake is checked against.

## Adding a feature

Android hub: create a package. Take what it needs as constructor parameters instead of reaching for a global. If the system creates part of it (a service, a receiver), declare it in `AndroidManifest.xml` with its full package path and read the graph with `context.hubGraph`. Create the feature in `HubGraph` and pass it to the screens from `HubShell`.

Windows hub: create a module under `hub/src`. If it needs events from the network, give `Hub` a field for it and call it from `Hub::event`. Put what the window needs to show into `Snapshot`, and turn the window's callbacks into `Command`s in `ui.rs`.
