package com.bnm.diagnosis.screens.staff

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
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.bnm.diagnosis.staff.LocalStaffRepository
import com.bnm.diagnosis.staff.LocalStaffSession
import com.bnm.diagnosis.staff.Staff
import com.bnm.diagnosis.staff.StaffRepository
import com.bnm.diagnosis.staff.StaffRole
import kotlinx.coroutines.launch

/**
 * Staff & roles (P4) — OWNER ONLY. Add people, set their role, set or clear a
 * PIN, and retire the ones who left.
 *
 * Retiring is `active = false`, never a delete: every result carries the name of
 * whoever entered, verified and approved it, and that attribution has to stay
 * readable long after the person leaves the lab.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StaffScreen(onBack: () -> Unit) {
    val repo = LocalStaffRepository.current
    val session = LocalStaffSession.current
    val signedIn by session.current.collectAsState()
    val scope = rememberCoroutineScope()
    val staff by remember(repo) { repo.listAllFlow() }.collectAsState(emptyList())

    var editing by remember { mutableStateOf<Staff?>(null) }   // null = dialog closed
    var creating by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    // Defence in depth: the Settings card already hides this from non-owners.
    val isOwner = signedIn?.canManageStaff == true

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Staff & roles") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            if (isOwner) ExtendedFloatingActionButton(
                onClick = { creating = true; editing = null },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Add person") },
            )
        },
    ) { inner ->
        if (!isOwner) {
            Box(Modifier.padding(inner).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Only the lab owner can manage staff and roles.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }
        LazyColumn(
            Modifier.padding(inner).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "Everyone who works this lab. Roles decide what they may do — only a " +
                        "pathologist (or the owner) can approve results. People who leave are " +
                        "deactivated, never deleted, so their name stays readable on old reports.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            message?.let {
                item { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
            }
            items(staff, key = { it.id }) { s ->
                StaffRow(
                    staff = s,
                    isSelf = s.id == signedIn?.id,
                    onEdit = { editing = s; creating = false },
                    onToggleActive = {
                        scope.launch {
                            repo.setActive(s.id, !s.active)
                                .onSuccess {
                                    message = if (s.active) "${s.name} deactivated" else "${s.name} reactivated"
                                    repo.byId(s.id)?.let { session.refresh(it) }
                                }
                                .onFailure { message = it.message }
                        }
                    },
                )
            }
        }
    }

    if (creating || editing != null) {
        StaffEditDialog(
            existing = editing,
            onDismiss = { creating = false; editing = null },
            onSave = { name, role, pinAction ->
                val target = editing
                scope.launch {
                    val saved = repo.upsert(
                        target?.copy(name = name, role = role)
                            ?: Staff(id = "", name = name, role = role)
                    )
                    when (pinAction) {
                        is PinAction.Keep -> Unit
                        is PinAction.Clear -> repo.setPin(saved.id, null)
                            .onFailure { message = it.message }
                        is PinAction.Set -> repo.setPin(saved.id, pinAction.pin)
                            .onFailure { message = it.message }
                    }
                    repo.byId(saved.id)?.let { session.refresh(it) }
                    message = "${saved.name} saved"
                    creating = false; editing = null
                }
            },
        )
    }
}

@Composable
private fun StaffRow(staff: Staff, isSelf: Boolean, onEdit: () -> Unit, onToggleActive: () -> Unit) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (staff.active) MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.surface
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        staff.name, style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (staff.active) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    RoleChip(staff.role)
                    if (isSelf) Text("(you)", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    buildString {
                        append(if (staff.hasPin) "PIN set" else "No PIN")
                        if (!staff.active) append(" · deactivated")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onEdit) { Text("Edit") }
            OutlinedButton(onClick = onToggleActive) {
                Text(if (staff.active) "Deactivate" else "Reactivate")
            }
        }
    }
}

/** What to do with the PIN when the dialog is saved. */
private sealed interface PinAction {
    data object Keep : PinAction
    data object Clear : PinAction
    data class Set(val pin: String) : PinAction
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StaffEditDialog(
    existing: Staff?,
    onDismiss: () -> Unit,
    onSave: (name: String, role: String, pin: PinAction) -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var role by remember { mutableStateOf(existing?.role ?: StaffRole.RECEPTIONIST) }
    var pin by remember { mutableStateOf("") }
    var clearPin by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add person" else "Edit ${existing.name}") },
        text = {
            Column(Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Full name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Role", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StaffRole.ALL.forEach { r ->
                        FilterChip(selected = role == r, onClick = { role = r }, label = { Text(StaffRole.label(r)) })
                    }
                }
                Text(StaffRole.describe(role), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)

                OutlinedTextField(
                    value = pin, onValueChange = { v -> pin = v.filter { it.isDigit() }.take(8); clearPin = false },
                    label = {
                        Text(
                            if (existing?.hasPin == true) "New PIN (blank = keep current)"
                            else "PIN (blank = no PIN, tap to enter)"
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "A PIN keeps someone else from signing in as this person on a shared lab PC. " +
                        "It is convenience, not security — the lab's data lives on this machine either way.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (existing?.hasPin == true) {
                    TextButton(onClick = { clearPin = !clearPin; if (clearPin) pin = "" }) {
                        Text(if (clearPin) "PIN will be removed — undo" else "Remove this person's PIN")
                    }
                }
                err?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val n = name.trim()
                if (n.isEmpty()) { err = "Name is required"; return@Button }
                if (pin.isNotBlank() && pin.length < StaffRepository.MIN_PIN) {
                    err = "PIN must be at least ${StaffRepository.MIN_PIN} digits"; return@Button
                }
                val action = when {
                    clearPin -> PinAction.Clear
                    pin.isNotBlank() -> PinAction.Set(pin)
                    else -> PinAction.Keep
                }
                onSave(n, role, action)
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
