package com.bnm.diagnosis.screens.lab

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import com.bnm.diagnosis.lab.Patient
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.launch

/** Patient master: search + list + add/edit sheet (age & sex mandatory —
 *  reference ranges depend on them). Delete is a soft-delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatientsScreen(onBack: () -> Unit) {
    val repo = LocalLabRepository.current
    var query by remember { mutableStateOf("") }
    var patients by remember { mutableStateOf<List<Patient>>(emptyList()) }
    var refresh by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf<Patient?>(null) }
    var showForm by remember { mutableStateOf(false) }

    LaunchedEffect(query, refresh) {
        patients = runCatching { repo.searchPatients(query) }.getOrDefault(emptyList())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Patients") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = null; showForm = true }) {
                Icon(Icons.Default.Add, contentDescription = "New patient")
            }
        },
    ) { inner ->
        Column(Modifier.padding(inner).fillMaxSize()) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                label = { Text("Search name or phone") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(patients, key = { it.id }) { p ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { editing = p; showForm = true },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    ) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text(p.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                Text(
                                    ageSexLabel(p.dob, p.ageYears, p.sex) +
                                        (p.phone?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showForm) {
        PatientFormDialog(
            initial = editing,
            onDismiss = { showForm = false },
            onSaved = { showForm = false; refresh++ },
            onDeleted = { showForm = false; refresh++ },
        )
    }
}

/** Add/edit patient dialog — shared FormSheet-style card. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalUuidApi::class, ExperimentalLayoutApi::class)
@Composable
fun PatientFormDialog(
    initial: Patient?,
    onDismiss: () -> Unit,
    onSaved: (Patient) -> Unit,
    onDeleted: (() -> Unit)? = null,
) {
    val repo = LocalLabRepository.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var sex by remember { mutableStateOf(initial?.sex) }
    var ageText by remember { mutableStateOf(initial?.ageYears?.toString().orEmpty()) }
    var dob by remember { mutableStateOf(initial?.dob.orEmpty()) }
    var phone by remember { mutableStateOf(initial?.phone.orEmpty()) }
    var address by remember { mutableStateOf(initial?.address.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New patient" else "Edit patient") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Age & sex are required — reference ranges depend on them.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Full name *") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("M" to "Male", "F" to "Female", "O" to "Other").forEach { (code, label) ->
                        FilterChip(selected = sex == code, onClick = { sex = code }, label = { Text(label) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = ageText, onValueChange = { ageText = it.filter { c -> c.isDigit() }.take(3) },
                        label = { Text("Age (years) *") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f))
                    OutlinedTextField(value = dob, onValueChange = { dob = it }, label = { Text("or DOB (YYYY-MM-DD)") },
                        singleLine = true, modifier = Modifier.weight(1.3f))
                }
                OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text("Phone") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("Address") },
                    modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val dobClean = dob.trim().ifBlank { null }
                val age = ageText.trim().toLongOrNull()
                when {
                    name.trim().isBlank() -> error = "Name is required"
                    sex == null -> error = "Sex is required"
                    dobClean == null && age == null -> error = "Enter the age in years, or a DOB"
                    dobClean != null && runCatching { kotlinx.datetime.LocalDate.parse(dobClean.take(10)) }.isFailure ->
                        error = "DOB must be YYYY-MM-DD"
                    else -> {
                        error = null
                        scope.launch {
                            runCatching {
                                repo.upsertPatient(
                                    (initial ?: Patient(id = Uuid.random().toString(), name = "")).copy(
                                        name = name.trim(), sex = sex!!,
                                        dob = dobClean, ageYears = if (dobClean == null) age else null,
                                        phone = phone.trim().ifBlank { null },
                                        address = address.trim().ifBlank { null },
                                    )
                                )
                            }.onSuccess { onSaved(it) }
                                .onFailure { error = it.message ?: "Could not save patient" }
                        }
                    }
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
            text = { Text("The patient is hidden from search (soft delete); past orders and reports keep their data.") },
            confirmButton = {
                Button(onClick = {
                    confirmDelete = false
                    scope.launch {
                        runCatching { repo.softDeletePatient(initial.id) }
                        onDeleted()
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
        )
    }
}
