package com.bandsync.app.session

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bandsync.app.BandSyncApp
import com.bandsync.app.net.JoinRequest
import com.bandsync.app.net.NearbyTransport
import com.bandsync.app.pdf.LibrarySong
import com.bandsync.app.pdf.PdfImporter
import com.bandsync.app.pdf.SheetLayouts
import com.bandsync.app.pdf.SongLibrary
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
    val library: List<LibrarySong> = SongLibrary.songs(application)
    val state = session.state
    val joinRequests = nearby.joinRequests
    val radio = nearby.radio
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

    fun retryHosting() = nearby.restartAdvertising(name)
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

    /**
     * Replaces whatever is loaded, because one part holding two songs' sheets is never what anyone wants. Each
     * instrument's tempo is preset from its tab. Only the count-in carries over, since that's the band's choice.
     */
    fun loadSong(song: LibrarySong) {
        viewModelScope.launch {
            val sheets = try {
                withContext(Dispatchers.IO) {
                    song.sheets.fold(RoleSheetMap()) { map, sheet ->
                        val file = importer.importAsset(sheet.assetPath, sheet.displayName)
                        map.withFile(file).assign(sheet.part, SheetSegment(file.id))
                    }
                }
            } catch (_: IOException) {
                notices.tryEmit("Couldn't load ${song.title}")
                return@launch
            }
            session.updateSheets(sheets)
            val printed = withContext(Dispatchers.IO) { markedSheetsByPart(sheets) }
            val countInBars = session.state.value.song.countInBars
            session.updateSong(SongConfig(title = song.title, countInBars = countInBars).withPrintedTempos(printed))
            notices.tryEmit("${song.title} loaded")
        }
    }

    /** Presets each instrument's tempo changes from all of its sheets, replacing any changes already there. */
    fun readTempoFromTabs() {
        viewModelScope.launch {
            val current = session.state.value
            val printed = withContext(Dispatchers.IO) { markedSheetsByPart(current.sheets) }
            if (printed.isEmpty()) {
                notices.tryEmit("No tempo marks found on these sheets")
                return@launch
            }
            session.updateSong(current.song.withPrintedTempos(printed))
            notices.tryEmit(tempoReport(printed.keys, current.sheets))
        }
    }

    /** Says what wasn't read too, so a sheet left out doesn't look like a sheet with no tempo changes. */
    private fun tempoReport(read: Set<Role>, sheets: RoleSheetMap): String = buildString {
        append("Tempo set for ").append(read.joinToString(", ") { it.label })
        val withoutMarks = sheets.assignments.filterValues { it.isNotEmpty() }.keys - read
        if (withoutMarks.isNotEmpty()) append(". No marks for ").append(withoutMarks.joinToString(", ") { it.label })
        val unassigned = (sheets.files.keys - sheets.assignments.values.flatten().map { it.fileId }.toSet()).size
        if (unassigned > 0) append(". Skipped $unassigned ${if (unassigned == 1) "sheet" else "sheets"} with no part")
    }

    /** Every part's sheets in order, leaving out those with no marks. Reads files, so call it off the main thread. */
    private fun markedSheetsByPart(sheets: RoleSheetMap): Map<Role, List<BarLayout>> {
        val layouts = app.sheetLayouts.layoutsFor(sheets.files.keys.toList())
        return Role.entries.associateWith { part ->
            sheets.segmentsFor(part)
                .map { it.fileId }
                .distinct()
                .mapNotNull(layouts::get)
                .filter { it.tempos.isNotEmpty() || it.meters.isNotEmpty() }
        }.filterValues { it.isNotEmpty() }
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
