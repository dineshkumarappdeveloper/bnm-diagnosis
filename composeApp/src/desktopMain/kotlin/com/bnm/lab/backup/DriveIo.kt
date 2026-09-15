package com.bnm.lab.backup

import com.bnm.lab.diagnostics.AppLog
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * File operations on the pendrive, retried. An on-access antivirus scanner
 * holds a freshly written file open for a few seconds; a rename, delete or
 * read-back that lands in that window fails with a sharing violation and
 * would otherwise count as a failed backup. Ten tries, half a second apart.
 */
internal object DriveIo {
    fun <T> retry(
        what: String,
        times: Int = BackupPolicy.DRIVE_RETRY_TIMES,
        delayMs: Long = BackupPolicy.DRIVE_RETRY_DELAY_MS,
        block: () -> T,
    ): T {
        var last: IOException? = null
        for (attempt in 1..times) {
            try {
                return block()
            } catch (e: IOException) {
                last = e
                if (attempt == times) break
                if (attempt == 1) AppLog.w("Backup", "$what: ${e::class.simpleName}, retrying")
                Thread.sleep(delayMs)
            }
        }
        throw last ?: IOException("$what failed")
    }

    /** Atomic where the file system allows, plain otherwise (FAT on some hosts). */
    fun move(from: Path, to: Path) {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun isDiskFull(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            val m = t.message.orEmpty().lowercase()
            if (m.contains("no space left") || m.contains("not enough space") || m.contains("disk full") ||
                m.contains("insufficient")
            ) return true
            t = t.cause
        }
        return false
    }
}
