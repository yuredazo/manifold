package dev.mkzk.manifold.hub.network.protocol

// A hello costs key agreements before it can be judged, so each source address may send only so many, as WireGuard
// allows: a burst of 5, then 20 a second, whatever they turn out to be. A device dialing sends one a second.
internal class HelloGate {
    enum class Verdict { PASS, DROP, DROP_AND_REPORT }

    // budgetMs is time saved up, one hello's worth every COST_MS.
    private class Bucket(var budgetMs: Long, var at: Long, var reportedAt: Long? = null)

    private val buckets = HashMap<String, Bucket>()

    fun admit(host: String, now: Long): Verdict {
        if (host !in buckets && buckets.size >= MAX_HOSTS) buckets.values.removeAll { now - it.at >= FULL_MS }
        val bucket = buckets.getOrPut(host) { Bucket(FULL_MS, now) }
        bucket.budgetMs = minOf(bucket.budgetMs + now - bucket.at, FULL_MS)
        bucket.at = now
        if (bucket.budgetMs >= COST_MS) {
            bucket.budgetMs -= COST_MS
            return Verdict.PASS
        }
        if (bucket.reportedAt?.let { now - it < REPORT_EVERY_MS } == true) return Verdict.DROP
        bucket.reportedAt = now
        return Verdict.DROP_AND_REPORT
    }

    companion object {
        const val PER_SECOND = 20
        const val BURST = 5
        private const val COST_MS = 1000L / PER_SECOND
        private const val FULL_MS = BURST * COST_MS
        private const val MAX_HOSTS = 1024
        private const val REPORT_EVERY_MS = 60_000L
    }
}
