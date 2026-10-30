package com.bandsync.core.metronome

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val NS_PER_MINUTE = 60_000_000_000L

const val MIN_BPM = 20
const val MAX_BPM = 300

/**
 * The band's shared timeline: a pure function from master time to beat, so every phone can work out any
 * beat by itself and no per-beat message ever crosses the network.
 *
 * Beat 0 is bar 1, beat 1. [firstBeat] sounds at [startMasterNs]; beats before [playFromBeat] are the
 * count-in. Both are always on a bar line, so bar arithmetic holds for negative beats too.
 */
@Serializable
data class Timeline(
    val startMasterNs: Long,
    val firstBeat: Long,
    val playFromBeat: Long,
    val bpm: Int,
    val beatsPerBar: Int,
) {
    init {
        require(bpm > 0) { "bpm must be positive" }
        require(beatsPerBar > 0) { "beatsPerBar must be positive" }
    }

    /**
     * Computed from the beat number every time, never by adding a period to the previous beat, so rounding
     * can't accumulate. Rounds up so that `beatAt(masterTimeOf(n)) == n` holds exactly.
     */
    fun masterTimeOf(beat: Long): Long =
        startMasterNs - Math.floorDiv(-(beat - firstBeat) * NS_PER_MINUTE, bpm.toLong())

    /** The latest beat at or before [masterNs]. Below [firstBeat] until the metronome has started. */
    fun beatAt(masterNs: Long): Long =
        firstBeat + Math.floorDiv((masterNs - startMasterNs) * bpm, NS_PER_MINUTE)

    /** The earliest beat at or after [masterNs]. */
    fun nextBeatFrom(masterNs: Long): Long {
        val beat = beatAt(masterNs)
        return if (masterTimeOf(beat) == masterNs) beat else beat + 1
    }

    fun barOf(beat: Long): Long = Math.floorDiv(beat, beatsPerBar.toLong()) + 1
    fun beatInBar(beat: Long): Int = Math.floorMod(beat, beatsPerBar.toLong()).toInt() + 1
    fun isCountIn(beat: Long): Boolean = beat < playFromBeat
    fun secondsAt(beat: Long): Long = beat * 60 / bpm
}

@Serializable
sealed interface TransportState {
    @Serializable
    @SerialName("stopped")
    data object Stopped : TransportState

    @Serializable
    @SerialName("running")
    data class Running(val timeline: Timeline) : TransportState

    /** Keeps running until [atMasterNs], so every phone freezes on the same instant. */
    @Serializable
    @SerialName("paused")
    data class Paused(val timeline: Timeline, val atMasterNs: Long) : TransportState {
        /** A pause during the countdown or count-in stays at the place the song was about to play from. */
        val beat: Long get() = maxOf(timeline.beatAt(atMasterNs), timeline.playFromBeat)
    }
}
