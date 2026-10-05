# Manifold protocol and security model

This describes what the hub and the SDK do, as of protocol version 1. It was written from the source, so where it says "the hub does X" you can find X in `hub/src/main/kotlin/dev/mkzk/manifold/hub/`.

Most apps should use `ManifoldSender` and `ManifoldReceiver` and never touch the AIDL. This document is for people who write another client, review the hub, or want to know what they are trusting.

## Roles

- **Hub:** one app, package `dev.mkzk.manifold`, with a bound service. It keeps the list of senders, receivers and subscriptions.
- **Sender:** an app that announces a named feed and draws into surfaces it is given.
- **Receiver:** an app that lists senders, and subscribes to one by name with a `Surface` it owns. It only gets frames once the owner of the hub has allowed it.
- **Owner:** the person using the phone. They decide, in the hub app, which receiving apps may watch.

An app can be both. The hub is not on the frame path: after a subscription is delivered, the sender draws into the receiver's surface directly through the system's buffer queue.

## Connecting

Clients bind to the hub with the action `dev.mkzk.manifold.BIND`, restricted to the package `dev.mkzk.manifold`. The SDK manifest declares that package in `<queries>`, so apps targeting Android 11 or later can see it.

The first call is `protocolVersion()`. The SDK refuses a hub whose version differs from its own and logs the mismatch.

The SDK keeps the binding alive. If the hub dies, is updated or is force-stopped, it rebinds, retrying after 1 second and backing off to 30 seconds. If the hub is not installed it listens for package installs, so a hub installed later is picked up within seconds. It also keeps retrying, with the delay growing to at most 5 minutes.

## The interface

Defined in `sdk/src/main/aidl/dev/mkzk/manifold/`.

| Call | Who | Meaning |
| --- | --- | --- |
| `registerSender(info, callback)` | sender | Announce a feed. Returns the name it got, or null if refused. |
| `updateSender(info, callback)` | sender | Change size, fps or the audio flag. The name cannot change. |
| `unregisterSender(callback)` | sender | Remove the feed. |
| `registerReceiver(callback)` | receiver | Start getting the sender list. The current list is pushed at once. |
| `unregisterReceiver(callback)` | receiver | Stop, and drop all of this receiver's subscriptions. |
| `subscribe(receiver, senderName, surface, width, height, audioSink)` | receiver | Watch a sender. Returns a subscription id, or null if refused. |
| `unsubscribe(subscriptionId)` | receiver | Stop watching. |

Callbacks from the hub are `oneway`:

- `IManifoldSender.onSubscribe(id, surface, width, height, audioSink)` and `onUnsubscribe(id)`.
- `IManifoldReceiver.onSenders(list)`, always the full list.
- `IManifoldReceiver.onAccess(id, access)`, where `access` is 0 (pending), 1 (allowed) or 2 (blocked). It is sent when a subscription is made and whenever the owner changes the decision.

`SenderInfo` carries `name`, `label`, `packageName`, `width`, `height`, `fps` and `hasAudio`.

## Behavior you can rely on

- **Names:** 1 to 64 characters after trimming, no control characters. If a different app already holds the name, the later one is registered as `name (2)`, `name (3)` and so on. The same app announcing the name it already holds takes over its old entry, which is how a restarted sender gets its name back.
- **Waiting subscriptions:** a subscription to a name nobody holds is kept. It is delivered when a sender with that name registers. If that sender goes away, the subscription goes back to waiting and the same surface is delivered to the next sender.
- **Approval:** the hub keeps a decision per receiving app: allowed, blocked, or ask. A new app is asked. Until the owner allows it, its subscriptions wait, nothing is delivered, and the owner gets a notification. Changes apply at once: blocking an app takes back every feed it already has, and allowing it delivers what was waiting. A blocked app also receives an empty sender list.
- **What a decision is tied to:** the package name and the SHA-256 of the signing certificate seen when the decision was made. If the same package later shows up with a different certificate, the decision stops applying and the owner is asked again. If the hub cannot read an app's certificate, the decision falls back to the package name alone. When an app is uninstalled the hub drops its decision, so a later install starts undecided. Android does not deliver that notice to a hub that was force-stopped at the time, in which case the old decision stays until the app is allowed, blocked or reset again.
- **Several receivers:** each subscription is delivered separately, so a sender with three watchers gets three surfaces.
- **Cleanup:** when a client's process dies, the hub drops its senders or receivers through a death recipient. Dropping a subscription releases the surface and closes the audio pipe the hub held.
- **Audio:** if a receiver passes an audio sink, the sender writes 48 kHz, 2 channel, signed 16-bit little-endian PCM into it. Receivers that do not want audio pass null.
- **Hub restart:** every subscription ends. The SDK registers again and re-issues subscriptions, and senders see `onUnsubscribe` followed by a new `onSubscribe`. Decisions are stored by the hub and survive the restart.

## Limits

Enforced in the hub (`Registry.kt`), not only in the SDK:

| Limit | Value |
| --- | --- |
| Announced senders | 32 |
| Connected receivers | 16 |
| Subscriptions per app | 32 |
| Name length | 64 |
| Width and height | up to 8192 |
| Frame rate | up to 240 |

Dimensions in `subscribe` are clamped to 1 to 8192, and announced sizes and rates are clamped to their ranges. A call over a count limit is refused: `registerSender` and `subscribe` return null, and `registerReceiver` throws `IllegalStateException`.

## Versioning

The AIDL files are the wire contract. Changing a method's signature, or reordering methods, breaks every installed client. The rules for changes:

- Add new methods at the end of an interface.
- Add new `SenderInfo` fields at the end. Older readers skip fields they do not know.
- Raise `Manifold.PROTOCOL_VERSION` for any change an older client cannot handle. The SDK treats a different version as incompatible.

`onAccess` was added this way, at the end of `IManifoldReceiver`, without a version bump. A receiver built before it ignores the call and simply sees no frames until the owner allows it.

The public Kotlin API is separate and is locked by `sdk/api/sdk.api`.

## Security model

This is a description of how the code behaves, not an audit. The registry's unit tests cover ownership checks, name rules and limits, and the probe's `misuse` phase covers misuse of the SDK. `HubService`, the Binder layer that reads the caller's uid, has no tests, and nothing has been tried with a deliberately hostile app.

### What the hub guarantees

- **Caller identity comes from the system.** The hub reads the caller's uid from Binder and replaces `label` and `packageName` in every announced `SenderInfo`. A sender cannot claim to be another app. If several packages share a uid, the first one is reported.
- **Callers can only change their own state.** `updateSender`, `unregisterSender`, `unregisterReceiver`, `subscribe` and `unsubscribe` check that the caller's uid matches the owner of the entry. A different app that obtains a callback or a subscription id cannot use it.
- **Inputs are bounded.** Names are validated, numbers are clamped, and counts are limited as above. Null arguments are rejected.
- **No frames in the hub.** It forwards a surface and a pipe and never reads either.
- **Receivers need the owner's approval.** An app that has not been allowed gets no frames, and an app that was blocked loses the ones it had. A decision is bound to the package and its signing certificate, so an impersonating build is asked about again.

### What it does not guarantee

- **Anyone can bind.** The service is exported with no permission. This is deliberate: Android grants a custom permission only to apps installed after the app that declares it, so a client installed before the hub could never connect.
- **Approval is all or nothing per app.** An allowed receiver can watch every feed. The sender is not asked and is told only the subscription id, the size and the surfaces, not which app is watching, so a sender cannot refuse one receiver while allowing another.
- **Sender names are visible before approval.** Any app that connects as a receiver gets the list of announced senders (name, label, package, size) until the owner blocks it. Only the video and audio are held back.
- **Anyone can announce a feed.** Senders need no approval. The owner sees them in the hub, but the hub does not stop an app from announcing.
- **A prompt can be ignored.** The owner is told through a notification and the Live tab. If notifications are off, an app stays pending until the owner opens the hub.
- **Names are first come, first served.** The first app to announce a name keeps it. A malicious app that announces `my-avatar` before the real one gets that name, and the real sender becomes `my-avatar (2)`. Receivers subscribe by name, so they would get the wrong feed. A receiver that cares should show `label` and `packageName` from `SenderInfo` to the user, and check `packageName` against the sender it expects before subscribing.
- **A sender writes whatever it likes into a surface it is given.** A receiver should treat the picture as untrusted content, like any image from another app.
- **No rate limits.** The counts above are limited, but how often a client calls the hub is not.
- **Everyone trusts the hub.** Senders and receivers rely on it for identity and for passing surfaces along. A modified build of `dev.mkzk.manifold`, once installed, gets all of that trust. Android refuses to update the hub with a build signed by a different key, but it cannot stop a user from installing a different build after removing the original. Install the hub only from a source you trust.

If you find a problem, see [SECURITY.md](../SECURITY.md).
