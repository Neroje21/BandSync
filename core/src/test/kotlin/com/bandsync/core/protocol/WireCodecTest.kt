package com.bandsync.core.protocol

import com.bandsync.core.metronome.MAX_BPM
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
    private val map = SongConfig(
        bpm = 97,
        changes = listOf(
            SongChange(5, 168, TimeSignature(4, 4)),
            SongChange(9, 61, TimeSignature(3, 4)),
        ),
    ).toMap()
    private val timeline = Timeline(Long.MAX_VALUE / 2, -4, 0, map)

    @Test
    fun `every message survives a round trip`() {
        val messages = listOf(
            WireMessage.Hello("Sam's phone", Role.DRUMS),
            WireMessage.Ping(1),
            WireMessage.Pong(1, 2, 3),
            WireMessage.NeedFiles(2, listOf("a", "b")),
            WireMessage.FileReceived("a", ok = true),
            WireMessage.SyncReport(1_500_000),
            WireMessage.SongInfo(
                SongConfig(
                    title = "Song",
                    bpm = 96,
                    timeSignature = TimeSignature(6, 8),
                    countInBars = 2,
                    changes = listOf(SongChange(9, 140, TimeSignature(4, 4))),
                ),
            ),
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

    /** The section start times aren't sent, so every phone has to rebuild identical ones from what is. */
    @Test
    fun `a received timeline computes exactly the same beats`() {
        val received = (WireCodec.decode(WireCodec.encode(WireMessage.StartAt(timeline))) as WireMessage.StartAt).timeline

        for (beat in -4L..200) {
            assertEquals(timeline.masterTimeOf(beat), received.masterTimeOf(beat), "beat $beat")
            assertEquals(timeline.beatInBar(beat), received.beatInBar(beat), "beat $beat")
        }
    }

    @Test
    fun `a song with many tempo changes fits inside a BYTES payload`() {
        val changes = (2..300).map { SongChange(it, MAX_BPM, TimeSignature(12, 8)) }
        val busy = Timeline(Long.MAX_VALUE / 2, -24, 0, SongConfig(changes = changes).toMap())

        val bytes = WireCodec.encode(WireMessage.StartAt(busy))

        assertTrue(bytes.size < 32_768, "StartAt is ${bytes.size} bytes")
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
