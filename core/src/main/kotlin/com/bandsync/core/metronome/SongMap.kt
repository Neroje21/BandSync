package com.bandsync.core.metronome

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

internal const val NS_PER_MINUTE = 60_000_000_000L
private const val NS_PER_SECOND = 1_000_000_000L

/** Rounds up so that a beat's time, and the beat found at that time, always agree exactly. */
internal fun durationNs(beats: Long, bpm: Int): Long =
    -Math.floorDiv(-beats * NS_PER_MINUTE, bpm.toLong())

/** From bar [fromBar], which begins on beat [fromBeat], the song has [bpm] and [beatsPerBar] until the next section. */
@Serializable
data class SongSection(val fromBar: Long, val fromBeat: Long, val bpm: Int, val beatsPerBar: Int) {
    init {
        require(bpm > 0) { "bpm must be positive" }
        require(beatsPerBar > 0) { "beatsPerBar must be positive" }
    }
}

/**
 * Where every bar and beat of a song falls, given that tempo and time signature can change. Beat 0 is bar 1,
 * beat 1. Times are nanoseconds from beat 0 and are integers, so every phone gets identical answers.
 *
 * A time is computed from its section's start, never from the beat before, so rounding can add at most one
 * nanosecond per section however long the song runs.
 */
@Serializable
data class SongMap(val sections: List<SongSection>) {
    init {
        require(sections.isNotEmpty()) { "a song needs at least one section" }
    }

    @Transient
    private val startsNs: List<Long> = sections
        .zipWithNext { section, next -> durationNs(next.fromBeat - section.fromBeat, section.bpm) }
        .runningFold(0L, Long::plus)

    /** Beats before the first section belong to it, which keeps bar arithmetic working for negative beats. */
    fun sectionAt(beat: Long): SongSection = sections[sectionIndexAt(beat)]

    fun barOf(beat: Long): Long {
        val section = sectionAt(beat)
        return section.fromBar + Math.floorDiv(beat - section.fromBeat, section.beatsPerBar.toLong())
    }

    fun beatInBar(beat: Long): Int {
        val section = sectionAt(beat)
        return Math.floorMod(beat - section.fromBeat, section.beatsPerBar.toLong()).toInt() + 1
    }

    fun firstBeatOfBar(bar: Long): Long {
        val section = sections.lastOrNull { it.fromBar <= bar } ?: sections.first()
        return section.fromBeat + (bar - section.fromBar) * section.beatsPerBar
    }

    fun timeOf(beat: Long): Long {
        val index = sectionIndexAt(beat)
        val section = sections[index]
        return startsNs[index] + durationNs(beat - section.fromBeat, section.bpm)
    }

    /** The latest beat at or before [ns]. */
    fun beatAt(ns: Long): Long {
        val index = startsNs.indexOfLast { it <= ns }.coerceAtLeast(0)
        val section = sections[index]
        return section.fromBeat + Math.floorDiv((ns - startsNs[index]) * section.bpm, NS_PER_MINUTE)
    }

    /** The earliest beat at or after [ns]. */
    fun nextBeatFrom(ns: Long): Long {
        val beat = beatAt(ns)
        return if (timeOf(beat) == ns) beat else beat + 1
    }

    fun secondsAt(beat: Long): Long = timeOf(beat) / NS_PER_SECOND

    private fun sectionIndexAt(beat: Long): Int = sections.indexOfLast { it.fromBeat <= beat }.coerceAtLeast(0)

    companion object {
        fun steady(bpm: Int, beatsPerBar: Int): SongMap = SongMap(listOf(SongSection(1, 0, bpm, beatsPerBar)))
    }
}
