package com.bandsync.core.protocol

import com.bandsync.core.metronome.Timeline
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

@Serializable
data class SongConfig(
    val title: String = "",
    val bpm: Int = 120,
    val timeSignature: TimeSignature = TimeSignature(4, 4),
    val countInBars: Int = 1,
)

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
