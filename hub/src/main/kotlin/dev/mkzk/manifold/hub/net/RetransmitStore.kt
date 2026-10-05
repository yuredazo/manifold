package dev.mkzk.manifold.hub.net

/** A frame is answered only so often: a receiver that keeps asking is on a link that will not deliver it. */
internal class RetransmitStore(
    private val keepFor: Long = 1_000_000_000L,
    private val keepBytes: Int = 4 shl 20,
) {
    private class Kept(val frameId: Int, val storedAt: Long, val fragments: List<ByteArray>) {
        var answered = 0
        val size get() = fragments.sumOf { it.size }
    }

    private val frames = ArrayDeque<Kept>()
    private var bytes = 0

    fun remember(frameId: Int, fragments: List<ByteArray>, now: Long) {
        val kept = Kept(frameId, now, fragments)
        frames.addLast(kept)
        bytes += kept.size
        while (frames.isNotEmpty() && (now - frames.first().storedAt > keepFor || bytes > keepBytes)) {
            bytes -= frames.removeFirst().size
        }
    }

    fun fragments(frameId: Int, indexes: List<Int>): List<ByteArray> {
        val kept = frames.lastOrNull { it.frameId == frameId } ?: return emptyList()
        if (kept.answered >= MAX_ANSWERS) return emptyList()
        kept.answered++
        return if (indexes.isEmpty()) kept.fragments else indexes.mapNotNull { kept.fragments.getOrNull(it) }
    }

    private companion object {
        const val MAX_ANSWERS = 6
    }
}
