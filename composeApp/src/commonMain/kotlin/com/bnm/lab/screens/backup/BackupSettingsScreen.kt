package com.bnm.lab.screens.backup

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.UsbOff
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bnm.lab.backup.BackupController
import com.bnm.lab.backup.BackupCopy
import com.bnm.lab.backup.BackupStatus
import com.bnm.lab.diagnostics.AppLog
import com.bnm.lab.lab.TenantRowCounts
import com.bnm.lab.license.LicenseManager
import com.bnm.lab.staff.Staff
import com.bnm.lab.staff.StaffRole
import com.bnm.lab.ui.theme.AppTheme
import kotlinx.coroutines.launch

/** Actions that first ask the signed-in owner for their PIN. */
private enum class Sensitive { SHOW_CODE, PRINT_CARD, PURGE }

/**
 * Settings › Data › Backup pendrive — the sub-page (same shape as Print
 * settings). A status block, then the everyday buttons, then the ones an
 * owner reaches for once a year.
 *
 * Gating follows Staff & roles: a non-owner sees the owner rows disabled with
 * the reason as the subtitle. Showing or printing the recovery code, and
 * deleting every backup, also re-ask the owner's PIN ([OwnerPinDialog]) —
 * the code opens the lab's whole history, and a purge is the DPDP erasure.
 *
 * Nothing here reads a database or a preference store directly: the status
 * is the engine's flow, the two repository touches ([verifyPin], [rowCounts])
 * are handed in, so the page renders in a test with a fake engine.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupSettingsScreen(
    controller: BackupController,
    licenseManager: LicenseManager,
    labName: String,
    signedInStaff: Staff?,
    /** [com.bnm.lab.staff.StaffRepository.verifyPin] for the given person. */
    verifyPin: suspend (Staff, String) -> Boolean,
    /** For the "1,204 patients" in the first-backup line; null when unknown. */
    rowCounts: suspend () -> TenantRowCounts?,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val status by controller.status.collectAsState()
    val owner = signedInStaff?.takeIf { it.active && it.role == StaffRole.OWNER }
    val now = nowMs()

    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var showSetup by remember { mutableStateOf(false) }
    var showRestore by remember { mutableStateOf(false) }
    var pinFor by remember { mutableStateOf<Sensitive?>(null) }
    var codeShown by remember { mutableStateOf<String?>(null) }
    var confirmPurge by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }

    fun run(label: String, block: suspend () -> Result<Unit>, done: String) {
        if (busy) return
        busy = true; message = null
        scope.launch {
            block()
                .onSuccess { message = done }
                .onFailure {
                    AppLog.w("Backup", "$label failed", it)
                    message = it.message ?: "$label failed"
                }
            busy = false
        }
    }

    fun afterPin(action: Sensitive) {
        pinFor = null
        when (action) {
            Sensitive.SHOW_CODE -> scope.launch {
                codeShown = controller.recoveryCode() ?: "No recovery code — the pendrive is not set up"
            }
            Sensitive.PRINT_CARD -> run("Printing the backup card", { controller.printBackupCard() }, "Backup card sent to the printer")
            Sensitive.PURGE -> confirmPurge = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(BackupCopy.FEATURE) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item { StatusCard(status, now, Modifier.pageWidth()) }

            if (!status.isSetUp) item {
                Column(Modifier.pageWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { showSetup = true }, enabled = !busy) {
                        Icon(Icons.Outlined.Usb, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("  Set up backup pendrive…")
                    }
                    Text(
                        "Takes a minute. You will need a spare pendrive (1 GB or more) and your licence key.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            message?.let { m ->
                item { Text(m, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.pageWidth()) }
            }

            if (status.isSetUp) item {
                Group("Everyday", Modifier.pageWidth()) {
                    ActionRow(
                        icon = Icons.Outlined.Backup, tint = MaterialTheme.colorScheme.primary,
                        title = "Back up now",
                        subtitle = if (status.retentionPaused) "Also lets older backups be tidied again"
                        else "Writes a copy straight away if anything changed",
                        enabled = !busy,
                        onClick = { run("Backup", { controller.backupNow() }, "Backed up") },
                        trailing = if (busy) { { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) } } else null,
                    )
                    Divider()
                    ActionRow(
                        icon = Icons.Outlined.Usb, tint = MaterialTheme.colorScheme.primary,
                        title = "Choose backup pendrive…",
                        subtitle = "Move to a new or bigger pendrive",
                        enabled = !busy,
                        onClick = { showSetup = true },
                    )
                }
            }

            item {
                Group("Recovery", Modifier.pageWidth()) {
                    if (status.isSetUp) {
                        ActionRow(
                            icon = Icons.Outlined.VpnKey, tint = MaterialTheme.colorScheme.secondary,
                            title = "Show recovery code",
                            subtitle = ownerNote(owner, "Opens the backup if BNM re-issues your licence key"),
                            enabled = owner != null && !busy,
                            onClick = { pinFor = Sensitive.SHOW_CODE },
                        )
                        Divider()
                        ActionRow(
                            icon = Icons.Outlined.Print, tint = MaterialTheme.colorScheme.secondary,
                            title = "Print backup card",
                            subtitle = ownerNote(owner, "An A6 card with the code and the restore steps"),
                            enabled = owner != null && !busy,
                            onClick = { pinFor = Sensitive.PRINT_CARD },
                        )
                        Divider()
                    }
                    ActionRow(
                        icon = Icons.Outlined.Restore, tint = MaterialTheme.colorScheme.secondary,
                        title = if (status.isSetUp) "Restore from this pendrive…" else "Restore from a backup pendrive…",
                        subtitle = ownerNote(owner, "Bring back an earlier copy of the records"),
                        enabled = owner != null && !busy,
                        onClick = { showRestore = true },
                    )
                }
            }

            if (status.isSetUp) item {
                Group("Stop", Modifier.pageWidth()) {
                    ActionRow(
                        icon = Icons.Outlined.DeleteForever, tint = MaterialTheme.colorScheme.error,
                        title = "Delete all backups on this pendrive",
                        subtitle = ownerNote(owner, "Erases every copy on the pendrive. Cannot be undone."),
                        titleColor = MaterialTheme.colorScheme.error,
                        enabled = owner != null && !busy,
                        onClick = { pinFor = Sensitive.PURGE },
                    )
                    Divider()
                    ActionRow(
                        icon = Icons.Outlined.StopCircle, tint = MaterialTheme.colorScheme.error,
                        title = "Stop backing up",
                        subtitle = "Copies on the pendrive are kept; nothing new is written",
                        enabled = !busy,
                        onClick = { confirmStop = true },
                    )
                }
            }

            item {
                Text(
                    "BNM Lab copies every change to the pendrive within seconds, as a locked copy that only " +
                        "your licence key or the recovery code can open. Keep the pendrive plugged in. To move " +
                        "the lab to another computer, install BNM Lab there, plug in the pendrive and choose Restore.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.pageWidth(),
                )
            }
        }
    }

    // ── Dialogs ──

    if (showSetup) BackupSetupDialog(
        controller = controller,
        licenseManager = licenseManager,
        labName = labName,
        rowCounts = rowCounts,
        onDone = { showSetup = false; message = "Backup pendrive is set up" },
        onDismiss = { showSetup = false },
    )

    if (showRestore) RestoreDialog(
        controller = controller,
        activated = true,
        onDismiss = { showRestore = false },
    )

    // The owner signing out mid-question drops the question with them.
    LaunchedEffect(owner?.id) { if (owner == null) pinFor = null }
    val pinAction = pinFor
    if (pinAction != null && owner != null) {
        val action = pinAction
        val who = owner
        OwnerPinDialog(
            staff = who,
            title = when (action) {
                Sensitive.SHOW_CODE -> "Show the recovery code?"
                Sensitive.PRINT_CARD -> "Print the backup card?"
                Sensitive.PURGE -> "Delete all backups?"
            },
            verify = { pin -> verifyPin(who, pin) },
            onVerified = { afterPin(action) },
            onDismiss = { pinFor = null },
        )
    }

    codeShown?.let { code ->
        AlertDialog(
            onDismissRequest = { codeShown = null },
            title = { Text("Recovery code") },
            text = {
                Column(Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    RecoveryCodePanel(code)
                    Text(
                        "Write it down and keep it away from this computer. Anyone holding it and the pendrive can read the lab's records.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { codeShown = null }) { Text("Close") } },
        )
    }

    if (confirmPurge) {
        AlertDialog(
            onDismissRequest = { if (!busy) confirmPurge = false },
            title = { Text("Delete all backups on this pendrive?") },
            text = {
                Text(
                    "Every copy of the records on the pendrive is erased. The records on this computer are not touched, " +
                        "but until the next backup runs they exist only here. This cannot be undone.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        confirmPurge = false
                        AppLog.w("Backup", "owner requested erasure of every generation on the pendrive")
                        run("Deleting backups", { controller.purgeDrive() }, "All backups on the pendrive were deleted")
                    },
                ) { Text("Delete all", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { if (!busy) confirmPurge = false }) { Text("Cancel") } },
        )
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { if (!busy) confirmStop = false },
            title = { Text("Stop backing up?") },
            text = {
                Text(
                    "New changes will no longer be copied to the pendrive. The copies already on it are kept, and " +
                        "choosing the pendrive again later carries on from them.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        confirmStop = false
                        run("Stopping", { controller.stop() }, "Backups stopped")
                    },
                ) { Text("Stop", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { if (!busy) confirmStop = false }) { Text("Cancel") } },
        )
    }
}

/** The subtitle a gated row shows: the reason for a non-owner, the action for the owner. */
private fun ownerNote(owner: Staff?, action: String): String =
    if (owner == null) "Only the lab owner can do this" else action

/**
 * The state block: headline in the phase colour, then the facts a lab asks
 * about — which pendrive, when, whether it was proven restorable, how much
 * is kept — and the two notices (guard tripped, last error).
 */
@Composable
internal fun StatusCard(status: BackupStatus, nowMs: Long, modifier: Modifier = Modifier) {
    val (bg, fg) = toneColors(BackupCopy.tone(status.phase))
    val icon = when (status.phase) {
        BackupStatus.Phase.DRIVE_MISSING -> Icons.Outlined.UsbOff
        BackupStatus.Phase.FAILING, BackupStatus.Phase.DB_PROBLEM -> Icons.Outlined.ErrorOutline
        else -> Icons.Outlined.Usb
    }
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(48.dp).background(bg, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                    if (status.phase == BackupStatus.Phase.WORKING) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = fg)
                    else Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(26.dp))
                }
                Text(
                    BackupCopy.chipLabel(status, nowMs),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = fg,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            if (!status.isSetUp) {
                Text(
                    "Nothing is backed up yet. If this computer fails, the lab's records are lost with it.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Fact("Pendrive", status.driveName?.takeIf { it.isNotBlank() }
                    ?: if (status.phase == BackupStatus.Phase.DRIVE_MISSING) "not connected" else "—")
                Fact("Last backup", status.lastOkAt?.let { BackupCopy.dateTimeLabel(it) } ?: "never")
                Fact("Verified restorable", status.lastVerifiedAt?.let {
                    (if (BackupCopy.sameDay(it, nowMs)) "today " else "") + BackupCopy.timeLabel(it, nowMs)
                } ?: "not yet")
                Fact("Copies kept", "${status.generations} · ${BackupCopy.sizeLabel(status.bytesOnDrive)} on the pendrive")
                if (status.retentionPaused) {
                    Text(BackupCopy.RETENTION_PAUSED, style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.warning)
                }
                status.lastError?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (status.restoredFromBackup) {
                    Text(
                        "This computer was restored from a backup. Register it with your licence key from Settings › License & devices.",
                        style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.warning,
                    )
                }
            }
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(min = 140.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

// ── The same list vocabulary as Settings (its building blocks are private there) ──

@Composable
private fun Group(caption: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            caption, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp),
        )
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(content = content)
        }
    }
}

@Composable
private fun Divider() {
    HorizontalDivider(modifier = Modifier.padding(start = 60.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String? = null,
    titleColor: Color? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val alpha = if (enabled) 1f else 0.45f
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .heightIn(min = 56.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp).background(tint.copy(alpha = 0.14f * alpha), RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tint.copy(alpha = alpha), modifier = Modifier.size(19.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title, style = MaterialTheme.typography.bodyLarge,
                color = (titleColor ?: MaterialTheme.colorScheme.onSurface).copy(alpha = alpha),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let {
                Text(
                    it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        when {
            trailing != null -> trailing()
            onClick != null -> Icon(
                Icons.Outlined.ChevronRight, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f * alpha), modifier = Modifier.size(20.dp),
            )
        }
    }
}

private fun Modifier.pageWidth(): Modifier = this.fillMaxWidth().widthIn(max = 720.dp)
