package com.bandsync.core.protocol

import com.bandsync.core.metronome.Timeline
import com.bandsync.core.sheets.Role
import com.bandsync.core.sheets.SheetFile
import com.bandsync.core.sheets.SheetManifest
import com.bandsync.core.sheets.SheetSegment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WireCodecTest {
    private val timeline = Timeline(Long.MAX_VALUE / 2, -4, 0, 120, 4)

    @Test
    fun `every message survives a round trip`() {
        val messages = listOf(
            WireMessage.Hello("Sam's phone", Role.DRUMS),
            WireMessage.Ping(1),
            WireMessage.Pong(1, 2, 3),
            WireMessage.NeedFiles(2, listOf("a", "b")),
            WireMessage.FileReceived("a", ok = true),
            WireMessage.SyncReport(1_500_000),
            WireMessage.SongInfo(SongConfig("Song", 96, TimeSignature(6, 8), countInBars = 2)),
            WireMessage.Sheets(
                SheetManifest(
                    revision = 2,
                    role = Role.BASS,
                    files = listOf(SheetFile("a", "bass.pdf", 10, 2)),
                    segments = listOf(SheetSegment("a", listOf(1))),
                ),
            ),
            WireMessage.FileHeader(99, "a"),
            WireMessage.StartAt(timeline),
            WireMessage.PauseAt(timeline, 5),
            WireMessage.Stop,
        )

        messages.forEach { assertEquals(it, WireCodec.decode(WireCodec.encode(it))) }
    }

    @Test
    fun `a clock reading keeps every bit`() {
        val big = 9_007_199_254_740_993 // Not representable as a double.
        assertEquals(WireMessage.Ping(big), WireCodec.decode(WireCodec.encode(WireMessage.Ping(big))))
    }

    @Test
    fun `garbage decodes to null instead of throwing`() {
        assertNull(WireCodec.decode("not json".encodeToByteArray()))
        assertNull(WireCodec.decode("""{"t":"nonsense"}""".encodeToByteArray()))
        assertNull(WireCodec.decode("""{"t":"ping"}""".encodeToByteArray()))
    }

    @Test
    fun `unknown fields from a newer version are ignored`() {
        val bytes = """{"t":"ping","t0":5,"extra":true}""".encodeToByteArray()
        assertEquals(WireMessage.Ping(5), WireCodec.decode(bytes))
    }

    @Test
    fun `a full manifest fits well inside a BYTES payload`() {
        val files = List(50) { SheetFile("0".repeat(64), "A long sheet name number $it.pdf", 1L shl 30, 500) }
        val segments = List(50) { SheetSegment("0".repeat(64), (0 until 40).toList()) }
        val bytes = WireCodec.encode(WireMessage.Sheets(SheetManifest(1, Role.GUITAR, files, segments)))
        assertTrue(bytes.size < 32_768, "manifest is ${bytes.size} bytes")
    }
}
