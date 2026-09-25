package com.bnm.lab

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.bnm.lab.db.AppDatabase
import com.bnm.lab.db.addColumn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `SELECT *` maps positionally, so every column added to `instruments` after
 * install must be the LAST one both in Instruments.sq and after the
 * AppDatabaseFactory self-heal on an already-installed database.
 */
class InstrumentSchemaTest {

    private fun columns(driver: JdbcSqliteDriver): List<String> =
        driver.getConnection().createStatement().use { st ->
            st.executeQuery("PRAGMA table_info(instruments)").use { rs ->
                buildList { while (rs.next()) add(rs.getString("name")) }
            }
        }

    @Test
    fun `analyzer_host is the last column of a fresh schema`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        val cols = columns(driver)
        assertEquals("analyzer_host", cols.last(), cols.toString())
        assertEquals("verify_pending", cols[cols.size - 2], cols.toString())
    }

    @Test
    fun `an installed database gains analyzer_host last through the column self-heal, and the upsert round-trips it`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        driver.execute(null, "DROP TABLE instruments", 0)
        driver.execute(null,
            "CREATE TABLE instruments (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, driver_key TEXT NOT NULL, " +
                "transport TEXT NOT NULL, serial_port TEXT, baud INTEGER NOT NULL DEFAULT 115200, tcp_port INTEGER, " +
                "enabled INTEGER NOT NULL DEFAULT 1, param_map_json TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL)", 0)
        driver.execute(null, "INSERT INTO instruments VALUES ('old', 'Old', 'mispa_count_x', 'tcp', NULL, 115200, 5500, 1, NULL, 'a', 'b')", 0)
        // The same order AppDatabaseFactory applies, twice (idempotent).
        repeat(2) {
            driver.addColumn("instruments", "verify_pending", "INTEGER NOT NULL DEFAULT 0")
            driver.addColumn("instruments", "analyzer_host", "TEXT")
        }
        val cols = columns(driver)
        assertEquals("analyzer_host", cols.last(), cols.toString())

        val db = AppDatabase(driver)
        assertNull(db.instrumentsQueries.instrumentById("old").executeAsOne().analyzer_host)
        db.instrumentsQueries.upsertInstrument("old", "Old", "mispa_count_x", "tcp", null, 115200L, 5500L, 1L, null, "a", "c", 0L, "192.168.1.77")
        assertEquals("192.168.1.77", db.instrumentsQueries.instrumentById("old").executeAsOne().analyzer_host)
    }
}
