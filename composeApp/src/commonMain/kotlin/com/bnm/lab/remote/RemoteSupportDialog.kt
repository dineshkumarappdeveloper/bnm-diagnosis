package com.bnm.lab.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bnm.lab.screens.lab.shortTimeLabel
import com.bnm.lab.staff.LocalStaffRepository
import com.bnm.lab.staff.LocalStaffSession
import com.bnm.lab.staff.Staff
import com.bnm.lab.staff.StaffCredential
import com.bnm.lab.staff.StaffRole
import kotlinx.coroutines.launch

/**
 * "Remote support": the owner starts a time-boxed session in which BNM's
 * engineer can reach this copy of BNM Lab, reads the code to the engineer, and
 * watches what happens — live status and Support history — until End.
 *
 * Owner-only, the same way Staff & roles is: an owner who is signed in goes
 * straight to the consent screen; anyone else (a technician at the bench, or
 * nobody — the Help menu opens this from activation and sign-in too) is asked
 * for the owner's PIN first, checked with the same [com.bnm.lab.staff.StaffRepository.verifyPin]
 * the sign-in grid uses.
 *
 * Opened from Help ▸ Remote support… and Settings ▸ App ▸ Remote support
 * ([RemoteSupportUi]); App.kt renders it above whatever screen is showing.
 */
@Composable
fun RemoteSupportDialog(controller: RemoteSupportController, onDismiss: () -> Unit) {
    val staffSession = LocalStaffSession.current
    val staffRepo = LocalStaffRepository.current
    val signedIn by staffSession.current.collectAsState()
    val activeStaff by remember(staffRepo) { staffRepo.listActiveFlow() }.collectAsState(emptyList())
    val status by controller.status.collectAsState()
    // Newest first; re-read whenever an action lands or the session changes phase.
    var history by remember { mutableStateOf<List<SupportAuditRow>>(emptyList()) }
    LaunchedEffect(status.actions, status.phase) {
        history = runCatching { controller.history(HISTORY_ROWS) }.getOrDefault(emptyList())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remote support") },
        text = {
            RemoteSupportDialogBody(
                status = status,
                signedIn = signedIn,
                owners = activeStaff.filter { it.role == StaffRole.OWNER },
                verifyOwnerPin = { id, pin -> staffRepo.verifyPin(id, pin) },
                history = history,
                onStart = { consent, durationS, starter -> controller.start(consent, durationS, starter) },
                onEnd = { controller.end("owner pressed End") },
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/**
 * The dialog's content with every dependency passed in, so a render test can
 * draw each state (owner gate, consent, running session, history) without a
 * database or an engine behind it.
 */
@Composable
fun RemoteSupportDialogBody(
    status: RemoteSupportStatus,
    signedIn: Staff?,
    owners: List<Staff>,
    verifyOwnerPin: suspend (staffId: String, pin: String) -> Boolean,
    history: List<SupportAuditRow>,
    onStart: suspend (SupportConsent, Long, SupportStarter) -> Result<SupportSession>,
    onEnd: suspend () -> Unit,
) {
    // The owner who approved this session — the signed-in owner, or whoever
    // typed their PIN. Cleared when the dialog closes (it is remembered per
    // composition), so the next opening asks again.
    var approvedOwner by remember { mutableStateOf<Staff?>(signedIn?.takeIf { it.role == StaffRole.OWNER }) }
    LaunchedEffect(signedIn) { if (signedIn?.role == StaffRole.OWNER) approvedOwner = signedIn }

    Column(
        Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val session = status.session
        val owner = approvedOwner
        when {
            status.isActive && session != null -> RunningPanel(status, session, onEnd)
            owner == null -> OwnerGate(owners, signedIn, verifyOwnerPin, onApproved = { approvedOwner = it })
            else -> ConsentForm(
                lastError = status.lastError?.takeIf { status.phase == RemoteSupportStatus.Phase.ENDED_ERROR },
                onStart = { consent, durationS ->
                    // Whoever is at the seat is the starter; with nobody signed in
                    // (activation, sign-in grid) it is the owner who typed the PIN.
                    val starter = signedIn?.let { SupportStarter(it.id, it.name) } ?: SupportStarter(owner.id, owner.name)
                    onStart(consent, durationS, starter)
                },
            )
        }
        HorizontalDivider()
        SupportHistory(history)
    }
}

// ── owner gate ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OwnerGate(
    owners: List<Staff>,
    signedIn: Staff?,
    verifyOwnerPin: suspend (String, String) -> Boolean,
    onApproved: (Staff) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var chosen by remember(owners) { mutableStateOf(owners.firstOrNull()) }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }

    Text("Only the lab owner can start a support session.", style = MaterialTheme.typography.titleSmall)
    if (owners.isEmpty()) {
        Text(
            "No owner account is set up on this computer. Add one in Settings ▸ Staff & roles first.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        return
    }
    Text(
        if (signedIn != null) "You are signed in as ${signedIn.name} (${signedIn.roleLabel}). Ask the owner to approve."
        else "Nobody is signed in. Ask the owner to approve.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (owners.size > 1) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            owners.forEach { o ->
                FilterChip(selected = chosen?.id == o.id, onClick = { chosen = o; pin = ""; error = null },
                    label = { Text(o.name, maxLines = 1, overflow = TextOverflow.Ellipsis) })
            }
        }
    }
    val target = chosen ?: return
    fun approve() {
        if (checking) return
        checking = true; error = null
        scope.launch {
            val ok = runCatching { verifyOwnerPin(target.id, pin) }.getOrDefault(false)
            checking = false
            if (ok) onApproved(target)
            else {
                pin = ""
                error = if (target.credential == StaffCredential.PIN) "Wrong PIN — try again" else "Wrong password — try again"
            }
        }
    }
    if (target.hasSecret) {
        val isPin = target.credential == StaffCredential.PIN
        OutlinedTextField(
            value = pin,
            onValueChange = { pin = it; error = null },
            label = { Text(if (isPin) "${target.name}'s PIN" else "${target.name}'s password") },
            singleLine = true,
            isError = error != null,
            enabled = !checking,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = if (isPin) KeyboardType.NumberPassword else KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        Button(onClick = { approve() }, enabled = pin.isNotEmpty() && !checking) {
            Text(if (checking) "Checking…" else "Approve")
        }
    } else {
        // A PIN-less owner is tap-to-enter at the sign-in grid too.
        Text(
            "${target.name} has no PIN set, so no PIN is needed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = { approve() }, enabled = !checking) { Text("Continue as ${target.name}") }
    }
}

// ── consent ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConsentForm(
    lastError: String?,
    onStart: suspend (SupportConsent, Long) -> Result<SupportSession>,
) {
    val scope = rememberCoroutineScope()
    var durationS by remember { mutableStateOf(RemoteSupportCopy.DEFAULT_DURATION_S) }
    var analyzerData by remember { mutableStateOf(false) }
    var records by remember { mutableStateOf(false) }
    var screen by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(lastError) }

    Text(RemoteSupportCopy.consentText(durationS), style = MaterialTheme.typography.bodyMedium)
    Text("How long", style = MaterialTheme.typography.labelMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RemoteSupportCopy.DURATIONS.forEach { (s, label) ->
            FilterChip(selected = durationS == s, onClick = { durationS = s }, label = { Text(label) }, enabled = !busy)
        }
    }
    ConsentRow(RemoteSupportCopy.CONSENT_ANALYZER_DATA, RemoteSupportCopy.CONSENT_ANALYZER_DATA_DETAIL, analyzerData, !busy) { analyzerData = it }
    ConsentRow(RemoteSupportCopy.CONSENT_RECORDS, RemoteSupportCopy.CONSENT_RECORDS_DETAIL, records, !busy) { records = it }
    ConsentRow(RemoteSupportCopy.CONSENT_SCREEN, RemoteSupportCopy.CONSENT_SCREEN_DETAIL, screen, !busy) { screen = it }
    error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    Button(
        enabled = !busy,
        onClick = onClick@{
            if (busy) return@onClick
            busy = true; error = null
            scope.launch {
                onStart(SupportConsent(analyzerData = analyzerData, records = records, screen = screen), durationS)
                    .onFailure { error = it.message?.takeIf { m -> m.isNotBlank() } ?: "The support session could not start." }
                busy = false
            }
        },
    ) { Text(if (busy) "Connecting…" else "Start session") }
}

@Composable
private fun ConsentRow(label: String, detail: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text("($detail)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ── running session ──

@Composable
private fun RunningPanel(status: RemoteSupportStatus, session: SupportSession, onEnd: suspend () -> Unit) {
    val scope = rememberCoroutineScope()
    var ending by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        SelectionContainer {
            Text(
                session.displayCode,
                style = MaterialTheme.typography.displaySmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 4.sp,
                textAlign = TextAlign.Center,
            )
        }
        Text(RemoteSupportCopy.READ_CODE, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(10.dp).background(
                if (status.peerConnected) Color(0xFF1B8A3C) else Color(0xFF8A6D00),
                CircleShape,
            ),
        )
        Spacer(Modifier.width(8.dp))
        Text(RemoteSupportCopy.phaseLabel(status), style = MaterialTheme.typography.bodyMedium)
    }
    Text(
        "Ends at ${RemoteSupportCopy.endsAtLabel(session)} · ${RemoteSupportCopy.remainingLabel(status.remainingS)}",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        "${status.actions} ${if (status.actions == 1) "action" else "actions"} so far · started by ${session.startedBy.staffName}",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        RemoteSupportCopy.consentSummary(session.consent),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedButton(
        enabled = !ending,
        onClick = { ending = true; scope.launch { runCatching { onEnd() }; ending = false } },
    ) { Text(if (ending) "Ending…" else "End session") }
}

// ── history ──

@Composable
private fun SupportHistory(rows: List<SupportAuditRow>) {
    Text("Support history", style = MaterialTheme.typography.titleSmall)
    if (rows.isEmpty()) {
        Text(
            "Nothing yet. Every action the engineer takes is listed here — time, what it was, and whether it ran.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    rows.take(HISTORY_ROWS).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
            Text(
                shortTimeLabel(kotlin.time.Instant.fromEpochMilliseconds(row.atMs).toString()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(88.dp),
            )
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(row.tool, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        RemoteSupportCopy.outcomeLabel(row.outcome),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (row.outcome == SupportAuditRow.Outcome.OK) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                    )
                }
                if (row.summary.isNotBlank()) {
                    Text(row.summary, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** The dialog lists this many recent actions (the audit table keeps them all). */
private const val HISTORY_ROWS = 50
