# Manifold

Manifold lets one Android app send live video, and optionally audio, to another app on the same phone. There is no screen capture and no encoding step: the receiving app hands over a `Surface` and the sending app draws straight into it.

A small hub app introduces the two sides. It never sees a frame. If the hub is killed or updated, the feeds keep running and the SDK reconnects on its own.

Status: version 0.1.0, protocol version 1, minimum SDK 24. It has been tested on one phone so far, and the API can still change before 1.0.

## How it works

1. A sender announces a named feed to the hub, for example `my-avatar`.
2. A receiver lists the announced senders and subscribes to one by name, passing the `Surface` it wants filled and the size it wants.
3. The hub forwards that `Surface` to the sender. The sender renders into it with OpenGL, a `MediaCodec` decoder, a `Canvas`, or anything else that can draw to a `Surface`.
4. When the receiver unsubscribes or goes away, the sender is told to stop.

A subscription can be made before the sender exists. It waits, and is delivered when a sender with that name appears. If the sender restarts, it gets the same surface again.

Several receivers can watch one sender. The sender gets one surface per receiver and draws into each of them.

## Modules

| Module | What it is |
| --- | --- |
| `sdk` | The library apps depend on: `ManifoldSender`, `ManifoldReceiver` and the AIDL protocol. |
| `hub` | The hub app (`dev.mkzk.manifold`). It also has a status screen that lists senders, receivers and subscriptions. |
| `probe` | A test app that exercises the SDK across processes. |

## Using the SDK

There is no public Maven artifact yet. To use the SDK today, publish it to a local repository:

```
./gradlew :sdk:publishReleasePublicationToLocalRepository
```

That writes `dev.mkzk.manifold:manifold-sdk:0.1.0` to `sdk/build/repo`. Add that folder as a Maven repository in the app that depends on it, or include the `sdk` module as a Gradle project.

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

Run the sender inside a foreground service. On the phone this was developed on, Android restarted background services only after about 20 seconds, and a feed that disappears for that long is a bad feed.

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

### Rules that apply to both

- `start()` can be called once per instance. Calling it twice throws `IllegalStateException`. `stop()` can be called any number of times.
- Listener callbacks run on the main thread unless you pass an `Executor` to the constructor.
- Sender names are 1 to 64 characters with no control characters. Use `Manifold.isValidName` to check one.
- Bad arguments throw `IllegalArgumentException` instead of failing later.

## Building

You need JDK 17 and the Android SDK (compile SDK 36).

```
./gradlew :hub:assembleDebug      # the hub
./gradlew :hub:assembleRelease    # the hub, see "Signing" below
./gradlew :sdk:testDebugUnitTest :hub:testDebugUnitTest
./gradlew :sdk:apiCheck           # fails if the public SDK API changed
```

`sdk/api/sdk.api` records the public API. After an intended change, run `./gradlew :sdk:apiDump` and commit the new file.

### Signing

`:hub:assembleRelease` reads `keystore.properties` in the repository root, which is not committed:

```
storeFile=path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Without that file the release build is signed with the debug key so that you can still install it. A build signed that way must not be published or handed to other people. An app signed with a different key cannot update one signed with another, so the key has to stay the same for the life of the hub.

### Releasing

Every push to `main` runs the tests and uploads the debug APKs as a workflow artifact. Pushing a tag such as `v1.0.0` runs the release workflow, which builds the hub signed with the release key and attaches the APK to a GitHub release. The tag has to match `versionName` in `hub/build.gradle.kts`.

The workflow needs four repository secrets: `KEYSTORE_BASE64` (the keystore file, base64 encoded), `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`.

### Testing across processes

The probe app starts senders and receivers in separate processes and checks the behavior that unit tests cannot: hub death, sender death, a receiver that disappears, and misuse of the API. With the hub installed:

```
adb install -r probe/build/outputs/apk/debug/probe-debug.apk
adb shell am start -n dev.mkzk.manifold.probe/.MainActivity --es phase basic
adb logcat -s PROBE
```

The phases are `basic`, `hubkill`, `senderkill`, `consumerlost` and `misuse`. Each result is logged as `RESULT PASS` or `RESULT FAIL`. `--es phase sender --es name probe-a` leaves a sender running so that you can watch it from another app.

## Security

The hub is open on purpose: any app can bind to it, send or receive. It works out who is calling from the system, not from what the caller says, and limits how much each app can do.

That means any installed app can subscribe to any announced feed, and a sender cannot refuse a receiver or see which app it is. Only announce what you would be fine showing to every app on the device. The details, and the other limits, are in [docs/PROTOCOL.md](docs/PROTOCOL.md).

## License

Apache License 2.0. See [LICENSE](LICENSE).
