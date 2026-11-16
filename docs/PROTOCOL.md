# Manifold protocol and security model

What the hub and the SDK do on one phone, as of protocol version 1, written from the source: the registry and access rules are in `hub/src/main/kotlin/dev/mkzk/manifold/hub/broker`. Most apps should use `ManifoldSender` and `ManifoldReceiver` and never touch the AIDL. This is for people who write another client, review the hub, or want to know what they are trusting.

## Roles

| Role | Meaning |
| --- | --- |
| Hub | One app, package `dev.mkzk.manifold`, with a bound service. It keeps the senders, receivers and subscriptions. |
| Sender | An app that announces a named feed and draws into the surfaces it is given. |
| Receiver | An app that lists senders and subscribes to one by name with a `Surface` it owns. It gets frames only once the owner has allowed it. |
| Owner | The person using the phone, who decides in the hub which receiving apps may watch. |

An app can be both. After a subscription is delivered the sender draws into the receiver's surface through the system's buffer queue, so the hub is not on the frame path.

## Connecting

Clients bind to the hub with the action `dev.mkzk.manifold.BIND`, restricted to the package `dev.mkzk.manifold`, which the SDK manifest declares in `<queries>` so apps targeting Android 11 or later can see it. The first call is `protocolVersion()`; the SDK refuses a hub whose version differs from its own and logs the mismatch.

The SDK keeps the binding alive. If the hub dies, is updated or is force-stopped, it rebinds after 1 second, backing off to 30 seconds. If the hub is not installed it listens for package installs, so a hub installed later is picked up within seconds, and keeps retrying with the delay growing to at most 5 minutes.

## The interface

Defined in `sdk/src/main/aidl/dev/mkzk/manifold/`.

| Call | Who | Meaning |
| --- | --- | --- |
| `registerSender(info, callback)` | Sender | Announce a feed. Returns the name it got, or null if refused. |
| `updateSender(info, callback)` | Sender | Change size, fps, the audio flag or the sound-only flag. The name cannot change. |
| `unregisterSender(callback)` | Sender | Remove the feed. |
| `registerReceiver(callback)` | Receiver | Start getting the sender list; the current list is pushed at once. |
| `unregisterReceiver(callback)` | Receiver | Stop, and drop all of this receiver's subscriptions. |
| `subscribe(receiver, senderName, surface, width, height, audioSink)` | Receiver | Watch a sender. Returns a subscription id, or null if refused. |
| `unsubscribe(subscriptionId)` | Receiver | Stop watching. |

Callbacks from the hub are `oneway`:

- `IManifoldSender.onSubscribe(id, surface, width, height, audioSink)` and `onUnsubscribe(id)`.
- `IManifoldReceiver.onSenders(list)`, always the full list.
- `IManifoldReceiver.onAccess(id, access)`, with 0 pending, 1 allowed or 2 blocked, sent when a subscription is made and whenever the owner changes the decision.

`SenderInfo` carries `name`, `label`, `packageName`, `width`, `height`, `fps`, `hasAudio`, `soundOnly` and `hasAlpha`. `soundOnly` is ignored without `hasAudio`; subscribers to a sound-only feed still pass a surface, which the sender is expected not to draw into.

## Behavior you can rely on

| Topic | Behavior |
| --- | --- |
| Names | 1 to 64 characters after trimming, no control characters. If a different app holds the name, the later one becomes `name (2)`, `name (3)` and so on. The same app announcing a name it holds takes over its old entry, which is how a restarted sender gets its name back. |
| Waiting subscriptions | A subscription to a name nobody holds is kept and delivered when a sender with that name registers. If that sender goes away, it waits again and the same surface goes to the next sender. |
| Approval | The hub keeps a decision per receiving app: allowed, blocked or ask. A new app is asked: its subscriptions wait, nothing is delivered, and the owner gets a notification. Changes apply at once: blocking takes back every feed the app has, allowing delivers what was waiting, and a blocked app receives an empty sender list. |
| What a decision is tied to | The package name and the SHA-256 of the signing certificate seen when the decision was made. A different certificate for the same package makes the decision stop applying, and the owner is asked again. If the certificate cannot be read, the package name alone is used. Uninstalling an app drops its decision, but Android does not tell a hub that was force-stopped at the time, so then the old decision stays until it is changed. |
| Several receivers | Each subscription is delivered separately, so a sender with three watchers gets three surfaces. |
| Cleanup | When a client's process dies, the hub drops its senders or receivers through a death recipient. Dropping a subscription releases the surface and closes the audio pipe the hub held. |
| Audio | A receiver that passes an audio sink gets 48 kHz, 2 channel, signed 16-bit little-endian PCM from the sender. Receivers that do not want audio pass null. |
| Hub restart | Every subscription ends. The SDK registers again and re-subscribes, so senders see `onUnsubscribe` and then a new `onSubscribe`. Decisions are stored and survive. |

## Limits

Enforced in the hub (`broker/Registry.kt`), not only the SDK:

| Limit | Value |
| --- | --- |
| Announced senders | 32 |
| Connected receivers | 16 |
| Subscriptions per app | 32 |
| Name length | 64 |
| Width and height | Up to 8192 |
| Frame rate | Up to 240 |

Dimensions in `subscribe` are clamped to 1 to 8192, and announced sizes and rates to their ranges. A call over a count limit is refused: `registerSender` and `subscribe` return null, and `registerReceiver` throws `IllegalStateException`.

## Versioning

The AIDL files are the wire contract; changing a method's signature or order breaks every installed client. So:

- Add new methods at the end of an interface.
- Add new `SenderInfo` fields at the end. Older readers skip fields they do not know.
- Raise `Manifold.PROTOCOL_VERSION` for any change an older client cannot handle. The SDK treats a different version as incompatible.

`onAccess` was added this way, at the end of `IManifoldReceiver`, without a version bump: an older receiver ignores the call and simply sees no frames until the owner allows it. The public Kotlin API is separate and is listed in `sdk/api/sdk.api`.

## Security model

This describes how the code behaves; it is not an audit. The registry's unit tests cover ownership checks, name rules and limits, and the probe's `misuse` phase covers SDK misuse. `HubService`, the Binder layer that reads the caller's uid, has no tests, and nothing has been tried with a deliberately hostile app.

### What the hub guarantees

- Caller identity comes from the system. The hub reads the caller's uid from Binder and replaces `label` and `packageName` in every announced `SenderInfo`, so a sender cannot claim to be another app. If several packages share a uid, the first is reported.
- Callers change only their own state. `updateSender`, `unregisterSender`, `unregisterReceiver`, `subscribe` and `unsubscribe` check that the caller's uid owns the entry, so an app that obtains another's callback or subscription id cannot use it.
- Inputs are bounded: names are validated, numbers clamped, counts limited, null arguments rejected.
- No frames in the hub. It forwards a surface and a pipe and never reads either.
- Receivers need the owner's approval, bound to the package and its signing certificate, so an impersonating build is asked about again. An app not allowed gets no frames, and a blocked app loses the ones it had.

### What it does not guarantee

- Anyone can bind. The service is exported without a permission on purpose: Android grants a custom permission only to apps installed after the app that declares it, so a client installed before the hub could never connect.
- Approval is all or nothing per app. An allowed receiver can watch every feed. The sender is told only the subscription id, the size and the surfaces, not which app watches, so it cannot refuse one receiver while allowing another.
- Sender names are visible before approval. Any app that connects as a receiver gets the sender list (name, label, package, size) until it is blocked; only video and audio are held back.
- Anyone can announce a feed. Senders need no approval. The owner sees them in the hub, but the hub does not stop an app announcing.
- A prompt can be ignored. The owner is told through a notification and the Live tab; with notifications off, an app stays pending until the owner opens the hub.
- Names are first come, first served. An app that announces `my-avatar` before the real one keeps that name, the real sender becomes `my-avatar (2)`, and receivers that subscribe by name get the wrong feed. A receiver that cares should show `label` and `packageName` from `SenderInfo` and check `packageName` against the sender it expects before subscribing.
- A sender writes whatever it likes into a surface it is given, so a receiver should treat the picture as untrusted content, like any image from another app.
- No rate limits. Counts are limited, but how often a client calls the hub is not.
- Everyone trusts the hub for identity and for passing surfaces along. A modified build of `dev.mkzk.manifold` gets all of that trust once installed. Android refuses to update the hub with a build signed by another key, but cannot stop a user from installing a different build after removing the original, so install the hub only from a source you trust.

If you find a problem, see [SECURITY.md](../SECURITY.md).
