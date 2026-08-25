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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bnm.diagnosis.lab.LocalLabRepository
import com.bnm.diagnosis.lab.Referrer
import com.bnm.diagnosis.util.formatDecimal1
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.launch

/** Referrer master: doctors/clinics who send patients (commission % recorded
 *  for later payout reports; nothing is auto-deducted anywhere). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReferrersScreen(onBack: () -> Unit) {
    val repo = LocalLabRepository.current
    var referrers by remember { mutableStateOf<List<Referrer>>(emptyList()) }
    var refresh by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf<Referrer?>(null) }
    var showForm by remember { mutableStateOf(false) }

    LaunchedEffect(refresh) {
        referrers = runCatching { repo.listReferrers() }.getOrDefault(emptyList())
    }

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
            FloatingActionButton(onClick = { editing = null; showForm = true }) {
                Icon(Icons.Default.Add, contentDescription = "New referrer")
            }
        },
    ) { inner ->
        LazyColumn(
            Modifier.padding(inner).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(referrers, key = { it.id }) { r ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { editing = r; showForm = true },
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                ) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
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
                }
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
            text = { Text("The referrer is hidden from pickers (soft delete); past orders keep the link.") },
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
