package com.bandsync.core.session

import com.bandsync.core.sheets.FileId
import java.io.InputStream

typealias EndpointId = String

/** The sessions' only view of the network. Nearby Connections sits behind this in the app. */
interface Transport {
    fun send(to: EndpointId, bytes: ByteArray)

    /** Starts a FILE transfer and returns its payload id, or null if the file can't be read. */
    fun sendFile(to: EndpointId, fileId: FileId): Long?
}

/** A completed incoming FILE payload, still in the transport's temporary storage. */
interface IncomingFile {
    fun open(): InputStream
    fun delete()
}

/** Every callback arrives on one thread, which is what lets the sessions keep plain fields. */
interface TransportListener {
    fun onConnected(endpoint: EndpointId)
    fun onDisconnected(endpoint: EndpointId)

    /** [receivedAtNs] is read before anything else happens to the packet. Clock sync depends on it. */
    fun onBytes(from: EndpointId, bytes: ByteArray, receivedAtNs: Long)

    fun onFileProgress(payloadId: Long, transferredBytes: Long, totalBytes: Long)
    fun onFileSent(payloadId: Long)
    fun onFileFailed(payloadId: Long)
    fun onFileReceived(payloadId: Long, file: IncomingFile)
}
