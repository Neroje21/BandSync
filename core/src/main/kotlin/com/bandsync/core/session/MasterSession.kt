package com.bandsync.core.session

import com.bandsync.core.metronome.Timeline
import com.bandsync.core.metronome.TransportState
import com.bandsync.core.protocol.SongConfig
import com.bandsync.core.protocol.WireCodec
import com.bandsync.core.protocol.WireMessage
import com.bandsync.core.sheets.FileId
import com.bandsync.core.sheets.Role
import com.bandsync.core.sheets.RoleSheetMap
import com.bandsync.core.time.MonotonicClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** How far ahead the master schedules a start, so START_AT reaches every phone before it takes effect. */
const val START_LEAD_NS = 2_000_000_000L

/** How far ahead a pause is scheduled, so every phone freezes on the same instant. */
const val PAUSE_LEAD_NS = 200_000_000L

sealed interface DeliveryState {
    data object Pending : DeliveryState
    data class Sending(val fraction: Float) : DeliveryState
    data object Verifying : DeliveryState
    data object Delivered : DeliveryState
    data object Failed : DeliveryState
}

data class Member(
    val endpoint: EndpointId,
    val name: String,
    val role: Role,
    val delivery: Map<FileId, DeliveryState> = emptyMap(),
    val uncertaintyNs: Long? = null,
)

data class MasterState(
    val ownRole: Role,
    val song: SongConfig = SongConfig(),
    val members: Map<EndpointId, Member> = emptyMap(),
    val sheets: RoleSheetMap = RoleSheetMap(),
    val transport: TransportState = TransportState.Stopped,
    val revision: Int = 0,
)

private data class OutgoingFile(val endpoint: EndpointId, val fileId: FileId)

/** What the band is playing now. Any part's timeline can be built from it, including a late joiner's. */
private class Performance(val song: SongConfig, val originMasterNs: Long, val playFromNs: Long) {
    fun timelineFor(part: Role): Timeline = song.timelineFor(part, originMasterNs, playFromNs)
}

class MasterSession(
    private val transport: Transport,
    private val clock: MonotonicClock,
    ownRole: Role,
) : TransportListener {
    private val _state = MutableStateFlow(MasterState(ownRole))
    val state: StateFlow<MasterState> = _state.asStateFlow()

    private val outgoing = HashMap<Long, OutgoingFile>()
    private var performance: Performance? = null

    fun updateSong(song: SongConfig) {
        _state.update { it.copy(song = song) }
        broadcast(WireMessage.SongInfo(song))
    }

    fun updateSheets(sheets: RoleSheetMap) {
        _state.update { it.copy(sheets = sheets, revision = it.revision + 1) }
        _state.value.members.keys.forEach(::sendManifest)
    }

    /**
     * Starts from the top, or from the beginning of the paused bar so the band re-enters on a downbeat. The start
     * is timed so that the slowest part's count-in still begins at least [START_LEAD_NS] from now.
     */
    fun start() {
        val current = _state.value
        val song = current.song
        val playFromNs = resumePointNs(current)
        val originMasterNs = clock.nowNs() + START_LEAD_NS + song.longestCountInNs(playFromNs) - playFromNs
        val next = Performance(song, originMasterNs, playFromNs)
        performance = next
        _state.update { it.copy(transport = TransportState.Running(next.timelineFor(it.ownRole))) }
        current.members.values.forEach { send(it.endpoint, WireMessage.StartAt(next.timelineFor(it.role))) }
    }

    /**
     * The bar the master's own part paused in, found by number, which still means the same place if the song was
     * edited while paused. All parts resume from that one moment, so parts on tempos of their own keep their
     * place relative to each other, and parts on the master's tempo come back in on a bar line with it.
     */
    private fun resumePointNs(current: MasterState): Long {
        val paused = current.transport as? TransportState.Paused ?: return 0
        val playing = performance ?: return 0
        val before = playing.song.toMap(current.ownRole)
        val pausedNs = maxOf(paused.atMasterNs - playing.originMasterNs, playing.playFromNs)
        val after = current.song.toMap(current.ownRole)
        return after.timeOf(after.firstBeatOfBar(before.barOf(before.beatAt(pausedNs))))
    }

    fun pause() {
        val running = _state.value.transport as? TransportState.Running ?: return
        val playing = performance ?: return
        val atMasterNs = clock.nowNs() + PAUSE_LEAD_NS
        _state.update { it.copy(transport = TransportState.Paused(running.timeline, atMasterNs)) }
        _state.value.members.values.forEach {
            send(it.endpoint, WireMessage.PauseAt(playing.timelineFor(it.role), atMasterNs))
        }
    }

    fun stop() {
        performance = null
        _state.update { it.copy(transport = TransportState.Stopped) }
        broadcast(WireMessage.Stop)
    }

    /** Re-offers a member its sheets. The manifest handshake finds out what is still missing. */
    fun redeliver(endpoint: EndpointId) = sendManifest(endpoint)

    override fun onConnected(endpoint: EndpointId) = Unit // A member only counts once it has said hello.

    override fun onDisconnected(endpoint: EndpointId) {
        outgoing.values.removeAll { it.endpoint == endpoint }
        _state.update { it.copy(members = it.members - endpoint) }
    }

    override fun onBytes(from: EndpointId, bytes: ByteArray, receivedAtNs: Long) {
        when (val message = WireCodec.decode(bytes)) {
            is WireMessage.Ping -> {
                // t2 is read last so the master's own processing time drops out of the round trip.
                val pong = WireMessage.Pong(message.t0, t1 = receivedAtNs, t2 = clock.nowNs())
                send(from, pong)
            }
            is WireMessage.Hello -> onHello(from, message)
            is WireMessage.NeedFiles -> onNeedFiles(from, message)
            is WireMessage.FileReceived -> setDelivery(
                from,
                message.fileId,
                if (message.ok) DeliveryState.Delivered else DeliveryState.Failed,
            )
            is WireMessage.SyncReport -> updateMember(from) { it.copy(uncertaintyNs = message.uncertaintyNs) }
            else -> Unit
        }
    }

    override fun onFileProgress(payloadId: Long, transferredBytes: Long, totalBytes: Long) {
        val file = outgoing[payloadId] ?: return
        val fraction = if (totalBytes > 0) transferredBytes.toFloat() / totalBytes else 0f
        setDelivery(file.endpoint, file.fileId, DeliveryState.Sending(fraction))
    }

    override fun onFileSent(payloadId: Long) {
        val file = outgoing.remove(payloadId) ?: return
        setDelivery(file.endpoint, file.fileId, DeliveryState.Verifying)
    }

    override fun onFileFailed(payloadId: Long) {
        val file = outgoing.remove(payloadId) ?: return
        setDelivery(file.endpoint, file.fileId, DeliveryState.Failed)
    }

    override fun onFileReceived(payloadId: Long, file: IncomingFile) = file.delete() // Masters only send.

    private fun onHello(from: EndpointId, hello: WireMessage.Hello) {
        _state.update { it.copy(members = it.members + (from to Member(from, hello.name, hello.role))) }
        val current = _state.value
        send(from, WireMessage.SongInfo(current.song))
        sendManifest(from)
        // A late joiner gets the live timeline and works out the current beat from it by itself. Stopped is
        // sent too: a phone rejoining after a drop is still following the timeline it had.
        val timeline = performance?.timelineFor(hello.role)
        when (val state = current.transport) {
            TransportState.Stopped -> send(from, WireMessage.Stop)
            is TransportState.Running -> send(from, WireMessage.StartAt(timeline ?: state.timeline))
            is TransportState.Paused -> send(from, WireMessage.PauseAt(timeline ?: state.timeline, state.atMasterNs))
        }
    }

    private fun sendManifest(endpoint: EndpointId) {
        val current = _state.value
        val member = current.members[endpoint] ?: return
        send(endpoint, WireMessage.Sheets(current.sheets.manifestFor(member.role, current.revision)))
    }

    private fun onNeedFiles(from: EndpointId, need: WireMessage.NeedFiles) {
        val current = _state.value
        val member = current.members[from] ?: return
        if (need.revision != current.revision) return // A newer manifest is on its way; its reply will come.

        val manifest = current.sheets.manifestFor(member.role, current.revision)
        val inFlight = outgoing.values.filter { it.endpoint == from }.map { it.fileId }.toSet()
        updateMember(from) { old ->
            old.copy(
                delivery = manifest.files.associate { file ->
                    file.id to when (file.id) {
                        in inFlight -> old.delivery[file.id] ?: DeliveryState.Pending
                        in need.fileIds -> DeliveryState.Pending
                        else -> DeliveryState.Delivered
                    }
                },
            )
        }
        need.fileIds.filter { it !in inFlight && it in current.sheets.files }.forEach { sendFile(from, it) }
    }

    private fun sendFile(to: EndpointId, fileId: FileId) {
        val payloadId = transport.sendFile(to, fileId)
        if (payloadId == null) {
            setDelivery(to, fileId, DeliveryState.Failed)
            return
        }
        outgoing[payloadId] = OutgoingFile(to, fileId)
        setDelivery(to, fileId, DeliveryState.Sending(0f))
        send(to, WireMessage.FileHeader(payloadId, fileId))
    }

    private fun setDelivery(endpoint: EndpointId, fileId: FileId, state: DeliveryState) =
        updateMember(endpoint) { it.copy(delivery = it.delivery + (fileId to state)) }

    private fun updateMember(endpoint: EndpointId, change: (Member) -> Member) = _state.update { state ->
        val member = state.members[endpoint] ?: return@update state
        state.copy(members = state.members + (endpoint to change(member)))
    }

    private fun broadcast(message: WireMessage) {
        val bytes = WireCodec.encode(message)
        _state.value.members.keys.forEach { transport.send(it, bytes) }
    }

    private fun send(to: EndpointId, message: WireMessage) = transport.send(to, WireCodec.encode(message))
}
