package com.bnm.diagnosis.db

import app.cash.sqldelight.db.SqlDriver

/** Filename of the on-device chat database (used by all three platform drivers). */
const val CHAT_DB_NAME = "bnm_chat.db"

/**
 * Creates the platform [SqlDriver] backing [AppDatabase].
 *
 * - Android: `AndroidSqliteDriver` (handles schema create/migrate automatically).
 * - iOS: `NativeSqliteDriver` (handles schema create/migrate automatically).
 * - Desktop/JVM: `JdbcSqliteDriver` — does NOT auto-create, so the actual runs
 *   `AppDatabase.Schema.create(driver)` on first launch.
 */
expect class DriverFactory() {
    fun createDriver(): SqlDriver
}
