package com.bnm.lab.screens.backup

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.UsbOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bnm.lab.backup.BackupController
import com.bnm.lab.backup.BackupCopy
import com.bnm.lab.backup.BackupExitFlow
import com.bnm.lab.backup.BackupGeneration
import com.bnm.lab.backup.BackupStatus
import com.bnm.lab.ui.theme.AppTheme

/** Wall clock for the labels; the chip recomposes on every status change anyway. */
internal fun nowMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

/** Theme tokens for a copy tone: background to foreground. */
@Composable
internal fun toneColors(tone: BackupCopy.Tone): Pair<Color, Color> {
    val c = AppTheme.colors
    return when (tone) {
        BackupCopy.Tone.NEUTRAL -> c.surfaceMuted to c.textSecondary
        BackupCopy.Tone.GOOD -> c.successSoft to c.accentTextOnSoft
        BackupCopy.Tone.BUSY -> c.infoSoft to c.info
        BackupCopy.Tone.WARN -> c.warningSoft to c.warning
        BackupCopy.Tone.BAD -> c.dangerSoft to c.danger
    }
}

/**
 * The home-screen backup chip — five looks for seven phases (see
 * [BackupCopy.tone]): grey not set up, green backed up, blue working, amber
 * pendrive missing, red failing. Tap → the Backup page. [compact] is the
 * narrow TopAppBar form beside the staff chip.
 */
@Composable
fun BackupStatusChip(
    status: BackupStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val (bg, fg) = toneColors(BackupCopy.tone(status.phase))
    val icon = when (status.phase) {
        BackupStatus.Phase.DRIVE_MISSING -> Icons.Outlined.UsbOff
        BackupStatus.Phase.FAILING, BackupStatus.Phase.DB_PROBLEM -> Icons.Outlined.ErrorOutline
        else -> Icons.Outlined.Usb
    }
    val now = nowMs()
    val label = if (compact) BackupCopy.chipLabelShort(status, now) else BackupCopy.chipLabel(status, now)
    Row(
        modifier
            .background(bg, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (status.phase == BackupStatus.Phase.WORKING) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = fg)
        } else {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(15.dp))
        }
        Text(
            label,
            style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = if (compact) Modifier.widthIn(max = 180.dp) else Modifier,
        )
    }
}

/**
 * "Work since 09:12 is not backed up. Plug in the backup pendrive." — the
 * full-width band over LabHome and the staff sign-in grid, and nowhere else:
 * never over order registration, result entry or approval. Dismissing it
 * hides it for the rest of the session ([BackupController.dismissBannerForSession]).
 */
@Composable
fun BackupNotBackedUpBanner(controller: BackupController?, onOpenBackup: () -> Unit) {
    if (controller == null) return
    val status by controller.status.collectAsState()
    val text = BackupCopy.bannerText(status, nowMs()) ?: return
    val c = AppTheme.colors
    Surface(color = c.warningSoft, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Outlined.UsbOff, contentDescription = null, tint = c.warning, modifier = Modifier.size(20.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = c.warning,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpenBackup) { Text("Open backup", color = c.warning) }
            TextButton(onClick = { controller.dismissBannerForSession() }) { Text("Dismiss", color = c.warning) }
        }
    }
}

/**
 * Launch-time offer: this PC holds no records, but a bound pendrive with a
 * backup on it is plugged in — a reinstall, or a lab moving computers. Asked
 * once per session by the host; "Restore…" opens [RestoreDialog] on [gen].
 */
@Composable
fun BackupRestoreOfferDialog(gen: BackupGeneration, onRestore: () -> Unit, onNotNow: () -> Unit) {
    AlertDialog(
        onDismissRequest = onNotNow,
        title = { Text("Records found on the backup pendrive") },
        text = {
            Column(Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(BackupCopy.restoreOfferText(gen), style = MaterialTheme.typography.bodyMedium)
                Text(
                    "You can also do this later from Settings › Backup pendrive › Restore.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { Button(onClick = onRestore) { Text("Restore…") } },
        dismissButton = { TextButton(onClick = onNotNow) { Text("Not now") } },
    )
}

/**
 * The close-window flow for the desktop host (`Main.kt`): mount this once
 * the operator asks to close. It decides via [BackupExitFlow], runs the flush
 * behind an overlay, or asks — and ends in exactly one of [onExit] /
 * [onCancel]. [controller] null (no engine) exits at once.
 */
@Composable
fun BackupExitFlowUi(
    controller: BackupController?,
    onExit: () -> Unit,
    onCancel: () -> Unit,
) {
    val status = controller?.status?.value
    var decision by remember { mutableStateOf(BackupExitFlow.decide(status)) }
    LaunchedEffect(decision) {
        when (val d = decision) {
            BackupExitFlow.Decision.Exit -> onExit()
            is BackupExitFlow.Decision.Flush -> {
                val flushed = controller != null &&
                    runCatching { controller.flushOnExit(d.maxWaitMs) }.getOrDefault(false)
                // The engine's answer, or the live status: a flush that ran past
                // the deadline may well have finished by the time this reads.
                val clean = flushed || controller?.status?.value?.hasUnsavedChanges == false
                decision = BackupExitFlow.afterFlush(clean)
            }
            BackupExitFlow.Decision.Confirm -> Unit
        }
    }
    when (decision) {
        is BackupExitFlow.Decision.Flush -> BackupFlushOverlay()
        BackupExitFlow.Decision.Confirm -> BackupExitConfirmDialog(
            status = controller?.status?.value,
            onCloseAnyway = onExit,
            onCancel = onCancel,
        )
        BackupExitFlow.Decision.Exit -> Unit
    }
}

/**
 * "Backing up…" — a scrim with a small card while the last generation is
 * written. The scrim is a wall, not a tint: a draw-only Box registers no hit,
 * so clicks would fall through to the app beneath and a result saved "while
 * closing" would re-mark the database dirty and turn a finished flush into
 * "the pendrive could not take them". Every pointer event over it is consumed
 * here, and it takes the keyboard focus so the field that had it stops
 * receiving keys.
 */
@Composable
fun BackupFlushOverlay() {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(
        Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .pointerInput(Unit) {
                awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
            }
            .focusRequester(focus)
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
            Row(
                Modifier.padding(horizontal = 22.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                Column {
                    Text(BackupExitFlow.FLUSHING, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        BackupExitFlow.FLUSHING_DETAIL,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** "Backup pendrive is not connected. Changes since 09:12 exist only on this computer." */
@Composable
fun BackupExitConfirmDialog(status: BackupStatus?, onCloseAnyway: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(BackupExitFlow.confirmTitle(status)) },
        text = { Text(BackupExitFlow.confirmBody(status, nowMs()), style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = onCloseAnyway) { Text(BackupExitFlow.CLOSE_ANYWAY, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(BackupExitFlow.CANCEL) } },
    )
}
