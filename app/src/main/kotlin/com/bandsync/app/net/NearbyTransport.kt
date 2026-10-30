package com.bandsync.app.net

import android.content.Context
import android.os.Build
import com.bandsync.core.session.EndpointId
import com.bandsync.core.session.IncomingFile
import com.bandsync.core.session.Transport
import com.bandsync.core.session.TransportListener
import com.bandsync.core.sheets.FileId
import com.bandsync.core.sheets.SheetStore
import com.bandsync.core.time.MonotonicClock
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private const val SERVICE_ID = "com.bandsync.app.session"

/** One master, many followers: the highest-bandwidth topology that fits a band. */
private val STRATEGY = Strategy.P2P_STAR

/** Requests that find the radio already in the wanted state, which are not failures. */
private val HARMLESS_CODES = setOf(
    ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING,
    ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING,
    ConnectionsStatusCodes.STATUS_ALREADY_CONNECTED_TO_ENDPOINT,
)

data class DiscoveredHost(val endpoint: EndpointId, val name: String)

/** [code] is shown on both phones. Comparing them proves the master is talking to the right person. */
data class JoinRequest(val endpoint: EndpointId, val name: String, val code: String)

/** Whether this phone is advertising or scanning. Kept on screen, because a brief message is easily missed. */
sealed interface RadioState {
    data object Idle : RadioState
    data object Starting : RadioState
    data object Active : RadioState
    data class Failed(val reason: String) : RadioState
}

/** Nearby Connections behind the [Transport] interface. All of its callbacks run on the main thread. */
class NearbyTransport(
    private val context: Context,
    private val store: SheetStore,
    private val clock: MonotonicClock,
) : Transport {
    private val connections = Nearby.getConnectionsClient(context)

    var listener: TransportListener? = null

    private val _hosts = MutableStateFlow<List<DiscoveredHost>>(emptyList())
    val hosts: StateFlow<List<DiscoveredHost>> = _hosts.asStateFlow()

    private val _joinRequests = MutableStateFlow<List<JoinRequest>>(emptyList())
    val joinRequests: StateFlow<List<JoinRequest>> = _joinRequests.asStateFlow()

    private val _pairingCode = MutableStateFlow<String?>(null)
    val pairingCode: StateFlow<String?> = _pairingCode.asStateFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    private val _radio = MutableStateFlow<RadioState>(RadioState.Idle)
    val radio: StateFlow<RadioState> = _radio.asStateFlow()

    private val incomingFiles = HashMap<Long, Payload>()
    private val outgoingFiles = HashSet<Long>()
    private var advertising = false
    private var discovering = false

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            // Read first: every moment before this line is network delay the clock sync can't see.
            val receivedAtNs = clock.nowNs()
            when (payload.type) {
                Payload.Type.BYTES -> payload.asBytes()?.let { listener?.onBytes(endpointId, it, receivedAtNs) }
                Payload.Type.FILE -> incomingFiles[payload.id] = payload
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            val id = update.payloadId
            if (id !in incomingFiles && id !in outgoingFiles) return
            when (update.status) {
                PayloadTransferUpdate.Status.IN_PROGRESS ->
                    listener?.onFileProgress(id, update.bytesTransferred, update.totalBytes)
                PayloadTransferUpdate.Status.SUCCESS -> {
                    if (outgoingFiles.remove(id)) listener?.onFileSent(id)
                    incomingFiles.remove(id)?.asFile()?.let { listener?.onFileReceived(id, ReceivedFile(it)) }
                }
                PayloadTransferUpdate.Status.FAILURE, PayloadTransferUpdate.Status.CANCELED -> {
                    outgoingFiles.remove(id)
                    incomingFiles.remove(id)
                    listener?.onFileFailed(id)
                }
            }
        }
    }

    private val lifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            if (info.isIncomingConnection) {
                val request = JoinRequest(endpointId, info.endpointName, info.authenticationDigits)
                _joinRequests.update { it + request }
            } else {
                _pairingCode.value = info.authenticationDigits
                connections.acceptConnection(endpointId, payloadCallback).addOnFailureListener(::report)
            }
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            _joinRequests.update { requests -> requests.filterNot { it.endpoint == endpointId } }
            _pairingCode.value = null
            when (resolution.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    stopDiscovery() // Scanning competes with the link for the radio.
                    listener?.onConnected(endpointId)
                }
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> _errors.tryEmit("The master declined the request")
                else -> _errors.tryEmit("Couldn't connect. Try again.")
            }
        }

        override fun onDisconnected(endpointId: String) {
            listener?.onDisconnected(endpointId)
        }
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (info.serviceId != SERVICE_ID) return
            _hosts.update { hosts ->
                hosts.filterNot { it.endpoint == endpointId } + DiscoveredHost(endpointId, info.endpointName)
            }
        }

        override fun onEndpointLost(endpointId: String) {
            _hosts.update { hosts -> hosts.filterNot { it.endpoint == endpointId } }
        }
    }

    fun startAdvertising(name: String) {
        if (advertising) return
        advertising = true
        _radio.value = RadioState.Starting
        val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        connections.startAdvertising(name, SERVICE_ID, lifecycleCallback, options)
            .addOnSuccessListener { _radio.value = RadioState.Active }
            .addOnFailureListener { failure ->
                advertising = isHarmless(failure)
                setRadioFailed(failure)
            }
    }

    fun stopAdvertising() {
        advertising = false
        _radio.value = RadioState.Idle
        connections.stopAdvertising()
    }

    /** Clears any state left over in Play services, which can quietly stop a phone being found. */
    fun restartAdvertising(name: String) {
        stopAdvertising()
        startAdvertising(name)
    }

    fun startDiscovery() {
        discovering = true
        _radio.value = RadioState.Starting
        val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        connections.startDiscovery(SERVICE_ID, discoveryCallback, options)
            .addOnSuccessListener { _radio.value = RadioState.Active }
            .addOnFailureListener { failure ->
                discovering = isHarmless(failure)
                setRadioFailed(failure)
            }
    }

    private fun stopDiscovery() {
        if (!discovering) return
        discovering = false
        _radio.value = RadioState.Idle
        _hosts.value = emptyList()
        connections.stopDiscovery()
    }

    fun restartDiscovery() {
        stopDiscovery()
        startDiscovery()
    }

    fun requestConnection(name: String, endpoint: EndpointId) {
        connections.requestConnection(name, endpoint, lifecycleCallback).addOnFailureListener(::report)
    }

    fun accept(request: JoinRequest) {
        connections.acceptConnection(request.endpoint, payloadCallback).addOnFailureListener(::report)
    }

    fun decline(request: JoinRequest) {
        _joinRequests.update { it - request }
        connections.rejectConnection(request.endpoint)
    }

    fun shutdown() {
        listener = null
        connections.stopAdvertising()
        connections.stopDiscovery()
        connections.stopAllEndpoints()
    }

    override fun send(to: EndpointId, bytes: ByteArray) {
        // A failed send means the endpoint has gone, which onDisconnected reports.
        connections.sendPayload(to, Payload.fromBytes(bytes))
    }

    override fun sendFile(to: EndpointId, fileId: FileId): Long? {
        val payload = try {
            Payload.fromFile(store.fileFor(fileId))
        } catch (_: FileNotFoundException) {
            return null
        }
        outgoingFiles += payload.id
        connections.sendPayload(to, payload).addOnFailureListener {
            if (outgoingFiles.remove(payload.id)) listener?.onFileFailed(payload.id)
        }
        return payload.id
    }

    private fun report(failure: Exception) {
        if (!isHarmless(failure)) _errors.tryEmit("Nearby Connections: ${describe(failure)}")
    }

    private fun setRadioFailed(failure: Exception) {
        _radio.value = if (isHarmless(failure)) RadioState.Active else RadioState.Failed(describe(failure))
    }

    private fun isHarmless(failure: Exception): Boolean = (failure as? ApiException)?.statusCode in HARMLESS_CODES

    /** Gives the number as well as the name, because the number is what can be looked up. */
    private fun describe(failure: Exception): String {
        val code = (failure as? ApiException)?.statusCode ?: return failure.message.orEmpty()
        return "${ConnectionsStatusCodes.getStatusCodeString(code)} ($code)"
    }

    /** Android 10 moved received files behind a content URI. */
    private inner class ReceivedFile(private val file: Payload.File) : IncomingFile {
        override fun open(): InputStream = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            file.asUri()?.let(context.contentResolver::openInputStream)
        } else {
            @Suppress("DEPRECATION")
            file.asJavaFile()?.let(::FileInputStream)
        } ?: throw IOException("Received file is unavailable")

        override fun delete() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                file.asUri()?.let { context.contentResolver.delete(it, null, null) }
            } else {
                @Suppress("DEPRECATION")
                file.asJavaFile()?.delete()
            }
        }
    }
}
