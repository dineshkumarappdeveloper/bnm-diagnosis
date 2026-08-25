package com.bnm.diagnosis.screens.lab

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.bnm.diagnosis.lab.LabTest
import com.bnm.diagnosis.lab.LocalLabRepository
import com.bnm.diagnosis.lab.Referrer
import com.bnm.diagnosis.lab.ReferrerCommissionRow
import com.bnm.diagnosis.lab.ReferrerOrderRow
import com.bnm.diagnosis.print.printReceipt
import com.bnm.diagnosis.util.formatDecimal1
import com.bnm.diagnosis.util.formatDecimal2
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.launch
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime

/**
 * Referrer hub (P4) — two in-screen tabs, no extra nav route:
 *
 *  1. **Referrers** — the doctor/clinic master (name, kind, phone, commission %)
 *     plus each referrer's **rate list**: a per-test B2B price override. A blank
 *     field means "catalog price" (no row is stored), so a catalog reprice
 *     flows through automatically instead of freezing at whatever it was.
 *  2. **Commission** — the payout statement over a date range. Gross is summed
 *     from the ORDER LINE SNAPSHOTS (`lab_order_tests.price`), which already
 *     carry the negotiated rate as it stood at registration — the historical
 *     truth. Payable = gross × commission %. Printable / copyable.
 *
 * Everything here is offline: the local SQLDelight DB is the system of record.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReferrersScreen(onBack: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    var refresh by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf<Referrer?>(null) }
    var showForm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Referrers") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        floatingActionButton = {
            if (tab == 0) {
                FloatingActionButton(onClick = { editing = null; showForm = true }) {
                    Icon(Icons.Default.Add, contentDescription = "New referrer")
                }
            }
        },
    ) { inner ->
        Column(Modifier.padding(inner).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Referrers") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Commission") })
            }
            if (tab == 0) {
                ReferrerListTab(
                    refreshKey = refresh,
                    onEdit = { editing = it; showForm = true },
                    onChanged = { refresh++ },
                )
            } else {
                CommissionTab()
            }
        }
    }

    if (showForm) {
        ReferrerFormDialog(
            initial = editing,
            onDismiss = { showForm = false },
            onSaved = { showForm = false; refresh++ },
            onDeleted = { showForm = false; refresh++ },
        )
    }
}

// ───────────────────────────── Tab 1: referrers ─────────────────────────────

@Composable
private fun ReferrerListTab(refreshKey: Int, onEdit: (Referrer) -> Unit, onChanged: () -> Unit) {
    val repo = LocalLabRepository.current
    var referrers by remember { mutableStateOf<List<Referrer>>(emptyList()) }
    var rateCounts by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var rateListFor by remember { mutableStateOf<Referrer?>(null) }

    LaunchedEffect(refreshKey) {
        referrers = runCatching { repo.listReferrers() }.getOrDefault(emptyList())
        rateCounts = referrers.associate { it.id to runCatching { repo.rateCount(it.id) }.getOrDefault(0L) }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(referrers, key = { it.id }) { r ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Row(
                        Modifier.fillMaxWidth().clickable { onEdit(r) },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(r.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            Text(
                                r.kind.replace('_', ' ') +
                                    (r.phone?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (r.commissionPct > 0) {
                            Text(
                                "${formatDecimal1(r.commissionPct)}%",
                                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val n = rateCounts[r.id] ?: 0L
                        Text(
                            if (n > 0L) "$n negotiated rate${if (n == 1L) "" else "s"}" else "Billed at catalog prices",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { rateListFor = r }) { Text("Rate list") }
                    }
                }
            }
        }
        if (referrers.isEmpty()) {
            item {
                Text(
                    "No referrers yet. Add the doctors and clinics that send you samples — " +
                        "their commission % and negotiated rates drive the payout statement.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 24.dp),
                )
            }
        }
    }

    rateListFor?.let { r ->
        RateListDialog(
            referrer = r,
            onDismiss = { rateListFor = null },
            onSaved = { rateListFor = null; onChanged() },
        )
    }
}

/**
 * Per-referrer B2B price list. Every active test gets a field whose PLACEHOLDER
 * is the catalog price — leaving it blank stores nothing (catalog price stands),
 * typing a number stores the override. "Discount N% off catalog" fills every
 * visible field at once for the common "this clinic gets 20% off" deal.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun RateListDialog(referrer: Referrer, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val repo = LocalLabRepository.current
    val scope = rememberCoroutineScope()
    var tests by remember { mutableStateOf<List<LabTest>>(emptyList()) }
    var original by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }
    val drafts = remember { mutableStateMapOf<String, String>() }   // testId → raw text
    var search by remember { mutableStateOf("") }
    var discount by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(referrer.id) {
        tests = runCatching { repo.listTests() }.getOrDefault(emptyList())
        original = runCatching { repo.ratesFor(referrer.id) }.getOrDefault(emptyMap())
        drafts.clear()
        original.forEach { (id, p) -> drafts[id] = formatDecimal2(p) }
        loaded = true
    }

    val q = search.trim().lowercase()
    val visible = tests.filter {
        q.isEmpty() || it.name.lowercase().contains(q) || it.code.lowercase().contains(q) ||
            (it.category?.lowercase()?.contains(q) == true)
    }
    val overrideCount = drafts.count { it.value.trim().toDoubleOrNull() != null }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.widthIn(max = 620.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Rate list · ${referrer.name}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "Blank = the catalog price (shown as the field's hint). Only the tests you " +
                        "price here are billed differently for this referrer.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = search, onValueChange = { search = it },
                    label = { Text("Search test name, code or category") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // Bulk helper: "20% off catalog" across every visible test.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = discount,
                        onValueChange = { discount = it.filter { c -> c.isDigit() || c == '.' }.take(5) },
                        label = { Text("Discount %") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.width(140.dp),
                    )
                    OutlinedButton(onClick = {
                        val pct = discount.trim().toDoubleOrNull()
                        if (pct == null || pct < 0 || pct > 100) { error = "Discount must be 0–100"; return@OutlinedButton }
                        error = null
                        visible.forEach { t -> drafts[t.id] = formatDecimal2(t.price * (1.0 - pct / 100.0)) }
                    }) { Text("Apply to ${visible.size} shown") }
                    TextButton(onClick = { drafts.clear() }) { Text("Clear all") }
                }
                HorizontalDivider()

                if (!loaded) {
                    Text("Loading catalog…", style = MaterialTheme.typography.bodySmall)
                } else {
                    LazyColumn(
                        Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 380.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(visible, key = { it.id }) { t ->
                            val raw = drafts[t.id].orEmpty()
                            val parsed = raw.trim().toDoubleOrNull()
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(t.name, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "${t.code} · catalog ₹ ${formatDecimal2(t.price)}" +
                                            if (parsed != null && kotlin.math.abs(parsed - t.price) > 0.005) {
                                                val delta = (t.price - parsed) / (if (t.price == 0.0) 1.0 else t.price) * 100.0
                                                " · ${formatDecimal1(delta)}% off"
                                            } else "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                OutlinedTextField(
                                    value = raw,
                                    onValueChange = { v ->
                                        val clean = v.filter { c -> c.isDigit() || c == '.' }.take(9)
                                        if (clean.isEmpty()) drafts.remove(t.id) else drafts[t.id] = clean
                                    },
                                    placeholder = { Text(formatDecimal2(t.price), style = MaterialTheme.typography.bodySmall) },
                                    prefix = { Text("₹") }, singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    modifier = Modifier.width(150.dp),
                                )
                            }
                        }
                    }
                }

                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "$overrideCount override${if (overrideCount == 1) "" else "s"} · ${tests.size} tests in catalog",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                        Button(enabled = !saving && loaded, onClick = {
                            saving = true
                            scope.launch {
                                runCatching {
                                    // Write the diff only: new/changed prices upsert,
                                    // cleared fields delete (falling back to catalog).
                                    val wanted = drafts.mapNotNull { (id, raw) ->
                                        raw.trim().toDoubleOrNull()?.let { id to it }
                                    }.toMap()
                                    for ((id, price) in wanted) {
                                        if (original[id] == null || kotlin.math.abs(original[id]!! - price) > 0.0005) {
                                            repo.setRate(referrer.id, id, price)
                                        }
                                    }
                                    for (id in original.keys) if (id !in wanted) repo.clearRate(referrer.id, id)
                                }.onSuccess { onSaved() }
                                    .onFailure { saving = false; error = it.message ?: "Could not save the rate list" }
                            }
                        }) { Text(if (saving) "Saving…" else "Save rate list") }
                    }
                }
            }
        }
    }
}

// ──────────────────────────── Tab 2: commission ────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CommissionTab() {
    val repo = LocalLabRepository.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    val today = remember { todayLocal() }
    var preset by remember { mutableStateOf("this_month") }
    var fromText by remember { mutableStateOf(firstOfMonth(today).toString()) }
    var toText by remember { mutableStateOf(today.toString()) }
    var rows by remember { mutableStateOf<List<ReferrerCommissionRow>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var drill by remember { mutableStateOf<ReferrerCommissionRow?>(null) }
    var statement by remember { mutableStateOf<String?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }

    fun applyPreset(p: String) {
        preset = p
        val (f, t) = presetRange(today, p) ?: return
        fromText = f.toString(); toText = t.toString()
    }

    LaunchedEffect(fromText, toText) {
        loading = true
        rows = runCatching { repo.commissionReport(fromText, toText) }.getOrDefault(emptyList())
        loading = false
    }

    val grossTotal = rows.sumOf { it.gross }
    val payableTotal = rows.sumOf { it.payable }
    val orderTotal = rows.sumOf { it.ordersCount }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(
                    "this_month" to "This month",
                    "last_month" to "Last month",
                    "this_quarter" to "This quarter",
                    "custom" to "Custom",
                ).forEach { (code, label) ->
                    FilterChip(selected = preset == code, onClick = { applyPreset(code); preset = code },
                        label = { Text(label) })
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = fromText,
                    onValueChange = { fromText = it.take(10); preset = "custom" },
                    label = { Text("From (YYYY-MM-DD)") }, singleLine = true, modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = toText,
                    onValueChange = { toText = it.take(10); preset = "custom" },
                    label = { Text("To (YYYY-MM-DD)") }, singleLine = true, modifier = Modifier.weight(1f),
                )
            }
        }
        item {
            Card(
                Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("${rows.size} referrer${if (rows.size == 1) "" else "s"} · $orderTotal order${if (orderTotal == 1L) "" else "s"}",
                            style = MaterialTheme.typography.bodySmall)
                        Text("Gross ₹ ${formatDecimal2(grossTotal)}", style = MaterialTheme.typography.bodyMedium)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Commission payable", style = MaterialTheme.typography.bodySmall)
                        Text("₹ ${formatDecimal2(payableTotal)}", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = rows.isNotEmpty(),
                    onClick = { statement = renderCommissionStatement(fromText, toText, rows) },
                ) { Text("Print / copy statement") }
                if (toast != null) {
                    Text(toast!!, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp))
                }
            }
        }

        if (loading) {
            item { Text("Loading…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp)) }
        } else if (rows.isEmpty()) {
            item {
                Text(
                    "No referred orders in this range. Only orders that carry a referrer " +
                        "(and aren't cancelled) appear on the statement.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }

        items(rows, key = { it.referrerId }) { row ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable { drill = row },
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            ) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(row.referrerName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${row.ordersCount} order${if (row.ordersCount == 1L) "" else "s"} · " +
                                "gross ₹ ${formatDecimal2(row.gross)} · ${formatDecimal1(row.commissionPct)}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text("₹ ${formatDecimal2(row.payable)}", style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    drill?.let { row ->
        ReferrerDrillDownDialog(row = row, fromDate = fromText, toDate = toText, onDismiss = { drill = null })
    }

    statement?.let { body ->
        val csv = renderCommissionCsv(fromText, toText, rows)
        AlertDialog(
            onDismissRequest = { statement = null },
            title = { Text("Commission statement") },
            text = {
                Box(Modifier.heightIn(max = 360.dp)) {
                    LazyColumn {
                        item {
                            Text(body, fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(csv)); toast = "CSV copied"
                    }) { Text("Copy CSV") }
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(body)); toast = "Statement copied"
                    }) { Text("Copy text") }
                    Button(onClick = {
                        scope.launch {
                            toast = runCatching { printReceipt("Commission $fromText..$toText", body) }
                                .getOrElse { "Print failed: ${it.message}" }
                        }
                        statement = null
                    }) { Text("Print") }
                }
            },
            dismissButton = { TextButton(onClick = { statement = null }) { Text("Close") } },
        )
    }
}

/** Drill-down: the orders behind one statement row. */
@Composable
private fun ReferrerDrillDownDialog(
    row: ReferrerCommissionRow,
    fromDate: String,
    toDate: String,
    onDismiss: () -> Unit,
) {
    val repo = LocalLabRepository.current
    var orders by remember { mutableStateOf<List<ReferrerOrderRow>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(row.referrerId, fromDate, toDate) {
        orders = runCatching { repo.referrerOrders(row.referrerId, fromDate, toDate) }.getOrDefault(emptyList())
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(row.referrerName) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "$fromDate → $toDate · gross ₹ ${formatDecimal2(row.gross)} · " +
                        "${formatDecimal1(row.commissionPct)}% ⇒ payable ₹ ${formatDecimal2(row.payable)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider()
                if (loading) Text("Loading…", style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.heightIn(max = 340.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(orders, key = { it.orderId }) { o ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${o.accessionNo} · ${o.patientName}", style = MaterialTheme.typography.bodyMedium)
                                Text("${shortTimeLabel(o.createdAt)} · ${o.status}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("₹ ${formatDecimal2(o.amount)}", style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

// ─────────────────────────── Statement rendering ───────────────────────────

/** Plain-text monospace payout statement — the same text-print path the
 *  thermal slip uses (`printReceipt`), so it lands on any installed printer. */
internal fun renderCommissionStatement(
    fromDate: String,
    toDate: String,
    rows: List<ReferrerCommissionRow>,
    widthChars: Int = 64,
): String {
    val w = widthChars.coerceIn(40, 100)
    val sb = StringBuilder()
    fun ln(s: String = "") { sb.append(s).append('\n') }
    fun rule(ch: Char = '-') = ln(ch.toString().repeat(w))
    fun center(s: String) = ln(" ".repeat(((w - s.length) / 2).coerceAtLeast(0)) + s)
    fun lr(l: String, r: String) = ln(l + " ".repeat((w - l.length - r.length).coerceAtLeast(1)) + r)

    rule('=')
    center("REFERRER COMMISSION STATEMENT")
    center("$fromDate  to  $toDate")
    rule('=')
    // Column head: name | orders | gross | % | payable
    ln("Referrer".padEnd(w - 34) + "Ord".padStart(4) + "Gross".padStart(12) + "Pct".padStart(6) + "Payable".padStart(12))
    rule()
    rows.forEach { r ->
        val name = r.referrerName.let { if (it.length > w - 35) it.take(w - 35) else it }
        ln(
            name.padEnd(w - 34) +
                r.ordersCount.toString().padStart(4) +
                formatDecimal2(r.gross).padStart(12) +
                formatDecimal1(r.commissionPct).padStart(6) +
                formatDecimal2(r.payable).padStart(12)
        )
    }
    rule('=')
    lr("Orders", rows.sumOf { it.ordersCount }.toString())
    lr("Gross billed", formatDecimal2(rows.sumOf { it.gross }))
    lr("TOTAL COMMISSION PAYABLE", formatDecimal2(rows.sumOf { it.payable }))
    rule('=')
    ln()
    // Keep every footer line inside `w` so the slip never wraps mid-sentence.
    listOf(
        "Gross = the billed test lines on each referred order, at the",
        "price snapshotted when the order was registered. Cancelled",
        "orders are excluded. Payable = gross x commission %.",
    ).forEach { ln(it.take(w)) }
    return sb.toString()
}

/** Copy-friendly CSV of the same statement (spreadsheet paste). */
internal fun renderCommissionCsv(
    fromDate: String,
    toDate: String,
    rows: List<ReferrerCommissionRow>,
): String = buildString {
    append("Referrer,Kind,Phone,Orders,Gross,Commission %,Payable\n")
    rows.forEach { r ->
        append(csvCell(r.referrerName)).append(',')
        append(csvCell(r.kind)).append(',')
        append(csvCell(r.phone.orEmpty())).append(',')
        append(r.ordersCount).append(',')
        append(formatDecimal2(r.gross)).append(',')
        append(formatDecimal1(r.commissionPct)).append(',')
        append(formatDecimal2(r.payable)).append('\n')
    }
    append("TOTAL,,,")
    append(rows.sumOf { it.ordersCount }).append(',')
    append(formatDecimal2(rows.sumOf { it.gross })).append(",,")
    append(formatDecimal2(rows.sumOf { it.payable })).append('\n')
    append("# Range,").append(fromDate).append(" to ").append(toDate).append('\n')
}

private fun csvCell(s: String): String =
    if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

// ───────────────────────────── Date helpers ─────────────────────────────

private fun todayLocal(): LocalDate =
    kotlin.time.Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date

private fun ymd(year: Int, month1: Int, day: Int): LocalDate = LocalDate.parse(
    year.toString().padStart(4, '0') + "-" + month1.toString().padStart(2, '0') + "-" + day.toString().padStart(2, '0')
)

private fun firstOfMonth(d: LocalDate): LocalDate = ymd(d.year, d.month.ordinal + 1, 1)

/** Preset → inclusive local date range; "custom" leaves the fields alone. */
private fun presetRange(today: LocalDate, preset: String): Pair<LocalDate, LocalDate>? = when (preset) {
    "this_month" -> firstOfMonth(today) to today
    "last_month" -> {
        val firstThis = firstOfMonth(today)
        val lastPrev = firstThis.minus(DatePeriod(days = 1))
        firstOfMonth(lastPrev) to lastPrev
    }
    "this_quarter" -> {
        val qStart = ymd(today.year, (today.month.ordinal / 3) * 3 + 1, 1)
        qStart to today
    }
    else -> null
}

// ───────────────────────── Referrer add/edit dialog ─────────────────────────

/** Add/edit referrer dialog — also used as the quick-add from New order. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalUuidApi::class, ExperimentalLayoutApi::class)
@Composable
fun ReferrerFormDialog(
    initial: Referrer?,
    onDismiss: () -> Unit,
    onSaved: (Referrer) -> Unit,
    onDeleted: (() -> Unit)? = null,
) {
    val repo = LocalLabRepository.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var kind by remember { mutableStateOf(initial?.kind ?: "doctor") }
    var phone by remember { mutableStateOf(initial?.phone.orEmpty()) }
    var commission by remember { mutableStateOf(initial?.commissionPct?.takeIf { it > 0 }?.let { formatDecimal1(it) }.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New referrer" else "Edit referrer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name *") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("doctor" to "Doctor", "clinic" to "Clinic", "walk_in" to "Walk-in").forEach { (code, label) ->
                        FilterChip(selected = kind == code, onClick = { kind = code }, label = { Text(label) })
                    }
                }
                OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text("Phone") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = commission,
                    onValueChange = { commission = it.filter { c -> c.isDigit() || c == '.' }.take(6) },
                    label = { Text("Commission %") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Commission is recorded, never auto-deducted — it drives the payout " +
                        "statement on the Commission tab. Negotiated per-test prices live " +
                        "in this referrer's Rate list.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (name.trim().isBlank()) { error = "Name is required"; return@Button }
                val pct = commission.trim().toDoubleOrNull() ?: 0.0
                if (pct < 0 || pct > 100) { error = "Commission must be 0–100"; return@Button }
                scope.launch {
                    runCatching {
                        repo.upsertReferrer(
                            (initial ?: Referrer(id = Uuid.random().toString(), name = "")).copy(
                                name = name.trim(), kind = kind,
                                phone = phone.trim().ifBlank { null },
                                commissionPct = pct,
                            )
                        )
                    }.onSuccess { onSaved(it) }
                        .onFailure { error = it.message ?: "Could not save referrer" }
                }
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (initial != null && onDeleted != null) {
                    TextButton(onClick = { confirmDelete = true }) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )

    if (confirmDelete && initial != null && onDeleted != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${initial.name}?") },
            text = { Text("The referrer is hidden from pickers (soft delete); past orders keep the link, so old commission statements still add up.") },
            confirmButton = {
                Button(onClick = {
                    confirmDelete = false
                    scope.launch {
                        runCatching { repo.softDeleteReferrer(initial.id) }
                        onDeleted()
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
        )
    }
}
