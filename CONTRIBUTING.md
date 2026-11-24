# Contributing

Bug reports and pull requests are welcome. For a vulnerability, follow [SECURITY.md](SECURITY.md) instead of opening a public issue.

Before a larger change, open an issue to say what you plan. Installed apps and older hubs depend on the wire protocols, so some changes need more care than they seem to (see [Changing the protocol](#changing-the-protocol)). [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) explains where new code belongs.

## Building and testing

Android needs JDK 17 and the Android SDK (compile SDK 37):

```sh
./gradlew :hub:assembleDebug      # the hub
./gradlew :hub:assembleRelease    # see "Signing" below
./gradlew :probe:assembleDebug    # the cross-process test app
./gradlew :sdk:testDebugUnitTest :hub:testDebugUnitTest
```

The desktop hub's requirements and build steps for Windows and Linux are in [desktop/README.md](desktop/README.md#build-and-run). Before a pull request, run from `desktop`:

```sh
cargo clippy --workspace --all-targets -- -D warnings
cargo test -p manifold-protocol
```

### Across processes

Unit tests cannot cover hub death, sender death, a receiver that disappears or API misuse across processes. The probe app runs senders and receivers in separate processes and checks those. Install the hub first. The probe is a receiver, so before its first run open the hub, go to Apps and set "Manifold probe" to Allowed; until then its video checks fail with zero frames.

```sh
adb install -r probe/build/outputs/apk/debug/probe-debug.apk
adb shell am force-stop dev.mkzk.manifold.probe
adb shell am start -n dev.mkzk.manifold.probe/.MainActivity --es phase basic
adb logcat -s PROBE
```

Force-stop the probe before each phase, or Android hands the intent to the running instance and the phase does not start. Each result is logged as `RESULT PASS` or `RESULT FAIL`.

| Phase | Checks |
| --- | --- |
| `basic` | A sender in another process is found, its video and audio arrive, and a second receiver gets its own stream. |
| `hubkill` | Recovery after the hub is killed. Takes about a minute. |
| `senderkill` | A killed sender coming back within 10 seconds. Phones that restart a service only after 20 seconds need `--ei seconds 40`. |
| `consumerlost` | A receiver that disappears; reported through the `SENDER` log lines. |
| `misuse` | Wrong use of the API. |
| `alpha` | Transparency between two apps on the phone. |
| `screensound`, `screensync` | The hub's screen share, which must be running with sound and the probe allowed. |
| `sender` | Leaves a sender running, named with `--es name probe-a`, to watch from another app. |

## Changing the SDK API

`sdk/api/sdk.api` lists the public API. The binary-compatibility check that wrote it does not run on the Kotlin built into AGP 9, which the project uses, so update the file by hand when the API changes and say so in the pull request. The SDK uses `explicitApi()`, so every public declaration states its visibility and type.

## Changing the protocol

Both protocols have installed clients that cannot be updated: the AIDL interface between apps and the hub, and the packets between hubs.

- AIDL: add methods and `SenderInfo` fields at the end, and raise `Manifold.PROTOCOL_VERSION` for any change an older client cannot handle. See [PROTOCOL.md](docs/PROTOCOL.md#versioning).
- Packets: `hub/.../network/protocol` (Kotlin) and `desktop/protocol` (Rust) implement the same format and change together. `CompatFixturesTest` and `desktop/protocol/tests/compat.rs` compare their output with `fixtures/compat.txt`, bytes the Kotlin code produced, so a format change fails both suites until the fixture and both implementations agree.
- A device acknowledges and ignores control messages it does not understand, so a newer device cannot make an older one drop the link. Keep that when adding messages.

## Signing

`:hub:assembleRelease` reads `keystore.properties` in the repository root, which is not committed:

```properties
storeFile=path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Without it the release build is signed with the debug key so you can still install it; do not publish or share such a build. Android refuses an update signed with a different key, so the release key must stay the same for the life of the hub.

## Releases

Releases are built and published from a PC with `release.ps1`. There is no CI.

```powershell
./release.ps1            # build and check everything, publish nothing
./release.ps1 -Publish   # the same, then create the GitHub release
```

It needs a clean working tree whose commit is pushed, `keystore.properties` with the release key, the Android SDK and the Windows build tools. It reads `versionName` in `hub/build.gradle.kts` and `version` in `desktop/hub/Cargo.toml`, stops if they differ or the tag exists on GitHub, and then:

1. runs the SDK and hub unit tests, builds the release APK and checks that it carries the release signature;
2. runs Clippy and the protocol tests, builds the Windows hub and zips it with its licence files;
3. with `-Publish`, creates a draft release `v<version>`, uploads the APK and the zip, checks that GitHub lists the same SHA-256 as each local file, and only then publishes. If a step fails, the draft is deleted.

The GitHub token is the Git Credential Manager login that `git push` uses, and the script never prints it. Release notes are empty unless you pass `-Notes`. Raise `versionCode` with the version, since Android refuses an update with a lower one. The files stay in `build/release`.

## Style

- Comments say why, not what: a reason, a constraint, a unit or a platform quirk.
- Match the surrounding code. Names say what a thing is, and a class that only forwards calls should not exist.
- Add a test for behavior you change. The registry, the network state machines and the codecs' pure logic have unit tests; hardware codec and native capture code are tested by running them, so say in the pull request what you ran.
