package com.bnm.lab.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Cable
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Downloading
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bnm.lab.instruments.ClaimCandidate
import com.bnm.lab.instruments.ClaimCandidates
import com.bnm.lab.instruments.INSTRUMENT_DRIVERS
import com.bnm.lab.instruments.InstrumentConfig
import com.bnm.lab.instruments.InstrumentEngine
import com.bnm.lab.instruments.InstrumentTransport
import com.bnm.lab.instruments.LinkCheck
import com.bnm.lab.instruments.LinkCheckReport
import com.bnm.lab.instruments.LinkEnvironment
import com.bnm.lab.instruments.LinkFacts
import com.bnm.lab.instruments.LinkFactsSnapshot
import com.bnm.lab.instruments.LinkLogRow
import com.bnm.lab.instruments.LinkLogSummaries
import com.bnm.lab.instruments.QueuedFrame
import com.bnm.lab.instruments.driverFor
import com.bnm.lab.instruments.filterForClaim
import com.bnm.lab.instruments.gatherLinkFacts
import com.bnm.lab.instruments.listSerialPorts
import com.bnm.lab.instruments.narrowsToOneNonAccession
import com.bnm.lab.instruments.platformLinkEnvironment
import com.bnm.lab.instruments.scanTargetFor
import com.bnm.lab.instruments.serialSupported
import com.bnm.lab.print.AnalyzerWorksheet
import com.bnm.lab.print.buildAnalyzerWorksheet
import com.bnm.lab.print.layoutAnalyzerWorksheetA4
import com.bnm.lab.print.printA4
import com.bnm.lab.staff.LabPermission
import com.bnm.lab.staff.LocalStaffSession
import com.bnm.lab.staff.allows
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Instruments (I0) — connect lab analyzers so results enter themselves.
 *
 * Three panels: the configured analyzers (with live listener status and the
 * Link check verdict), the claim queue (frames that arrived without a
 * resolvable accession), and the raw traffic log (commissioning against real
 * hardware is 90% "what did the machine actually send"). Each analyzer's
 * editor carries the full Link check ([LinkCheckPanel]): the ordered
 * checklist that names the step that blocks.
 *
 * The claim queue is where a run whose order was never registered ends up, and
 * it has three ways out: assign it to an order (picked from a list, not typed
 * from memory), print it as an [AnalyzerWorksheet] for the bench while the
 * order is still missing, or discard it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstrumentsScreen(
    engine: InstrumentEngine,
    /** The licence's lab name — heads the worksheet, as it heads a report. */
    labName: String,
    onBack: () -> Unit,
    /** "Verified" pressed after remote support changed an analyzer's settings — the host writes the audit row. */
    onVerified: suspend (InstrumentConfig) -> Unit = {},
    /** PC facts for the Link check (addresses, firewall, ping, ports); the platform's own when null. Tests pass a fake. */
    environment: LinkEnvironment? = null,
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val signedIn = LocalStaffSession.current.signedIn
    // One rule for every door onto a queued result — assign, print, discard.
    // The licence is NOT part of it: a lapsed seat is read-only for new work and
    // must keep printing and exporting what it already holds.
    val canWorkResults = signedIn.allows(LabPermission.RESULTS)
    val env = environment ?: remember { platformLinkEnvironment() }
    // remember(engine), not a fresh call per recomposition: collectAsState keys
    // its subscription on the FLOW INSTANCE, so a new one each pass cancels and
    // re-establishes the query — and re-decodes every queued payload — on every
    // keystroke in the picker's search box. With the frames a Mispa sends
    // (128-point histograms, base64 scattergrams) that is a visible stutter.
    val instruments by remember(engine) { engine.instrumentsFlow() }.collectAsState(emptyList())
    val statuses by engine.status.collectAsState()
    val queue by remember(engine) { engine.queueFlow() }.collectAsState(emptyList())
    // 300 rows so a chatty analyzer can't push a quiet one's newest rows out of the Link check; the list shows 100.
    val log by remember(engine) { engine.logFlow(300) }.collectAsState(emptyList())

    // ── Link check: the PC facts every analyzer shares (addresses, serial
    // ports, firewall per port, ping per analyzer host) are read once every
    // 10 s, or now on "Check again"; status counters and log rows are live. ──
    var snapshot by remember { mutableStateOf<LinkFactsSnapshot?>(null) }
    var refreshKey by remember { mutableStateOf(0) }
    val factsKey = instruments.map { Triple(it.transport, it.tcpPort, it.analyzerHost) }
    LaunchedEffect(refreshKey, factsKey) {
        while (isActive) {
            snapshot = gatherLinkFacts(env, instruments)
            delay(10_000)
        }
    }
    fun factsFor(inst: InstrumentConfig): LinkFacts = snapshot?.forInstrument(inst) ?: LinkFactsSnapshot.pending(env)
    fun logsFor(inst: InstrumentConfig): LinkLogSummaries = LinkLogSummaries.of(
        log.filter { it.instrument_id == inst.id }.map { LinkLogRow(it.direction, it.summary, it.created_at) })
    // The queue itself, not the session counter: claiming or discarding a result
    // removes it here, while framesUnmatched only ever grows (listUnmatched caps
    // at 50, which is far past the point where the count stops being the news).
    fun queuedFor(inst: InstrumentConfig): Int = queue.count { it.instrumentId == inst.id }
    /** The analyzer's configured name, or the engine's own fallback for a row
     *  whose instrument has since been removed. */
    fun instrumentName(row: QueuedFrame): String =
        instruments.firstOrNull { it.id == row.instrumentId }?.name ?: "Analyzer"

    var editing by remember { mutableStateOf<InstrumentConfig?>(null) }
    var claiming by remember { mutableStateOf<QueuedFrame?>(null) }
    var printing by remember { mutableStateOf<QueuedFrame?>(null) }
    var discarding by remember { mutableStateOf<QueuedFrame?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Instruments") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Column(Modifier.pageWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Caption("Analyzers")
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column {
                            if (instruments.isEmpty()) {
                                Text(
                                    "No analyzers yet. Add one, pick how it's connected, and " +
                                        "results (histograms included) will enter themselves — " +
                                        "you only verify and approve.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(16.dp),
                                )
                            }
                            instruments.forEachIndexed { i, inst ->
                                if (i > 0) HorizontalDivider(
                                    Modifier.padding(start = 48.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                )
                                InstrumentRow(
                                    inst = inst,
                                    statusLine = statusLine(statuses[inst.id]),
                                    verdict = LinkCheck.evaluate(inst, statuses[inst.id], logsFor(inst),
                                        factsFor(inst), queuedFor(inst)),
                                    onClick = { editing = inst },
                                    onToggle = { on ->
                                        scope.launch { engine.saveInstrument(inst.copy(enabled = on)) }
                                    },
                                )
                                if (inst.verifyPending) {
                                    VerifyPendingRow(
                                        // Any signed-in staff may confirm — the check is at the bench,
                                        // not a rights question.
                                        enabled = signedIn != null,
                                        onVerified = {
                                            scope.launch {
                                                engine.setVerifyPending(inst.id, false)
                                                onVerified(inst)
                                                message = "${inst.name} verified — results apply to orders again"
                                            }
                                        },
                                    )
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable {
                                        editing = InstrumentConfig(
                                            id = "", name = "", driver = INSTRUMENT_DRIVERS.first().key,
                                            transport = if (serialSupported()) InstrumentTransport.SERIAL
                                            else InstrumentTransport.TCP,
                                        )
                                    }
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Outlined.Add, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                Text("Add analyzer", color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                    message?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            if (queue.isNotEmpty()) {
                item {
                    Column(Modifier.pageWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Caption("Waiting for an order (${queue.size})")
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                            Column {
                                queue.forEachIndexed { i, row ->
                                    if (i > 0) HorizontalDivider(
                                        Modifier.padding(start = 14.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant,
                                    )
                                    ClaimQueueRow(
                                        row = row,
                                        instrumentName = instrumentName(row),
                                        enabled = canWorkResults,
                                        onAssign = { claiming = row },
                                        onPrint = { printing = row },
                                        onDiscard = { discarding = row },
                                    )
                                }
                            }
                        }
                        Text(
                            if (canWorkResults)
                                "These results arrived without a matching accession. Assign one, print " +
                                    "the worksheet for the bench while the order is still missing, or key " +
                                    "the accession number on the analyzer next time."
                            else LabPermission.RESULTS.explanation,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                Column(Modifier.pageWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Caption("Traffic log")
                        TextButton(onClick = { scope.launch { engine.clearLog() } }, enabled = log.isNotEmpty()) {
                            Icon(Icons.Outlined.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text(" Clear")
                        }
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column {
                            if (log.isEmpty()) {
                                Text(
                                    "Nothing yet. Every byte an analyzer sends shows up here — " +
                                        "your first stop when a machine goes quiet.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(16.dp),
                                )
                            }
                            log.take(100).forEachIndexed { i, row ->
                                if (i > 0) HorizontalDivider(
                                    Modifier.padding(start = 14.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                )
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        if (row.direction == "error") Icons.Outlined.ErrorOutline else Icons.Outlined.Info,
                                        contentDescription = null,
                                        tint = if (row.direction == "error") MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(row.summary, style = MaterialTheme.typography.bodySmall,
                                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            "${row.instrument_name ?: "—"} · ${niceTime(row.created_at)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { cfg ->
        InstrumentEditDialog(
            initial = cfg,
            linkCheck = if (cfg.id.isBlank()) null else {
                {
                    // The SAVED row (a flag may change while the dialog is open), never the unsaved edits.
                    val saved = instruments.firstOrNull { it.id == cfg.id } ?: cfg
                    LinkCheckPanel(
                        cfg = saved,
                        status = statuses[saved.id],
                        logs = logsFor(saved),
                        facts = factsFor(saved),
                        queued = queuedFor(saved),
                        environment = env,
                        engine = engine,
                        onCheckAgain = { refreshKey++ },
                        onMessage = { message = it },
                    )
                }
            },
            onDismiss = { editing = null },
            onSave = { updated ->
                editing = null
                scope.launch {
                    engine.saveInstrument(updated)
                    message = "Saved — listener restarted"
                }
            },
            onDelete = if (cfg.id.isNotBlank()) {
                {
                    editing = null
                    scope.launch { engine.deleteInstrument(cfg.id); message = "Analyzer removed" }
                }
            } else null,
        )
    }

    claiming?.let { row ->
        var candidates by remember(row.id) { mutableStateOf<ClaimCandidates?>(null) }
        var query by remember(row.id) { mutableStateOf("") }
        var busy by remember(row.id) { mutableStateOf(false) }
        var err by remember(row.id) { mutableStateOf<String?>(null) }
        // Re-read when the operator types, because the search lives in SQL: the
        // unfiltered list is only the newest `limit` orders, and a lab that
        // registers a hundred a day pushes this morning's out of it by the
        // afternoon. Filtering a fixed prefetch would answer "nothing matches"
        // for a patient who is plainly in the system, and the queue is exactly
        // where duplicate registrations come from.
        //
        // Not a live feed either way — nothing re-ranks under a finger that is
        // already moving towards a row. It re-reads when, and only when, the
        // operator changes the query, after they stop typing.
        LaunchedEffect(row.id, query) {
            if (query.isNotEmpty()) delay(250)      // debounce: one read per pause, not per keystroke
            engine.claimCandidates(row.id, query)
                .onSuccess { candidates = it; err = null }
                .onFailure { err = it.message ?: "Could not read the orders" }
        }
        fun assign(accession: String) {
            busy = true; err = null
            scope.launch {
                engine.claimUnmatched(row.id, accession, by = signedIn)
                    .onSuccess { message = it; claiming = null }
                    .onFailure { err = it.message ?: "Failed" }
                busy = false
            }
        }
        ClaimPickerDialog(
            queued = row,
            instrumentName = instrumentName(row),
            candidates = candidates,
            query = query,
            onQuery = { query = it },
            busy = busy,
            error = err,
            onAssign = ::assign,
            onDismiss = { if (!busy) claiming = null },
        )
    }

    printing?.let { row ->
        val worksheet = remember(row.id, labName) {
            buildAnalyzerWorksheet(labName, row, instrumentName(row))
        }
        var busy by remember(row.id) { mutableStateOf(false) }
        var status by remember(row.id) { mutableStateOf<String?>(null) }
        WorksheetDialog(
            worksheet = worksheet,
            busy = busy,
            status = status,
            onPrint = {
                busy = true
                scope.launch {
                    // The report's own print path — same A4 page, same platform
                    // print dialog. Nothing else happens to the RESULT: no
                    // archive file, no share token, and the row stays in the
                    // queue, because none of that would be true of this sheet.
                    // The trail is the exception — a page of a patient's numbers
                    // leaving the building is an event worth being able to name.
                    val outcome = withContext(Dispatchers.Default) { printA4(layoutAnalyzerWorksheetA4(worksheet)) }
                    status = outcome
                    // Only a page that actually went out is logged — the engine
                    // reads printA4's verdict rather than assume it.
                    engine.logWorksheetPrinted(row.id, outcome, by = signedIn)
                    busy = false
                }
            },
            onCopy = {
                clipboard.setText(AnnotatedString(worksheet.plainText()))
                status = "Copied — paste it wherever the bench needs it."
            },
            onDismiss = { if (!busy) printing = null },
        )
    }

    discarding?.let { row ->
        AlertDialog(
            onDismissRequest = { discarding = null },
            title = { Text("Discard this result?") },
            text = {
                Column(Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Specimen ${row.specimenId ?: AnalyzerWorksheet.NO_SPECIMEN} · " +
                            "${row.paramCount} parameters from ${instrumentName(row)}, " +
                            niceTime(row.receivedAt) + ".",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "It leaves the queue and cannot be assigned to an order afterwards. " +
                            "The sample would have to be run again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        engine.discardUnmatched(row.id, by = signedIn)
                        message = "Result discarded"
                    }
                    discarding = null
                }) { Text("Discard", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { discarding = null }) { Text("Keep it") } },
        )
    }
}

// ── the claim queue ──

/**
 * One waiting result.
 *
 * Specimen id, instrument, time, parameter count — and the headline values,
 * because a bench that ran four samples in ten minutes cannot tell four
 * "Specimen —" rows apart, and the haemoglobin usually can.
 */
@Composable
internal fun ClaimQueueRow(
    row: QueuedFrame,
    instrumentName: String,
    /** False for a seat with no results permission: the row still reads, nothing acts. */
    enabled: Boolean,
    onAssign: () -> Unit,
    onPrint: () -> Unit,
    onDiscard: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Downloading, contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                row.specimenId?.let { "Specimen $it" } ?: "No specimen id keyed",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "$instrumentName · ${niceTime(row.receivedAt)} · ${row.paramCount} parameters",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            row.headline.takeIf { it.isNotEmpty() }?.let { values ->
                Text(
                    values.joinToString("  ·  ") { (k, v) -> "$k $v" },
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        TextButton(onClick = onAssign, enabled = enabled) { Text("Assign") }
        TextButton(onClick = onPrint, enabled = enabled) { Text("Print") }
        TextButton(onClick = onDiscard, enabled = enabled) {
            Text("Discard", color = if (enabled) MaterialTheme.colorScheme.error else Color.Unspecified)
        }
    }
}

/**
 * Pick the order this run belongs to.
 *
 * Typing was the whole interface before, and it asked the operator to remember
 * an accession number for a patient standing in front of them. Now the orders
 * are on screen with their patients, best guess first, and typing is what
 * NARROWS the list — except for a barcode scanner, which types an accession and
 * sends Enter, and still assigns in one motion ([scanTargetFor]).
 */
@Composable
internal fun ClaimPickerDialog(
    queued: QueuedFrame,
    instrumentName: String,
    /** Null while the orders are still being read. */
    candidates: ClaimCandidates?,
    query: String,
    onQuery: (String) -> Unit,
    busy: Boolean,
    error: String?,
    onAssign: (accession: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val open = candidates?.open.orEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Assign to an order") },
        text = {
            ClaimPickerBody(queued, instrumentName, candidates, query, onQuery, busy, error, onAssign)
        },
        confirmButton = {
            TextButton(
                enabled = !busy && query.isNotBlank(),
                onClick = { scanTargetFor(query, open)?.let(onAssign) },
            ) { Text(if (busy) "Applying…" else "Assign typed") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Close") } },
    )
}

/** The picker's content — the dialog shell is Material's, this is ours. */
@Composable
internal fun ClaimPickerBody(
    queued: QueuedFrame,
    instrumentName: String,
    candidates: ClaimCandidates?,
    query: String,
    onQuery: (String) -> Unit,
    busy: Boolean,
    error: String?,
    onAssign: (accession: String) -> Unit,
) {
    val open = candidates?.open.orEmpty()
    val shown = open.filterForClaim(query)
    Column(Modifier.widthIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Specimen ${queued.specimenId ?: AnalyzerWorksheet.NO_SPECIMEN} · " +
                "${queued.paramCount} parameters from $instrumentName. " +
                "Pick the order, or scan its barcode.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = query,
            // No case-forcing: a lab may configure a lowercase prefix, and the
            // engine tries the case variants itself.
            onValueChange = onQuery,
            label = { Text("Search accession, patient or phone — or scan") },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            // The bench scanner's Enter lands here — and ONLY an accession is
            // assigned by it (see scanTargetFor). A name that happens to leave
            // one row standing is a narrowed list, not a decision.
            keyboardActions = KeyboardActions(onDone = { scanTargetFor(query, open)?.let(onAssign) }),
            modifier = Modifier.fillMaxWidth(),
        )
        when {
            candidates == null && error == null ->
                Text("Reading the orders…", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            // Before either "nothing is here" sentence: on a failed read the app
            // knows NOTHING about the worklist, and telling the bench to go and
            // register the patient again on the strength of a read that did not
            // happen is how the same sample gets billed twice. The error line at
            // the foot of the dialog carries the actual message.
            candidates == null ->
                Unit
            open.isEmpty() && query.isBlank() ->
                Text(
                    "No order is waiting for results. If this sample was never registered, " +
                        "register it first — the result keeps waiting here until you do.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            shown.isEmpty() ->
                Text("Nothing matches “${query.trim()}”.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    itemsIndexed(shown) { i, c ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        CandidateRow(c, enabled = !busy, onClick = { onAssign(c.accessionNo) })
                    }
                }
            }
        }
        // Enter is a reflex in a search box, and it no longer assigns on a name
        // that narrows to one row. Say which key does what rather than let it
        // look broken.
        if (narrowsToOneNonAccession(query, open)) {
            Text("One order left — tap it to assign. Enter takes an accession or a scan.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Said out loud, never silently filtered: an operator who cannot find
        // the order concludes it is not in the app and registers a duplicate.
        candidates?.lockedNote?.let {
            Text(it, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Same reason: a capped list must never read as "not in the app".
        candidates?.windowNote?.let {
            Text(it, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        error?.let {
            Text(it, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error)
        }
    }
}

/** One pickable order: who it is, what was ordered, and why it is ranked here. */
@Composable
private fun CandidateRow(c: ClaimCandidate, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(c.accessionNo, style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                Text(c.status.replace('_', ' '), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "${c.patientName} · ${c.ageSex}" + (c.phone?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            c.tests.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                "${niceTime(c.registeredAt)} · ${c.matchNote}",
                style = MaterialTheme.typography.labelSmall,
                color = if (c.matched > 0) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The worksheet, on screen before it is on paper — and with the same warning
 * the paper carries, because this preview is the copy most operators will
 * actually read.
 */
@Composable
internal fun WorksheetDialog(
    worksheet: AnalyzerWorksheet,
    busy: Boolean,
    status: String?,
    onPrint: () -> Unit,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(AnalyzerWorksheet.TITLE) },
        text = { WorksheetBody(worksheet, status) },
        confirmButton = {
            Row {
                TextButton(onClick = onCopy, enabled = !busy) { Text("Copy values") }
                TextButton(onClick = onPrint, enabled = !busy) { Text(if (busy) "Printing…" else "Print") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Close") } },
    )
}

/**
 * What the worksheet says, on screen. The warning is first and in the error
 * colour for the same reason it is banded on the paper: this preview is the
 * copy most operators will actually read.
 */
@Composable
internal fun WorksheetBody(worksheet: AnalyzerWorksheet, status: String? = null) {
    Column(Modifier.widthIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(8.dp)) {
            Text(
                AnalyzerWorksheet.DISCLAIMER,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(10.dp),
            )
        }
        Text(
            "Specimen ${worksheet.specimenLabel}\n" +
                "${worksheet.instrumentName} · ${worksheet.driverLabel}\n" +
                "Received ${worksheet.receivedAt}" +
                (worksheet.runMode?.let { "\nRun mode $it" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            LazyColumn(Modifier.heightIn(max = 260.dp).padding(10.dp)) {
                itemsIndexed(worksheet.lines) { _, l ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(l.param, Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        // Value, unit and the analyzer's own flag, all in one ink:
                        // no reference range on this page, so no verdict either.
                        Text(
                            l.value + (if (l.unit.isBlank()) "" else " ${l.unit}") +
                                (l.flag?.takeIf { it.isNotBlank() }?.let { "  [$it]" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
        status?.let {
            Text(it, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
        }
    }
}

// ── rows & bits ──

/**
 * Shown under an analyzer whose driver or param map BNM support changed
 * remotely. Until "Verified" is pressed every result from it waits in the
 * claim queue (see InstrumentEngine.VERIFY_PENDING_REASON), so a mapping
 * typed from the office cannot land on a patient unchecked.
 */
@Composable
private fun VerifyPendingRow(enabled: Boolean, onVerified: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(VERIFY_AMBER_BG)
            .padding(start = 60.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Verified, contentDescription = null, tint = VERIFY_AMBER_FG, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "Settings changed by BNM support — Verified?",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = VERIFY_AMBER_FG,
            )
            Text(
                "Run one known sample and check its values against the order. Until then, " +
                    "results from this analyzer wait in \"Waiting for an order\" instead of " +
                    "filling in automatically." +
                    if (!enabled) " Sign in to press Verified." else "",
                style = MaterialTheme.typography.bodySmall,
                color = VERIFY_AMBER_FG,
            )
        }
        TextButton(onClick = onVerified, enabled = enabled) {
            Text("Verified", color = VERIFY_AMBER_FG)
        }
    }
}

/** Amber, fixed rather than a theme role: it must read as "attention" in both themes. */
private val VERIFY_AMBER_BG = Color(0xFFFFF3CD)
private val VERIFY_AMBER_FG = Color(0xFF7A4F00)

@Composable
private fun InstrumentRow(
    inst: InstrumentConfig,
    statusLine: String,
    verdict: LinkCheckReport,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(34.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Cable, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(inst.name, style = MaterialTheme.typography.bodyLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            // The Link check verdict — green linked, amber waiting, red blocked at a named step.
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(7.dp).background(linkColor(verdict.verdictState), CircleShape))
                Text(
                    verdict.verdict,
                    style = MaterialTheme.typography.bodySmall,
                    color = linkColor(verdict.verdictState),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                "${driverFor(inst.driver)?.label ?: inst.driver} · $statusLine",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Switch(checked = inst.enabled, onCheckedChange = onToggle)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstrumentEditDialog(
    initial: InstrumentConfig,
    /** The Link check for a SAVED analyzer (null while adding one). */
    linkCheck: (@Composable () -> Unit)?,
    onDismiss: () -> Unit,
    onSave: (InstrumentConfig) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var name by remember { mutableStateOf(initial.name) }
    var driver by remember { mutableStateOf(initial.driver) }
    var transport by remember { mutableStateOf(initial.transport) }
    var serialPort by remember { mutableStateOf(initial.serialPort ?: "") }
    var baud by remember { mutableStateOf(initial.baud.toString()) }
    var tcpPort by remember { mutableStateOf(initial.tcpPort?.toString() ?: "5500") }
    var analyzerHost by remember { mutableStateOf(initial.analyzerHost ?: "") }
    var ports by remember { mutableStateOf(listSerialPorts()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id.isBlank()) "Add analyzer" else "Edit analyzer") },
        text = {
            Column(
                Modifier.widthIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name (e.g. Mispa Count X — bench 1)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Text("Machine", style = MaterialTheme.typography.labelLarge)
                INSTRUMENT_DRIVERS.forEach { d ->
                    FilterChip(
                        selected = driver == d.key,
                        onClick = {
                            driver = d.key
                            if (name.isBlank()) name = d.label
                            if (d.tcpOnly) transport = InstrumentTransport.TCP
                        },
                        label = { Text(d.label) },
                    )
                }
                driverFor(driver)?.let {
                    Text(it.detail, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("Connection", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = transport == InstrumentTransport.SERIAL,
                        onClick = { transport = InstrumentTransport.SERIAL },
                        label = { Text("Serial (RS-232)") },
                        enabled = serialSupported() && driverFor(driver)?.tcpOnly != true,
                    )
                    FilterChip(
                        selected = transport == InstrumentTransport.TCP,
                        onClick = { transport = InstrumentTransport.TCP },
                        label = { Text("Network (TCP)") },
                    )
                }
                if (driverFor(driver)?.tcpOnly == true) {
                    Text("This analyzer talks HL7 over the network and waits for the app's acknowledgement — serial has no reply path.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (transport == InstrumentTransport.SERIAL) {
                    if (!serialSupported()) {
                        Text("Serial ports are only available on the lab PC (desktop app).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("Port", style = MaterialTheme.typography.labelLarge)
                            TextButton(onClick = { ports = listSerialPorts() }) { Text("Refresh") }
                        }
                        if (ports.isEmpty()) {
                            Text("No serial ports found — plug in the USB-to-RS232 cable.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        ports.forEach { p ->
                            FilterChip(selected = serialPort == p, onClick = { serialPort = p },
                                label = { Text(p, fontFamily = FontFamily.Monospace) })
                        }
                        OutlinedTextField(
                            value = baud,
                            onValueChange = { baud = it.filter { c -> c.isDigit() } },
                            label = { Text("Baud rate") },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else {
                    OutlinedTextField(
                        value = tcpPort,
                        onValueChange = { tcpPort = it.filter { c -> c.isDigit() } },
                        label = { Text("Listen port") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    Text("This app listens on the port; point the analyzer (or a test " +
                        "sender) at this PC's IP address.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = analyzerHost,
                        onValueChange = { analyzerHost = it.trim() },
                        label = { Text("Analyzer IP address (optional)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    Text("Only used by the Link check below to ping the analyzer from this PC — " +
                        "the analyzer still connects to this PC, not the other way round.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (linkCheck != null) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text("Link check", style = MaterialTheme.typography.labelLarge)
                    Text("Which step is blocking, live. Edits above apply after Save & connect.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    linkCheck()
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() &&
                    (transport == InstrumentTransport.TCP && tcpPort.toIntOrNull() in 1..65535 ||
                        transport == InstrumentTransport.SERIAL && serialPort.isNotBlank()),
                onClick = {
                    onSave(initial.copy(
                        name = name,
                        driver = driver,
                        transport = transport,
                        serialPort = serialPort.ifBlank { null },
                        baud = baud.toIntOrNull() ?: (driverFor(driver)?.defaultBaud ?: 115200),
                        tcpPort = tcpPort.toIntOrNull(),
                        enabled = true,
                        analyzerHost = analyzerHost.ifBlank { null },
                    ))
                },
            ) { Text("Save & connect") }
        },
        dismissButton = {
            Row {
                onDelete?.let {
                    TextButton(onClick = it) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun Caption(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp),
    )
}

@Composable
private fun statusLine(s: com.bnm.lab.instruments.InstrumentStatus?): String = when {
    s == null -> "Starting…"
    s.lastFrameAt != null -> "${s.detail ?: s.state} · last result ${niceTime(s.lastFrameAt)}"
    else -> s.detail ?: s.state
}

/**
 * An ISO instant as the bench reads a clock — local time, not the stored UTC.
 *
 * The stamps are written as UTC instants and used to be shown by chopping the
 * string, which put "16:33" on a run the lab took at 22:03. Harmless on its own;
 * not harmless beside the worksheet, which prints the same moment properly
 * converted, so one screen showed one event at two times. Unparseable input
 * falls back to the old truncation rather than losing the stamp.
 */
private fun niceTime(iso: String?): String {
    if (iso == null) return "—"
    return runCatching {
        val t = kotlin.time.Instant.parse(iso).toLocalDateTime(TimeZone.currentSystemDefault())
        "${t.date} ${t.hour.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')}"
    }.getOrElse { iso.replace('T', ' ').take(16) }
}

private fun Modifier.pageWidth(): Modifier = this.fillMaxWidth().widthIn(max = 760.dp)
