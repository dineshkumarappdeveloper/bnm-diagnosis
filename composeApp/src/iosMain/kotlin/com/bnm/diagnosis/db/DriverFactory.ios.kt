package com.bnm.diagnosis.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver

actual class DriverFactory actual constructor() {
    actual fun createDriver(): SqlDriver {
        val driver = NativeSqliteDriver(AppDatabase.Schema, CHAT_DB_NAME)
        // Self-healing schema: IF-NOT-EXISTS creates on every open.
        AppDatabase.Schema.create(driver)
        return driver
    }
}
