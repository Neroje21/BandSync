package com.bandsync.app.ui.master

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bandsync.app.pdf.LibrarySong
import com.bandsync.app.session.MasterViewModel
import com.bandsync.core.metronome.TransportState
import com.bandsync.core.session.MasterState

@Composable
fun LibraryTab(state: MasterState, viewModel: MasterViewModel) {
    // A song in progress or paused still needs its sheets and timeline, so swapping waits for Stop.
    val canLoad = state.transport == TransportState.Stopped
    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                if (canLoad) {
                    "Loading a song sends each part its tab and presets every instrument's tempo."
                } else {
                    "Stop the song to load another."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(viewModel.library, key = { it.title }) { song ->
            LibrarySongCard(
                song = song,
                isLoaded = song.title == state.song.title,
                canLoad = canLoad,
                onLoad = { viewModel.loadSong(song) },
            )
        }
    }
}

/** Loading again is offered too, as the way back to the preset after editing a song. */
@Composable
private fun LibrarySongCard(song: LibrarySong, isLoaded: Boolean, canLoad: Boolean, onLoad: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(song.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    song.sheets.joinToString(", ") { it.part.label },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isLoaded) {
                OutlinedButton(onClick = onLoad, enabled = canLoad) { Text("Reload") }
            } else {
                Button(onClick = onLoad, enabled = canLoad) { Text("Load") }
            }
        }
    }
}
