package com.bandsync.app.ui.home

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bandsync.app.BandSyncApp
import com.bandsync.app.net.missingPermissions
import com.bandsync.app.ui.common.RolePicker

@Composable
fun HomeScreen(onHost: () -> Unit, onJoin: () -> Unit) {
    val context = LocalContext.current
    val profile = remember { (context.applicationContext as BandSyncApp).profile }
    var name by rememberSaveable { mutableStateOf(profile.name) }
    var role by rememberSaveable { mutableStateOf(profile.role) }
    var denied by rememberSaveable { mutableStateOf(false) }
    var afterGrant by remember { mutableStateOf<(() -> Unit)?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        denied = !results.values.all { it }
        if (!denied) afterGrant?.invoke()
        afterGrant = null
    }

    fun enter(destination: () -> Unit) {
        profile.name = name.trim().ifEmpty { profile.name }
        profile.role = role
        val missing = missingPermissions(context)
        if (missing.isEmpty()) {
            destination()
        } else {
            afterGrant = destination
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("BandSync", style = MaterialTheme.typography.displayMedium)
            Text(
                "One beat and the right sheet on every phone.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Your name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Your part", style = MaterialTheme.typography.titleMedium)
            RolePicker(role, onSelect = { role = it })

            Button(onClick = { enter(onHost) }, Modifier.fillMaxWidth().height(64.dp)) {
                Text("Host Session", style = MaterialTheme.typography.titleLarge)
            }
            OutlinedButton(onClick = { enter(onJoin) }, Modifier.fillMaxWidth().height(64.dp)) {
                Text("Join Session", style = MaterialTheme.typography.titleLarge)
            }

            if (denied) {
                Text(
                    "BandSync needs the Nearby devices and precise Location permissions to find the other " +
                        "phones. Nothing is tracked.",
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(
                    onClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null),
                            ),
                        )
                    },
                ) {
                    Text("Open settings")
                }
            }
        }
    }
}
