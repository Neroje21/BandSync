package com.bandsync.app.ui.client

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bandsync.app.net.DiscoveredHost
import com.bandsync.app.session.ClientViewModel
import com.bandsync.app.ui.common.KeepScreenOn
import com.bandsync.app.ui.common.LeaveGuard
import com.bandsync.app.ui.common.ShowMessages
import com.bandsync.app.ui.common.formatUncertainty
import com.bandsync.app.ui.live.CountdownOverlay
import com.bandsync.app.ui.live.LiveReadout
import com.bandsync.app.ui.live.rememberPlayhead
import com.bandsync.app.ui.sheet.SheetViewer
import com.bandsync.core.metronome.TransportState
import com.bandsync.core.session.ClientState
import com.bandsync.core.session.DeliveryState
import com.bandsync.core.session.Link
import com.bandsync.core.sheets.Role

@Composable
fun ClientScreen(onLeave: () -> Unit, viewModel: ClientViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val hosts by viewModel.hosts.collectAsStateWithLifecycle()
    val pairingCode by viewModel.pairingCode.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    KeepScreenOn()
    ShowMessages(viewModel.messages, snackbar)
    LeaveGuard("Leave the session?", "You'll stop following the master.", onLeave)

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val followedSomething = state.transport != TransportState.Stopped || state.sheetsReady
            if (state.link == Link.Connected || followedSomething) {
                InSession(state, viewModel, hosts, pairingCode)
            } else {
                FindMaster(viewModel.role, hosts, pairingCode, onSelect = viewModel::connect)
            }
        }
    }
}

@Composable
private fun FindMaster(
    role: Role,
    hosts: List<DiscoveredHost>,
    pairingCode: String?,
    onSelect: (DiscoveredHost) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Joining as ${role.label}", style = MaterialTheme.typography.headlineMedium)
        if (pairingCode != null) {
            PairingCode(pairingCode)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator()
                Text("Looking for a master nearby…")
            }
            HostList(hosts, onSelect)
        }
    }
}

@Composable
private fun PairingCode(code: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text("Check this code matches the master's screen")
        Text(code, fontSize = 64.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun HostList(hosts: List<DiscoveredHost>, onSelect: (DiscoveredHost) -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(hosts, key = { it.endpoint }) { host ->
            Card(Modifier.fillMaxWidth().clickable { onSelect(host) }) {
                Text(host.name, Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

@Composable
private fun InSession(
    state: ClientState,
    viewModel: ClientViewModel,
    hosts: List<DiscoveredHost>,
    pairingCode: String?,
) {
    // Before the first lock there is no master time to place the playhead on.
    val followed = if (state.sync != null) state.transport else TransportState.Stopped
    val playhead by rememberPlayhead(followed, viewModel::masterNow)
    val manifest = state.manifest
    val pages = remember(manifest) { manifest?.pages().orEmpty() }

    Column(Modifier.fillMaxSize()) {
        if (state.link != Link.Connected) {
            ConnectionLost(hosts, pairingCode, onSelect = viewModel::connect)
        }
        LiveReadout(playhead, viewModel.ticks, viewModel::localNow)
        SessionHeader(viewModel.role, state)

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.sheetsReady) {
                SheetViewer(pages, viewModel.store, Modifier.fillMaxSize())
            } else {
                WaitingForSheet(viewModel.role, state)
            }
            CountdownOverlay(playhead)
        }
    }
}

@Composable
private fun ConnectionLost(hosts: List<DiscoveredHost>, pairingCode: String?, onSelect: (DiscoveredHost) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Connection lost. Still following the last timeline. Tap the master to rejoin.",
            color = MaterialTheme.colorScheme.error,
        )
        if (pairingCode != null) PairingCode(pairingCode) else HostList(hosts, onSelect)
    }
}

@Composable
private fun SessionHeader(role: Role, state: ClientState) {
    val song = state.song
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            listOfNotNull(
                song?.title?.ifBlank { null },
                // A song with changes has no one tempo to name. The readout above shows the current one.
                song?.takeIf { it.changes.isEmpty() }?.let { "${it.bpm} BPM ${it.timeSignature.label}" },
            ).joinToString(" · ").ifEmpty { role.label },
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            state.sync?.let { formatUncertainty(it.uncertaintyNs) } ?: "Syncing…",
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun WaitingForSheet(role: Role, state: ClientState) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text(role.label, style = MaterialTheme.typography.displaySmall)
        val delivery = state.delivery.values
        when {
            delivery.isEmpty() -> Text("Waiting for the master to assign your sheet…")
            delivery.any { it == DeliveryState.Failed } ->
                Text("A sheet didn't arrive. The master can resend it.", color = MaterialTheme.colorScheme.error)
            else -> {
                Text("Receiving your sheet…")
                val sending = delivery.filterIsInstance<DeliveryState.Sending>()
                if (sending.isNotEmpty()) {
                    LinearProgressIndicator(
                        progress = { sending.map { it.fraction }.average().toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
    }
}
