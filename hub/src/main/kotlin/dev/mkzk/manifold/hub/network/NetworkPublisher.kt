package dev.mkzk.manifold.hub.network

import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import dev.mkzk.manifold.SenderInfo
import dev.mkzk.manifold.hub.broker.Access
import dev.mkzk.manifold.hub.broker.Owner
import dev.mkzk.manifold.hub.broker.ReceiverLink
import dev.mkzk.manifold.hub.broker.Registry
import dev.mkzk.manifold.hub.network.codec.AacEncoder
import dev.mkzk.manifold.hub.network.codec.H264Encoder
import dev.mkzk.manifold.hub.network.protocol.Control
import dev.mkzk.manifold.hub.network.protocol.Device
import dev.mkzk.manifold.hub.network.protocol.DeviceBook
import dev.mkzk.manifold.hub.network.protocol.Endpoint
import dev.mkzk.manifold.hub.network.protocol.Fragmenter
import dev.mkzk.manifold.hub.network.stream.RateControl
import dev.mkzk.manifold.hub.network.stream.RetransmitStore
import dev.mkzk.manifold.hub.network.stream.SendStats
import java.util.Locale

private const val TAG = "NetworkPublisher"
private const val STATS_TAG = "NetStats"

/** So senders and receivers the hub made on behalf of another device are never offered on again. */
internal const val REMOTE_PREFIX = "net:"

private const val MAX_STREAMS_PER_DEVICE = 4
private const val MAX_STREAMS = 8
private const val MAX_SIZE = 3840
private const val MIN_BITRATE_KBPS = 200
private const val MAX_BITRATE_KBPS = 40_000

internal class NetworkPublisher(
    private val endpoint: Endpoint,
    private val book: DeviceBook,
    private val registry: Registry,
    private val post: (() -> Unit) -> Unit,
) {
    private class Served(
        val link: ReceiverLink,
        val encoder: H264Encoder?,
        val audio: AacEncoder?,
        val stats: SendStats,
        val rate: RateControl,
    ) {
        val store = RetransmitStore()
        var lastReport: Control.StreamReport? = null
    }

    private val streams = HashMap<Pair<String, Int>, Served>()
    private val ownUid = Process.myUid()

    fun onSubscribe(device: Device, request: Control.Subscribe) {
        val current = book.find(device.publicKey) ?: return
        fun refuse(reason: Control.Refusal) = endpoint.refuseSubscribe(current.publicKey, request.streamId, reason)
        if (!current.send) return refuse(Control.Refusal.NOT_SHARED)
        val feed = registry.state.value.senders
            .firstOrNull { it.name == request.feed && !it.packageName.startsWith(REMOTE_PREFIX) } ?: return refuse(Control.Refusal.NOT_FOUND)
        val key = current.publicKey to request.streamId
        stop(key)
        if (streams.keys.count { it.first == current.publicKey } >= MAX_STREAMS_PER_DEVICE || streams.size >= MAX_STREAMS) {
            return refuse(Control.Refusal.BUSY)
        }

        val width = request.width.coerceIn(1, MAX_SIZE)
        val height = request.height.coerceIn(1, MAX_SIZE)
        val stats = SendStats(System.nanoTime())
        val rate = RateControl(MIN_BITRATE_KBPS, request.bitrateKbps.coerceIn(MIN_BITRATE_KBPS, MAX_BITRATE_KBPS))
        // A feed with no picture has nothing to encode, only its sound to pass on.
        val encoder = if (feed.soundOnly) null else try {
            H264Encoder(width, height, rate.kbps, request.fps) { frame ->
                val producedAt = System.nanoTime()
                post {
                    val stream = streams[key] ?: return@post
                    val fragments = Fragmenter.split(frame)
                    stream.store.remember(frame.id, fragments, producedAt)
                    endpoint.sendVideo(current.publicKey, request.streamId, fragments)
                    stats.frame(frame.encoded.size, frame.captureLagMs)
                    stats.sent(System.nanoTime() - producedAt)
                }
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "cannot encode '${feed.name}' for ${current.name}", e)
            return refuse(Control.Refusal.FAILED)
        }

        val pipe = if (request.audio && feed.hasAudio) ParcelFileDescriptor.createPipe() else null
        val audio = pipe?.let { (source, _) -> openAudio(source, current, request.streamId, key) }
        val audioSink = if (audio != null) pipe?.get(1) else null
        if (audio == null) pipe?.forEach { runCatching { it.close() } }

        val link = object : ReceiverLink {
            override val key = Any()
            override fun sendersChanged(senders: List<SenderInfo>) {}
            override fun accessChanged(subscriptionId: String, access: Access) {}
        }
        val owner = Owner(ownUid, REMOTE_PREFIX + current.fingerprint, "${current.name} (network)")
        val subscribed = registry.addReceiver(link, owner) &&
            registry.subscribe(link.key, ownUid, feed.name, encoder?.surface, width, height, audioSink) != null
        if (!subscribed) {
            registry.removeReceiver(link.key, ownUid)
            encoder?.stop()
            audio?.stop()
            audioSink?.close()
            return refuse(Control.Refusal.FAILED)
        }
        streams[key] = Served(link, encoder, audio, stats, rate)
    }

    /** A feed whose sound cannot be encoded is still sent, without it. */
    private fun openAudio(source: ParcelFileDescriptor, device: Device, streamId: Int, key: Pair<String, Int>): AacEncoder? = try {
        AacEncoder(source) { timestamp, frame ->
            post {
                if (streams.containsKey(key)) endpoint.sendAudio(device.publicKey, streamId, timestamp, frame)
            }
        }
    } catch (e: RuntimeException) {
        Log.w(TAG, "cannot encode sound for ${device.name}", e)
        null
    }

    fun onUnsubscribe(device: Device, streamId: Int) = stop(device.publicKey to streamId)

    fun tick() {
        val now = System.nanoTime()
        for ((key, stream) in streams) {
            val report = stream.stats.window(key.second, now) ?: continue
            val rtt = endpoint.rttMs(key.first)?.let { "%.1f".format(Locale.ROOT, it) } ?: "?"
            val peer = stream.lastReport?.let { "${it.lostFrames} lost, ${it.resendRequests} resends asked of ${it.fragments} fragments" } ?: "no report yet"
            Log.i(
                STATS_TAG,
                "send stream ${key.second} ${report.fps10 / 10f} fps ${report.bitrateKbps} kbps encode ${report.encodeMs10 / 10f} ms " +
                    "send ${report.sendMs10 / 10f} ms rtt $rtt ms target ${stream.rate.kbps} kbps; peer: $peer",
            )
            endpoint.sendSenderStats(key.first, report)
        }
    }

    fun onNack(device: Device, nack: Control.Nack) {
        val stream = streams[device.publicKey to nack.streamId] ?: return
        val fragments = stream.store.fragments(nack.frameId, nack.indexes)
        if (fragments.isNotEmpty()) endpoint.sendVideo(device.publicKey, nack.streamId, fragments)
    }

    fun onStreamReport(device: Device, report: Control.StreamReport) {
        val stream = streams[device.publicKey to report.streamId] ?: return
        stream.lastReport = report
        val kbps = stream.rate.onReport(report, System.nanoTime()) ?: return
        Log.i(STATS_TAG, "stream ${report.streamId}: ${report.lostFrames} lost, ${report.resendRequests} resends asked of ${report.fragments}, bitrate now $kbps kbps")
        stream.encoder?.setBitrate(kbps)
    }

    fun onKeyframeRequest(device: Device, streamId: Int) {
        streams[device.publicKey to streamId]?.encoder?.requestKeyframe()
    }

    fun stopDevice(publicKey: String) {
        streams.keys.filter { it.first == publicKey }.forEach(::stop)
    }

    fun enforce() {
        streams.keys.filter { book.find(it.first)?.send != true }.forEach(::stop)
    }

    fun stopAll() {
        streams.keys.toList().forEach(::stop)
    }

    private fun stop(key: Pair<String, Int>) {
        val stream = streams.remove(key) ?: return
        // The feed is told to stop drawing before the encoder goes away.
        registry.removeReceiver(stream.link.key, ownUid)
        stream.encoder?.stop()
        stream.audio?.stop()
    }
}
