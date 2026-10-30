package com.bandsync.core.metronome

import com.bandsync.core.time.ClockSync
import kotlin.math.abs
import kotlin.math.ln
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

private const val MS = 1_000_000L

/** A phone's clock: started at an arbitrary value and running [skewPpm] fast or slow. */
private class PhoneClock(private val baseNs: Long, private val skewPpm: Double) {
    private val rate = 1 + skewPpm / 1_000_000
    fun read(trueNs: Long): Long = baseNs + (trueNs * rate).toLong()
    fun trueTimeOf(localNs: Long): Long = ((localNs - baseNs) / rate).toLong()
}

/** One-way delay: a floor, exponential queueing, and occasional long stalls. Each leg is drawn separately. */
private class LinkModel(
    private val floorNs: Long,
    private val meanQueueNs: Long,
    private val stallChance: Double,
    private val maxStallNs: Long,
    private val random: Random,
) {
    fun oneWayNs(): Long {
        val queue = (-ln(1 - random.nextDouble()) * meanQueueNs).toLong()
        val stall = if (random.nextDouble() < stallChance) random.nextLong(maxStallNs) else 0
        return floorNs + queue + stall
    }
}

/**
 * A band of phones sharing nothing but ping/pong exchanges and one Timeline. Reports how far apart their
 * flashes land in true time, the number that matters and the one no single device can measure by itself.
 */
class BandSimulationTest {
    private val master = PhoneClock(baseNs = 9_000_000 * MS, skewPpm = 8.0)
    private val phones = listOf(
        PhoneClock(baseNs = 123_456_789 * MS, skewPpm = 41.0),
        PhoneClock(baseNs = 42 * MS, skewPpm = -50.0),
        PhoneClock(baseNs = 7_777_777 * MS, skewPpm = 13.0),
        PhoneClock(baseNs = 300_000_000 * MS, skewPpm = -22.0),
    )

    /** Maximum distance in true time between any phone's flash and the master's, over one song. */
    private fun worstErrorNs(
        link: LinkModel,
        keepSyncing: Boolean,
        bpm: Int = 120,
        beats: Long = 600,
    ): Long {
        val syncs = phones.map { ClockSync { 0 } }

        fun exchange(index: Int, sendAtTrueNs: Long) {
            val phone = phones[index]
            val arriveNs = sendAtTrueNs + link.oneWayNs()
            val replyNs = arriveNs + 200_000
            val backNs = replyNs + link.oneWayNs()
            syncs[index].onPong(phone.read(sendAtTrueNs), master.read(arriveNs), master.read(replyNs), phone.read(backNs))
        }

        repeat(16) { ping -> phones.indices.forEach { exchange(it, ping * 50 * MS) } }

        val songStartTrueNs = 3_000 * MS
        val timeline = Timeline(master.read(songStartTrueNs), 0, 0, bpm, beatsPerBar = 4)
        val periodNs = 60_000 * MS / bpm
        var worstNs = 0L

        for (beat in 0 until beats) {
            val masterNs = timeline.masterTimeOf(beat)
            val masterFlashTrueNs = master.trueTimeOf(masterNs)
            if (keepSyncing && beat % (500 * MS / periodNs).coerceAtLeast(1) == 0L) {
                phones.indices.forEach { exchange(it, masterFlashTrueNs - periodNs / 2) }
            }
            phones.forEachIndexed { index, phone ->
                val flashTrueNs = phone.trueTimeOf(syncs[index].toLocal(masterNs))
                worstNs = maxOf(worstNs, abs(flashTrueNs - masterFlashTrueNs))
            }
        }
        return worstNs
    }

    @Test
    fun `phones stay within 3 ms of the master on a fast Wi-Fi style link`() {
        val link = LinkModel(2 * MS, 2 * MS, stallChance = 0.05, maxStallNs = 150 * MS, Random(1))

        val worst = worstErrorNs(link, keepSyncing = true)

        println("wifi-style link, worst flash error ${worst / 1_000} us")
        assertTrue(worst < 3 * MS, "worst flash error $worst ns")
    }

    @Test
    fun `phones stay within 12 ms of the master on a slow Bluetooth style link`() {
        val link = LinkModel(15 * MS, 10 * MS, stallChance = 0.10, maxStallNs = 300 * MS, Random(2))

        val worst = worstErrorNs(link, keepSyncing = true)

        println("bluetooth-style link, worst flash error ${worst / 1_000} us")
        assertTrue(worst < 12 * MS, "worst flash error $worst ns")
    }

    @Test
    fun `the result holds across seeds`() {
        val worst = (1..50).maxOf { seed ->
            val link = LinkModel(2 * MS, 2 * MS, stallChance = 0.05, maxStallNs = 150 * MS, Random(seed))
            worstErrorNs(link, keepSyncing = true)
        }

        println("wifi-style link over 50 seeds, worst flash error ${worst / 1_000} us")
        assertTrue(worst < 4 * MS, "worst flash error $worst ns")
    }

    @Test
    fun `syncing only before the song lets the clocks drift apart`() {
        val link = LinkModel(2 * MS, 2 * MS, stallChance = 0.05, maxStallNs = 150 * MS, Random(1))

        val worst = worstErrorNs(link, keepSyncing = false)

        println("sync before the song only, worst flash error ${worst / 1_000} us")
        assertTrue(worst > 10 * MS, "expected 5 minutes of skew to show, got $worst ns")
    }
}
