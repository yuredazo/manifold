# Manifold

Manifold lets one Android app send live video, and optionally audio, to another app on the same phone. The receiving app hands over a `Surface` and the sending app draws straight into it, so nothing is captured or encoded in between.

A small hub app introduces the two sides and lets the phone's owner decide which apps may receive. The hub never sees a frame, and if it is killed or updated the feeds keep running while the SDK reconnects. Hubs on different devices (Android phones, a Windows or Linux PC) can also pair and share feeds over an encrypted network link, and a feed from another device shows up to local apps as an ordinary sender.

Requirements: Android 7.0 (API 24) for the SDK, Android 10 (API 29) for the hub, 64-bit Windows 10 or 11 or an X11 Linux desktop for the PC hub. Tested on Android 14 and Android 16 phones, Windows 11 and Ubuntu 22.04.

## Install

Download the hub from the [releases page](https://github.com/yuredazo/manifold/releases).

- Android: `manifold-hub-v<version>.apk`. Open it on the phone and allow installs from the app you opened it with. Apps that use Manifold work only while the hub is installed.
- Windows: `manifold-hub-windows-v<version>.zip`. Unzip it into a folder you can write to and run `manifold_hub.exe`. The build is not code signed, so SmartScreen may warn the first time.
- Linux: build it from source, see [desktop/README.md](desktop/README.md#linux).

## How it works

1. A sender announces a named feed to the hub, for example `my-avatar`.
2. A receiver lists the senders and subscribes to one by name, passing the `Surface` it wants filled and the size it wants.
3. The first time an app subscribes, the hub holds the request and notifies the owner, who allows or blocks the app in the hub. Once allowed, the hub hands the `Surface` to the sender, which draws into it with OpenGL, a `MediaCodec` decoder, a `Canvas` or anything else that draws to a `Surface`.
4. When the receiver unsubscribes or goes away, the sender is told to stop.

A subscription can be made before the sender exists; it is delivered when a sender with that name appears, and a sender that restarts gets the same surface again. Several receivers can watch one sender, which then gets one surface per receiver.

## Using the SDK

There is no public Maven artifact yet. Publish the SDK to a local repository:

```sh
./gradlew :sdk:publishReleasePublicationToLocalRepository
```

This writes `dev.mkzk.manifold:manifold-sdk:1.1.0` to `sdk/build/repo`. Add that folder as a Maven repository, or include the `sdk` module as a Gradle project.

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
sender.stop() // when the feed should disappear
```

- With `Config.hasAudio`, `Subscription.audioSink` is a pipe for 48 kHz stereo signed 16-bit little-endian PCM. It is null when the receiver does not want audio.
- A sound-only feed sets `Config(name, hasAudio = true, soundOnly = true)` (SDK 1.1.0 and later). Subscribers still pass a surface, which is never drawn into.
- A feed with transparent pixels sets `hasAlpha = true`. On one phone nothing changes, since the receiver's surface takes whatever alpha is drawn; across the network it makes the hub keep the transparency (see [NETWORK.md](docs/NETWORK.md#transparency)). Draw premultiplied color, which is what Android surfaces hold.
- Run the sender in a foreground service. Android can take about 20 seconds to restart a killed background service (seen on an Android 14 phone).

### Receiving

```kotlin
val receiver = ManifoldReceiver(context, object : ManifoldReceiver.Listener {
    override fun onSenders(senders: List<SenderInfo>) {
        // The full list of announced senders, on every change.
    }
})
receiver.start()
val subscription = receiver.subscribe("my-avatar", surface, width = 720, height = 1080)
receiver.unsubscribe(subscription)
receiver.stop()
```

The receiver owns the surface and audio pipe it passes in and releases them after `unsubscribe`. Nothing is delivered until the owner allows the app; override `Listener.onAccessChanged` to learn whether a subscription is `PENDING`, `ALLOWED` or `BLOCKED`, so you can ask the person to approve it in Manifold.

### Rules for both

- `start()` can be called once per instance; a second call throws `IllegalStateException`. `stop()` can be called any number of times.
- Listener callbacks run on the main thread unless you pass an `Executor` to the constructor.
- Sender names are 1 to 64 characters with no control characters. `Manifold.isValidName` checks one.
- Bad arguments throw `IllegalArgumentException` at once.

The public API is listed in `sdk/api/sdk.api`. The wire protocol and the hub's behavior are in [PROTOCOL.md](docs/PROTOCOL.md).

## Between devices

Hubs pair once by comparing a six digit code shown on both screens, then reconnect on their own. Devices on the same network that are open for pairing appear in a list; across networks you type the other device's address, and to reach a hub from outside its network you forward UDP port 47200 to it. There is no relay. Each paired device has switches for what it may watch and what you watch from it. Video is H.264 and audio AAC, both encrypted over UDP with resends for lost packets and a playout delay that follows the link. Details are in [NETWORK.md](docs/NETWORK.md) and [desktop/README.md](desktop/README.md).

## Remote control

A paired phone can control a window or display the PC shares. In the PC hub, open the device in Devices and switch on "Control PC" under "It can watch" (needs "Windows and displays"). The Share page lists who can control what, with a Stop button. On the phone, open the feed and tap the mouse button in the bottom bar; a rail beside the picture switches between pointer and game mode, opens the keyboard and stops control.

- Pointer mode: one finger moves the pointer under a magnifier, and a second finger clicks, holds for a right click, drags or scrolls. Touch mode, from the rail, clicks where you tap.
- Game mode turns on by itself when a game holds the cursor. A drag turns the camera and a pinch zooms. A thumb in the bottom-left quarter brings up a pad that holds W, A, S and D. On-screen buttons, placed and bound in the editor on the rail, send keys and mouse buttons, and a held button turns the camera when the finger slides. Layouts are saved on the phone.
- The keyboard is a full PC layout that sends physical keys. A Bluetooth keyboard on the phone works too. Android keeps the Windows key, so Right Ctrl sends it.
- Up to four controllers paired with the phone appear on the PC as Xbox 360 controllers, with rumble sent back. This needs the [ViGEmBus](https://github.com/nefarius/ViGEmBus/releases/latest) driver on Windows and access to `/dev/uinput` on Linux ([how](desktop/README.md#controllers-on-linux)); the hub says when it is missing.
- With "Share clipboard" also on for the device, text copied on the PC can be pasted on the phone and the other way round while you control, up to 64 KB.
- Button layouts can also hold an on-screen controller: sticks, a d-pad and Xbox buttons, sent to the PC as one more Xbox 360 controller. A ready "Controller" layout is added once.

Input from the phone reaches the PC as if typed there, so give control only to devices you trust. Windows does not pass input from a program to one running as administrator, so controlling such a program needs the hub to run as administrator. UAC prompts and the lock screen need an optional service, see [UAC prompts and the lock screen](desktop/README.md#uac-prompts-and-the-lock-screen).

## Updates

Settings has "Check for updates on launch", on by default, and a button to check now. A check is one request to `api.github.com/repos/yuredazo/manifold/releases/latest` (GitHub allows 60 anonymous requests an hour per IP address). Nothing is installed until you press Update.

The Android hub hands the downloaded APK to Android's installer, which asks you to confirm, and the first time to allow installs from Manifold. Android refuses an update signed with a different key, so a build you made yourself cannot be replaced from a release. The Windows hub unpacks the zip, exits, copies the files over its own folder and starts again, so that folder has to be writable. The Linux hub links to the release instead.

Both hubs refuse download links outside `github.com/yuredazo/manifold/releases/download/`, and ignore a file whose SHA-256 GitHub does not list or that does not match it. That proves the file is the one on the release, not who published it.

## Security

Any app can bind to the hub and announce a feed, but an app receives video only after the owner allows it. The hub learns who is calling from the system, not from the caller, and ties each decision to the app's package and signing certificate, so a different build of the same package is asked about again. An allowed app can watch every feed, and announced feed names are visible to any app until it is blocked. The full model and its limits are in [PROTOCOL.md](docs/PROTOCOL.md#security-model). To report a vulnerability, see [SECURITY.md](SECURITY.md).

## Documentation

| Document | Contents |
| --- | --- |
| [docs/PROTOCOL.md](docs/PROTOCOL.md) | The AIDL interface, the hub's behavior and limits, and the security model. |
| [docs/NETWORK.md](docs/NETWORK.md) | Pairing, packet formats, video and audio delivery, measurements, known gaps. |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | How the source is organized and wired together. |
| [desktop/README.md](desktop/README.md) | Building and using the Windows and Linux hub. |
| [CONTRIBUTING.md](CONTRIBUTING.md) | Building, testing, changing the API or protocol, signing and releases. |

## License

Apache License 2.0. See [LICENSE](LICENSE).
