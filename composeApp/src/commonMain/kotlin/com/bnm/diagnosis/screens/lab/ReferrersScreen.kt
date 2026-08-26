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
import com.bnm.diagnosis.lab.CommissionRateSheet
import com.bnm.diagnosis.lab.LabTest
import com.bnm.diagnosis.lab.LocalLabRepository
import com.bnm.diagnosis.lab.Referrer
import com.bnm.diagnosis.lab.ReferrerCommissionRow
import com.bnm.diagnosis.lab.ReferrerStatement
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
 *  1. **Referrers** — the lab-wide commission BASE, then the doctor/clinic
 *     master (name, kind, phone, commission %), and per referrer two editors:
 *     a **rate list** (per-test B2B price) and a **commission list** (per-test
 *     %). Both work the same way and for the same reason: a blank field stores
 *     NO row, so the inherited value — catalog price, or the base/referrer
 *     percentage — keeps flowing through instead of freezing at what it was
 *     the day someone opened the editor.
 *  2. **Commission** — the payout statement over a date range, drilling into a
 *     per-test breakdown and the settlements already paid. Gross AND payable
 *     are summed from the ORDER LINE SNAPSHOTS (`lab_order_tests.price` and
 *     `.commission_pct`), which carry the price and percentage as they stood at
 *     registration — the historical truth. Payable is NEVER gross × the
 *     doctor's current rate; renegotiating a rate must not restate a past month.
 *     Printable / copyable.
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
    var commissionCounts by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var basePct by remember { mutableStateOf(0.0) }
    var rateListFor by remember { mutableStateOf<Referrer?>(null) }
    var commissionListFor by remember { mutableStateOf<Referrer?>(null) }
    var editBase by remember { mutableStateOf(false) }

    LaunchedEffect(refreshKey) {
        referrers = runCatching { repo.listReferrers() }.getOrDefault(emptyList())
        rateCounts = referrers.associate { it.id to runCatching { repo.rateCount(it.id) }.getOrDefault(0L) }
        commissionCounts = referrers.associate {
            it.id to runCatching { repo.commissionRateCount(it.id) }.getOrDefault(0L)
        }
        basePct = runCatching { repo.labBaseCommissionPct() }.getOrDefault(0.0)
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { CommissionBaseCard(basePct = basePct, onEdit = { editBase = true }) }
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
                        // Inherited rates are shown as inherited, never as if the
                        // referrer had agreed that number themselves.
                        val ownPct = r.commissionPct.takeIf { it > 0 }
                        Text(
                            if (ownPct != null) "${formatDecimal1(ownPct)}%"
                            else if (basePct > 0) "${formatDecimal1(basePct)}% base" else "—",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (ownPct != null) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (ownPct != null) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val n = rateCounts[r.id] ?: 0L
                        val m = commissionCounts[r.id] ?: 0L
                        Text(
                            listOf(
                                if (n > 0L) "$n negotiated rate${if (n == 1L) "" else "s"}" else "Catalog prices",
                                if (m > 0L) "$m test commission${if (m == 1L) "" else "s"}" else null,
                            ).filterNotNull().joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { rateListFor = r }) { Text("Rate list") }
                        TextButton(onClick = { commissionListFor = r }) { Text("Commission") }
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

    commissionListFor?.let { r ->
        CommissionListDialog(
            referrer = r,
            onDismiss = { commissionListFor = null },
            onSaved = { commissionListFor = null; onChanged() },
        )
    }

    if (editBase) {
        BaseCommissionDialog(
            current = basePct,
            onDismiss = { editBase = false },
            onSaved = { editBase = false; onChanged() },
        )
    }
}

/**
 * The lab-wide base % — level one of the three-level rule. It is deliberately
 * the FIRST thing on this screen: it is the number every referrer inherits, and
 * raising it must visibly flow through to everyone who has not been priced
 * individually (nothing stores a copy of it).
 */
@Composable
private fun CommissionBaseCard(basePct: Double, onEdit: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Commission base", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    if (basePct > 0)
                        "${formatDecimal1(basePct)}% for every referrer without a rate of their own"
                    else "Not set — referrers earn only the % recorded against them",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onEdit) { Text(if (basePct > 0) "Change" else "Set") }
        }
    }
}

/** Editor for the lab-wide base %, persisted in `lab_settings`. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BaseCommissionDialog(current: Double, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val repo = LocalLabRepository.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(current.takeIf { it > 0 }?.let { formatDecimal1(it) }.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Lab-wide commission base") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter { c -> c.isDigit() || c == '.' }.take(6) },
                    label = { Text("Base commission %") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Every referrer starts here. A referrer's own % overrides the base, and a " +
                        "per-test commission overrides both. Changing the base moves everyone who " +
                        "inherits it — but never a percentage already frozen on a registered order.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val pct = text.trim().ifBlank { "0" }.toDoubleOrNull()
                if (pct == null || pct < 0 || pct > 100) { error = "Base must be 0–100"; return@Button }
                scope.launch {
                    runCatching { repo.setLabBaseCommissionPct(pct) }
                        .onSuccess { onSaved() }
                        .onFailure { error = it.message ?: "Could not save the base %" }
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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

/**
 * Per-(referrer, test) COMMISSION list — the twin of [RateListDialog], and
 * deliberately built the same way, because it obeys the same invariant: a blank
 * field stores NO row and the test keeps inheriting (referrer % → lab base). A
 * typed number — including 0 — stores an override, so "this doctor earns nothing
 * on ultrasound" is expressible even while the base is 15%.
 *
 * The inherited value is shown as the field's hint rather than pre-filled: the
 * moment we pre-fill it, saving would freeze a copy and the base would stop
 * flowing through. That is the whole bug this round exists to prevent.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CommissionListDialog(referrer: Referrer, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val repo = LocalLabRepository.current
    val scope = rememberCoroutineScope()
    var tests by remember { mutableStateOf<List<LabTest>>(emptyList()) }
    var sheet by remember { mutableStateOf(CommissionRateSheet(referrerId = referrer.id)) }
    val drafts = remember { mutableStateMapOf<String, String>() }   // testId → raw text
    var search by remember { mutableStateOf("") }
    var bulk by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(referrer.id) {
        tests = runCatching { repo.listTests() }.getOrDefault(emptyList())
        sheet = runCatching { repo.commissionSheet(referrer.id) }
            .getOrDefault(CommissionRateSheet(referrerId = referrer.id))
        drafts.clear()
        sheet.overrides.forEach { (id, pct) -> drafts[id] = formatDecimal1(pct) }
        loaded = true
    }

    val q = search.trim().lowercase()
    val visible = tests.filter {
        q.isEmpty() || it.name.lowercase().contains(q) || it.code.lowercase().contains(q) ||
            (it.category?.lowercase()?.contains(q) == true)
    }
    val overrideCount = drafts.count { it.value.trim().toDoubleOrNull() != null }
    val inherited = sheet.inheritedPct

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.widthIn(max = 620.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Commission · ${referrer.name}", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold)
                Text(
                    "Blank = inherits ${formatDecimal1(inherited)}% from ${sheet.inheritedFrom} " +
                        "(lab base ${formatDecimal1(sheet.labBasePct)}%). A number here applies to that " +
                        "test only, and takes effect on orders registered from now on.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = search, onValueChange = { search = it },
                    label = { Text("Search test name, code or category") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = bulk,
                        onValueChange = { bulk = it.filter { c -> c.isDigit() || c == '.' }.take(5) },
                        label = { Text("Set %") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.width(120.dp),
                    )
                    OutlinedButton(onClick = {
                        val pct = bulk.trim().toDoubleOrNull()
                        if (pct == null || pct < 0 || pct > 100) { error = "Percentage must be 0–100"; return@OutlinedButton }
                        error = null
                        visible.forEach { t -> drafts[t.id] = formatDecimal1(pct) }
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
                                        if (parsed != null)
                                            "${t.code} · override ${formatDecimal1(parsed)}% " +
                                                "· ₹ ${formatDecimal2(t.price * parsed / 100.0)} per test"
                                        else "${t.code} · inherits ${formatDecimal1(inherited)}%",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                OutlinedTextField(
                                    value = raw,
                                    onValueChange = { v ->
                                        val clean = v.filter { c -> c.isDigit() || c == '.' }.take(5)
                                        if (clean.isEmpty()) drafts.remove(t.id) else drafts[t.id] = clean
                                    },
                                    placeholder = {
                                        Text(formatDecimal1(inherited), style = MaterialTheme.typography.bodySmall)
                                    },
                                    suffix = { Text("%") }, singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    modifier = Modifier.width(130.dp),
                                )
                            }
                        }
                    }
                }

                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "$overrideCount override${if (overrideCount == 1) "" else "s"} · " +
                            "${tests.size} tests in catalog",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                        Button(enabled = !saving && loaded, onClick = {
                            val bad = drafts.values.mapNotNull { it.trim().toDoubleOrNull() }
                                .any { it < 0 || it > 100 }
                            if (bad) { error = "Percentages must be 0–100"; return@Button }
                            saving = true
                            scope.launch {
                                runCatching {
                                    // Diff only: new/changed percentages upsert, cleared
                                    // fields delete so the test inherits again.
                                    val wanted = drafts.mapNotNull { (id, raw) ->
                                        raw.trim().toDoubleOrNull()?.let { id to it }
                                    }.toMap()
                                    for ((id, pct) in wanted) {
                                        val before = sheet.overrides[id]
                                        if (before == null || kotlin.math.abs(before - pct) > 0.0005) {
                                            repo.setCommissionRate(referrer.id, id, pct)
                                        }
                                    }
                                    for (id in sheet.overrides.keys) {
                                        if (id !in wanted) repo.clearCommissionRate(referrer.id, id)
                                    }
                                }.onSuccess { onSaved() }
                                    .onFailure { saving = false; error = it.message ?: "Could not save the commission list" }
                            }
                        }) { Text(if (saving) "Saving…" else "Save commission list") }
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
    var paidByReferrer by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var drill by remember { mutableStateOf<ReferrerCommissionRow?>(null) }
    var statement by remember { mutableStateOf<String?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    // Bumped after a settlement is recorded so the list re-reads what is paid.
    var refresh by remember { mutableStateOf(0) }

    fun applyPreset(p: String) {
        preset = p
        val (f, t) = presetRange(today, p) ?: return
        fromText = f.toString(); toText = t.toString()
    }

    LaunchedEffect(fromText, toText, refresh) {
        loading = true
        rows = runCatching { repo.commissionReport(fromText, toText) }.getOrDefault(emptyList())
        paidByReferrer = runCatching { repo.commissionPaidByReferrer(fromText, toText) }.getOrDefault(emptyMap())
        loading = false
    }

    val grossTotal = rows.sumOf { it.gross }
    val payableTotal = rows.sumOf { it.payable }
    val orderTotal = rows.sumOf { it.ordersCount }
    // Settlements are counted for the whole period, including any referrer with
    // no orders in it (a lab often settles last month's dues this month).
    val paidTotal = paidByReferrer.values.sum()
    val blendedPct = if (grossTotal > 0) payableTotal / grossTotal * 100.0 else 0.0

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
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
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
                    HorizontalDivider()
                    // Earned vs settled — without this the statement can only ever
                    // say what is owed, never what is still owed.
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        StatCell("Blended rate", "${formatDecimal1(blendedPct)}%")
                        StatCell("Paid", "₹ ${formatDecimal2(paidTotal)}")
                        StatCell(
                            "Outstanding", "₹ ${formatDecimal2(payableTotal - paidTotal)}",
                            emphasise = payableTotal - paidTotal > 0.005,
                        )
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = rows.isNotEmpty(),
                    onClick = { statement = renderCommissionStatement(fromText, toText, rows, paid = paidByReferrer) },
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
                                "gross ₹ ${formatDecimal2(row.gross)} · earned ${formatDecimal1(row.effectivePct)}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // The EARNED rate is blended from the frozen per-line
                        // percentages, so it can differ from today's headline
                        // rate — say so rather than let it look like a bug.
                        if (kotlin.math.abs(row.effectivePct - row.commissionPct) > 0.05) {
                            Text(
                                "current rate ${formatDecimal1(row.commissionPct)}% — earlier orders were " +
                                    "registered at a different rate",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        val paid = paidByReferrer[row.referrerId] ?: 0.0
                        if (paid > 0.005) {
                            Text(
                                "paid ₹ ${formatDecimal2(paid)} · outstanding ₹ ${formatDecimal2(row.payable - paid)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text("₹ ${formatDecimal2(row.payable)}", style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    drill?.let { row ->
        ReferrerStatementDialog(
            referrerId = row.referrerId, fromDate = fromText, toDate = toText,
            onDismiss = { drill = null },
            onSettled = { refresh++ },
        )
    }

    statement?.let { body ->
        val csv = renderCommissionCsv(fromText, toText, rows, paidByReferrer)
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

/** Label over value, used across the money summaries. */
@Composable
private fun StatCell(label: String, value: String, emphasise: Boolean = false) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasise) FontWeight.Bold else FontWeight.Medium)
    }
}

/**
 * One referrer's FULL statement (feedback item 7 — "well detail"): period
 * totals, the per-test breakdown, the orders behind them (registered AND
 * reported time, item 3), and the settlements already recorded — plus the
 * button that records the next one.
 */
@Composable
private fun ReferrerStatementDialog(
    referrerId: String,
    fromDate: String,
    toDate: String,
    onDismiss: () -> Unit,
    onSettled: () -> Unit,
) {
    val repo = LocalLabRepository.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var st by remember { mutableStateOf<ReferrerStatement?>(null) }
    var loading by remember { mutableStateOf(true) }
    var section by remember { mutableStateOf(0) }     // 0 = tests, 1 = orders, 2 = payouts
    var settling by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(referrerId, fromDate, toDate, reload) {
        loading = true
        st = runCatching { repo.referrerStatement(referrerId, fromDate, toDate) }.getOrNull()
        loading = false
    }

    val s = st
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s?.referrerName ?: "Statement") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("$fromDate → $toDate", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (loading || s == null) {
                    Text(if (loading) "Loading…" else "Statement unavailable",
                        style = MaterialTheme.typography.bodySmall)
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        StatCell("Orders", "${s.ordersCount}")
                        StatCell("Gross", "₹ ${formatDecimal2(s.gross)}")
                        StatCell("Earned", "${formatDecimal1(s.effectivePct)}%")
                        StatCell("Payable", "₹ ${formatDecimal2(s.payable)}", emphasise = true)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        StatCell("Paid", "₹ ${formatDecimal2(s.paid)}")
                        StatCell("Outstanding", "₹ ${formatDecimal2(s.outstanding)}",
                            emphasise = s.outstanding > 0.005)
                        StatCell(
                            "Rate now",
                            s.headlinePct?.let { "${formatDecimal1(it)}%" }
                                ?: "${formatDecimal1(s.labBasePct)}% base",
                        )
                    }
                    HorizontalDivider()
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Per test" to 0, "Orders" to 1, "Settlements" to 2).forEach { (label, i) ->
                            FilterChip(selected = section == i, onClick = { section = i }, label = { Text(label) })
                        }
                    }
                    LazyColumn(Modifier.heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        when (section) {
                            0 -> {
                                if (s.tests.isEmpty()) item {
                                    Text("No billed tests in this range.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                items(s.tests, key = { it.testId }) { t ->
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(t.testName, style = MaterialTheme.typography.bodyMedium)
                                            Text(
                                                "×${t.timesOrdered} · gross ₹ ${formatDecimal2(t.gross)} · " +
                                                    "${formatDecimal1(t.effectivePct)}%",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        Text("₹ ${formatDecimal2(t.payable)}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                            1 -> {
                                items(s.orders, key = { it.orderId }) { o ->
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text("${o.accessionNo} · ${o.patientName}",
                                                style = MaterialTheme.typography.bodyMedium)
                                            Text(
                                                "Reg ${shortTimeLabel(o.createdAt)} · " +
                                                    (o.reportedAt?.let { "reported ${shortTimeLabel(it)}" }
                                                        ?: "not reported") + " · ${o.status}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        Text("₹ ${formatDecimal2(o.amount)}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                            else -> {
                                if (s.payouts.isEmpty()) item {
                                    Text("Nothing settled yet.", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                items(s.payouts, key = { it.id }) { p ->
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text("${p.periodFrom} → ${p.periodTo}",
                                                style = MaterialTheme.typography.bodyMedium)
                                            Text(
                                                listOfNotNull(
                                                    p.method?.replaceFirstChar { it.uppercase() },
                                                    p.paidAt?.let { shortTimeLabel(it) },
                                                    p.notes,
                                                ).joinToString(" · ").ifBlank { "settlement" },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        Text("₹ ${formatDecimal2(p.paidAmount)}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                    }
                    toast?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (s != null) {
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(renderReferrerStatement(s)))
                        toast = "Statement copied"
                    }) { Text("Copy") }
                    TextButton(onClick = {
                        val body = renderReferrerStatement(s)
                        scope.launch {
                            toast = runCatching { printReceipt("${s.referrerName} $fromDate..$toDate", body) }
                                .getOrElse { "Print failed: ${it.message}" }
                        }
                    }) { Text("Print") }
                    Button(enabled = s.outstanding > 0.005, onClick = { settling = true }) { Text("Record payment") }
                }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )

    if (settling && s != null) {
        RecordPayoutDialog(
            statement = s,
            onDismiss = { settling = false },
            onSaved = { settling = false; reload++; onSettled() },
        )
    }
}

/**
 * Record a settlement against a referrer's period. The amount defaults to the
 * outstanding balance but is free — labs pay in instalments, and each payment
 * is its own row rather than an edit of the last one, so the audit trail of
 * what was paid when survives.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun RecordPayoutDialog(
    statement: ReferrerStatement,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val repo = LocalLabRepository.current
    val scope = rememberCoroutineScope()
    var amount by remember { mutableStateOf(formatDecimal2(statement.outstanding.coerceAtLeast(0.0))) }
    var method by remember { mutableStateOf("cash") }
    var notes by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pay ${statement.referrerName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "${statement.fromDate} → ${statement.toDate} · payable ₹ ${formatDecimal2(statement.payable)} · " +
                        "already paid ₹ ${formatDecimal2(statement.paid)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' }.take(11) },
                    label = { Text("Amount paid") }, prefix = { Text("₹") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("cash" to "Cash", "upi" to "UPI", "bank" to "Bank", "adjustment" to "Adjustment")
                        .forEach { (code, label) ->
                            FilterChip(selected = method == code, onClick = { method = code }, label = { Text(label) })
                        }
                }
                OutlinedTextField(
                    value = notes, onValueChange = { notes = it.take(160) },
                    label = { Text("Note (cheque no., reference…)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "The period totals are frozen onto the payment, so entering a back-dated " +
                        "result later can never restate a settlement you have already made.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(enabled = !saving, onClick = {
                val paid = amount.trim().toDoubleOrNull()
                if (paid == null || paid <= 0) { error = "Enter the amount paid"; return@Button }
                saving = true
                scope.launch {
                    repo.recordPayout(
                        referrerId = statement.referrerId,
                        fromDate = statement.fromDate, toDate = statement.toDate,
                        gross = statement.gross, payable = statement.payable,
                        paidAmount = paid, method = method, notes = notes,
                    ).onSuccess { onSaved() }
                        .onFailure { saving = false; error = it.message ?: "Could not record the payment" }
                }
            }) { Text(if (saving) "Saving…" else "Record payment") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ─────────────────────────── Statement rendering ───────────────────────────

/** Plain-text monospace payout statement — the same text-print path the
 *  thermal slip uses (`printReceipt`), so it lands on any installed printer. */
internal fun renderCommissionStatement(
    fromDate: String,
    toDate: String,
    rows: List<ReferrerCommissionRow>,
    /** referrerId → settled in this period; drives the paid/outstanding lines. */
    paid: Map<String, Double> = emptyMap(),
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
    // Column head: name | orders | gross | earned % | payable. The percentage
    // printed is the BLENDED one actually earned in the period, not the
    // referrer's current headline rate — those differ after a renegotiation.
    ln("Referrer".padEnd(w - 34) + "Ord".padStart(4) + "Gross".padStart(12) + "Earned".padStart(6) + "Payable".padStart(12))
    rule()
    rows.forEach { r ->
        val name = r.referrerName.let { if (it.length > w - 35) it.take(w - 35) else it }
        ln(
            name.padEnd(w - 34) +
                r.ordersCount.toString().padStart(4) +
                formatDecimal2(r.gross).padStart(12) +
                formatDecimal1(r.effectivePct).padStart(6) +
                formatDecimal2(r.payable).padStart(12)
        )
        // Settled money rides a continuation line so the table stays narrow
        // enough for a 64-column thermal slip.
        val settled = paid[r.referrerId] ?: 0.0
        if (settled > 0.005) {
            ln("  paid ${formatDecimal2(settled)} · outstanding ${formatDecimal2(r.payable - settled)}".take(w))
        }
    }
    rule('=')
    val paidTotal = paid.values.sum()
    lr("Orders", rows.sumOf { it.ordersCount }.toString())
    lr("Gross billed", formatDecimal2(rows.sumOf { it.gross }))
    lr("Commission payable", formatDecimal2(rows.sumOf { it.payable }))
    if (paidTotal > 0.005) {
        lr("Already paid", formatDecimal2(paidTotal))
        lr("TOTAL OUTSTANDING", formatDecimal2(rows.sumOf { it.payable } - paidTotal))
    }
    rule('=')
    ln()
    // Keep every footer line inside `w` so the slip never wraps mid-sentence.
    listOf(
        "Gross = the billed test lines on each referred order, at the",
        "price snapshotted when the order was registered. Payable is",
        "summed per line from the commission % frozen at that moment,",
        "so a later rate change never restates this period. Cancelled",
        "orders are excluded.",
    ).forEach { ln(it.take(w)) }
    return sb.toString()
}

/**
 * ONE referrer's detailed statement: totals, the per-test breakdown, the orders
 * behind them (with reported time) and every settlement. This is the sheet a
 * lab hands the doctor, so it must explain itself without the app.
 */
internal fun renderReferrerStatement(s: ReferrerStatement, widthChars: Int = 64): String {
    val w = widthChars.coerceIn(40, 100)
    val sb = StringBuilder()
    fun ln(t: String = "") { sb.append(t.take(w)).append('\n') }
    fun rule(ch: Char = '-') = ln(ch.toString().repeat(w))
    fun center(t: String) = ln(" ".repeat(((w - t.length) / 2).coerceAtLeast(0)) + t)
    fun lr(l: String, r: String) = ln(l + " ".repeat((w - l.length - r.length).coerceAtLeast(1)) + r)

    rule('=')
    center("COMMISSION STATEMENT")
    center(s.referrerName)
    center("${s.fromDate}  to  ${s.toDate}")
    rule('=')
    lr("Orders", s.ordersCount.toString())
    lr("Gross billed", formatDecimal2(s.gross))
    lr("Earned rate (blended)", "${formatDecimal1(s.effectivePct)}%")
    lr("COMMISSION PAYABLE", formatDecimal2(s.payable))
    lr("Already paid", formatDecimal2(s.paid))
    lr("OUTSTANDING", formatDecimal2(s.outstanding))
    rule('=')
    ln()
    ln("Per test".padEnd(w - 30) + "Qty".padStart(4) + "Gross".padStart(12) + "Payable".padStart(14))
    rule()
    s.tests.forEach { t ->
        ln(
            t.testName.take(w - 31).padEnd(w - 30) +
                t.timesOrdered.toString().padStart(4) +
                formatDecimal2(t.gross).padStart(12) +
                formatDecimal2(t.payable).padStart(14)
        )
    }
    if (s.orders.isNotEmpty()) {
        ln()
        ln("Orders")
        rule()
        s.orders.forEach { o ->
            // Reported time matters to the doctor as much as registered time —
            // it is when the patient actually got the result.
            val when_ = o.createdAt.take(10) + (o.reportedAt?.let { " → rep ${it.take(10)}" } ?: "")
            ln("${o.accessionNo} ${o.patientName.take(18)}".padEnd(w - 12) + formatDecimal2(o.amount).padStart(12))
            ln("  $when_ · ${o.status}")
        }
    }
    if (s.payouts.isNotEmpty()) {
        ln()
        ln("Settlements")
        rule()
        s.payouts.forEach { p ->
            lr(
                "${p.periodFrom}..${p.periodTo} ${p.method.orEmpty()}".trim(),
                formatDecimal2(p.paidAmount),
            )
        }
    }
    ln()
    listOf(
        "Payable is summed per order line from the commission % that",
        "was frozen when the order was registered — changing a rate",
        "today never restates a period already closed.",
    ).forEach { ln(it) }
    return sb.toString()
}

/** Copy-friendly CSV of the same statement (spreadsheet paste). */
internal fun renderCommissionCsv(
    fromDate: String,
    toDate: String,
    rows: List<ReferrerCommissionRow>,
    /** referrerId → settled in this period; blank map ⇒ nothing paid yet. */
    paid: Map<String, Double> = emptyMap(),
): String = buildString {
    // New columns are APPENDED, never inserted: pasted sheets and the round-1
    // regression test both key off the original column order.
    append("Referrer,Kind,Phone,Orders,Gross,Rate %,Payable,Earned %,Paid,Outstanding\n")
    rows.forEach { r ->
        val settled = paid[r.referrerId] ?: 0.0
        append(csvCell(r.referrerName)).append(',')
        append(csvCell(r.kind)).append(',')
        append(csvCell(r.phone.orEmpty())).append(',')
        append(r.ordersCount).append(',')
        append(formatDecimal2(r.gross)).append(',')
        append(formatDecimal1(r.commissionPct)).append(',')
        append(formatDecimal2(r.payable)).append(',')
        append(formatDecimal1(r.effectivePct)).append(',')
        append(formatDecimal2(settled)).append(',')
        append(formatDecimal2(r.payable - settled)).append('\n')
    }
    val paidTotal = paid.values.sum()
    append("TOTAL,,,")
    append(rows.sumOf { it.ordersCount }).append(',')
    append(formatDecimal2(rows.sumOf { it.gross })).append(",,")
    append(formatDecimal2(rows.sumOf { it.payable })).append(",,")
    append(formatDecimal2(paidTotal)).append(',')
    append(formatDecimal2(rows.sumOf { it.payable } - paidTotal)).append('\n')
    append("# Range,").append(fromDate).append(" to ").append(toDate).append('\n')
}

private fun csvCell(s: String): String =
    if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

// ───────────────────────────── Date helpers ─────────────────────────────

/** Internal, not private: the home dashboard's commission tile ranges its
 *  month-to-date rollup with exactly the same two helpers, and the statement
 *  and the tile disagreeing about when the month starts would be a real bug. */
internal fun todayLocal(): LocalDate =
    kotlin.time.Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date

private fun ymd(year: Int, month1: Int, day: Int): LocalDate = LocalDate.parse(
    year.toString().padStart(4, '0') + "-" + month1.toString().padStart(2, '0') + "-" + day.toString().padStart(2, '0')
)

internal fun firstOfMonth(d: LocalDate): LocalDate = ymd(d.year, d.month.ordinal + 1, 1)

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
    /**
     * False hides the commission field entirely.
     *
     * This dialog is reused by NewOrderScreen's quick-add during registration,
     * which is deliberately open to everyone — so without this a receptionist who
     * can never reach the (gated) Referrers hub could still read and edit what the
     * lab pays its doctors. Defaults to true so the Referrers hub, which is
     * already behind the MONEY gate, needs no change.
     */
    canSeeCommission: Boolean = true,
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
                if (canSeeCommission) {
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
                }
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
