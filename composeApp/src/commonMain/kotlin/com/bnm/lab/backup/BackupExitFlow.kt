package com.bnm.lab.backup

/**
 * What closing the window does about unsaved backup work — decided here, in
 * plain Kotlin, so the desktop `Main.kt` only has to ask and act (and so the
 * rule is testable without a window).
 *
 * - Nothing set up, or nothing unsaved → just exit.
 * - Unsaved work and the pendrive is present → [Decision.Flush]: write a
 *   generation behind a small "Backing up…" overlay, then exit. A flush that
 *   fails or runs out of time falls through to [Decision.Confirm].
 * - Unsaved work and no pendrive to take it (absent, or the database itself
 *   is the problem) → [Decision.Confirm]: say what exists only on this PC and
 *   let the operator close anyway or cancel.
 *
 * The JVM shutdown hook never snapshots (Windows gives seconds and shows
 * "preventing shutdown" if we block); this close-window path is the one
 * place a last generation is written.
 */
object BackupExitFlow {
    /** How long the close-window flush may take before the operator is asked instead. */
    const val FLUSH_MAX_WAIT_MS = 30_000L

    sealed class Decision {
        data object Exit : Decision()
        data class Flush(val maxWaitMs: Long = FLUSH_MAX_WAIT_MS) : Decision()
        data object Confirm : Decision()
    }

    /** [status] null = no engine on this platform. */
    fun decide(status: BackupStatus?): Decision = when {
        status == null -> Decision.Exit
        !status.isSetUp -> Decision.Exit
        !status.hasUnsavedChanges -> Decision.Exit
        status.phase == BackupStatus.Phase.DRIVE_MISSING -> Decision.Confirm
        status.phase == BackupStatus.Phase.DB_PROBLEM -> Decision.Confirm
        else -> Decision.Flush()
    }

    /** After [BackupController.flushOnExit]: clean → exit; still unsaved → ask. */
    fun afterFlush(flushed: Boolean): Decision = if (flushed) Decision.Exit else Decision.Confirm

    fun confirmTitle(status: BackupStatus?): String = when (status?.phase) {
        BackupStatus.Phase.DRIVE_MISSING -> "Backup pendrive is not connected"
        else -> "Changes are not backed up"
    }

    fun confirmBody(status: BackupStatus?, nowMs: Long): String {
        val since = status?.dirtySince?.let { BackupCopy.timeLabel(it, nowMs) }
        val head = if (since != null) "Changes since $since exist only on this computer."
        else "Recent changes exist only on this computer."
        return when (status?.phase) {
            BackupStatus.Phase.DRIVE_MISSING -> "$head Plug in the backup pendrive and try again, or close anyway."
            BackupStatus.Phase.DB_PROBLEM -> "$head The database could not be backed up — contact BNM."
            else -> "$head The backup pendrive could not take them."
        }
    }

    const val CLOSE_ANYWAY = "Close anyway"
    const val CANCEL = "Cancel"
    const val FLUSHING = "Backing up…"
    const val FLUSHING_DETAIL = "Writing your latest changes to the backup pendrive before closing."
}
