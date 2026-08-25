package com.bnm.diagnosis.db

import app.cash.sqldelight.db.SqlDriver

/**
 * Idempotent `ALTER TABLE … ADD COLUMN` — the column-level twin of the
 * CREATE TABLE IF NOT EXISTS self-heal below, for columns added to a table a
 * device already has. SQLite offers no IF NOT EXISTS for columns: adding one
 * that already exists raises "duplicate column name", which is exactly the
 * no-op wanted on the second and every later run. Any other failure is equally
 * non-fatal — opening the app must never depend on a migration succeeding.
 *
 * `internal` so the upgrade path is directly testable (EmrIdentityTest).
 */
internal fun SqlDriver.addColumn(table: String, column: String, type: String) {
    runCatching { execute(null, "ALTER TABLE $table ADD COLUMN $column $type", 0) }
}

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
    // ── LIMS tables (P1a) — same self-heal for devices installed before them ──
    driver.execute(null,
        "CREATE TABLE IF NOT EXISTS patients (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, " +
        "sex TEXT NOT NULL DEFAULT 'O', dob TEXT, age_years INTEGER, phone TEXT, address TEXT, " +
        "created_at TEXT NOT NULL, updated_at TEXT NOT NULL, deleted_at TEXT)", 0)
    driver.execute(null, "CREATE INDEX IF NOT EXISTS patients_name ON patients(name)", 0)
    driver.execute(null, "CREATE INDEX IF NOT EXISTS patients_phone ON patients(phone)", 0)
    driver.execute(null,
        "CREATE TABLE IF NOT EXISTS referrers (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, " +
        "kind TEXT NOT NULL DEFAULT 'doctor', phone TEXT, commission_pct REAL NOT NULL DEFAULT 0, " +
        "created_at TEXT NOT NULL, deleted_at TEXT)", 0)
    driver.execute(null,
        "CREATE TABLE IF NOT EXISTS lab_tests (id TEXT NOT NULL PRIMARY KEY, code TEXT NOT NULL UNIQUE, " +
        "name TEXT NOT NULL, category TEXT, price REAL NOT NULL DEFAULT 0, " +
        "sample_type TEXT NOT NULL DEFAULT 'blood', method TEXT, active INTEGER NOT NULL DEFAULT 1, " +
        "sort_order INTEGER NOT NULL DEFAULT 0, parameters_json TEXT NOT NULL)", 0)
    driver.execute(null,
        "CREATE TABLE IF NOT EXISTS lab_panels (id TEXT NOT NULL PRIMARY KEY, code TEXT NOT NULL UNIQUE, " +
        "name TEXT NOT NULL, price REAL NOT NULL DEFAULT 0, test_ids_json TEXT NOT NULL, " +
        "active INTEGER NOT NULL DEFAULT 1)", 0)
    driver.execute(null,
        "CREATE TABLE IF NOT EXISTS lab_orders (id TEXT NOT NULL PRIMARY KEY, accession_no TEXT NOT NULL UNIQUE, " +
        "patient_id TEXT NOT NULL, referrer_id TEXT, invoice_id TEXT, status TEXT NOT NULL DEFAULT 'registered', " +
        "priority TEXT NOT NULL DEFAULT 'routine', notes TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, " +
        "collected_at TEXT, approved_at TEXT, reported_at TEXT)", 0)
    driver.execute(null, "CREATE INDEX IF NOT EXISTS lab_orders_status ON lab_orders(status, created_at)", 0)
    driver.execute(null, "CREATE INDEX IF NOT EXISTS lab_orders_patient ON lab_orders(patient_id, created_at)", 0)
    driver.execute(null,
        "CREATE TABLE IF NOT EXISTS lab_order_tests (id TEXT NOT NULL PRIMARY KEY, order_id TEXT NOT NULL, " +
        "test_id TEXT NOT NULL, test_name TEXT NOT NULL, price REAL NOT NULL DEFAULT 0, " +
        "status TEXT NOT NULL DEFAULT 'pending')", 0)
    driver.execute(null, "CREATE INDEX IF NOT EXISTS lab_order_tests_order ON lab_order_tests(order_id)", 0)
    driver.execute(null,
        "CREATE TABLE IF NOT EXISTS lab_results (id TEXT NOT NULL PRIMARY KEY, order_id TEXT NOT NULL, " +
        "test_id TEXT NOT NULL, parameter_key TEXT NOT NULL, value TEXT, unit TEXT, flag TEXT, " +
        "ref_display TEXT, notes TEXT, entered_by TEXT, entered_at TEXT, verified_by TEXT, verified_at TEXT, " +
        "approved_by TEXT, approved_at TEXT)", 0)
    driver.execute(null,
        "CREATE UNIQUE INDEX IF NOT EXISTS lab_results_key ON lab_results(order_id, test_id, parameter_key)", 0)
    driver.execute(null, "CREATE INDEX IF NOT EXISTS lab_results_order ON lab_results(order_id)", 0)
    driver.execute(null,
        "CREATE TABLE IF NOT EXISTS accession_series (seat TEXT NOT NULL PRIMARY KEY, " +
        "prefix TEXT NOT NULL DEFAULT 'ACC', high_water INTEGER NOT NULL DEFAULT 0)", 0)
    // ── P4: per-referrer B2B rate lists (no row = catalog price stands) ──
    driver.execute(null,
        "CREATE TABLE IF NOT EXISTS referrer_rates (referrer_id TEXT NOT NULL, test_id TEXT NOT NULL, " +
        "price REAL NOT NULL, PRIMARY KEY (referrer_id, test_id))", 0)
    // ── P3 sync: EMR bridge inbox ──
    driver.execute(null,
        "CREATE TABLE IF NOT EXISTS emr_inbox (id TEXT NOT NULL PRIMARY KEY, visit_id TEXT, " +
        "test_name TEXT NOT NULL, instructions TEXT, status TEXT, lab_status TEXT, accession_no TEXT, " +
        "matched_order_id TEXT, seq INTEGER NOT NULL DEFAULT 0, done INTEGER NOT NULL DEFAULT 0, " +
        "status_pushed INTEGER NOT NULL DEFAULT 0, created_at TEXT, test_code TEXT, visit_number TEXT, " +
        "patient_name TEXT, patient_phone TEXT, patient_sex TEXT, patient_dob TEXT)", 0)
    driver.execute(null, "CREATE INDEX IF NOT EXISTS emr_inbox_open ON emr_inbox(done, seq)", 0)
    // P3b identity columns: devices whose emr_inbox predates them already have
    // the table, so CREATE IF NOT EXISTS is a no-op there — add the columns
    // one by one instead. SQLite has no ADD COLUMN IF NOT EXISTS; a re-run just
    // errors with "duplicate column name", which addColumn swallows. Order
    // matches the CREATE above so SELECT * maps positionally either way.
    driver.addColumn("emr_inbox", "test_code", "TEXT")
    driver.addColumn("emr_inbox", "visit_number", "TEXT")
    driver.addColumn("emr_inbox", "patient_name", "TEXT")
    driver.addColumn("emr_inbox", "patient_phone", "TEXT")
    driver.addColumn("emr_inbox", "patient_sex", "TEXT")
    driver.addColumn("emr_inbox", "patient_dob", "TEXT")
    // ── P4: staff accounts + roles (local RBAC, synced across the lab's seats) ──
    driver.execute(null,
        "CREATE TABLE IF NOT EXISTS staff (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, " +
        "role TEXT NOT NULL DEFAULT 'receptionist', pin_hash TEXT, active INTEGER NOT NULL DEFAULT 1, " +
        "created_at TEXT NOT NULL, updated_at TEXT NOT NULL, deleted_at TEXT)", 0)
    driver.execute(null, "CREATE INDEX IF NOT EXISTS staff_active ON staff(active, name)", 0)
    return AppDatabase(driver)
}
