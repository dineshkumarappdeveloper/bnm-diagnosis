package com.bnm.diagnosis.db

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
        // AndroidSqliteDriver creates/migrates the schema for us.
        return AndroidSqliteDriver(AppDatabase.Schema, ctx, CHAT_DB_NAME)
    }
}
