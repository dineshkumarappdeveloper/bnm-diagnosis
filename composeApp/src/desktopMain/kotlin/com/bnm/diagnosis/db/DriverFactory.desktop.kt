package com.bnm.diagnosis.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File

actual class DriverFactory actual constructor() {
    actual fun createDriver(): SqlDriver {
        val dbFile = File(appDataDir(), CHAT_DB_NAME)
        // One-time migration: this app USED to write into the shared "BNMAdmin"
        // data dir (clone-chain leftover — BNMAdmin/BNMBilling/BNMDiagnosis all
        // opened the SAME sqlite file). Copy the legacy file once so existing
        // lab data (catalog/patients/orders) follows; foreign tables inside the
        // copy are inert.
        if (!dbFile.exists()) {
            val legacy = File(File(dbFile.parentFile.parentFile, "BNMAdmin"), CHAT_DB_NAME)
            if (legacy.exists()) runCatching { legacy.copyTo(dbFile) }
        }
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

/** OS-appropriate per-user app data directory; created if missing. */
private fun appDataDir(): File {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    val home = System.getProperty("user.home").orEmpty()
    val base = when {
        os.contains("win") -> System.getenv("APPDATA") ?: "$home\\AppData\\Roaming"
        os.contains("mac") -> "$home/Library/Application Support"
        else -> System.getenv("XDG_DATA_HOME") ?: "$home/.local/share"
    }
    return File(base, "BNMDiagnosis").apply { mkdirs() }
}
