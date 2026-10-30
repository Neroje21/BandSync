package com.bandsync.core.time

import kotlin.math.abs

/** Largest correction applied per pong during playback, so a re-estimate can't make a beat visibly jump. */
private const val MAX_SLEW_NS = 500_000L

/** Beyond this the clock genuinely is wrong and slewing would take too long, so step straight to it. */
private const val STEP_THRESHOLD_NS = 50_000_000L

/**
 * A client's running estimate of the master clock. Written from the transport's callback thread and read
 * from the metronome thread, hence the volatile fields.
 */
class ClockSync(private val clock: MonotonicClock) : TimeBase {
    private val estimator = ClockEstimator()

    @Volatile
    private var appliedOffsetNs = 0L

    @Volatile
    var estimate: ClockEstimate? = null
        private set

    val locked: Boolean get() = estimate != null

    fun stampPing(): Long = clock.nowNs()

    /** [t3] must be read as soon as the pong arrives, before it is decoded or queued. */
    fun onPong(t0: Long, t1: Long, t2: Long, t3: Long): ClockEstimate? {
        val sample = ClockSample.of(t0, t1, t2, t3) ?: return estimate
        val next = estimator.add(sample) ?: return estimate
        val correction = next.offsetNs - appliedOffsetNs
        appliedOffsetNs = when {
            estimate == null || abs(correction) > STEP_THRESHOLD_NS -> next.offsetNs
            else -> appliedOffsetNs + correction.coerceIn(-MAX_SLEW_NS, MAX_SLEW_NS)
        }
        estimate = next
        return next
    }

    /** Forget all samples, for a new connection. The old offset stays in use until the next lock. */
    fun reset() {
        estimator.clear()
        estimate = null
    }

    override fun toMaster(localNs: Long): Long = localNs + appliedOffsetNs
    override fun toLocal(masterNs: Long): Long = masterNs - appliedOffsetNs
}
