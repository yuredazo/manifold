package dev.mkzk.manifold.hub.network.codec

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt

private const val BYTES_PER_FRAME = 4
private const val NANOS = 1_000_000_000.0
/** A second-order loop with these gains settles in a few seconds and averages out about 1 ms of noise on the due times. */
private const val PHASE_GAIN = 0.05
private const val FREQUENCY_GAIN = PHASE_GAIN * PHASE_GAIN / 4
private const val MAX_SPEED_CHANGE = 0.005
/** A step in the due times bigger than this is a stall or a change of delay, not a clock that runs differently. */
private const val RELOCK_AFTER = 8_000_000L
private const val TRIM_PER_WAIT_MS = 0.02e-6
private const val WAIT_TOLERANCE = 3_000_000L
private const val TRIM_DECAY_PER_SECOND = 2e-6
private const val MAX_TRIM = 0.004

/** Takes what fits and returns how many bytes that was, zero when the pipe is full. */
internal fun interface PcmSink {
    fun write(bytes: ByteArray, offset: Int, length: Int): Int
}

/**
 * A pipe allowed to fill would make the sound lag the picture by more every minute. So chunks are squeezed to follow the
 * sender's clock, old chunks are cut, and a player that runs slow gets later chunks squeezed until it keeps up.
 */
internal class AudioPacer(
    private val sink: PcmSink,
    private val now: () -> Long,
    private val waitUntil: (Long) -> Unit,
    private val sampleRate: Int = 48_000,
    private val maxLate: Long = 25_000_000L,
) {
    private val nominalNanosPerFrame = NANOS / sampleRate
    private var locked = false
    private var phase = 0.0
    private var lastFrames = 0
    private var nanosPerFrame = nominalNanosPerFrame
    private var trim = 0.0
    private var trimUpdatedAt = 0L

    /** The part of a frame left over by squeezing, so a tiny speed change is not lost to rounding. */
    private var carry = 0.0

    private val rate get() = (nanosPerFrame / nominalNanosPerFrame).coerceIn(1 - MAX_SPEED_CHANGE, 1 + MAX_SPEED_CHANGE)

    var cut = 0
        private set

    /** In parts per million. */
    val speedPpm get() = ((rate * (1 - trim) - 1) * 1e6).roundToInt()

    /** [dueAt] is on the [now] clock. */
    fun play(pcm: ByteArray, dueAt: Long) {
        val frames = pcm.size / BYTES_PER_FRAME
        if (frames == 0) return
        val smoothed = followTheDueTimes(dueAt, frames)

        if (now() - dueAt > STALE) {
            cut++
            return
        }
        val exact = frames * rate * (1 - trim) + carry
        val outFrames = exact.toInt().coerceAtLeast(1)
        carry = exact - outFrames
        val out = resample(pcm, frames, outFrames)
        waitUntil(smoothed)
        val began = now()
        write(out, began = began, deadline = began + maxLate)
    }

    private fun followTheDueTimes(dueAt: Long, frames: Int): Long {
        if (!locked) {
            locked = true
            phase = dueAt.toDouble()
        } else {
            val expected = phase + lastFrames * nanosPerFrame
            val error = dueAt - expected
            if (abs(error) > RELOCK_AFTER) {
                // The timeline moved. The speed stays what it was.
                phase = dueAt.toDouble()
            } else {
                phase = expected + PHASE_GAIN * error
                nanosPerFrame += FREQUENCY_GAIN * error / lastFrames
            }
        }
        lastFrames = frames
        val elapsed = (dueAt - trimUpdatedAt).coerceAtLeast(0) / NANOS
        trim = (trim - elapsed * TRIM_DECAY_PER_SECOND).coerceAtLeast(0.0)
        trimUpdatedAt = dueAt
        return phase.toLong()
    }

    private fun write(bytes: ByteArray, began: Long, deadline: Long) {
        var offset = 0
        while (offset < bytes.size) {
            val taken = sink.write(bytes, offset, bytes.size - offset)
            offset += taken
            if (offset >= bytes.size) break
            // Cutting in the middle of a sample would swap the channels for everything after it.
            if (now() >= deadline && offset % BYTES_PER_FRAME == 0) {
                cut++
                break
            }
            waitUntil(now() + POLL)
        }
        // How long the chunk took to get in, not how late it arrived: a slow Wi-Fi hop says nothing about the player.
        val waited = now() - began
        if (waited > WAIT_TOLERANCE) trim = (trim + (waited - WAIT_TOLERANCE) / 1e6 * TRIM_PER_WAIT_MS).coerceAtMost(MAX_TRIM)
    }

    private fun resample(pcm: ByteArray, frames: Int, outFrames: Int): ByteArray {
        if (outFrames == frames) return pcm
        val input = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val buffer = ByteBuffer.allocate(outFrames * BYTES_PER_FRAME).order(ByteOrder.LITTLE_ENDIAN)
        val out = buffer.asShortBuffer()
        val step = if (outFrames > 1) (frames - 1).toDouble() / (outFrames - 1) else 0.0
        for (i in 0 until outFrames) {
            val position = i * step
            val whole = position.toInt().coerceAtMost(frames - 1)
            val next = minOf(whole + 1, frames - 1)
            val fraction = position - whole
            for (channel in 0 until 2) {
                val a = input[whole * 2 + channel].toInt()
                val b = input[next * 2 + channel].toInt()
                out.put(i * 2 + channel, (a + (b - a) * fraction).roundToInt().toShort())
            }
        }
        return buffer.array()
    }

    private companion object {
        /** A chunk this far past its time is not worth playing, and playing it would put the sound behind the picture. */
        const val STALE = 60_000_000L
        const val POLL = 2_000_000L
    }
}
