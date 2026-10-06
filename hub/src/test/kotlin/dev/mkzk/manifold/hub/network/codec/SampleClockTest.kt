package dev.mkzk.manifold.hub.network.codec

import java.util.Random
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleClockTest {

    private val chunk = 1024
    private val chunkUs = chunk * 1_000_000L / 48_000

    private fun run(skew: Double, lateMs: Int, chunks: Int = 100_000): List<Double> {
        val clock = SampleClock(48_000)
        val random = Random(9)
        val errors = ArrayList<Double>()
        for (k in 0 until chunks) {
            val producedUs = 5_000_000 + (k + 1) * chunkUs / (1 + skew)
            val readUs = producedUs + (random.nextInt(lateMs + 1) * 1000)
            val stamp = clock.stamp(readUs.toLong(), k.toLong() * chunk, chunk)
            val truth = 5_000_000 + k * chunkUs / (1 + skew)
            errors += (stamp - truth) / 1000.0
        }
        return errors
    }

    @Test
    fun theStampIsWithinAMillisecondOfWhenTheChunkWasMadeOnASteadySource() {
        val errors = run(skew = 0.0, lateMs = 0)

        assertTrue("worst ${errors.maxOf { abs(it) }} ms", errors.maxOf { abs(it) } < 1.0)
    }

    @Test
    fun lateReadsDoNotMoveTheStamps() {
        val errors = run(skew = 0.0, lateMs = 8).drop(2_000)

        println("reads up to 8 ms late: stamps off by ${errors.maxOf { abs(it) }} ms at most")
        assertTrue("worst ${errors.maxOf { abs(it) }} ms", errors.maxOf { abs(it) } < 2.0)
    }

    @Test
    fun aSourceWhoseClockRunsFastOrSlowIsFollowedOverTwoHours() {
        for (skew in listOf(-150e-6, 150e-6)) {
            val errors = run(skew = skew, lateMs = 3).drop(2_000)

            println("source clock ${skew * 1e6} ppm: stamps off by ${errors.maxOf { abs(it) }} ms at most")
            assertTrue("skew $skew worst ${errors.maxOf { abs(it) }} ms", errors.maxOf { abs(it) } < 4.0)
        }
    }
}
