package com.bnm.lab.backup

import com.bnm.lab.diagnostics.AppLog
import com.russhwolf.settings.Settings
import java.io.File
import java.nio.file.Files
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** The two refusals a restore preview can end in. */
internal object RestoreGuards {
    /** Column order is load-bearing (positional `SELECT *`): a file made by a NEWER build is refused. */
    fun isNewer(manifestVersion: String, appVersion: String): Boolean {
        val a = parse(manifestVersion) ?: return false
        val b = parse(appVersion) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Null on a PC with no fingerprint (not activated) — nothing to compare with. */
    fun sameLicence(storedFp: String?, manifestFp: String?): Boolean? =
        storedFp?.takeIf { it.isNotBlank() }?.let { it == manifestFp }

    private fun parse(v: String): List<Int>? {
        val core = v.trim().substringBefore('-').substringBefore('+')
        val parts = core.split('.').map { it.toIntOrNull() ?: return null }
        return parts.takeIf { it.isNotEmpty() }
    }
}

/**
 * The restore that never touches the open database.
 *
 * [BackupService.stageRestore] decrypts a generation to
 * `bnm_chat.db.restore-pending` beside the live file and writes the carried
 * preferences; the NEXT launch calls [applyPending] before the database is
 * opened (DriverFactory), which sets the current file — and its rollback
 * journal, never deleted — aside and moves the pending one into place.
 */
internal object RestoreStaging {
    const val PENDING_SUFFIX = ".restore-pending"
    const val SET_ASIDE_INFIX = ".before-restore-"
    /** Written by the staging step, read (for the log line) and removed by the swap. */
    const val APPLIED_MARKER = "restore-prefs-applied"
    val SIDECARS = listOf("-journal", "-wal", "-shm")
    const val KEEP_SET_ASIDE = 3
    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    fun pendingFile(dbFile: File): File = File(dbFile.parentFile, dbFile.name + PENDING_SUFFIX)

    /**
     * Write the carried preferences into [settings] and drop this PC's device
     * identity, so the next launch mints a fresh device id (fresh accession
     * seat, fresh bill series — nothing issued after the chosen generation can
     * be re-issued). A reports folder that does not exist on this PC goes back
     * to the default rather than silently swallowing every filed report.
     */
    fun applyCarriedPrefs(
        carried: Map<String, String>,
        settings: Settings,
        pathExists: (String) -> Boolean = { File(it).isDirectory },
    ): List<String> {
        val notes = ArrayList<String>()
        BackupAllowList.apply(carried, settings)
        for (k in BackupAllowList.DEVICE_KEYS) settings.remove(k)
        val reportsDir = settings.getStringOrNull(REPORTS_DIR_KEY)
        if (!reportsDir.isNullOrBlank() && !pathExists(reportsDir)) {
            settings.remove(REPORTS_DIR_KEY)
            notes += "Reports folder reset to the default (the old one does not exist on this computer)."
        }
        return notes
    }

    class Applied(val seq: Long?)

    /**
     * At launch, before the database is opened: if a staged restore exists,
     * move `bnm_chat.db` and every sidecar to `.before-restore-<stamp>` (the
     * newest [KEEP_SET_ASIDE] sets are kept), then move the staged file into
     * place. Null when nothing is pending. Throws when the swap cannot be
     * completed — the caller must NOT open a half-swapped database.
     */
    fun applyPending(dbFile: File, now: ZonedDateTime = ZonedDateTime.now()): Applied? {
        val pending = pendingFile(dbFile)
        if (!pending.exists()) return null
        val dir = dbFile.parentFile
        val stamp = now.format(STAMP)
        DriveIo.retry("set the current database aside") {
            for (suffix in listOf("") + SIDECARS) {
                val live = File(dir, dbFile.name + suffix)
                if (live.exists()) Files.move(live.toPath(), File(dir, dbFile.name + suffix + SET_ASIDE_INFIX + stamp).toPath())
            }
        }
        DriveIo.retry("move the restored database into place") { DriveIo.move(pending.toPath(), dbFile.toPath()) }
        pruneSetAside(dbFile)
        val marker = File(dir, APPLIED_MARKER)
        val seq = runCatching { marker.readText().trim().removePrefix("seq=").toLongOrNull() }.getOrNull()
        runCatching { marker.delete() }
        return Applied(seq)
    }

    /** Keep the newest [KEEP_SET_ASIDE] set-aside sets (a set = the database and its sidecars under one stamp). */
    fun pruneSetAside(dbFile: File) {
        val files = dbFile.parentFile.listFiles { f -> f.isFile && f.name.startsWith(dbFile.name) && f.name.contains(SET_ASIDE_INFIX) }
            ?: return
        val byStamp = files.groupBy { it.name.substringAfterLast(SET_ASIDE_INFIX) }
        byStamp.keys.sortedDescending().drop(KEEP_SET_ASIDE).forEach { stamp ->
            byStamp.getValue(stamp).forEach { f -> runCatching { f.delete() } }
        }
    }

    fun writeAppliedMarker(dbFile: File, seq: Long) {
        runCatching { File(dbFile.parentFile, APPLIED_MARKER).writeText("seq=$seq") }
            .onFailure { AppLog.w("Backup", "could not write the restore marker: ${it::class.simpleName}") }
    }

    private const val REPORTS_DIR_KEY = "report_archive_dir"
}
