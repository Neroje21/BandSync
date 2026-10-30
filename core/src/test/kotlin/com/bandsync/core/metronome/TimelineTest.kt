package com.bandsync.core.metronome

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val SECOND = 1_000_000_000L

class TimelineTest {
    private val timeline = Timeline(
        startMasterNs = 10 * SECOND,
        firstBeat = -4,
        playFromBeat = 0,
        map = SongMap.steady(bpm = 120, beatsPerBar = 4),
    )

    @Test
    fun `beats are evenly spaced from the start time`() {
        assertEquals(10 * SECOND, timeline.masterTimeOf(-4))
        assertEquals(12 * SECOND, timeline.masterTimeOf(0))
        assertEquals(12 * SECOND + SECOND / 2, timeline.masterTimeOf(1))
    }

    @Test
    fun `rounding never accumulates`() {
        // 60e9 / 97 is not a whole number of nanoseconds, so repeated addition would creep.
        val slow = Timeline(10 * SECOND, 0, 0, SongMap.steady(97, 4))
        val hours = 10L
        val beat = 97 * 60 * hours

        assertEquals(10 * SECOND + hours * 3600 * SECOND, slow.masterTimeOf(beat))
    }

    @Test
    fun `beatAt inverts masterTimeOf at every tempo`() {
        for (bpm in MIN_BPM..MAX_BPM) {
            val sample = timeline.copy(map = SongMap.steady(bpm, 4))
            for (beat in listOf(-4L, -1, 0, 1, 7, 1_000, 123_457)) {
                val time = sample.masterTimeOf(beat)
                assertEquals(beat, sample.beatAt(time), "bpm $bpm beat $beat")
                assertEquals(beat - 1, sample.beatAt(time - 1), "bpm $bpm just before beat $beat")
            }
        }
    }

    @Test
    fun `nextBeatFrom lands on or after the given time`() {
        assertEquals(0L, timeline.nextBeatFrom(timeline.masterTimeOf(0)))
        assertEquals(1L, timeline.nextBeatFrom(timeline.masterTimeOf(0) + 1))
    }

    @Test
    fun `the grid extends before the start, leaving the engine to begin at firstBeat`() {
        assertEquals(-24L, timeline.nextBeatFrom(0))
    }

    @Test
    fun `bars count through the count-in`() {
        assertEquals(0L, timeline.barOf(-4))
        assertEquals(4, timeline.beatInBar(-1))
        assertEquals(1L, timeline.barOf(0))
        assertEquals(1, timeline.beatInBar(0))
        assertEquals(2L, timeline.barOf(4))
        assertEquals(3, timeline.beatInBar(6))
    }

    @Test
    fun `pausing during the count-in stays at the play position`() {
        val paused = TransportState.Paused(timeline, atMasterNs = 11 * SECOND)
        assertEquals(0L, paused.beat)
    }

    @Test
    fun `playhead walks through countdown, count-in, playing and paused`() {
        val running = TransportState.Running(timeline)

        assertEquals(Playhead.Countdown(15), playheadAt(running, 8 * SECOND + SECOND / 2))
        assertEquals(
            Playhead.CountIn(count = 1, beatsPerBar = 4, bpm = 120, beatsLeft = 4),
            playheadAt(running, 10 * SECOND),
        )
        assertEquals(
            Playhead.CountIn(count = 4, beatsPerBar = 4, bpm = 120, beatsLeft = 1),
            playheadAt(running, 11 * SECOND + SECOND / 2),
        )
        assertEquals(
            Playhead.Playing(bar = 1, beat = 1, beatsPerBar = 4, bpm = 120, seconds = 0),
            playheadAt(running, 12 * SECOND),
        )
        assertEquals(
            Playhead.Playing(bar = 2, beat = 2, beatsPerBar = 4, bpm = 120, seconds = 2),
            playheadAt(running, 14 * SECOND + SECOND / 2),
        )

        val paused = TransportState.Paused(timeline, atMasterNs = 15 * SECOND)
        val expected = Playhead.Paused(bar = 2, beat = 3, beatsPerBar = 4, bpm = 120, seconds = 3)
        assertIs<Playhead.Playing>(playheadAt(paused, 15 * SECOND - 1))
        assertEquals(expected, playheadAt(paused, 15 * SECOND))
        assertEquals(expected, playheadAt(paused, 99 * SECOND))

        assertEquals(Playhead.Stopped, playheadAt(TransportState.Stopped, 12 * SECOND))
    }

    @Test
    fun `playhead shows the section's tempo and meter`() {
        val running = TransportState.Running(Timeline(0, 0, 0, ironMan))

        val shortBar = assertIs<Playhead.Playing>(playheadAt(running, ironMan.timeOf(29)))
        assertEquals(Playhead.Playing(bar = 8, beat = 2, beatsPerBar = 2, bpm = 65, seconds = 26), shortBar)

        val fast = assertIs<Playhead.Playing>(playheadAt(running, ironMan.timeOf(238)))
        assertEquals(61L, fast.bar)
        assertEquals(168, fast.bpm)
    }

    @Test
    fun `the song clock runs in real time across tempo changes`() {
        val running = TransportState.Running(Timeline(0, 0, 0, ironMan))
        val verse = assertIs<Playhead.Playing>(playheadAt(running, ironMan.timeOf(238)))

        assertEquals(ironMan.timeOf(238) / SECOND, verse.seconds)
    }

    @Test
    fun `count-in is only the beats before the play position`() {
        assertTrue(timeline.isCountIn(-1))
        assertFalse(timeline.isCountIn(0))
    }
}
