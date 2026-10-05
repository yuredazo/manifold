package dev.mkzk.manifold.hub.net

/** Recovered losses do not count: a radio loses packets at any bitrate. */
internal class RateControl(private val minKbps: Int, private val maxKbps: Int) {
    var kbps = maxKbps
        private set

    private var cleanReports = 0
    private var changedAt = Long.MIN_VALUE / 2

    fun onReport(report: Control.StreamReport, now: Long): Int? {
        val requestedShare = report.resendRequests.toFloat() / (report.fragments + report.resendRequests).coerceAtLeast(1)
        val before = kbps
        when {
            report.lostFrames > 0 -> {
                cleanReports = 0
                if (now - changedAt >= BACK_OFF_EVERY_NS) {
                    kbps = (kbps * BACK_OFF_FACTOR).toInt().coerceAtLeast(minKbps)
                    changedAt = now
                }
            }
            requestedShare < CLEAN_BELOW -> {
                cleanReports++
                if (cleanReports >= CLEAN_REPORTS_TO_RAISE && now - changedAt >= RAISE_EVERY_NS) {
                    kbps = (kbps * RAISE_FACTOR).toInt().plus(RAISE_STEP_KBPS).coerceAtMost(maxKbps)
                    cleanReports = 0
                    changedAt = now
                }
            }
        }
        return kbps.takeIf { it != before }
    }

    private companion object {
        const val CLEAN_BELOW = 0.03f
        const val BACK_OFF_FACTOR = 0.85f
        const val BACK_OFF_EVERY_NS = 1_000_000_000L
        const val RAISE_EVERY_NS = 1_500_000_000L
        const val RAISE_FACTOR = 1.08f
        const val RAISE_STEP_KBPS = 50
        const val CLEAN_REPORTS_TO_RAISE = 3
    }
}
