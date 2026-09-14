package com.bnm.lab.db

import com.bnm.lab.diagnostics.AppLog

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File

actual class DriverFactory actual constructor() {
    actual fun createDriver(): SqlDriver {
        val dbFile = File(appDataDir(), CHAT_DB_NAME)
        // One-time migration into this data dir, tried NEWEST-LEGACY-FIRST.
        //
        // This matters more than it looks. SQLDelight is the SYSTEM OF RECORD for
        // a lab — a standalone licence never syncs, so for those installs there is
        // no server copy at all. If the app opens a path with no database, the
        // self-healing schema below cheerfully CREATES an empty one: no crash, no
        // warning, and a lab starts its day with every patient, result and bill
        // apparently gone. So every historical location is checked, the newest
        // first, and the file is COPIED (never moved) so the original stays intact
        // if anything goes wrong.
        //
        //   "BNMDiagnosis" — this app's own dir before the BNM Lab rename.
        //   "BNMAdmin"     — the clone-chain leftover, when all three apps shared
        //                    one sqlite file. Foreign tables in the copy are inert.
        if (!dbFile.exists()) {
            val parent = dbFile.parentFile.parentFile
            for (legacyDir in LEGACY_APP_DIRS) {
                val legacy = File(File(parent, legacyDir), CHAT_DB_NAME)
                if (!legacy.exists()) continue
                val copied = runCatching {
                    legacy.copyTo(dbFile, overwrite = false)
                    // SQLite keeps recent commits in the -wal sidecar until a
                    // checkpoint; copying the main file alone can silently drop
                    // the most recent work. Bring the sidecars along too.
                    for (suffix in listOf("-wal", "-shm")) {
                        val side = File(legacy.parentFile, CHAT_DB_NAME + suffix)
                        if (side.exists()) {
                            side.copyTo(File(dbFile.parentFile, CHAT_DB_NAME + suffix), overwrite = false)
                        }
                    }
                }.onFailure {
                    AppLog.e("Database", "copying legacy database from $legacyDir failed", it)
                }.isSuccess
                if (copied) {
                    AppLog.w("Database", "no database in ${dbFile.parentFile.name}; adopted a copy from " +
                        "$legacyDir (${legacy.length() / 1024} KB)")
                    break
                }
            }
            // The failure mode this block exists to prevent: a brand-new EMPTY
            // database. When a lab says "all our patients disappeared", this line
            // is the first thing to look for.
            if (!dbFile.exists()) AppLog.w("Database", "no existing database found anywhere — starting EMPTY at ${dbFile.absolutePath}")
        }
        AppLog.i("Database", "opening ${dbFile.absolutePath} (${dbFile.length() / 1024} KB)")
        // Capture freshness BEFORE constructing the driver (which opens/creates the file).
        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        // Self-healing schema: every CREATE is IF NOT EXISTS — create on EVERY
        // open adds tables an older on-disk db lacks, keeping data (columns are
        // healed separately by AppDatabaseFactory's addColumn migrations).
        AppDatabase.Schema.create(driver)
        // NOTE: when schema version > 1 ships, add PRAGMA user_version tracking +
        // AppDatabase.Schema.migrate(...) here.
        return driver
    }
}

/**
 * Data directories this app has used before, newest first — checked once when
 * the current one has no database (see [DriverFactory.createDriver]).
 *
 * MUST NOT contain the CURRENT directory name: the lookup would find the file
 * it is standing in and copy nothing, which is how a rename silently orphans a
 * lab's records. A rename adds the OLD name here; it never renames the entries.
 */
internal val LEGACY_APP_DIRS = listOf("BNMDiagnosis", "BNMAdmin")

/** This app's own data directory name — [LEGACY_APP_DIRS] must never list it. */
internal const val APP_DIR_NAME = "BNMLab"

/** OS-appropriate per-user app data directory; created if missing. */
internal fun appDataDir(): File {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    val home = System.getProperty("user.home").orEmpty()
    val base = when {
        os.contains("win") -> System.getenv("APPDATA") ?: "$home\\AppData\\Roaming"
        os.contains("mac") -> "$home/Library/Application Support"
        else -> System.getenv("XDG_DATA_HOME") ?: "$home/.local/share"
    }
    return File(base, APP_DIR_NAME).apply { mkdirs() }
}
