package com.bandsync.app.session

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bandsync.app.BandSyncApp
import com.bandsync.app.net.DiscoveredHost
import com.bandsync.app.net.NearbyTransport
import com.bandsync.app.pdf.SheetLayouts
import com.bandsync.core.metronome.MetronomeEngine
import com.bandsync.core.metronome.TransportState
import com.bandsync.core.session.ClientSession
import com.bandsync.core.session.Link
import com.bandsync.core.sheets.Role
import com.bandsync.core.sheets.SheetStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class ClientViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as BandSyncApp
    private val clock = app.clock
    private val name = app.profile.name

    private val nearby = NearbyTransport(application, app.store, clock)
    private val session = ClientSession(
        transport = nearby,
        store = app.store,
        clock = clock,
        name = name,
        role = app.profile.role,
        scope = viewModelScope,
        ioDispatcher = Dispatchers.IO,
    )

    private val tickerDispatcher = newTickerDispatcher()
    private val tickerScope = CoroutineScope(tickerDispatcher)
    private val engine = MetronomeEngine(clock, session.clockSync, tickerScope)

    val role: Role = app.profile.role
    val store: SheetStore = app.store
    val sheetLayouts: SheetLayouts = app.sheetLayouts
    val state = session.state
    val hosts = nearby.hosts
    val pairingCode = nearby.pairingCode
    val ticks = engine.ticks
    val messages = nearby.errors

    init {
        nearby.listener = session
        nearby.startDiscovery()
        viewModelScope.launch {
            // Until the first lock the offset is meaningless, and beats computed from it would be nonsense.
            session.state
                .map { if (it.sync != null) it.transport else TransportState.Stopped }
                .distinctUntilChanged()
                .collect(engine::apply)
        }
        viewModelScope.launch {
            session.state.map { it.link }.distinctUntilChanged().collect { link ->
                if (link == Link.Lost) nearby.startDiscovery()
            }
        }
    }

    fun localNow(): Long = clock.nowNs()
    fun masterNow(): Long = session.clockSync.toMaster(clock.nowNs())

    fun connect(host: DiscoveredHost) = nearby.requestConnection(name, host.endpoint)

    override fun onCleared() {
        nearby.shutdown()
        tickerScope.cancel()
        tickerDispatcher.close()
    }
}
