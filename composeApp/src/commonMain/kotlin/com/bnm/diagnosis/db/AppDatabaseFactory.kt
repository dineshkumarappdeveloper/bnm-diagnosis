package com.bnm.diagnosis.db

/**
 * Builds the single app-wide [AppDatabase].
 *
 * Construct ONCE (remembered at the App root in App.kt) and thread it down — the
 * underlying SQLite connection is meant to be a process singleton. On Android,
 * `initDbContext(context)` must have run first (MainActivity does this before
 * setContent); iOS/Desktop need no prior setup.
 */
fun createAppDatabase(driverFactory: DriverFactory = DriverFactory()): AppDatabase {
    val driver = driverFactory.createDriver()
    // Self-heal for tables added AFTER a device's DB was first created. Without
    // this, an already-installed app (e.g. one that only had the chat tables)
    // crashes with "no such table: ecom_entity" the moment a commerce screen
    // queries it. CREATE IF NOT EXISTS is a no-op on fresh installs.
    driver.execute(null,
        "CREATE TABLE IF NOT EXISTS ecom_entity (entity TEXT NOT NULL, id TEXT NOT NULL, " +
        "business_id TEXT NOT NULL, seq INTEGER NOT NULL DEFAULT 0, created_at TEXT, " +
        "json TEXT NOT NULL, PRIMARY KEY (entity, id))", 0)
    driver.execute(null,
        "CREATE INDEX IF NOT EXISTS ecom_entity_list ON ecom_entity(entity, business_id, created_at)", 0)
    driver.execute(null,
        "CREATE TABLE IF NOT EXISTS counter_series (business_id TEXT NOT NULL, series TEXT NOT NULL, " +
        "fy TEXT NOT NULL, high_water INTEGER NOT NULL DEFAULT 0, prefix TEXT NOT NULL DEFAULT 'INV', " +
        "number_format TEXT NOT NULL DEFAULT '{prefix}-{series}-{seq}', PRIMARY KEY (business_id, series, fy))", 0)
    driver.execute(null,
        "CREATE TABLE IF NOT EXISTS billing_outbox (id TEXT NOT NULL PRIMARY KEY, business_id TEXT NOT NULL, " +
        "op TEXT NOT NULL, aggregate_id TEXT NOT NULL, idempotency_key TEXT NOT NULL, payload TEXT NOT NULL, " +
        "created_at TEXT NOT NULL, seq_local INTEGER NOT NULL, depends_on TEXT, attempts INTEGER NOT NULL DEFAULT 0, " +
        "next_attempt_at TEXT, status TEXT NOT NULL DEFAULT 'pending', last_error TEXT)", 0)
    driver.execute(null,
        "CREATE INDEX IF NOT EXISTS billing_outbox_drain ON billing_outbox(status, created_at, seq_local)", 0)
    driver.execute(null,
        "CREATE UNIQUE INDEX IF NOT EXISTS billing_outbox_idem ON billing_outbox(idempotency_key)", 0)
    return AppDatabase(driver)
}
