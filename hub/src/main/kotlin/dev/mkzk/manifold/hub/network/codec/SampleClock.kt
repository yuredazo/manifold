package dev.mkzk.manifold.hub.network.codec

private const val CREEP_PER_MICRO = 200e-6

/**
 * A read finishes later than its samples were made, so the stamp follows the earliest times seen and only creeps upward,
 * which also follows a source whose clock runs slow.
 */
internal class SampleClock(private val sampleRate: Int) {
    private var anchorUs = 0.0
    private var lastUs = 0L
    private var anchored = false

    fun stamp(nowUs: Long, samplesBefore: Long, chunkSamples: Int): Long {
        val chunkUs = chunkSamples * 1_000_000L / sampleRate
        val candidate = (nowUs - chunkUs - samplesBefore * 1_000_000L / sampleRate).toDouble()
        anchorUs = if (!anchored) candidate else minOf(candidate, anchorUs + (nowUs - lastUs).coerceAtLeast(0) * CREEP_PER_MICRO)
        anchored = true
        lastUs = nowUs
        return anchorUs.toLong() + samplesBefore * 1_000_000L / sampleRate
    }
}
