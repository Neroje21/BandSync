package com.bandsync.app.ui.master

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bandsync.app.session.MasterViewModel
import com.bandsync.core.metronome.MAX_BPM
import com.bandsync.core.metronome.MIN_BPM
import com.bandsync.core.metronome.TransportState
import com.bandsync.core.protocol.SongChange
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
private const val MAX_BAR = 9999
private const val MAX_DIGITS = 4
private const val BARS_TO_NEW_CHANGE = 8

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
        val songEditable = state.transport !is TransportState.Running
        SongSettings(state.song, state.ownRole, songEditable, viewModel::updateSong)
        OutlinedButton(
            onClick = viewModel::readTempoFromTabs,
            modifier = Modifier.fillMaxWidth(),
            enabled = songEditable && state.sheets.assignments.values.any { it.isNotEmpty() },
        ) {
            Text("Read tempo from tabs")
        }
        PdfLibrary(state.sheets, songEditable, viewModel)
        Role.entries.forEach { role ->
            RoleCard(role, state, viewModel)
        }
    }
}

@Composable
private fun SongSettings(song: SongConfig, ownRole: Role, enabled: Boolean, onChange: (SongConfig) -> Unit) {
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

    SongChanges(song, ownRole, enabled, onChange)
}

@Composable
private fun BpmField(bpm: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        FilledTonalButton(onClick = { onChange((bpm - 1).coerceAtLeast(MIN_BPM)) }, enabled = enabled) { Text("−") }
        NumberField(bpm, MIN_BPM..MAX_BPM, "BPM", enabled, onChange, Modifier.width(120.dp))
        FilledTonalButton(onClick = { onChange((bpm + 1).coerceAtMost(MAX_BPM)) }, enabled = enabled) { Text("+") }
    }
}

/** Only passes on values in [range], so a half-typed number never reaches the band. */
@Composable
private fun NumberField(
    value: Int,
    range: IntRange,
    label: String,
    enabled: Boolean,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    val valid = text.toIntOrNull()?.let { it in range } == true
    OutlinedTextField(
        value = text,
        onValueChange = { typed ->
            text = typed.filter(Char::isDigit).take(MAX_DIGITS)
            text.toIntOrNull()?.takeIf { it in range }?.let(onChange)
        },
        label = { Text(label) },
        isError = !valid,
        supportingText = if (valid) null else ({ Text("${range.first} to ${range.last}") }),
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
private fun SongChanges(song: SongConfig, ownRole: Role, enabled: Boolean, onChange: (SongConfig) -> Unit) {
    var part by rememberSaveable { mutableStateOf(ownRole) }

    Text("Tempo and time changes", style = MaterialTheme.typography.titleSmall)
    Text(
        "The BPM above is how the song opens. Each instrument has its own changes, with bars numbered as on its " +
            "own tab. Pick one to see and edit its list.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Role.entries.forEach { role ->
            val count = song.changes.count { it.appliesTo(role) }
            FilterChip(
                selected = role == part,
                onClick = { part = role },
                label = { Text(if (count == 0) role.label else "${role.label} ($count)") },
            )
        }
    }
    song.changes.forEachIndexed { index, change ->
        if (change.appliesTo(part)) {
            // Rows come and go as the instrument changes, so each is tied to its change, not its place on screen.
            key(index) {
                SongChangeRow(
                    change = change,
                    enabled = enabled,
                    onChange = { edited ->
                        onChange(song.copy(changes = song.changes.mapIndexed { i, old -> if (i == index) edited else old }))
                    },
                    onRemove = { onChange(song.copy(changes = song.changes.filterIndexed { i, _ -> i != index })) },
                )
            }
        }
    }
    TextButton(
        onClick = { onChange(song.copy(changes = song.changes + nextChange(song, part))) },
        enabled = enabled,
    ) {
        Text("Add a change for ${part.label}")
    }
}

/** Starts from wherever this part last got to, since a new change usually differs in only one way. */
private fun nextChange(song: SongConfig, part: Role): SongChange {
    val last = song.changes.filter { it.appliesTo(part) }.maxByOrNull { it.bar }
    return SongChange(
        bar = (last?.bar ?: 1) + BARS_TO_NEW_CHANGE,
        bpm = last?.bpm ?: song.bpm,
        timeSignature = last?.timeSignature ?: song.timeSignature,
        parts = setOf(part),
    )
}

@Composable
private fun SongChangeRow(
    change: SongChange,
    enabled: Boolean,
    onChange: (SongChange) -> Unit,
    onRemove: () -> Unit,
) {
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            NumberField(
                value = change.bar,
                range = (if (change.parts.isEmpty()) 2 else 1)..MAX_BAR, // A part can open at its own tempo.
                label = "Bar",
                enabled = enabled,
                onChange = { onChange(change.copy(bar = it)) },
                modifier = Modifier.weight(1f),
            )
            NumberField(
                value = change.bpm,
                range = MIN_BPM..MAX_BPM,
                label = "BPM",
                enabled = enabled,
                onChange = { onChange(change.copy(bpm = it)) },
                modifier = Modifier.weight(1f),
            )
            TimeSignatureMenu(change.timeSignature, enabled, onSelect = { onChange(change.copy(timeSignature = it)) })
            TextButton(
                onClick = onRemove,
                enabled = enabled,
                modifier = Modifier.semantics { contentDescription = "Remove this change" },
            ) {
                Text("✕")
            }
        }
        PartsMenu(change.parts, enabled, onChange = { onChange(change.copy(parts = it)) })
    }
}

/** Stays open after a tap on a part, since picking several is the point of it. */
@Composable
private fun PartsMenu(parts: Set<Role>, enabled: Boolean, onChange: (Set<Role>) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val selected = Role.entries.filter { it in parts }
    Box {
        TextButton(onClick = { open = true }, enabled = enabled) {
            Text("For: ${selected.joinToString(", ") { it.label }.ifEmpty { "Everyone" }}")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Everyone") },
                onClick = {
                    open = false
                    onChange(emptySet())
                },
            )
            Role.entries.forEach { part ->
                DropdownMenuItem(
                    text = { Text(part.label) },
                    leadingIcon = { Text(if (part in parts) "✓" else "") },
                    onClick = { onChange(if (part in parts) parts - part else parts + part) },
                )
            }
        }
    }
}

@Composable
private fun TimeSignatureMenu(selected: TimeSignature, enabled: Boolean, onSelect: (TimeSignature) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { open = true },
            enabled = enabled,
            contentPadding = PaddingValues(horizontal = 12.dp),
        ) {
            Text(selected.label)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            TIME_SIGNATURES.forEach { signature ->
                DropdownMenuItem(
                    text = { Text(signature.label) },
                    onClick = {
                        open = false
                        onSelect(signature)
                    },
                )
            }
        }
    }
}

@Composable
private fun PdfLibrary(sheets: RoleSheetMap, songEditable: Boolean, viewModel: MasterViewModel) {
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
    if (viewModel.hasTestTabs) {
        // It also sets the tempo, so it waits for the song to stop just as the tempo field does.
        OutlinedButton(onClick = viewModel::loadTestTabs, Modifier.fillMaxWidth(), enabled = songEditable) {
            Text("Load test tabs (Iron Man)")
        }
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
