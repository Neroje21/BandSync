package com.bandsync.core.metronome

import com.bandsync.core.time.MonotonicClock
import com.bandsync.core.time.TimeBase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/** delay() can overshoot, so it only gets within this distance of a beat and the rest is spent spinning. */
private const val MIN_SPIN_WINDOW_NS = 3_000_000L

/** Enough for a 15.6 ms timer tick, the coarsest in common use. Spinning any longer just burns battery. */
private const val MAX_SPIN_WINDOW_NS = 25_000_000L
private const val NS_PER_MS = 1_000_000L

/** [localNs] is when the beat was due on this device, which is what a renderer should measure from. */
data class BeatTick(val beat: Long, val beatInBar: Int, val localNs: Long)

/**
 * The drift-free ticker. Run [scope] on a dedicated high-priority thread: spinning shares nothing with
 * other work there, and on a busy dispatcher `yield()` could hand the thread away at the wrong moment.
 */
class MetronomeEngine(
    private val clock: MonotonicClock,
    private val timeBase: TimeBase,
    private val scope: CoroutineScope,
) {
    private val _ticks = MutableStateFlow<BeatTick?>(null)
    val ticks: StateFlow<BeatTick?> = _ticks.asStateFlow()

    private var job: Job? = null
    private var spinWindowNs = MIN_SPIN_WINDOW_NS

    fun apply(transport: TransportState) {
        job?.cancel()
        job = null
        when (transport) {
            TransportState.Stopped -> _ticks.value = null
            is TransportState.Running -> job = scope.launch { run(transport.timeline, Long.MAX_VALUE) }
            is TransportState.Paused -> job = scope.launch { run(transport.timeline, transport.atMasterNs) }
        }
    }

    private suspend fun run(timeline: Timeline, stopAtMasterNs: Long) {
        var beat = timeline.firstBeat
        while (true) {
            // Re-derived from the clock rather than just incremented, so a phone that stalled or joined
            // late lands on the correct beat instead of replaying the ones it missed.
            beat = maxOf(beat, timeline.nextBeatFrom(timeBase.toMaster(clock.nowNs())))
            val masterNs = timeline.masterTimeOf(beat)
            if (masterNs >= stopAtMasterNs) return
            awaitBeat(masterNs)
            _ticks.value = BeatTick(beat, timeline.beatInBar(beat), timeBase.toLocal(masterNs))
            beat++
        }
    }

    /** Re-reads the conversion every pass so a clock correction takes effect on the beat being waited for. */
    private suspend fun awaitBeat(masterNs: Long) {
        while (true) {
            val remainingNs = timeBase.toLocal(masterNs) - clock.nowNs()
            when {
                remainingNs <= 0 -> return
                remainingNs > spinWindowNs -> sleep(remainingNs - spinWindowNs)
                else -> yield()
            }
        }
    }

    /** Watches how far each sleep overshoots, so a device with coarse timers still lands on the beat. */
    private suspend fun sleep(ns: Long) {
        val wakeAtNs = clock.nowNs() + ns
        delay(ns / NS_PER_MS)
        val overshootNs = clock.nowNs() - wakeAtNs
        spinWindowNs = maxOf(spinWindowNs * 15 / 16, overshootNs * 2)
            .coerceIn(MIN_SPIN_WINDOW_NS, MAX_SPIN_WINDOW_NS)
    }
}
