package com.bnm.lab.db

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

private var dbContext: Context? = null

/** Called once from MainActivity so the chat DB can open. Mirrors initAudioContext/initPushContext. */
fun initDbContext(context: Context) {
    dbContext = context.applicationContext
}

actual class DriverFactory actual constructor() {
    actual fun createDriver(): SqlDriver {
        val ctx = dbContext
            ?: error("DriverFactory: call initDbContext(context) from MainActivity before opening the DB")
        val driver = AndroidSqliteDriver(AppDatabase.Schema, ctx, CHAT_DB_NAME)
        // Self-healing schema: IF-NOT-EXISTS creates on every open (no .sqm
        // migrations; onCreate only runs for a fresh db).
        AppDatabase.Schema.create(driver)
        return driver
    }
}
