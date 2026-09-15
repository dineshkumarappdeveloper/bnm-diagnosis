package com.bnm.lab.backup

import app.cash.sqldelight.db.SqlDriver
import java.io.File

/**
 * The two places the database layer touches the backup engine, kept to one
 * call each so DriverFactory stays readable.
 */
internal object BackupHooks {
    /** Right after the driver is constructed: the engine may now read, and learns of every write. */
    fun attach(driver: SqlDriver, dbFile: File) = BackupService.shared.attachDatabase(driver, dbFile)

    /**
     * Is this PC's bound pendrive plugged in right now? Decides whether a
     * MISSING database is left empty for the restore offer instead of being
     * adopted from a months-old legacy directory copy — a stale copy must
     * never silently win over the lab's own backups.
     */
    fun boundPendriveReachable(prefs: BackupPrefs = BackupPrefs()): Boolean {
        val dir = prefs.dir ?: return false
        val id = prefs.vaultId ?: return false
        return runCatching { DriveScan.markerMatches(File(dir), id) }.getOrDefault(false)
    }
}
