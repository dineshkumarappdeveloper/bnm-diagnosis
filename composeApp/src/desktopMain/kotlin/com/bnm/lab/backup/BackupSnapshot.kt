package com.bnm.lab.backup

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Properties

/**
 * Taking a consistent copy of the live database while the app keeps writing.
 *
 * NEVER a file copy: with a rollback journal a copy taken mid-transaction is a
 * corrupt file. `VACUUM INTO` reads one consistent snapshot under a shared lock
 * and writes a fresh, compact database; app writes that arrive meanwhile wait
 * on their own `busy_timeout` (raised to 10 s in DriverFactory for exactly this).
 *
 * Plain JDBC on purpose — the engine's connection is its own, never the app's
 * driver, and it references no sqlite-jdbc class (only java.sql), so it is
 * indifferent to how SQLDelight wraps the driver.
 */
internal object BackupSnapshot {

    /** The live database is unreadable — SQLITE_CORRUPT or not a database at all. */
    class DatabaseCorrupt(message: String, cause: Throwable?) : Exception(message, cause)

    fun connect(dbFile: File, busyTimeoutMs: Int, readOnly: Boolean): Connection {
        val props = Properties().apply {
            setProperty("busy_timeout", busyTimeoutMs.toString())
            // SQLITE_OPEN_READONLY — the engine never writes the live database.
            if (readOnly) setProperty("open_mode", "1")
        }
        return DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}", props)
    }

    /** `VACUUM INTO` [staging] (deleted first — SQLite refuses an existing target). Returns the duration in ms. */
    fun vacuumInto(conn: Connection, staging: File): Long {
        staging.parentFile?.mkdirs()
        if (staging.exists() && !staging.delete()) throw java.io.IOException("could not remove the previous staging file")
        val t0 = System.nanoTime()
        try {
            conn.createStatement().use { it.execute("VACUUM INTO '${staging.absolutePath.replace("'", "''")}'") }
        } catch (e: SQLException) {
            if (isCorrupt(e)) throw DatabaseCorrupt("the database failed its integrity check during the snapshot", e)
            throw e
        }
        return (System.nanoTime() - t0) / 1_000_000
    }

    /** Changes committed by ANY connection since the last call on the same [conn] bump this. */
    fun dataVersion(conn: Connection): Long =
        conn.createStatement().use { st -> st.executeQuery("PRAGMA data_version").use { rs -> if (rs.next()) rs.getLong(1) else 0L } }

    /**
     * Make the staging copy self-contained and prove it is readable: rollback
     * journal mode (a WAL header inherited from a legacy copy must not travel),
     * `quick_check` must answer `ok`, then the row counts the manifest carries.
     */
    fun prepareStaging(staging: File): BackupCounts {
        connect(staging, busyTimeoutMs = 5_000, readOnly = false).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("PRAGMA journal_mode=DELETE").use { it.next() }
            }
            val verdict = quickCheck(conn)
            if (verdict != "ok") throw DatabaseCorrupt("snapshot failed quick_check: $verdict", null)
            return counts(conn)
        }
    }

    fun quickCheck(conn: Connection): String = conn.createStatement().use { st ->
        st.executeQuery("PRAGMA quick_check").use { rs ->
            val rows = ArrayList<String>()
            while (rs.next() && rows.size < 5) rows += rs.getString(1)
            if (rows.size == 1) rows[0] else rows.joinToString("; ").ifBlank { "no answer" }
        }
    }

    fun quickCheck(dbFile: File): String =
        connect(dbFile, busyTimeoutMs = 5_000, readOnly = true).use { quickCheck(it) }

    /** The five tenant counts. A table an older file lacks counts as zero rather than failing the snapshot. */
    fun counts(conn: Connection): BackupCounts {
        fun count(table: String): Long = runCatching {
            conn.createStatement().use { st ->
                st.executeQuery("SELECT count(*) FROM $table").use { rs -> if (rs.next()) rs.getLong(1) else 0L }
            }
        }.getOrDefault(0L)
        return BackupCounts(
            patients = count("patients"), orders = count("lab_orders"), results = count("lab_results"),
            staff = count("staff"), tests = count("lab_tests"),
        )
    }

    /** Counts of the live database on its own read-only connection; all zero when the file is missing. */
    fun countsOf(dbFile: File): BackupCounts {
        if (!dbFile.isFile) return BackupCounts()
        return connect(dbFile, busyTimeoutMs = 5_000, readOnly = true).use { counts(it) }
    }

    /**
     * Patients, orders or results down by more than [BackupPolicy.ROW_DROP_GUARD_PERCENT]
     * against the previous generation: a wiped or wrong database is about to
     * become "the newest backup" — write it, but stop pruning the good ones.
     */
    fun dropped(previous: BackupCounts, now: BackupCounts): Boolean {
        fun drop(before: Long, after: Long): Boolean =
            before > 0 && after < before && (before - after) * 100 / before > BackupPolicy.ROW_DROP_GUARD_PERCENT
        return drop(previous.patients, now.patients) || drop(previous.orders, now.orders) || drop(previous.results, now.results)
    }

    fun isCorrupt(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            val m = t.message.orEmpty()
            if (m.contains("SQLITE_CORRUPT") || m.contains("SQLITE_NOTADB") || m.contains("malformed") ||
                m.contains("not a database")
            ) return true
            t = t.cause
        }
        return false
    }
}
