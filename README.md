# Manifold

Manifold lets one Android app send live video, and optionally audio, to another app on the same phone. Between the two apps there is no capture and no encoding step: the receiving app hands over a `Surface` and the sending app draws straight into it.

A small hub app introduces the two sides, and the person who owns the phone decides which apps may receive. The hub never sees a frame. If the hub is killed or updated, the feeds keep running and the SDK reconnects on its own. Hubs on different devices (phones, and a Windows PC) can also pair and share feeds over the network.

Status: SDK 1.1.0, hub 1.1.0, protocol version 1, minimum Android SDK 24. Tested on two Android 14 phones and Windows 11.

## How it works

1. A sender announces a named feed to the hub, for example `my-avatar`.
2. A receiver lists the announced senders and subscribes to one by name, passing the `Surface` it wants filled and the size it wants.
3. The first time an app subscribes, the hub holds the request and notifies the phone's owner, who allows or blocks that app in the hub. Once the app is allowed, the hub forwards the `Surface` to the sender. The sender renders into it with OpenGL, a `MediaCodec` decoder, a `Canvas`, or anything else that can draw to a `Surface`.
4. When the receiver unsubscribes or goes away, the sender is told to stop.

A subscription can be made before the sender exists. It waits, and is delivered when a sender with that name appears. If the sender restarts, it gets the same surface again. Several receivers can watch one sender: the sender gets one surface per receiver and draws into each of them.

## Modules

| Module | What it is |
| --- | --- |
| `sdk` | The library apps depend on: `ManifoldSender`, `ManifoldReceiver` and the AIDL protocol. |
| `hub` | The Android hub app (`dev.mkzk.manifold`). Its screens show what is live, which apps may receive, and the paired devices. It can also share the phone's whole screen as a feed. |
| `probe` | A test app that exercises the SDK across processes. |
| `desktop` | The Windows hub, a Flutter app that pairs with the Android hub over the network. |

## Using the SDK

There is no public Maven artifact yet. To use the SDK today, publish it to a local repository:

```
./gradlew :sdk:publishReleasePublicationToLocalRepository
```

That writes `dev.mkzk.manifold:manifold-sdk:1.1.0` to `sdk/build/repo`. Add that folder as a Maven repository in the app that depends on it, or include the `sdk` module as a Gradle project.

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

A feed that is only sound sets `Config(name, hasAudio = true, soundOnly = true)`. Its subscribers still pass a surface, which is never drawn into, and receivers that understand the flag play only the sound. `soundOnly` needs `hasAudio`, and has been in the SDK since 1.1.0.

Run the sender inside a foreground service. On a TECNO CK7n, Android restarted a killed background service only after about 20 seconds, which is a long gap in a live feed.

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

The receiver keeps ownership of the surface and audio pipe it passes in, and releases them after `unsubscribe`.

Nothing is delivered until the owner allows the app in the hub. Override `Listener.onAccessChanged` to learn where the subscription stands (`PENDING`, `ALLOWED` or `BLOCKED`) and tell the user to approve it in Manifold.

### Rules that apply to both

- `start()` can be called once per instance. Calling it twice throws `IllegalStateException`. `stop()` can be called any number of times.
- Listener callbacks run on the main thread unless you pass an `Executor` to the constructor.
- Sender names are 1 to 64 characters with no control characters. Use `Manifold.isValidName` to check one.
- Bad arguments throw `IllegalArgumentException` instead of failing later.

## Building

### Android

You need JDK 17 and the Android SDK (compile SDK 36).

```
./gradlew :hub:assembleDebug      # the hub
./gradlew :hub:assembleRelease    # the hub, see "Signing" below
./gradlew :sdk:testDebugUnitTest :hub:testDebugUnitTest
./gradlew :sdk:apiCheck           # fails if the public SDK API changed
```

`sdk/api/sdk.api` records the public API. After an intended change, run `./gradlew :sdk:apiDump` and commit the new file.

### Windows hub

You need Flutter (3.44 stable is what CI uses) and Visual Studio with the "Desktop development with C++" workload.

```
cd desktop
flutter pub get
flutter analyze
flutter test                   # protocol tests, which also compare bytes with the Android code
flutter run -d windows
flutter build windows --release
```

The release build is in `desktop/build/windows/x64/runner/Release`. Sharing the sound of an application needs Windows 10 build 20348 or later; without it windows are shared without sound. More in [desktop/README.md](desktop/README.md).

### Signing

`:hub:assembleRelease` reads `keystore.properties` in the repository root, which is not committed:

```
storeFile=path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Without that file the release build is signed with the debug key so that you can still install it. A build signed that way must not be published or handed to other people. An app signed with a different key cannot update one signed with another, so the key has to stay the same for the life of the hub.

## Continuous integration and releases

Every push to `main` and every pull request runs two jobs. The Android job runs the unit tests and the API check and builds the hub and the probe. The Windows job runs the Dart analyzer and tests and builds the Windows hub. The debug APKs and the Windows zip are kept as workflow artifacts for 14 days.

Pushing a tag such as `v1.0.0` runs the release workflow. It builds the Android hub signed with the release key and attaches the APK to a GitHub release, then builds the Windows hub and attaches `manifold-hub-windows-v1.0.0.zip` to the same release. The tag has to match `versionName` in `hub/build.gradle.kts` and `version` in `desktop/pubspec.yaml`, and the workflow stops before publishing anything if either differs. Raise `versionCode` in the same commit, since Android refuses an update with a lower one. The Windows build is not code signed, so Windows SmartScreen may warn when it is first run.

The Android signing needs four repository secrets: `KEYSTORE_BASE64` (the keystore file, base64 encoded), `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`.

## Testing across processes

The probe app starts senders and receivers in separate processes and checks what unit tests cannot: hub death, sender death, a receiver that disappears, and misuse of the API. Install the hub first. The probe receives, so the first time you run it, open the hub, go to Apps and set "Manifold probe" to Allowed. Until then its video checks fail with zero frames, because the hub is holding them back.

```
adb install -r probe/build/outputs/apk/debug/probe-debug.apk
adb shell am start -n dev.mkzk.manifold.probe/.MainActivity --es phase basic
adb logcat -s PROBE
```

The phases are `basic`, `hubkill`, `senderkill`, `consumerlost`, `misuse` and `alpha` (transparency between two apps on the phone). Two more, `screensound` and `screensync`, need the hub's own screen share running with sound, and the probe allowed in the hub's Apps tab. Each result is logged as `RESULT PASS` or `RESULT FAIL`. `--es phase sender --es name probe-a` leaves a sender running so that you can watch it from another app.

## Between devices

Hubs on different devices can pair and share feeds over the network. A feed from another device shows up here as an ordinary sender. [docs/NETWORK.md](docs/NETWORK.md) describes the protocol, how loss and delay are handled, measurements, and what is not done yet.

## Updates

Both hubs can look for a newer release on GitHub. About has a "Check for updates on launch" switch, on by default, and a button to check at any time. A check is one request to `api.github.com/repos/yuredazo/manifold/releases/latest`, and GitHub allows 60 anonymous requests an hour per IP address. Nothing is installed until you press Update.

The Android hub downloads `manifold-hub-v<version>.apk` straight into an install session and hands it to Android's installer, which asks you to confirm. The first time, Android also asks you to allow installs from Manifold. Android refuses an update signed with a different key, so a build you made yourself cannot be replaced from a release. The Windows hub downloads `manifold-hub-windows-v<version>.zip`, unpacks it, exits, copies the files over its own folder and starts again, so that folder has to be writable.

Both hubs compare the download with the SHA-256 that GitHub lists for the file and ignore a release whose file has none, and both refuse download links outside `github.com/yuredazo/manifold/releases/download/`. That shows the file is the one on the release. It does not show who published it, and the Windows build is not code signed.

## Security

Any app can bind to the hub and announce a feed, but an app only receives video after the owner has allowed it in the hub. The hub works out who is calling from the system, not from what the caller says. A decision belongs to the app and the certificate it is signed with, so a different build of the same package is asked about again.

An allowed app can watch every feed, and a sender cannot refuse one receiver or see which app it is. The names of announced feeds are visible to any app until it is blocked. The details, and the other limits, are in [docs/PROTOCOL.md](docs/PROTOCOL.md). To report a vulnerability, see [SECURITY.md](SECURITY.md).

## License

Apache License 2.0. See [LICENSE](LICENSE).
