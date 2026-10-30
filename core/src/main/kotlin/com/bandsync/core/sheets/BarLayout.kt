package com.bandsync.core.sheets

import com.bandsync.core.metronome.SongMap
import com.bandsync.core.metronome.TransportState
import kotlin.math.floor
import kotlin.math.min

/**
 * Printed tabs leave about 20pt of an A4 page between a bar line and the bar's first note, and about the same
 * after the last one. As a fraction of the page width, so it holds at any paper size.
 */
private const val NOTE_INSET = 0.034f

/** One drawn bar, which can stand for several when the tab prints a long rest as one bar. */
data class BarCell(
    val bar: Int,
    val pageIndex: Int,
    val startX: Float,
    val endX: Float,
    val top: Float,
    val bottom: Float,
) {
    private val inset: Float get() = min(NOTE_INSET, (endX - startX) / 4)
    val notesStartX: Float get() = startX + inset
    val notesEndX: Float get() = endX - inset

    fun isOnSameLineAs(other: BarCell): Boolean = pageIndex == other.pageIndex && top == other.top
}

/** A place on a page. All coordinates are fractions of the page, measured from its top left. */
data class TabPosition(val pageIndex: Int, val x: Float, val top: Float, val bottom: Float)

data class TempoMark(val bar: Int, val bpm: Int)

data class MeterMark(val bar: Int, val beatsPerBar: Int, val beatUnit: Int)

/** Where each bar of a tab is printed, in bar order, and the tempo and time signature marks printed with them. */
data class BarLayout(
    val cells: List<BarCell>,
    val tempos: List<TempoMark> = emptyList(),
    val meters: List<MeterMark> = emptyList(),
) {
    /**
     * Beats are spaced evenly from a bar's first note, which is how tabs print them when the rhythm is even.
     * The last beat then covers whatever is left up to the next bar's first note, so the arrow never jumps
     * within a line and is exactly on the first note of every bar.
     */
    fun locate(beat: Double, map: SongMap): TabPosition? {
        val bar = map.barOf(floor(beat).toLong())
        val index = cells.indexOfLast { it.bar <= bar }
        if (index < 0) return null
        val cell = cells[index]
        val next = cells.getOrNull(index + 1)

        val firstBeat = map.firstBeatOfBar(cell.bar.toLong())
        val endBeat = map.firstBeatOfBar((next?.bar ?: (cell.bar + 1)).toLong())
        val beats = (endBeat - firstBeat).toDouble()
        val fraction = (beat - firstBeat) / beats
        if (fraction >= 1) return null // Past the last printed bar.

        val lastBeatStarts = (beats - 1) / beats
        val notesWidth = cell.notesEndX - cell.notesStartX
        val x = if (fraction < lastBeatStarts) {
            cell.notesStartX + fraction * notesWidth
        } else {
            val from = cell.notesStartX + lastBeatStarts * notesWidth
            val to = next?.takeIf(cell::isOnSameLineAs)?.notesStartX ?: cell.endX
            from + (fraction - lastBeatStarts) / (1 - lastBeatStarts) * (to - from)
        }
        return TabPosition(cell.pageIndex, x.toFloat(), cell.top, cell.bottom)
    }
}

/** Where the arrow goes, as a position in the list of pages the viewer is showing. */
data class SheetCursor(val listIndex: Int, val x: Float, val top: Float, val bottom: Float)

/** Finds the current place among one part's pages, which can come from several PDFs. */
class SheetTracker(
    private val pages: List<SheetPage>,
    private val layouts: Map<FileId, BarLayout>,
) {
    private val files = pages.map { it.fileId }.distinct()

    fun locate(transport: TransportState, masterNs: Long): SheetCursor? {
        val (beat, map) = when (transport) {
            TransportState.Stopped -> return null
            is TransportState.Running -> transport.timeline.cursorBeatAt(masterNs) to transport.timeline.map
            is TransportState.Paused -> transport.cursorBeatAt(masterNs) to transport.timeline.map
        }
        for (file in files) {
            val position = layouts[file]?.locate(beat, map) ?: continue
            val listIndex = pages.indexOf(SheetPage(file, position.pageIndex))
            if (listIndex >= 0) return SheetCursor(listIndex, position.x, position.top, position.bottom)
        }
        return null
    }
}
