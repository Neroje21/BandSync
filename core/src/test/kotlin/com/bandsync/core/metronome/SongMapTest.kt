package com.bandsync.core.metronome

import com.bandsync.core.protocol.SongChange
import com.bandsync.core.protocol.SongConfig
import com.bandsync.core.protocol.TimeSignature
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val SECOND = 1_000_000_000L
private val FOUR_FOUR = TimeSignature(4, 4)

/** The map printed on the Iron Man test tabs, which is a useful real-world case: eight tempos and a 2/4 bar. */
internal val ironMan = SongConfig(
    bpm = 65,
    timeSignature = FOUR_FOUR,
    changes = listOf(
        SongChange(8, 65, TimeSignature(2, 4)),
        SongChange(9, 70, FOUR_FOUR),
        SongChange(11, 72, FOUR_FOUR),
        SongChange(13, 76, FOUR_FOUR),
        SongChange(61, 168, FOUR_FOUR),
        SongChange(85, 78, FOUR_FOUR),
        SongChange(97, 79, FOUR_FOUR),
        SongChange(103, 158, FOUR_FOUR),
    ),
).toMap()

class SongMapTest {
    @Test
    fun `sections follow on from each other`() {
        assertEquals(
            listOf(
                SongSection(1, 0, 65, 4),
                SongSection(8, 28, 65, 2),
                SongSection(9, 30, 70, 4),
                SongSection(11, 38, 72, 4),
                SongSection(13, 46, 76, 4),
                SongSection(61, 238, 168, 4),
                SongSection(85, 334, 78, 4),
                SongSection(97, 382, 79, 4),
                SongSection(103, 406, 158, 4),
            ),
            ironMan.sections,
        )
    }

    @Test
    fun `a short bar moves every later bar line`() {
        assertEquals(8L, ironMan.barOf(29))
        assertEquals(2, ironMan.beatInBar(29))
        assertEquals(9L, ironMan.barOf(30))
        assertEquals(1, ironMan.beatInBar(30))
        assertEquals(30L, ironMan.firstBeatOfBar(9))
        assertEquals(46L, ironMan.firstBeatOfBar(13))
    }

    @Test
    fun `bars and beats agree everywhere`() {
        for (bar in 1L..149) {
            val first = ironMan.firstBeatOfBar(bar)
            assertEquals(bar, ironMan.barOf(first), "bar $bar")
            assertEquals(1, ironMan.beatInBar(first), "bar $bar")
        }
    }

    @Test
    fun `each section runs at its own tempo`() {
        val map = SongConfig(
            bpm = 60,
            timeSignature = FOUR_FOUR,
            changes = listOf(SongChange(2, 120, FOUR_FOUR)),
        ).toMap()

        assertEquals(3 * SECOND, map.timeOf(3))
        assertEquals(4 * SECOND, map.timeOf(4))
        assertEquals(4 * SECOND + SECOND / 2, map.timeOf(5))
        assertEquals(6 * SECOND, map.timeOf(8))
    }

    @Test
    fun `beat times and beats invert exactly across every section`() {
        for (beat in 0L..600) {
            val time = ironMan.timeOf(beat)
            assertEquals(beat, ironMan.beatAt(time), "beat $beat")
            if (beat > 0) assertEquals(beat - 1, ironMan.beatAt(time - 1), "just before beat $beat")
        }
    }

    @Test
    fun `beats only ever move forward`() {
        val gaps = (0L..600).map { ironMan.timeOf(it + 1) - ironMan.timeOf(it) }
        assertTrue(gaps.all { it > 0 })
    }

    @Test
    fun `the map adds up to the length of the song`() {
        val seconds = ironMan.timeOf(ironMan.firstBeatOfBar(150)) / SECOND.toDouble()
        assertEquals(353.6, seconds, 0.1)
    }

    @Test
    fun `changes can be entered in any order`() {
        val shuffled = SongConfig(
            bpm = 100,
            changes = listOf(SongChange(9, 140, FOUR_FOUR), SongChange(5, 120, FOUR_FOUR)),
        ).toMap()

        assertEquals(listOf(1L, 5, 9), shuffled.sections.map { it.fromBar })
    }

    @Test
    fun `the later of two changes on one bar wins`() {
        val doubled = SongConfig(
            bpm = 100,
            changes = listOf(SongChange(5, 120, FOUR_FOUR), SongChange(5, 150, FOUR_FOUR)),
        ).toMap()

        assertEquals(listOf(100, 150), doubled.sections.map { it.bpm })
    }

    @Test
    fun `the opening tempo owns bar one`() {
        val map = SongConfig(bpm = 100, changes = listOf(SongChange(1, 180, FOUR_FOUR))).toMap()
        assertEquals(listOf(SongSection(1, 0, 100, 4)), map.sections)
    }

    @Test
    fun `a song with no changes behaves like a steady one`() {
        assertEquals(SongMap.steady(120, 4), SongConfig(bpm = 120).toMap())
    }

    @Test
    fun `timeline starts in the middle of the map at the right tempo`() {
        val bar61 = ironMan.firstBeatOfBar(61)
        val timeline = Timeline(startMasterNs = 0, firstBeat = bar61 - 4, playFromBeat = bar61, map = ironMan)

        assertEquals(168, timeline.bpmAt(bar61 - 2), "the count-in takes the tempo it leads into")
        assertEquals(3, timeline.beatInBar(bar61 - 2))
        assertEquals(1_428_571_429, timeline.masterTimeOf(bar61), "four beats at 168, rounded up")
        assertEquals(1, timeline.beatInBar(bar61))
    }

    @Test
    fun `timeline crosses a section boundary without a gap`() {
        val timeline = Timeline(startMasterNs = 0, firstBeat = 0, playFromBeat = 0, map = ironMan)

        assertEquals(ironMan.timeOf(238), timeline.masterTimeOf(238))
        assertEquals(76, timeline.bpmAt(237))
        assertEquals(168, timeline.bpmAt(238))
    }

    @Test
    fun `timeline inverts across the count-in and every section`() {
        val from = ironMan.firstBeatOfBar(11)
        val timeline = Timeline(startMasterNs = 5 * SECOND, firstBeat = from - 8, playFromBeat = from, map = ironMan)

        for (beat in (from - 8)..600) {
            val time = timeline.masterTimeOf(beat)
            assertEquals(beat, timeline.beatAt(time), "beat $beat")
            assertEquals(beat - 1, timeline.beatAt(time - 1), "just before beat $beat")
        }
    }
}
