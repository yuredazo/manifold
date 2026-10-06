# Contributing

Bug reports and pull requests are welcome. For a vulnerability, do not open a public issue; follow [SECURITY.md](SECURITY.md).

Before you start on a larger change, open an issue to say what you plan to do. The hub speaks a wire protocol that older installed apps depend on, so some changes need more care than they look like they do (see [Changing the protocol](#changing-the-protocol)).

[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) explains how the source is organized and where a new piece of code belongs.

## Building and testing

### Android

You need JDK 17 and the Android SDK (compile SDK 36).

```
./gradlew :hub:assembleDebug      # the hub
./gradlew :hub:assembleRelease    # the hub, see "Signing" below
./gradlew :probe:assembleDebug    # the cross-process test app
./gradlew :sdk:testDebugUnitTest :hub:testDebugUnitTest
./gradlew :sdk:apiCheck           # fails if the public SDK API changed
```

### Windows hub

You need Flutter (CI uses 3.44 stable) and Visual Studio with the "Desktop development with C++" workload.

```
cd desktop
flutter pub get
flutter analyze
flutter test
flutter run -d windows
flutter build windows --release
```

The first configure downloads the Spout 2.007.017 sources from GitHub (a pinned archive, checked by SHA-256), so it needs internet access. The release build is in `desktop/build/windows/x64/runner/Release`.

### Across processes

Unit tests cannot cover hub death, sender death, a receiver that disappears or misuse of the API across process boundaries. The probe app starts senders and receivers in separate processes and checks those. Install the hub first. The probe is a receiver, so the first time you run it, open the hub, go to Apps and set "Manifold probe" to Allowed. Until then its video checks fail with zero frames, because the hub is holding them back.

```
adb install -r probe/build/outputs/apk/debug/probe-debug.apk
adb shell am force-stop dev.mkzk.manifold.probe
adb shell am start -n dev.mkzk.manifold.probe/.MainActivity --es phase basic
adb logcat -s PROBE
```

Force-stop the probe before each phase. If its screen is still open, Android hands the new intent to the running instance and the phase does not start.

The phases are `basic`, `hubkill`, `senderkill`, `consumerlost`, `misuse` and `alpha` (transparency between two apps on the phone). Each result is logged as `RESULT PASS` or `RESULT FAIL`; `consumerlost` reports through the `SENDER` log lines instead. `hubkill` takes about a minute. `senderkill` waits 10 seconds for the killed sender to come back, and some phones restart a killed service only after 20 seconds, so on those pass `--ei seconds 40`. `screensound` and `screensync` need the hub's own screen share running with sound and the probe allowed in the Apps tab. `--es phase sender --es name probe-a` leaves a sender running so you can watch it from another app.

## Changing the SDK API

`sdk/api/sdk.api` records the public API, and `:sdk:apiCheck` fails when the code and the file disagree. After a change you meant to make, run `./gradlew :sdk:apiDump` and commit the new file. The SDK uses `explicitApi()`, so every public declaration needs its visibility and type written out.

## Changing the protocol

There are two protocols: the AIDL interface between apps and the hub on one phone, and the packets between hubs. Both have installed clients that you cannot update.

- AIDL: add methods and `SenderInfo` fields at the end, and raise `Manifold.PROTOCOL_VERSION` for any change an older client cannot handle. The rules are in [docs/PROTOCOL.md](docs/PROTOCOL.md#versioning).
- Network packets: the Kotlin code in `hub/.../network/protocol` and the Dart code in `desktop/lib/network/protocol` implement the same format and must change together. `desktop/test/fixtures/compat.txt` holds bytes the Kotlin code produces. `CompatFixturesTest` (Kotlin) and `compat_test.dart` (Dart) both compare their output with that file, so a format change shows up as a failure in both suites until the fixture and both implementations agree.
- A device must ignore control messages it does not understand and acknowledge them, so a newer device cannot make an older one drop the link. Keep that property when adding messages.

## Signing

`:hub:assembleRelease` reads `keystore.properties` in the repository root. The file is not committed:

```
storeFile=path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Without that file the release build is signed with the debug key so that you can still install it. Do not publish such a build or hand it to other people. An app signed with a different key cannot update one signed with another, so the release key has to stay the same for the life of the hub.

## Continuous integration and releases

Every push to `main` and every pull request runs two jobs. The Android job runs the unit tests and the API check and builds the hub and the probe. The Windows job runs the Dart analyzer and tests and builds the Windows hub. The debug APKs and the Windows zip are kept as workflow artifacts for 14 days.

Pushing a tag such as `v1.0.0` runs the release workflow. It builds the Android hub signed with the release key and attaches the APK to a GitHub release, then builds the Windows hub and attaches `manifold-hub-windows-v1.0.0.zip` to the same release. The tag has to match `versionName` in `hub/build.gradle.kts` and `version` in `desktop/pubspec.yaml`, and the workflow stops before publishing anything if either differs. Raise `versionCode` in the same commit, since Android refuses an update with a lower one.

The Android signing needs four repository secrets: `KEYSTORE_BASE64` (the keystore file, base64 encoded), `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`.

## Style

- Comments say why, not what. A reason, a constraint, a unit or a platform quirk is worth a comment; a restatement of the next line is not.
- Match the surrounding code. Names say what a thing is, and a class that only forwards calls to another one should not exist.
- Add a test for behavior you change. The registry, the network state machines and the codecs' pure logic are covered by unit tests; hardware codec code and native Windows capture code are tested by running them, so say in the pull request what you ran.
