package dev.mkzk.manifold.hub.network.stream

import java.util.Random
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayoutClockTest {

    private val millisecond = 1_000_000L
    private val tick = 3_000 // 33.3 ms at 90 kHz

    private fun arrival(n: Int, transit: Long) = n * 33_333_333L + transit

    private fun intervals(times: List<Long>) = times.zipWithNext { a, b -> (b - a) / 1e6 }

    @Test
    fun aSteadyLinkAddsAlmostNoDelay() {
        val clock = PlayoutClock()

        val shown = (0 until 120).map { clock.schedule(arrival(it, 5 * millisecond), it * tick) }

        assertTrue("delay ${clock.delay / 1e6} ms", clock.delay <= 3 * millisecond)
        assertTrue(intervals(shown).all { abs(it - 33.3) < 0.1 })
    }

    @Test
    fun unevenArrivalComesOutEven() {
        val random = Random(7)
        val transits = (0 until 300).map { (5 + random.nextInt(15)) * millisecond }
        val arrivals = (0 until 300).map { arrival(it, transits[it]) }
        val clock = PlayoutClock()

        val shown = (0 until 300).map { clock.schedule(arrivals[it], it * tick) }

        fun spread(times: List<Long>): Double = intervals(times).drop(60).let { gaps -> gaps.maxOrNull()!! - gaps.minOrNull()!! }
        val raw = spread(arrivals)
        val smoothed = spread(shown)
        println("gap between pictures, largest minus smallest: arriving ${raw} ms, shown $smoothed ms, delay ${clock.delay / 1e6} ms")
        assertTrue("$smoothed ms against $raw ms", smoothed < raw / 2)
        assertTrue("delay ${clock.delay / 1e6} ms", clock.delay < 40 * millisecond)
    }

    @Test
    fun aFrameIsNeverShownBeforeItArrives() {
        val random = Random(3)
        val clock = PlayoutClock()

        repeat(300) {
            val arrived = arrival(it, (3 + random.nextInt(40)) * millisecond)
            assertTrue(clock.schedule(arrived, it * tick) >= arrived)
        }
    }

    @Test
    fun aFrameThatIsMuchLaterThanTheRestIsShownTheMomentItArrives() {
        val clock = PlayoutClock()
        repeat(60) { clock.schedule(arrival(it, 5 * millisecond), it * tick) }

        val late = arrival(60, 150 * millisecond)

        assertEquals(late, clock.schedule(late, 60 * tick))
    }

    @Test
    fun theDelayNeverPassesItsLimit() {
        val clock = PlayoutClock(maxDelay = 30 * millisecond)
        val random = Random(1)

        repeat(300) { clock.schedule(arrival(it, random.nextInt(200) * millisecond), it * tick) }

        assertTrue(clock.delay <= 32 * millisecond)
    }

    @Test
    fun timestampsThatWrapAroundKeepTheSchedule() {
        val clock = PlayoutClock()
        val start = Int.MAX_VALUE - 5 * tick

        val shown = (0 until 20).map { clock.schedule(arrival(it, 5 * millisecond), start + it * tick) }

        assertTrue(intervals(shown).all { abs(it - 33.3) < 0.1 })
    }

    @Test
    fun aPathThatGetsSlowerBecomesTheNewNormalAfterTheWindow() {
        val clock = PlayoutClock(window = 2_000_000_000L)
        repeat(90) { clock.schedule(arrival(it, 5 * millisecond), it * tick) }

        val shown = (90 until 300).map { clock.schedule(arrival(it, 35 * millisecond), it * tick) }

        val lastGaps = intervals(shown.takeLast(30))
        assertTrue(lastGaps.all { abs(it - 33.3) < 0.1 })
        assertTrue("delay ${clock.delay / 1e6} ms", clock.delay <= 3 * millisecond)
    }

    private fun followingTheLink() = PlayoutClock(
        window = 10_000_000_000L,
        minDelay = 30 * millisecond,
        maxDelay = 500 * millisecond,
        spikeFactor = 1.1,
        maxShrinkRate = 0.003,
    )

    private fun arrivalThroughStalls(n: Int, every: Long, length: Long): Long {
        val madeAt = n * 33_333_333L
        val into = madeAt % every
        return (if (into < length) madeAt - into + length else madeAt) + 5 * millisecond
    }

    @Test
    fun aStallThatHappenedLatelyIsRiddenOutTheNextTime() {
        val every = 1_500 * millisecond
        val legacy = PlayoutClock()
        val following = followingTheLink()
        var legacyLate = 0
        var followingLate = 0

        for (n in 0 until 1_800) {
            val arrived = arrivalThroughStalls(n, every, 150 * millisecond)
            val stalled = arrived - (n * 33_333_333L + 5 * millisecond) > 20 * millisecond
            if (legacy.schedule(arrived, n * tick) == arrived && stalled && n >= 900) legacyLate++
            if (following.schedule(arrived, n * tick) == arrived && stalled && n >= 900) followingLate++
        }

        println("frames shown late over the last 30 s of 150 ms stalls: legacy $legacyLate, following $followingLate, delay ${following.delay / 1e6} ms")
        assertTrue("legacy $legacyLate", legacyLate > 20)
        assertEquals(0, followingLate)
    }

    @Test
    fun aCalmLinkKeepsTheDelayNearItsFloor() {
        val clock = followingTheLink()

        repeat(600) { clock.schedule(arrival(it, 5 * millisecond), it * tick) }

        assertTrue("delay ${clock.delay / 1e6} ms", clock.delay <= 33 * millisecond)
    }

    @Test
    fun theOffsetShrinksSlowlyOnceTheStallsStop() {
        val clock = followingTheLink()
        repeat(900) { clock.schedule(arrivalThroughStalls(it, 1_500 * millisecond, 150 * millisecond), it * tick) }

        val calm = (900 until 1_800).map { clock.schedule(arrival(it, 5 * millisecond), it * tick) }

        val gaps = intervals(calm)
        assertTrue("shortest gap ${gaps.minOrNull()} ms", gaps.all { it >= 33.3 * 0.995 })
        assertTrue("delay ${clock.delay / 1e6} ms", clock.delay <= 33 * millisecond)
    }

    @Test
    fun pictureAndSoundWithTheSameTimestampAreShownTogether() {
        val clock = followingTheLink()
        val shown = HashMap<Int, Pair<Long, Long>>()

        for (n in 0 until 300) {
            val video = clock.schedule(arrival(n, 12 * millisecond), n * tick, kind = 0)
            val sound = clock.schedule(arrival(n, 4 * millisecond), n * tick, kind = 1)
            shown[n] = video to sound
        }

        val worst = shown.entries.filter { it.key > 100 }.maxOf { abs(it.value.first - it.value.second) }
        assertTrue("largest difference ${worst / 1000} us", worst < millisecond / 10)
    }

    @Test
    fun aPictureThatStartsLateIsStillOnTheSameTimelineAsTheSound() {
        val clock = followingTheLink()
        var worst = 0L
        var delayAfter = 0L

        for (n in 0 until 600) {
            val sound = clock.schedule(arrival(n, 4 * millisecond), n * tick, kind = 1)
            if (n >= 90) {
                val video = clock.schedule(arrival(n, 12 * millisecond), n * tick, kind = 0)
                if (n > 200) worst = maxOf(worst, abs(video - sound))
            }
            if (n == 599) delayAfter = clock.delay
        }

        assertTrue("picture and sound ${worst / 1000} us apart", worst < millisecond / 10)
        assertTrue("delay ${delayAfter / 1e6} ms", delayAfter <= 45 * millisecond)
    }
}
