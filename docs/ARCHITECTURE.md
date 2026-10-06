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
| `desktop` | The Windows hub: a Flutter app in `lib`, native code in `windows/runner`. |
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
| `about` | The About screen and its update section. |
| `notify` | Notification channel setup shared by the services. |
| `ui` | The rows, groups, icon badge and switch row that every screen is built from, so type sizes and spacing are defined once. |

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

The Dart code is in `desktop/lib`, one folder per feature. Tests are under `desktop/test` and mirror it.

| Folder | What it does |
| --- | --- |
| `network` | `Network` owns the UDP socket, the timers, pairing and the paired devices, and tells features what happens. `Discovery` finds devices that are open for pairing. Also the Devices page and the pairing dialog. |
| `network/protocol` | A Dart port of `network.protocol` in the Android hub. |
| `network/stream` | The parts of `network.stream` the Windows hub uses: bitrate control and the store of recent fragments for resends. |
| `share` | `Sharing` offers windows, displays, cameras and Spout senders to other devices. The Share page, saved shares and the matching of a saved share to an open window are here. |
| `watch` | `Watching` and `ViewerSession` play feeds from other devices, each in a native window drawn by libmpv. The Watch page is here. |
| `update` | The updater. |
| `app` | The window, navigation, tray, settings and the About page. `Hub` holds the objects `main.dart` builds. |
| `core` | The monotonic clock, the app data folder, Windows data protection and the page frame widget. |

Receive-side buffering for video is in `watch/viewer.dart`, and playout smoothing is left to libmpv. The Android hub has its own versions of both in `network/stream`.

### Wiring

`main.dart` is the only place that connects `Network`, `Sharing` and `Watching`. `Sharing` and `Watching` mix in `NetworkFeature`, which has an empty method for each thing `Network` can report: a timer tick, a link going down, a subscribe request, a video fragment and so on. `Network` calls every feature it was given. A feature that needs to send something gets a function for it when it is built, for example `Sharing(sendVideo: ...)`. `Network` itself does not import `share` or `watch`.

### Dependency rules

- `core` imports no feature.
- `network` imports only `core`.
- `share` and `watch` import `network` and `core`, and never each other.
- `app` imports every feature and is the only folder that does.

### Native runner

`windows/runner` holds C++ for what Dart cannot do. The files at its root (`main.cpp`, `flutter_window`, `win32_window`, `utils`, `Runner.rc`, the manifest) come from the Flutter template and should stay as they are. Our code is in four folders.

| Folder | Contents |
| --- | --- |
| `capture` | `CaptureHost`, the method channel `manifold/capture` that Dart's `Sharing` talks to, and the sources it starts: window, display, camera and Spout capturers. Each is a `FrameSource` that submits finished NV12 pictures to a `FramePump`, which encodes the latest one at up to 30 frames a second and repeats it while the source is still. Window and Spout capture scale and convert color on the GPU with `Nv12Converter`. |
| `audio` | Capturing one application's sound with process loopback. |
| `encode` | The H.264 and AAC encoders, on top of Media Foundation transforms. |
| `viewer` | `WindowHost`, the method channel `manifold/windows` that opens and closes the native player windows. |

## The two protocol implementations

The Kotlin and Dart `protocol` packages implement the same packet format, written separately. `desktop/test/fixtures/compat.txt` holds bytes the Kotlin code produced. A Kotlin test and a Dart test both compare their output with it, so the two implementations cannot drift apart without a failing test. `noise_vectors.txt`, in the same folder, holds the official Noise test vectors that both handshake implementations are checked against.

## Adding a feature

Android hub: create a package. Take what it needs as constructor parameters instead of reaching for a global. If the system creates part of it (a service, a receiver), declare it in `AndroidManifest.xml` with its full package path and read the graph with `context.hubGraph`. Create the feature in `HubGraph` and pass it to the screens from `HubShell`.

Windows hub: create a folder under `lib`. If the feature needs events from the network, mix in `NetworkFeature` and override what it needs. Build it in `main.dart`, add it to the list given to `Network`, add it to `Hub` if a page needs it, and build the page in `app/app.dart`.
