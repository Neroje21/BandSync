package com.bandsync.core.session

import com.bandsync.core.metronome.TransportState
import com.bandsync.core.protocol.SongConfig
import com.bandsync.core.protocol.WireCodec
import com.bandsync.core.protocol.WireMessage
import com.bandsync.core.sheets.FileId
import com.bandsync.core.sheets.Role
import com.bandsync.core.sheets.SheetManifest
import com.bandsync.core.sheets.SheetStore
import com.bandsync.core.time.ClockEstimate
import com.bandsync.core.time.ClockSync
import com.bandsync.core.time.MonotonicClock
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val BURST_PINGS = 16
private const val BURST_INTERVAL_MS = 50L

/** Keeps pinging through the whole session: quartz clocks can diverge by several ms over one song. */
private const val STEADY_INTERVAL_MS = 500L
private const val PONGS_PER_REPORT = 4

enum class Link { Connecting, Connected, Lost }

data class ClientState(
    val link: Link = Link.Connecting,
    val song: SongConfig? = null,
    val manifest: SheetManifest? = null,
    val delivery: Map<FileId, DeliveryState> = emptyMap(),
    val transport: TransportState = TransportState.Stopped,
    val sync: ClockEstimate? = null,
) {
    val sheetsReady: Boolean
        get() = manifest != null && manifest.files.isNotEmpty() &&
            manifest.files.all { delivery[it.id] == DeliveryState.Delivered }
}

class ClientSession(
    private val transport: Transport,
    private val store: SheetStore,
    clock: MonotonicClock,
    private val name: String,
    private val role: Role,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) : TransportListener {
    val clockSync = ClockSync(clock)

    private val _state = MutableStateFlow(ClientState())
    val state: StateFlow<ClientState> = _state.asStateFlow()

    @Volatile // Also read from the I/O thread when a received file has been stored.
    private var master: EndpointId? = null
    private var pingJob: Job? = null
    private var pongsSinceReport = 0

    // FILE and its header can arrive in either order, so each waits here for the other.
    private val headers = HashMap<Long, FileId>()
    private val completedFiles = HashMap<Long, IncomingFile>()

    override fun onConnected(endpoint: EndpointId) {
        master = endpoint
        clockSync.reset()
        headers.clear()
        completedFiles.values.forEach(IncomingFile::delete)
        completedFiles.clear()
        // The timeline and the last offset carry the phone through until it has locked on again.
        _state.update { ClientState(link = Link.Connected, transport = it.transport, sync = it.sync) }
        send(WireMessage.Hello(name, role))
        pingJob?.cancel()
        pingJob = scope.launch {
            repeat(BURST_PINGS) {
                ping()
                delay(BURST_INTERVAL_MS)
            }
            while (true) {
                ping()
                delay(STEADY_INTERVAL_MS)
            }
        }
    }

    override fun onDisconnected(endpoint: EndpointId) {
        if (endpoint != master) return
        master = null
        pingJob?.cancel()
        _state.update { it.copy(link = Link.Lost) }
    }

    override fun onBytes(from: EndpointId, bytes: ByteArray, receivedAtNs: Long) {
        when (val message = WireCodec.decode(bytes)) {
            is WireMessage.Pong -> onPong(message, receivedAtNs)
            is WireMessage.SongInfo -> _state.update { it.copy(song = message.song) }
            is WireMessage.Sheets -> onManifest(message.manifest)
            is WireMessage.FileHeader -> {
                headers[message.payloadId] = message.fileId
                setDelivery(message.fileId, DeliveryState.Sending(0f))
                adoptWhenReady(message.payloadId)
            }
            is WireMessage.StartAt -> setTransport(TransportState.Running(message.timeline))
            is WireMessage.PauseAt -> setTransport(TransportState.Paused(message.timeline, message.atMasterNs))
            WireMessage.Stop -> setTransport(TransportState.Stopped)
            else -> Unit
        }
    }

    override fun onFileProgress(payloadId: Long, transferredBytes: Long, totalBytes: Long) {
        val fileId = headers[payloadId] ?: return
        val fraction = if (totalBytes > 0) transferredBytes.toFloat() / totalBytes else 0f
        setDelivery(fileId, DeliveryState.Sending(fraction))
    }

    override fun onFileReceived(payloadId: Long, file: IncomingFile) {
        completedFiles[payloadId] = file
        adoptWhenReady(payloadId)
    }

    override fun onFileFailed(payloadId: Long) {
        val fileId = headers.remove(payloadId) ?: return
        setDelivery(fileId, DeliveryState.Failed)
    }

    override fun onFileSent(payloadId: Long) = Unit // Clients only receive.

    private fun onPong(pong: WireMessage.Pong, receivedAtNs: Long) {
        val estimate = clockSync.onPong(pong.t0, pong.t1, pong.t2, receivedAtNs) ?: return
        _state.update { it.copy(sync = estimate) }
        if (++pongsSinceReport >= PONGS_PER_REPORT) {
            pongsSinceReport = 0
            send(WireMessage.SyncReport(estimate.uncertaintyNs))
        }
    }

    private fun onManifest(manifest: SheetManifest) {
        val fileIds = manifest.files.map { it.id }
        val missing = fileIds.filterNot(store::contains)
        _state.update { state ->
            state.copy(
                manifest = manifest,
                delivery = fileIds.associateWith { id ->
                    if (id in missing) state.delivery[id].takeUnless { it == DeliveryState.Delivered } ?: DeliveryState.Pending
                    else DeliveryState.Delivered
                },
            )
        }
        send(WireMessage.NeedFiles(manifest.revision, missing))
    }

    private fun adoptWhenReady(payloadId: Long) {
        val fileId = headers[payloadId] ?: return
        val file = completedFiles.remove(payloadId) ?: return
        headers.remove(payloadId)
        setDelivery(fileId, DeliveryState.Verifying)
        scope.launch(ioDispatcher) {
            val ok = try {
                file.open().use { store.adopt(it, fileId) }
            } catch (_: IOException) {
                false
            } finally {
                file.delete()
            }
            setDelivery(fileId, if (ok) DeliveryState.Delivered else DeliveryState.Failed)
            send(WireMessage.FileReceived(fileId, ok))
        }
    }

    private fun ping() = send(WireMessage.Ping(clockSync.stampPing()))

    private fun setDelivery(fileId: FileId, state: DeliveryState) =
        _state.update { it.copy(delivery = it.delivery + (fileId to state)) }

    private fun setTransport(transport: TransportState) = _state.update { it.copy(transport = transport) }

    private fun send(message: WireMessage) {
        val endpoint = master ?: return
        transport.send(endpoint, WireCodec.encode(message))
    }
}
