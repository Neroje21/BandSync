package com.bandsync.app.session

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bandsync.app.BandSyncApp
import com.bandsync.app.net.JoinRequest
import com.bandsync.app.net.NearbyTransport
import com.bandsync.app.pdf.PdfImporter
import com.bandsync.app.pdf.SheetLayouts
import com.bandsync.app.pdf.TestTabs
import com.bandsync.core.metronome.MetronomeEngine
import com.bandsync.core.metronome.TransportState
import com.bandsync.core.protocol.SongConfig
import com.bandsync.core.protocol.withPrintedTempos
import com.bandsync.core.session.EndpointId
import com.bandsync.core.session.MasterSession
import com.bandsync.core.sheets.BarLayout
import com.bandsync.core.sheets.FileId
import com.bandsync.core.sheets.Role
import com.bandsync.core.sheets.RoleSheetMap
import com.bandsync.core.sheets.SheetSegment
import com.bandsync.core.sheets.SheetStore
import com.bandsync.core.time.TimeBase
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MasterViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as BandSyncApp
    private val clock = app.clock
    private val name = app.profile.name

    private val nearby = NearbyTransport(application, app.store, clock)
    private val session = MasterSession(nearby, clock, app.profile.role)
    private val importer = PdfImporter(application, app.store)

    private val tickerDispatcher = newTickerDispatcher()
    private val tickerScope = CoroutineScope(tickerDispatcher)
    private val engine = MetronomeEngine(clock, TimeBase.Master, tickerScope)

    private val notices = MutableSharedFlow<String>(extraBufferCapacity = 8)

    val store: SheetStore = app.store
    val sheetLayouts: SheetLayouts = app.sheetLayouts
    val hasTestTabs: Boolean = TestTabs.isAvailable(application)
    val state = session.state
    val joinRequests = nearby.joinRequests
    val ticks = engine.ticks
    val messages = merge(nearby.errors, notices)

    init {
        nearby.listener = session
        nearby.startAdvertising(name)
        viewModelScope.launch {
            session.state.map { it.transport }.distinctUntilChanged().collect { transport ->
                engine.apply(transport)
                // Advertising competes with the links for the radio, so it stops while the band plays.
                if (transport is TransportState.Running) nearby.stopAdvertising() else nearby.startAdvertising(name)
            }
        }
    }

    fun localNow(): Long = clock.nowNs()

    fun accept(request: JoinRequest) = nearby.accept(request)
    fun decline(request: JoinRequest) = nearby.decline(request)
    fun redeliver(endpoint: EndpointId) = session.redeliver(endpoint)

    fun updateSong(song: SongConfig) = session.updateSong(song)

    fun addPdfs(uris: List<Uri>) {
        viewModelScope.launch {
            for (uri in uris) {
                try {
                    val file = withContext(Dispatchers.IO) { importer.import(uri) }
                    editSheets { it.withFile(file) }
                } catch (_: IOException) {
                    notices.tryEmit("Couldn't read that PDF")
                } catch (_: SecurityException) {
                    notices.tryEmit("That PDF is password protected")
                }
            }
        }
    }

    /** Safe to tap twice: files are stored by content, and a part that already has its tab keeps it once. */
    fun loadTestTabs() {
        viewModelScope.launch {
            val tabs = try {
                withContext(Dispatchers.IO) {
                    TestTabs.all.map { it.role to importer.importAsset(it.assetPath, it.displayName) }
                }
            } catch (_: IOException) {
                notices.tryEmit("Couldn't load the test tabs")
                return@launch
            }
            editSheets { sheets ->
                tabs.fold(sheets) { map, (role, file) ->
                    map.withFile(file).assignOnce(role, SheetSegment(file.id))
                }
            }
            val sheets = session.state.value.sheets
            val printed = withContext(Dispatchers.IO) { tabsByPart(sheets) }
            session.updateSong(TestTabs.song.withPrintedTempos(printed))
        }
    }

    /** Sets the song's tempo changes from what is printed on each part's tab, replacing any already there. */
    fun readTempoFromTabs() {
        viewModelScope.launch {
            val current = session.state.value
            val printed = withContext(Dispatchers.IO) { tabsByPart(current.sheets) }
            if (printed.isEmpty()) {
                notices.tryEmit("No tempo marks found on these tabs")
                return@launch
            }
            session.updateSong(current.song.withPrintedTempos(printed))
            notices.tryEmit("Tempo read from ${printed.size} ${if (printed.size == 1) "tab" else "tabs"}")
        }
    }

    /** Each part's first sheet that has marks. Reads files, so call it off the main thread. */
    private fun tabsByPart(sheets: RoleSheetMap): Map<Role, BarLayout> {
        val layouts = app.sheetLayouts.layoutsFor(sheets.files.keys.toList())
        return Role.entries.mapNotNull { part ->
            sheets.segmentsFor(part)
                .mapNotNull { layouts[it.fileId] }
                .firstOrNull { it.tempos.isNotEmpty() || it.meters.isNotEmpty() }
                ?.let { part to it }
        }.toMap()
    }

    fun removePdf(id: FileId) = editSheets { it.withoutFile(id) }
    fun assign(role: Role, id: FileId) = editSheets { it.assign(role, SheetSegment(id)) }
    fun unassign(role: Role, index: Int) = editSheets { it.unassign(role, index) }
    fun setPages(role: Role, index: Int, pages: List<Int>) = editSheets { it.setPages(role, index, pages) }

    fun start() = session.start()
    fun pause() = session.pause()
    fun stop() = session.stop()

    private fun editSheets(change: (RoleSheetMap) -> RoleSheetMap) {
        session.updateSheets(change(session.state.value.sheets))
    }

    override fun onCleared() {
        nearby.shutdown()
        tickerScope.cancel()
        tickerDispatcher.close()
    }
}
