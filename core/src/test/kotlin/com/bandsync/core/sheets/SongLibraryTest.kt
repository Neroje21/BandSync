package com.bandsync.core.sheets

import com.bandsync.core.protocol.SongConfig
import com.bandsync.core.protocol.TimeSignature
import com.bandsync.core.protocol.withPrintedTempos
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Song name to each part's tab, read the way the app reads the library. Empty on a fresh clone. */
private val library: Map<String, Map<Role, BarLayout>> by lazy {
    File("../app/src/debug/assets/library").listFiles().orEmpty()
        .filter { it.isDirectory }
        .sortedBy { it.name }
        .associate { song ->
            song.name to song.listFiles().orEmpty().associate { file ->
                val part = Role.entries.firstOrNull { file.name.equals("${it.name}.pdf", ignoreCase = true) }
                assertNotNull(part, file.name) to assertNotNull(BarLayoutParser.parse(file.readBytes()), file.path)
            }
        }
}

private fun presetFor(tabs: Map<Role, BarLayout>): SongConfig =
    SongConfig().withPrintedTempos(tabs.mapValues { (_, tab) -> listOf(tab) })

/** Checks the real library when it's on this machine. It's gitignored, so a fresh clone skips these. */
class RealLibraryTest {
    private class Expected(val bpm: Int, val lastBar: Int, val seconds: Double)

    private val expected = mapOf(
        "Iron Man" to Expected(bpm = 65, lastBar = 149, seconds = 353.6),
        "Paranoid" to Expected(bpm = 163, lastBar = 112, seconds = 164.9),
        "Seven Nation Army" to Expected(bpm = 124, lastBar = 117, seconds = 226.5),
    )

    @Test
    fun `each song has a tab for guitar, bass and drums`() {
        for ((title, tabs) in library) assertEquals(setOf(Role.GUITAR, Role.BASS, Role.DRUMS), tabs.keys, title)
    }

    @Test
    fun `each song's preset matches what its tabs print`() {
        for ((title, tabs) in library) {
            val song = expected[title] ?: continue
            val preset = presetFor(tabs)

            assertEquals(song.bpm, preset.bpm, title)
            assertEquals(TimeSignature(4, 4), preset.timeSignature, title)
            for ((part, tab) in tabs) {
                val map = preset.toMap(part)
                assertEquals(song.lastBar, tab.cells.last().bar, "$title $part")
                assertEquals(song.seconds, map.timeOf(map.firstBeatOfBar(song.lastBar + 1L)) / 1e9, 0.1, "$title $part")
            }
        }
    }

    @Test
    fun `every instrument gets a list of its own`() {
        for ((title, tabs) in library) {
            val preset = presetFor(tabs)
            for (part in tabs.keys) assertTrue(preset.changes.any { part in it.parts }, "$title $part")
        }
    }

    @Test
    fun `the arrow crosses every part of every song`() {
        for ((title, tabs) in library) {
            val preset = presetFor(tabs)
            for ((part, tab) in tabs) {
                val map = preset.toMap(part)
                val beats = map.firstBeatOfBar(tab.cells.last().bar + 1L)
                val path = (0 until beats * 4).map { tab.locate(it / 4.0, map) }

                assertTrue(path.all { it != null }, "$title $part has a hole")
                val pages = path.filterNotNull().map { it.pageIndex }
                assertEquals(pages.sorted(), pages, "$title $part went back a page")
            }
        }
    }
}
