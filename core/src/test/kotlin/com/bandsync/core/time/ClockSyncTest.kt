package com.bandsync.core.time

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val MS = 1_000_000L

class ClockSyncTest {
    @Test
    fun `sample recovers the offset when both legs are equal`() {
        val trueOffset = 5_000 * MS
        val t0 = 1_000 * MS
        val t1 = t0 + 30 * MS + trueOffset
        val t2 = t1 + 2 * MS
        val t3 = t0 + 62 * MS

        val sample = assertNotNull(ClockSample.of(t0, t1, t2, t3))

        assertEquals(60 * MS, sample.rttNs, "master's processing time is not network delay")
        assertEquals(trueOffset, sample.offsetNs)
    }

    @Test
    fun `sample error from a lopsided path is bounded by half the round trip`() {
        val trueOffset = -2_000 * MS
        val t0 = 1_000 * MS
        val t1 = t0 + 58 * MS + trueOffset // Nearly all the delay is on the way out.
        val t3 = t0 + 60 * MS

        val sample = assertNotNull(ClockSample.of(t0, t1, t1, t3))

        assertTrue(abs(sample.offsetNs - trueOffset) <= sample.rttNs / 2)
    }

    @Test
    fun `sample rejects impossible exchanges`() {
        assertNull(ClockSample.of(t0 = 100, t1 = 0, t2 = 0, t3 = 50), "reply before the request")
        assertNull(ClockSample.of(t0 = 0, t1 = 0, t2 = 0, t3 = 60_000 * MS), "minute-long round trip")
    }

    @Test
    fun `estimator waits for enough samples`() {
        val estimator = ClockEstimator(windowSize = 16, minSamples = 8)
        repeat(7) { assertNull(estimator.add(ClockSample(0, MS))) }
        assertNotNull(estimator.add(ClockSample(0, MS)))
    }

    @Test
    fun `estimator ignores congested exchanges`() {
        val trueOffset = 700 * MS
        val estimator = ClockEstimator()
        repeat(4) { estimator.add(ClockSample(trueOffset + it * 100_000L, 4 * MS)) }
        repeat(12) { estimator.add(ClockSample(trueOffset + 300 * MS, 600 * MS)) }

        val estimate = assertNotNull(estimator.estimate())

        assertTrue(abs(estimate.offsetNs - trueOffset) < MS)
        assertEquals(2 * MS, estimate.uncertaintyNs)
    }

    @Test
    fun `estimator forgets samples that leave the window`() {
        val estimator = ClockEstimator(windowSize = 8, minSamples = 8)
        repeat(8) { estimator.add(ClockSample(0, MS)) }
        repeat(8) { estimator.add(ClockSample(99 * MS, 2 * MS)) }

        assertEquals(99 * MS, estimator.estimate()?.offsetNs)
    }

    @Test
    fun `first lock steps straight to the estimate`() {
        val sync = lockedSync(offsetNs = 123_456 * MS)

        assertTrue(sync.locked)
        assertEquals(1_000L + 123_456 * MS, sync.toMaster(1_000))
    }

    @Test
    fun `small corrections slew instead of jumping`() {
        val sync = lockedSync(offsetNs = 0)
        val before = sync.toMaster(0)

        feed(sync, offsetNs = 10 * MS, count = 1)
        assertEquals(before, sync.toMaster(0), "one sample can't outvote the window")

        feed(sync, offsetNs = 10 * MS, count = 15)
        val after = sync.toMaster(0)
        assertTrue(after in 1..(8 * MS), "moved $after ns, expected a capped slew")
    }

    @Test
    fun `slewing converges on the new offset`() {
        val sync = lockedSync(offsetNs = 0)

        feed(sync, offsetNs = 10 * MS, count = 100)

        assertEquals(10 * MS, sync.toMaster(0))
    }

    @Test
    fun `a large error steps immediately`() {
        val sync = lockedSync(offsetNs = 0)

        feed(sync, offsetNs = 500 * MS, count = 16)

        assertEquals(500 * MS, sync.toMaster(0))
    }

    @Test
    fun `reset drops the lock but keeps the last offset in use`() {
        val sync = lockedSync(offsetNs = 40 * MS)

        sync.reset()

        assertFalse(sync.locked)
        assertEquals(40 * MS, sync.toMaster(0))
    }

    @Test
    fun `toLocal inverts toMaster`() {
        val sync = lockedSync(offsetNs = -777 * MS)
        assertEquals(555L, sync.toLocal(sync.toMaster(555)))
    }

    private fun lockedSync(offsetNs: Long) = ClockSync { 0 }.also { feed(it, offsetNs, count = 8) }

    /** Feeds symmetric exchanges, each with a 2 ms round trip. */
    private fun feed(sync: ClockSync, offsetNs: Long, count: Int) = repeat(count) {
        val t0 = 1_000 * MS
        sync.onPong(t0, t0 + MS + offsetNs, t0 + MS + offsetNs, t0 + 2 * MS)
    }
}
