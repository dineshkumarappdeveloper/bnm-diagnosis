package com.bnm.analyzersim.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bnm.analyzersim.Analyzer
import com.bnm.analyzersim.Profile

/**
 * The whole window.
 *
 * Two columns, not one. The form is a settings panel an engineer sets once and
 * then only ever changes the address in; the transcript is a log they watch
 * while a run goes past. Stacked, the transcript pushed the sample and fault
 * cards off a 700px-tall laptop screen and left half the width empty — so the
 * form scrolls on the left and the log stands still on the right, with Send and
 * the expectation line always in view.
 */
@Composable
fun SimScreen(state: SimState) {
    val form = state.form
    val formScroll = rememberScrollState()
    // The faults panel is the third card, below two tall ones. Expanding a panel
    // you then cannot see reads as a broken checkbox, so it brings itself into
    // view — after a frame, because its height is not known until it is measured.
    LaunchedEffect(state.faultsExpanded) {
        if (!state.faultsExpanded) return@LaunchedEffect
        withFrameNanos { }
        formScroll.animateScrollTo(formScroll.maxValue)
    }
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Header(state)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Row(Modifier.weight(1f)) {
                Column(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(formScroll).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    LinkCard(state, form)
                    SampleCard(state, form)
                    FaultsCard(state, form)
                }
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.width(350.dp).fillMaxHeight()) {
                    TranscriptHeader(state)
                    TranscriptPane(state, Modifier.weight(1f))
                    ExpectationLine(form)
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SendBar(state)
        }
    }
}

// ── header and presets ────────────────────────────────────────────────────────

@Composable
private fun Header(state: SimState) {
    var saveOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(16.dp, 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("BNM Analyzer Simulator", style = MaterialTheme.typography.titleMedium)
            Text(
                "Pretends to be a lab analyzer, so a commissioning can be rehearsed with nothing on the bench.",
                style = MaterialTheme.typography.bodySmall, color = SimMuted,
            )
        }
        Picker(
            selected = "Presets",
            options = state.presets.map { it.name },
            onPick = { name -> state.presets.firstOrNull { it.name == name }?.let(state::loadPreset) },
            modifier = Modifier.width(180.dp),
        )
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = { saveOpen = true }) { Text("Save as…") }
    }
    if (saveOpen) {
        SavePresetDialog(
            onDismiss = { saveOpen = false },
            onSave = { state.savePreset(it); saveOpen = false },
        )
    }
}

@Composable
private fun SavePresetDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save this form as a preset") },
        text = {
            Column {
                Text(
                    "Everything on screen is saved — the address, the id, the profile and the faults.",
                    style = MaterialTheme.typography.bodySmall, color = SimMuted,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(name, { name = it }, singleLine = true,
                    label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { Button(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ── the link ──────────────────────────────────────────────────────────────────

@Composable
private fun LinkCard(state: SimState, form: SimForm) = SectionCard("The link") {
    Picker(
        selected = form.analyzer.shortName,
        options = Analyzer.entries.map { it.shortName },
        onPick = { name ->
            Analyzer.entries.firstOrNull { it.shortName == name }
                ?.let { a -> state.edit { it.withAnalyzer(a) } }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    Text(driverReminder(form), style = MaterialTheme.typography.bodySmall, color = SimMuted,
        modifier = Modifier.padding(top = 6.dp))

    Spacer(Modifier.height(8.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
        TransportChoice("TCP", form.transport == TransportKind.TCP, true) {
            state.edit { it.withTransport(TransportKind.TCP) }
        }
        Spacer(Modifier.width(12.dp))
        TransportChoice("Serial (RS-232)", form.transport == TransportKind.SERIAL, form.serialAllowed) {
            state.edit { it.withTransport(TransportKind.SERIAL) }
        }
    }
    form.serialRefusal?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = SimMuted,
            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
    }

    if (form.transport == TransportKind.SERIAL && form.serialAllowed) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Picker(
                    selected = form.serialPort.ifBlank { "Choose a port…" },
                    options = state.serialPorts.ifEmpty { listOf("(no serial ports on this machine)") },
                    onPick = { p -> if (!p.startsWith("(")) state.edit { it.copy(serialPort = p) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                form.problem(FormField.SERIAL_PORT)?.let { ErrorLine(it) }
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = state::refreshSerialPorts) {
                Icon(Icons.Default.Refresh, null, Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text("Refresh")
            }
            Spacer(Modifier.width(8.dp))
            Field("Baud", form.baud, { v -> state.edit { it.copy(baud = v) } },
                Modifier.width(110.dp), error = form.problem(FormField.BAUD))
        }
    } else {
        Row(verticalAlignment = Alignment.Top) {
            Field("PC running BNM Lab", form.host, { v -> state.edit { it.withHost(v) } },
                Modifier.weight(1f), error = form.problem(FormField.HOST))
            Spacer(Modifier.width(8.dp))
            Field("Port", form.port, { v -> state.edit { it.copy(port = v) } },
                Modifier.width(110.dp), error = form.problem(FormField.PORT))
        }
    }

    if (form.sendsToAnotherMachine) LiveLabConsent(state, form)

    Spacer(Modifier.height(8.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = state::testLink, enabled = !state.testingLink) {
            if (state.testingLink) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text("Test link")
        }
        Spacer(Modifier.width(10.dp))
        state.linkResult?.let { result ->
            Text(
                result.message,
                style = MaterialTheme.typography.bodySmall,
                color = if (result.ok) MaterialTheme.colorScheme.primary else SimDanger,
                modifier = Modifier.weight(1f),
            )
        } ?: Text(
            "Opens the link and closes it again. Sends nothing, files nothing.",
            style = MaterialTheme.typography.bodySmall, color = SimMuted, modifier = Modifier.weight(1f),
        )
    }
}

/**
 * The one thing standing between a re-run and invented results on a real
 * patient's order.
 *
 * Drawn in the error colour and never remembered: the tick is cleared the
 * moment the address changes, and no preset carries it. A dialog would be worse
 * — dialogs are dismissed by muscle memory, and this has to be READ.
 */
@Composable
private fun LiveLabConsent(state: SimState, form: SimForm) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.Top) {
            Checkbox(form.liveLab, { v -> state.edit { it.copy(liveLab = v) } })
            Column(Modifier.padding(start = 2.dp, top = 10.dp)) {
                Text(
                    "Yes, send invented results to ${form.host.trim()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    "That is not this computer. Once BNM Lab has filed these numbers they are " +
                        "indistinguishable from the analyzer's own — on the order with that accession, " +
                        "approvable, printable, with nothing anywhere to say they were generated. " +
                        "A bench install, never a lab seeing patients.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

@Composable
private fun TransportChoice(label: String, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.selectable(selected, enabled, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, onClick = onSelect, enabled = enabled)
        Text(label, style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else SimMuted)
    }
}

// ── the sample ────────────────────────────────────────────────────────────────

@Composable
private fun SampleCard(state: SimState, form: SimForm) = SectionCard("The sample") {
    Row(verticalAlignment = Alignment.Top) {
        Field("Specimen id (the accession on the tube)", form.sampleId,
            { v -> state.edit { it.copy(sampleId = v) } },
            Modifier.weight(1f), enabled = !form.faults.noSpecimen,
            error = form.problem(FormField.SAMPLE_ID))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.width(150.dp).padding(top = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(form.autoIncrementId, { v -> state.edit { it.copy(autoIncrementId = v) } })
                Spacer(Modifier.width(6.dp))
                Text("+1 per run", style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                if (form.autoIncrementId) "next: ${nextSampleId(form.sampleId)}" else "every run reuses it",
                style = MaterialTheme.typography.bodySmall, color = SimMuted,
            )
        }
    }

    Spacer(Modifier.height(8.dp))

    Row(verticalAlignment = Alignment.Top) {
        Field("Samples", form.count, { v -> state.edit { it.copy(count = v) } },
            Modifier.width(100.dp), error = form.problem(FormField.COUNT))
        Spacer(Modifier.width(8.dp))
        Field("Seconds apart", form.intervalSeconds, { v -> state.edit { it.copy(intervalSeconds = v) } },
            Modifier.width(120.dp), error = form.problem(FormField.INTERVAL))
        Spacer(Modifier.width(8.dp))
        Field("Seed", form.seed, { v -> state.edit { it.copy(seed = v) } },
            Modifier.weight(1f), error = form.problem(FormField.SEED))
    }

    Spacer(Modifier.height(8.dp))

    Picker(
        selected = form.profile.cliName,
        options = Profile.entries.map { it.cliName },
        onPick = { n -> Profile.of(n)?.let { p -> state.edit { f -> f.copy(profile = p) } } },
        modifier = Modifier.fillMaxWidth(),
    )
    Text(form.profile.summary, style = MaterialTheme.typography.bodySmall, color = SimMuted,
        modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))

    Row(verticalAlignment = Alignment.Top) {
        Field("Patient name", form.patientName, { v -> state.edit { it.copy(patientName = v) } },
            Modifier.weight(1f), enabled = form.analyzer == Analyzer.MINDRAY)
        Spacer(Modifier.width(8.dp))
        Field("Patient id", form.patientId, { v -> state.edit { it.copy(patientId = v) } },
            Modifier.weight(1f))
    }
    if (form.analyzer == Analyzer.MISPA) {
        Text(
            "The Mispa frame carries no patient name — only the id travels.",
            style = MaterialTheme.typography.bodySmall, color = SimMuted,
            modifier = Modifier.padding(top = 2.dp),
        )
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Toggle("QC run", form.qc) { v -> state.edit { it.copy(qc = v) } }
        Toggle("Histograms", form.histograms) { v -> state.edit { it.copy(histograms = v) } }
        Toggle(
            "Scattergram", form.image && form.analyzer == Analyzer.MINDRAY,
            enabled = form.analyzer == Analyzer.MINDRAY,
        ) { v -> state.edit { it.copy(image = v) } }
        // A run MODE, not a display option: no differential rows at all. The
        // Mispa is a 3-part analyzer and has no such mode to imitate.
        Toggle(
            "CBC only", form.cbcOnly && form.analyzer == Analyzer.MINDRAY,
            enabled = form.analyzer == Analyzer.MINDRAY,
        ) { v -> state.edit { it.copy(cbcOnly = v) } }
    }
}

// ── faults ────────────────────────────────────────────────────────────────────

@Composable
private fun FaultsCard(state: SimState, form: SimForm) {
    val f = form.faults
    // Truncation, a burst and a hang are all about a CONNECTION, and a cable
    // has none — the core refuses them on serial, so the boxes say why here
    // rather than letting Send fail with a sentence about a tick three cards up.
    val onCable = form.transport == TransportKind.SERIAL && form.serialAllowed
    val cableNote = "needs a connection to act on; a serial cable has none"
    SectionCard(
        title = "Faults" + if (f.chosenCount > 0) "  ·  ${f.chosenCount} chosen" else "",
        trailing = {
            Icon(if (state.faultsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
        },
        onTitleClick = { state.faultsExpanded = !state.faultsExpanded },
    ) {
        if (!state.faultsExpanded) {
            Text(
                "The reason this tool exists. \"It will not link\" is almost never the parser — it is half " +
                    "a frame, a dribbled cable, or a barcode nobody keyed.",
                style = MaterialTheme.typography.bodySmall, color = SimMuted,
            )
            return@SectionCard
        }
        Fault("Truncated frame",
            if (onCable) cableNote else "Half a frame, then the connection closes. Nothing should be filed.",
            f.truncated, enabled = !onCable) { v -> state.edit { it.copy(faults = it.faults.copy(truncated = v)) } }
        Fault("Garbage bytes", "Not a frame at all. The bytes should count; the frame counter should not.",
            f.garbage) { v -> state.edit { it.copy(faults = it.faults.copy(garbage = v)) } }
        Fault("Slow chunks", "64-byte pieces with a gap — a slow USB-serial adapter. Tests reassembly.",
            f.slowChunks, trailing = {
                Field("ms", f.slowChunksMs, { v -> state.edit { it.copy(faults = it.faults.copy(slowChunksMs = v)) } },
                    Modifier.width(78.dp), enabled = f.slowChunks, error = form.problem(FormField.SLOW_CHUNKS))
            }) { v -> state.edit { it.copy(faults = it.faults.copy(slowChunks = v)) } }
        Fault("Unknown parameter code", "A firmware the driver has never met. Must not land on a real parameter.",
            f.unknownCode) { v -> state.edit { it.copy(faults = it.faults.copy(unknownCode = v)) } }
        Fault("Bad units", "A unit the converter cannot bridge. The value must still land, with a warning.",
            f.badUnits) { v -> state.edit { it.copy(faults = it.faults.copy(badUnits = v)) } }
        Fault("No specimen id", "The tube was run before the barcode was scanned. Proves the claim queue.",
            f.noSpecimen) { v -> state.edit { it.copy(faults = it.faults.copy(noSpecimen = v)) } }
        Fault("Duplicate frame", "A retransmit the analyzer did not believe was ACKed. Must not double-apply.",
            f.duplicate) { v -> state.edit { it.copy(faults = it.faults.copy(duplicate = v)) } }
        Fault("Burst", if (onCable) cableNote else "Several analyzers at once. Proves no connection is dropped.",
            f.burst, enabled = !onCable, trailing = {
                Field("n", f.burstCount, { v -> state.edit { it.copy(faults = it.faults.copy(burstCount = v)) } },
                    Modifier.width(70.dp), enabled = f.burst, error = form.problem(FormField.BURST))
            }) { v -> state.edit { it.copy(faults = it.faults.copy(burst = v)) } }
        Fault("Hang",
            if (onCable) cableNote else "Connect, send nothing, hold the socket — wired up, but nobody pressed Send.",
            f.hang, enabled = !onCable) { v -> state.edit { it.copy(faults = it.faults.copy(hang = v)) } }
    }
}

@Composable
private fun Fault(
    title: String,
    proves: String,
    checked: Boolean,
    enabled: Boolean = true,
    trailing: @Composable (() -> Unit)? = null,
    onCheck: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheck, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else SimMuted)
            Text(proves, style = MaterialTheme.typography.bodySmall, color = SimMuted)
        }
        trailing?.invoke()
    }
}

// ── send, transcript, expectation ─────────────────────────────────────────────

@Composable
private fun SendBar(state: SimState) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(16.dp, 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(onClick = state::send, enabled = !state.running) {
            Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Send")
        }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = state::stop, enabled = state.running && !state.stopping) {
            Text(if (state.stopping) "Stopping…" else "Stop")
        }
        if (state.running) {
            Spacer(Modifier.width(12.dp))
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        }
        Spacer(Modifier.width(14.dp))
        Text(
            state.status
                ?: if (state.stopping) "Finishing the sample already in flight, then stopping."
                else "Nothing is sent until you press Send.",
            style = MaterialTheme.typography.bodySmall, color = SimMuted, modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TranscriptHeader(state: SimState) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(12.dp, 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Transcript", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        TextButton(
            onClick = { copyToClipboard(transcriptText(state.blocks)) },
            enabled = state.blocks.isNotEmpty(),
        ) { Text("Copy") }
    }
}

@Composable
private fun TranscriptPane(state: SimState, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    // Newest at the bottom, and the pane follows it — an engineer watching a
    // five-sample run should never have to scroll to see what just happened.
    LaunchedEffect(state.blocks.size) {
        if (state.blocks.isNotEmpty()) listState.scrollToItem(state.blocks.lastIndex)
    }
    Box(modifier.fillMaxWidth().background(SimWell).padding(horizontal = 12.dp, vertical = 8.dp)) {
        if (state.blocks.isEmpty()) {
            Text(
                "Press Send. Every sample appears here: the values, the bytes that left this machine, " +
                    "and what BNM Lab said back.",
                style = MaterialTheme.typography.bodySmall, color = SimMuted,
            )
        }
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.blocks) { block -> TranscriptBlockView(block) }
        }
    }
}

@Composable
private fun TranscriptBlockView(block: TranscriptBlock) {
    val accent = when (block.tone) {
        BlockTone.OK -> MaterialTheme.colorScheme.primary
        BlockTone.FAILED -> SimDanger
        BlockTone.RUNNING -> SimInfo
        BlockTone.NEUTRAL -> MaterialTheme.colorScheme.outline
    }
    // IntrinsicSize.Min so the colour bar is exactly as tall as the text beside
    // it — a fixed height would leave a stub next to a five-line block.
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        // A colour bar, not coloured body text: the transcript has to stay
        // readable for someone who cannot tell red from green.
        Box(Modifier.width(3.dp).fillMaxHeight().background(accent))
        Column(Modifier.padding(start = 8.dp)) {
            Text(block.title, style = MonoStyle.copy(fontWeight = FontWeight.SemiBold), color = accent)
            for (line in block.lines) Text(line, style = MonoStyle, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun ExpectationLine(form: SimForm) {
    Box(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            labExpectation(form),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/** AWT rather than Compose's clipboard: this app is desktop-only, and the
 *  transcript's whole point is being pasted into a bug report or a WhatsApp. */
private fun copyToClipboard(text: String) {
    runCatching {
        java.awt.Toolkit.getDefaultToolkit().systemClipboard
            .setContents(java.awt.datatransfer.StringSelection(text), null)
    }
}

// ── small shared pieces ───────────────────────────────────────────────────────

@Composable
private fun SectionCard(
    title: String,
    trailing: @Composable (() -> Unit)? = null,
    onTitleClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth().let { if (onTitleClick != null) it.clickable(onClick = onTitleClick) else it },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                trailing?.invoke()
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    error: String? = null,
) {
    Column(modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label, style = MaterialTheme.typography.bodySmall) },
            singleLine = true,
            enabled = enabled,
            isError = error != null,
            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let { ErrorLine(it) }
    }
}

@Composable
private fun ErrorLine(message: String) {
    Text(
        message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(start = 10.dp, top = 2.dp),
    )
}

@Composable
private fun Toggle(label: String, checked: Boolean, enabled: Boolean = true, onCheck: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheck, enabled = enabled)
        Text(label, style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else SimMuted)
    }
}

@Composable
private fun Picker(
    selected: String,
    options: List<String>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(selected, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                maxLines = 1)
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            for (option in options) {
                DropdownMenuItem(text = { Text(option) }, onClick = { open = false; onPick(option) })
            }
        }
    }
}
