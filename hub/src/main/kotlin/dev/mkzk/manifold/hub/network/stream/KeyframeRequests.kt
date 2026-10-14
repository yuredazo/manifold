package dev.mkzk.manifold.hub.network.stream

internal class KeyframeRequests {
    private var lastAt = Long.MIN_VALUE / 2
    private var asked = 0

    fun due(nowMs: Long, needed: Boolean, repairing: Boolean): Boolean {
        if (!needed) {
            asked = 0
            return false
        }
        if (repairing) return false
        val wait = if (asked == 0) 0L else minOf(FIRST_WAIT_MS shl (asked - 1), MAX_WAIT_MS)
        if (nowMs - lastAt < wait) return false
        lastAt = nowMs
        asked++
        return true
    }

    private companion object {
        const val FIRST_WAIT_MS = 500L
        const val MAX_WAIT_MS = 2_000L
    }
}
