package com.bandsync.core.protocol

import com.bandsync.core.sheets.BarLayout
import com.bandsync.core.sheets.Role

/**
 * Presets every instrument from all of its sheets. Each part gets its own copy of the tempo marks and time
 * signatures printed on them, even where the tabs agree, so one part can be changed without touching the others.
 * That is how a part that speeds up while others hold their tempo comes out right with nothing entered by hand.
 *
 * A part with no marks has nothing of its own to follow, so it gets the changes that every marked part agrees on.
 *
 * Replaces any changes already there, and leaves the song alone if no sheet has marks.
 */
fun SongConfig.withPrintedTempos(sheets: Map<Role, List<BarLayout>>): SongConfig {
    val printed = sheets.mapValues { (_, tabs) -> printedChanges(tabs) }.filterValues { it.isNotEmpty() }
    if (printed.isEmpty()) return this

    val agreed = printed.values.map { it.toSet() }.reduce { shared, tab -> shared intersect tab }
    val opening = agreed.firstOrNull { it.bar == 1 }
    val eachPart = printed.flatMap { (part, changes) -> changes.map { it.copy(parts = setOf(part)) } }
    val withoutMarks = Role.entries.toSet() - printed.keys
    val theRest = if (withoutMarks.isEmpty()) {
        emptyList()
    } else {
        agreed.filter { it.bar > 1 }.sortedBy { it.bar }.map { it.copy(parts = withoutMarks) }
    }
    return copy(
        bpm = opening?.bpm ?: bpm,
        timeSignature = opening?.timeSignature ?: timeSignature,
        changes = eachPart + theRest,
    )
}

/**
 * Tempo and time signature marks can fall on different bars, so each change states both from that bar on.
 * Where a part's sheets print different marks for the same bar, the first sheet in its order wins.
 */
private fun SongConfig.printedChanges(tabs: List<BarLayout>): List<SongChange> {
    val tempos = tabs.flatMap { it.tempos }.distinctBy { it.bar }.sortedBy { it.bar }
    val meters = tabs.flatMap { it.meters }.distinctBy { it.bar }.sortedBy { it.bar }
    return (tempos.map { it.bar } + meters.map { it.bar }).distinct().sorted().map { bar ->
        val meter = meters.lastOrNull { it.bar <= bar }
        SongChange(
            bar = bar,
            bpm = tempos.lastOrNull { it.bar <= bar }?.bpm ?: bpm,
            timeSignature = meter?.let { TimeSignature(it.beatsPerBar, it.beatUnit) } ?: timeSignature,
        )
    }
}
