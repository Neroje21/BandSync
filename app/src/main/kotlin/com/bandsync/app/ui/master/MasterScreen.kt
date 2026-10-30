package com.bandsync.app.ui.master

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
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
    val snackbar = remember { SnackbarHostState() }
    val tabs = remember(viewModel) {
        MasterTab.entries.filter { it != MasterTab.LIBRARY || viewModel.library.isNotEmpty() }
    }
    var selected by rememberSaveable { mutableIntStateOf(0) }

    KeepScreenOn()
    ShowMessages(viewModel.messages, snackbar)
    LeaveGuard("End the session?", "Every phone will be disconnected.", onLeave)

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = selected) {
                tabs.forEachIndexed { index, tab ->
                    Tab(selected = selected == index, onClick = { selected = index }, text = { Text(tab.title) })
                }
            }
            Box(Modifier.weight(1f)) {
                when (tabs[selected]) {
                    MasterTab.BAND -> BandTab(state, joinRequests, viewModel)
                    MasterTab.LIBRARY -> LibraryTab(state, viewModel)
                    MasterTab.SONG -> SongTab(state, viewModel)
                    MasterTab.LIVE -> LiveTab(state, viewModel)
                }
            }
        }
    }
}
