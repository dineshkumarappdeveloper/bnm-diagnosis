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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import com.bnm.lab.instruments.driverFor
import com.bnm.lab.instruments.gatherLinkFacts
import com.bnm.lab.instruments.listSerialPorts
import com.bnm.lab.instruments.platformLinkEnvironment
import com.bnm.lab.instruments.serialSupported
import com.bnm.lab.staff.LocalStaffSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Instruments (I0) — connect lab analyzers so results enter themselves.
 *
 * Three panels: the configured analyzers (with live listener status and the
 * Link check verdict), the claim queue (frames that arrived without a
 * resolvable accession), and the raw traffic log (commissioning against real
 * hardware is 90% "what did the machine actually send"). Each analyzer's
 * editor carries the full Link check ([LinkCheckPanel]): the ordered
 * checklist that names the step that blocks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstrumentsScreen(
    engine: InstrumentEngine,
    onBack: () -> Unit,
    /** "Verified" pressed after remote support changed an analyzer's settings — the host writes the audit row. */
    onVerified: suspend (InstrumentConfig) -> Unit = {},
    /** PC facts for the Link check (addresses, firewall, ping, ports); the platform's own when null. Tests pass a fake. */
    environment: LinkEnvironment? = null,
) {
    val scope = rememberCoroutineScope()
    val signedIn = LocalStaffSession.current.signedIn
    val env = environment ?: remember { platformLinkEnvironment() }
    val instruments by engine.instrumentsFlow().collectAsState(emptyList())
    val statuses by engine.status.collectAsState()
    val unmatched by engine.unmatchedFlow().collectAsState(emptyList())
    // 300 rows so a chatty analyzer can't push a quiet one's newest rows out of the Link check; the list shows 100.
    val log by engine.logFlow(300).collectAsState(emptyList())

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
    fun queuedFor(inst: InstrumentConfig): Int = unmatched.count { it.instrument_id == inst.id }

    var editing by remember { mutableStateOf<InstrumentConfig?>(null) }
    var claiming by remember { mutableStateOf<String?>(null) }      // instrument_results.id
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

            if (unmatched.isNotEmpty()) {
                item {
                    Column(Modifier.pageWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Caption("Waiting for an order (${unmatched.size})")
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                            Column {
                                unmatched.forEachIndexed { i, row ->
                                    if (i > 0) HorizontalDivider(
                                        Modifier.padding(start = 14.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant,
                                    )
                                    Row(
                                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(Icons.Outlined.Downloading, contentDescription = null,
                                            tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(20.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                "Specimen ${row.specimen_id ?: "(not keyed)"}",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                            Text(
                                                niceTime(row.received_at),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        TextButton(onClick = { claiming = row.id }) { Text("Assign") }
                                        TextButton(onClick = {
                                            scope.launch { engine.discardUnmatched(row.id) }
                                        }) { Text("Discard", color = MaterialTheme.colorScheme.error) }
                                    }
                                }
                            }
                        }
                        Text(
                            "These results arrived without a matching accession. Assign one, " +
                            "or key the accession number on the analyzer next time.",
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

    claiming?.let { resultId ->
        var accession by remember(resultId) { mutableStateOf("") }
        var busy by remember(resultId) { mutableStateOf(false) }
        var err by remember(resultId) { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { if (!busy) claiming = null },
            title = { Text("Assign to an order") },
            text = {
                Column(Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Type or scan the order's accession number — the stored analyzer " +
                        "result will be applied to it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = accession,
                        // No case-forcing: accession lookup is exact-match and a
                        // lab may configure a lowercase prefix; the engine tries
                        // case variants itself.
                        onValueChange = { accession = it },
                        label = { Text("Accession (e.g. ACC-S1-00042)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    err?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy && accession.isNotBlank(),
                    onClick = {
                        busy = true; err = null
                        scope.launch {
                            engine.claimUnmatched(resultId, accession)
                                .onSuccess { message = it; claiming = null }
                                .onFailure { err = it.message ?: "Failed" }
                            busy = false
                        }
                    },
                ) { Text(if (busy) "Applying…" else "Apply results") }
            },
            dismissButton = {
                TextButton(onClick = { if (!busy) claiming = null }) { Text("Close") }
            },
        )
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

private fun niceTime(iso: String?): String =
    iso?.replace('T', ' ')?.take(16) ?: "—"

private fun Modifier.pageWidth(): Modifier = this.fillMaxWidth().widthIn(max = 760.dp)
