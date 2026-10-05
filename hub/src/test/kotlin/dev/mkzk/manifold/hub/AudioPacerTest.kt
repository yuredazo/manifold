package dev.mkzk.manifold.hub

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPacerTest {

    private val millisecond = 1_000_000L
    private val chunkFrames = 1024
    private val rateHz = 48_000
    private val chunkNanos = chunkFrames * 1e9 / rateHz

    private class SimulatedPlayer(private val capacity: Int, playerSkew: Double) : PcmSink {
        var clock = 0L
        var currentChunk = -1
        val playedAt = HashMap<Int, Long>()
        private val bytesPerNano = 48_000 * (1 + playerSkew) * 4 / 1e9
        private var queued = 0.0
        private var lastUpdate = 0L

        override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
            queued = (queued - (clock - lastUpdate) * bytesPerNano).coerceAtLeast(0.0)
            lastUpdate = clock
            val taken = minOf(length, (capacity - queued).toInt()).coerceAtLeast(0)
            if (taken > 0 && currentChunk !in playedAt) playedAt[currentChunk] = clock + (queued / bytesPerNano).toLong()
            queued += taken
            return taken
        }
    }

    private class Run(val error: List<Double>, val cut: Int, val speedPpm: Int) {
        fun worst(afterChunks: Int = 2_800) = error.drop(afterChunks).maxOf { abs(it) }

        fun settled() = worst(afterChunks = error.size / 2)
    }

    private fun run(senderSkew: Double, playerSkew: Double, seconds: Int = 3_600, jitterMs: Double = 1.0): Run {
        val player = SimulatedPlayer(8_192, playerSkew)
        val pacer = AudioPacer(player, now = { player.clock }, waitUntil = { player.clock = maxOf(player.clock, it) })
        val random = Random(5)
        val pcm = ByteArray(chunkFrames * 4)
        val due = HashMap<Int, Long>()

        for (k in 0 until (seconds * 1e9 / chunkNanos).toInt()) {
            val dueAt = (k * chunkNanos / (1 + senderSkew) + 100 * millisecond + random.nextGaussian() * jitterMs * millisecond).toLong()
            due[k] = dueAt
            player.clock = maxOf(player.clock, dueAt - 30 * millisecond)
            player.currentChunk = k
            pacer.play(pcm, dueAt)
        }
        val error = player.playedAt.entries.sortedBy { it.key }.map { (it.value - due.getValue(it.key)) / 1e6 }
        return Run(error, pacer.cut, pacer.speedPpm)
    }

    @Test
    fun aSteadyStreamIntoAnIdlePlayerStaysOnTime() {
        val result = run(senderSkew = 0.0, playerSkew = 0.0)

        println("steady: worst error ${result.worst()} ms, cut ${result.cut}")
        assertTrue("worst ${result.worst()} ms", result.worst() < 10.0)
        assertEquals(0, result.cut)
    }

    @Test
    fun aSenderWhoseClockIsFastDoesNotMakeTheSoundDrift() {
        val result = run(senderSkew = 150e-6, playerSkew = 0.0)

        println("sender +150 ppm: worst error ${result.worst()} ms over an hour, cut ${result.cut}, speed ${result.speedPpm} ppm")
        assertTrue("worst ${result.worst()} ms", result.worst() < 15.0)
        assertTrue("cut ${result.cut}", result.cut <= 2)
        assertTrue("speed ${result.speedPpm}", abs(result.speedPpm + 150) < 60)
    }

    @Test
    fun aSenderWhoseClockIsSlowDoesNotMakeTheSoundDrift() {
        val result = run(senderSkew = -200e-6, playerSkew = 0.0)

        println("sender -200 ppm: worst error ${result.worst()} ms over an hour, cut ${result.cut}, speed ${result.speedPpm} ppm")
        assertTrue("worst ${result.worst()} ms", result.worst() < 15.0)
        assertTrue("cut ${result.cut}", result.cut <= 2)
    }

    @Test
    fun aPlayerThatRunsSlowSettlesInsteadOfFallingFurtherBehind() {
        val result = run(senderSkew = 0.0, playerSkew = -120e-6)

        println("player -120 ppm: worst error ${result.worst()} ms, settled ${result.settled()} ms, cut ${result.cut}, speed ${result.speedPpm} ppm")
        assertTrue("worst ${result.worst()} ms", result.worst() < 80.0)
        assertTrue("settled ${result.settled()} ms", result.settled() < 50.0)
        assertTrue("cut ${result.cut}", result.cut <= 12)
    }

    @Test
    fun aPlayerThatRunsFastKeepsTheSoundOnTime() {
        val result = run(senderSkew = 0.0, playerSkew = 120e-6)

        println("player +120 ppm: worst error ${result.worst()} ms over an hour, cut ${result.cut}")
        assertTrue("worst ${result.worst()} ms", result.worst() < 10.0)
        assertEquals(0, result.cut)
    }

    @Test
    fun aChunkThatIsAlreadyOldIsNotPlayed() {
        val player = SimulatedPlayer(8_192, 0.0)
        val pacer = AudioPacer(player, now = { player.clock }, waitUntil = { player.clock = maxOf(player.clock, it) })
        player.clock = 500 * millisecond

        pacer.play(ByteArray(4_096), dueAt = 100 * millisecond)

        assertEquals(1, pacer.cut)
        assertTrue(player.playedAt.isEmpty())
    }

    @Test
    fun squeezingKeepsTheToneAndTheChannels() {
        val pcm = ByteBuffer.allocate(chunkFrames * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until chunkFrames) {
            pcm.putShort((10_000 * sin(2 * Math.PI * 440 * i / rateHz)).toInt().toShort())
            pcm.putShort(0)
        }
        var clock = 0L
        var latest = ByteArrayOutputStream()
        val pacer = AudioPacer({ bytes, offset, length -> latest.write(bytes, offset, length); length }, now = { clock }, waitUntil = { clock = maxOf(clock, it) })
        val spacing = (chunkNanos * 0.995).toLong()
        repeat(600) {
            latest = ByteArrayOutputStream()
            clock = maxOf(clock, 100 * millisecond + it * spacing - 30 * millisecond)
            pacer.play(pcm.array(), 100 * millisecond + it * spacing)
        }

        val samples = ByteBuffer.wrap(latest.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val left = (0 until samples.limit() / 2).map { samples[it * 2].toInt() }
        val right = (0 until samples.limit() / 2).map { samples[it * 2 + 1].toInt() }
        val crossings = left.zipWithNext().count { (a, b) -> (a < 0) != (b < 0) }

        assertTrue("speed ${pacer.speedPpm}", pacer.speedPpm < -3_000)
        assertTrue("frames ${left.size}", left.size in 1_010..1_020)
        assertTrue("the other channel stays silent", right.all { it == 0 })
        assertTrue("crossings $crossings", crossings in 17..20)
        assertTrue("peak ${left.maxOrNull()}", left.maxOrNull()!! in 9_800..10_000)
    }
}
