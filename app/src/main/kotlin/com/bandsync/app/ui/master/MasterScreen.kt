package com.bandsync.app.ui.master

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bandsync.app.net.JoinRequest
import com.bandsync.app.session.MasterViewModel
import com.bandsync.app.ui.common.KeepScreenOn
import com.bandsync.app.ui.common.LeaveGuard
import com.bandsync.app.ui.common.ShowMessages

private enum class MasterTab(val title: String) {
    BAND("Band"),
    LIBRARY("Library"),
    SONG("Song"),
    LIVE("Live"),
}

@Composable
fun MasterScreen(onLeave: () -> Unit, viewModel: MasterViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val joinRequests by viewModel.joinRequests.collectAsStateWithLifecycle()
    val radio by viewModel.radio.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val tabs = remember(viewModel) {
        MasterTab.entries.filter { it != MasterTab.LIBRARY || viewModel.library.isNotEmpty() }
    }
    var selected by rememberSaveable { mutableIntStateOf(0) }

    KeepScreenOn()
    ShowMessages(viewModel.messages, snackbar)
    LeaveGuard("End the session?", "Every phone will be disconnected.", onLeave)

    // A dialog, because the phone asking to join has to be answered whichever tab is showing.
    joinRequests.firstOrNull()?.let { request ->
        JoinRequestDialog(
            request,
            onAccept = { viewModel.accept(request) },
            onDecline = { viewModel.decline(request) },
        )
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = selected) {
                tabs.forEachIndexed { index, tab ->
                    Tab(selected = selected == index, onClick = { selected = index }, text = { Text(tab.title) })
                }
            }
            Box(Modifier.weight(1f)) {
                when (tabs[selected]) {
                    MasterTab.BAND -> BandTab(state, radio, viewModel)
                    MasterTab.LIBRARY -> LibraryTab(state, viewModel)
                    MasterTab.SONG -> SongTab(state, viewModel)
                    MasterTab.LIVE -> LiveTab(state, viewModel)
                }
            }
        }
    }
}

@Composable
private fun JoinRequestDialog(request: JoinRequest, onAccept: () -> Unit, onDecline: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("${request.name} wants to join") },
        text = {
            Column {
                Text("Check their phone shows this code:")
                Text(request.code, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            }
        },
        confirmButton = { TextButton(onClick = onAccept) { Text("Accept") } },
        dismissButton = { TextButton(onClick = onDecline) { Text("Decline") } },
    )
}
