@file:OptIn(ExperimentalCoroutinesApi::class)

package com.bandsync.core.metronome

import com.bandsync.core.protocol.SongChange
import com.bandsync.core.protocol.SongConfig
import com.bandsync.core.protocol.TimeSignature
import com.bandsync.core.time.MonotonicClock
import com.bandsync.core.time.SystemMonotonicClock
import com.bandsync.core.time.TimeBase
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

private const val MS = 1_000_000L

/** Virtual time that also creeps forward on every read, so the engine's spin loop can finish. */
private class SpinningClock(private val scheduler: TestCoroutineScheduler) : MonotonicClock {
    private var reads = 0L
    override fun nowNs(): Long = scheduler.currentTime * MS + reads++ * 10_000
}

class MetronomeEngineTest {
    private fun timeline(startMasterNs: Long, firstBeat: Long = 0) = Timeline(
        startMasterNs = startMasterNs,
        firstBeat = firstBeat,
        playFromBeat = firstBeat,
        map = SongMap.steady(bpm = 120, beatsPerBar = 4),
    )

    private fun TestScope.engineWithLog(clock: MonotonicClock): Pair<MetronomeEngine, MutableList<BeatTick>> {
        val engine = MetronomeEngine(clock, TimeBase.Master, backgroundScope)
        val log = mutableListOf<BeatTick>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            engine.ticks.filterNotNull().collect { log += it }
        }
        return engine to log
    }

    @Test
    fun `fires every beat exactly on schedule`() = runTest {
        val clock = SpinningClock(testScheduler)
        val (engine, log) = engineWithLog(clock)
        val line = timeline(startMasterNs = 1_000 * MS)

        engine.apply(TransportState.Running(line))
        advanceTimeBy(3_200)

        assertEquals(listOf(0L, 1, 2, 3, 4), log.map { it.beat })
        assertEquals(log.map { line.masterTimeOf(it.beat) }, log.map { it.localNs })
    }

    @Test
    fun `follows tempo and time signature changes`() = runTest {
        val clock = SpinningClock(testScheduler)
        val (engine, log) = engineWithLog(clock)
        val map = SongConfig(
            bpm = 120,
            timeSignature = TimeSignature(2, 4),
            changes = listOf(SongChange(2, 60, TimeSignature(3, 4))),
        ).toMap()

        engine.apply(TransportState.Running(Timeline(1_000 * MS, 0, 0, map)))
        advanceTimeBy(5_200)

        assertEquals(listOf(1_000L, 1_500, 2_000, 3_000, 4_000, 5_000).map { it * MS }, log.map { it.localNs })
        assertEquals(listOf(1, 2, 1, 2, 3, 1), log.map { it.beatInBar })
    }

    @Test
    fun `a late joiner starts on the current beat, not the first`() = runTest {
        val clock = SpinningClock(testScheduler)
        val (engine, log) = engineWithLog(clock)
        advanceTimeBy(10_250)

        engine.apply(TransportState.Running(timeline(startMasterNs = 0)))
        advanceTimeBy(1_000)

        assertEquals(listOf(21L, 22), log.map { it.beat })
    }

    @Test
    fun `stops at the pause time without firing past it`() = runTest {
        val clock = SpinningClock(testScheduler)
        val (engine, log) = engineWithLog(clock)
        val line = timeline(startMasterNs = 0)

        engine.apply(TransportState.Running(line))
        advanceTimeBy(1_100)
        engine.apply(TransportState.Paused(line, atMasterNs = 1_800 * MS))
        advanceTimeBy(5_000)

        assertEquals(3L, log.last().beat)
        assertEquals(1_500 * MS, log.last().localNs)
    }

    @Test
    fun `stop clears the last tick`() = runTest {
        val clock = SpinningClock(testScheduler)
        val (engine, _) = engineWithLog(clock)

        engine.apply(TransportState.Running(timeline(startMasterNs = 0)))
        advanceTimeBy(600)
        engine.apply(TransportState.Stopped)

        assertEquals(null, engine.ticks.value)
    }

    @Test
    fun `a clock correction moves the beat being waited for`() = runTest {
        var offsetNs = 0L
        val shifting = object : TimeBase {
            override fun toMaster(localNs: Long) = localNs + offsetNs
            override fun toLocal(masterNs: Long) = masterNs - offsetNs
        }
        val clock = SpinningClock(testScheduler)
        val engine = MetronomeEngine(clock, shifting, backgroundScope)

        engine.apply(TransportState.Running(timeline(startMasterNs = 1_000 * MS)))
        advanceTimeBy(100)
        offsetNs = 40 * MS
        advanceTimeBy(1_000)

        assertEquals(960 * MS, engine.ticks.value?.localNs)
    }

    /** Real thread and real clock. On Windows this also exercises the spin window widening for coarse timers. */
    @Test
    fun `real clock fires within a millisecond`() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(dispatcher)
        try {
            val clock = SystemMonotonicClock
            val engine = MetronomeEngine(clock, TimeBase.Master, scope)
            val lateNs = mutableListOf<Long>()
            val line = Timeline(clock.nowNs() + 50 * MS, 0, 0, SongMap.steady(bpm = 300, beatsPerBar = 4))

            engine.apply(TransportState.Running(line))
            withTimeout(10_000) {
                launch(Dispatchers.Unconfined) {
                    engine.ticks.filterNotNull().take(25).collect { lateNs += clock.nowNs() - it.localNs }
                }.join()
            }
            engine.apply(TransportState.Stopped)

            val median = lateNs.sorted()[lateNs.size / 2]
            println(
                "real clock lateness: median ${median / 1_000} us, first beat ${lateNs.first() / 1_000} us, " +
                    "worst after that ${lateNs.drop(1).max() / 1_000} us",
            )
            assertTrue(median < MS, "median lateness $median ns")
        } finally {
            scope.cancel()
            dispatcher.close()
        }
    }
}
