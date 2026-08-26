package com.bnm.diagnosis.screens.lab

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.bnm.diagnosis.lab.LabPanel
import com.bnm.diagnosis.lab.LabRepository
import com.bnm.diagnosis.lab.LabTest
import com.bnm.diagnosis.lab.LocalLabRepository
import com.bnm.diagnosis.lab.RefRange
import com.bnm.diagnosis.lab.TestParameter
import com.bnm.diagnosis.util.formatDecimal2
import kotlin.math.floor
import kotlinx.coroutines.launch

private val SAMPLE_TYPES = listOf("blood", "serum", "urine", "stool", "swab", "other")

/**
 * Test catalog: tests (search, price/active/sample edits, per-parameter
 * REFERENCE RANGES) + panels (price edits).
 *
 * Editing a range only changes what FUTURE results are judged against:
 * `lab_results.flag` and `ref_display` are frozen onto the row at entry, so no
 * already-entered result and no reprinted report can drift under a lab that
 * corrects a range today.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(onBack: () -> Unit) {
    val repo = LocalLabRepository.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(0) }
    var search by remember { mutableStateOf("") }
    var tests by remember { mutableStateOf<List<LabTest>>(emptyList()) }
    var panels by remember { mutableStateOf<List<LabPanel>>(emptyList()) }
    var refresh by remember { mutableStateOf(0) }
    var editTest by remember { mutableStateOf<LabTest?>(null) }
    var editPanel by remember { mutableStateOf<LabPanel?>(null) }

    LaunchedEffect(refresh) {
        tests = runCatching { repo.listTests(includeInactive = true) }.getOrDefault(emptyList())
        panels = runCatching { repo.listPanels(includeInactive = true) }.getOrDefault(emptyList())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Test catalog") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { inner ->
        Column(Modifier.padding(inner).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Tests (${tests.size})") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Panels (${panels.size})") })
            }
            if (tab == 0) {
                OutlinedTextField(
                    value = search, onValueChange = { search = it },
                    label = { Text("Search name, code or category") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                val q = search.trim().lowercase()
                val visible = tests.filter {
                    q.isEmpty() || it.name.lowercase().contains(q) || it.code.lowercase().contains(q) ||
                        (it.category?.lowercase()?.contains(q) == true)
                }
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(visible, key = { it.id }) { t ->
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable { editTest = t },
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                        ) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(t.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        listOfNotNull(t.code, t.category, t.sampleType).joinToString(" · ") +
                                            " · ${t.parameters.size} param${if (t.parameters.size == 1) "" else "s"}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text("₹ ${formatDecimal2(t.price)}", style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(end = 10.dp))
                                Switch(
                                    checked = t.active,
                                    onCheckedChange = { on ->
                                        scope.launch {
                                            runCatching { repo.setTestActive(t.id, on) }
                                            refresh++
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(panels, key = { it.id }) { p ->
                        val names = p.testIds.mapNotNull { id -> tests.firstOrNull { it.id == id }?.name }
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable { editPanel = p },
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                        ) {
                            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(p.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "${p.code} · ${p.testIds.size} tests" +
                                            (names.takeIf { it.isNotEmpty() }?.let { ": ${it.take(4).joinToString(", ")}${if (it.size > 4) "…" else ""}" } ?: ""),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text("₹ ${formatDecimal2(p.price)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
    }

    editTest?.let { t ->
        TestEditDialog(t, onDismiss = { editTest = null }, onSaved = { editTest = null; refresh++ })
    }
    editPanel?.let { p ->
        PanelEditDialog(p, onDismiss = { editPanel = null }, onSaved = { editPanel = null; refresh++ })
    }
}

/**
 * Edit sheet: price + active + sample type + each parameter's reference ranges.
 *
 * Range edits are held in [params] and written by the one Save below, so a
 * half-finished range list is never persisted — and the whole test goes out in
 * a single `upsertTest` (parameters live in one `parameters_json` blob).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TestEditDialog(test: LabTest, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val repo = LocalLabRepository.current
    val scope = rememberCoroutineScope()
    var price by remember { mutableStateOf(formatDecimal2(test.price).removeSuffix(".00")) }
    var sample by remember { mutableStateOf(test.sampleType) }
    var active by remember { mutableStateOf(test.active) }
    var params by remember { mutableStateOf(test.parameters) }
    var editParamIndex by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(test.name) },
        text = {
            Column(
                Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "${test.code}${test.category?.let { " · $it" } ?: ""} · ${params.size} parameter${if (params.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = price,
                    onValueChange = { price = it.filter { c -> c.isDigit() || c == '.' }.take(9) },
                    label = { Text("Price (₹)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Sample type", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SAMPLE_TYPES.forEach { s ->
                        FilterChip(selected = sample == s, onClick = { sample = s }, label = { Text(s) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Active (orderable)", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = active, onCheckedChange = { active = it })
                }

                if (params.isNotEmpty()) {
                    HorizontalDivider()
                    Text("Reference ranges", style = MaterialTheme.typography.labelMedium)
                    Text(
                        "Applies to results entered from now on — flags already printed stay as they were.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    params.forEachIndexed { i, param ->
                        val warnings = rangeWarnings(param.ranges)
                        Row(
                            Modifier.fillMaxWidth().clickable { editParamIndex = i }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    param.name + (param.unit?.let { " ($it)" } ?: ""),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    rangeSummary(param),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (warnings.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.error,
                                )
                            }
                            TextButton(onClick = { editParamIndex = i }) { Text("Edit") }
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val p = price.trim().toDoubleOrNull()
                if (p == null || p < 0) { error = "Enter a valid price"; return@Button }
                scope.launch {
                    runCatching {
                        repo.upsertTest(test.copy(price = p, sampleType = sample, active = active, parameters = params))
                    }
                        .onSuccess { onSaved() }
                        .onFailure { error = it.message ?: "Could not save" }
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    editParamIndex?.let { i ->
        params.getOrNull(i)?.let { param ->
            ParamRangesDialog(
                param = param,
                onDismiss = { editParamIndex = null },
                onApply = { updated ->
                    params = params.toMutableList().also { it[i] = updated }
                    editParamIndex = null
                },
            )
        }
    }
}

/**
 * The range list of ONE parameter. List position carries no meaning:
 * `LabRepository.pickRange` chooses by specificity, so adding a range in the
 * middle or at the end cannot change which one a given patient is judged by.
 * Edits stay local to this dialog until Done, and are still only persisted by
 * the parent's Save.
 */
@Composable
private fun ParamRangesDialog(param: TestParameter, onDismiss: () -> Unit, onApply: (TestParameter) -> Unit) {
    var ranges by remember(param.key) { mutableStateOf(param.ranges) }
    // null = closed; -1 = adding a new range; >= 0 = editing that index.
    var editIndex by remember { mutableStateOf<Int?>(null) }

    val warnings = rangeWarnings(ranges)
    val sexSplitOnly = ranges.isNotEmpty() && ranges.all { it.sex != null }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.widthIn(max = 620.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    param.name + (param.unit?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "A result is judged against the MOST SPECIFIC range the patient matches: " +
                        "an age band beats an open range, then a sex-specific range beats a sex-neutral " +
                        "one, then the narrower band wins. A patient matching nothing here is never flagged.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider()

                if (ranges.isEmpty()) {
                    Text(
                        "No ranges — results for this parameter print without a range and carry no flag.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ranges.forEachIndexed { i, r ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("${i + 1}. ${scopeLabel(r)}", style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        valueLabel(r, param.decimals),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(onClick = { editIndex = i }) { Text("Edit") }
                                TextButton(onClick = {
                                    ranges = ranges.toMutableList().also { it.removeAt(i) }
                                }) { Text("Remove") }
                            }
                        }
                    }
                }

                warnings.forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                // Sex 'O' matches sex-neutral ranges ONLY, so an all-M/F list silently
                // leaves those patients unflagged. The lab decides the clinical answer;
                // this only makes the hole visible and the fallback one tap away.
                if (sexSplitOnly) {
                    Text(
                        "Every range here is Male- or Female-only, so a patient recorded as Other " +
                            "matches none of them and their results won't be flagged. Add a range with " +
                            "sex \"Any\" as the fallback if your lab wants one.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { editIndex = -1 }) { Text("+ Add range") }
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                        Button(onClick = { onApply(param.copy(ranges = ranges)) }) { Text("Done") }
                    }
                }
            }
        }
    }

    editIndex?.let { i ->
        RangeEditDialog(
            initial = ranges.getOrNull(i) ?: RefRange(),
            decimals = param.decimals,
            onDismiss = { editIndex = null },
            onSave = { r ->
                ranges = if (i < 0) ranges + r else ranges.toMutableList().also { it[i] = r }
                editIndex = null
            },
        )
    }
}

/** One range: who it applies to, its limits, and the validation that keeps it sane. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun RangeEditDialog(initial: RefRange, decimals: Int, onDismiss: () -> Unit, onSave: (RefRange) -> Unit) {
    var sex by remember { mutableStateOf(initial.sex?.uppercase()) }
    var ageMin by remember { mutableStateOf(initial.ageMinY.toField()) }
    var ageMax by remember { mutableStateOf(initial.ageMaxY.toField()) }
    var low by remember { mutableStateOf(initial.low.toField()) }
    var high by remember { mutableStateOf(initial.high.toField()) }
    var critLow by remember { mutableStateOf(initial.criticalLow.toField()) }
    var critHigh by remember { mutableStateOf(initial.criticalHigh.toField()) }
    var text by remember { mutableStateOf(initial.text.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }

    fun draft() = RefRange(
        sex = sex,
        ageMinY = ageMin.toBound(), ageMaxY = ageMax.toBound(),
        low = low.toBound(), high = high.toBound(),
        criticalLow = critLow.toBound(), criticalHigh = critHigh.toBound(),
        text = text.trim().ifBlank { null },
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == RefRange()) "Add reference range" else "Edit reference range") },
        text = {
            Column(
                Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Applies to", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(null to "Any sex", "M" to "Male", "F" to "Female").forEach { (v, label) ->
                        FilterChip(selected = sex == v, onClick = { sex = v }, label = { Text(label) })
                    }
                }
                Text(
                    "Leave the age boxes empty for an all-ages range. Age bands are in YEARS and " +
                        "may be fractional (0.077 ≈ 4 weeks); both ends are inclusive, so make one " +
                        "band end where the next begins — an age with no band at all is never flagged.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField(ageMin, { ageMin = it }, "Age from (y)", Modifier.weight(1f))
                    NumField(ageMax, { ageMax = it }, "Age to (y)", Modifier.weight(1f))
                }
                HorizontalDivider()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField(low, { low = it }, "Normal low", Modifier.weight(1f))
                    NumField(high, { high = it }, "Normal high", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField(critLow, { critLow = it }, "Critical low", Modifier.weight(1f))
                    NumField(critHigh, { critHigh = it }, "Critical high", Modifier.weight(1f))
                }
                Text(
                    "One-sided is fine: fill only the high box for a \"< 200\" range. Criticals are " +
                        "optional and must sit OUTSIDE the normal limits — they are what gets phoned out.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Rendered by the same function the report uses, so what the operator
                // reads here is literally what will print on the patient's report.
                Text(
                    "Prints as: ${LabRepository.refDisplay(draft(), decimals) ?: LabRepository.NO_RANGE}",
                    style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
                )
                HorizontalDivider()
                OutlinedTextField(
                    value = text, onValueChange = { text = it.take(60) },
                    label = { Text("Expected value (qualitative)") },
                    placeholder = { Text("Non-reactive") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "For tests that read positive/negative instead of a number. A result matching " +
                        "this text flags N, anything else flags A (abnormal).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val r = draft()
                val problem = validateRange(r)
                if (problem != null) { error = problem; return@Button }
                onSave(r)
            }) { Text("Save range") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun NumField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.filter { c -> c.isDigit() || c == '.' }.take(12)) },
        label = { Text(label, style = MaterialTheme.typography.bodySmall) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

// ── range formatting + validation ────────────────────────────────────────────

/** Blank field = "not set", which is meaningful (an open bound), not zero. */
private fun String.toBound(): Double? = trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()

private fun Double?.toField(): String {
    val v = this ?: return ""
    return if (v == floor(v)) v.toLong().toString() else v.toString()
}

private fun sexLabel(sex: String?): String {
    val s = sex?.uppercase() ?: return "Any sex"
    return when (s) {
        "M" -> "Male"
        "F" -> "Female"
        else -> s
    }
}

/** Whole numbers print bare (a "12 y" band, a 20000 critical), fractions to 2dp. */
private fun num(v: Double): String = if (v == floor(v)) v.toLong().toString() else formatDecimal2(v)

/** "Male · 12–18 y" — who this range applies to, in one line. */
private fun scopeLabel(r: RefRange): String {
    val age = when {
        r.ageMinY != null && r.ageMaxY != null -> "${num(r.ageMinY!!)}–${num(r.ageMaxY!!)} y"
        r.ageMaxY != null -> "up to ${num(r.ageMaxY!!)} y"
        r.ageMinY != null -> "${num(r.ageMinY!!)} y and over"
        else -> "all ages"
    }
    return "${sexLabel(r.sex)} · $age"
}

/** The range as the report prints it, plus the critical limits behind it. */
private fun valueLabel(r: RefRange, decimals: Int): String {
    val main = LabRepository.refDisplay(r, decimals) ?: "no limits set"
    val crit = listOfNotNull(
        r.criticalLow?.let { "< ${num(it)}" },
        r.criticalHigh?.let { "> ${num(it)}" },
    )
    return if (crit.isEmpty()) main else "$main · critical ${crit.joinToString(" or ")}"
}

/** The parameter row's subtitle: how many ranges, and whether they split by sex or age. */
private fun rangeSummary(param: TestParameter): String {
    if (param.ranges.isEmpty()) return "No range — results never flagged"
    val banded = param.ranges.count { it.ageMinY != null || it.ageMaxY != null }
    val split = param.ranges.count { it.sex != null }
    return buildString {
        append("${param.ranges.size} range${if (param.ranges.size == 1) "" else "s"}")
        if (split > 0) append(" · sex-split")
        if (banded > 0) append(" · $banded age band${if (banded == 1) "" else "s"}")
        if (rangeWarnings(param.ranges).isNotEmpty()) append(" · overlapping")
    }
}

/** Upper age used when a range has no `ageMaxY`, matching `LabRepository.bandWidth`. */
private const val AGE_OPEN = 200.0

/** Rejects a range the picker or the flag rule could not use sensibly. */
private fun validateRange(r: RefRange): String? {
    val hasText = !r.text.isNullOrBlank()
    val hasNumeric = r.low != null || r.high != null || r.criticalLow != null || r.criticalHigh != null
    // computeFlag() short-circuits on `text` and never looks at the numbers, so a
    // range carrying both would silently ignore half of what the operator typed.
    if (hasText && hasNumeric) return "Use either numeric limits or an expected value — not both"
    if (!hasText && !hasNumeric) return "Enter at least one limit, or an expected value"
    if (r.ageMinY != null && r.ageMaxY != null && r.ageMinY!! > r.ageMaxY!!) return "Age from must be at or below age to"
    if (r.low != null && r.high != null && r.low!! > r.high!!) return "Normal low must be at or below normal high"
    if (r.criticalLow != null && r.low != null && r.criticalLow!! > r.low!!) return "Critical low must be at or below normal low"
    if (r.criticalHigh != null && r.high != null && r.criticalHigh!! < r.high!!) return "Critical high must be at or above normal high"
    if (r.criticalLow != null && r.criticalHigh != null && r.criticalLow!! > r.criticalHigh!!) {
        return "Critical low must be at or below critical high"
    }
    return null
}

/**
 * Overlaps `pickRange` resolves SILENTLY, surfaced so a mistyped band is caught
 * here rather than in a patient's report.
 *
 * Only same-sex, same-layer pairs are reported: an age band deliberately covers
 * the same patients as the all-ages range beneath it, and a sex-specific range
 * deliberately covers the same patients as a sex-neutral one — those layers are
 * the feature, not a bug. Two ranges with the SAME sex whose age windows truly
 * intersect are the ambiguous case; a shared endpoint (one band ending where the
 * next begins) intersects at a single point and is contiguous by design, so it
 * is not reported.
 */
private fun rangeWarnings(ranges: List<RefRange>): List<String> {
    val out = mutableListOf<String>()
    for (i in ranges.indices) {
        for (j in i + 1 until ranges.size) {
            val a = ranges[i]
            val b = ranges[j]
            if (a.sex?.uppercase() != b.sex?.uppercase()) continue
            val lo = maxOf(a.ageMinY ?: 0.0, b.ageMinY ?: 0.0)
            val hi = minOf(a.ageMaxY ?: AGE_OPEN, b.ageMaxY ?: AGE_OPEN)
            if (hi - lo > 0.0) {
                out += "Ranges ${i + 1} and ${j + 1} overlap for ${sexLabel(a.sex).lowercase()} — " +
                    "the narrower band silently wins. Fix the age bands unless that is what you meant."
            }
        }
    }
    return out
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PanelEditDialog(panel: LabPanel, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val repo = LocalLabRepository.current
    val scope = rememberCoroutineScope()
    var price by remember { mutableStateOf(formatDecimal2(panel.price).removeSuffix(".00")) }
    var active by remember { mutableStateOf(panel.active) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(panel.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${panel.code} · ${panel.testIds.size} tests. Ordering a panel bills its tests individually this phase.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = price,
                    onValueChange = { price = it.filter { c -> c.isDigit() || c == '.' }.take(9) },
                    label = { Text("Panel price (₹)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Active (orderable)", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = active, onCheckedChange = { active = it })
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val p = price.trim().toDoubleOrNull()
                if (p == null || p < 0) { error = "Enter a valid price"; return@Button }
                scope.launch {
                    runCatching { repo.upsertPanel(panel.copy(price = p, active = active)) }
                        .onSuccess { onSaved() }
                        .onFailure { error = it.message ?: "Could not save" }
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
