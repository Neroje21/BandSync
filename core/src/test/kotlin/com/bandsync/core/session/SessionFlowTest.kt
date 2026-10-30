package com.bandsync.core.session

import com.bandsync.core.metronome.TransportState
import com.bandsync.core.protocol.SongChange
import com.bandsync.core.protocol.SongConfig
import com.bandsync.core.protocol.TimeSignature
import com.bandsync.core.sheets.FileId
import com.bandsync.core.sheets.Role
import com.bandsync.core.sheets.RoleSheetMap
import com.bandsync.core.sheets.SheetFile
import com.bandsync.core.sheets.SheetSegment
import com.bandsync.core.sheets.SheetStore
import com.bandsync.core.time.MonotonicClock
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

private const val MS = 1_000_000L

private class TestClock(var ns: Long) : MonotonicClock {
    override fun nowNs(): Long = ns
}

/** Delivers in order whenever pumped. Files can be held back to arrive after their header. */
private class FakeNetwork(
    private val masterStore: SheetStore,
    private val masterClock: TestClock,
    private val clientClock: TestClock,
) {
    private val queue = ArrayDeque<() -> Unit>()
    private val heldFiles = ArrayDeque<() -> Unit>()
    private val clients = HashMap<EndpointId, TransportListener>()
    private var nextPayloadId = 1L

    lateinit var master: TransportListener
    val filesSent = mutableListOf<Pair<EndpointId, FileId>>()
    var filesArriveLast = false
    var corruptFiles = false

    val masterTransport = object : Transport {
        override fun send(to: EndpointId, bytes: ByteArray) {
            queue.addLast { clients[to]?.onBytes("master", bytes, clientClock.ns) }
        }

        override fun sendFile(to: EndpointId, fileId: FileId): Long? {
            if (!masterStore.contains(fileId)) return null
            filesSent += to to fileId
            val payloadId = nextPayloadId++
            val bytes = if (corruptFiles) byteArrayOf(0) else masterStore.fileFor(fileId).readBytes()
            val deliver = {
                clients[to]?.onFileReceived(payloadId, ByteFile(bytes))
                master.onFileSent(payloadId)
            }
            if (filesArriveLast) heldFiles.addLast(deliver) else queue.addLast(deliver)
            return payloadId
        }
    }

    fun connect(endpoint: EndpointId, client: TransportListener) {
        clients[endpoint] = client
        master.onConnected(endpoint)
        client.onConnected("master")
    }

    fun disconnect(endpoint: EndpointId) {
        clients.remove(endpoint)?.onDisconnected("master")
        master.onDisconnected(endpoint)
    }

    fun clientTransport(endpoint: EndpointId) = object : Transport {
        override fun send(to: EndpointId, bytes: ByteArray) {
            queue.addLast { master.onBytes(endpoint, bytes, masterClock.ns) }
        }

        override fun sendFile(to: EndpointId, fileId: FileId): Long? = null
    }

    fun pump() {
        while (queue.isNotEmpty() || heldFiles.isNotEmpty()) {
            while (queue.isNotEmpty()) queue.removeFirst()()
            heldFiles.removeFirstOrNull()?.invoke()
        }
    }
}

private class ByteFile(private val bytes: ByteArray) : IncomingFile {
    override fun open(): InputStream = ByteArrayInputStream(bytes)
    override fun delete() = Unit
}

@OptIn(ExperimentalCoroutinesApi::class)
class SessionFlowTest {
    private val masterClock = TestClock(50_000 * MS)
    private val clientClock = TestClock(9_000_000 * MS)
    private val masterStore = SheetStore(Files.createTempDirectory("master").toFile())
    private val network = FakeNetwork(masterStore, masterClock, clientClock)
    private val session = MasterSession(network.masterTransport, masterClock, Role.GUITAR)
        .also { network.master = it }

    private fun sheet(name: String, pages: Int = 2): SheetFile {
        val bytes = "%PDF $name".encodeToByteArray()
        return SheetFile(masterStore.import(ByteArrayInputStream(bytes)), name, bytes.size.toLong(), pages)
    }

    private val guitarSheet = sheet("guitar.pdf")
    private val drumSheet = sheet("drums.pdf")
    private val sheets = RoleSheetMap()
        .withFile(guitarSheet)
        .withFile(drumSheet)
        .assign(Role.GUITAR, SheetSegment(guitarSheet.id))
        .assign(Role.DRUMS, SheetSegment(drumSheet.id, listOf(1)))

    private fun TestScope.client(
        endpoint: EndpointId,
        role: Role,
        store: SheetStore = SheetStore(Files.createTempDirectory(endpoint).toFile()),
    ): Pair<ClientSession, SheetStore> {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val client = ClientSession(
            transport = network.clientTransport(endpoint),
            store = store,
            clock = clientClock,
            name = endpoint,
            role = role,
            scope = CoroutineScope(backgroundScope.coroutineContext + dispatcher),
            ioDispatcher = dispatcher,
        )
        return client to store
    }

    private fun TestScope.settle() {
        repeat(5) {
            network.pump()
            runCurrent()
        }
    }

    @Test
    fun `each role receives only its own sheet`() = runTest {
        session.updateSheets(sheets)
        val (guitarist, guitarStore) = client("guitarist", Role.GUITAR)
        val (drummer, drumStore) = client("drummer", Role.DRUMS)

        network.connect("guitarist", guitarist)
        network.connect("drummer", drummer)
        settle()

        assertTrue(guitarStore.contains(guitarSheet.id))
        assertFalse(guitarStore.contains(drumSheet.id))
        assertTrue(drumStore.contains(drumSheet.id))
        assertFalse(drumStore.contains(guitarSheet.id))

        assertTrue(guitarist.state.value.sheetsReady)
        assertTrue(drummer.state.value.sheetsReady)
        assertEquals(1, drummer.state.value.manifest?.pages()?.size, "drummer was assigned page 2 only")

        val members = session.state.value.members
        assertEquals(setOf("guitarist", "drummer"), members.keys)
        assertEquals(DeliveryState.Delivered, members["drummer"]?.delivery?.get(drumSheet.id))
    }

    @Test
    fun `two phones in one role get the same sheet`() = runTest {
        session.updateSheets(sheets)
        val (first, _) = client("first", Role.GUITAR)
        val (second, _) = client("second", Role.GUITAR)

        network.connect("first", first)
        network.connect("second", second)
        settle()

        assertTrue(first.state.value.sheetsReady)
        assertTrue(second.state.value.sheetsReady)
        assertEquals(2, network.filesSent.size)
    }

    @Test
    fun `a phone that already has the file is not sent it again`() = runTest {
        session.updateSheets(sheets)
        val store = SheetStore(Files.createTempDirectory("cached").toFile())
        store.import(ByteArrayInputStream(masterStore.fileFor(guitarSheet.id).readBytes()))
        val (guitarist, _) = client("guitarist", Role.GUITAR, store)

        network.connect("guitarist", guitarist)
        settle()

        assertTrue(guitarist.state.value.sheetsReady)
        assertEquals(emptyList(), network.filesSent)
        assertEquals(DeliveryState.Delivered, session.state.value.members["guitarist"]?.delivery?.get(guitarSheet.id))
    }

    @Test
    fun `a file arriving after its header is matched up`() = runTest {
        session.updateSheets(sheets)
        network.filesArriveLast = true
        val (guitarist, store) = client("guitarist", Role.GUITAR)

        network.connect("guitarist", guitarist)
        settle()

        assertTrue(store.contains(guitarSheet.id))
    }

    @Test
    fun `a corrupt transfer is reported and can be redelivered`() = runTest {
        session.updateSheets(sheets)
        network.corruptFiles = true
        val (guitarist, store) = client("guitarist", Role.GUITAR)

        network.connect("guitarist", guitarist)
        settle()

        assertFalse(store.contains(guitarSheet.id))
        assertEquals(DeliveryState.Failed, session.state.value.members["guitarist"]?.delivery?.get(guitarSheet.id))

        network.corruptFiles = false
        session.redeliver("guitarist")
        settle()

        assertTrue(store.contains(guitarSheet.id))
        assertEquals(DeliveryState.Delivered, session.state.value.members["guitarist"]?.delivery?.get(guitarSheet.id))
    }

    @Test
    fun `changing the map reaches connected phones`() = runTest {
        val (guitarist, store) = client("guitarist", Role.GUITAR)
        network.connect("guitarist", guitarist)
        settle()
        assertFalse(guitarist.state.value.sheetsReady, "nothing assigned yet")

        session.updateSheets(sheets)
        settle()

        assertTrue(store.contains(guitarSheet.id))
    }

    @Test
    fun `a client locks onto the master clock`() = runTest {
        val (guitarist, _) = client("guitarist", Role.GUITAR)
        network.connect("guitarist", guitarist)
        settle()

        // Pings go out on the burst timer; the fake network delivers them with no delay at all.
        repeat(16) {
            advanceTimeBy(50)
            settle()
        }

        val sync = guitarist.clockSync
        assertTrue(sync.locked)
        val trueOffset = masterClock.ns - clientClock.ns
        assertEquals(trueOffset, sync.toMaster(0))
    }

    @Test
    fun `start is scheduled two seconds ahead on the master clock`() = runTest {
        session.updateSong(SongConfig(bpm = 100, timeSignature = TimeSignature(3, 4), countInBars = 2))
        val (guitarist, _) = client("guitarist", Role.GUITAR)
        network.connect("guitarist", guitarist)
        settle()

        session.start()
        settle()

        val timeline = assertIs<TransportState.Running>(guitarist.state.value.transport).timeline
        assertEquals(masterClock.ns + 2_000 * MS, timeline.startMasterNs)
        assertEquals(-6L, timeline.firstBeat)
        assertEquals(0L, timeline.playFromBeat)
        assertEquals(100, timeline.bpmAt(0))
        assertEquals(3, timeline.beatsPerBarAt(0))
    }

    @Test
    fun `the whole tempo map reaches every phone`() = runTest {
        val song = SongConfig(bpm = 90, changes = listOf(SongChange(5, 150, TimeSignature(3, 4))))
        session.updateSong(song)
        val (guitarist, _) = client("guitarist", Role.GUITAR)
        network.connect("guitarist", guitarist)
        settle()

        session.start()
        settle()

        assertEquals(song, guitarist.state.value.song)
        val timeline = assertIs<TransportState.Running>(guitarist.state.value.transport).timeline
        assertEquals(song.toMap(), timeline.map)
        assertEquals(session.state.value.transport, guitarist.state.value.transport)
    }

    @Test
    fun `resuming in a later section counts in at that section's tempo and meter`() = runTest {
        session.updateSong(
            SongConfig(bpm = 120, changes = listOf(SongChange(3, 180, TimeSignature(3, 4)))),
        )

        session.start() // One bar of count-in at 120, then bars 1 and 2 at 120: bar 3 is 8 s after pressing start.
        masterClock.ns += 8_000 * MS + 1_000 * MS // Into bar 4, once bar 3's three beats at 180 have gone by.
        session.pause()
        masterClock.ns += 1_000 * MS
        session.start()

        val resumed = assertIs<TransportState.Running>(session.state.value.transport).timeline
        assertEquals(11L, resumed.playFromBeat, "bar 4 follows two bars of 4/4 and one of 3/4")
        assertEquals(8L, resumed.firstBeat, "one bar of 3/4 count-in")
        assertEquals(180, resumed.bpmAt(resumed.firstBeat))
    }

    @Test
    fun `resume restarts from the beginning of the paused bar`() = runTest {
        val (guitarist, _) = client("guitarist", Role.GUITAR)
        network.connect("guitarist", guitarist)
        settle()

        session.start() // 120 BPM 4/4 with one bar of count-in, so bar 1 is 4 s after pressing start.
        masterClock.ns += 4_000 * MS + 2_600 * MS // Bar 2, beat 2, once the pause lead is added.
        session.pause()
        settle()

        val paused = assertIs<TransportState.Paused>(guitarist.state.value.transport)
        assertEquals(5L, paused.beat)

        masterClock.ns += 1_000 * MS
        session.start()
        settle()

        val resumed = assertIs<TransportState.Running>(guitarist.state.value.transport).timeline
        assertEquals(4L, resumed.playFromBeat)
        assertEquals(0L, resumed.firstBeat)
    }

    @Test
    fun `resume keeps the bar when the time signature changed while paused`() = runTest {
        session.start()
        masterClock.ns += 4_000 * MS + 4_100 * MS // Into bar 3 of 4/4.
        session.pause()
        masterClock.ns += 1_000 * MS

        session.updateSong(session.state.value.song.copy(timeSignature = TimeSignature(3, 4)))
        session.start()

        val resumed = assertIs<TransportState.Running>(session.state.value.transport).timeline
        assertEquals(6L, resumed.playFromBeat, "bar 3 in 3/4 starts at beat 6")
    }

    @Test
    fun `stop reaches clients and the next start begins at the top`() = runTest {
        val (guitarist, _) = client("guitarist", Role.GUITAR)
        network.connect("guitarist", guitarist)
        settle()

        session.start()
        masterClock.ns += 10_000 * MS
        session.stop()
        settle()
        assertEquals(TransportState.Stopped, guitarist.state.value.transport)

        session.start()
        settle()
        assertEquals(0L, assertIs<TransportState.Running>(guitarist.state.value.transport).timeline.playFromBeat)
    }

    @Test
    fun `a late joiner receives the running timeline`() = runTest {
        session.start()
        val expected = session.state.value.transport
        masterClock.ns += 30_000 * MS

        val (latecomer, _) = client("latecomer", Role.GUITAR)
        network.connect("latecomer", latecomer)
        settle()

        assertEquals(expected, latecomer.state.value.transport)
    }

    @Test
    fun `a dropped client keeps its timeline and is forgotten by the master`() = runTest {
        val (guitarist, _) = client("guitarist", Role.GUITAR)
        network.connect("guitarist", guitarist)
        settle()
        session.start()
        settle()

        network.disconnect("guitarist")
        settle()

        assertEquals(Link.Lost, guitarist.state.value.link)
        assertIs<TransportState.Running>(guitarist.state.value.transport)
        assertTrue(session.state.value.members.isEmpty())
    }

    @Test
    fun `a rejoining phone learns the song stopped while it was away`() = runTest {
        val (guitarist, _) = client("guitarist", Role.GUITAR)
        network.connect("guitarist", guitarist)
        settle()
        session.start()
        settle()

        network.disconnect("guitarist")
        session.stop()
        network.connect("guitarist", guitarist)
        settle()

        assertEquals(Link.Connected, guitarist.state.value.link)
        assertEquals(TransportState.Stopped, guitarist.state.value.transport)
    }

    @Test
    fun `a client reports its sync quality to the master`() = runTest {
        val (guitarist, _) = client("guitarist", Role.GUITAR)
        network.connect("guitarist", guitarist)
        settle()

        repeat(16) {
            advanceTimeBy(50)
            settle()
        }

        val reported = assertNotNull(session.state.value.members["guitarist"]?.uncertaintyNs)
        assertTrue(abs(reported) < MS)
    }

    @Test
    fun `an unreadable file is marked failed instead of stalling`() = runTest {
        val missing = SheetFile("f".repeat(64), "gone.pdf", 1, 1)
        session.updateSheets(RoleSheetMap().withFile(missing).assign(Role.BASS, SheetSegment(missing.id)))
        val (bassist, _) = client("bassist", Role.BASS)

        network.connect("bassist", bassist)
        settle()

        assertEquals(DeliveryState.Failed, session.state.value.members["bassist"]?.delivery?.get(missing.id))
    }
}
