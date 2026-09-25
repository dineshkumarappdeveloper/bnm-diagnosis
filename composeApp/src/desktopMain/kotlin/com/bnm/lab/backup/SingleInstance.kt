package com.bnm.lab.backup

import com.bnm.lab.diagnostics.AppLog
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption
import javax.swing.JOptionPane

/**
 * One BNM Lab per data directory. Two processes would run two backup engines
 * against one pendrive with the same generation numbers, and the launch-time
 * restore swap would fail against a database the other process still holds
 * open. The lock is released by the OS when the process ends, however it ends.
 */
internal object SingleInstance {
    const val LOCK_FILE = "bnmlab.lock"
    private var channel: FileChannel? = null
    private var lock: FileLock? = null

    /** True when this process now holds the lock. A lock that cannot be TAKEN AT ALL never blocks the launch. */
    fun acquire(dataDir: File): Boolean {
        return try {
            val ch = FileChannel.open(File(dataDir, LOCK_FILE).toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            val l = try {
                ch.tryLock()
            } catch (e: OverlappingFileLockException) {
                null
            }
            if (l == null) {
                ch.close()
                false
            } else {
                channel = ch
                lock = l
                true
            }
        } catch (e: IOException) {
            AppLog.w("Backup", "single-instance lock unavailable (${e::class.simpleName}); continuing")
            true
        }
    }

    /** The other copy has the window; tell the operator and go away quietly. */
    fun refuseAndExit(): Nothing {
        AppLog.w("Lifecycle", "another BNM Lab already holds the data directory — exiting")
        runCatching {
            JOptionPane.showMessageDialog(null, "BNM Lab is already running.", "BNM Lab", JOptionPane.INFORMATION_MESSAGE)
        }
        kotlin.system.exitProcess(0)
    }

    fun release() {
        runCatching { lock?.release() }
        runCatching { channel?.close() }
        lock = null
        channel = null
    }
}
