package com.bandsync.core.metronome

/** What the UI shows at a moment in time. Equal values mean no redraw, so fields are only as fine as they need to be. */
sealed interface Playhead {
    data object Stopped : Playhead
    data class Countdown(val tenthsLeft: Long) : Playhead
    data class CountIn(val count: Int, val beatsPerBar: Int, val bpm: Int, val beatsLeft: Long) : Playhead
    data class Playing(val bar: Long, val beat: Int, val beatsPerBar: Int, val bpm: Int, val seconds: Long) : Playhead
    data class Paused(val bar: Long, val beat: Int, val beatsPerBar: Int, val bpm: Int, val seconds: Long) : Playhead
}

fun playheadAt(transport: TransportState, masterNs: Long): Playhead = when (transport) {
    TransportState.Stopped -> Playhead.Stopped
    is TransportState.Running -> runningPlayhead(transport.timeline, masterNs)
    is TransportState.Paused -> {
        val timeline = transport.timeline
        if (masterNs < transport.atMasterNs) {
            runningPlayhead(timeline, masterNs)
        } else {
            Playhead.Paused(
                bar = timeline.barOf(transport.beat),
                beat = timeline.beatInBar(transport.beat),
                beatsPerBar = timeline.beatsPerBarAt(transport.beat),
                bpm = timeline.bpmAt(transport.beat),
                seconds = timeline.secondsAt(transport.beat),
            )
        }
    }
}

private fun runningPlayhead(timeline: Timeline, masterNs: Long): Playhead {
    if (masterNs < timeline.startMasterNs) {
        return Playhead.Countdown((timeline.startMasterNs - masterNs + 99_999_999) / 100_000_000)
    }
    val beat = timeline.beatAt(masterNs)
    return if (timeline.isCountIn(beat)) {
        Playhead.CountIn(
            count = timeline.beatInBar(beat),
            beatsPerBar = timeline.beatsPerBarAt(beat),
            bpm = timeline.bpmAt(beat),
            beatsLeft = timeline.playFromBeat - beat,
        )
    } else {
        Playhead.Playing(
            bar = timeline.barOf(beat),
            beat = timeline.beatInBar(beat),
            beatsPerBar = timeline.beatsPerBarAt(beat),
            bpm = timeline.bpmAt(beat),
            seconds = timeline.secondsAt(beat),
        )
    }
}
