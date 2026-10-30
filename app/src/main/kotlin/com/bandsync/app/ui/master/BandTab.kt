package com.bandsync.app.ui.master

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bandsync.app.net.RadioState
import com.bandsync.app.session.MasterViewModel
import com.bandsync.app.ui.common.ConnectionStatus
import com.bandsync.app.ui.common.formatUncertainty
import com.bandsync.core.metronome.TransportState
import com.bandsync.core.session.DeliveryState
import com.bandsync.core.session.MasterState
import com.bandsync.core.session.Member

@Composable
fun BandTab(state: MasterState, radio: RadioState, viewModel: MasterViewModel) {
    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            val open = state.transport !is TransportState.Running
            Text(
                if (open) "Open to new members. You're playing ${state.ownRole.label}."
                else "New members can join once the song is paused or stopped.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.members.isEmpty()) {
            item {
                ConnectionStatus(radio, "Visible to nearby phones. Waiting for one to join…", viewModel::retryHosting)
            }
        }
        items(state.members.values.toList(), key = { it.endpoint }) { member ->
            MemberCard(member, onResend = { viewModel.redeliver(member.endpoint) })
        }
    }
}

@Composable
private fun MemberCard(member: Member, onResend: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(member.name, style = MaterialTheme.typography.titleMedium)
                Text(member.role.label, color = MaterialTheme.colorScheme.primary)
            }
            Text(
                member.uncertaintyNs?.let { "In sync ${formatUncertainty(it)}" } ?: "Syncing clock…",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(deliverySummary(member), style = MaterialTheme.typography.bodyMedium)
            if (member.delivery.values.any { it == DeliveryState.Failed }) {
                TextButton(onClick = onResend) { Text("Resend") }
            }
        }
    }
}

private fun deliverySummary(member: Member): String {
    val delivery = member.delivery.values
    val delivered = delivery.count { it == DeliveryState.Delivered }
    val sending = delivery.filterIsInstance<DeliveryState.Sending>()
    return when {
        delivery.isEmpty() -> "No sheet assigned"
        delivery.any { it == DeliveryState.Failed } -> "A sheet failed to send"
        sending.isNotEmpty() -> "Sending ${(sending.map { it.fraction }.average() * 100).toInt()}%"
        delivered == delivery.size -> "Sheets delivered"
        else -> "Sheets on the way ($delivered of ${delivery.size})"
    }
}
