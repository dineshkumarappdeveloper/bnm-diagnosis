package com.bnm.lab.screens.machine

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cable
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bnm.lab.billing.PrintProfiles
import com.bnm.lab.instruments.InstrumentEngine
import com.bnm.lab.instruments.MachineReport
import com.bnm.lab.instruments.MachineReportDoc
import com.bnm.lab.instruments.QueuedFrame
import com.bnm.lab.lab.DiagnosisPrefs
import com.bnm.lab.lab.LocalLabRepository
import com.bnm.lab.report.LetterheadLogo
import com.bnm.lab.report.ReportPrefs
import com.bnm.lab.report.availablePrinters
import com.bnm.lab.report.openPdf
import com.bnm.lab.report.printPdf
import com.bnm.lab.report.printPdfSilently
import com.bnm.lab.report.writeLabReportPdf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Machine-only mode — the whole application, on one screen.
 *
 * A lab in this mode keys nothing: the analyzer already holds the patient's
 * name, age and sex, and the lab bills from a system that is not this one. So
 * the home screen is a list of what the analyzer has sent, newest first, and a
 * Print button. There is no registration, no order, no worklist and no
 * invoice, because in this mode none of those exist.
 *
 * WHY THE LIST IS THE CLAIM QUEUE. Every frame an analyzer sends that matches
 * no order already lands in `instrument_results` and is shown on the
 * Instruments screen as "Waiting for an order". In machine-only mode NOTHING
 * matches an order, by design — so that queue is simply the day's work, and
 * this screen is a different reading of a table that was already being filled.
 * Nothing new is stored and switching back to Full lab loses nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MachineHomeScreen(
    engine: InstrumentEngine,
    labName: String,
    onOpenSettings: () -> Unit,
    onOpenInstruments: () -> Unit,
    /** Wall clock "yyyy-MM-dd HH:mm" for the Reported stamp. */
    now: () -> String = ::machineNowStamp,
) {
    val allFrames by engine.queueFlow().collectAsState(emptyList())
    var showBackground by remember { mutableStateOf(false) }
    // Background counts are the analyzer talking about itself, not patients.
    // Hidden by default rather than deleted: a bench checking why a run looks
    // odd wants to see the blank, and the count on the toggle says they exist.
    val backgroundCount = allFrames.count { MachineReport.isBackgroundRun(it.frame) }
    val frames = if (showBackground) allFrames
        else allFrames.filterNot { MachineReport.isBackgroundRun(it.frame) }
    val prefs = remember { ReportPrefs() }
    val diagPrefs = remember { DiagnosisPrefs() }
    val scope = rememberCoroutineScope()

    // The letterhead logos live in the database, so they are READ here and
    // passed into every build. The ordered path gets them from the assembler;
    // this path has no assembler, which is exactly how they went missing.
    val labRepo = LocalLabRepository.current
    var logoLeft by remember { mutableStateOf<ByteArray?>(null) }
    var logoRight by remember { mutableStateOf<ByteArray?>(null) }
    LaunchedEffect(Unit) {
        logoLeft = LetterheadLogo.decode(labRepo.letterheadLogo(LetterheadLogo.Side.LEFT))
        logoRight = LetterheadLogo.decode(labRepo.letterheadLogo(LetterheadLogo.Side.RIGHT))
    }

    var autoPrint by remember { mutableStateOf(diagPrefs.autoPrint) }
    var autoPrinted by remember { mutableStateOf(setOf<String>()) }
    var autoPrintError by remember { mutableStateOf<String?>(null) }
    var autoPrintLast by remember { mutableStateOf<String?>(null) }
    var pickPrinter by remember { mutableStateOf(false) }

    var printing by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<QueuedFrame?>(null) }

    /**
     * Render and hand to the printer. [preview] opens the PDF instead, which
     * is what a bench uses to check the sheet before committing paper.
     */
    fun output(row: QueuedFrame, header: MachineReportDoc.Header, preview: Boolean) {
        printing = row.id
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val doc = MachineReportDoc.build(
                        frame = row.frame,
                        labName = labName,
                        header = header,
                        defaultReferrer = diagPrefs.machineReferrer,
                        letterheadLines = prefs.letterheadLines(),
                        logoLeftPng = logoLeft,
                        logoRightPng = logoRight,
                        mode = prefs.mode(),
                        headerMm = prefs.headerMm.toFloat(),
                        footerMm = prefs.footerMm.toFloat(),
                        accentRgb = prefs.accentRgb,
                        pagination = prefs.pagination(),
                        reported = now(),
                        generatedAt = now(),
                    ) ?: return@runCatching "This analyzer has no machine-only report layout."
                    val path = writeLabReportPdf(doc)
                    if (preview) openPdf(path) else printPdf(path)
                }.getOrElse { "Could not print: ${it.message ?: "unknown error"}" }
            }
            printing = null
            editing = null
            message = result.takeIf { it.isNotBlank() }
        }
    }

    /**
     * Auto-print: every result that arrives goes straight to the printer.
     *
     * Three rules make this safe enough to leave running unattended:
     *  · BACKGROUND COUNTS ARE NEVER PRINTED. They are the analyzer's own
     *    blank, not a patient, and a tray of them is how a lab decides the
     *    feature is broken and turns it off.
     *  · A WATERMARK, persisted, decides what is "new". Switching the toggle
     *    on stamps it with the current time, so enabling the feature does not
     *    print the whole day's backlog; keeping it across restarts means a
     *    reopened app does not reprint this morning's work.
     *  · ONE AT A TIME, oldest first. Firing five jobs at one printer
     *    concurrently is how pages come out interleaved.
     */
    LaunchedEffect(autoPrint, frames) {
        if (!autoPrint) return@LaunchedEffect
        val after = diagPrefs.autoPrintAfter
        val due = frames
            .filterNot { MachineReport.isBackgroundRun(it.frame) }
            .filter { it.receivedAt > after && it.id !in autoPrinted }
            .sortedBy { it.receivedAt }
        for (row in due) {
            val outcome = withContext(Dispatchers.Default) {
                runCatching {
                    val doc = MachineReportDoc.build(
                        frame = row.frame,
                        labName = labName,
                        defaultReferrer = diagPrefs.machineReferrer,
                        letterheadLines = prefs.letterheadLines(),
                        logoLeftPng = logoLeft,
                        logoRightPng = logoRight,
                        mode = prefs.mode(),
                        headerMm = prefs.headerMm.toFloat(),
                        footerMm = prefs.footerMm.toFloat(),
                        accentRgb = prefs.accentRgb,
                        pagination = prefs.pagination(),
                        reported = now(),
                        generatedAt = now(),
                    ) ?: return@runCatching "no machine-only layout for this analyzer"
                    // Silent, and to the REPORT profile's printer — the one
                    // the lab chose for A4 reports, not whatever the OS
                    // happens to default to on a PC that also has a label
                    // printer and a PDF writer attached.
                    printPdfSilently(
                        writeLabReportPdf(doc),
                        PrintProfiles.report.printerName.takeIf { it.isNotBlank() },
                    )
                }.getOrElse { it.message ?: "print failed" }
            }
            // Mark it done either way. A printer that is off should not make
            // the app retry the same sheet on every refresh for the rest of
            // the day — the row stays on screen with its Print button.
            autoPrinted = autoPrinted + row.id
            diagPrefs.autoPrintAfter = row.receivedAt
            // "Sent to <printer>" is the answer to "is this actually working?",
            // so it goes on the row, not only into the log.
            autoPrintLast = outcome
            if (!outcome.startsWith("Sent to")) autoPrintError = "Auto-print: $outcome"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(labName, style = MaterialTheme.typography.titleMedium)
                        Text(
                            when {
                                autoPrint && autoPrintLast != null -> "Machine only · $autoPrintLast"
                                autoPrint -> "Machine only · every result prints as it arrives"
                                else -> "Machine only · results print as the analyzer sends them"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Auto-print",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Switch(
                            checked = autoPrint,
                            onCheckedChange = { on ->
                                if (on && PrintProfiles.report.printerName.isBlank()) {
                                    // The ONE touch this feature gets: which
                                    // printer. Asked once, when the lab turns
                                    // it on, because after this nobody is
                                    // standing here — and a silent job to
                                    // whatever the OS defaults to is how a
                                    // report ends up on a label printer.
                                    pickPrinter = true
                                } else {
                                    autoPrint = on
                                    diagPrefs.autoPrint = on
                                    // Stamp the watermark ON ENABLE so the
                                    // backlog on screen is not printed at once.
                                    if (on) diagPrefs.autoPrintAfter = nowIsoForWatermark()
                                }
                            },
                            modifier = Modifier.padding(start = 6.dp, end = 4.dp),
                        )
                    }
                    IconButton(onClick = onOpenInstruments) {
                        Icon(Icons.Outlined.Cable, contentDescription = "Analyzer connection")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { pad ->
        if (frames.isEmpty() && backgroundCount > 0 && !showBackground) {
            Box(Modifier.fillMaxSize().padding(pad).padding(32.dp), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Only background counts so far", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "The analyzer has sent $backgroundCount background count(s) — its own blank " +
                            "check, not a patient. Run a sample to get a report.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp).widthIn(max = 420.dp),
                    )
                    TextButton(onClick = { showBackground = true }, modifier = Modifier.padding(top = 12.dp)) {
                        Text("Show them anyway")
                    }
                }
            }
        } else if (frames.isEmpty()) {
            // The empty state has to say what to DO. On a bench PC this screen
            // is the only thing on the monitor, and "nothing yet" with no next
            // step is indistinguishable from a broken cable.
            Box(Modifier.fillMaxSize().padding(pad).padding(32.dp), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Waiting for the analyzer", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Run a sample and press Send on the analyzer. Results appear here " +
                            "by themselves — nothing needs to be typed.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp).widthIn(max = 420.dp),
                    )
                    OutlinedButton(onClick = onOpenInstruments, modifier = Modifier.padding(top = 16.dp)) {
                        Text("Check the connection")
                    }
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(pad),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (backgroundCount > 0) {
                    item {
                        TextButton(onClick = { showBackground = !showBackground }) {
                            Text(
                                if (showBackground) "Hide $backgroundCount background count(s)"
                                else "Show $backgroundCount background count(s)",
                            )
                        }
                    }
                }
                items(frames, key = { it.id }) { row ->
                    ResultCard(
                        row = row,
                        busy = printing == row.id,
                        onPrint = { output(row, MachineReportDoc.Header(), preview = false) },
                        onPreview = { output(row, MachineReportDoc.Header(), preview = true) },
                        onEdit = { editing = row },
                    )
                }
            }
        }
    }

    editing?.let { row ->
        HeaderDialog(
            row = row,
            onDismiss = { editing = null },
            onPrint = { header -> output(row, header, preview = false) },
        )
    }

    // The one-time printer choice. After this the feature never asks again: a
    // result arrives, a sheet comes out, and the only button left is Print —
    // for a reprint.
    if (pickPrinter) {
        val printers = remember { availablePrinters() }
        AlertDialog(
            onDismissRequest = { pickPrinter = false },
            title = { Text("Which printer?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (printers.isEmpty()) {
                            "This computer reports no printers. Connect one, then turn auto-print on."
                        } else {
                            "Reports will print here automatically, with nothing to click. " +
                                "You can change it later in Settings - Print settings."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    printers.forEach { name ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                PrintProfiles.report.printerName = name
                                autoPrint = true
                                diagPrefs.autoPrint = true
                                diagPrefs.autoPrintAfter = nowIsoForWatermark()
                                pickPrinter = false
                            }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) { Text(name, style = MaterialTheme.typography.bodyLarge) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickPrinter = false }) { Text("Cancel") } },
        )
    }

    autoPrintError?.let { text ->
        AlertDialog(
            onDismissRequest = { autoPrintError = null },
            title = { Text("Auto-print") },
            text = {
                Text(
                    "$text\n\nThe result is safe — it is still in the list and can be " +
                        "printed with its Print button.",
                )
            },
            confirmButton = { TextButton(onClick = { autoPrintError = null }) { Text("OK") } },
        )
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text("Print") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
        )
    }
}

@Composable
private fun ResultCard(
    row: QueuedFrame,
    busy: Boolean,
    onPrint: () -> Unit,
    onPreview: () -> Unit,
    onEdit: () -> Unit,
) {
    val frame = row.frame
    val built = remember(row.id) { MachineReport.build(frame) }
    Card(
        Modifier.fillMaxWidth().clickable(enabled = !busy, onClick = onEdit),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        frame.patientName?.takeIf { it.isNotBlank() } ?: "No name from the analyzer",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val id = frame.specimenId?.takeIf { it.isNotBlank() }
                        ?: frame.patientId?.takeIf { it.isNotBlank() }
                    Text(
                        listOfNotNull(
                            id?.let { "Sample $it" },
                            MachineReportDoc.ageSex(frame, MachineReportDoc.Header())
                                .takeIf { it.isNotBlank() },
                            row.receivedAt.takeIf { it.isNotBlank() },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (busy) {
                    CircularProgressIndicator(Modifier.padding(end = 12.dp))
                } else {
                    OutlinedButton(onClick = onPreview) { Text("Preview") }
                    Button(onClick = onPrint, modifier = Modifier.padding(start = 8.dp)) {
                        Icon(Icons.Outlined.Print, contentDescription = null)
                        Text("Print", modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
            // The headline numbers, so two samples run back to back can be told
            // apart without opening either.
            val headline = row.headline.joinToString("   ") { "${it.first} ${it.second}" }
            if (headline.isNotBlank()) {
                Text(headline, style = MaterialTheme.typography.bodyMedium)
            }
            built?.let { b ->
                // Say it on the card, not in a log: a machine-only bench has no
                // second screen to check, and a value printed in the analyzer's
                // own unit is the one thing the operator must not miss.
                if (b.unconverted.isNotEmpty()) {
                    Text(
                        "Printed in the analyzer's own units: " +
                            b.unconverted.joinToString { it.param } +
                            ". Check the analyzer's unit settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                // The graph note earns its place on the card, not in a log: it
                // names an analyzer setting the bench can change in a minute.
                b.graphNote?.let { note ->
                    Text(
                        note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (b.missing.isNotEmpty()) {
                    Text(
                        "${b.missing.size} parameter(s) not sent by the analyzer",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } ?: Text(
                "This analyzer has no machine-only report layout.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * The only typing in this mode, and all of it optional: a name the bench keyed
 * wrongly, an age the analyzer never sent, and the referring doctor, which HL7
 * has no field for. Print works with every box empty.
 */
@Composable
private fun HeaderDialog(
    row: QueuedFrame,
    onDismiss: () -> Unit,
    onPrint: (MachineReportDoc.Header) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var ageSex by remember { mutableStateOf("") }
    var referrer by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Before printing") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Everything here is optional — leave it blank to print exactly " +
                        "what the analyzer sent.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Patient name") },
                    placeholder = { Text(row.frame.patientName.orEmpty().ifBlank { "not sent" }) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = ageSex, onValueChange = { ageSex = it },
                    label = { Text("Age / Sex") },
                    placeholder = {
                        Text(
                            MachineReportDoc.ageSex(row.frame, MachineReportDoc.Header())
                                .ifBlank { "not sent" },
                        )
                    },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = referrer, onValueChange = { referrer = it },
                    label = { Text("Referred by") },
                    placeholder = { Text("the analyzer never sends this") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                onPrint(
                    MachineReportDoc.Header(
                        patientName = name, ageSex = ageSex, referrer = referrer,
                    ),
                )
            }) { Text("Print") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** "yyyy-MM-dd HH:mm" in the bench PC's own zone — what the sheet stamps. */
internal fun machineNowStamp(): String {
    val t = kotlin.time.Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    fun p(n: Int) = n.toString().padStart(2, '0')
    return "${t.year}-${p(t.monthNumber)}-${p(t.dayOfMonth)} ${p(t.hour)}:${p(t.minute)}"
}

/**
 * The watermark stamp. Matches the shape of `QueuedFrame.receivedAt` (a
 * SQLite ISO-8601 string) so the two can be compared directly — anything
 * cleverer would only be a second format to keep in step with the first.
 */
internal fun nowIsoForWatermark(): String = kotlin.time.Clock.System.now().toString()
