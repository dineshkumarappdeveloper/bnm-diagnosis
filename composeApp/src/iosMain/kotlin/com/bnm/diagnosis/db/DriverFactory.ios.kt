package com.bnm.diagnosis.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver

actual class DriverFactory actual constructor() {
    actual fun createDriver(): SqlDriver {
        // NativeSqliteDriver creates/migrates the schema for us.
        return NativeSqliteDriver(AppDatabase.Schema, CHAT_DB_NAME)
    }
}
