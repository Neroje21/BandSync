package com.bandsync.core.time

private const val MAX_PLAUSIBLE_RTT_NS = 5_000_000_000L

/** One ping/pong exchange. [offsetNs] is what to add to the client clock to read the master clock. */
data class ClockSample(val offsetNs: Long, val rttNs: Long) {
    companion object {
        /**
         * NTP's four-timestamp formula. t0 and t3 are read on the client, t1 and t2 on the master:
         *
         *     rtt    = (t3 - t0) - (t2 - t1)
         *     offset = ((t1 - t0) + (t2 - t3)) / 2
         *
         * Returns null for an exchange no real network could have produced.
         */
        fun of(t0: Long, t1: Long, t2: Long, t3: Long): ClockSample? {
            val rttNs = (t3 - t0) - (t2 - t1)
            if (rttNs < 0 || rttNs > MAX_PLAUSIBLE_RTT_NS) return null
            return ClockSample(offsetNs = ((t1 - t0) + (t2 - t3)) / 2, rttNs = rttNs)
        }
    }
}

/** [uncertaintyNs] is half the chosen exchange's round trip, the worst case if all its delay sat on one leg. */
data class ClockEstimate(val offsetNs: Long, val uncertaintyNs: Long)

/**
 * Sliding-window offset filter. Queueing is what makes the two legs unequal and it also inflates the
 * round trip, so the fastest exchanges are the trustworthy ones: take the fastest quarter of the window,
 * then the median offset among those so one lucky-but-lopsided exchange can't decide the result.
 */
class ClockEstimator(
    private val windowSize: Int = 16,
    private val minSamples: Int = 8,
) {
    private val window = ArrayDeque<ClockSample>(windowSize)

    fun add(sample: ClockSample): ClockEstimate? {
        window.addLast(sample)
        if (window.size > windowSize) window.removeFirst()
        return estimate()
    }

    fun estimate(): ClockEstimate? {
        if (window.size < minSamples) return null
        val fastest = window.sortedBy { it.rttNs }.take(maxOf(window.size / 4, 2))
        val median = fastest.sortedBy { it.offsetNs }[(fastest.size - 1) / 2]
        return ClockEstimate(offsetNs = median.offsetNs, uncertaintyNs = median.rttNs / 2)
    }

    fun clear() = window.clear()
}
