package com.bnm.lab.screens.backup

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
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.bnm.lab.backup.BackupController
import com.bnm.lab.backup.BackupCopy
import com.bnm.lab.backup.BackupGeneration
import com.bnm.lab.backup.DriveCandidate
import com.bnm.lab.backup.RestorePreview
import com.bnm.lab.backup.RestoreStaged
import com.bnm.lab.backup.Unlock
import com.bnm.lab.diagnostics.AppLog
import com.bnm.lab.ui.theme.AppTheme
import kotlinx.coroutines.launch

/**
 * Everything the restore flow remembers between steps — snapshot state in a
 * plain class so a render test can build any step directly. [initial] skips
 * the folder and generation steps (the launch-time offer already chose one).
 */
internal class RestoreFlowState(initial: BackupGeneration? = null) {
    enum class Step { FOLDER, GENERATIONS, UNLOCK, PREVIEW, DONE }

    var step by mutableStateOf(if (initial == null) Step.FOLDER else Step.UNLOCK)

    var candidates by mutableStateOf<List<DriveCandidate>>(emptyList())
    var loadingCandidates by mutableStateOf(initial == null)
    var folder by mutableStateOf<String?>(null)
    /** [folder] sits on this PC's own disk: the generations are restorable, but this is no backup pendrive. */
    var folderOnOwnDisk by mutableStateOf(false)

    var generations by mutableStateOf<List<BackupGeneration>>(emptyList())
    var selected by mutableStateOf(initial)

    var useRecoveryCode by mutableStateOf(false)
    var secret by mutableStateOf("")

    /** The unlock that opened [preview]; reused for the restore itself. */
    var unlock by mutableStateOf<Unlock?>(null)
    var preview by mutableStateOf<RestorePreview?>(null)
    var staged by mutableStateOf<RestoreStaged?>(null)

    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
}

internal class RestoreActions(
    val onPickCandidate: (DriveCandidate) -> Unit = {},
    val onPickFolder: () -> Unit = {},
    val onSelectGeneration: (BackupGeneration) -> Unit = {},
    val onToggleUnlockKind: () -> Unit = {},
    val onSubmitUnlock: () -> Unit = {},
)

/**
 * Restore from a backup pendrive — shared by the Activation screen (a new or
 * reinstalled PC) and the Backup page (an activated PC rolling back).
 *
 * Folder → generations, newest first → unlock (an activated PC restoring its
 * own pendrive needs no secret; otherwise the licence key, or the recovery
 * code) → preview with refusals → staged; then "Close and reopen BNM Lab".
 * The live database is never touched here — the next launch swaps the file.
 */
@Composable
fun RestoreDialog(
    controller: BackupController,
    /** A genuine licence is on this PC: try [Unlock.ThisPc] before asking for a secret. */
    activated: Boolean,
    initialGeneration: BackupGeneration? = null,
    onDismiss: () -> Unit,
) {
    val state = remember { RestoreFlowState(initialGeneration) }
    val scope = rememberCoroutineScope()

    suspend fun tryPreview(gen: BackupGeneration, unlock: Unlock): Boolean {
        state.busy = true; state.error = null
        val ok = controller.preview(gen, unlock)
            .onSuccess { p ->
                state.preview = p; state.unlock = unlock; state.step = RestoreFlowState.Step.PREVIEW
            }
            .isSuccess
        state.busy = false
        return ok
    }

    /** Silent same-PC unlock first; fall back to asking. */
    suspend fun openGeneration(gen: BackupGeneration) {
        state.selected = gen
        state.secret = ""
        if (activated && tryPreview(gen, Unlock.ThisPc)) return
        state.error = null
        state.step = RestoreFlowState.Step.UNLOCK
    }

    LaunchedEffect(Unit) {
        if (initialGeneration != null) {
            openGeneration(initialGeneration)
        } else {
            state.candidates = runCatching { controller.listDriveCandidates() }
                .onFailure { AppLog.w("Backup", "listing drive candidates failed", it) }
                .getOrDefault(emptyList())
            state.loadingCandidates = false
        }
    }

    fun open(folder: String) {
        state.busy = true; state.error = null
        scope.launch {
            controller.findBackupsAt(folder)
                .onSuccess { gens ->
                    state.folder = folder
                    // Said before unlocking, not after: the engine will keep the
                    // key but refuse to adopt a folder that dies with this PC.
                    state.folderOnOwnDisk = BackupCopy.matchCandidate(folder, state.candidates)?.sameVolumeAsData == true
                    if (gens.isEmpty()) state.error = "No backups found there. Choose the pendrive itself, or its BNM Lab Backup folder."
                    else { state.generations = gens; state.step = RestoreFlowState.Step.GENERATIONS }
                }
                .onFailure { state.error = it.message ?: "Couldn't read that folder" }
            state.busy = false
        }
    }

    val actions = RestoreActions(
        onPickCandidate = { c -> open(c.path) },
        onPickFolder = {
            scope.launch { controller.pickFolder("Where is the backup pendrive?")?.let { open(it) } }
        },
        onSelectGeneration = { g -> if (!g.damaged) scope.launch { openGeneration(g) } },
        onToggleUnlockKind = { state.useRecoveryCode = !state.useRecoveryCode; state.secret = ""; state.error = null },
        onSubmitUnlock = {
            val gen = state.selected
            if (gen != null && state.secret.isNotBlank() && !state.busy) scope.launch {
                val unlock = if (state.useRecoveryCode) Unlock.RecoveryCode(state.secret) else Unlock.LicenceKey(state.secret)
                if (!tryPreview(gen, unlock)) {
                    state.secret = ""
                    state.error = if (state.useRecoveryCode) "That code doesn't open this backup — check it and try again"
                    else "That key doesn't open this backup — check it, or use the recovery code"
                }
            }
        },
    )

    fun restore() {
        val gen = state.selected ?: return
        val unlock = state.unlock ?: return
        state.busy = true; state.error = null
        scope.launch {
            controller.stageRestore(gen, unlock)
                .onSuccess {
                    AppLog.i("Backup", "restore staged from generation seq=${gen.seq}")
                    state.staged = it; state.step = RestoreFlowState.Step.DONE
                }
                .onFailure {
                    AppLog.w("Backup", "staging restore failed", it)
                    state.error = it.message ?: "Restore failed — nothing was changed"
                }
            state.busy = false
        }
    }

    val refusal = state.preview?.let { BackupCopy.restoreRefusal(it) }
    AlertDialog(
        onDismissRequest = { if (!state.busy && state.step != RestoreFlowState.Step.DONE) onDismiss() },
        title = {
            Text(
                when (state.step) {
                    RestoreFlowState.Step.FOLDER -> "Restore from a backup pendrive"
                    RestoreFlowState.Step.GENERATIONS -> "Which backup?"
                    RestoreFlowState.Step.UNLOCK -> "Unlock the backup"
                    RestoreFlowState.Step.PREVIEW -> "Restore these records?"
                    RestoreFlowState.Step.DONE -> "Restored"
                }
            )
        },
        text = { RestoreBody(state, actions) },
        confirmButton = {
            when (state.step) {
                RestoreFlowState.Step.UNLOCK -> TextButton(
                    onClick = actions.onSubmitUnlock,
                    enabled = !state.busy && state.secret.isNotBlank(),
                ) { Text(if (state.busy) "Checking…" else "Unlock") }
                RestoreFlowState.Step.PREVIEW -> TextButton(
                    onClick = { restore() },
                    enabled = !state.busy && refusal == null,
                ) { Text(if (state.busy) "Restoring…" else "Restore") }
                RestoreFlowState.Step.DONE -> Button(onClick = { controller.closeApp() }) { Text("Close BNM Lab") }
                else -> Unit
            }
        },
        dismissButton = {
            when (state.step) {
                RestoreFlowState.Step.FOLDER -> TextButton(onClick = onDismiss, enabled = !state.busy) { Text("Cancel") }
                RestoreFlowState.Step.GENERATIONS -> TextButton(
                    onClick = { state.step = RestoreFlowState.Step.FOLDER; state.error = null },
                    enabled = !state.busy,
                ) { Text("Back") }
                RestoreFlowState.Step.UNLOCK, RestoreFlowState.Step.PREVIEW -> TextButton(
                    onClick = {
                        state.error = null; state.preview = null
                        if (initialGeneration != null) onDismiss()
                        else state.step = RestoreFlowState.Step.GENERATIONS
                    },
                    enabled = !state.busy,
                ) { Text(if (initialGeneration != null) "Cancel" else "Back") }
                RestoreFlowState.Step.DONE -> Unit
            }
        },
    )
}

/** The dialog's content for the current step. Rendered on its own by the render tests. */
@Composable
internal fun RestoreBody(state: RestoreFlowState, actions: RestoreActions) {
    Column(
        Modifier.widthIn(max = 480.dp).heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (state.step) {
            RestoreFlowState.Step.FOLDER -> FolderStep(state, actions)
            RestoreFlowState.Step.GENERATIONS -> GenerationsStep(state, actions)
            RestoreFlowState.Step.UNLOCK -> UnlockStep(state, actions)
            RestoreFlowState.Step.PREVIEW -> PreviewStep(state)
            RestoreFlowState.Step.DONE -> DoneStep(state)
        }
        state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun FolderStep(state: RestoreFlowState, actions: RestoreActions) {
    Text(
        "Plug in the backup pendrive and pick it. Nothing on this computer changes until you confirm.",
        style = MaterialTheme.typography.bodyMedium,
    )
    when {
        state.loadingCandidates || state.busy -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(if (state.busy) "Reading the pendrive…" else "Looking for pendrives…",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.candidates.isEmpty() -> Text(
            "No pendrive found. Plug one in, or choose its folder below.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> state.candidates.forEach { c ->
            RestoreCandidateRow(c, onClick = { actions.onPickCandidate(c) })
        }
    }
    TextButton(onClick = actions.onPickFolder, enabled = !state.busy) { Text("Choose a folder instead…") }
}

/** A volume as a restore source — the lab whose backups it holds is the headline. */
@Composable
private fun RestoreCandidateRow(c: DriveCandidate, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Outlined.Restore, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(c.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    c.existingBackupOf?.let { "Backups of $it" } ?: "No BNM Lab backups found on it",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (c.existingBackupOf != null) AppTheme.colors.success else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun GenerationsStep(state: RestoreFlowState, actions: RestoreActions) {
    Text(
        "Newest first. Pick the copy to bring back — usually the top one.",
        style = MaterialTheme.typography.bodyMedium,
    )
    if (state.folderOnOwnDisk) {
        Text(
            BackupCopy.RESTORE_FROM_OWN_DISK_WARNING,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = AppTheme.colors.warning,
        )
    }
    state.generations.forEach { g ->
        val enabled = !g.damaged && !state.busy
        Surface(
            onClick = { actions.onSelectGeneration(g) },
            enabled = enabled,
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (g.damaged) 0.5f else 1f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    BackupCopy.generationLine(g),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (g.damaged) 0.55f else 1f),
                )
                Text(
                    if (g.damaged) "This copy cannot be opened — choose another"
                    else listOfNotNull(g.labName, "copy #${g.seq}").joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (g.damaged) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (state.busy) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Text("Opening…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun UnlockStep(state: RestoreFlowState, actions: RestoreActions) {
    state.selected?.let {
        Text(
            "${it.labName ?: "Backup"} · ${BackupCopy.dateTimeLabel(it.createdAt)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (state.useRecoveryCode) {
        Text("Type the recovery code from the backup card.", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = state.secret,
            onValueChange = { state.secret = BackupCopy.recoveryCodeInput(it); state.error = null },
            singleLine = true,
            label = { Text("Recovery code") },
            placeholder = { Text("XXXXX-XXXXX-XXXXX-XXXXX-XXXXX") },
            leadingIcon = { Icon(Icons.Outlined.VpnKey, contentDescription = null, modifier = Modifier.size(18.dp)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { actions.onSubmitUnlock() }),
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        Text("Type the lab's licence key — the one BNM issued for this lab.", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = state.secret,
            onValueChange = { state.secret = BackupCopy.licenceKeyInput(it); state.error = null },
            singleLine = true,
            label = { Text("Licence key") },
            placeholder = { Text("BNMD-XXXX-XXXX-XXXX-XXXX") },
            leadingIcon = { Icon(Icons.Outlined.Key, contentDescription = null, modifier = Modifier.size(18.dp)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { actions.onSubmitUnlock() }),
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    TextButton(onClick = actions.onToggleUnlockKind, enabled = !state.busy) {
        Text(if (state.useRecoveryCode) "Use the licence key instead" else "Use recovery code instead")
    }
}

@Composable
private fun PreviewStep(state: RestoreFlowState) {
    val p = state.preview ?: return
    Text(BackupCopy.restorePreviewText(p), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    Text(BackupCopy.setAsideText(p), style = MaterialTheme.typography.bodyMedium)
    Text(BackupCopy.NEW_SERIES_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(
        "Made with BNM Lab ${p.appVersion} · copy #${p.seq}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontFamily = FontFamily.Monospace,
    )
    BackupCopy.restoreRefusal(p)?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun DoneStep(state: RestoreFlowState) {
    Text("Restored. Close and reopen BNM Lab.", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    Text(
        "The records come back when the app next opens. Keep the pendrive plugged in — it is this computer's backup pendrive now.",
        style = MaterialTheme.typography.bodyMedium,
    )
    state.staged?.notes?.forEach {
        Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
