package com.bandsync.app.ui.common

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.bandsync.app.net.RadioState
import com.bandsync.app.net.connectionChecks
import com.bandsync.core.sheets.Role
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow

private const val CHECK_INTERVAL_MS = 2_000L

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

/**
 * What's happening with Nearby and what might be stopping it, which stays on screen while phones look for each
 * other. The checks refresh by themselves, so switching Bluetooth on shows up without leaving the screen.
 */
@Composable
fun ConnectionStatus(radio: RadioState, activeText: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var status by remember { mutableStateOf(connectionChecks(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(CHECK_INTERVAL_MS)
            status = connectionChecks(context)
        }
    }
    val headline = when (radio) {
        RadioState.Idle -> "Nearby is off"
        RadioState.Starting -> "Starting Nearby…"
        RadioState.Active -> activeText
        is RadioState.Failed -> "Nearby failed: ${radio.reason}"
    }

    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                headline,
                style = MaterialTheme.typography.titleSmall,
                color = if (radio is RadioState.Failed) MaterialTheme.colorScheme.error else Color.Unspecified,
            )
            status.checks.forEach { check ->
                Text(
                    "${if (check.ok) "✓" else "✗"} ${check.label}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (check.ok) Color.Unspecified else MaterialTheme.colorScheme.error,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRetry) { Text("Try again") }
                if (!status.locationOn) {
                    TextButton(
                        onClick = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
                    ) {
                        Text("Turn on Location")
                    }
                }
            }
        }
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
