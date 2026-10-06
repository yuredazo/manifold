# Manifold

Manifold lets one Android app send live video, and optionally audio, to another app on the same phone. The receiving app hands over a `Surface` and the sending app draws straight into it, so there is no capture and no encoding step between the two.

A small hub app introduces the two sides and lets the phone's owner decide which apps may receive. The hub never sees a frame. If it is killed or updated, the feeds keep running and the SDK reconnects on its own.

Hubs on different devices, Android phones and a Windows PC, can also pair and share feeds over an encrypted network link. A feed from another device shows up to local apps as an ordinary sender.

Requirements: Android 7.0 (API 24) or later for the SDK and the hub, 64-bit Windows for the PC hub. Tested on two Android 14 phones and on Windows 11.

## What is in this repository

| Directory | What it is |
| --- | --- |
| `sdk` | The library apps depend on: `ManifoldSender`, `ManifoldReceiver` and the AIDL interface. |
| `hub` | The Android hub app (`dev.mkzk.manifold`). It lists what is live, decides which apps may receive, pairs with other devices and can share the phone's screen as a feed. |
| `desktop` | The Windows hub, a Flutter app with a native capture and encoding runner. It pairs with the Android hub over the network. |
| `probe` | A test app that exercises the SDK across processes. |
| `docs` | Protocol, network and architecture notes. |

## Install

Download the hub for your platform from the [releases page](https://github.com/yuredazo/manifold/releases).

- Android: `manifold-hub-v<version>.apk`. Open it on the phone and allow installs from the app you opened it with when Android asks. Apps that use Manifold work only while the hub is installed.
- Windows: `manifold-hub-windows-v<version>.zip`. Unzip it anywhere you can write to and run `manifold_hub.exe`. The build is not code signed, so Windows SmartScreen may warn the first time.

Both hubs can check for newer releases themselves, see [Updates](#updates).

## How it works

1. A sender announces a named feed to the hub, for example `my-avatar`.
2. A receiver lists the announced senders and subscribes to one by name. It passes the `Surface` it wants filled and the size it wants.
3. The first time an app subscribes, the hub holds the request and notifies the phone's owner, who allows or blocks that app in the hub. Once the app is allowed, the hub hands the `Surface` to the sender. The sender draws into it with OpenGL, a `MediaCodec` decoder, a `Canvas`, or anything else that can draw to a `Surface`.
4. When the receiver unsubscribes or goes away, the sender is told to stop.

A subscription can be made before the sender exists. It waits and is delivered when a sender with that name appears, and a sender that restarts gets the same surface again. Several receivers can watch one sender: the sender gets one surface per receiver and draws into each of them.

## Using the SDK

There is no public Maven artifact yet. To use the SDK, publish it to a local repository:

```
./gradlew :sdk:publishReleasePublicationToLocalRepository
```

This writes `dev.mkzk.manifold:manifold-sdk:1.1.0` to `sdk/build/repo`. Add that folder as a Maven repository in the app that depends on it, or include the `sdk` module as a Gradle project.

### Sending

```kotlin
val sender = ManifoldSender(
    context,
    ManifoldSender.Config(name = "my-avatar", width = 720, height = 1080, fps = 30),
    object : ManifoldSender.Listener {
        override fun onSubscribe(subscription: ManifoldSender.Subscription) {
            // Draw into subscription.surface at subscription.width x subscription.height.
            // Release the surface and the audio sink when the subscription ends.
        }

        override fun onUnsubscribe(subscriptionId: String) {
            // Stop drawing for this subscription.
        }
    },
)
sender.start()

// When the feed should disappear:
sender.stop()
```

If `Config.hasAudio` is set, `Subscription.audioSink` is a pipe to write 48 kHz, stereo, signed 16-bit little-endian PCM into. It is null when the receiver does not want audio.

A feed that is only sound sets `Config(name, hasAudio = true, soundOnly = true)`. Its subscribers still pass a surface, which is never drawn into, and receivers that understand the flag play only the sound. `soundOnly` needs `hasAudio` and has been in the SDK since 1.1.0.

Run the sender inside a foreground service. Android can wait about 20 seconds before it restarts a killed background service (seen on an Android 14 phone), which is a long gap in a live feed.

### Receiving

```kotlin
val receiver = ManifoldReceiver(context, object : ManifoldReceiver.Listener {
    override fun onSenders(senders: List<SenderInfo>) {
        // The full list of announced senders, sent on every change.
    }
})
receiver.start()

val subscription = receiver.subscribe("my-avatar", surface, width = 720, height = 1080)

// Later:
receiver.unsubscribe(subscription)
receiver.stop()
```

The receiver keeps ownership of the surface and audio pipe it passes in and releases them after `unsubscribe`.

Nothing is delivered until the phone's owner allows the app in the hub. Override `Listener.onAccessChanged` to learn where a subscription stands (`PENDING`, `ALLOWED` or `BLOCKED`) so you can tell the person to approve it in Manifold.

### Rules that apply to both

- `start()` can be called once per instance. A second call throws `IllegalStateException`. `stop()` can be called any number of times.
- Listener callbacks run on the main thread unless you pass an `Executor` to the constructor.
- Sender names are 1 to 64 characters with no control characters. `Manifold.isValidName` checks one.
- Bad arguments throw `IllegalArgumentException` instead of failing later.

The public API is recorded in `sdk/api/sdk.api` and checked in CI. The wire protocol and the hub's behavior are described in [docs/PROTOCOL.md](docs/PROTOCOL.md).

## Between devices

Hubs on different devices pair once, with a six digit code shown on both screens, and then reconnect on their own. Each paired device has switches for what it may receive from you and what you may receive from it. Video is H.264 and audio is AAC. Both travel encrypted over UDP on port 47200, with resends for lost packets and a playout delay that follows the link.

While you pair, devices on the same network that are open for pairing show up in a list, so there is nothing to type. There is no relay and no discovery beyond the local network: to pair across networks you type the other device's address, and to reach a hub from outside its network you forward UDP port 47200 to it. [docs/NETWORK.md](docs/NETWORK.md) describes the protocol, how loss and delay are handled, measurements, and what is not done yet. The Windows hub is described in [desktop/README.md](desktop/README.md).

## Updates

Both hubs can look for a newer release on GitHub. About has a "Check for updates on launch" switch, on by default, and a button to check at any time. A check is one request to `api.github.com/repos/yuredazo/manifold/releases/latest`, and GitHub allows 60 anonymous requests an hour per IP address. Nothing is installed until you press Update.

The Android hub downloads `manifold-hub-v<version>.apk` into an install session and hands it to Android's installer, which asks you to confirm. The first time, Android also asks you to allow installs from Manifold. Android refuses an update signed with a different key, so a build you made yourself cannot be replaced from a release. The Windows hub downloads `manifold-hub-windows-v<version>.zip`, unpacks it, exits, copies the files over its own folder and starts again, so that folder has to be writable.

Both hubs compare the download with the SHA-256 that GitHub lists for the file and ignore a release whose file has none. Both refuse download links outside `github.com/yuredazo/manifold/releases/download/`. That shows the file is the one on the release. It does not show who published it, and the Windows build is not code signed.

## Security

Any app can bind to the hub and announce a feed, but an app receives video only after the phone's owner has allowed it in the hub. The hub works out who is calling from the system, not from what the caller says. A decision belongs to the app and the certificate it is signed with, so a different build of the same package is asked about again.

An allowed app can watch every feed, and a sender cannot refuse one receiver or see which app it is. The names of announced feeds are visible to any app until it is blocked. The details and the other limits are in [docs/PROTOCOL.md](docs/PROTOCOL.md). To report a vulnerability, see [SECURITY.md](SECURITY.md).

## Documentation

| Document | Contents |
| --- | --- |
| [docs/PROTOCOL.md](docs/PROTOCOL.md) | The AIDL interface, the hub's behavior and limits, and the security model. |
| [docs/NETWORK.md](docs/NETWORK.md) | Pairing, packet formats, video and audio delivery, measurements, known gaps. |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | How the source is organized and how the parts are wired together. |
| [desktop/README.md](desktop/README.md) | Building and using the Windows hub. |
| [CONTRIBUTING.md](CONTRIBUTING.md) | Building, testing, changing the API, signing and releases. |
| [SECURITY.md](SECURITY.md) | How to report a vulnerability. |

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for how to build and test each part.

## License

Apache License 2.0. See [LICENSE](LICENSE).
