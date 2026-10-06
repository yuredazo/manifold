# Network feeds

Hubs on different devices can pair and share feeds. A feed from another device shows up in the local hub as an ordinary sender, so an app that receives through Manifold does not know it came over the network. Frames still never pass through the hub on the local path; only feeds that cross the network are encoded, sent and decoded.

There is no relay, no NAT traversal and no automatic discovery on the internet. To reach a hub from outside its network, give its public address and forward one UDP port (47200). The Devices screen of both hubs lists the local addresses of this device with a copy button. The public address is looked up only when you press Look up, with one request to `api.ipify.org`, which sees the address the request comes from.

## Pairing and identity

Every hub has a long-term X25519 key pair, created on first run. A device is identified by the SHA-256 of its public key, never by its address or name. The private key is stored encrypted: on Android with an AES key held by the Android Keystore, on Windows with DPAPI for the signed-in user. A key stored in plain by an earlier version is encrypted the first time it is read. Another program running as the same user can still ask the system to decrypt it, and a phone whose Keystore cannot be used keeps the key as before. If the stored key cannot be decrypted, for instance after the app's data was copied to another device, the hub makes a new identity and has to be paired again.

Pairing runs the Noise XX handshake. Both sides learn the other's public key and derive the same six digit code from the handshake. The owners compare the code on both screens and confirm, which defeats a device in the middle. Nothing is stored until both have confirmed, and the window that lets another device start a pairing closes as soon as one pairing succeeds.

Later sessions use Noise IK: the initiator already knows the responder's key, so a session starts in one round trip. A device whose key is not in the paired list gets no reply at all.

Both Noise patterns are implemented in the hub, from the specification, with X25519 and ChaCha20-Poly1305 from BouncyCastle's lightweight API (it works on every supported Android version). The handshake is tested byte for byte against the official cacophony test vectors.

Not implemented: pairing by QR code, and discovery on the local network. The address of the other device is typed in.

## Packets

A datagram is at most 1200 bytes. Handshake packets are `type(1) | step(1) | sender index(4) | receiver index(4) | Noise message`. After the handshake, data packets follow the WireGuard design:

| Field | Size | Meaning |
| --- | --- | --- |
| type | 1 | handshake pair, handshake hello, or data |
| session index | 4 | picks the session on the receiver |
| counter | 8 | explicit nonce, increases with every packet |
| ciphertext | n + 16 | ChaCha20-Poly1305 over the payload |

The receiver keeps a sliding window of recent counters and drops repeats and anything too old. Out-of-order packets are fine. The decrypted payload starts with a stream byte: control, video or audio.

## Control messages

Each message is `kind(1) | id(4) | body`, big-endian. Feed list, subscribe, unsubscribe, keyframe request, and the pairing confirmations are acknowledged and resent until they are acknowledged. A message whose kind or body the receiver does not understand is acknowledged and ignored, so a newer device cannot make an older one drop the link.

A feed list holds the feeds the other device may see, and is sent again on every change. A subscribe names a feed and the size, bitrate and frame rate wanted, and whether the sound is wanted too; the publishing hub encodes only while someone is subscribed. A subscribe that stops after the name means no sound, and one without the frame rate byte means 30. A publisher that will not send a stream answers with a refusal naming the stream and a reason: the receiving device has not been allowed to watch, the feed is gone, the publisher is already serving as many streams as it allows (4 per device, 8 in all), or the stream could not be started. The receiver stops waiting and shows the reason on the device's row until that device's feed list changes. A publisher from before refusals existed stays silent, and an older receiver ignores the message.

These are sent once and never acknowledged, because they are worth nothing when late: ping, the time request and reply (a round trip measurement every second), the publisher's numbers for a stream (once a second), the receiver's report on a stream (twice a second), and the request to send fragments again.

A session ends after 15 seconds without any packet, and a ping every 3 seconds keeps an idle one alive. If a paired device dials in while its old session is still up, because it restarted, the old session is reported down first, so streams that belonged to it stop.

## Video

The publishing hub gives the local feed a Surface that belongs to a hardware H.264 encoder and sends what the encoder produces: constant bitrate, low latency, Baseline profile, a keyframe every 2 seconds or when requested. Keyframes carry the SPS and PPS. A hardware decoder renders straight into the Surface of whichever local app subscribed.

Each frame is split into fragments that fit one packet, each with a 13 byte header: frame id (4), timestamp at 90 kHz (4), flags (1), fragment index (2) and count (2).

### Loss, delay and bitrate

The behavior below was chosen with `LinkSimulationTest`, which runs the real receiver and publisher code over a simulated lossy link.

Missing fragments are asked for again. A frame that is missing fragments holds up the ones after it for at most 300 ms. On an Android receiver that is the playout delay minus 60 ms, and at least 30 ms. After 4 ms without the missing pieces the receiver asks for them, then asks again after `max(10 ms, 1.3 round trips)`, and each further wait is twice the one before, up to six asks per piece. The waits double so that one bad stretch of radio does not use all the asks within a few milliseconds. The publisher keeps the last second of fragments (up to 4 MB) and sends the requested ones again, at most six times per frame. Only when the wait is over is the frame given up on, which triggers a keyframe request. With 2% packet loss and 8 to 35 fragments per frame, the simulation delivered 23% of frames when lost fragments were not requested (the picture froze until each next keyframe) and 100% when they were, with a 95th percentile delay of 23 ms. At 8% loss it delivered 99%, 37 ms.

The bitrate follows the receiver's reports. Twice a second the receiver reports how many fragments arrived, how many it requested again and how many frames it gave up on. The publisher lowers the encoder bitrate by 15%, at most once a second, when frames were given up on. It raises it by 8% plus 50 kbit/s after three clean reports, never above what the receiver asked for. Recovered fragments do not lower it, since a radio loses them at any bitrate. In the simulation, 6 Mbit/s into a 3 Mbit/s link delivered nothing without this and 98% of frames with it, but settled near 0.9 Mbit/s, because a keyframe is sent as one burst and the simulated queue holds 60 ms.

An Android receiver shows each picture and each piece of sound at the time it was made plus an offset that follows the link. The offset starts at 30 ms. It grows at once to cover the lateness seen over the last 10 seconds plus 10%, up to 500 ms, and shrinks by at most 0.3% of elapsed time, because sound played faster than it was made changes pitch. A frame waits in a queue and goes to the decoder 40 ms before it is due, since a decoder given frames early runs out of output buffers and drops them. A frame that arrives after its time is shown at once. In the simulation, with a 150 ms stall every 1.5 s and 1% loss, hold limits of 50 ms and 150 ms delivered 98.3% of frames with 3 keyframe requests, and a limit of 300 ms delivered 99.4% with 1. The Windows viewer holds up to 300 ms and leaves smoothing to its player.

A subscribe can ask for up to 60 frames a second, and the encoder is held to it on Android 10 and later.

On a phone, the Devices tab shows for each stream being watched the frame rate, bitrate, round trip, lost frames, requests to send again, decoder time, playout delay and jitter, and the publisher's own numbers: the time from a frame being drawn to leaving the encoder, and from there to the socket. The same lines go to logcat under `NetStats`. The Windows hub does not show them.

Sending at 1.2 to 3 times the stream's bitrate instead of in one burst was tried and left out. In the simulation it delivered fewer frames or built a queue on the sender, since the link's limit was below all of those rates. It would need an estimate of what the link can carry.

### Measurements

Measured on a TECNO CK7n, an Infinix SMART 9 (both Android 14) and a Windows PC, all on one Wi-Fi network.

| Path | Result |
| --- | --- |
| TECNO to Infinix, probe feed, about 720x1500 | 26 to 29 fps at about 380 kbit/s, no frame lost. First picture 362 to 617 ms after subscribing. |
| Per frame, same run | Encoder 14 to 26 ms from drawing to output, 4 to 6 ms more to the socket, under 2 ms to reassemble, 15 ms in the decoder, 11 to 19 ms of added playout delay. Frames arrived 6 to 14 ms off their timestamps. |
| Same run, total | About 65 to 75 ms from drawn to handed to the screen, before the display's own refresh. |
| Windows PC to TECNO, a typed-into Notepad window, 1428x783 | 23 to 29 fps at 110 to 420 kbit/s, no frame lost. Reassembly under 1 ms, decoder 11 to 18 ms, playout 1 to 39 ms (15 ms on average). |
| TECNO to the PC, probe feed 320x240 | Encoder 8 to 13 ms, 2 to 3.5 ms more to the socket, 27 fps at 78 kbit/s. |
| Windows PC (wired) to TECNO, a Notepad window 1428x783, healthy Wi-Fi (receive speed 52 to 72 Mbit/s), with the mouse circling over the window | 28.7 fps on average at about 105 kbit/s, no frame lost, no keyframe request, 440 ms between a frame being made and shown. With the mouse still, 4 fps at about 2 kbit/s. |
| Windows PC (wired) to TECNO, a Vivaldi window playing YouTube, 1920x1078, with sound, about a minute | 28 to 29 fps at 1.3 Mbit/s on average. In calm stretches the playout delay was 230 to 330 ms (median 276 to 295 ms), set by packets that arrived 100 to 230 ms after they were sent. In one episode of about 14 s the Wi-Fi delivered up to 1.5 s late, the delay reached its 500 ms limit and about 100 chunks of sound were cut. 0 to 3 frames lost. |

The round trip as the hub reads it (the middle of the last nine samples) was 8 to 11 ms from the Infinix and 17 to 27 ms from the TECNO, whose network thread also serves its encoder; between the TECNO and the PC it was 15 to 33 ms, while `ping` read 1 ms at best, 9 ms on average and 91 ms at worst. The radio is the main source of jitter. Real-time priority, operating rate and latency hints on the encoder made no difference to its time and were removed, and a low latency Wi-Fi lock on the phone had no visible effect. Not measured: the PC's encode time, and the whole way from a pixel being drawn to it being seen.

A phone's Wi-Fi can drop to a receive rate of 1 Mbit/s for 10 to 20 seconds, with the signal still strong, and nothing arrives meanwhile. This was seen twice in one 70 second run on one phone, once with the mouse still, and a buffer of 400 ms cannot hide it. It did not happen in the next two runs.

## Audio

One format is sent, so nothing about it travels: AAC-LC, 48 kHz, two channels, 128 kbit/s. A packet is the stream id (2 bytes), a timestamp in 90 kHz ticks (4 bytes) and one raw AAC frame of about 340 bytes. It is not resent when lost.

Sound and picture of a feed share one clock: a sound timestamp is on the same timeline as the picture's. A Windows PC stamps both from the performance counter, and its loopback capture stayed within 1.5 ms of it over a minute. A phone stamps the picture with the monotonic clock its frames carry. Each chunk of its sound is stamped with the time it was read, corrected to the earliest times seen (`SampleClock`); that followed a source clock 150 ppm off to within 0.4 ms over two simulated hours.

A phone decodes sound with MediaCodec. On Windows each frame gets an ADTS header for the player, and the viewer pins the first sound to the timestamp of the picture it is showing and follows its own clock from there.

An Android receiver decodes each frame shortly before it is due and writes the PCM into the player's pipe when it is due (`AudioPacer`). The pipe is cut to 8 KB. What the player has not yet played is how far the sound lags the picture, so it cannot grow past about 43 ms. Each chunk is stretched or squeezed by at most 0.5% to match the spacing of the due times, which follows a sender whose clock is off. A chunk more than 60 ms late is cut, and a player that cannot keep up gets later chunks squeezed until it can. In the simulation, with a sender clock 150 ppm fast or 200 ppm slow, or a player 120 ppm fast, sound stayed within 5 ms of its time for an hour and no chunk was cut. A player 120 ppm slow reached 49 ms while the pacer learned it, then stayed within 33 ms. The hub's preview keeps its audio buffer at about 60 ms for the same reason. A player in another app sizes its own buffer, and the hub cannot see what sits in it. The offset between sound and picture on a real stream has not been measured.

## How it fits into the hub

To publish, the hub subscribes to the local feed as a receiver of its own, with the encoder's Surface. The permission checked is the per-device Send switch, not the app allow list.

To receive, the hub registers a remote feed as a sender named like `camera (Pixel)`. When an allowed local app subscribes, the hub starts a decoder into that app's surface, and stops it when the subscription ends.

Tapping a feed on the Live screen previews it full screen. The hub subscribes as a receiver of its own, so local and remote feeds preview the same way and no permission is asked. A feed with sound plays through an audio pipe with a mute button, and the hub does not take audio focus. If the sender goes away the preview keeps waiting, since a subscription outlives its sender.

## Sharing the phone's screen

The share-screen icon in the top bar offers the whole screen as a feed named `Screen`, with or without sound, and turns into a stop icon with the number of watchers while it runs. The feed is announced at the screen's size with the long edge capped at 1920 and at 30 frames a second. The hub registers it as a sender of its own, so paired devices and local apps subscribe to it like any other feed, with the same permissions. Android's consent dialog is asked for each time. Where the system offers it, the dialog lets the owner pick a single app instead of the whole screen, and the feed then follows the size of that app's window as Android reports it; the TECNO CK7n (Android 14) used for testing shows a consent dialog with only Cancel and Start now, so the single-app path could not be reached and is untested. The capture runs in a foreground service of type `mediaProjection`, and the notification has a Stop button.

Android 14 allows one virtual display per capture, so a single `ScreenFrames` object draws the screen into each watcher's encoder surface with OpenGL and fits it with black bars to the size that watcher asked for. The virtual display is detached from its surface while nobody watches, and a watcher who joins a still screen gets the last picture at once. The feed's size follows a rotation of the phone.

Sound is whatever the phone plays through media, game and unspecified audio usage, taken with playback capture (Android 10 and later) and written to every watcher that asked for sound. It needs the microphone permission, which the hub only asks for when sharing with sound; if it is refused the screen is shared without sound. An app that opts out of capture is not heard. On a TECNO CK7n (Android 14) the picture reached the Windows hub in portrait and, after rotating the phone, in landscape inside the portrait stream, and sound from a music player reached the encoder's pipe. Each watcher's sound pipe is written without waiting, so a watcher that stops reading loses chunks and never holds up the others; before that, one stuck watcher silenced all of them (measured on the TECNO: 0 bytes in 10 seconds against 1.92 MB with a second watcher that never read). Timing was measured at the phone with the probe's `screensync` phase, which flashes its own screen and plays a click at the same moment and notes when each reaches the feed: the picture arrived 33 to 47 ms after the flash and the sound 142 to 181 ms after the click, so the sound trails the picture by 101 to 134 ms (ten flashes, resolution about 21 ms for the sound chunk size). Part of that is the phone's own delay between starting a sound and the mixer passing it on, so the sound is stamped late by about that much. This is the capture end only; playout on the receiving device is not included.

## Windows hub

The Flutter app in `desktop/` speaks the same packets. Its protocol code is a Dart port of the Kotlin `net` package, and `desktop/test/fixtures/compat.txt` holds bytes the Kotlin code produced, which both test suites compare against.

Only one hub runs per Windows session, because two would compete for UDP port 47200. Each feed opens in a native window that holds only the picture. The hub rebuilds frames, wraps them in an MPEG transport stream and hands it to libmpv (through `media_kit`) over a connection on the loopback address. Closing the window ends the stream on the other device.

Any window of the PC, or a whole display, can be shared. The runner (`desktop/windows/runner`) captures it with Windows.Graphics.Capture, scales and converts color on the GPU with the D3D11 video processor, and encodes with the H.264 and AAC encoders that ship with Windows. Sound is that of one application and the processes it started, taken with process loopback capture, which needs Windows 10 build 20348 or later; without it a window is shared without sound. The H.264 encoder runs on one worker thread and is flushed after every frame. With its default parallel worker threads it held about 13 frames before the first came out: 413 ms at 30 frames a second and 3.2 s for a still window, which is re-encoded 4 times a second. Now a frame leaves 7 ms after it went in at 30 frames a second, and 2 ms at 4.

A display is captured the same way as a window, and its sound is everything the PC plays except Manifold's own player windows, so a feed that is being watched on this PC is not sent back to it. A saved display is found again by its device name, such as `\\.\DISPLAY2`, and waits while that display is not connected. Shared windows are saved, with the title, window class and executable name of each, and survive a restart and a window that closes. A saved window is found again as OBS does it: the same executable is required, an exact title wins, and otherwise any window of the same class is taken, so a browser window is still found when its page changes. While no window matches, the share stays on the page as waiting and is not offered to devices. The feed name stays what it was when the window was shared.

The stream is exactly the size the first watcher asked for, with the window scaled to fit and black bars for the rest. A second device watching the same window gets that stream, at that size and bitrate. A window that does not change is encoded again four times a second so the stream stays alive, and a keyframe goes out every 2 seconds. A window is captured only while a device watches it, and only if the owner switched on "Let it watch my windows" for that device. At most 4 streams per device and 8 in all are served. Capture is fixed at 30 frames a second.

A window or a display can also be shared for its sound alone. The feed is announced with width 0, height 0, sound set and the sound-only flag, which is bit 1 of the audio byte in the feed list (bit 0 is "has audio"), so a hub that predates it still reads "has audio" and nothing more; a feed of size 0 with sound but without the flag is an ordinary feed with no size preference. The runner then captures only the sound (a window's application, or everything the PC plays except Manifold for a display) and sends no video. A display shared this way is named `PC sound`, a window `<title> (sound)`. An Android hub lists such a feed as "sound only" and plays it through the preview, and the Windows hub plays it without a window through libmpv, from a transport stream with only the audio, which carries the clock and repeats its tables about once a second since there are no keyframes to carry them. Neither asks for keyframes for it. An app using the SDK can announce one with `Config(soundOnly = true)`; the hub then publishes it to paired devices without an encoder, and gives the sender a placeholder surface when the subscriber is another device, since a sender's callback always receives one.

A webcam is shared the same way, without sound. It is read through Media Foundation in the mode the camera offers closest to the size the watcher asked for, converted to NV12 and fitted with black bars. A camera is found again after a restart by its device id, or by its name when it moved to another USB port, and is only opened while a device watches it. Reading is asynchronous: stopping flushes the pending read before the camera is shut down, because shutting it down under a blocking read hung the app.

A Spout sender on the PC (TouchDesigner, Resolume, VTube Studio and other programs that share their output as a Spout texture) is shared like a camera: picture only, announced at the sender's size and fitted into the size the watcher asked for. The hub lists senders from Spout's shared sender list, receives with the Spout 2.007.017 `SpoutDX` receiver (fetched at build time, BSD 2-Clause, license shipped in `licenses/`), copies each new texture on the GPU, and converts it to NV12 on the same video processor that window capture uses. It polls at 60 times a second and encodes at most 30 frames a second. A sender is found again after a restart by its name, and a share waits while the sender is not running: when the program closes, the stream ends and the feed is withdrawn until the sender is back. Only senders that share a GPU texture work: a sender in Spout's memory-share mode has no texture to open, and a sender on a different graphics adapter than the default one cannot be opened. Textures other than 8-bit BGRA or RGBA have not been tried.

On the PC, a missing fragment is requested again when it watches a phone. When a phone watches a window of the PC, the PC answers requests to send again and follows the phone's reports with the encoder bitrate. It does not smooth playout, since the player does its own.

## Devices

Each paired device has a row with its status and address, and these controls:

- Receive their feeds: their feeds appear here as local senders (on Windows, "Watch its feeds").
- Share my feeds: they may watch this hub's feeds (on Windows, "Let it watch my windows"). Off by default, and it applies to every feed.
- Let it watch my camera (Windows only): they may watch the cameras this PC shares. Off by default, and separate from the switch above, so a device allowed to see windows does not see a camera, and its feed list does not show one.
- Let it watch my Spout senders (Windows only): they may watch the Spout senders this PC shares. Off by default and separate from the other two.
- Unpair: forget the key; the device has to pair again. Both hubs ask for confirmation first.

A switch turns incoming connections on or off. On Windows and on Android the choice is remembered. Android brings the listener back when the app is opened and when it restarts the service after killing the app; it does not start it after a reboot until the app is opened. While it is on the hub listens on one UDP port and shows a persistent notification, which Android requires for a foreground service.

## Known gaps

- Every local app watching a remote feed gets its own stream, so two watchers cost twice the bandwidth.
- Sharing is per device, not per feed.
- Anything that floods the port is not rate limited, and the port cannot be changed.
- The mouse cursor is part of the captured picture.
- The hardware codec code and the native Windows capture and audio code are tested by running them, not in unit tests.
- Playing a sound-only feed on Windows, and handing a placeholder surface to an SDK sender that is watched by another device, are covered by unit tests of the pieces but have not been run against a live stream.

## Limits

Two devices that are both behind strict NATs cannot connect without a relay. Wi-Fi power saving can add latency. On Windows, playback depends on libmpv probing a transport stream it is fed live: it needs the tables first and the first keyframe right after, and a different libmpv build may behave differently.
