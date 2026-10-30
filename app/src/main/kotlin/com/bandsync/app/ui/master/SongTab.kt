package com.bandsync.app.ui.master

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bandsync.app.session.MasterViewModel
import com.bandsync.core.metronome.MAX_BPM
import com.bandsync.core.metronome.MIN_BPM
import com.bandsync.core.metronome.TransportState
import com.bandsync.core.protocol.SongConfig
import com.bandsync.core.protocol.TimeSignature
import com.bandsync.core.session.MasterState
import com.bandsync.core.sheets.PageRanges
import com.bandsync.core.sheets.Role
import com.bandsync.core.sheets.RoleSheetMap
import com.bandsync.core.sheets.SheetFile
import com.bandsync.core.sheets.SheetSegment

private val TIME_SIGNATURES = listOf(
    TimeSignature(2, 4),
    TimeSignature(3, 4),
    TimeSignature(4, 4),
    TimeSignature(5, 4),
    TimeSignature(6, 8),
    TimeSignature(7, 8),
    TimeSignature(12, 8),
)
private val COUNT_IN_BARS = listOf(0, 1, 2)

@Composable
fun SongTab(state: MasterState, viewModel: MasterViewModel) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Changing tempo under a running timeline would leave phones with no shared plan.
        SongSettings(state.song, enabled = state.transport !is TransportState.Running, viewModel::updateSong)
        PdfLibrary(state.sheets, viewModel)
        Role.entries.forEach { role ->
            RoleCard(role, state, viewModel)
        }
    }
}

@Composable
private fun SongSettings(song: SongConfig, enabled: Boolean, onChange: (SongConfig) -> Unit) {
    OutlinedTextField(
        value = song.title,
        onValueChange = { onChange(song.copy(title = it)) },
        label = { Text("Song title") },
        enabled = enabled,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    BpmField(song.bpm, enabled, onChange = { onChange(song.copy(bpm = it)) })

    Text("Time signature", style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TIME_SIGNATURES.forEach { signature ->
            FilterChip(
                selected = signature == song.timeSignature,
                onClick = { onChange(song.copy(timeSignature = signature)) },
                label = { Text(signature.label) },
                enabled = enabled,
            )
        }
    }

    Text("Count-in", style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        COUNT_IN_BARS.forEach { bars ->
            FilterChip(
                selected = bars == song.countInBars,
                onClick = { onChange(song.copy(countInBars = bars)) },
                label = { Text(if (bars == 0) "None" else "$bars bar${if (bars > 1) "s" else ""}") },
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun BpmField(bpm: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    var text by remember(bpm) { mutableStateOf(bpm.toString()) }
    val valid = text.toIntOrNull()?.let { it in MIN_BPM..MAX_BPM } == true
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        FilledTonalButton(onClick = { onChange((bpm - 1).coerceAtLeast(MIN_BPM)) }, enabled = enabled) { Text("−") }
        OutlinedTextField(
            value = text,
            onValueChange = { typed ->
                text = typed.filter(Char::isDigit).take(3)
                text.toIntOrNull()?.takeIf { it in MIN_BPM..MAX_BPM }?.let(onChange)
            },
            label = { Text("BPM") },
            isError = !valid,
            supportingText = if (valid) null else ({ Text("$MIN_BPM to $MAX_BPM") }),
            enabled = enabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(120.dp),
        )
        FilledTonalButton(onClick = { onChange((bpm + 1).coerceAtMost(MAX_BPM)) }, enabled = enabled) { Text("+") }
    }
}

@Composable
private fun PdfLibrary(sheets: RoleSheetMap, viewModel: MasterViewModel) {
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
        viewModel::addPdfs,
    )
    Text("Sheets", style = MaterialTheme.typography.titleMedium)
    sheets.files.values.forEach { file ->
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${file.name} (${file.pageCount} pp)", Modifier.weight(1f))
            TextButton(onClick = { viewModel.removePdf(file.id) }) { Text("Remove") }
        }
    }
    Button(onClick = { picker.launch(arrayOf("application/pdf")) }, Modifier.fillMaxWidth()) {
        Text("Add PDFs")
    }
}

@Composable
private fun RoleCard(role: Role, state: MasterState, viewModel: MasterViewModel) {
    val phones = state.members.values.count { it.role == role }
    val heading = buildString {
        append(role.label)
        if (role == state.ownRole) append(" (you)")
        if (phones > 0) append(" · $phones ${if (phones == 1) "phone" else "phones"}")
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(heading, style = MaterialTheme.typography.titleSmall)
            state.sheets.segmentsFor(role).forEachIndexed { index, segment ->
                val file = state.sheets.files[segment.fileId] ?: return@forEachIndexed
                SegmentRow(
                    file = file,
                    segment = segment,
                    onPages = { viewModel.setPages(role, index, it) },
                    onRemove = { viewModel.unassign(role, index) },
                )
            }
            AddSheetMenu(state.sheets.files.values.toList(), onAdd = { viewModel.assign(role, it.id) })
        }
    }
}

@Composable
private fun SegmentRow(
    file: SheetFile,
    segment: SheetSegment,
    onPages: (List<Int>) -> Unit,
    onRemove: () -> Unit,
) {
    var text by remember(segment) { mutableStateOf(PageRanges.format(segment.pages)) }
    val parsed = PageRanges.parse(text, file.pageCount)
    Column {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(file.name, Modifier.weight(1f))
            TextButton(onClick = onRemove) { Text("Remove") }
        }
        OutlinedTextField(
            value = text,
            onValueChange = { typed ->
                text = typed
                PageRanges.parse(typed, file.pageCount)?.let(onPages)
            },
            label = { Text("Pages, e.g. 1-3, 5") },
            placeholder = { Text("All ${file.pageCount} pages") },
            isError = parsed == null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AddSheetMenu(files: List<SheetFile>, onAdd: (SheetFile) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        TextButton(onClick = { open = true }, enabled = files.isNotEmpty()) { Text("Assign a sheet") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            files.forEach { file ->
                DropdownMenuItem(
                    text = { Text(file.name) },
                    onClick = {
                        open = false
                        onAdd(file)
                    },
                )
            }
        }
    }
}
