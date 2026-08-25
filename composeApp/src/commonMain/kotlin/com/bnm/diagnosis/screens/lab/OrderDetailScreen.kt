package com.bnm.diagnosis.screens.lab

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bnm.diagnosis.billing.BillingPrefs
import com.bnm.diagnosis.components.StatusBadge
import com.bnm.diagnosis.lab.LabOrder
import com.bnm.diagnosis.lab.LabOrderTest
import com.bnm.diagnosis.lab.LabRepository
import com.bnm.diagnosis.lab.LabResult
import com.bnm.diagnosis.lab.LabStatus
import com.bnm.diagnosis.lab.LabTest
import com.bnm.diagnosis.lab.LocalLabRepository
import com.bnm.diagnosis.lab.Patient
import com.bnm.diagnosis.lab.RefRange
import com.bnm.diagnosis.lab.Referrer
import com.bnm.diagnosis.lab.TestParameter
import com.bnm.diagnosis.print.BtPrinter
import com.bnm.diagnosis.print.EscPos
import com.bnm.diagnosis.print.printToNetworkPrinter
import com.bnm.diagnosis.print.renderLabReport
import com.bnm.diagnosis.report.ReportDoc
import com.bnm.diagnosis.report.ReportPrefs
import com.bnm.diagnosis.report.buildReportDoc
import com.bnm.diagnosis.report.openPdf
import com.bnm.diagnosis.report.printPdf
import com.bnm.diagnosis.report.writeLabReportPdf
import com.bnm.diagnosis.staff.LocalStaffSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Statuses in which result entry is still open (mirrors the repo's guard). */
private val ENTRY_OPEN = setOf(LabStatus.REGISTERED, LabStatus.COLLECTED, LabStatus.IN_PROGRESS, LabStatus.ENTERED)

/** Below this the table collapses to two-line rows (phone / split desktop pane). */
private const val WIDE_MIN_DP = 900

// Grid geometry — the header row and every data row read the SAME numbers, so
// the columns line up exactly like the printed report they mirror.
private const val W_PARAM = 2.4f
private const val W_RANGE = 1.5f
private val COL_RESULT = 148.dp
private val COL_UNIT = 78.dp
private val COL_FLAG = 108.dp

/**
 * One order's workbench: a compact patient header + entry progress, then a
 * DENSE results table (Parameter | Result | Unit | Ref. range | Flag) that
 * mirrors the printed report, and a compact stage action bar.
 *
 * Three things make it a bench tool rather than a form:
 *  - **ranges are shown BEFORE entry** — computed live from the catalog against
 *    the patient's age/sex ([LabRepository.refDisplayFor]); the frozen
 *    `ref_display` still wins for rows already entered (historical truth);
 *  - **flags are live** — recomputed client-side as you type so an out-of-range
 *    value is obvious immediately; the authoritative flag is still whatever
 *    `enterResult` freezes on commit;
 *  - **keyboard-first** — Tab/Shift-Tab walk the rows, Enter jumps to the next
 *    EMPTY row (a whole CBC without touching the mouse). Every move blurs the
 *    field, and blur is what commits.
 *
 * Entry locks once the order is verified (repo enforces; UI reflects).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderDetailScreen(
    orderId: String,
    labName: String,
    onBack: () -> Unit,
    onOpenInvoice: (String) -> Unit,
) {
    val repo = LocalLabRepository.current
    val scope = rememberCoroutineScope()
    val prefs = remember { LimsPrefs() }
    // P4: attribution + RBAC ride the SIGNED-IN staff member. LimsPrefs stays the
    // device-name/printing holder — it is no longer who did the work.
    val session = LocalStaffSession.current
    val me by session.current.collectAsState()
    // Graceful fallback: a somehow-empty session degrades to the station name
    // rather than stamping a blank `entered_by`/`verified_by`.
    val actor = me?.name?.takeIf { it.isNotBlank() } ?: prefs.deviceName
    // Approval is the pathologist's signature — technicians verify, they don't
    // approve. A null session falls back to the old free-text dialog.
    val canApprove = me?.canApprove ?: true

    var order by remember { mutableStateOf<LabOrder?>(null) }
    var patient by remember { mutableStateOf<Patient?>(null) }
    var referrer by remember { mutableStateOf<Referrer?>(null) }
    var tests by remember { mutableStateOf<List<LabOrderTest>>(emptyList()) }
    var results by remember { mutableStateOf<Map<String, LabResult>>(emptyMap()) } // "testId|paramKey"
    var catalog by remember { mutableStateOf<Map<String, LabTest>>(emptyMap()) }
    var reloadTick by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var showApprove by remember { mutableStateOf(false) }
    var showCancel by remember { mutableStateOf(false) }
    var showPrintChooser by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    // What is IN the boxes right now (may be ahead of the committed result).
    // Single source of truth for the inputs: lets us find the next EMPTY field
    // and flag a value live without asking each row for its private state.
    val draft = remember { mutableStateMapOf<String, String>() }
    var focusedKey by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(orderId, reloadTick) {
        val o = repo.orderById(orderId) ?: return@LaunchedEffect
        order = o
        patient = repo.patientById(o.patientId)
        referrer = o.referrerId?.let { repo.referrerById(it) }
        val ts = repo.orderTests(orderId)
        tests = ts
        val fresh = repo.resultsForOrder(orderId).associateBy { "${it.testId}|${it.parameterKey}" }
        results = fresh
        catalog = ts.mapNotNull { t -> repo.testById(t.testId)?.let { t.testId to it } }.toMap()
        // Re-seed the boxes from the DB, but never yank text out from under the
        // field the technician is typing in (a status walk can reload mid-entry).
        fresh.forEach { (k, r) ->
            if (k != focusedKey && draft[k] != r.value.orEmpty()) draft[k] = r.value.orEmpty()
        }
    }

    val o = order
    val locked = o == null || o.status !in ENTRY_OPEN
    val enteredCount = results.values.count { it.isEntered }
    val totalCount = results.size

    /** Catalog parameter display name for a result row (raw key fallback). */
    val nameOf: (LabResult) -> String = { r ->
        catalog[r.testId]?.parameters?.firstOrNull { it.key == r.parameterKey }?.name ?: r.parameterKey
    }

    /** First successful print/open of an APPROVED order marks it `reported`.
     *  Reprints stay unlimited (reported/delivered orders reprint freely). */
    suspend fun markReportedIfApproved() {
        val ord = order ?: return
        if (ord.status == LabStatus.APPROVED) {
            repo.setOrderStatus(ord.id, LabStatus.REPORTED)
            reloadTick++
        }
    }

    /** Assemble the styled-A4 document from the frozen results + this device's
     *  letterhead prefs (lab name ALWAYS the license-bound one). */
    fun buildDoc(): ReportDoc? {
        val ord = order ?: return null
        val pat = patient ?: return null
        val rp = ReportPrefs()
        return buildReportDoc(
            labName = labName, order = ord, patient = pat, tests = tests,
            results = results.values.toList(), referrerName = referrer?.name,
            mode = rp.mode(), headerMm = rp.headerMm.toFloat(), footerMm = rp.footerMm.toFloat(),
            accentRgb = rp.accentRgb, letterheadLines = rp.letterheadLines(),
            paramName = nameOf,
        )
    }

    /** Styled A4 PDF path: write, then open in the viewer or send to the OS
     *  print pipeline. Success (not cancelled/failed) marks approved → reported. */
    suspend fun pdfReport(print: Boolean): Boolean {
        val doc = buildDoc() ?: return false
        val status = withContext(Dispatchers.Default) {
            val path = writeLabReportPdf(doc)
            when {
                path.isBlank() -> "PDF reports arrive on iOS later"
                print -> printPdf(path)
                else -> openPdf(path)
            }
        }
        message = status
        val ok = !status.contains("failed", ignoreCase = true) &&
            !status.contains("cancelled", ignoreCase = true) &&
            !status.contains("not found", ignoreCase = true) &&
            !status.contains("later", ignoreCase = true)
        if (ok) markReportedIfApproved()
        return ok
    }

    /** Legacy monospace slip on the configured LAN/BT thermal printer (the
     *  renderLabReport text path — kept for sample-tube counter slips). */
    suspend fun printThermalSlip(): Boolean {
        val ord = order ?: return false
        val pat = patient ?: return false
        val bp = BillingPrefs()
        val result = withContext(Dispatchers.Default) {
            val body = renderLabReport(
                labName = labName, order = ord, patient = pat, tests = tests,
                results = results.values.toList(), referrerName = referrer?.name,
                widthChars = bp.paperWidth, paramName = nameOf,
            )
            when (bp.printerConnection) {
                "network" -> printToNetworkPrinter(bp.printerIp, bp.printerPort, EscPos.encode(body))
                "bluetooth" -> BtPrinter.getInstance().printBytes(bp.printerBtAddress, EscPos.encode(body))
                else -> "No thermal printer configured"
            }
        }
        val ok = result.startsWith("Sent to")
        message = if (ok) "Report sent to printer" else result
        if (ok) markReportedIfApproved()
        return ok
    }

    /** Commit one cell. Flags + ref_display are frozen server-side by the repo —
     *  the live chip is only a preview of what it will decide. */
    fun commit(row: GridRow, text: String) {
        val ord = order ?: return
        session.touch()
        scope.launch {
            repo.enterResult(ord.id, row.testId, row.paramKey, text, enteredBy = actor)
                .onSuccess { updated ->
                    results = results + (row.key to updated)
                    // Entry can walk the order status (in_progress/entered).
                    repo.orderById(ord.id)?.let { fresh -> if (fresh.status != ord.status) reloadTick++ }
                }
                .onFailure { message = it.message }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        o?.accessionNo ?: "Order",
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    o?.invoiceId?.let { inv -> TextButton(onClick = { onOpenInvoice(inv) }) { Text("Bill") } }
                    if (o != null && o.status != LabStatus.DELIVERED && o.status != LabStatus.CANCELLED) {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("Cancel order") }, onClick = { menuOpen = false; showCancel = true })
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (o != null) {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            message?.let {
                                Text(it, style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            stageHint(o.status, enteredCount, totalCount, canApprove)?.let { hint ->
                                Text(
                                    hint, style = MaterialTheme.typography.bodySmall,
                                    color = if (o.status == LabStatus.CANCELLED) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        ActionBar(
                            status = o.status,
                            busy = busy,
                            onForward = { next ->
                                if (busy) return@ActionBar
                                busy = true; message = null
                                scope.launch {
                                    repo.setOrderStatus(o.id, next)
                                        .onSuccess { reloadTick++ }
                                        .onFailure { message = it.message }
                                    busy = false
                                }
                            },
                            onVerify = {
                                if (busy) return@ActionBar
                                busy = true; message = null; session.touch()
                                scope.launch {
                                    repo.verifyOrder(o.id, actor)
                                        .onSuccess { message = "Verified by $actor"; reloadTick++ }
                                        .onFailure { message = it.message }
                                    busy = false
                                }
                            },
                            canApprove = canApprove,
                            onApprove = { session.touch(); showApprove = true },
                            onPrint = { if (!busy) showPrintChooser = true },
                        )
                    }
                }
            }
        },
    ) { inner ->
        if (o == null || patient == null) {
            Box(Modifier.padding(inner).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        val pat = patient!!
        val rows = remember(tests, catalog, results, pat) { buildGrid(tests, catalog, results, pat) }
        val requesters = remember(rows.size) { List(rows.size) { FocusRequester() } }
        val listState = rememberLazyListState()
        val focusManager = LocalFocusManager.current
        val showLockNote = locked && o.status != LabStatus.CANCELLED

        BoxWithConstraints(Modifier.padding(inner).fillMaxSize()) {
            val wide = maxWidth >= WIDE_MIN_DP.dp
            // Rows start after the header block (+ the table header when wide).
            val leading = if (wide) 2 else 1

            /** Focus row [target], scrolling it into view first — a LazyColumn
             *  requester that is not composed would throw. */
            fun focusRow(target: Int) {
                if (target !in rows.indices) { focusManager.clearFocus(); return }
                scope.launch {
                    val itemIndex = leading + target
                    if (listState.layoutInfo.visibleItemsInfo.none { it.index == itemIndex }) {
                        runCatching { listState.scrollToItem(itemIndex) }
                    }
                    runCatching { requesters[target].requestFocus() }
                }
            }

            /** Enter = "next thing that still needs a value": forward first,
             *  then wrap to an earlier gap, else just step down; nothing empty
             *  left → drop focus (which commits the field we are leaving). */
            fun focusNextEmpty(from: Int) {
                val blank = { i: Int -> draft[rows[i].key].isNullOrBlank() }
                val target = (from + 1 until rows.size).firstOrNull(blank)
                    ?: (0 until from).firstOrNull(blank)
                    ?: (from + 1).takeIf { it < rows.size }
                if (target == null) focusManager.clearFocus() else focusRow(target)
            }

            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(bottom = 28.dp),
            ) {
                item(key = "head") {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OrderHeader(o, pat, referrer)
                        if (totalCount > 0) EntryProgress(enteredCount, totalCount)
                        if (showLockNote) {
                            Text(
                                "Results are locked (order is ${o.status.replace('_', ' ')}).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (wide) item(key = "cols") { TableHeader() }
                itemsIndexed(rows, key = { _, r -> r.key }) { i, row ->
                    Column(Modifier.fillMaxWidth()) {
                        row.section?.let { SectionHeader(it) }
                        ResultGridRow(
                            row = row,
                            value = draft[row.key].orEmpty(),
                            wide = wide,
                            locked = locked,
                            focused = focusedKey == row.key,
                            last = i == rows.lastIndex,
                            focusRequester = requesters[i],
                            onValueChange = { draft[row.key] = it },
                            onFocus = { focusedKey = row.key },
                            onBlur = {
                                if (focusedKey == row.key) focusedKey = null
                                val text = draft[row.key].orEmpty()
                                if (text.trim() != row.result.value.orEmpty().trim()) commit(row, text)
                            },
                            onNext = { focusRow(i + 1) },
                            onPrevious = { if (i == 0) focusManager.clearFocus() else focusRow(i - 1) },
                            onEnter = { focusNextEmpty(i) },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                    }
                }
            }
        }
    }

    // ── Approve dialog (P4): the SIGNED-IN pathologist signs — no free-text
    // "approved by" any more. The old typed-name field survives only as the
    // fallback for a somehow-null session (never expected once the sign-in gate
    // is in front of the app, but approval must never become unreachable). ──
    if (showApprove && o != null && canApprove) {
        val signer = me
        var name by remember(signer) { mutableStateOf(signer?.name ?: prefs.approvedBy) }
        var err by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showApprove = false },
            title = { Text("Approve results") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("The approving pathologist's name prints on every report.", style = MaterialTheme.typography.bodySmall)
                    if (signer != null) {
                        Text(signer.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "Signing as ${signer.roleLabel.lowercase()} — switch user from the home header to sign as someone else.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        OutlinedTextField(
                            value = name, onValueChange = { name = it },
                            label = { Text("Approved by (pathologist)") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    err?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val n = (signer?.name ?: name).trim()
                    if (n.isEmpty()) { err = "Name is required"; return@Button }
                    if (signer == null) prefs.approvedBy = n
                    showApprove = false
                    busy = true; message = null; session.touch()
                    scope.launch {
                        repo.approveOrder(o.id, n)
                            .onSuccess { message = "Approved by $n"; reloadTick++ }
                            .onFailure { message = it.message }
                        busy = false
                    }
                }) { Text("Approve") }
            },
            dismissButton = { TextButton(onClick = { showApprove = false }) { Text("Cancel") } },
        )
    }

    // ── Print chooser: styled A4 PDF (open / print) + optional thermal slip ──
    if (showPrintChooser && o != null) {
        val thermalAvailable = remember {
            val conn = BillingPrefs().printerConnection
            conn == "network" || conn == "bluetooth"
        }
        fun run(block: suspend () -> Unit) {
            showPrintChooser = false
            if (busy) return
            busy = true; message = "Preparing report…"
            scope.launch {
                try { block() } catch (e: Throwable) { message = "Report failed: ${e.message}" }
                busy = false
            }
        }
        AlertDialog(
            onDismissRequest = { showPrintChooser = false },
            title = { Text("Report ${o.accessionNo}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "The A4 report uses this device's letterhead settings (Settings → Report & letterhead).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = { run { pdfReport(print = false) } }, modifier = Modifier.fillMaxWidth()) {
                        Text("Open PDF")
                    }
                    Button(onClick = { run { pdfReport(print = true) } }, modifier = Modifier.fillMaxWidth()) {
                        Text("Print")
                    }
                    if (thermalAvailable) {
                        OutlinedButton(onClick = { run { printThermalSlip() } }, modifier = Modifier.fillMaxWidth()) {
                            Text("Thermal slip")
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showPrintChooser = false }) { Text("Close") } },
        )
    }

    // ── Cancel confirm ──
    if (showCancel && o != null) {
        AlertDialog(
            onDismissRequest = { showCancel = false },
            title = { Text("Cancel order ${o.accessionNo}?") },
            text = { Text("The order is marked cancelled and leaves the worklist. The linked bill (if any) is NOT voided automatically.") },
            confirmButton = {
                Button(onClick = {
                    showCancel = false
                    scope.launch {
                        repo.setOrderStatus(o.id, LabStatus.CANCELLED)
                            .onSuccess { reloadTick++ }
                            .onFailure { message = it.message }
                    }
                }) { Text("Cancel order") }
            },
            dismissButton = { TextButton(onClick = { showCancel = false }) { Text("Keep") } },
        )
    }
}

// ── Grid model ───────────────────────────────────────────────────────────────

/** One printable line of the results table. Everything the row needs is
 *  resolved once, at load: no per-frame catalog lookups, no age math. */
private data class GridRow(
    val key: String,                  // "testId|paramKey" — matches the results map
    val testId: String,
    val paramKey: String,
    val label: String,
    val unit: String,
    val refDisplay: String,
    val numeric: Boolean,
    val range: RefRange?,             // for the LIVE flag while typing
    val result: LabResult,
    val section: String?,             // slim test header drawn above this row
)

/**
 * Flatten the order into table rows. A test with SEVERAL parameters gets a slim
 * section header on its first row; a single-parameter test gets no header at
 * all — the row itself is the test ([singleRowLabel] picks the name that reads
 * best). A test whose catalog entry has vanished still renders from its frozen
 * result rows so nothing is ever hidden from the technician.
 */
private fun buildGrid(
    tests: List<LabOrderTest>,
    catalog: Map<String, LabTest>,
    results: Map<String, LabResult>,
    patient: Patient,
): List<GridRow> {
    val rows = mutableListOf<GridRow>()
    for (t in tests) {
        val test = catalog[t.testId]
        val params = test?.parameters.orEmpty()
        if (test != null && params.isNotEmpty()) {
            val multi = params.size > 1
            var first = true
            for (p in params) {
                val res = results["${t.testId}|${p.key}"] ?: continue
                rows += GridRow(
                    key = "${t.testId}|${p.key}",
                    testId = t.testId,
                    paramKey = p.key,
                    label = if (multi) p.name else singleRowLabel(t.testName, p.name),
                    unit = (res.unit ?: p.unit).orEmpty(),
                    // Frozen range wins once a value is in (that is what printed);
                    // otherwise show what WILL be frozen for this patient.
                    refDisplay = res.refDisplay?.takeIf { res.isEntered && it.isNotBlank() }
                        ?: LabRepository.refDisplayFor(test, p, patient),
                    numeric = p.isNumeric(),
                    range = LabRepository.rangeFor(test, p, patient),
                    result = res,
                    section = if (multi && first) t.testName else null,
                )
                first = false
            }
        } else {
            var first = true
            for (res in results.values.filter { it.testId == t.testId }) {
                rows += GridRow(
                    key = "${t.testId}|${res.parameterKey}",
                    testId = t.testId,
                    paramKey = res.parameterKey,
                    label = res.parameterKey,
                    unit = res.unit.orEmpty(),
                    refDisplay = res.refDisplay?.takeIf { it.isNotBlank() } ?: LabRepository.NO_RANGE,
                    numeric = true,
                    range = null,
                    result = res,
                    section = if (first) t.testName else null,
                )
                first = false
            }
        }
    }
    return rows
}

private val NAME_SPLIT = Regex("[^a-z0-9]+")
private val NAME_NOISE = setOf("serum", "plasma", "total", "blood", "test", "level")

/** Qualifiers a lab drops in conversation — "Serum Creatinine" IS "Creatinine". */
private fun normalizeName(s: String): String =
    s.lowercase().split(NAME_SPLIT).filter { it.isNotBlank() && it !in NAME_NOISE }.joinToString("")

/**
 * The one name to show for a single-parameter test. When the parameter is just
 * the test again ("Serum Creatinine" → "Creatinine") the fuller test name wins;
 * when they say different things the PARAMETER name wins, because that is what
 * the report prints.
 */
private fun singleRowLabel(testName: String, paramName: String): String {
    val t = normalizeName(testName)
    val p = normalizeName(paramName)
    return when {
        t.isEmpty() || p.isEmpty() -> paramName
        t.contains(p) -> testName
        else -> paramName
    }
}

/** Numeric unless every defined range is qualitative (text-only). */
private fun TestParameter?.isNumeric(): Boolean {
    if (this == null) return false
    if (ranges.isEmpty()) return true
    return ranges.any { it.text == null }
}

/** The one line under the buttons that says what this stage is waiting for. */
private fun stageHint(status: String, entered: Int, total: Int, canApprove: Boolean): String? = when (status) {
    LabStatus.REGISTERED -> "Collect the sample to start"
    LabStatus.COLLECTED, LabStatus.IN_PROGRESS -> "$entered of $total results entered — verify unlocks when all are in"
    LabStatus.ENTERED -> "All $total results in — ready to verify"
    LabStatus.VERIFIED -> if (canApprove) "Verified — awaiting the pathologist's approval"
    else "Only a pathologist can approve results"
    LabStatus.APPROVED -> "Approved — printing marks it reported"
    LabStatus.REPORTED, LabStatus.DELIVERED -> "Report issued — reprints stay open"
    LabStatus.CANCELLED -> "Order cancelled"
    else -> null
}

// ── Pieces ───────────────────────────────────────────────────────────────────

/** Compact identity strip: who, how old, reachable where, which accession. */
@Composable
private fun OrderHeader(order: LabOrder, patient: Patient, referrer: Referrer?) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    patient.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    ageSexLabel(patient.dob, patient.ageYears, patient.sex) +
                        (patient.phone?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (!order.priority.equals("routine", ignoreCase = true)) StatusBadge(order.priority)
                StatusBadge(order.status)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    order.accessionNo, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    "· Registered ${shortTimeLabel(order.createdAt)}" +
                        (referrer?.let { " · Ref: ${it.name}" } ?: ""),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** "N of M results entered" + the thin bar that makes a half-done order obvious. */
@Composable
private fun EntryProgress(entered: Int, total: Int) {
    val fraction = if (total <= 0) 0f else entered.toFloat() / total.toFloat()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "$entered of $total results entered",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().height(4.dp),
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

/** The table's column strip — same widths as every data row. */
@Composable
private fun TableHeader() {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HeadCell("Parameter", Modifier.weight(W_PARAM))
            HeadCell("Result", Modifier.width(COL_RESULT))
            HeadCell("Unit", Modifier.width(COL_UNIT))
            HeadCell("Ref. range", Modifier.weight(W_RANGE))
            HeadCell("Flag", Modifier.width(COL_FLAG))
        }
    }
}

@Composable
private fun HeadCell(text: String, modifier: Modifier = Modifier) {
    Text(
        text, modifier = modifier, style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
    )
}

/** Slim divider + test name — only for tests with more than one parameter. */
@Composable
private fun SectionHeader(name: String) {
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            name.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                .padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}

/**
 * One parameter line. Wide → the five-column table row; narrow → two lines
 * (name + range above, input + unit + flag below). Never a card either way.
 */
@Composable
private fun ResultGridRow(
    row: GridRow,
    value: String,
    wide: Boolean,
    locked: Boolean,
    focused: Boolean,
    last: Boolean,
    focusRequester: FocusRequester,
    onValueChange: (String) -> Unit,
    onFocus: () -> Unit,
    onBlur: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onEnter: () -> Unit,
) {
    // Live preview of the flag enterResult will freeze — same range brain.
    val liveFlag = when {
        value.isBlank() -> null
        else -> LabRepository.computeFlag(value, row.range) ?: row.result.flag
    }
    val bg = if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.06f) else Color.Transparent

    val field: @Composable (Modifier) -> Unit = { m ->
        ResultField(
            value = value, enabled = !locked, numeric = row.numeric, last = last,
            focusRequester = focusRequester, onValueChange = onValueChange,
            onFocus = onFocus, onBlur = onBlur, onNext = onNext, onPrevious = onPrevious,
            onEnter = onEnter, modifier = m,
        )
    }

    if (wide) {
        Row(
            Modifier.fillMaxWidth().background(bg).heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                row.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(W_PARAM),
            )
            field(Modifier.width(COL_RESULT))
            Text(
                row.unit, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(COL_UNIT),
            )
            Text(
                row.refDisplay, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(W_RANGE),
            )
            Box(Modifier.width(COL_FLAG), contentAlignment = Alignment.CenterStart) { FlagCell(liveFlag) }
        }
    } else {
        Column(
            Modifier.fillMaxWidth().background(bg).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Text(
                    row.refDisplay, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                field(Modifier.weight(1f))
                Text(
                    row.unit, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(COL_UNIT),
                )
                FlagCell(liveFlag)
            }
        }
    }
}

/**
 * The value box: a 34dp bordered field (a Material text field is 56dp tall and
 * would blow the row height apart). Blur commits — Tab/Shift-Tab step, Enter
 * jumps to the next empty cell, and both blur on the way out.
 */
@Composable
private fun ResultField(
    value: String,
    enabled: Boolean,
    numeric: Boolean,
    last: Boolean,
    focusRequester: FocusRequester,
    onValueChange: (String) -> Unit,
    onFocus: () -> Unit,
    onBlur: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onEnter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val borderColor = when {
        !enabled -> MaterialTheme.colorScheme.outlineVariant
        focused -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
    }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(
            keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text,
            imeAction = if (last) ImeAction.Done else ImeAction.Next,
        ),
        keyboardActions = KeyboardActions(onNext = { onEnter() }, onDone = { onEnter() }),
        modifier = modifier
            .focusRequester(focusRequester)
            .onFocusChanged { st ->
                if (st.isFocused) {
                    if (!focused) { focused = true; onFocus() }
                } else if (focused) {
                    focused = false; onBlur()
                }
            }
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (ev.key) {
                    Key.Tab -> { if (ev.isShiftPressed) onPrevious() else onNext(); true }
                    Key.Enter, Key.NumPadEnter -> { onEnter(); true }
                    else -> false
                }
            },
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().height(34.dp)
                    .background(
                        if (enabled) MaterialTheme.colorScheme.surface else Color.Transparent,
                        RoundedCornerShape(7.dp),
                    )
                    .border(if (focused) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(7.dp))
                    .padding(horizontal = 9.dp),
                contentAlignment = Alignment.CenterStart,
            ) { inner() }
        },
    )
}

/** Stage-appropriate primary actions — normal-width buttons, never a slab. */
@Composable
private fun ActionBar(
    status: String,
    busy: Boolean,
    /** P4 RBAC: only a pathologist (or the owner) may sign results off. */
    canApprove: Boolean,
    onForward: (String) -> Unit,
    onVerify: () -> Unit,
    onApprove: () -> Unit,
    onPrint: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        when (status) {
            LabStatus.REGISTERED ->
                Button(onClick = { onForward(LabStatus.COLLECTED) }, enabled = !busy) { Text("Mark collected") }
            LabStatus.COLLECTED ->
                Button(onClick = { onForward(LabStatus.IN_PROGRESS) }, enabled = !busy) { Text("Start processing") }
            LabStatus.ENTERED ->
                Button(onClick = onVerify, enabled = !busy) { Text("Verify results") }
            LabStatus.VERIFIED ->
                Button(onClick = onApprove, enabled = !busy && canApprove) { Text("Approve") }
            LabStatus.APPROVED ->
                Button(onClick = onPrint, enabled = !busy) {
                    if (busy) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(16.dp), strokeWidth = 2.dp)
                    Text("Print report")
                }
            LabStatus.REPORTED, LabStatus.DELIVERED ->
                OutlinedButton(onClick = onPrint, enabled = !busy) {
                    if (busy) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(16.dp), strokeWidth = 2.dp)
                    Text("Print report again")
                }
        }
    }
}
