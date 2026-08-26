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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.bnm.diagnosis.staff.LocalStaffRepository
import com.bnm.diagnosis.staff.LocalStaffSession
import com.bnm.diagnosis.staff.Staff
import com.bnm.diagnosis.staff.StaffCredential
import com.bnm.diagnosis.staff.StaffRepository
import com.bnm.diagnosis.staff.StaffRole
import kotlinx.coroutines.launch

/**
 * Staff & roles (P4) — OWNER ONLY. Add people, set their role, give them a
 * username + password (or a PIN, or neither), and retire the ones who left.
 *
 * This is the answer to round-1 feedback item 2: "how to add users … set username
 * and password for an employee who won't see the commission". The *role* picked
 * here is the whole of that second half — commission, payouts, negotiated rates
 * and catalog prices are owner-only, enforced at the nav layer by
 * [com.bnm.diagnosis.navigation.RouteGuard], not by hiding buttons.
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
    var signing by remember { mutableStateOf<Staff?>(null) }   // signature pad target
    var message by remember { mutableStateOf<String?>(null) }

    // Defence in depth: the Settings card hides this and RouteGuard blocks the route.
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
                        "pathologist (or the owner) can approve results, and only the owner sees " +
                        "commission, payouts and prices. People who leave are deactivated, never " +
                        "deleted, so their name stays readable on old reports.",
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
                    onSignature = { signing = s },
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

    signing?.let { target ->
        SignatureDialog(
            person = target,
            onDismiss = { signing = null },
            onSave = { png, quals, reg ->
                scope.launch {
                    // Re-read first: the list row is a snapshot, and writing it
                    // back wholesale would undo anything another seat changed.
                    val fresh = repo.byId(target.id) ?: target
                    repo.save(fresh.copy(signaturePng = png, qualifications = quals, registrationNo = reg))
                        .onSuccess { saved ->
                            session.refresh(saved)
                            message = if (png == null) "${saved.name}'s signature removed"
                            else "${saved.name}'s signature saved"
                        }
                        .onFailure { message = it.message }
                    signing = null
                }
            },
        )
    }

    if (creating || editing != null) {
        StaffEditDialog(
            existing = editing,
            onDismiss = { creating = false; editing = null },
            onSave = { name, role, username, credential ->
                val target = editing
                scope.launch {
                    // Profile FIRST, credential second: dropping someone back to
                    // tap-to-enter is refused while they still hold a username, so
                    // the username has to be gone from the row before the clear.
                    val saved = repo.save(
                        target?.copy(name = name, role = role, username = username)
                            ?: Staff(id = "", name = name, role = role, username = username)
                    ).getOrElse { message = it.message; return@launch }

                    val credResult = when (credential) {
                        is CredentialAction.Keep -> Result.success(Unit)
                        is CredentialAction.Clear -> repo.clearCredential(saved.id)
                        is CredentialAction.SetPin -> repo.setPin(saved.id, credential.pin)
                        is CredentialAction.SetPassword -> repo.setPassword(saved.id, credential.password)
                    }
                    repo.byId(saved.id)?.let { session.refresh(it) }
                    message = credResult.exceptionOrNull()?.message ?: "${saved.name} saved"
                    creating = false; editing = null
                }
            },
        )
    }
}

@Composable
private fun StaffRow(
    staff: Staff,
    isSelf: Boolean,
    onEdit: () -> Unit,
    onSignature: () -> Unit,
    onToggleActive: () -> Unit,
) {
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
                        staff.username?.let { append("@$it · ") }
                        append(signInSummary(staff))
                        if (staff.canSeeMoney) append(" · sees commission & prices")
                        if (!staff.active) append(" · deactivated")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Only for people who can actually sign off a report — a
                // receptionist's signature would never be printed anywhere.
                if (staff.canApprove) {
                    Text(
                        signatureSummary(staff),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (staff.canApprove) TextButton(onClick = onSignature) { Text("Signature") }
            TextButton(onClick = onEdit) { Text("Edit") }
            OutlinedButton(onClick = onToggleActive) {
                Text(if (staff.active) "Deactivate" else "Reactivate")
            }
        }
    }
}

private fun signInSummary(staff: Staff): String = when (staff.credential) {
    StaffCredential.PASSWORD -> "Password set"
    StaffCredential.PIN -> "PIN set"
    StaffCredential.NONE -> "Tap to enter"
    StaffCredential.UNREADABLE -> "Login set on a newer version — reset it here"
}

/** What to do with the person's one secret when the dialog is saved. */
private sealed interface CredentialAction {
    data object Keep : CredentialAction
    data object Clear : CredentialAction
    data class SetPin(val pin: String) : CredentialAction
    data class SetPassword(val password: String) : CredentialAction
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StaffEditDialog(
    existing: Staff?,
    onDismiss: () -> Unit,
    onSave: (name: String, role: String, username: String?, credential: CredentialAction) -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var role by remember { mutableStateOf(existing?.role ?: StaffRole.RECEPTIONIST) }
    var username by remember { mutableStateOf(existing?.username.orEmpty()) }
    // UNREADABLE is not an option the owner can pick — it starts as "Tap to
    // enter", so saving unchanged clears the unusable secret and unblocks them.
    var kind by remember {
        mutableStateOf(
            when (existing?.credential) {
                StaffCredential.PIN -> StaffCredential.PIN
                StaffCredential.PASSWORD -> StaffCredential.PASSWORD
                else -> StaffCredential.NONE
            }
        )
    }
    var pin by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }

    // Blank means "leave the current secret alone", but only when the kind is
    // unchanged — switching PIN → password with an empty box has nothing to set.
    val keepsExisting = existing != null && kind == existing.credential && existing.hasSecret

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add person" else "Edit ${existing.name}") },
        text = {
            Column(
                Modifier.widthIn(max = 420.dp).heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it; err = null },
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
                    value = username,
                    // Folded as typed so what the owner reads is what gets stored.
                    onValueChange = { username = it.trim().lowercase(); err = null },
                    label = { Text("Username (optional)") },
                    supportingText = { Text("Lets this person sign in by typing, instead of picking their tile.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("Sign-in", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CredentialChip(StaffCredential.NONE, "Tap to enter", kind) { kind = it; err = null }
                    CredentialChip(StaffCredential.PIN, "PIN", kind) { kind = it; err = null }
                    CredentialChip(StaffCredential.PASSWORD, "Password", kind) { kind = it; err = null }
                }

                when (kind) {
                    StaffCredential.PIN -> OutlinedTextField(
                        value = pin,
                        onValueChange = { v -> pin = v.filter { it.isDigit() }.take(8); err = null },
                        label = {
                            Text(if (keepsExisting) "New PIN (blank = keep current)" else "PIN")
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // No digit filter and no numeric keyboard here: a password is
                    // alphanumeric, which is the whole point of the second scheme.
                    StaffCredential.PASSWORD -> {
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it; err = null },
                            label = {
                                Text(if (keepsExisting) "New password (blank = keep current)" else "Password")
                            },
                            singleLine = true,
                            visualTransformation =
                                if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TextButton(onClick = { reveal = !reveal }) {
                            Text(if (reveal) "Hide password" else "Show password")
                        }
                    }

                    StaffCredential.NONE, StaffCredential.UNREADABLE -> Text(
                        "Anyone can sign in as this person by tapping their tile. Fine for a " +
                            "single-person lab; give everyone else a PIN or a password.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Text(
                    "A PIN or password keeps someone else from signing in as this person on a " +
                        "shared lab PC, and keeps the commission screens off their seat. It is " +
                        "convenience, not security — the lab's data lives on this machine either way.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                err?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val n = name.trim()
                if (n.isEmpty()) { err = "Name is required"; return@Button }

                val uname = username.trim().lowercase().ifBlank { null }
                StaffRepository.usernameProblem(uname)?.let { err = it; return@Button }

                // A username with nothing behind it is a login anyone could use —
                // the repository refuses that state, so catch it here where the
                // owner can still fix it.
                if (uname != null && kind == StaffCredential.NONE) {
                    err = "A username needs a PIN or a password"; return@Button
                }

                val action = when (kind) {
                    StaffCredential.NONE, StaffCredential.UNREADABLE ->
                        if (existing?.hasSecret == true) CredentialAction.Clear else CredentialAction.Keep

                    StaffCredential.PIN -> when {
                        pin.isBlank() && keepsExisting -> CredentialAction.Keep
                        pin.length < StaffRepository.MIN_PIN -> {
                            err = "PIN must be at least ${StaffRepository.MIN_PIN} digits"; return@Button
                        }
                        else -> CredentialAction.SetPin(pin)
                    }

                    StaffCredential.PASSWORD -> when {
                        password.isEmpty() && keepsExisting -> CredentialAction.Keep
                        password.length < StaffRepository.MIN_PASSWORD -> {
                            err = "Password must be at least ${StaffRepository.MIN_PASSWORD} characters"
                            return@Button
                        }
                        else -> CredentialAction.SetPassword(password)
                    }
                }
                onSave(n, role, uname, action)
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CredentialChip(
    value: StaffCredential,
    label: String,
    selected: StaffCredential,
    onSelect: (StaffCredential) -> Unit,
) {
    FilterChip(selected = selected == value, onClick = { onSelect(value) }, label = { Text(label) })
}
