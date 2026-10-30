package com.bandsync.core.protocol

import com.bandsync.core.metronome.SongMap
import com.bandsync.core.metronome.SongSection
import com.bandsync.core.metronome.Timeline
import com.bandsync.core.metronome.durationNs
import com.bandsync.core.sheets.FileId
import com.bandsync.core.sheets.Role
import com.bandsync.core.sheets.SheetManifest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Serializable
data class TimeSignature(val beatsPerBar: Int, val beatUnit: Int) {
    val label: String get() = "$beatsPerBar/$beatUnit"
}

/**
 * From [bar] on, the song has this tempo and time signature, until the next change. With no [parts] it is for
 * the whole band, otherwise only for those parts. [bar] is numbered as printed on the part's own tab.
 */
@Serializable
data class SongChange(
    val bar: Int,
    val bpm: Int,
    val timeSignature: TimeSignature,
    val parts: Set<Role> = emptySet(),
)

/** [bpm] and [timeSignature] are how the song opens. */
@Serializable
data class SongConfig(
    val title: String = "",
    val bpm: Int = 120,
    val timeSignature: TimeSignature = TimeSignature(4, 4),
    val countInBars: Int = 1,
    val changes: List<SongChange> = emptyList(),
) {
    /**
     * [part]'s map of the song, or with no part the map everyone shares. Changes can be in any order. If two share
     * a bar the later one wins, and a part's own change beats one for everyone. The opening values own bar 1,
     * except that a part can open differently with a change of its own.
     */
    fun toMap(part: Role? = null): SongMap {
        val applicable = changes
            .filter { it.parts.isEmpty() && it.bar > 1 || part != null && part in it.parts }
            .sortedBy { it.parts.isNotEmpty() }
            .associateBy { it.bar }
        val opening = applicable[1]
        val openingBeats = (opening?.timeSignature ?: timeSignature).beatsPerBar
        val sections = mutableListOf(SongSection(1, 0, opening?.bpm ?: bpm, openingBeats))
        applicable.filterKeys { it > 1 }.toSortedMap().values.forEach { change ->
            val previous = sections.last()
            sections += SongSection(
                fromBar = change.bar.toLong(),
                fromBeat = previous.fromBeat + (change.bar - previous.fromBar) * previous.beatsPerBar,
                bpm = change.bpm,
                beatsPerBar = change.timeSignature.beatsPerBar,
            )
        }
        return SongMap(sections)
    }

    /**
     * [part]'s timeline, laid on the one song clock the whole band shares: bar 1 of every part falls at
     * [originMasterNs], and a part at song time t plays at origin + t whatever its tempo. So parts can move
     * apart and meet again exactly, and a count-in at any tempo ends on the same instant for everyone.
     * [playFromNs] is the song time to play from. A part with no beat there comes in on its next one.
     */
    fun timelineFor(part: Role, originMasterNs: Long, playFromNs: Long): Timeline {
        val map = toMap(part)
        val playFromBeat = map.nextBeatFrom(playFromNs)
        val section = map.sectionAt(playFromBeat)
        val countInBeats = countInBars.toLong() * section.beatsPerBar
        return Timeline(
            startMasterNs = originMasterNs + map.timeOf(playFromBeat) - durationNs(countInBeats, section.bpm),
            firstBeat = playFromBeat - countInBeats,
            playFromBeat = playFromBeat,
            map = map,
        )
    }

    /** The slowest part's count-in, which is how much room the band needs before [playFromNs]. */
    fun longestCountInNs(playFromNs: Long): Long = Role.entries.maxOf { part ->
        val map = toMap(part)
        val section = map.sectionAt(map.nextBeatFrom(playFromNs))
        durationNs(countInBars.toLong() * section.beatsPerBar, section.bpm)
    }
}

@Serializable
sealed interface WireMessage {
    // Client to master.

    @Serializable
    @SerialName("hello")
    data class Hello(val name: String, val role: Role) : WireMessage

    @Serializable
    @SerialName("ping")
    data class Ping(val t0: Long) : WireMessage

    @Serializable
    @SerialName("need")
    data class NeedFiles(val revision: Int, val fileIds: List<FileId>) : WireMessage

    @Serializable
    @SerialName("got")
    data class FileReceived(val fileId: FileId, val ok: Boolean) : WireMessage

    @Serializable
    @SerialName("sync")
    data class SyncReport(val uncertaintyNs: Long) : WireMessage

    // Master to client.

    @Serializable
    @SerialName("pong")
    data class Pong(val t0: Long, val t1: Long, val t2: Long) : WireMessage

    @Serializable
    @SerialName("song")
    data class SongInfo(val song: SongConfig) : WireMessage

    @Serializable
    @SerialName("sheets")
    data class Sheets(val manifest: SheetManifest) : WireMessage

    /** Names a FILE payload. BYTES and FILE have no ordering guarantee, so either may arrive first. */
    @Serializable
    @SerialName("file")
    data class FileHeader(val payloadId: Long, val fileId: FileId) : WireMessage

    @Serializable
    @SerialName("start")
    data class StartAt(val timeline: Timeline) : WireMessage

    @Serializable
    @SerialName("pause")
    data class PauseAt(val timeline: Timeline, val atMasterNs: Long) : WireMessage

    @Serializable
    @SerialName("stop")
    data object Stop : WireMessage
}

object WireCodec {
    private val json = Json {
        classDiscriminator = "t"
        ignoreUnknownKeys = true
    }

    fun encode(message: WireMessage): ByteArray =
        json.encodeToString(WireMessage.serializer(), message).encodeToByteArray()

    /** Returns null for anything undecodable, so a malformed packet can't take a session down. */
    fun decode(bytes: ByteArray): WireMessage? = try {
        json.decodeFromString(WireMessage.serializer(), bytes.decodeToString())
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
