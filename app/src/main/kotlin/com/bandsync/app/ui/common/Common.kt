package com.bandsync.app.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.bandsync.core.sheets.Role
import kotlinx.coroutines.flow.Flow

/** A phone on a music stand must not dim halfway through a song. */
@Composable
fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
fun ShowMessages(messages: Flow<String>, snackbar: SnackbarHostState) {
    LaunchedEffect(messages) {
        messages.collect { snackbar.showSnackbar(it) }
    }
}

/** Back ends the session for everyone relying on this phone, so it asks first. */
@Composable
fun LeaveGuard(title: String, body: String, onLeave: () -> Unit) {
    var asking by remember { mutableStateOf(false) }
    BackHandler { asking = true }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(title) },
            text = { Text(body) },
            confirmButton = { TextButton(onClick = onLeave) { Text("Leave") } },
            dismissButton = { TextButton(onClick = { asking = false }) { Text("Stay") } },
        )
    }
}

@Composable
fun RolePicker(selected: Role, onSelect: (Role) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Role.entries.forEach { role ->
            FilterChip(
                selected = role == selected,
                onClick = { onSelect(role) },
                label = { Text(role.label) },
            )
        }
    }
}

fun formatUncertainty(ns: Long): String = "±%.1f ms".format(ns / 1_000_000.0)
