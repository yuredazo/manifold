package dev.mkzk.manifold.hub.network.stream

import dev.mkzk.manifold.hub.network.protocol.Control
import java.util.Locale
import kotlin.math.abs

private const val WINDOW_NS = 1_000_000_000L
private const val REPORT_EVERY_NS = 500_000_000L
private const val NS_PER_MS = 1_000_000f

internal data class ReceiveWindow(
    val fps: Float,
    val kbps: Int,
    val lostFrames: Int,
    val stalls: Int,
    val keyframeRequests: Int,
    val resendRequests: Int,
    val assemblyMs: Float,
    val decodeMs: Float,
    val playoutMs: Float,
    /** Smoothed as in RFC 3550. */
    val jitterMs: Float,
)

/** Times are nanoseconds from one monotonic clock. Not thread-safe. */
internal class ReceiveStats(private var windowStart: Long) {
    private var bytes = 0L
    private var frames = 0
    private var lost = 0
    private var stalls = 0
    private var keyframeRequests = 0
    private var resendRequests = 0
    private var assemblyNs = 0L
    private var decoded = 0
    private var decodeNs = 0L
    private var playoutNs = 0L
    private var jitterMs = 0f
    private var reportStart = windowStart
    private var reportFragments = 0
    private var reportResends = 0
    private var reportLost = 0
    private var previousArrival = 0L
    private var previousTimestamp = 0
    private var hasPrevious = false

    fun fragment(byteCount: Int) {
        bytes += byteCount
        reportFragments++
    }

    fun frame(arrivedAt: Long, timestamp: Int, assemblyNs: Long) {
        frames++
        this.assemblyNs += assemblyNs
        if (hasPrevious) {
            val arrivalGapMs = (arrivedAt - previousArrival) / NS_PER_MS
            val timestampGapMs = (timestamp - previousTimestamp) / 90f
            jitterMs += (abs(arrivalGapMs - timestampGapMs) - jitterMs) / 16f
        }
        hasPrevious = true
        previousArrival = arrivedAt
        previousTimestamp = timestamp
    }

    fun playout(delayNs: Long) {
        playoutNs += delayNs
    }

    fun decoded(decodeNs: Long) {
        decoded++
        this.decodeNs += decodeNs
    }

    fun framesLost(count: Int) {
        lost += count
        reportLost += count
    }

    fun stall() {
        stalls++
    }

    fun keyframeRequested() {
        keyframeRequests++
    }

    fun resendRequested(fragments: Int) {
        resendRequests += fragments
        reportResends += fragments
    }

    fun report(streamId: Int, now: Long): Control.StreamReport? {
        val elapsed = now - reportStart
        if (elapsed < REPORT_EVERY_NS) return null
        val report = Control.StreamReport(
            streamId = streamId,
            windowMs = (elapsed / 1_000_000L).toInt(),
            fragments = reportFragments,
            resendRequests = reportResends,
            lostFrames = reportLost,
            jitterMs10 = (jitterMs * 10).toInt(),
        )
        reportStart = now
        reportFragments = 0
        reportResends = 0
        reportLost = 0
        return report
    }

    fun window(now: Long): ReceiveWindow? {
        val elapsed = now - windowStart
        if (elapsed < WINDOW_NS) return null
        val seconds = elapsed.toFloat() / WINDOW_NS
        val result = ReceiveWindow(
            fps = frames / seconds,
            kbps = (bytes * 8 / 1000 / seconds).toInt(),
            lostFrames = lost,
            stalls = stalls,
            keyframeRequests = keyframeRequests,
            resendRequests = resendRequests,
            assemblyMs = if (frames == 0) 0f else assemblyNs / NS_PER_MS / frames,
            decodeMs = if (decoded == 0) 0f else decodeNs / NS_PER_MS / decoded,
            playoutMs = if (frames == 0) 0f else playoutNs / NS_PER_MS / frames,
            jitterMs = jitterMs,
        )
        windowStart = now
        bytes = 0
        frames = 0
        lost = 0
        stalls = 0
        keyframeRequests = 0
        resendRequests = 0
        assemblyNs = 0
        decoded = 0
        decodeNs = 0
        playoutNs = 0
        return result
    }
}

internal class SendStats(private var windowStart: Long) {
    private var bytes = 0L
    private var frames = 0
    private var encodeNs = 0L
    private var encodeKnown = 0
    private var sendNs = 0L
    private var sent = 0

    /** [captureLagMs] is negative where the encoder could not tell. */
    fun frame(byteCount: Int, captureLagMs: Float) {
        bytes += byteCount
        frames++
        if (captureLagMs >= 0) {
            encodeNs += (captureLagMs * NS_PER_MS).toLong()
            encodeKnown++
        }
    }

    fun sent(sendNs: Long) {
        this.sendNs += sendNs
        sent++
    }

    fun window(streamId: Int, now: Long): Control.SenderStats? {
        val elapsed = now - windowStart
        if (elapsed < WINDOW_NS) return null
        val seconds = elapsed.toFloat() / WINDOW_NS
        val result = Control.SenderStats(
            streamId = streamId,
            bitrateKbps = (bytes * 8 / 1000 / seconds).toInt(),
            fps10 = (frames * 10 / seconds).toInt(),
            encodeMs10 = if (encodeKnown == 0) 0 else (encodeNs / NS_PER_MS * 10 / encodeKnown).toInt(),
            sendMs10 = if (sent == 0) 0 else (sendNs / NS_PER_MS * 10 / sent).toInt(),
        )
        windowStart = now
        bytes = 0
        frames = 0
        encodeNs = 0
        encodeKnown = 0
        sendNs = 0
        sent = 0
        return result
    }
}

internal data class StreamSnapshot(
    val feed: String,
    val device: String,
    val deviceKey: String,
    val window: ReceiveWindow,
    val rttMs: Float?,
    val sender: Control.SenderStats?,
)

private fun tenth(value: Float) = String.format(Locale.ROOT, "%.1f", value)

internal fun StreamSnapshot.headline(): String =
    "$feed  ${tenth(window.fps)} fps  ${window.kbps} kbps  rtt ${rttMs?.let { tenth(it) } ?: "?"} ms"

internal fun StreamSnapshot.details(): String {
    val receiving = "lost ${window.lostFrames}  resends asked ${window.resendRequests}  stalls ${window.stalls}  keyframe requests ${window.keyframeRequests}  " +
        "jitter ${tenth(window.jitterMs)} ms  assembly ${tenth(window.assemblyMs)} ms  decode ${tenth(window.decodeMs)} ms  playout ${tenth(window.playoutMs)} ms"
    val publishing = sender?.let { "  |  encode ${tenth(it.encodeMs10 / 10f)} ms  send ${tenth(it.sendMs10 / 10f)} ms  sent ${tenth(it.fps10 / 10f)} fps" } ?: ""
    return receiving + publishing
}

internal fun StreamSnapshot.describe(): String = "${headline()}  ${details()}  (from $device)"
