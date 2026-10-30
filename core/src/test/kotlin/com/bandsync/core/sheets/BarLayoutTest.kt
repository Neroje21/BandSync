package com.bandsync.core.sheets

import com.bandsync.core.metronome.SongMap
import com.bandsync.core.metronome.Timeline
import com.bandsync.core.metronome.TransportState
import com.bandsync.core.metronome.ironMan
import com.bandsync.core.protocol.SongChange
import com.bandsync.core.protocol.SongConfig
import com.bandsync.core.protocol.TimeSignature
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Deflater
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val FUZZ = 0.0005f
private val STEADY = SongMap.steady(bpm = 120, beatsPerBar = 4)

class BarLayoutTest {
    private val first = BarCell(1, 0, startX = 0.1f, endX = 0.5f, top = 0.1f, bottom = 0.2f)
    private val second = BarCell(2, 0, startX = 0.5f, endX = 0.9f, top = 0.1f, bottom = 0.2f)
    private val nextLine = BarCell(3, 0, startX = 0.1f, endX = 0.9f, top = 0.3f, bottom = 0.4f)
    private val layout = BarLayout(listOf(first, second, nextLine))

    private fun x(beat: Double, map: SongMap = STEADY) = assertNotNull(layout.locate(beat, map)).x

    @Test
    fun `a bar begins on its first note, not its bar line`() {
        assertEquals(first.notesStartX, x(0.0), FUZZ)
        assertEquals(second.notesStartX, x(4.0), FUZZ)
    }

    @Test
    fun `beats are evenly spaced across the notes`() {
        val spacing = (first.notesEndX - first.notesStartX) / 4
        assertEquals(first.notesStartX + spacing, x(1.0), FUZZ)
        assertEquals(first.notesStartX + 2.5f * spacing, x(2.5), FUZZ)
    }

    @Test
    fun `the last beat glides into the next bar without a jump`() {
        val justBefore = x(3.9999)
        assertEquals(second.notesStartX, justBefore, FUZZ)
    }

    @Test
    fun `the arrow only moves forward along a line`() {
        val path = (0..799).map { x(it / 100.0) }
        assertTrue(path.zipWithNext().all { (a, b) -> b >= a }, "arrow went backwards")
    }

    @Test
    fun `the end of a line runs to the edge of the staff`() {
        assertEquals(second.endX, x(7.9999), FUZZ)
    }

    @Test
    fun `the arrow moves to the next line`() {
        val position = assertNotNull(layout.locate(8.0, STEADY))
        assertEquals(nextLine.top, position.top)
        assertEquals(nextLine.notesStartX, position.x, FUZZ)
    }

    @Test
    fun `a long rest printed as one bar is crossed at the right speed`() {
        val rest = BarCell(1, 0, startX = 0.1f, endX = 0.9f, top = 0.1f, bottom = 0.2f) // Bars 1 to 3.
        val after = BarCell(4, 0, startX = 0.1f, endX = 0.9f, top = 0.3f, bottom = 0.4f)
        val compressed = BarLayout(listOf(rest, after))

        val halfway = assertNotNull(compressed.locate(6.0, STEADY))

        assertEquals(rest.notesStartX + (rest.notesEndX - rest.notesStartX) / 2, halfway.x, FUZZ)
    }

    @Test
    fun `tempo and meter changes decide where bars begin`() {
        val map = SongConfig(
            bpm = 120,
            timeSignature = TimeSignature(4, 4),
            changes = listOf(SongChange(2, 60, TimeSignature(2, 4))),
        ).toMap()

        assertEquals(nextLine.notesStartX, x(6.0, map), FUZZ) // Bar 2 of 4/4 and 2/4 ends at beat 6.
    }

    @Test
    fun `nothing is shown past the last printed bar`() {
        assertNull(layout.locate(12.0, STEADY))
    }

    @Test
    fun `a tracker follows the shared timeline`() {
        val pages = listOf(SheetPage("a", 0))
        val tracker = SheetTracker(pages, mapOf("a" to layout))
        val timeline = Timeline(startMasterNs = 0, firstBeat = -4, playFromBeat = 0, map = STEADY)

        val countIn = assertNotNull(tracker.locate(TransportState.Running(timeline), 500_000_000))
        assertEquals(first.notesStartX, countIn.x, FUZZ)

        val playing = assertNotNull(tracker.locate(TransportState.Running(timeline), 2_500_000_000))
        assertEquals(first.notesStartX + (first.notesEndX - first.notesStartX) / 4, playing.x, FUZZ)

        assertNull(tracker.locate(TransportState.Stopped, 0))
    }

    @Test
    fun `a paused arrow stays where the song paused`() {
        val tracker = SheetTracker(listOf(SheetPage("a", 0)), mapOf("a" to layout))
        val timeline = Timeline(0, 0, 0, STEADY)
        val paused = TransportState.Paused(timeline, atMasterNs = 500_000_000)

        assertEquals(tracker.locate(paused, 500_000_000), tracker.locate(paused, 9_000_000_000))
    }

    @Test
    fun `the arrow lands on the right place in the viewer's page list`() {
        val onTwo = BarLayout(listOf(first.copy(pageIndex = 2)))
        val tracker = SheetTracker(listOf(SheetPage("a", 2), SheetPage("a", 5)), mapOf("a" to onTwo))

        assertEquals(0, tracker.locate(TransportState.Running(Timeline(0, 0, 0, STEADY)), 0)?.listIndex)
    }

    @Test
    fun `no arrow on a page the part isn't shown`() {
        val onTwo = BarLayout(listOf(first.copy(pageIndex = 2)))
        val tracker = SheetTracker(listOf(SheetPage("a", 0)), mapOf("a" to onTwo))

        assertNull(tracker.locate(TransportState.Running(Timeline(0, 0, 0, STEADY)), 0))
    }

    @Test
    fun `a sheet with no layout simply has no arrow`() {
        val tracker = SheetTracker(listOf(SheetPage("a", 0)), emptyMap())
        assertNull(tracker.locate(TransportState.Running(Timeline(0, 0, 0, STEADY)), 0))
    }
}

/** Builds a tiny PDF laid out the way tab printing does it: compressed pages, one positioned run per digit. */
private object TabPdf {
    private const val WIDTH = 600
    private const val HEIGHT = 800

    /** Each number is (value, x, y), drawn at 7pt in a bold font. Footers add the numbers a parser must ignore. */
    fun build(pages: List<List<Triple<Int, Int, Int>>>, pageNumberFooters: Boolean = false): ByteArray {
        val output = ByteArrayOutputStream()
        val offsetPages = 10
        val font = 3
        val cmap = 4

        fun write(text: String) = output.write(text.toByteArray(Charsets.ISO_8859_1))
        fun obj(id: Int, body: String) = write("$id 0 obj\n$body\nendobj\n")
        fun flate(id: Int, content: String) {
            val deflater = Deflater()
            deflater.setInput(content.toByteArray(Charsets.ISO_8859_1))
            deflater.finish()
            val buffer = ByteArray(65536)
            val size = deflater.deflate(buffer)
            write("$id 0 obj\n<</Filter /FlateDecode /Length $size>> stream\n")
            output.write(buffer, 0, size)
            write("\nendstream\nendobj\n")
        }

        write("%PDF-1.4\n")
        obj(1, "<</Type /Catalog /Pages 2 0 R>>")
        val kids = pages.indices.joinToString(" ") { "${offsetPages + it * 2} 0 R" }
        obj(2, "<</Type /Pages /Count ${pages.size} /Kids [$kids]>>")
        obj(font, "<</Type /Font /BaseFont /ABCDEF+Some-Bold /ToUnicode $cmap 0 R>>")
        flate(cmap, "1 beginbfrange\n<0030> <0039> <0030>\nendbfrange\n")
        pages.forEachIndexed { index, numbers ->
            val page = offsetPages + index * 2
            obj(
                page,
                "<</Type /Page /Parent 2 0 R /MediaBox [0 0 $WIDTH $HEIGHT] " +
                    "/Resources <</Font <</F1 $font 0 R>>>> /Contents ${page + 1} 0 R>>",
            )
            val content = StringBuilder("q\n.5 0 0 .5 0 0 cm\n")
            numbers.forEach { (value, x, y) ->
                value.toString().forEachIndexed { digit, char ->
                    val code = "%04X".format(char.code)
                    content.append("BT\n/F1 14 Tf\n1 0 0 1 ${(x + digit * 4) * 2} ${y * 2} Tm\n<$code> Tj\nET\n")
                }
            }
            if (pageNumberFooters) {
                val code = "%04X".format('1'.code + index)
                content.append("BT\n/F1 20 Tf\n1 0 0 1 1100 20 Tm\n<$code> Tj\nET\n")
            }
            content.append("Q\n")
            flate(page + 1, content.toString())
        }
        write("trailer\n<</Root 1 0 R>>\n")
        return output.toByteArray()
    }

    const val PAGE_WIDTH = WIDTH.toFloat()
}

class BarLayoutParserTest {
    private val twoLines = listOf(
        Triple(1, 42, 700),
        Triple(2, 300, 700),
        Triple(3, 42, 600),
        Triple(10, 300, 600),
    )

    @Test
    fun `finds bars, lines and pages`() {
        val second = listOf(Triple(11, 42, 700), Triple(12, 300, 700))
        val layout = assertNotNull(BarLayoutParser.parse(TabPdf.build(listOf(twoLines, second))))

        assertEquals(listOf(1, 2, 3, 10, 11, 12), layout.cells.map { it.bar })
        assertEquals(listOf(0, 0, 0, 0, 1, 1), layout.cells.map { it.pageIndex })
    }

    @Test
    fun `a bar runs to the next bar, and the last on a line to the right margin`() {
        val layout = assertNotNull(BarLayoutParser.parse(TabPdf.build(listOf(twoLines))))
        val (first, second) = layout.cells

        assertEquals(42 / TabPdf.PAGE_WIDTH, first.startX, FUZZ)
        assertEquals(300 / TabPdf.PAGE_WIDTH, first.endX, FUZZ)
        assertEquals((600 - 42) / TabPdf.PAGE_WIDTH, second.endX, FUZZ)
    }

    @Test
    fun `lines are measured from the top of the page`() {
        val layout = assertNotNull(BarLayoutParser.parse(TabPdf.build(listOf(twoLines))))

        assertTrue(layout.cells[0].top < layout.cells[0].bottom)
        assertTrue(layout.cells[0].bottom < layout.cells[2].top + 0.02f)
        assertTrue(layout.cells[0].isOnSameLineAs(layout.cells[1]))
        assertTrue(!layout.cells[0].isOnSameLineAs(layout.cells[2]))
    }

    @Test
    fun `page numbers are not mistaken for bars`() {
        val pages = listOf(listOf(Triple(1, 42, 700), Triple(2, 300, 700)), listOf(Triple(3, 42, 700), Triple(4, 300, 700)))
        val layout = assertNotNull(BarLayoutParser.parse(TabPdf.build(pages, pageNumberFooters = true)))

        assertEquals(listOf(1, 2, 3, 4), layout.cells.map { it.bar })
    }

    @Test
    fun `a document with only page numbers has no layout`() {
        val pages = List(5) { listOf(Triple(it + 1, 500, 40)) }
        assertNull(BarLayoutParser.parse(TabPdf.build(pages)))
    }

    @Test
    fun `numbers that don't rise steadily are not bars`() {
        val frets = listOf(Triple(5, 42, 700), Triple(3, 100, 700), Triple(7, 200, 700), Triple(2, 300, 700))
        assertNull(BarLayoutParser.parse(TabPdf.build(listOf(frets))))
    }

    @Test
    fun `garbage is not a crash`() {
        assertNull(BarLayoutParser.parse("not a pdf at all".encodeToByteArray()))
        assertNull(BarLayoutParser.parse(ByteArray(0)))
    }
}

/** Checks the real test tabs when they're on this machine. They're gitignored, so a fresh clone skips these. */
class RealTabLayoutTest {
    private fun layoutOf(name: String): BarLayout? {
        val file = File("../app/src/debug/assets/library/Iron Man/$name")
        return if (file.exists()) assertNotNull(BarLayoutParser.parse(file.readBytes()), name) else null
    }

    @Test
    fun `every bar of the drum tab is found`() {
        val layout = layoutOf("drums.pdf") ?: return
        assertEquals((1..149).toList(), layout.cells.map { it.bar })
    }

    @Test
    fun `the bar positions match what was measured from the PDF`() {
        val layout = layoutOf("drums.pdf") ?: return
        val bar = { n: Int -> layout.cells.first { it.bar == n } }

        assertEquals(41.8f / 594.96f, bar(7).startX, 0.002f)
        assertEquals(350f / 594.96f, bar(9).startX, 0.002f)
        assertEquals(0, bar(9).pageIndex)
        assertEquals(1, bar(18).pageIndex)
    }

    @Test
    fun `tabs with long rests still reach the last bar`() {
        for (name in listOf("bass.pdf", "guitar.pdf")) {
            val layout = layoutOf(name) ?: return
            assertEquals(149, layout.cells.last().bar, name)
            assertTrue(layout.cells.zipWithNext().all { (a, b) -> a.bar < b.bar }, name)
        }
    }

    @Test
    fun `the arrow crosses the whole song without losing its place`() {
        for (name in listOf("bass.pdf", "drums.pdf", "guitar.pdf")) {
            val layout = layoutOf(name) ?: return
            val lastBeat = ironMan.firstBeatOfBar(150)
            val path = (0 until lastBeat * 4).map { layout.locate(it / 4.0, ironMan) }

            assertTrue(path.all { it != null }, "$name has a hole")
            val pages = path.filterNotNull().map { it.pageIndex }
            assertEquals(pages.sorted(), pages, "$name went back a page")
        }
    }
}
