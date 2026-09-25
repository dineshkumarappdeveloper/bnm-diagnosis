package com.bnm.lab.remote

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.bnm.lab.db.AppDatabase
import com.bnm.lab.remote.RemoteTestFixtures.waitFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.sql.SQLTimeoutException
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

    private val none = SupportConsent()
    private val recordsOnly = SupportConsent(records = true)
    private val analyzerOnly = SupportConsent(analyzerData = true)
    private val all = SupportConsent(analyzerData = true, records = true, screen = true)

    private fun refused(sql: String, consent: SupportConsent = none): String {
        val v = DbQueryGuard.check(sql, consent)
        assertIs<DbQueryGuard.Verdict.Refused>(v, "expected a refusal for: $sql")
        return v.reason
    }

    private fun allowed(sql: String, consent: SupportConsent = none) {
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
        allowed("EXPLAIN QUERY PLAN SELECT * FROM instruments")
        allowed("PRAGMA table_info(instruments)")
        allowed("PRAGMA index_list('instruments')")
        allowed("PRAGMA journal_mode")
        allowed("PRAGMA integrity_check")
        allowed("SELECT name FROM sqlite_master WHERE type = 'table'")
    }

    @Test
    fun `tables holding patient records need the owner's consent`() {
        for (t in DbQueryGuard.PHI_TABLES) {
            assertTrue(t in refused("SELECT count(*) FROM $t"), "refusal names the table $t")
            allowed("SELECT count(*) FROM $t", all)
        }
        // Case-insensitive, anywhere in the text, including a JOIN and a subquery.
        refused("select * from Patients")
        refused("SELECT i.name FROM instruments i JOIN lab_orders o ON 1=1")
        refused("SELECT * FROM instruments WHERE id IN (SELECT instrument_id FROM instrument_results)")
        // A column that merely LOOKS like a PHI table trips it too — the safe direction.
        refused("SELECT patients FROM some_view")
        // Staff carry PIN hashes, signatures and council numbers: records consent, like the patients.
        assertTrue("staff" in refused("SELECT id, name, role, registration_no FROM staff"))
        allowed("SELECT id, name, role, registration_no FROM staff", recordsOnly)
        // Non-PHI tables never need consent.
        allowed("SELECT * FROM instruments")
        allowed("SELECT * FROM lab_tests")
        allowed("SELECT * FROM support_audit")
    }

    @Test
    fun `the analyzer frame log follows the analyzer-data tick, not the records one`() {
        // instrument_log.raw IS the frame as it arrived — an HL7 PID with the
        // patient's name, the Mispa PatientID — which instruments.log only
        // shares scrubbed and under this same tick.
        val sql = "SELECT raw FROM instrument_log WHERE direction = 'rx'"
        assertTrue("analyzer data" in refused(sql), refused(sql))
        assertTrue("instrument_log" in refused(sql, recordsOnly), "records consent is the wrong tick for frames")
        allowed(sql, analyzerOnly)
        allowed(sql, all)
        // …and the other way round: analyzer data says nothing about patients.
        assertTrue("records" in refused("SELECT name FROM patients", analyzerOnly))
        // A query across both needs both ticks, and names the missing one.
        val joined = "SELECT l.raw FROM instrument_log l JOIN lab_orders o ON 1=1"
        assertTrue("instrument_log" in refused(joined, recordsOnly))
        assertTrue("lab_orders" in refused(joined, analyzerOnly))
        allowed(joined, all)
    }

    @Test
    fun `PIN hashes and signature images are refused whatever the consent`() {
        for (consent in listOf(none, recordsOnly, analyzerOnly, all)) {
            assertTrue("pin_hash" in refused("SELECT id, name, pin_hash FROM staff", consent))
            assertTrue("signature_png" in refused("select signature_png from staff where id = 'x'", consent))
            assertTrue("pin_hash" in refused("SELECT * FROM staff WHERE PIN_HASH IS NOT NULL", consent))
        }
        // A `SELECT *` cannot be caught by name — the tool blanks those columns instead.
        assertEquals(listOf("pin_hash", "Signature_PNG"), DbQueryGuard.hiddenColumns(listOf("id", "pin_hash", "name", "Signature_PNG")))
        assertEquals(emptyList(), DbQueryGuard.hiddenColumns(listOf("id", "name")))
    }

    @Test
    fun `a driver error loses every quoted span before it reaches the engineer`() {
        // What sqlite-jdbc says for an unterminated literal: the engineer's own text, echoed.
        val raw = "[SQLITE_ERROR] SQL error or missing database (unrecognized token: \"'Ramesh Kumar\")"
        val described = DbQueryGuard.describeError(raw)
        assertTrue(described.startsWith("SQL error — check the statement ("), described)
        assertTrue("Ramesh" !in described, described)
        assertTrue("unrecognized token" in described, "the kind of error stays: $described")
        // Terminated literals go too; schema words stay.
        val near = DbQueryGuard.describeError("[SQLITE_ERROR] near 'Kavitha': syntax error in SELECT name FROM patients")
        assertTrue("Kavitha" !in near && "patients" in near, near)
        assertEquals("SQL error — check the statement", DbQueryGuard.describeError(null))
        assertEquals("SQL error — check the statement", DbQueryGuard.describeError("   "))
        assertTrue(DbQueryGuard.describeError("x".repeat(500)).length < 200)
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

    @Test
    fun `a runaway query is interrupted at the time limit instead of pinning a core`() = runBlocking<Unit> {
        val file = Files.createTempFile("bnmlab-ro-timeout", ".db").toFile().also { it.deleteOnExit() }
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        AppDatabase.Schema.create(driver)
        val ro = JdbcReadOnlySql(file, queryTimeoutMs = 300L)

        // count(*) over an unbounded recursion never yields its first row, so
        // maxRows cannot save it; only an interrupt can. The outer limit is
        // there so a LOST interrupt is a red test and not a CI job that hangs.
        withTimeout(30_000) {
            val t0 = System.currentTimeMillis()
            val e = assertFails { ro.query(RUNAWAY, 10) }
            val elapsed = System.currentTimeMillis() - t0
            assertTrue(elapsed < 10_000L, "stopped in ${elapsed} ms, not at the heat death of the universe")
            assertIs<SQLTimeoutException>(e)
            assertTrue("stopped after 0 s" in e.message!!, e.message!!)
        }

        // The connection is per query: the next one is unaffected.
        assertEquals(listOf(listOf("1")), ro.query("SELECT 1", 10).rows)
        driver.close()
    }

    @Test
    fun `ending the session mid-query stops it, instead of leaving sqlite spinning`() = runBlocking<Unit> {
        val file = Files.createTempFile("bnmlab-ro-cancel", ".db").toFile().also { it.deleteOnExit() }
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        AppDatabase.Schema.create(driver)
        // A minute of headroom: nothing but the cancellation may end this query.
        val ro = JdbcReadOnlySql(file, queryTimeoutMs = 60_000L)

        // The session loop awaits `tools/call` inline, so End (or expiry)
        // cancels the coroutine the query is being awaited on. Its own scope,
        // not a child of this one: a query that refuses to die must fail the
        // test, not wedge the whole run waiting for it.
        val session = CoroutineScope(Dispatchers.Default)
        val call = session.launch { runCatching { ro.query(RUNAWAY, 10) } }
        waitFor("the query to reach a JDBC thread") { ro.inFlight.get() == 1 }
        call.cancel()
        withTimeout(15_000) { call.join() }
        waitFor("the interrupted query to let go of its connection") { ro.inFlight.get() == 0 }

        // And the lab PC is usable again.
        assertEquals(listOf(listOf("1")), ro.query("SELECT 1", 10).rows)
        driver.close()
    }

    private companion object {
        /** Unbounded recursion: it ends when someone interrupts it, and not before. */
        const val RUNAWAY = "WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM c) SELECT count(*) FROM c"
    }
}
