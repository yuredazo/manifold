package dev.mkzk.manifold.hub

import android.os.Process
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.Surface
import dev.mkzk.manifold.Manifold
import dev.mkzk.manifold.SenderInfo
import dev.mkzk.manifold.hub.net.Assembled
import dev.mkzk.manifold.hub.net.Control
import dev.mkzk.manifold.hub.net.Device
import dev.mkzk.manifold.hub.net.Endpoint
import dev.mkzk.manifold.hub.net.FeedInfo
import dev.mkzk.manifold.hub.net.Frame
import dev.mkzk.manifold.hub.net.FrameBuffer
import dev.mkzk.manifold.hub.net.PlayoutClock
import dev.mkzk.manifold.hub.net.ReceiveStats
import dev.mkzk.manifold.hub.net.StreamSnapshot
import dev.mkzk.manifold.hub.net.describe

private const val TAG = "RemoteFeeds"
private const val STATS_TAG = "NetStats"
private const val KEYFRAME_REQUEST_EVERY_MS = 500L
private const val MIN_BITRATE_KBPS = 500
private const val MAX_BITRATE_KBPS = 20_000
private const val DEFAULT_FPS = Control.Subscribe.DEFAULT_FPS
private const val NANOS_PER_MS = 1_000_000f

private const val UNKNOWN_RTT_MS = 20f
private const val MIN_NACK_INTERVAL_NS = 10_000_000L

/** The delay grows when the link stalls and shrinks slowly afterwards, which keeps it short without freezing. */
private const val MIN_DELAY_NS = 30_000_000L
private const val MAX_EXTRA_DELAY_NS = 470_000_000L
private const val SPIKE_MEMORY_NS = 10_000_000_000L
private const val SPIKE_FACTOR = 1.1

/** Sound changes pitch if it is played faster than it was made, so the delay may shrink by this much of every second. */
private const val MAX_SHRINK_RATE = 0.003

/** Leaves room to decode and draw a frame that was held back to its due time. */
private const val HOLD_MARGIN_NS = 60_000_000L
private const val MIN_HOLD_NS = 30_000_000L
private const val MAX_HOLD_NS = 300_000_000L
private const val DECODE_LEAD_NS = 40_000_000L

internal class RemoteFeeds(
    private val endpoint: Endpoint,
    private val post: (() -> Unit) -> Unit,
    private val onStats: (List<StreamSnapshot>) -> Unit = {},
    private val onRefused: (deviceKey: String, feed: String, reason: Control.Refusal) -> Unit = { _, _, _ -> },
) {
    private class Received(
        val deviceKey: String,
        val deviceName: String,
        val streamId: Int,
        val feed: String,
        val subscriptionId: String,
        val decoder: H264Decoder?,
        val audio: AacDecoder?,
    ) {
        val buffer = FrameBuffer()

        /** Picture and sound share it, which is what keeps them together. */
        val playout = PlayoutClock(SPIKE_MEMORY_NS, MAX_EXTRA_DELAY_NS, MIN_DELAY_NS, SPIKE_FACTOR, MAX_SHRINK_RATE)
        val videoDue = ArrayDeque<DueFrame>()
        val stats = ReceiveStats(System.nanoTime())
        var lastKeyframeRequest = 0L
        var lostSeen = 0
        val subscribedAt = System.nanoTime()
        var shown = false
        var sender: Control.SenderStats? = null
        var latest: StreamSnapshot? = null
    }

    private class DueFrame(val frame: Frame, val showAt: Long)

    private inner class RemoteSender(
        val deviceKey: String,
        val deviceName: String,
        val feed: String,
        val fps: Int,
        val soundOnly: Boolean,
    ) : SenderLink {
        override val key = Any()

        override fun deliver(subscriptionId: String, surface: Surface?, width: Int, height: Int, audioSink: ParcelFileDescriptor?) {
            if (surface == null && !soundOnly) return
            post { start(this, subscriptionId, surface, width, height, audioSink) }
        }

        override fun revoke(subscriptionId: String) {
            post { stopStream(subscriptionId) }
        }
    }

    private val ownUid = Process.myUid()
    private val senders = HashMap<String, MutableMap<String, RemoteSender>>()
    private val streams = HashMap<Pair<String, Int>, Received>()
    private val lastStreamId = HashMap<String, Int>()

    fun update(device: Device, feeds: List<FeedInfo>) {
        val known = senders.getOrPut(device.publicKey) { HashMap() }
        val offered = feeds.associateBy { it.name }
        known.keys.filter { it !in offered }.forEach { removeSender(device.publicKey, it) }
        for (feed in feeds) {
            if (feed.name in known) continue
            val sender = RemoteSender(device.publicKey, device.name, feed.name, feed.fps, feed.soundOnly)
            val announced = SenderInfo().also {
                it.name = "${feed.name} (${device.name})".take(Manifold.MAX_NAME_LENGTH)
                it.width = feed.width
                it.height = feed.height
                it.fps = feed.fps
                it.hasAudio = feed.hasAudio
                it.soundOnly = feed.soundOnly
            }
            val owner = Owner(ownUid, REMOTE_PREFIX + device.fingerprint, device.name)
            if (Registry.instance.addSender(sender, announced, owner) != null) known[feed.name] = sender
        }
    }

    fun clear(deviceKey: String) {
        senders[deviceKey]?.keys?.toList()?.forEach { removeSender(deviceKey, it) }
        senders.remove(deviceKey)
    }

    /** The other device will not send this stream, so waiting for its picture would never end. */
    fun onRefused(device: Device, refusal: Control.SubscribeRefused) {
        val stream = streams[device.publicKey to refusal.streamId] ?: return
        stopStream(stream.subscriptionId)
        onRefused(device.publicKey, stream.feed, refusal.reason)
    }

    fun onVideo(device: Device, streamId: Int, fragment: ByteArray) {
        val stream = streams[device.publicKey to streamId] ?: return
        val now = System.nanoTime()
        stream.stats.fragment(fragment.size)
        decode(stream, stream.buffer.add(fragment, now), now)
    }

    // Every few milliseconds because a missing piece is only waited for a few frames.
    fun poll(now: Long) {
        for (stream in streams.values) {
            val roundTrip = ((endpoint.rttMs(stream.deviceKey) ?: UNKNOWN_RTT_MS) * NANOS_PER_MS).toLong()
            stream.buffer.nackInterval = (roundTrip * 13 / 10).coerceAtLeast(MIN_NACK_INTERVAL_NS)
            stream.buffer.maxWait = (stream.playout.delay - HOLD_MARGIN_NS).coerceIn(MIN_HOLD_NS, MAX_HOLD_NS)
            val result = stream.buffer.poll(now)
            for (missing in result.missing) {
                stream.stats.resendRequested(if (missing.indexes.isEmpty()) 1 else missing.indexes.size)
                endpoint.requestRetransmit(stream.deviceKey, Control.Nack(stream.streamId, missing.frameId, missing.indexes))
            }
            decode(stream, result.frames, now)
            release(stream, now)
        }
    }

    private fun release(stream: Received, now: Long) {
        while (stream.videoDue.firstOrNull()?.let { it.showAt - DECODE_LEAD_NS <= now } == true) {
            val due = stream.videoDue.removeFirst()
            stream.decoder?.push(due.frame, due.showAt)
        }
    }

    fun hasStreams() = streams.isNotEmpty()

    private fun decode(stream: Received, frames: List<Assembled>, now: Long) {
        for (assembled in frames) {
            stream.stats.frame(now, assembled.frame.timestamp, assembled.assemblyTime)
            val showAt = stream.playout.schedule(now, assembled.frame.timestamp)
            stream.stats.playout(showAt - now)
            stream.videoDue.addLast(DueFrame(assembled.frame, showAt))
        }
        val lost = stream.buffer.lostFrames
        if (lost != stream.lostSeen) {
            stream.stats.framesLost(lost - stream.lostSeen)
            stream.lostSeen = lost
        }
    }

    fun onSenderStats(device: Device, stats: Control.SenderStats) {
        streams[device.publicKey to stats.streamId]?.sender = stats
    }

    fun onAudio(device: Device, streamId: Int, timestamp: Int, frame: ByteArray) {
        val stream = streams[device.publicKey to streamId] ?: return
        stream.playout.schedule(System.nanoTime(), timestamp, kind = 1)
        stream.audio?.push(frame, stream.playout.ideal)
    }

    // The first request may be lost, so it is repeated while a stream waits.
    fun tick(now: Long) {
        var updated = false
        for (stream in streams.values) {
            if (stream.decoder != null && stream.buffer.needsKeyframe && now - stream.lastKeyframeRequest >= KEYFRAME_REQUEST_EVERY_MS) {
                stream.lastKeyframeRequest = now
                stream.stats.keyframeRequested()
                endpoint.requestKeyframe(stream.deviceKey, stream.streamId)
            }
            stream.stats.report(stream.streamId, System.nanoTime())?.let { endpoint.sendStreamReport(stream.deviceKey, it) }
            val window = stream.stats.window(System.nanoTime()) ?: continue
            val snapshot = StreamSnapshot(stream.feed, stream.deviceName, stream.deviceKey, window, endpoint.rttMs(stream.deviceKey), stream.sender)
            stream.latest = snapshot
            updated = true
            Log.i(STATS_TAG, "recv ${snapshot.describe()}")
            stream.audio?.let { Log.i(STATS_TAG, "audio ${it.speedPpm} ppm against the sender, ${it.cutChunks} chunks cut, delay ${stream.playout.delay / 1_000_000} ms") }
        }
        if (updated) publishStats()
    }

    private fun publishStats() {
        onStats(streams.values.mapNotNull { it.latest })
    }

    fun stopAll() {
        streams.values.map { it.subscriptionId }.forEach(::stopStream)
        senders.keys.toList().forEach(::clear)
    }

    private fun start(
        sender: RemoteSender,
        subscriptionId: String,
        surface: Surface?,
        width: Int,
        height: Int,
        audioSink: ParcelFileDescriptor?,
    ) {
        // Sound alone has nothing to play without a pipe to play it into.
        if (sender.soundOnly && audioSink == null) return
        val streamId = nextStreamId(sender.deviceKey)
        val decoder = if (sender.soundOnly || surface == null) null else try {
            H264Decoder(
                surface,
                width,
                height,
                onStall = {
                    post {
                        streams[sender.deviceKey to streamId]?.let {
                            it.buffer.needKeyframe()
                            it.stats.stall()
                        }
                    }
                },
                onShown = { nanos ->
                    post {
                        val stream = streams[sender.deviceKey to streamId] ?: return@post
                        stream.stats.decoded(nanos)
                        if (!stream.shown) {
                            stream.shown = true
                            Log.i(STATS_TAG, "first picture of ${stream.feed} ${(System.nanoTime() - stream.subscribedAt) / 1_000_000} ms after subscribing")
                        }
                    }
                },
            )
        } catch (e: RuntimeException) {
            Log.w(TAG, "cannot decode '${sender.feed}'", e)
            return
        }
        // A feed that cannot be decoded for sound still shows its picture.
        val audio = audioSink?.let {
            try {
                AacDecoder(it)
            } catch (e: RuntimeException) {
                Log.w(TAG, "cannot decode the sound of '${sender.feed}'", e)
                null
            }
        }
        if (sender.soundOnly && audio == null) return
        streams[sender.deviceKey to streamId] = Received(sender.deviceKey, sender.deviceName, streamId, sender.feed, subscriptionId, decoder, audio)
        val fps = sender.fps.takeIf { it > 0 }?.coerceAtMost(Control.Subscribe.MAX_FPS) ?: DEFAULT_FPS
        val kbps = (width.toLong() * height * fps * 7 / 100 / 1000).toInt().coerceIn(MIN_BITRATE_KBPS, MAX_BITRATE_KBPS)
        endpoint.subscribe(sender.deviceKey, Control.Subscribe(streamId, sender.feed, width, height, kbps, audio = audio != null, fps = fps))
    }

    private fun stopStream(subscriptionId: String) {
        val entry = streams.entries.firstOrNull { it.value.subscriptionId == subscriptionId } ?: return
        streams.remove(entry.key)
        endpoint.unsubscribe(entry.value.deviceKey, entry.value.streamId)
        entry.value.decoder?.stop()
        entry.value.audio?.stop()
        publishStats()
    }

    private fun removeSender(deviceKey: String, feed: String) {
        val sender = senders[deviceKey]?.remove(feed) ?: return
        Registry.instance.removeSender(sender.key, ownUid)
        // The registry only marks local subscriptions as waiting, so the streams are ended here.
        streams.values.filter { it.deviceKey == deviceKey && it.feed == feed }.map { it.subscriptionId }.forEach(::stopStream)
    }

    private fun nextStreamId(deviceKey: String): Int {
        val next = (lastStreamId[deviceKey] ?: 0) % 0xFFFF + 1
        lastStreamId[deviceKey] = next
        return next
    }
}
