package com.bnm.diagnosis.screens.lab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bnm.diagnosis.billing.BillingPrefs
import com.bnm.diagnosis.components.StatusBadge
import com.bnm.diagnosis.lab.LabOrder
import com.bnm.diagnosis.lab.LabOrderTest
import com.bnm.diagnosis.lab.LabResult
import com.bnm.diagnosis.lab.LabStatus
import com.bnm.diagnosis.lab.LocalLabRepository
import com.bnm.diagnosis.lab.Patient
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Statuses in which result entry is still open (mirrors the repo's guard). */
private val ENTRY_OPEN = setOf(LabStatus.REGISTERED, LabStatus.COLLECTED, LabStatus.IN_PROGRESS, LabStatus.ENTERED)

/**
 * One order's workbench: patient header + per-test parameter grid (value input,
 * unit, frozen ref range, live flag chip) + the stage-appropriate action bar
 * (collect → process → verify → approve → print report → reprint) and cancel.
 * Values save through repo.enterResult on focus-loss (flags computed there);
 * entry locks once the order is verified (repo enforces; UI reflects).
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

    var order by remember { mutableStateOf<LabOrder?>(null) }
    var patient by remember { mutableStateOf<Patient?>(null) }
    var referrer by remember { mutableStateOf<Referrer?>(null) }
    var tests by remember { mutableStateOf<List<LabOrderTest>>(emptyList()) }
    var results by remember { mutableStateOf<Map<String, LabResult>>(emptyMap()) } // "testId|paramKey"
    var paramsByTest by remember { mutableStateOf<Map<String, List<TestParameter>>>(emptyMap()) }
    var reloadTick by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var showApprove by remember { mutableStateOf(false) }
    var showCancel by remember { mutableStateOf(false) }
    var showPrintChooser by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(orderId, reloadTick) {
        val o = repo.orderById(orderId) ?: return@LaunchedEffect
        order = o
        patient = repo.patientById(o.patientId)
        referrer = o.referrerId?.let { repo.referrerById(it) }
        val ts = repo.orderTests(orderId)
        tests = ts
        results = repo.resultsForOrder(orderId).associateBy { "${it.testId}|${it.parameterKey}" }
        paramsByTest = ts.associate { t -> t.testId to (repo.testById(t.testId)?.parameters ?: emptyList()) }
    }

    val o = order
    val locked = o == null || o.status !in ENTRY_OPEN
    val enteredCount = results.values.count { it.isEntered }
    val totalCount = results.size

    /** Catalog parameter display name for a result row (raw key fallback). */
    val nameOf: (LabResult) -> String = { r ->
        paramsByTest[r.testId]?.firstOrNull { it.key == r.parameterKey }?.name ?: r.parameterKey
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
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        message?.let {
                            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                        ActionBar(
                            status = o.status,
                            busy = busy,
                            enteredCount = enteredCount,
                            totalCount = totalCount,
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
                                busy = true; message = null
                                scope.launch {
                                    repo.verifyOrder(o.id, prefs.deviceName)
                                        .onSuccess { message = "Verified by ${prefs.deviceName}"; reloadTick++ }
                                        .onFailure { message = it.message }
                                    busy = false
                                }
                            },
                            onApprove = { showApprove = true },
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
        LazyColumn(
            Modifier.padding(inner).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { PatientHeader(o, patient!!, referrer) }
            if (locked && o.status != LabStatus.CANCELLED) {
                item {
                    Text(
                        "Results are locked (order is ${o.status.replace('_', ' ')}).",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            tests.forEach { t ->
                item(key = "test-${t.testId}") {
                    Text(t.testName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 6.dp))
                }
                val params = paramsByTest[t.testId].orEmpty()
                val rows: List<Pair<TestParameter?, LabResult?>> =
                    if (params.isNotEmpty()) params.map { p -> p to results["${t.testId}|${p.key}"] }
                    else results.values.filter { it.testId == t.testId }.map { r -> null to r }
                rows.forEach { (param, res) ->
                    if (res == null) return@forEach
                    item(key = "row-${t.testId}-${param?.key ?: res.parameterKey}") {
                        ResultRow(
                            paramName = param?.name ?: res.parameterKey,
                            numeric = param.isNumeric(),
                            result = res,
                            locked = locked,
                            onCommit = { text ->
                                scope.launch {
                                    repo.enterResult(o.id, t.testId, param?.key ?: res.parameterKey, text, enteredBy = prefs.deviceName)
                                        .onSuccess { updated ->
                                            results = results + ("${t.testId}|${updated.parameterKey}" to updated)
                                            // Entry can walk the order status (in_progress/entered).
                                            repo.orderById(o.id)?.let { fresh ->
                                                if (fresh.status != o.status) reloadTick++
                                            }
                                        }
                                        .onFailure { message = it.message }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    // ── Approve dialog: pathologist name asked once, persisted as default ──
    if (showApprove && o != null) {
        var name by remember { mutableStateOf(prefs.approvedBy) }
        var err by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showApprove = false },
            title = { Text("Approve results") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("The approving pathologist's name prints on every report.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        label = { Text("Approved by (pathologist)") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    err?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val n = name.trim()
                    if (n.isEmpty()) { err = "Name is required"; return@Button }
                    prefs.approvedBy = n
                    showApprove = false
                    busy = true; message = null
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

/** Numeric unless every defined range is qualitative (text-only). */
private fun TestParameter?.isNumeric(): Boolean {
    if (this == null) return false
    if (ranges.isEmpty()) return true
    return ranges.any { it.text == null }
}

@Composable
private fun PatientHeader(order: LabOrder, patient: Patient, referrer: Referrer?) {
    Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(patient.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!order.priority.equals("routine", ignoreCase = true)) StatusBadge(order.priority)
                    StatusBadge(order.status)
                }
            }
            Text(
                ageSexLabel(patient.dob, patient.ageYears, patient.sex) +
                    (patient.phone?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
            )
            referrer?.let {
                Text("Referred by ${it.name}", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "Registered ${shortTimeLabel(order.createdAt)}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** One parameter row: name | value input | unit | ref range | flag chip.
 *  Commits to repo.enterResult on focus loss (blank clears the cell). */
@Composable
private fun ResultRow(
    paramName: String,
    numeric: Boolean,
    result: LabResult,
    locked: Boolean,
    onCommit: (String) -> Unit,
) {
    var text by remember(result.id) { mutableStateOf(result.value.orEmpty()) }
    var hadFocus by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(paramName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1.2f))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                enabled = !locked,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text),
                modifier = Modifier.width(120.dp).onFocusChanged { st ->
                    if (st.isFocused) hadFocus = true
                    else if (hadFocus) {
                        hadFocus = false
                        if (text.trim() != result.value.orEmpty().trim()) onCommit(text)
                    }
                },
            )
            Text(
                result.unit.orEmpty(), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(64.dp),
            )
            Text(
                result.refDisplay ?: "—", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.9f),
            )
            Box(Modifier.width(44.dp), contentAlignment = Alignment.Center) { FlagChip(result.flag) }
        }
    }
}

/** Stage-appropriate primary actions. */
@Composable
private fun ActionBar(
    status: String,
    busy: Boolean,
    enteredCount: Int,
    totalCount: Int,
    onForward: (String) -> Unit,
    onVerify: () -> Unit,
    onApprove: () -> Unit,
    onPrint: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        when (status) {
            LabStatus.REGISTERED -> {
                Button(onClick = { onForward(LabStatus.COLLECTED) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text("Mark collected")
                }
            }
            LabStatus.COLLECTED -> {
                Button(onClick = { onForward(LabStatus.IN_PROGRESS) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text("Start processing")
                }
            }
            LabStatus.IN_PROGRESS -> {
                Text(
                    "$enteredCount / $totalCount results entered — verify unlocks when all are in",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
            LabStatus.ENTERED -> {
                Button(onClick = onVerify, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Verify results") }
            }
            LabStatus.VERIFIED -> {
                Button(onClick = onApprove, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Approve") }
            }
            LabStatus.APPROVED -> {
                Button(onClick = onPrint, enabled = !busy, modifier = Modifier.weight(1f)) {
                    if (busy) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(16.dp), strokeWidth = 2.dp)
                    Text("Print report")
                }
            }
            LabStatus.REPORTED, LabStatus.DELIVERED -> {
                OutlinedButton(onClick = onPrint, enabled = !busy, modifier = Modifier.weight(1f)) {
                    if (busy) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(16.dp), strokeWidth = 2.dp)
                    Text("Print report again")
                }
            }
            LabStatus.CANCELLED -> {
                Text("Order cancelled", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f))
            }
        }
    }
}
