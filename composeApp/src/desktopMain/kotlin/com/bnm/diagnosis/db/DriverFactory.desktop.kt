package com.bnm.diagnosis.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File

actual class DriverFactory actual constructor() {
    actual fun createDriver(): SqlDriver {
        val dbFile = File(appDataDir(), CHAT_DB_NAME)
        // Capture freshness BEFORE constructing the driver (which opens/creates the file).
        val needsCreate = !dbFile.exists()
        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        if (needsCreate) {
            // JDBC driver does not auto-create; build the schema on first run.
            AppDatabase.Schema.create(driver)
        }
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
    return File(base, "BNMAdmin").apply { mkdirs() }
}
