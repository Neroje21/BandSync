package com.bandsync.core.sheets

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater
import kotlin.math.abs
import kotlin.math.roundToInt

/** Headroom above a bar number for the top of the arrow, in points. */
private const val ABOVE_NUMBER_PT = 6.0

/** Gap left before the next line's bar numbers, in points. */
private const val ROW_GAP_PT = 12.0

/** Used for a page with a single line, so there's no other line to measure from. */
private const val DEFAULT_ROW_HEIGHT_PT = 100.0

/** Fewer than this per page is more likely to be page numbers than bars. */
private const val MIN_NUMBERS_PER_PAGE = 2

private const val NUMBER = """-?[\d.]+\s+"""

/** The content stream operators that move or draw text, plus the q/Q/cm that nest the coordinate system. */
private val CONTENT_TOKEN = Regex(
    """(?<![\w.])([qQ])(?=\s)""" +
        """|((?:$NUMBER){6})cm(?=\s)""" +
        """|((?:$NUMBER){6})Tm(?=\s)""" +
        """|((?:$NUMBER){2})Td(?=\s)""" +
        """|/(\w+)\s+(-?[\d.]+)\s+Tf(?=\s)""" +
        """|<([0-9A-Fa-f]+)>\s*Tj""" +
        """|\[([^\]]*)\]\s*TJ""",
)

private data class Matrix(val a: Double, val b: Double, val c: Double, val d: Double, val e: Double, val f: Double) {
    operator fun times(o: Matrix) = Matrix(
        a * o.a + b * o.c,
        a * o.b + b * o.d,
        c * o.a + d * o.c,
        c * o.b + d * o.d,
        e * o.a + f * o.c + o.e,
        e * o.b + f * o.d + o.f,
    )

    companion object {
        val IDENTITY = Matrix(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)

        fun of(text: String): Matrix {
            val v = text.trim().split(Regex("""\s+""")).map(String::toDouble)
            return Matrix(v[0], v[1], v[2], v[3], v[4], v[5])
        }
    }
}

private class Font(val name: String, val unicode: Map<Int, Char>)

private class TextRun(val page: Int, val x: Double, val y: Double, val size: Double, val font: String, val text: String)

private class PrintedNumber(val value: Int, val page: Int, val x: Double, val y: Double)

private class Page(val width: Double, val height: Double, val text: List<TextRun>)

/**
 * Finds where bars are printed in a tab PDF. It reads the printed bar numbers, so it needs a tab that numbers
 * its bars, as Songsterr's print layout does. PdfRenderer can't report text positions on most Android versions,
 * which is why this reads the file directly.
 *
 * Bar numbers are recognised by what they are, not by their font, which differs between computers: they are
 * the one set of numbers in a single type style that rises steadily through the whole document.
 */
object BarLayoutParser {
    /** Returns null for anything it can't make sense of, which just means this sheet has no arrow. */
    fun parse(pdf: ByteArray): BarLayout? = runCatching {
        val objects = readObjects(pdf.toString(Charsets.ISO_8859_1))
        val pages = readPages(objects)
        val numbers = findBarNumbers(pages) ?: return null
        BarLayout(buildCells(numbers, pages))
    }.getOrNull()

    private fun readObjects(pdf: String): Map<Int, String> {
        val header = Regex("""(?m)^(\d+) 0 obj\b""")
        val objects = HashMap<Int, String>()
        var match = header.find(pdf)
        while (match != null) {
            val start = match.range.last + 1
            val end = pdf.indexOf("endobj", start)
            if (end < 0) break
            objects[match.groupValues[1].toInt()] = pdf.substring(start, end)
            match = header.find(pdf, end)
        }
        return objects
    }

    private fun readPages(objects: Map<Int, String>): List<Page> {
        val root = objects.entries.first { "/Type /Pages" in it.value && "/Parent" !in it.value }.key
        val pageObjects = mutableListOf<String>()

        fun walk(id: Int) {
            val body = objects[id] ?: return
            if ("/Type /Pages" in body) {
                val kids = Regex("""/Kids \[(.*?)\]""", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)
                Regex("""(\d+) 0 R""").findAll(kids.orEmpty()).forEach { walk(it.groupValues[1].toInt()) }
            } else if (Regex("""/Type /Page\b""").containsMatchIn(body)) {
                pageObjects += body
            }
        }
        walk(root)

        return pageObjects.mapIndexed { index, body ->
            val box = Regex("""/MediaBox \[([^\]]*)\]""").find(body)!!.groupValues[1].trim().split(Regex("""\s+"""))
            val contents = Regex("""/Contents (\d+) 0 R""").find(body)!!.groupValues[1].toInt()
            Page(
                width = box[2].toDouble(),
                height = box[3].toDouble(),
                text = readText(index, inflate(objects.getValue(contents)), readFonts(body, objects)),
            )
        }
    }

    private fun readFonts(page: String, objects: Map<Int, String>): Map<String, Font> {
        val resources = Regex("""/Resources (\d+) 0 R""").find(page)
            ?.let { objects[it.groupValues[1].toInt()] }
            ?: page
        val inline = Regex("""/Font\s*<<(.*?)>>""", RegexOption.DOT_MATCHES_ALL).find(resources)?.groupValues?.get(1)
        val table = inline
            ?: Regex("""/Font (\d+) 0 R""").find(resources)?.let { objects[it.groupValues[1].toInt()] }
            ?: return emptyMap()
        return Regex("""/(\w+) (\d+) 0 R""").findAll(table).associate { match ->
            val font = objects[match.groupValues[2].toInt()].orEmpty()
            val name = Regex("""/BaseFont /([^\s/]+)""").find(font)?.groupValues?.get(1).orEmpty().substringAfter('+')
            val unicode = Regex("""/ToUnicode (\d+) 0 R""").find(font)
                ?.let { objects[it.groupValues[1].toInt()] }
                ?.let { readCharacterMap(inflate(it)) }
                .orEmpty()
            match.groupValues[1] to Font(name, unicode)
        }
    }

    private fun readCharacterMap(cmap: String): Map<Int, Char> {
        val mapping = HashMap<Int, Char>()
        val hex = """<([0-9A-Fa-f]+)>"""
        Regex("""beginbfchar(.*?)endbfchar""", RegexOption.DOT_MATCHES_ALL).findAll(cmap).forEach { block ->
            Regex("""$hex\s*$hex""").findAll(block.groupValues[1]).forEach {
                mapping[it.groupValues[1].toInt(16)] = it.groupValues[2].take(4).toInt(16).toChar()
            }
        }
        Regex("""beginbfrange(.*?)endbfrange""", RegexOption.DOT_MATCHES_ALL).findAll(cmap).forEach { block ->
            Regex("""$hex\s*$hex\s*$hex""").findAll(block.groupValues[1]).forEach {
                val low = it.groupValues[1].toInt(16)
                val base = it.groupValues[3].take(4).toInt(16)
                for (code in low..it.groupValues[2].toInt(16)) mapping[code] = (base + code - low).toChar()
            }
        }
        return mapping
    }

    private fun readText(page: Int, content: String, fonts: Map<String, Font>): List<TextRun> {
        val runs = mutableListOf<TextRun>()
        val saved = ArrayDeque<Matrix>()
        var transform = Matrix.IDENTITY
        var text = Matrix.IDENTITY
        var font: Font? = null
        var size = 0.0

        for (token in CONTENT_TOKEN.findAll(content)) {
            val (saveOrRestore, cm, tm, td, fontName, fontSize, hex, array) = token.destructured
            when {
                saveOrRestore == "q" -> saved.addLast(transform)
                saveOrRestore == "Q" -> transform = saved.removeLastOrNull() ?: transform
                cm.isNotEmpty() -> transform = Matrix.of(cm) * transform
                tm.isNotEmpty() -> text = Matrix.of(tm)
                td.isNotEmpty() -> {
                    val (dx, dy) = td.trim().split(Regex("""\s+""")).map(String::toDouble)
                    text = Matrix(1.0, 0.0, 0.0, 1.0, dx, dy) * text
                }
                fontName.isNotEmpty() -> {
                    font = fonts[fontName]
                    size = fontSize.toDouble()
                }
                else -> {
                    val chunks = if (hex.isNotEmpty()) {
                        listOf(hex)
                    } else {
                        Regex("""<([0-9A-Fa-f]+)>""").findAll(array).map { it.groupValues[1] }.toList()
                    }
                    val decoded = chunks.joinToString("") { chunk -> decode(chunk, font) }.trim()
                    if (decoded.isNotEmpty()) {
                        val place = text * transform
                        val height = abs(size * place.d)
                        runs += TextRun(page, place.e, place.f, height, font?.name.orEmpty(), decoded)
                    }
                }
            }
        }
        return runs
    }

    private fun decode(hex: String, font: Font?): String =
        hex.chunked(4).mapNotNull { code -> font?.unicode?.get(code.toInt(16)) }.joinToString("")

    private fun findBarNumbers(pages: List<Page>): List<PrintedNumber>? {
        val styles = pages.flatMap { it.text }
            .filter { run -> run.text.all(Char::isDigit) }
            .groupBy { it.font to (it.size * 10).roundToInt() }
        return styles.values
            .map(::readNumbers)
            .filter { numbers ->
                numbers.size >= pages.size * MIN_NUMBERS_PER_PAGE &&
                    numbers.first().value <= 2 &&
                    numbers.zipWithNext().all { (a, b) -> a.value < b.value }
            }
            .maxByOrNull { it.size }
    }

    /** Tabs often draw each digit on its own, so digits side by side are put back together. */
    private fun readNumbers(digits: List<TextRun>): List<PrintedNumber> =
        digits.groupBy { it.page }.toSortedMap().values.flatMap { onPage ->
            rows(onPage, tolerance = onPage.first().size) { it.y }.flatMap { row ->
                val numbers = mutableListOf<MutableList<TextRun>>()
                row.sortedBy { it.x }.forEach { digit ->
                    val previous = numbers.lastOrNull()?.last()
                    if (previous != null && digit.x - previous.x <= digit.size) {
                        numbers.last() += digit
                    } else {
                        numbers += mutableListOf(digit)
                    }
                }
                numbers.mapNotNull { parts ->
                    val first = parts.first()
                    parts.joinToString("") { it.text }.toIntOrNull()?.let { PrintedNumber(it, first.page, first.x, first.y) }
                }
            }
        }

    private fun <T> rows(items: List<T>, tolerance: Double, y: (T) -> Double): List<List<T>> {
        val rows = mutableListOf<MutableList<T>>()
        items.sortedByDescending(y).forEach { item ->
            val row = rows.lastOrNull()
            if (row != null && abs(y(row.first()) - y(item)) <= tolerance) row += item else rows += mutableListOf(item)
        }
        return rows
    }

    private fun buildCells(numbers: List<PrintedNumber>, pages: List<Page>): List<BarCell> {
        val leftMargin = numbers.minOf { it.x }
        val cells = mutableListOf<BarCell>()
        numbers.groupBy { it.page }.forEach { (pageIndex, onPage) ->
            val page = pages[pageIndex]
            val lines = rows(onPage, tolerance = 2.0) { it.y }
            lines.forEachIndexed { lineIndex, line ->
                val y = line.first().y
                val lineHeight = when {
                    lineIndex + 1 < lines.size -> y - lines[lineIndex + 1].first().y
                    lineIndex > 0 -> lines[lineIndex - 1].first().y - y
                    else -> DEFAULT_ROW_HEIGHT_PT
                }
                val sorted = line.sortedBy { it.x }
                sorted.forEachIndexed { index, number ->
                    val end = sorted.getOrNull(index + 1)?.x ?: (page.width - leftMargin)
                    cells += BarCell(
                        bar = number.value,
                        pageIndex = pageIndex,
                        startX = (number.x / page.width).toFloat(),
                        endX = (end / page.width).toFloat(),
                        top = (1 - (y + ABOVE_NUMBER_PT) / page.height).toFloat(),
                        bottom = (1 - (y - lineHeight + ROW_GAP_PT) / page.height).toFloat(),
                    )
                }
            }
        }
        return cells
    }

    private fun inflate(body: String): String {
        val start = body.indexOf("stream")
        val end = body.lastIndexOf("endstream")
        if (start < 0 || end < 0) return ""
        var from = start + "stream".length
        if (body.startsWith("\r", from)) from++
        if (body.startsWith("\n", from)) from++
        val raw = body.substring(from, end).toByteArray(Charsets.ISO_8859_1)
        if ("FlateDecode" !in body.substring(0, start)) return raw.toString(Charsets.ISO_8859_1)

        val inflater = Inflater()
        val output = ByteArrayOutputStream(raw.size * 4)
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        try {
            inflater.setInput(raw)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                output.write(buffer, 0, count)
            }
        } finally {
            inflater.end()
        }
        return output.toString("ISO-8859-1")
    }
}
