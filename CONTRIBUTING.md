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

You need Rust stable, Visual Studio with the "Desktop development with C++" workload, and Python 3 for packaging.

```
cd windows
cargo clippy --workspace --all-targets -- -D warnings
cargo test -p manifold-protocol
cargo run -p manifold-hub
cargo build --release -p manifold-hub
./package.ps1 -Out manifold-hub-windows.zip
```

The first build downloads the Spout 2.007.017 sources from GitHub (a pinned archive, checked by SHA-256), so it needs internet access. The release build is `windows/target/release/manifold_hub.exe`, and `package.ps1` zips it with its licence files. More in [windows/README.md](windows/README.md).

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
- Network packets: the Kotlin code in `hub/.../network/protocol` and the Rust crate in `windows/protocol` implement the same format and must change together. `fixtures/compat.txt` holds bytes the Kotlin code produces. `CompatFixturesTest` (Kotlin) and `windows/protocol/tests/compat.rs` (Rust) both compare their output with that file, so a format change shows up as a failure in both suites until the fixture and both implementations agree.
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

## Releases

Releases are built and published from a PC with `release.ps1` in the repository root. There is no CI.

```
./release.ps1            # build and check everything, publish nothing
./release.ps1 -Publish   # the same, then create the GitHub release
```

The script needs a clean working tree whose commit is already pushed, `keystore.properties` with the release key (see "Signing"), the Android SDK, and the tools for the Windows hub. It reads `versionName` in `hub/build.gradle.kts` and `version` in `windows/hub/Cargo.toml`, stops if they differ or if their tag already exists on GitHub, and then:

1. runs the Android unit tests and the API check and builds the release APK, and checks that it carries the release signature;
2. runs Clippy and the protocol tests and builds the Windows hub, and zips it with its licence files;
3. with `-Publish`, creates a draft release `v<version>` on the current commit, uploads `manifold-hub-v<version>.apk` and `manifold-hub-windows-v<version>.zip`, checks that GitHub lists the same SHA-256 for each as the local file, and only then publishes it. If any step fails, the draft is deleted again.

The GitHub token is the login Git Credential Manager holds for github.com, the one `git push` uses, and the script never prints it. Release notes are empty unless you pass `-Notes`. Raise `versionCode` together with the version, since Android refuses an update with a lower one. The files stay in `build/release`.

## Style

- Comments say why, not what. A reason, a constraint, a unit or a platform quirk is worth a comment; a restatement of the next line is not.
- Match the surrounding code. Names say what a thing is, and a class that only forwards calls to another one should not exist.
- Add a test for behavior you change. The registry, the network state machines and the codecs' pure logic are covered by unit tests; hardware codec code and native Windows capture code are tested by running them, so say in the pull request what you ran.
