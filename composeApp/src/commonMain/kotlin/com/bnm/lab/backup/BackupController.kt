package com.bnm.lab.backup

import kotlinx.coroutines.flow.StateFlow

/**
 * The "Backup pendrive" — a standalone (offline) lab's only copy of its records.
 *
 * The desktop app snapshots the SQLite system of record to an always-plugged
 * USB pendrive within seconds of every change, encrypted, as independent full
 * generations, and can rebuild the lab on a new PC from the newest one. This
 * interface is the WHOLE surface the UI sees; the engine (desktopMain
 * `BackupService`) is the only implementation, Android and iOS answer null.
 *
 * Nothing here ever carries patient data: statuses, counts, sizes, times and
 * short non-PHI reasons only. Text shown to staff is composed by the screens.
 */
interface BackupController {
    /** Live state for the home chip, the Settings row and the Backup page. */
    val status: StateFlow<BackupStatus>

    // ── set-up / everyday ──

    /** Volumes worth offering as a backup pendrive (removable first). */
    suspend fun listDriveCandidates(): List<DriveCandidate>

    /** OS folder chooser; null when the operator cancels. */
    suspend fun pickFolder(title: String): String?

    /**
     * Bind [folder] as the backup pendrive, create the vault and take the first
     * backup. [licenceKey] is the lab's own licence key (checked against the
     * stored fingerprint; it becomes one unlock of the vault). Returns the
     * recovery code — shown ONCE, printable via [printBackupCard].
     */
    suspend fun setUp(folder: String, licenceKey: String): Result<String>

    /** Snapshot now if anything changed (also clears a paused retention). */
    suspend fun backupNow(): Result<Unit>

    /** Unbind the pendrive; the vault key stays so a re-bind reads old generations. */
    suspend fun stop(): Result<Unit>

    /** The recovery code, or null when no vault exists. Screens gate this behind owner + PIN. */
    suspend fun recoveryCode(): String?

    /** Render the A6 backup card (lab, recovery code, pendrive id, restore steps) and open it for printing. */
    suspend fun printBackupCard(): Result<Unit>

    /** Delete every generation on the bound pendrive (DPDP erasure). Screens gate this behind owner + PIN. */
    suspend fun purgeDrive(): Result<Unit>

    // ── restore / move ──

    /** Generations found under [folder] (or its parent / volume root), newest first. */
    suspend fun findBackupsAt(folder: String): Result<List<BackupGeneration>>

    /** Read the manifest of [gen] after unlocking it — what a restore would bring back. */
    suspend fun preview(gen: BackupGeneration, unlock: Unlock): Result<RestorePreview>

    /**
     * Stage [gen] for the next launch: the live database is never touched while
     * open. Applies the carried preferences, drops this PC's device identity so
     * a fresh accession seat and bill series are minted, and adopts the pendrive.
     * The caller must then ask the operator to close and reopen the app.
     */
    suspend fun stageRestore(gen: BackupGeneration, unlock: Unlock): Result<RestoreStaged>

    /**
     * The newest generation on the bound pendrive when this PC's database holds
     * no records (fresh install / reinstall) — the screens offer to restore it.
     * Null when there is nothing to offer.
     */
    suspend fun restoreOfferAtLaunch(): BackupGeneration?

    /** Exit the process so the staged restore is applied by the next launch. */
    fun closeApp()

    // ── lifecycle hooks the app calls ──

    /**
     * Close-window path: when changes are unsaved and the pendrive is present,
     * write a generation first. Returns true when nothing is left unsaved
     * (clean, or flushed in time), false when work exists only on this PC.
     */
    suspend fun flushOnExit(maxWaitMs: Long): Boolean

    /** Snapshot before the records are erased for another lab; then expect the row-count drop. */
    suspend fun beforeTenantWipe()

    /** The vault belongs to the previous lab: forget it so the next lab sets up its own pendrive. */
    suspend fun afterTenantWipe()

    /** Hide the "not backed up" banner until the next session. */
    fun dismissBannerForSession()

    /**
     * A successful ONLINE activation with the licence key landed: BNM now knows
     * this computer, so the "restored from a backup — register it" notice can
     * go ([BackupStatus.restoredFromBackup] clears). No-op on other platforms.
     */
    fun markRegisteredOnline()
}

/** The engine on this platform, or null where there is none (Android, iOS). */
expect fun platformBackupController(): BackupController?

data class BackupStatus(
    val phase: Phase = Phase.NOT_SET_UP,
    /** e.g. "BNM BACKUP (E:)" — the OS display name of the bound volume. */
    val driveName: String? = null,
    /** Epoch millis of the newest generation this PC wrote; null when none. */
    val lastOkAt: Long? = null,
    /** Epoch millis of the last successful read-back verification. */
    val lastVerifiedAt: Long? = null,
    /** Epoch millis of the oldest unsaved change; null when everything is on the pendrive. */
    val dirtySince: Long? = null,
    val generations: Int = 0,
    val bytesOnDrive: Long = 0L,
    /** Short, non-PHI, already worded for staff ("Pendrive full — use a bigger pendrive"). */
    val lastError: String? = null,
    /** The row-count-drop guard tripped: older generations are kept until an owner presses Back up now. */
    val retentionPaused: Boolean = false,
    /** This install came from a restore and has not yet registered online with the licence key. */
    val restoredFromBackup: Boolean = false,
    /**
     * The old PC's seat row under the licence (from the backup's manifest), while
     * [restoredFromBackup]. When BNM answers "all seats in use" at registration,
     * the Activation screen pre-selects exactly this seat to take over.
     */
    val restoredFromDeviceRowId: String? = null,
    /** True once per session after [BackupController.dismissBannerForSession]. */
    val bannerDismissed: Boolean = false,
) {
    enum class Phase { NOT_SET_UP, OK, PENDING, WORKING, DRIVE_MISSING, FAILING, DB_PROBLEM }

    val isSetUp: Boolean get() = phase != Phase.NOT_SET_UP
    val hasUnsavedChanges: Boolean get() = dirtySince != null
}

data class DriveCandidate(
    val path: String,
    /** "BNM BACKUP (E:)" / "Untitled" — never blank. */
    val displayName: String,
    val fsType: String?,
    val totalBytes: Long,
    val freeBytes: Long,
    /** FAT/FAT32/exFAT and not the system volume — the shape of a pendrive. */
    val removableLikely: Boolean,
    /** Same volume as this PC's data directory: a backup there dies with the PC. */
    val sameVolumeAsData: Boolean,
    /** Lab name from an existing backup marker on this volume, when there is one. */
    val existingBackupOf: String?,
)

data class BackupGeneration(
    val seq: Long,
    /** Epoch millis from the manifest header (the writer's clock). */
    val createdAt: Long,
    val bytes: Long,
    val path: String,
    /** Lab name from the plaintext header — readable before unlocking. */
    val labName: String?,
    /** Header unreadable or the file is truncated: listed greyed, never restorable. */
    val damaged: Boolean,
)

data class RestorePreview(
    val labName: String,
    val createdAt: Long,
    val seq: Long,
    val appVersion: String,
    val patients: Int,
    val orders: Int,
    val results: Int,
    val staff: Int,
    val tests: Int,
    /** Null on a PC that is not activated (no fingerprint to compare with). */
    val sameLicence: Boolean?,
    /** Made by a newer BNM Lab than this one — column order is load-bearing, so refuse. */
    val newerThanThisApp: Boolean,
    /** Records on this PC that the restore would set aside (patients count). */
    val currentPatientsHere: Int,
)

data class RestoreStaged(
    /** Notes for the completion text, e.g. "Reports folder reset to the default." */
    val notes: List<String>,
)

/** How a generation is unlocked. */
sealed class Unlock {
    /** The lab's licence key, BNMD-XXXX-XXXX-XXXX-XXXX (case/dash tolerant). */
    data class LicenceKey(val key: String) : Unlock()
    /** The 25-character recovery code from the backup card. */
    data class RecoveryCode(val code: String) : Unlock()
    /** An activated PC restoring its own pendrive: the vault key is already here. */
    data object ThisPc : Unlock()
}
