package com.bandsync.app.ui.master

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bandsync.app.session.MasterViewModel
import com.bandsync.app.ui.live.CountdownOverlay
import com.bandsync.app.ui.live.LiveReadout
import com.bandsync.app.ui.live.rememberPlayhead
import com.bandsync.app.ui.sheet.SheetViewer
import com.bandsync.core.metronome.TransportState
import com.bandsync.core.session.MasterState

@Composable
fun LiveTab(state: MasterState, viewModel: MasterViewModel) {
    val playhead by rememberPlayhead(state.transport, viewModel::localNow)
    // This screen recomposes on every beat, and that is the wrong moment for needless main-thread work.
    val pages = remember(state.sheets, state.ownRole) {
        state.sheets.manifestFor(state.ownRole, state.revision).pages()
    }

    Column(Modifier.fillMaxSize()) {
        LiveReadout(playhead, viewModel.ticks, viewModel::localNow)
        TransportControls(state.transport, viewModel)

        val unsynced = state.members.values.count { it.uncertaintyNs == null }
        if (unsynced > 0) {
            Text(
                "$unsynced ${if (unsynced == 1) "phone is" else "phones are"} still syncing and will catch up",
                Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.secondary,
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (pages.isEmpty()) {
                Text(
                    "Assign a sheet to ${state.ownRole.label} on the Song tab to see it here",
                    Modifier.align(Alignment.Center).padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                SheetViewer(pages, viewModel.store, Modifier.fillMaxSize())
            }
            CountdownOverlay(playhead)
        }
    }
}

@Composable
private fun TransportControls(transport: TransportState, viewModel: MasterViewModel) {
    Row(
        Modifier.fillMaxWidth().padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val buttonModifier = Modifier.weight(1f).height(72.dp)
        Button(
            onClick = viewModel::start,
            modifier = buttonModifier,
            enabled = transport !is TransportState.Running,
        ) {
            Text(if (transport is TransportState.Paused) "RESUME" else "START")
        }
        FilledTonalButton(
            onClick = viewModel::pause,
            modifier = buttonModifier,
            enabled = transport is TransportState.Running,
        ) {
            Text("PAUSE")
        }
        OutlinedButton(
            onClick = viewModel::stop,
            modifier = buttonModifier,
            enabled = transport != TransportState.Stopped,
        ) {
            Text("STOP")
        }
    }
}
