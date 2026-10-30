package com.bandsync.core.protocol

import com.bandsync.core.sheets.BarLayout
import com.bandsync.core.sheets.Role

/**
 * Sets the song up from the tempo marks and time signatures printed on each part's tab. A change printed on every
 * tab is for the whole band, and one printed on only some tabs is for those parts alone. That is how a part that
 * speeds up while others hold their tempo comes out right without entering anything by hand.
 *
 * Replaces any changes already there, and leaves the song alone if no tab has marks.
 */
fun SongConfig.withPrintedTempos(tabs: Map<Role, BarLayout>): SongConfig {
    val printed = tabs.mapValues { (_, tab) -> tab.printedChanges(this) }.filterValues { it.isNotEmpty() }
    if (printed.isEmpty()) return this

    val everyone = printed.values.map { it.toSet() }.reduce { shared, tab -> shared intersect tab }
    val changes = printed.values.flatten().distinct().sortedBy { it.bar }.map { change ->
        if (change in everyone) change else change.copy(parts = printed.filterValues { change in it }.keys)
    }
    val opening = everyone.firstOrNull { it.bar == 1 }
    return copy(
        bpm = opening?.bpm ?: bpm,
        timeSignature = opening?.timeSignature ?: timeSignature,
        changes = changes.filterNot { it == opening },
    )
}

/** Tempo and time signature marks can fall on different bars, so each change states both from that bar on. */
private fun BarLayout.printedChanges(song: SongConfig): List<SongChange> =
    (tempos.map { it.bar } + meters.map { it.bar }).distinct().sorted().map { bar ->
        val meter = meters.lastOrNull { it.bar <= bar }
        SongChange(
            bar = bar,
            bpm = tempos.lastOrNull { it.bar <= bar }?.bpm ?: song.bpm,
            timeSignature = meter?.let { TimeSignature(it.beatsPerBar, it.beatUnit) } ?: song.timeSignature,
        )
    }
