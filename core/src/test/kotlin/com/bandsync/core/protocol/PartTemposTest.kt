package com.bandsync.core.protocol

import com.bandsync.core.metronome.ironMan
import com.bandsync.core.sheets.BarLayout
import com.bandsync.core.sheets.BarLayoutParser
import com.bandsync.core.sheets.MeterMark
import com.bandsync.core.sheets.Role
import com.bandsync.core.sheets.TempoMark
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val SECOND = 1_000_000_000L
private val FOUR_FOUR = TimeSignature(4, 4)

class PartTemposTest {
    private val song = SongConfig(
        bpm = 120,
        changes = listOf(
            SongChange(3, 90, FOUR_FOUR),
            SongChange(5, 240, FOUR_FOUR, parts = setOf(Role.DRUMS)),
        ),
    )

    @Test
    fun `a change for some parts leaves the others alone`() {
        assertEquals(listOf(120, 90, 240), song.toMap(Role.DRUMS).sections.map { it.bpm })
        assertEquals(listOf(120, 90), song.toMap(Role.BASS).sections.map { it.bpm })
    }

    @Test
    fun `the shared map has only the changes for everyone`() {
        assertEquals(listOf(120, 90), song.toMap().sections.map { it.bpm })
    }

    @Test
    fun `a part's own change beats one for everyone on the same bar`() {
        val both = SongConfig(
            changes = listOf(
                SongChange(5, 200, FOUR_FOUR, parts = setOf(Role.DRUMS)),
                SongChange(5, 100, FOUR_FOUR),
            ),
        )
        assertEquals(200, both.toMap(Role.DRUMS).sections.last().bpm)
        assertEquals(100, both.toMap(Role.BASS).sections.last().bpm)
    }

    @Test
    fun `a part can open at its own tempo`() {
        val song = SongConfig(bpm = 100, changes = listOf(SongChange(1, 180, FOUR_FOUR, parts = setOf(Role.DRUMS))))
        assertEquals(180, song.toMap(Role.DRUMS).sections.single().bpm)
        assertEquals(100, song.toMap(Role.GUITAR).sections.single().bpm)
    }

    @Test
    fun `parts on different tempos share one song clock`() {
        val doubleTime = SongConfig(
            bpm = 120,
            changes = listOf(SongChange(2, 240, FOUR_FOUR, parts = setOf(Role.DRUMS))),
        )
        val origin = 100 * SECOND
        val drums = doubleTime.timelineFor(Role.DRUMS, origin, 0)
        val bass = doubleTime.timelineFor(Role.BASS, origin, 0)

        // Bar 2 of both starts together, then the drums play two bars in the time the bass plays one.
        assertEquals(bass.masterTimeOf(4), drums.masterTimeOf(4))
        assertEquals(bass.masterTimeOf(8), drums.masterTimeOf(12))
    }

    @Test
    fun `count-ins at different tempos end on the same instant`() {
        val song = SongConfig(
            bpm = 120,
            countInBars = 1,
            changes = listOf(SongChange(1, 60, FOUR_FOUR, parts = setOf(Role.BASS))),
        )
        val slowest = song.longestCountInNs(0)
        val origin = 100 * SECOND
        val guitar = song.timelineFor(Role.GUITAR, origin, 0)
        val bass = song.timelineFor(Role.BASS, origin, 0)

        assertEquals(4 * SECOND, slowest)
        assertEquals(origin, guitar.masterTimeOf(0))
        assertEquals(origin, bass.masterTimeOf(0))
        assertEquals(origin - 2 * SECOND, guitar.startMasterNs)
        assertEquals(origin - 4 * SECOND, bass.startMasterNs)
    }

    @Test
    fun `a part with no beat at the resume point comes in on its next one`() {
        // The shared bar 2 starts at 2 s, which falls between the 1.8 s and 2.4 s beats of a part at 100 BPM.
        val song = SongConfig(bpm = 120, changes = listOf(SongChange(1, 100, FOUR_FOUR, parts = setOf(Role.KEYS))))
        val keys = song.timelineFor(Role.KEYS, 0, 2 * SECOND)

        assertEquals(4L, keys.playFromBeat)
        assertEquals(2_400_000_000, keys.masterTimeOf(4))
        assertEquals(1, keys.beatInBar(keys.firstBeat))
    }
}

class PrintedTemposTest {
    private fun tab(vararg tempos: Pair<Int, Int>, meters: List<MeterMark> = emptyList()) = BarLayout(
        cells = emptyList(),
        tempos = tempos.map { (bar, bpm) -> TempoMark(bar, bpm) },
        meters = meters,
    )

    @Test
    fun `a mark on every tab is for the whole band`() {
        val song = SongConfig().withPrintedTempos(
            mapOf(
                Role.DRUMS to listOf(tab(1 to 80, 9 to 140)),
                Role.BASS to listOf(tab(1 to 80, 9 to 140)),
            ),
        )

        assertEquals(80, song.bpm)
        assertEquals(
            listOf(
                SongChange(1, 80, FOUR_FOUR, parts = setOf(Role.DRUMS)),
                SongChange(9, 140, FOUR_FOUR, parts = setOf(Role.DRUMS)),
                SongChange(1, 80, FOUR_FOUR, parts = setOf(Role.BASS)),
                SongChange(9, 140, FOUR_FOUR, parts = setOf(Role.BASS)),
            ),
            song.changes.filter { Role.DRUMS in it.parts || Role.BASS in it.parts },
        )
    }

    @Test
    fun `changing one part's copy leaves the other parts alone`() {
        val song = SongConfig().withPrintedTempos(
            mapOf(
                Role.DRUMS to listOf(tab(1 to 80, 9 to 140)),
                Role.BASS to listOf(tab(1 to 80, 9 to 140)),
            ),
        )

        val edited = song.copy(
            changes = song.changes.map { if (it.bar == 9 && Role.DRUMS in it.parts) it.copy(bpm = 200) else it },
        )

        assertEquals(200, edited.toMap(Role.DRUMS).sections.last().bpm)
        assertEquals(140, edited.toMap(Role.BASS).sections.last().bpm)
    }

    @Test
    fun `a part keeps a change that only its own tab prints`() {
        val song = SongConfig().withPrintedTempos(
            mapOf(Role.DRUMS to listOf(tab(1 to 80, 9 to 160)), Role.BASS to listOf(tab(1 to 80))),
        )

        assertEquals(160, song.toMap(Role.DRUMS).sections.last().bpm)
        assertEquals(80, song.toMap(Role.BASS).sections.last().bpm)
    }

    @Test
    fun `a part with no marks follows what the marked parts agree on`() {
        val song = SongConfig().withPrintedTempos(
            mapOf(
                Role.DRUMS to listOf(tab(1 to 80, 9 to 140, 20 to 200)),
                Role.BASS to listOf(tab(1 to 80, 9 to 140)),
            ),
        )

        assertEquals(listOf(80, 140), song.toMap(Role.VOCALS).sections.map { it.bpm })
    }

    @Test
    fun `every sheet of a part is read`() {
        val song = SongConfig().withPrintedTempos(
            mapOf(Role.DRUMS to listOf(tab(1 to 80), tab(9 to 160))),
        )

        assertEquals(listOf(80, 160), song.toMap(Role.DRUMS).sections.map { it.bpm })
    }

    @Test
    fun `the first sheet wins where a part's sheets disagree`() {
        val song = SongConfig().withPrintedTempos(
            mapOf(Role.DRUMS to listOf(tab(9 to 160), tab(9 to 100))),
        )

        assertEquals(160, song.toMap(Role.DRUMS).sections.last().bpm)
    }

    @Test
    fun `parts that open differently keep their own opening`() {
        val song = SongConfig(bpm = 100).withPrintedTempos(
            mapOf(Role.DRUMS to listOf(tab(1 to 160)), Role.BASS to listOf(tab(1 to 80))),
        )

        assertEquals(160, song.toMap(Role.DRUMS).sections.first().bpm)
        assertEquals(80, song.toMap(Role.BASS).sections.first().bpm)
    }

    @Test
    fun `tempo and meter marks on different bars combine`() {
        val marked = tab(1 to 80, 9 to 140, meters = listOf(MeterMark(1, 4, 4), MeterMark(5, 3, 4)))
        val song = SongConfig().withPrintedTempos(mapOf(Role.DRUMS to listOf(marked)))

        assertEquals(
            listOf(
                SongChange(1, 80, FOUR_FOUR),
                SongChange(5, 80, TimeSignature(3, 4)),
                SongChange(9, 140, TimeSignature(3, 4)),
            ),
            song.changes.filter { Role.DRUMS in it.parts }.map { it.copy(parts = emptySet()) },
        )
    }

    @Test
    fun `tabs with no marks leave the song alone`() {
        val song = SongConfig(bpm = 99, changes = listOf(SongChange(5, 120, FOUR_FOUR)))
        assertEquals(song, song.withPrintedTempos(mapOf(Role.DRUMS to listOf(tab()))))
    }

    @Test
    fun `reading again replaces what was there`() {
        val song = SongConfig(changes = listOf(SongChange(30, 200, FOUR_FOUR)))
        val read = song.withPrintedTempos(mapOf(Role.DRUMS to listOf(tab(1 to 80, 9 to 160))))

        assertTrue(read.changes.none { it.bar == 30 })
    }
}

/** Checks the real test tabs when they're on this machine. They're gitignored, so a fresh clone skips these. */
class RealTabTemposTest {
    private fun layoutOf(name: String): BarLayout? {
        val file = File("../app/src/debug/assets/test-tabs/iron-man-$name.pdf")
        return if (file.exists()) assertNotNull(BarLayoutParser.parse(file.readBytes()), name) else null
    }

    @Test
    fun `every tab prints the same marks`() {
        for (name in listOf("bass", "drums", "guitar")) {
            val layout = layoutOf(name) ?: return
            assertEquals(
                listOf(1 to 65, 9 to 70, 11 to 72, 13 to 76, 61 to 168, 85 to 78, 97 to 79, 103 to 158),
                layout.tempos.map { it.bar to it.bpm },
                name,
            )
            assertEquals(listOf(MeterMark(1, 4, 4), MeterMark(8, 2, 4), MeterMark(9, 4, 4)), layout.meters, name)
        }
    }

    @Test
    fun `the tabs preset the whole song for every instrument`() {
        val tabs = mapOf(Role.BASS to "bass", Role.DRUMS to "drums", Role.GUITAR to "guitar")
            .mapValues { (_, name) -> listOf(layoutOf(name) ?: return) }

        val song = SongConfig(title = "Iron Man").withPrintedTempos(tabs)

        for (part in tabs.keys) {
            assertEquals(9, song.changes.count { part in it.parts }, "$part has a list of its own")
            assertEquals(ironMan, song.toMap(part), "$part")
        }
        assertEquals(ironMan, song.toMap(Role.VOCALS), "a part with no tab follows what the tabs agree on")
    }
}
