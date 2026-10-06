package dev.mkzk.manifold.hub.network.stream

import kotlin.math.abs

/**
 * The spike memory keeps a stall that just happened from freezing the picture again. Picture and sound share one clock so
 * they stay together, and the offset shrinks slowly because sound played faster than it was made changes pitch.
 */
internal class PlayoutClock(
    private val window: Long = 3_000_000_000L,
    private val maxDelay: Long = 60_000_000L,
    private val minDelay: Long = 2_000_000L,
    private val spikeFactor: Double = 0.0,
    private val maxShrinkRate: Double = 1.0,
) {
    private class Sample(val arrivedAt: Long, val offset: Long)

    private val samples = ArrayDeque<Sample>()
    private val timestamps = LongArray(KINDS)
    private val previousTimestamps = IntArray(KINDS)
    private val started = BooleanArray(KINDS)
    private var origin = 0
    private var hasOrigin = false
    private var deviation = 0.0
    private var lead = Long.MIN_VALUE
    private var lastArrival = 0L

    var delay = 0L
        private set

    /** Moves smoothly, which is why sound is paced by it. */
    var ideal = 0L
        private set

    fun schedule(arrivedAt: Long, senderTimestamp: Int, kind: Int = 0): Long {
        if (!hasOrigin) {
            origin = senderTimestamp
            hasOrigin = true
        }
        // Every kind counts from the same first timestamp, not from its own, or the one that starts later would be off by that much.
        timestamps[kind] = if (started[kind]) timestamps[kind] + (senderTimestamp - previousTimestamps[kind]).toLong() else (senderTimestamp - origin).toLong()
        previousTimestamps[kind] = senderTimestamp
        started[kind] = true

        val madeAt = timestamps[kind] * 1_000_000_000L / 90_000L
        val offset = arrivedAt - madeAt
        samples.addLast(Sample(arrivedAt, offset))
        while (samples.first().arrivedAt < arrivedAt - window) samples.removeFirst()
        val quickest = samples.minOf { it.offset }

        deviation += (abs(offset - quickest) - deviation) / 16.0
        val wanted = if (spikeFactor > 0.0) maxOf(deviation * 2, (samples.maxOf { it.offset } - quickest) * spikeFactor) else deviation * 2
        delay = wanted.toLong().coerceAtMost(maxDelay) + minDelay

        val target = quickest + delay
        val elapsed = (arrivedAt - lastArrival).coerceAtLeast(0L)
        lead = if (lead == Long.MIN_VALUE || target >= lead) target else maxOf(target, lead - (elapsed * maxShrinkRate).toLong())
        lastArrival = arrivedAt
        ideal = madeAt + lead
        return maxOf(arrivedAt, ideal)
    }

    private companion object {
        const val KINDS = 2
    }
}
