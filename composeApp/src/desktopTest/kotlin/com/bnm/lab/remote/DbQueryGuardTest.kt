package com.bnm.lab.remote

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.bnm.lab.db.AppDatabase
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `db.query` may only ever READ. Two layers, tested separately: the guard (what
 * text is accepted at all, and which tables need the owner's consent) and the
 * connection (opened read-only, so even a statement that slipped past the
 * guard could not write).
 */
class DbQueryGuardTest {

    private fun refused(sql: String, consent: Boolean = false): String {
        val v = DbQueryGuard.check(sql, consent)
        assertIs<DbQueryGuard.Verdict.Refused>(v, "expected a refusal for: $sql")
        return v.reason
    }

    private fun allowed(sql: String, consent: Boolean = false) {
        assertEquals(DbQueryGuard.Verdict.Allowed, DbQueryGuard.check(sql, consent), "expected to allow: $sql")
    }

    @Test
    fun `writes, chains and write pragmas are refused`() {
        refused("INSERT INTO lab_settings(key, value, updated_at) VALUES ('a', 'b', NULL)")
        refused("UPDATE instruments SET enabled = 0")
        refused("DELETE FROM instrument_log")
        refused("DROP TABLE instruments")
        assertTrue("One statement" in refused("SELECT 1; DROP TABLE instruments"))
        refused("PRAGMA journal_mode = WAL")
        refused("PRAGMA journal_mode=DELETE")
        refused("PRAGMA foreign_keys = ON")
        assertTrue("wal_checkpoint" in refused("PRAGMA wal_checkpoint"))
        refused("pragma cache_size(1000)")          // the parenthesised ASSIGNMENT form
        refused("ATTACH DATABASE '/tmp/x.db' AS x")
        refused("")
    }

    @Test
    fun `reads are allowed - select, with, explain and read-only pragmas`() {
        allowed("SELECT * FROM instruments")
        allowed("select id, name from instruments where enabled = 1;")
        allowed("WITH x AS (SELECT 1 AS n) SELECT * FROM x")
        allowed("EXPLAIN QUERY PLAN SELECT * FROM instrument_log")
        allowed("PRAGMA table_info(instruments)")
        allowed("PRAGMA index_list('instrument_log')")
        allowed("PRAGMA journal_mode")
        allowed("PRAGMA integrity_check")
        allowed("SELECT name FROM sqlite_master WHERE type = 'table'")
    }

    @Test
    fun `tables holding patient records need the owner's consent`() {
        for (t in DbQueryGuard.PHI_TABLES) {
            assertTrue(t in refused("SELECT count(*) FROM $t"), "refusal names the table $t")
            allowed("SELECT count(*) FROM $t", consent = true)
        }
        // Case-insensitive, anywhere in the text, including a JOIN and a subquery.
        refused("select * from Patients")
        refused("SELECT i.name FROM instruments i JOIN lab_orders o ON 1=1")
        refused("SELECT * FROM instruments WHERE id IN (SELECT instrument_id FROM instrument_results)")
        // A column that merely LOOKS like a PHI table trips it too — the safe direction.
        refused("SELECT patients FROM some_view")
        // Non-PHI tables never need consent.
        allowed("SELECT * FROM instrument_log")
        allowed("SELECT * FROM lab_tests")
        allowed("SELECT * FROM staff")
    }

    @Test
    fun `row cap and string cut`() {
        assertEquals(50, DbQueryGuard.clampRows(null))
        assertEquals(200, DbQueryGuard.clampRows(5_000))
        assertEquals(1, DbQueryGuard.clampRows(0))
        assertEquals(DbQueryGuard.MAX_STRING_CHARS + 1, DbQueryGuard.cut("x".repeat(5_000))!!.length)
        assertEquals("short", DbQueryGuard.cut("short"))
        assertEquals(listOf("instruments", "lab_tests"),
            DbQueryGuard.tablesMentioned("select * from lab_tests t, instruments i", listOf("patients", "instruments", "lab_tests")))
    }

    @Test
    fun `the read-only connection cannot write, reads, and reports truncation`() = runBlocking<Unit> {
        val file = Files.createTempFile("bnmlab-ro", ".db").toFile().also { it.deleteOnExit() }
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        AppDatabase.Schema.create(driver)
        val db = AppDatabase(driver)
        val now = "2026-09-25T10:00:00Z"
        db.instrumentsQueries.upsertInstrument("i1", "Mispa", "mispa_count_x", "tcp", null, 115200L, 5500L, 1L, null, now, now, 0L, null)

        val ro = JdbcReadOnlySql(file)

        // Even with the guard out of the way, the connection refuses to write.
        assertFails { ro.query("INSERT INTO lab_settings(key, value, updated_at) VALUES ('k', 'v', NULL)", 10) }
        assertFails { ro.query("UPDATE instruments SET enabled = 0", 10) }
        assertEquals(1L, db.instrumentsQueries.instrumentById("i1").executeAsOne().enabled, "nothing changed")
        assertEquals(0, ro.query("SELECT * FROM lab_settings", 10).rows.size, "the refused insert wrote nothing")

        // Reads work and come back as columns + rows.
        val rs = ro.query("SELECT id, name, enabled FROM instruments", 10)
        assertEquals(listOf("id", "name", "enabled"), rs.columns)
        assertEquals(listOf(listOf("i1", "Mispa", "1")), rs.rows)
        assertEquals(false, rs.truncated)

        // PRAGMA reads run too.
        assertTrue(ro.query("PRAGMA table_info(instruments)", 50).rows.any { it[1] == "verify_pending" })

        // The cap: 300 generated rows, 200 asked for → truncated.
        val big = ro.query("WITH RECURSIVE n(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM n WHERE x < 300) SELECT x FROM n", 200)
        assertEquals(200, big.rows.size)
        assertEquals(true, big.truncated)
        driver.close()
    }
}
