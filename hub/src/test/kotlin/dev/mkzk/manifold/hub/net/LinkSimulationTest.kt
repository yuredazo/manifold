package dev.mkzk.manifold.hub.net

import java.util.PriorityQueue
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkSimulationTest {

    private val millisecond = 1_000_000L
    private val frameInterval = 33_333_333L

    private val KEYFRAME_TIMES = 5

    private class Outcome(
        val sent: Int,
        val deliveredIds: List<Int>,
        val latenciesMs: List<Float>,
        val keyframeRequests: Int,
        val finalKbps: Int,
        val kbpsOverTime: List<Int>,
    ) {
        val share get() = deliveredIds.size.toFloat() / sent

        fun shareFrom(firstId: Int) = deliveredIds.count { it >= firstId }.toFloat() / (sent - firstId)

        fun percentile(p: Float): Float = latenciesMs.sorted().let { if (it.isEmpty()) Float.MAX_VALUE else it[((it.size - 1) * p).toInt()] }
    }

    private class Event(val at: Long, val order: Int, val action: () -> Unit)

    private fun run(
        lossPercent: Double = 0.0,
        askForLostPieces: Boolean = true,
        seconds: Int = 30,
        startKbps: Int = 1_500,
        adapt: Boolean = false,
        bottleneckKbps: Int? = null,
        queueMs: Double = 60.0,
        oneWayMs: Double = 4.0,
        jitterMs: Double = 3.0,
        stallEveryMs: Int = 0,
        stallMs: Int = 0,
        holdMs: Long? = null,
    ): Outcome {
        val random = Random(42)
        val events = PriorityQueue(compareBy<Event>({ it.at }, { it.order }))
        var counter = 0
        var clock = 0L
        var busyUntil = 0L
        fun at(time: Long, action: () -> Unit) {
            events += Event(time, counter++, action)
        }

        fun travel(bytes: Int, back: Boolean = false, action: () -> Unit) {
            if (random.nextDouble() * 100 < lossPercent) return
            var leaves = clock
            if (stallEveryMs > 0) {
                val into = clock % (stallEveryMs * millisecond)
                if (into < stallMs * millisecond) leaves = clock - into + stallMs * millisecond
            }
            if (bottleneckKbps != null && !back) {
                val start = maxOf(clock, busyUntil)
                if ((start - clock) / 1e6 > queueMs) return
                busyUntil = start + bytes * 8L * 1_000_000L / bottleneckKbps
                leaves = busyUntil
            }
            at(leaves + ((oneWayMs + random.nextDouble() * jitterMs) * millisecond).toLong(), action)
        }

        val buffer = FrameBuffer().apply { if (holdMs != null) maxWait = holdMs * millisecond }
        val store = RetransmitStore()
        val rate = RateControl(minKbps = 200, maxKbps = startKbps)
        val captured = HashMap<Int, Long>()
        val deliveredIds = ArrayList<Int>()
        val latencies = ArrayList<Float>()
        var keyframeRequests = 0
        var keyframeWanted = true
        var lastKeyframeRequest = -1_000 * millisecond
        var fragmentsSeen = 0
        var resendsAsked = 0
        var lostSeen = 0
        val kbpsOverTime = ArrayList<Int>()

        fun deliver(frames: List<Assembled>) {
            for (assembled in frames) {
                deliveredIds += assembled.frame.id
                latencies += (clock - captured.getValue(assembled.frame.id)) / 1e6f
            }
        }

        fun sendToReceiver(fragments: List<ByteArray>) {
            fragments.forEach { fragment ->
                travel(fragment.size) {
                    fragmentsSeen++
                    deliver(buffer.add(fragment, clock))
                }
            }
        }

        val total = seconds * 30
        for (id in 0 until total) {
            val time = id * frameInterval
            at(time) {
                val key = keyframeWanted || id % 90 == 0
                keyframeWanted = false
                captured[id] = time
                val kbps = if (adapt) rate.kbps else startKbps
                val average = kbps * 1000 / 8 / 30
                val frame = Frame(id, (time / 11_111).toInt(), key, ByteArray(if (key) average * KEYFRAME_TIMES else average))
                val fragments = Fragmenter.split(frame)
                store.remember(id, fragments, time)
                sendToReceiver(fragments)
            }
        }

        val end = total * frameInterval + 500 * millisecond
        var pollAt = 0L
        while (pollAt <= end) {
            at(pollAt) {
                val result = buffer.poll(clock)
                deliver(result.frames)
                for (missing in result.missing) {
                    resendsAsked += if (missing.indexes.isEmpty()) 1 else missing.indexes.size
                    if (askForLostPieces) travel(30, back = true) { sendToReceiver(store.fragments(missing.frameId, missing.indexes)) }
                }
                if (buffer.needsKeyframe && clock - lastKeyframeRequest >= 500 * millisecond) {
                    lastKeyframeRequest = clock
                    keyframeRequests++
                    travel(30, back = true) { keyframeWanted = true }
                }
            }
            pollAt += 2 * millisecond
        }

        var reportAt = 500 * millisecond
        while (reportAt <= end) {
            at(reportAt) {
                val report = Control.StreamReport(1, 500, fragmentsSeen, resendsAsked, buffer.lostFrames - lostSeen, 0)
                fragmentsSeen = 0
                resendsAsked = 0
                lostSeen = buffer.lostFrames
                travel(30, back = true) { rate.onReport(report, clock) }
                kbpsOverTime += rate.kbps
            }
            reportAt += 500 * millisecond
        }

        while (events.isNotEmpty()) {
            val event = events.poll()
            clock = event.at
            event.action()
        }
        return Outcome(total, deliveredIds, latencies, keyframeRequests, rate.kbps, kbpsOverTime)
    }

    @Test
    fun aCleanLinkDeliversEveryFrameWithoutAsking() {
        val outcome = run()

        assertEquals(1f, outcome.share, 0f)
        assertTrue("p95 ${outcome.percentile(0.95f)} ms", outcome.percentile(0.95f) < 12f)
        assertEquals("only the first keyframe", 1, outcome.keyframeRequests)
    }

    @Test
    fun askingForLostPiecesKeepsTheVideoGoingWhereGivingUpDoesNot() {
        val giving = run(lossPercent = 2.0, askForLostPieces = false)
        val asking = run(lossPercent = 2.0)

        println(
            "2 percent loss: giving up delivered ${giving.share}, asking ${asking.share}; p95 ${asking.percentile(0.95f)} ms; " +
                "keyframe requests ${giving.keyframeRequests} against ${asking.keyframeRequests}",
        )
        assertTrue("without asking: ${giving.share}", giving.share < 0.9f)
        assertTrue("with asking: ${asking.share}", asking.share > 0.97f)
        assertTrue("p95 ${asking.percentile(0.95f)} ms", asking.percentile(0.95f) < 45f)
    }

    @Test
    fun waitingLongerRidesOutAStallThatShorterWaitsGiveUpOn() {
        val results = listOf(50L, 150L, 300L).associateWith { run(lossPercent = 1.0, stallEveryMs = 1_500, stallMs = 150, holdMs = it) }

        results.forEach { (hold, outcome) ->
            println("150 ms stalls, hold limit $hold ms: delivered ${outcome.share}, keyframe requests ${outcome.keyframeRequests}, p95 ${outcome.percentile(0.95f)} ms")
        }
        assertTrue("50 ms: ${results.getValue(50L).share}", results.getValue(50L).share < results.getValue(300L).share)
        assertTrue("300 ms: ${results.getValue(300L).share}", results.getValue(300L).share > 0.99f)
        assertEquals("only the first keyframe", 1, results.getValue(300L).keyframeRequests)
    }

    @Test
    fun aBadLinkStillGetsMostFramesThrough() {
        val asking = run(lossPercent = 8.0)

        println("8 percent loss: asking delivered ${asking.share}; p95 ${asking.percentile(0.95f)} ms; keyframe requests ${asking.keyframeRequests}")
        assertTrue("with asking: ${asking.share}", asking.share > 0.85f)
    }

    @Test
    fun aSenderThatBacksOffFitsAStreamIntoALinkThatIsTooSmall() {
        val fixed = run(seconds = 60, startKbps = 6_000, bottleneckKbps = 3_000)
        val adapting = run(seconds = 60, startKbps = 6_000, bottleneckKbps = 3_000, adapt = true)

        println(
            "6 Mbit/s into 3 Mbit/s: fixed delivers ${fixed.shareFrom(900)} of the last 30 s, adapting ${adapting.shareFrom(900)} " +
                "(p95 ${adapting.percentile(0.95f)} ms), settled at ${adapting.finalKbps} kbps",
        )
        assertTrue("fixed: ${fixed.shareFrom(900)}", fixed.shareFrom(900) < 0.5f)
        assertTrue("adapting: ${adapting.shareFrom(900)}", adapting.shareFrom(900) > 0.9f)
        // A keyframe is sent in one burst and the link's queue holds 22 KB, so the sender settles well under the link's size.
        // Pacing the sender at 1.2 to 3 times its bitrate was tried here and made it worse.
        assertTrue("settled at ${adapting.finalKbps}", adapting.finalKbps in 500..3_100)
    }
}
