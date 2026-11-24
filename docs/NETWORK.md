# Network feeds

Hubs on different devices pair and share feeds. A feed from another device appears in the local hub as an ordinary sender, so an app receiving through Manifold cannot tell it came over the network. Only feeds that cross the network are encoded, sent and decoded; the local path still passes no frames through the hub.

There is no relay, no NAT traversal and no discovery beyond the local network. To reach a hub from outside its network, give its public address and forward UDP port 47200 to it. The Devices screen of each hub lists this device's local addresses with a copy button. The public address is looked up only when you press Look up, with one request to `api.ipify.org`, which sees the address the request comes from.

Two paired devices on a VPN such as Tailscale find each other through it without typing anything, once they have been connected while both were on it: each hub tells the other every address it listens on and later dials all of them (see [Addresses](#addresses)).

## Pairing and identity

Every hub has a long-term X25519 key pair, made on first run. A device is identified by the SHA-256 of its public key, never by address or name. The private key is stored encrypted: on Android with an AES key held by the Android Keystore, on Windows with DPAPI for the signed-in user, on Linux in plain hex in a folder only the user can open. A key stored in plain by an earlier version is encrypted the first time it is read. Another program running as the same user can still ask the system to decrypt it, and a phone whose Keystore cannot be used keeps the key in plain. If the stored key cannot be decrypted, for instance after app data was copied to another device, the hub makes a new identity and has to be paired again.

Pairing runs the Noise XX handshake. Both sides learn the other's public key and derive the same six digit code from the handshake hash. The person pairing compares the code on both screens and confirms, which defeats a device in the middle. Nothing is stored until both sides confirm, and the window that lets another device start a pairing closes as soon as one pairing succeeds.

Later sessions use Noise IK: the initiator already knows the responder's key, so a session starts in one round trip. A device whose key is not in the paired list gets no reply at all. Each hello carries a wall-clock time that must be newer than the last one from that device, so a recorded hello cannot be replayed.

A hello costs key agreements before the hub knows whether to answer it, so each source IP address may send hellos at most as fast as WireGuard allows: a burst of 5, then 20 a second, whatever they turn out to be. A device dialing sends one a second per address, so it is never held back, and nothing is ever shut out for longer than the next hello. The first hello dropped in a minute is logged (`network.log` on a PC, logcat on a phone).

The Android hub implements both patterns from the specification with X25519 and ChaCha20-Poly1305 from BouncyCastle's lightweight API (it works on every supported Android version); the Rust crate uses `snow` and refuses low-order keys on top of it. Both are tested byte for byte against the official cacophony test vectors. Pairing by QR code is not implemented.

## Finding a device to pair with

While its pairing window is open (2 minutes), a device broadcasts a beacon once a second: one UDP datagram to `255.255.255.255`, port 47201. It is sent from each of the device's addresses in a private range (10/8, 172.16/12, 192.168/16) through that address's own adapter, so a computer with several adapters announces on all of them and nothing goes out on a mobile network or a tunnel.

| Field | Size | Meaning |
| --- | --- | --- |
| magic | 4 | `MNFD` |
| version | 1 | 1. Another version is ignored. |
| port | 2 | The port to pair on, big-endian. |
| id | 8 | The first 8 bytes of the sender's fingerprint, which the device shows as four groups of four hex digits. |
| name length | 1 | Up to 64. |
| name | n | UTF-8. |

Bytes after the name are ignored, so a later version can add fields. A device listens on port 47201 only while its pairing screen is open, keeps at most 16 devices, each for 4 seconds after its last beacon, and does not list itself or a device it is already paired with. On Android the hub holds a multicast lock while the list is open, since Wi-Fi chips often drop broadcasts to save power.

Nothing in a beacon is authenticated, so the list only says where to try. What decides a pairing is the six digit code both owners compare, which a device that lied in its beacon cannot make match. The listed fingerprint lets the owner compare it with the other screen before choosing. A broadcast does not cross a router, and some Wi-Fi networks block traffic between clients; typing the address works in both cases.

## Packets

A datagram is at most 1200 bytes. Handshake packets are `type(1) | step(1) | sender index(4) | receiver index(4) | Noise message`. After the handshake, data packets follow the WireGuard design:

| Field | Size | Meaning |
| --- | --- | --- |
| type | 1 | Handshake pair, handshake hello, or data. |
| session index | 4 | Picks the session on the receiver. |
| counter | 8 | Explicit nonce, rising with every packet. |
| ciphertext | n + 16 | ChaCha20-Poly1305 over the payload. |

The receiver keeps a sliding window of recent counters and drops repeats and anything too old; out-of-order packets are fine. The decrypted payload starts with a stream byte: control, video or audio.

## Control messages

Each message is `kind(1) | id(4) | body`, big-endian. Feed list, subscribe, unsubscribe, keyframe request, refusal, addresses, the input messages marked below and the pairing confirmations are acknowledged and resent every 400 ms until acknowledged, for as long as the session may still be heard. A message whose kind or body the receiver does not understand is acknowledged and ignored, so a newer device cannot make an older one drop the link.

Sent once and never acknowledged, because they are worth nothing late: ping, the time request and reply (a round-trip measurement every second), the publisher's numbers for a stream (once a second), the receiver's report on a stream (twice a second), and the request to send fragments again.

A session ends after 15 seconds without a packet, and a ping every 3 seconds keeps an idle one alive. A dropped session is redialed at the next tick. When packets return after more than 2.5 seconds of silence, the Windows hub drops the sound it had buffered, since it predates the gap. If a paired device dials in while its old session is still up, because it restarted, the old session is reported down first so its streams stop.

### Feed list and subscribe

A feed list holds the feeds the other device may see and is sent again on every change. Each entry is the name, width, height, frame rate and a flags byte: bit 0 has audio, bit 1 sound only, bit 2 alpha, bit 3 controllable, bit 4 clipboard. After the entries it may carry a title per feed, in the same order, as a length byte and UTF-8 (length 0 for none). The name is what a subscribe asks for and never changes while the feed exists; the title is what people read and may change, as a browser window's does with every page, so a new title is a new feed list and no stream is touched. A list without titles, from an older hub, is read with the names as titles, and such a hub ignores the section.

A subscribe names a feed and the size, bitrate and frame rate wanted, whether sound is wanted, and whether alpha is wanted. One that stops after the name means no sound; one without the frame rate byte means 30. The alpha request is a flags byte after the frame rate, which is then always written. The publisher encodes only while someone is subscribed.

A publisher that will not send a stream answers with a refusal naming the stream and a reason: the receiving device is not allowed to watch, the feed is gone, the publisher already serves as many streams as it allows (4 per device, 8 in all), or the stream could not be started. The receiver stops waiting and shows the reason on the device's row until that device's feed list changes. A publisher from before refusals stays silent, and an older receiver ignores the message.

### Addresses

Addresses (kind 15) lists the IPv4 addresses the sender listens on, Tailscale's 100.x included, so the other side can reach it after one stops working. The body is a count byte (at most 16), then 4 address bytes and a 2-byte port per entry. Each hub sends it as soon as a session comes up. The receiver keeps up to 8 next to the address the session came from, which stays first; a new list goes ahead of the ones it knew, so an address typed by hand stays until newer ones push it out. Loopback, 0.0.0.0, multicast, broadcast, link-local and port 0 are dropped. When it dials again it sends the same hello to every known address at once; the other side answers the first copy and ignores the rest, since a hello is used once, and the session runs over the path that answered. A hub from before this message acknowledges and ignores it and keeps dialing the one address it knew. On disk the extra addresses share the address field, comma separated.

## Remote input

A feed the receiving device may control has bit 3 set in the feed list. The publisher sets it only for a window or display not shared for its sound alone, while the device may watch it and the owner switched control on for that device. Input travels as control messages that start with the stream id; sizes in bytes, big-endian.

| Kind | Message | Body | Acknowledged |
| --- | --- | --- | --- |
| 16 | Pointer | stream 2, seq 4, x 2, y 2 | no |
| 17 | Motion | stream 2, dx 2, dy 2 | no |
| 18 | Mouse button | stream 2, seq 4, button 1, flags 1, x 2, y 2 | yes |
| 19 | Scroll | stream 2, dx 2, dy 2 | yes |
| 20 | Release | stream 2, seq 4, key seq 4 | yes |
| 21 | Pointer captured, publisher to receiver | stream 2, captured 1 | yes |
| 22 | Key | stream 2, key seq 4, usage 2, down 1 | yes |
| 23 | Text | stream 2, key seq 4, length 1, UTF-8 | yes |
| 24 | Gamepad | stream 2, seq 4, pad 1, buttons 2, left trigger 1, right trigger 1, four sticks 2 each | no |
| 25 | Rumble, publisher to receiver | stream 2, pad 1, large motor 1, small motor 1 | no |
| 26 | Clipboard, either way | stream 2, seq 4, part 2, count 2, UTF-8 | yes |

Positions span the streamed picture, black bars included, from 0 to 65535. Motion is signed pixels of the streamed picture, for games that turn the camera with the mouse. Buttons are 1 left, 2 right, 3 middle, 4 back, 5 forward; flag bit 0 is down and bit 1 says the position is filled in. A button this version does not know makes the message unknown, so it is acknowledged and ignored. Scroll counts 120 to a notch, positive up and right. A key is a USB HID keyboard usage, which names a physical key on any layout. Text is cut between characters at 200 bytes. A gamepad state uses the XInput layout: its button bits, triggers 0 to 255, sticks with up and right positive.

Pointer, button, release and gamepad messages share one seq per stream. The publisher drops a pointer move older than the last one applied, and a button or pad state older than the last for that button or pad or than a release, so a resent press cannot land after its release. Keys and text have their own seq and are applied strictly in order; when 64 wait behind a gap, the gap is given up and the rest applied. A release carries the last key seq sent, so an earlier key is dropped. The publisher lets go of everything held when the stream ends, control is switched off for the device, or sharing is paused. Gamepad states are not acknowledged, so the receiver repeats every pad's state every 200 ms. The publisher repeats rumble every 250 ms while the pad exists, and a receiver that hears none for a second stops the motors.

Copied text travels as Clipboard messages while a feed is controlled. The publisher sets bit 4 on a controllable feed when the owner also switched on clipboard sharing for that device, and only then does the receiver send its own copies; a publisher ignores clipboard messages for a stream that is not controlled with that permission. A copy is UTF-8 of at most 64 KB, cut into parts that each fit a datagram; seq names the copy, and part and count let the other side put it back together in any order, a newer copy replacing one still arriving. Each side remembers the last text it sent or received and does not send that text again, so a copy set from the other device is not bounced back. What is on the clipboard when control begins is not sent; only later copies are. The Windows hub notices a copy by the clipboard sequence number, checked every 250 ms; the Linux hub receives copies but does not send its own yet.

The Windows hub applies input with `SendInput`: keys by scan code, so a game reading W, A, S and D gets the same keys on any layout, text as Unicode, and positions mapped onto the window the way the capture fitted it, so a point on a black bar stops at the window's edge. A press brings the shared window to the front. The cursor counts as captured when the shared window is in front and the cursor is confined or hidden, checked every 250 ms with two checks in a row agreeing, and not while the controlling device holds Alt, which games use to free the cursor on purpose. A gamepad becomes a virtual Xbox 360 controller through ViGEmBus and the `vigem-client` crate; while the driver is missing it is looked for again every 5 seconds. The Linux hub uses XTEST and uinput the same way.

## Video

The publisher gives the local feed a Surface belonging to a hardware H.264 encoder: constant bitrate, low latency, Baseline profile, a keyframe every 2 seconds or on request, SPS and PPS on every keyframe. A subscribe can ask for up to 60 frames a second, and Android 10 and later hold the encoder to it. The receiver's hardware decoder renders straight into the Surface of whichever local app subscribed. Each frame is split into fragments that fit one packet, each with a 13 byte header: frame id (4), timestamp at 90 kHz (4), flags (1), fragment index (2) and count (2).

### Loss, delay and bitrate

These choices were made with `LinkSimulationTest`, which runs the real receiver and publisher code over a simulated lossy link.

Missing fragments are asked for again. A frame missing fragments holds up the ones after it for at most the profile's hold time (300 ms by default; on an Android receiver the playout delay minus 60 ms, at least 30 ms). After 4 ms without the missing pieces the receiver asks for them, then again after `max(10 ms, 1.3 round trips)`, each wait twice the one before, up to six asks per piece, so one bad stretch of radio does not use all the asks within a few milliseconds. The publisher keeps the last second of fragments (up to 4 MB) and resends requested ones, at most six times per frame. Only when the wait is over is the frame given up, which triggers a keyframe request. With 2% loss and 8 to 35 fragments per frame, the simulation delivered 23% of frames without resends (the picture froze until each keyframe) and 100% with them, at a 95th percentile delay of 23 ms; at 8% loss, 99% at 37 ms.

After a loss the receiver needs a keyframe. It asks at once, again after 0.5 s and 1 s, then every 2 s while none has come, and not while a keyframe is arriving in part and being repaired. A keyframe is waited for longer than other frames (1.5 s in the default profile) and its missing pieces are asked for at an even pace, up to 14 times, because giving up costs another keyframe, the largest frame there is. A picture waiting for a keyframe skips whatever comes before one. A publisher ignores a second keyframe request within 400 ms of the last. In the simulation, a 3.5 Mbit/s stream into a 3 Mbit/s link made 7 keyframe requests in 60 s where there had been 26.

The bitrate follows the receiver's reports. Twice a second the receiver reports how many fragments arrived, how many it asked for again and how many frames it gave up. The publisher lowers the bitrate by 15%, at most once a second, when frames were given up, and raises it by 8% plus 50 kbit/s after three clean reports, never above what the receiver asked for. Recovered fragments do not lower it, since a radio loses them at any bitrate. In the simulation, 6 Mbit/s into a 3 Mbit/s link delivered nothing without this and 98% of frames with it, but settled near 0.9 Mbit/s, because a keyframe goes out as one burst and the simulated queue holds 60 ms. Pacing (spreading a frame's fragments at 1.2 to 3 times the bitrate) delivered fewer frames or queued on the sender in the simulation, since the link's limit was below all those rates; it would need an estimate of what the link carries, and is not implemented.

An Android receiver shows each picture and sound at the time it was made plus an offset that follows the link. The offset starts at 30 ms, grows at once to cover the lateness seen over the last 10 seconds plus 10%, up to 500 ms, and shrinks by at most 0.3% of elapsed time, because sound played faster than it was made changes pitch. A frame goes to the decoder 40 ms before it is due, since a decoder given frames early runs out of output buffers and drops them; a frame that arrives late is shown at once. With a 150 ms stall every 1.5 s and 1% loss, hold limits of 50 and 150 ms delivered 98.3% of frames with 3 keyframe requests, and 300 ms delivered 99.4% with 1. The Windows viewer holds up to the hold time and leaves smoothing to its player; on the PC a missing fragment is requested again too, and a PC publisher answers resend requests and follows the reports with its encoder bitrate.

The receiving hub has three profiles, under "Delay when watching" in Devices:

| Profile | Waits for a missing piece | Android playout delay | Bitrate asked | Windows sound buffer |
| --- | --- | --- | --- | --- |
| Lowest delay | 120 ms | 15 to 150 ms | 70% | 60 ms |
| Balanced | 300 ms | 30 to 470 ms | 100% | 120 ms |
| Smoothest | 900 ms | 150 ms to 1.5 s | 140% | 300 ms |

On a phone, the Devices tab shows for each watched stream the frame rate, bitrate, round trip, lost frames, resend requests, decoder time, playout delay and jitter, and the publisher's own times from a frame being drawn to leaving the encoder and from there to the socket. The same lines go to logcat under `NetStats`. The Windows hub does not show them.

### Sending queue

The Android hub sends on a thread of its own. Control packets (pings, acknowledgements, resend requests) go first and are never dropped. Video and audio packets that waited more than 250 ms are dropped, since the receiver has given up on them, and the queue holds at most 512 KB of media. The socket's send buffer is 64 KB, so a congested uplink makes the send thread wait early and the queue decides what is dropped, instead of the kernel hiding seconds of backlog. Each second the hub logs what went on the wire (`NetStats: wire`): kilobits, packets, the longest single send, packets dropped and waiting.

### Transparency

H.264 has no alpha channel, so a feed with alpha is sent twice as tall: premultiplied color in the top half, alpha as grey in the bottom. One stream carries both, so they cannot drift apart. The feed list marks it with bit 2 and a subscribe asks for it with bit 0 of the flags byte after the frame rate. A hub that does not know the bit never announces it and ignores the trailing byte, so it sends an opaque picture. The size a watcher asks for is one half: at most 3840 wide and 2048 tall, so the stream stays within 4096. Both ends clamp the same way, because the receiver sets up its decoder for twice the height it asked for.

On Android the sender's surface is a `SurfaceTexture`, and a GL pass draws each frame into the encoder surface as two halves (`AlphaPacker`). The receiver decodes into a `SurfaceTexture` and draws the halves into the app's surface as one RGBA picture (`AlphaUnpacker`), so a local app sees an ordinary transparent sender. The Android preview shows it over a checkerboard; the Windows player joins the halves in a pixel shader and blends them over a checkerboard. Spout senders on Windows are covered under [Desktop hub](#desktop-hub).

### Sound-only feeds

A window, a display or an SDK sender can share only its sound. The feed is announced with width 0, height 0, has audio and bit 1 of the flags byte, so a hub that predates the bit still reads "has audio"; a feed of size 0 with sound but without the bit is an ordinary feed with no size preference. Nothing is encoded but sound and no keyframes are asked for. An Android hub lists it as "sound only" and plays it in the preview, and the Windows hub plays it without a window. An SDK sender announces one with `Config(soundOnly = true)`; the hub publishes it without an encoder and gives the sender a placeholder surface when the subscriber is another device, since a sender's callback always receives one.

## Audio

One format, so nothing about it travels: AAC-LC, 48 kHz, two channels, 128 kbit/s. A packet is the stream id (2 bytes), a timestamp in 90 kHz ticks (4 bytes) and one raw AAC frame of about 340 bytes. It is not resent.

Sound and picture of a feed share one clock. A Windows PC stamps both from the performance counter, and its loopback capture stayed within 1.5 ms of it over a minute. A phone stamps the picture with the monotonic clock its frames carry, and each chunk of sound with the time it was read, corrected to the earliest times seen (`SampleClock`); that followed a source clock 150 ppm off to within 0.4 ms over two simulated hours.

A phone decodes with MediaCodec. An Android receiver decodes each frame shortly before it is due and writes the PCM into the player's pipe when due (`AudioPacer`). The pipe is cut to 8 KB, and what the player has not played yet is how far sound lags the picture, so that cannot pass about 43 ms. Each chunk is stretched or squeezed by at most 0.5% to match the spacing of the due times, which follows a sender whose clock is off. A chunk more than 60 ms late is cut, and a player that cannot keep up gets later chunks squeezed until it can. In the simulation, with a sender clock 150 ppm fast or 200 ppm slow, or a player 120 ppm fast, sound stayed within 5 ms of its time for an hour and no chunk was cut; a player 120 ppm slow reached 49 ms while the pacer learned it, then stayed within 33 ms. The hub's preview keeps its buffer at about 60 ms for the same reason. A player in another app sizes its own buffer, which the hub cannot see.

On Windows each frame gets an ADTS header for Windows' AAC decoder, and the viewer pins the first sound to the timestamp of the picture it shows and follows its own clock from there, behind the profile's sound buffer. Sound is played as it arrives and is not synchronized with the picture.

## In the Android hub

To publish, the hub subscribes to the local feed as a receiver of its own, with the encoder's Surface; what is checked is the device's Send switch, not the app allow list. To receive, it registers a remote feed as a sender named like `camera (Pixel)`, and when an allowed local app subscribes it starts a decoder into that app's surface, stopping it when the subscription ends.

Tapping a feed on the Live tab previews it full screen. The hub subscribes as a receiver of its own, so local and remote feeds preview the same way and no permission is asked. Sound plays through an audio pipe with a mute button, without taking audio focus. If the sender goes away the preview keeps waiting, since a subscription outlives its sender. A top bar shows the title, the sending device, the size and a picture-in-picture button; a bottom bar shows a LIVE badge, the ping and frame rate of a remote feed, mute, fit or fill, and a landscape toggle. The bars fade after 3 seconds, a tap brings them back and a double tap switches fit and fill. Leaving the app while previewing shrinks the picture into a picture-in-picture window with no controls.

Each paired device has a switch to show its feeds here, one to let it watch what this phone shares (off by default, for every feed), and Unpair, which asks first. A switch turns incoming connections on or off and is remembered. Android brings the listener back when the app opens and when it restarts the service after killing the app, but not after a reboot until the app is opened. While it is on, the hub shows the persistent notification Android requires for a foreground service.

### Sharing the phone's screen

The share-screen icon in the top bar offers the whole screen as a feed named `Screen`, with or without sound. While it runs the icon turns red, with a badge counting the watchers once there are any, and opens the same sheet with the muted apps and Stop. The feed is announced at the screen's size, long edge capped at 1920, at 30 frames a second, which also caps encoding (it used to encode 55 to 70 a second). The hub registers it as a sender of its own, so devices and local apps subscribe to it with the same permissions. Android's consent dialog is shown each time. Where the system offers it, the owner can pick a single app, and the feed follows that app's window size as Android reports it; the Android 14 phone used for testing offers only Cancel and Start now, so that path is untested. Capture runs in a foreground service of type `mediaProjection` with a Stop button in its notification.

Android 14 allows one virtual display per capture, so one `SurfaceFanout` draws the screen into each watcher's encoder surface with OpenGL, fitted with black bars to the size that watcher asked for. The virtual display is detached while nobody watches, a watcher joining a still screen gets the last picture at once, and the size follows rotation. On an Android 14 phone the picture reached the Windows hub in portrait and, after rotating, in landscape inside the portrait stream.

Sound is what the phone plays with media, game and unspecified usage, through playback capture (Android 10 and later), written to every watcher that asked for it. It needs the microphone permission, asked only when sharing with sound; if refused, the screen is shared without sound. Apps that opt out of capture are not heard. Each watcher's pipe is written without waiting, so a watcher that stops reading loses chunks without holding up the others: with a blocking write, a second watcher that never read left the first with 0 bytes in 10 seconds, against 1.92 MB now.

Apps can be left out of the sound. The sheet lists apps with a launcher icon (declared in `<queries>`, since Android 11 hides other apps), and the chosen packages become `excludeUid` rules on the playback capture; the hub's own uid is always excluded, so previewing a feed does not play back into the share. A capture's rules are fixed when its `AudioRecord` is built, so a change builds and starts a new recorder on another thread, the reader switches to it, and the old one stops there. Rebuilding in place silenced the sound for 554 to 711 ms per change (building 228 to 323 ms, starting 274 to 306 ms); with the new recorder started beside the old one, the reader switched in under a millisecond over five changes in a row. With Files by Google playing a video, the captured peak was 27,563 with nothing muted, 0 with Files muted and 27,576 after unmuting. If a new recorder cannot be built, the sound ends rather than carrying on without the new rules.

The probe's `screensync` phase flashes its screen and plays a click at the same moment and notes when each reaches the feed: the picture arrived 33 to 47 ms after the flash and the sound 142 to 181 ms after the click, so sound trails the picture by 101 to 134 ms at the capture end (ten flashes, resolution about 21 ms). Part of that is the phone's own delay before the mixer passes a sound on, so sound is stamped late by about that much. Playout on the receiving device is not included.

### Sharing a phone camera

The camera icon left of the share-screen icon offers the back or front camera as a feed named `Camera`, without sound. The lens can be switched while sharing and the feed keeps its name. Lock rotation keeps the picture turned the way the phone was held, and Mirror flips it left to right; both are saved on the phone, Mirror for each lens separately. The camera opens only while somebody watches, through Camera2 at 30 frames a second, in the largest size the camera offers with a long edge of at most 1920, 16:9 when there is one. `SurfaceFanout` draws it into each watcher's surface like the screen.

Android turns the camera's buffers upright for the phone held in its natural orientation. The hub reads the phone's tilt and turns the picture further, so it stays upright when the phone is turned and the feed changes between portrait and landscape; the turn changes only once the phone is 15 degrees past halfway. Android mirrors the front camera, and the hub flips it back, so both lenses send what the camera sees unless Mirror is on. If another app takes the camera, the feed stays announced and watchers keep the last picture, the sheet says the camera is in use, and the hub opens it again when it is free. Capture runs in a foreground service of type `camera`, which Android lets start only while the hub is on screen, with a Stop button in its notification. Both lenses, turned both ways, were checked on an Android 16 phone watched from the Windows hub.

## Desktop hub

The Rust hub in `desktop/` speaks the same packets; its `protocol` crate is a port of the Kotlin `network/protocol` and `network/stream` packages, checked against `fixtures/compat.txt`. What it shares and how is described for users in [desktop/README.md](../desktop/README.md); the details that matter on the wire are these.

The stream is exactly the size the first watcher asked for, with the source fitted and black bars for the rest, and a second device watching the same source gets that stream at that size and bitrate. A source that does not change is encoded again four times a second so the stream stays alive, with a keyframe every 2 seconds. Capture runs only while a device watches and only if the owner allowed it for that device; at most 4 streams per device and 8 in all. Windows, displays and Spout senders run at 30 frames a second unless the share was set to 24, 45 or 60, announced as the feed's frame rate and the same for every watcher; cameras stay at 30.

Windows are captured with Windows.Graphics.Capture, scaled and converted on the GPU with the D3D11 video processor and encoded with the H.264 and AAC encoders that ship with Windows. A window's sound is its application and the processes it started, through process loopback capture (Windows 10 build 20348 or later; earlier, without sound). A display's sound is everything the PC plays except Manifold's own player windows, so a feed watched on this PC is not sent back. The H.264 encoder runs one worker thread and is flushed after every frame: with its default parallel threads it held about 13 frames before the first came out (413 ms at 30 frames a second, 3.2 s for a still window), and now a frame leaves 7 ms after it went in at 30 frames a second and 2 ms at 4.

A camera is read through Media Foundation in the mode closest to the size asked for, converted to NV12 and fitted with black bars, without sound. Reading is asynchronous, and stopping flushes the pending read before the camera is shut down, because shutting it down under a blocking read hung the app.

A Spout sender is read with the Spout 2.007.017 `SpoutDX` receiver, picture only, polled 60 times a second and encoded at most 30 times a second. Each new texture is copied on the GPU and converted on the same video processor as window capture. Only senders that share a GPU texture work: memory-share mode has no texture, and a sender on another graphics adapter than the default cannot be opened. Textures other than 8-bit BGRA or RGBA are untried. With Transparency on, the default, the texture is rendered through a pixel shader into two copies, color times alpha and alpha as grey, and the video processor scales each into its half of one NV12 frame, with transparent bars around a fitted picture. Senders are assumed to write straight alpha, since Spout's `SendImage` takes BGRA. A Spout capture has one picture size, so a second watcher asking for the other kind of picture is refused as busy.

The Linux hub grabs X11 with MIT-SHM, converts with swscale and encodes with NVENC or x264; see [desktop/README.md](../desktop/README.md#linux).

## Measurements

Two Android 14 phones (A and B) and a Windows PC on one Wi-Fi network. Other phones, routers and conditions will differ.

| Path | Result |
| --- | --- |
| Phone A to phone B, probe feed about 720x1500 | 26 to 29 fps at about 380 kbit/s, nothing lost. First picture 362 to 617 ms after subscribing. Encoder 14 to 26 ms, 4 to 6 ms more to the socket, under 2 ms to reassemble, 15 ms to decode, 11 to 19 ms added playout delay, frames 6 to 14 ms off their timestamps. About 65 to 75 ms from drawn to handed to the screen, before the display's refresh. |
| PC to phone A, Notepad being typed into, 1428x783 | 23 to 29 fps at 110 to 420 kbit/s, nothing lost. Reassembly under 1 ms, decoder 11 to 18 ms, playout 1 to 39 ms (15 ms average). |
| Phone A to the PC, probe feed 320x240 | Encoder 8 to 13 ms, 2 to 3.5 ms more to the socket, 27 fps at 78 kbit/s. |
| PC (wired) to phone A, Notepad 1428x783, healthy Wi-Fi (52 to 72 Mbit/s), mouse circling | 28.7 fps average at about 105 kbit/s, nothing lost, no keyframe request, 440 ms from a frame being made to shown. With the mouse still, 4 fps at about 2 kbit/s. |
| PC (wired) to phone A, Vivaldi playing YouTube, 1920x1078 with sound, about a minute | 28 to 29 fps at 1.3 Mbit/s average. Calm stretches: playout delay 230 to 330 ms (median 276 to 295), set by packets 100 to 230 ms late. In one 14 s episode Wi-Fi delivered up to 1.5 s late, the delay hit its 500 ms limit and about 100 chunks of sound were cut. 0 to 3 frames lost. |
| PC to a phone, VTube Studio avatar over Spout with alpha, 1920x1080 | 28 to 32 fps, 2.0 to 2.8 Mbit/s, 14 to 17 ms to decode, 27 to 52 resend requests a second, no frame lost. |
| PC to one phone, 1080p, quiet Wi-Fi, by profile | Playout delay 100 to 130 ms, about 200 ms and about 300 ms; bitrate about 2, 2.8 and 4 Mbit/s for Lowest delay, Balanced and Smoothest. |

The round trip as the hub reads it (the median of the last nine samples) was 8 to 11 ms from phone B and 17 to 27 ms from phone A, whose network thread also serves its encoder; between phone A and the PC 15 to 33 ms, while `ping` read 1 ms at best, 9 on average and 91 at worst. The radio is the main source of jitter. Real-time priority, operating rate and latency hints on the encoder did not change its time, and a low latency Wi-Fi lock had no visible effect, so neither is used. Not measured: the PC's encode time, glass to glass, Android to Android with alpha, and the offset between sound and picture on a real stream.

Two things a phone can do on its own. Its Wi-Fi can drop to a 1 Mbit/s receive rate for 10 to 20 seconds with the signal still strong, and nothing arrives meanwhile; seen twice in one 70 second run on one phone, not in the next two, and no 400 ms buffer hides it. And on a TECNO phone (Android 14, HiOS) the firmware's traffic controller (`TranTrafficController`, logged by `OemNetd`) rate-limited the hub for 3 to 9 seconds at a time, repeatedly, while Chrome played YouTube in front: the send thread got through about 9 packets a second, each send took 300 to 600 ms, and the PC player stopped until the limit lifted. Each stretch began and ended with an `OemNetd: sendNetworkOptimizeMsg ... isLimit` line for the hub's uid. An app cannot lift that limit.

## Known gaps

- Two devices both behind strict NATs cannot connect without a relay. Wi-Fi power saving can add latency.
- Every local app watching a remote feed gets its own stream, so two watchers cost twice the bandwidth.
- Sharing is per device, not per feed.
- Only hellos are rate limited; a flood of other packets is not, and the port cannot be changed. Someone who can forge a paired device's address can use up that address's hellos while the flood lasts.
- The mouse cursor is part of the captured picture.
- On Windows, sound is not synchronized with the picture.
- Hardware codec code and native capture and audio code are tested by running them, not by unit tests. Playing a sound-only feed on Windows, and handing a placeholder surface to an SDK sender watched by another device, are unit-tested in pieces but not run against a live stream.
