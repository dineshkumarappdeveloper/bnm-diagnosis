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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import com.bnm.diagnosis.lab.LabPanel
import com.bnm.diagnosis.lab.LabTest
import com.bnm.diagnosis.lab.LocalLabRepository
import com.bnm.diagnosis.util.formatDecimal2
import kotlinx.coroutines.launch

private val SAMPLE_TYPES = listOf("blood", "serum", "urine", "stool", "swab", "other")

/**
 * Test catalog: tests (search, price/active/sample edits) + panels (price
 * edits). Parameters & reference ranges are seed-data-managed this phase —
 * no range editor here, so entered/printed history can't drift by accident.
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

/** Edit sheet: price + active + sample type. Parameters/ranges stay seed-managed. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TestEditDialog(test: LabTest, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val repo = LocalLabRepository.current
    val scope = rememberCoroutineScope()
    var price by remember { mutableStateOf(formatDecimal2(test.price).removeSuffix(".00")) }
    var sample by remember { mutableStateOf(test.sampleType) }
    var active by remember { mutableStateOf(test.active) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(test.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "${test.code}${test.category?.let { " · $it" } ?: ""} · ${test.parameters.size} parameters (ranges are seed-managed this phase)",
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
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val p = price.trim().toDoubleOrNull()
                if (p == null || p < 0) { error = "Enter a valid price"; return@Button }
                scope.launch {
                    runCatching { repo.upsertTest(test.copy(price = p, sampleType = sample, active = active)) }
                        .onSuccess { onSaved() }
                        .onFailure { error = it.message ?: "Could not save" }
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
