package com.bnm.diagnosis.screens.lab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bnm.diagnosis.lab.EmrInboxItem
import com.bnm.diagnosis.lab.LabRepository
import com.bnm.diagnosis.lab.LocalLabRepository

/**
 * P3 EMR bridge inbox: clinic lab orders routed to this lab. A row is either
 * UNREGISTERED (Register → NewOrderScreen pre-filled) or REGISTERED (accession
 * shown; the result reports back automatically once the local order is
 * approved). Rows are written by LabSyncEngine.
 *
 * P3b: rows that carry the clinic's identity block show WHO the order is for —
 * name · age/sex · phone · visit no. — so the desk can find the walk-in before
 * anyone looks at the badge (that's what the search field is for; it filters
 * the ALREADY-LOADED rows, no endpoint, works offline). Legacy rows without
 * demographics render exactly as they always did.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EmrInboxScreen(
    onBack: () -> Unit,
    onRegister: (emrId: String) -> Unit,
) {
    val repo = LocalLabRepository.current
    var rows by remember { mutableStateOf<List<EmrInboxItem>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        rows = runCatching { repo.emrOpen() }.getOrDefault(emptyList())
        loaded = true
    }

    // Local filter over the loaded rows — name / phone (digits too) / visit no.
    // / test name+code. Every term must hit, so "raj 9876" narrows.
    val visible = remember(rows, query) {
        val terms = query.trim().lowercase().split(' ').filter { it.isNotBlank() }
        if (terms.isEmpty()) rows
        else rows.filter { row -> row.searchBlob().let { b -> terms.all { t -> b.contains(t) } } }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("EMR orders") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { inner ->
        if (loaded && rows.isEmpty()) {
            Box(Modifier.padding(inner).fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No open EMR orders", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)
                    Text("Clinic orders routed to this lab appear here after a sync.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp))
                }
            }
            return@Scaffold
        }
        Column(Modifier.padding(inner).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Find the patient — name, phone, visit no. or test") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (visible.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No EMR order matches \"${query.trim()}\"",
                            style = MaterialTheme.typography.bodyMedium)
                        Text("${rows.size} open order${if (rows.size == 1) "" else "s"} in the inbox.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                }
                return@Column
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(visible, key = { it.id }) { row ->
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) { EmrRowIdentity(row) }
                            if (row.matchedOrderId == null) {
                                Button(onClick = { onRegister(row.id) }) { Text("Register") }
                            } else {
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(row.accessionNo ?: "Registered",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                                    Text("In progress — reports back once approved",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The left half of an inbox row. WITH identity: patient headline, then
 * age/sex · phone, then the test (+ code chip), then visit no. · ordered date.
 * WITHOUT (legacy row): the original test-first layout, unchanged — nothing to
 * show is better than a row full of em dashes.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmrRowIdentity(row: EmrInboxItem) {
    if (row.hasIdentity) {
        Text(
            row.patientName?.takeIf { it.isNotBlank() } ?: "Unnamed patient",
            style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
        )
        val ageSex = listOfNotNull(
            LabRepository.ageYearsFromDob(row.patientDob)?.let { "${it}y" },
            row.patientSex?.takeIf { it.isNotBlank() }?.uppercase(),
        ).joinToString(" / ")
        val line2 = listOfNotNull(
            ageSex.takeIf { it.isNotBlank() },
            row.patientPhone?.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
        if (line2.isNotBlank()) {
            Text(line2, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.padding(top = if (row.hasIdentity) 4.dp else 0.dp),
    ) {
        Text(
            row.testName,
            style = if (row.hasIdentity) MaterialTheme.typography.bodyMedium
            else MaterialTheme.typography.bodyLarge,
            fontWeight = if (row.hasIdentity) FontWeight.Medium else FontWeight.SemiBold,
        )
        row.testCode?.takeIf { it.isNotBlank() }?.let { CodeChip(it) }
    }
    row.instructions?.takeIf { it.isNotBlank() }?.let {
        Text(it, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val footer = listOfNotNull(
        row.visitNumber?.takeIf { it.isNotBlank() }?.let { "Visit $it" },
        row.createdAt?.takeIf { it.length >= 10 }?.let { "Ordered ${it.take(10)}" },
    ).joinToString(" · ")
    if (footer.isNotBlank()) {
        Text(footer, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The doctor's catalog pick, shown verbatim — this is what the desk matches on. */
@Composable
private fun CodeChip(code: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(6.dp)) {
        Text(
            code, style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}
