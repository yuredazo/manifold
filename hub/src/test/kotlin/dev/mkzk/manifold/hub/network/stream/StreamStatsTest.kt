package dev.mkzk.manifold.hub.network.stream

import dev.mkzk.manifold.hub.network.protocol.Control
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamStatsTest {

    private val second = 1_000_000_000L
    private val millisecond = 1_000_000L

    @Test
    fun nothingIsReportedBeforeTheWindowIsFull() {
        val stats = ReceiveStats(windowStart = 0)

        assertNull(stats.window(second - 1))
        assertNotNull(stats.window(second))
    }

    @Test
    fun aWindowReportsRatesAndStartsOver() {
        val stats = ReceiveStats(windowStart = 0)
        repeat(30) {
            stats.fragment(8_000)
            stats.frame(arrivedAt = it * 33 * millisecond, timestamp = it * 2970, assemblyNs = 3 * millisecond)
            stats.decoded(8 * millisecond)
            stats.playout(12 * millisecond)
        }
        stats.framesLost(2)
        stats.stall()
        stats.keyframeRequested()
        stats.resendRequested(4)
        stats.resendRequested(3)

        val window = stats.window(second)!!

        assertEquals(30f, window.fps, 0.01f)
        assertEquals(1920, window.kbps)
        assertEquals(2, window.lostFrames)
        assertEquals(1, window.stalls)
        assertEquals(1, window.keyframeRequests)
        assertEquals(7, window.resendRequests)
        assertEquals(3f, window.assemblyMs, 0.01f)
        assertEquals(8f, window.decodeMs, 0.01f)
        assertEquals(12f, window.playoutMs, 0.01f)

        val next = stats.window(2 * second)!!
        assertEquals(0f, next.fps, 0f)
        assertEquals(0, next.kbps)
        assertEquals(0, next.lostFrames)
    }

    @Test
    fun framesThatArriveWhenTheirTimestampsSayHaveNoJitter() {
        val stats = ReceiveStats(windowStart = 0)
        repeat(40) { stats.frame(arrivedAt = it * 33_333_333L, timestamp = it * 3000, assemblyNs = 0) }

        assertEquals(0f, stats.window(2 * second)!!.jitterMs, 0.01f)
    }

    @Test
    fun framesThatArriveInBunchesShowAsJitter() {
        val stats = ReceiveStats(windowStart = 0)
        repeat(40) {
            val arrival = if (it % 2 == 0) it * 33_333_333L else (it - 1) * 33_333_333L + millisecond
            stats.frame(arrivedAt = arrival, timestamp = it * 3000, assemblyNs = 0)
        }

        assertTrue(stats.window(2 * second)!!.jitterMs > 5f)
    }

    @Test
    fun timestampsThatWrapAroundDoNotCountAsJitter() {
        val stats = ReceiveStats(windowStart = 0)
        stats.frame(arrivedAt = 0, timestamp = Int.MAX_VALUE - 1_000, assemblyNs = 0)
        stats.frame(arrivedAt = 33_333_333L, timestamp = Int.MAX_VALUE - 1_000 + 3000, assemblyNs = 0)

        assertEquals(0f, stats.window(2 * second)!!.jitterMs, 0.01f)
    }

    @Test
    fun theSendingSideAveragesWhatTheEncoderKnows() {
        val stats = SendStats(windowStart = 0)
        stats.frame(byteCount = 10_000, captureLagMs = 10f)
        stats.frame(byteCount = 10_000, captureLagMs = 20f)
        stats.frame(byteCount = 10_000, captureLagMs = -1f)
        repeat(3) { stats.sent(2 * millisecond) }

        val report = stats.window(streamId = 7, now = second)!!

        assertEquals(7, report.streamId)
        assertEquals(240, report.bitrateKbps)
        assertEquals(30, report.fps10)
        assertEquals("the unknown lag is left out of the average", 150, report.encodeMs10)
        assertEquals(20, report.sendMs10)
    }

    @Test
    fun aSnapshotReadsAsTwoLines() {
        val window = ReceiveWindow(fps = 29.8f, kbps = 2410, lostFrames = 1, stalls = 0, keyframeRequests = 2, resendRequests = 5, assemblyMs = 3.1f, decodeMs = 8.2f, playoutMs = 13.9f, jitterMs = 2.4f)
        val sender = Control.SenderStats(1, 2400, 300, 95, 4)

        val snapshot = StreamSnapshot("alpha", "Infinix", "key", window, rttMs = 6f, sender = sender)

        assertEquals("alpha  29.8 fps  2410 kbps  rtt 6.0 ms", snapshot.headline())
        assertEquals(
            "lost 1  resends asked 5  stalls 0  keyframe requests 2  jitter 2.4 ms  assembly 3.1 ms  decode 8.2 ms  playout 13.9 ms  |  encode 9.5 ms  send 0.4 ms  sent 30.0 fps",
            snapshot.details(),
        )
        assertEquals("alpha  29.8 fps  2410 kbps  rtt ? ms", snapshot.copy(rttMs = null).headline())
    }
}
