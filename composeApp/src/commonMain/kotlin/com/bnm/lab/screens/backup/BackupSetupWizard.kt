package com.bnm.lab.screens.backup

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bnm.lab.backup.BackupController
import com.bnm.lab.backup.BackupCopy
import com.bnm.lab.backup.DriveCandidate
import com.bnm.lab.diagnostics.AppLog
import com.bnm.lab.lab.TenantRowCounts
import com.bnm.lab.license.LicenseManager
import com.bnm.lab.ui.theme.AppTheme
import kotlinx.coroutines.launch

/**
 * Everything the set-up wizard remembers between steps. A plain class of
 * snapshot state rather than a pile of `remember`s so a render test can
 * build any step directly.
 */
internal class BackupSetupState {
    enum class Step { DRIVE, KEY, WORKING, CODE }

    var step by mutableStateOf(Step.DRIVE)

    // Step 1 — the pendrive
    var candidates by mutableStateOf<List<DriveCandidate>>(emptyList())
    var loadingCandidates by mutableStateOf(true)
    var folder by mutableStateOf<String?>(null)
    /** Warning for the chosen folder (never a refusal — those clear [folder]). */
    var folderNote by mutableStateOf<String?>(null)
    var folderError by mutableStateOf<String?>(null)

    // Step 2 — the licence key
    var key by mutableStateOf("")
    var keyAttempts by mutableStateOf(0)
    var keyError by mutableStateOf<String?>(null)

    // Step 3/4 — the recovery code and the first backup
    var recoveryCode by mutableStateOf<String?>(null)
    var acknowledged by mutableStateOf(false)
    var firstBackupLine by mutableStateOf<String?>(null)
    var printing by mutableStateOf(false)
    var printMessage by mutableStateOf<String?>(null)

    var error by mutableStateOf<String?>(null)
}

/** What the body can ask its host to do; every default is a no-op so tests render silently. */
internal class BackupSetupActions(
    val onPickCandidate: (DriveCandidate) -> Unit = {},
    val onPickFolder: () -> Unit = {},
    val onPrintCard: () -> Unit = {},
)

/**
 * Set-up wizard (a dialog on the Backup page): choose the pendrive → type the
 * licence key → the vault is created and the first backup taken → the
 * recovery code is shown ONCE, with a print button and an acknowledgement
 * the operator must tick before Done.
 */
@Composable
fun BackupSetupDialog(
    controller: BackupController,
    licenseManager: LicenseManager,
    labName: String,
    rowCounts: suspend () -> TenantRowCounts?,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
) {
    val state = remember { BackupSetupState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        state.candidates = runCatching { controller.listDriveCandidates() }
            .onFailure { AppLog.w("Backup", "listing drive candidates failed", it) }
            .getOrDefault(emptyList())
        state.loadingCandidates = false
    }

    fun choose(path: String, candidate: DriveCandidate?) {
        state.folderError = null
        if (candidate == null) {
            // A hand-picked folder on no known volume: nothing to check against,
            // the engine will refuse it if it is wrong.
            state.folder = path
            state.folderNote = "Couldn't check this folder — make sure it is on the pendrive"
            return
        }
        val verdict = BackupCopy.driveVerdict(candidate, labName)
        if (!verdict.allowed) {
            state.folder = null; state.folderNote = null; state.folderError = verdict.note
            return
        }
        state.folder = path
        state.folderNote = verdict.note
    }

    fun submitKey() {
        val k = state.key.trim()
        if (k.length < 8) { state.keyError = "Enter your BNM Lab licence key"; return }
        val stored = licenseManager.licenseFingerprint
        if (stored != null && licenseManager.fingerprintOf(k) != stored) {
            state.keyAttempts++
            state.key = ""
            if (state.keyAttempts >= BackupCopy.MAX_KEY_ATTEMPTS) {
                AppLog.w("Backup", "set-up closed: licence key wrong ${state.keyAttempts} times")
                onDismiss(); return
            }
            state.keyError = "That is not this lab's licence key — check it and try again"
            return
        }
        val folder = state.folder ?: return
        state.step = BackupSetupState.Step.WORKING
        state.error = null
        scope.launch {
            controller.setUp(folder, k)
                .onSuccess { code ->
                    AppLog.i("Backup", "pendrive set up; first backup written")
                    state.recoveryCode = code
                    val patients = runCatching { rowCounts()?.patients }.getOrNull()
                    state.firstBackupLine = BackupCopy.firstBackupLine(controller.status.value.bytesOnDrive, patients)
                    state.step = BackupSetupState.Step.CODE
                }
                .onFailure {
                    AppLog.w("Backup", "set-up failed", it)
                    state.error = it.message ?: "Set-up failed — try again"
                    state.step = BackupSetupState.Step.DRIVE
                }
        }
    }

    val actions = BackupSetupActions(
        onPickCandidate = { c -> choose(c.path, c) },
        onPickFolder = {
            scope.launch {
                controller.pickFolder("Choose the backup pendrive")?.let { path ->
                    choose(path, BackupCopy.matchCandidate(path, state.candidates))
                }
            }
        },
        onPrintCard = {
            if (state.printing) return@BackupSetupActions
            state.printing = true; state.printMessage = null
            scope.launch {
                controller.printBackupCard()
                    .onSuccess { state.printMessage = "Backup card sent to the printer" }
                    .onFailure { state.printMessage = it.message ?: "Couldn't print — write the code down instead" }
                state.printing = false
            }
        },
    )

    val busy = state.step == BackupSetupState.Step.WORKING
    AlertDialog(
        onDismissRequest = { if (!busy && state.step != BackupSetupState.Step.CODE) onDismiss() },
        title = {
            Text(
                when (state.step) {
                    BackupSetupState.Step.DRIVE -> "Set up the backup pendrive"
                    BackupSetupState.Step.KEY -> "Type your licence key"
                    BackupSetupState.Step.WORKING -> "Setting up…"
                    BackupSetupState.Step.CODE -> "Your recovery code"
                }
            )
        },
        text = { BackupSetupBody(state, actions) },
        confirmButton = {
            when (state.step) {
                BackupSetupState.Step.DRIVE -> TextButton(
                    onClick = { state.keyError = null; state.step = BackupSetupState.Step.KEY },
                    enabled = state.folder != null,
                ) { Text("Next") }
                BackupSetupState.Step.KEY -> TextButton(
                    onClick = { submitKey() },
                    enabled = state.key.trim().length >= 8,
                ) { Text("Set up") }
                BackupSetupState.Step.WORKING -> Unit
                BackupSetupState.Step.CODE -> TextButton(onClick = onDone, enabled = state.acknowledged) { Text("Done") }
            }
        },
        dismissButton = {
            when (state.step) {
                BackupSetupState.Step.DRIVE -> TextButton(onClick = onDismiss) { Text("Cancel") }
                BackupSetupState.Step.KEY -> TextButton(onClick = { state.step = BackupSetupState.Step.DRIVE }) { Text("Back") }
                else -> Unit
            }
        },
    )
}

/** The dialog's content for the current step. Rendered on its own by the render tests. */
@Composable
internal fun BackupSetupBody(state: BackupSetupState, actions: BackupSetupActions) {
    Column(
        Modifier.widthIn(max = 480.dp).heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (state.step) {
            BackupSetupState.Step.DRIVE -> DriveStep(state, actions)
            BackupSetupState.Step.KEY -> KeyStep(state)
            BackupSetupState.Step.WORKING -> WorkingStep()
            BackupSetupState.Step.CODE -> CodeStep(state, actions)
        }
    }
}

@Composable
private fun DriveStep(state: BackupSetupState, actions: BackupSetupActions) {
    Text(
        "Plug in the pendrive that will stay connected to this computer. BNM Lab copies every " +
            "change to it within seconds, locked with your licence key.",
        style = MaterialTheme.typography.bodyMedium,
    )
    state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    when {
        state.loadingCandidates -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text("Looking for pendrives…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.candidates.isEmpty() -> Text(
            "No pendrive found. Plug one in and choose it below.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> state.candidates.forEach { c -> DriveCandidateRow(c, selected = state.folder == c.path, onClick = { actions.onPickCandidate(c) }) }
    }
    TextButton(onClick = actions.onPickFolder) { Text("Choose a folder instead…") }
    state.folder?.let { path ->
        Text("Chosen: $path", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
    state.folderNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.warning) }
    state.folderError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
}

/** One volume as a pick target: name, room, and why it is (not) suitable. */
@Composable
internal fun DriveCandidateRow(c: DriveCandidate, selected: Boolean, onClick: () -> Unit, labName: String? = null) {
    val verdict = BackupCopy.driveVerdict(c, labName)
    val border = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Surface(
        onClick = onClick,
        enabled = verdict.allowed,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (verdict.allowed) 1f else 0.5f),
        border = BorderStroke(1.dp, border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Outlined.Usb, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(c.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(BackupCopy.driveDetail(c), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                verdict.note?.let {
                    Text(
                        it, style = MaterialTheme.typography.bodySmall,
                        color = if (verdict.allowed) AppTheme.colors.warning else MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun KeyStep(state: BackupSetupState) {
    Text(
        "The licence key unlocks the backup if this computer is ever replaced. It is checked " +
            "against the licence on this computer and is never written to the pendrive.",
        style = MaterialTheme.typography.bodyMedium,
    )
    OutlinedTextField(
        value = state.key,
        onValueChange = { state.key = BackupCopy.licenceKeyInput(it); state.keyError = null },
        singleLine = true,
        label = { Text("Licence key") },
        placeholder = { Text("BNMD-XXXX-XXXX-XXXX-XXXX") },
        leadingIcon = { Icon(Icons.Outlined.Key, contentDescription = null, modifier = Modifier.size(18.dp)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
    )
    state.keyError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun WorkingStep() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        Text(
            "Preparing the pendrive and taking the first backup. This can take a minute — leave the pendrive in.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun CodeStep(state: BackupSetupState, actions: BackupSetupActions) {
    Text(
        "If BNM ever re-issues your licence key, this code is the only other way to open the " +
            "backup. BNM does not keep a copy, and it is shown only now.",
        style = MaterialTheme.typography.bodyMedium,
    )
    RecoveryCodePanel(state.recoveryCode ?: "—")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = actions.onPrintCard, enabled = !state.printing) {
            if (state.printing) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(16.dp), strokeWidth = 2.dp)
            else Icon(Icons.Outlined.Print, contentDescription = null, modifier = Modifier.padding(end = 8.dp).size(18.dp))
            Text("Print backup card")
        }
        state.printMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    state.firstBackupLine?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = AppTheme.colors.success)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Checkbox(checked = state.acknowledged, onCheckedChange = { state.acknowledged = it })
        Text(BackupCopy.ACKNOWLEDGE_CODE, style = MaterialTheme.typography.bodySmall)
    }
}

/** The 25-character code, large and monospace, on a tinted panel. */
@Composable
internal fun RecoveryCodePanel(code: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            code,
            fontFamily = FontFamily.Monospace,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
    }
}
