package com.bandsync.core.metronome

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

const val MIN_BPM = 20
const val MAX_BPM = 300

/**
 * The band's shared timeline: a pure function from master time to beat, so every phone can work out any
 * beat by itself and no per-beat message ever crosses the network, tempo changes included.
 *
 * [firstBeat] sounds at [startMasterNs]. Beats before [playFromBeat] are the count-in, which runs at the tempo
 * and meter of the place the song is about to play from, so resuming into a fast section counts in fast.
 * From [playFromBeat] on, [map] decides everything.
 */
@Serializable
data class Timeline(
    val startMasterNs: Long,
    val firstBeat: Long,
    val playFromBeat: Long,
    val map: SongMap,
) {
    @Transient
    private val playSection: SongSection = map.sectionAt(playFromBeat)

    @Transient
    private val playMasterNs: Long = startMasterNs + durationNs(playFromBeat - firstBeat, playSection.bpm)

    /** Computed from the beat number every time, never from the previous beat, so rounding can't accumulate. */
    fun masterTimeOf(beat: Long): Long = if (isCountIn(beat)) {
        startMasterNs + durationNs(beat - firstBeat, playSection.bpm)
    } else {
        playMasterNs + map.timeOf(beat) - map.timeOf(playFromBeat)
    }

    /** The latest beat at or before [masterNs]. Below [firstBeat] until the metronome has started. */
    fun beatAt(masterNs: Long): Long = if (masterNs < playMasterNs) {
        firstBeat + Math.floorDiv((masterNs - startMasterNs) * playSection.bpm, NS_PER_MINUTE)
    } else {
        map.beatAt(masterNs - playMasterNs + map.timeOf(playFromBeat))
    }

    /** The earliest beat at or after [masterNs]. */
    fun nextBeatFrom(masterNs: Long): Long {
        val beat = beatAt(masterNs)
        return if (masterTimeOf(beat) == masterNs) beat else beat + 1
    }

    /** Where the sheet arrow belongs, between beats too. It waits at the first note until the count-in ends. */
    fun cursorBeatAt(masterNs: Long): Double {
        val beat = beatAt(masterNs)
        if (isCountIn(beat)) return playFromBeat.toDouble()
        val beatStartNs = masterTimeOf(beat)
        return beat + (masterNs - beatStartNs).toDouble() / (masterTimeOf(beat + 1) - beatStartNs)
    }

    fun isCountIn(beat: Long): Boolean = beat < playFromBeat
    fun barOf(beat: Long): Long = map.barOf(beat)
    fun secondsAt(beat: Long): Long = map.secondsAt(beat)
    fun bpmAt(beat: Long): Int = sectionFor(beat).bpm
    fun beatsPerBarAt(beat: Long): Int = sectionFor(beat).beatsPerBar

    /** The count-in ends on a bar line, so its bars are counted back from there. */
    fun beatInBar(beat: Long): Int = if (isCountIn(beat)) {
        Math.floorMod(beat - playFromBeat, playSection.beatsPerBar.toLong()).toInt() + 1
    } else {
        map.beatInBar(beat)
    }

    private fun sectionFor(beat: Long): SongSection = if (isCountIn(beat)) playSection else map.sectionAt(beat)
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

        fun cursorBeatAt(masterNs: Long): Double = timeline.cursorBeatAt(minOf(masterNs, atMasterNs))
    }
}
