package dev.mkzk.manifold.hub.network.stream

internal enum class StreamProfile(
    val id: String,
    val minDelayMs: Long,
    val maxExtraDelayMs: Long,
    val holdMinMs: Long,
    val holdMaxMs: Long,
    val keyframeWaitMs: Long,
    val bitratePercent: Int,
) {
    LowLatency("low", 15, 150, 20, 120, 800, 70),
    Balanced("balanced", 30, 470, 30, 300, 1_500, 100),
    Smooth("smooth", 150, 1_500, 100, 900, 2_500, 140),
    ;

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: Balanced
    }
}
