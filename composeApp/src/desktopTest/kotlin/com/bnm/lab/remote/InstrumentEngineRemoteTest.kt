package com.bnm.lab.remote

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.bnm.lab.api.ApiClient
import com.bnm.lab.db.AppDatabase
import com.bnm.lab.instruments.InstrumentConfig
import com.bnm.lab.instruments.InstrumentEngine
import com.bnm.lab.instruments.InstrumentTransport
import com.bnm.lab.instruments.Mllp
import com.bnm.lab.lab.LabOrder
import com.bnm.lab.lab.LabRepository
import com.bnm.lab.lab.Patient
import com.bnm.lab.lab.SeedCatalog
import com.bnm.lab.remote.RemoteTestFixtures.freePort
import com.bnm.lab.remote.RemoteTestFixtures.mispa
import com.bnm.lab.remote.RemoteTestFixtures.oru
import com.bnm.lab.remote.RemoteTestFixtures.waitFor
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The engine hardening remote support relies on: counters that say whether
 * anything is arriving, a per-instrument restart, the self-heal retry, error
 * rows for frames the driver could not read, a row per ACK, the
 * verify_pending gate, and a dry run that writes nothing. Real listeners on
 * real (free) TCP ports; frames go in through the same assembler the sockets
 * feed.
 */
class InstrumentEngineRemoteTest {

    private class Bench {
        val db: AppDatabase = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).let { AppDatabase.Schema.create(it); AppDatabase(it) }
        val repo = LabRepository(db, ApiClient.json)
        // A huge heal interval: the tests call healOnce() themselves.
        val engine = InstrumentEngine(db, repo, ApiClient.json, healIntervalMs = 3_600_000L)
        val acks = mutableListOf<String>()
        val reply: suspend (ByteArray) -> Unit = { acks += it.decodeToString() }

        suspend fun hl7(name: String = "BC-5130", port: Int = freePort()): InstrumentConfig {
            val id = engine.saveInstrument(InstrumentConfig(id = "", name = name, driver = "mindray_hl7",
                transport = InstrumentTransport.TCP, tcpPort = port))
            waitFor("$name bound") { engine.status.value[id]?.boundAt != null }
            return engine.instrumentById(id)!!
        }

        suspend fun mispa(port: Int = freePort()): InstrumentConfig {
            val id = engine.saveInstrument(InstrumentConfig(id = "", name = "Mispa", driver = "mispa_count_x",
                transport = InstrumentTransport.TCP, tcpPort = port))
            waitFor("Mispa bound") { engine.status.value[id]?.boundAt != null }
            return engine.instrumentById(id)!!
        }

        suspend fun newOrder(): LabOrder {
            SeedCatalog.seedIfEmpty(repo)
            val patient = repo.upsertPatient(Patient(id = "pat-1", name = "Bench Patient", sex = "F", ageYears = 42))
            return repo.createLabOrder(patient.id, testIds = listOf("seed-cbc")).getOrThrow()
        }

        suspend fun send(cfg: InstrumentConfig, text: String) = engine.ingestBytes(cfg, Mllp.wrap(text), 1460, reply)
        suspend fun sendRaw(cfg: InstrumentConfig, text: String) = engine.ingestBytes(cfg, text.encodeToByteArray(), 64, null)

        fun status(cfg: InstrumentConfig) = engine.status.value.getValue(cfg.id)
        fun log() = db.instrumentsQueries.recentLog(500).executeAsList()
        fun unmatched() = db.instrumentsQueries.listUnmatched().executeAsList()
        suspend fun value(order: LabOrder, key: String) =
            repo.resultsForOrder(order.id).first { it.parameterKey == key }.value?.toDoubleOrNull()

        fun close() = runBlocking<Unit> { engine.stopAll() }
    }

    @Test
    fun `counters move as bytes, frames, results, ACKs and unmatched frames go through`() = runBlocking<Unit> {
        val b = Bench()
        try {
            val order = b.newOrder()
            val cfg = b.hl7()
            val before = b.status(cfg)
            assertEquals(0L, before.bytesIn); assertEquals(0L, before.framesIn)

            val msg = oru(order.accessionNo)
            b.send(cfg, msg)
            val s1 = b.status(cfg)
            assertEquals(Mllp.wrap(msg).size.toLong(), s1.bytesIn, "every byte counted")
            assertEquals(1L, s1.framesIn); assertEquals(1L, s1.framesParsed); assertEquals(1L, s1.framesApplied)
            assertEquals(1L, s1.acksSent); assertEquals(0L, s1.framesUnmatched); assertEquals(0L, s1.framesIgnored)
            assertNotNull(s1.lastFrameAt)
            assertEquals(9550.0, b.value(order, "wbc"))

            // QC: acknowledged, ignored, counted as such.
            b.send(cfg, oru(order.accessionNo, processingId = "Q"))
            val s2 = b.status(cfg)
            assertEquals(2L, s2.framesIn); assertEquals(1L, s2.framesParsed); assertEquals(1L, s2.framesIgnored); assertEquals(2L, s2.acksSent)

            // Unknown accession: queued and counted.
            b.send(cfg, oru("NOPE-999"))
            val s3 = b.status(cfg)
            assertEquals(2L, s3.framesParsed); assertEquals(1L, s3.framesUnmatched); assertEquals(1L, s3.framesApplied)
            assertEquals(1, b.unmatched().size)
        } finally { b.close() }
    }

    @Test
    fun `every ACK and NAK sent gets a log row with no raw`() = runBlocking<Unit> {
        val b = Bench()
        try {
            val order = b.newOrder()
            val cfg = b.hl7()
            b.send(cfg, oru(order.accessionNo))
            val ackRows = b.log().filter { it.summary.startsWith("ACK sent") }
            assertEquals(1, ackRows.size, b.log().map { it.summary }.toString())
            assertEquals("info", ackRows[0].direction)
            assertNull(ackRows[0].raw)
            assertTrue("20260908101500001" in ackRows[0].summary, "names the control id it acknowledged")

            // A runaway frame is abandoned with a NAK — also a row, also counted.
            val runaway = (Mllp.SB + "MSH|^~\\&|BC-5130|Mindray|||20260908120000||ORU^R01|RUNAWAY|P|2.3.1||||||UNICODE\r" +
                "A".repeat(9 * 1024 * 1024)).encodeToByteArray()
            b.engine.ingestBytes(cfg, runaway, chunk = 64 * 1024, reply = b.reply)
            assertTrue(b.log().any { it.summary.startsWith("NAK sent") })
            assertEquals(2L, b.status(cfg).acksSent)
        } finally { b.close() }
    }

    @Test
    fun `a framed Mispa message the driver cannot read is an error row with a masked excerpt, and is counted`() = runBlocking<Unit> {
        val b = Bench()
        try {
            val cfg = b.mispa()
            b.sendRaw(cfg, "\$\$\$ABC-12345\$###")
            val row = b.log().first { it.summary.startsWith("Frame not understood by mispa_count_x") }
            assertEquals("error", row.direction)
            assertNull(row.raw)
            assertTrue("A***2345" in row.summary && "ABC-12345" !in row.summary, row.summary)
            val st = b.status(cfg)
            assertEquals(1L, st.framesIn); assertEquals(0L, st.framesParsed); assertEquals(1L, st.framesIgnored)
            assertNull(st.lastFrameAt, "lastFrameAt moves only on a parsed result")

            // A readable one still parses and counts.
            b.sendRaw(cfg, mispa("SPEC-1"))
            assertEquals(1L, b.status(cfg).framesParsed)
        } finally { b.close() }
    }

    @Test
    fun `restart(id) rebinds only that listener and keeps its counters`() = runBlocking<Unit> {
        val b = Bench()
        try {
            val order = b.newOrder()
            val a = b.hl7("A")
            val c = b.hl7("C")
            b.send(a, oru(order.accessionNo))
            val aBefore = b.status(a)
            val cBefore = b.status(c)
            delay(20)

            b.engine.restart(a.id)
            waitFor("A rebound") { b.status(a).boundAt != null && b.status(a).boundAt != aBefore.boundAt }
            assertEquals(cBefore, b.status(c), "C's status — bind time included — is untouched")
            assertEquals(aBefore.framesApplied, b.status(a).framesApplied, "counters survive a restart")
            assertEquals(aBefore.lastFrameAt, b.status(a).lastFrameAt)
            // Both ports still answer.
            Socket("127.0.0.1", a.tcpPort!!).close()
            Socket("127.0.0.1", c.tcpPort!!).close()

            // A row that no longer exists disappears from the map.
            b.engine.deleteInstrument(a.id)
            assertFalse(a.id in b.engine.status.value)
            assertTrue(c.id in b.engine.status.value)
        } finally { b.close() }
    }

    @Test
    fun `self-heal reopens a listener whose bind failed, and logs the failure once`() = runBlocking<Unit> {
        val b = Bench()
        val port = freePort()
        val blocker = ServerSocket(port)
        try {
            val id = b.engine.saveInstrument(InstrumentConfig(id = "", name = "Blocked", driver = "mindray_hl7",
                transport = InstrumentTransport.TCP, tcpPort = port))
            waitFor("bind failure noticed") { b.engine.status.value[id]?.state == "error" }
            val first = b.engine.status.value.getValue(id)
            assertNotNull(first.lastError); assertNotNull(first.lastErrorAt)

            // Still blocked: the retry fails again, quietly.
            b.engine.healOnce()
            waitFor("second failure") { b.engine.status.value[id]?.state == "error" }
            delay(150)
            assertEquals(1, b.log().count { it.summary.startsWith("TCP listen failed") }, "one row per distinct failure, not per attempt")

            // Port freed (cable back in): the next pass brings it up.
            blocker.close()
            b.engine.healOnce()
            waitFor("healed") { b.engine.status.value[id]?.let { it.state == "listening" && it.boundAt != null } == true }
            Socket("127.0.0.1", port).close()
            assertEquals(first.lastError, b.engine.status.value.getValue(id).lastError, "the last error is kept for the engineer")
        } finally { runCatching { blocker.close() }; b.close() }
    }

    @Test
    fun `verify_pending sends frames to the claim queue until the bench clears it`() = runBlocking<Unit> {
        val b = Bench()
        try {
            val order = b.newOrder()
            val cfg = b.hl7()
            b.engine.setVerifyPending(cfg.id, true)
            assertTrue(b.engine.instrumentById(cfg.id)!!.verifyPending)

            b.send(cfg, oru(order.accessionNo))
            assertEquals(1, b.unmatched().size)
            assertTrue(b.repo.resultsForOrder(order.id).all { it.value.isNullOrBlank() }, "nothing applied")
            assertTrue(b.log().any { InstrumentEngine.VERIFY_PENDING_REASON in it.summary })
            assertEquals(1L, b.status(cfg).framesUnmatched)

            b.engine.setVerifyPending(cfg.id, false)
            b.send(cfg, oru(order.accessionNo))
            assertEquals(9550.0, b.value(order, "wbc"), "applies again without a listener restart")
            assertEquals(1, b.unmatched().size)
        } finally { b.close() }
    }

    @Test
    fun `dry run parses and maps but never writes, and BNMTEST- never matches`() = runBlocking<Unit> {
        val b = Bench()
        try {
            val order = b.newOrder()
            val cfg = b.hl7()
            val logBefore = b.log().size

            val r = b.engine.dryRun(cfg, Mllp.wrap(oru(order.accessionNo)).decodeToString())
            assertTrue(r.parsed)
            assertTrue(r.wouldMatch)
            assertEquals("ordered_tests", r.mappingBasis)
            assertEquals(FrameScrubber.maskId(order.accessionNo), r.accessionMasked)
            assertEquals("wbc", r.mapped["WBC"]); assertEquals("hb", r.mapped["HGB"])
            assertTrue("WBC" in r.paramKeys && r.units["WBC"] == "10*9/L")
            assertTrue(r.unmapped.isEmpty(), r.unmapped.toString())

            val t = b.engine.dryRun(cfg, oru("BNMTEST-001"))
            assertTrue(t.parsed); assertFalse(t.wouldMatch)
            assertTrue(t.note!!.contains("BNMTEST-"))
            assertEquals("catalog", t.mappingBasis, "no order: mapping is against the catalog")

            val junk = b.engine.dryRun(cfg, "hello")
            assertFalse(junk.parsed); assertNotNull(junk.note)

            // Mispa text without framing is accepted too; the mapping comes from the catalog.
            val m = b.engine.dryRun(cfg.copy(driver = "mispa_count_x"), mispa("BNMTEST-2").removePrefix("$$$").removeSuffix("###"))
            assertTrue(m.parsed); assertEquals("plt", m.mapped["PLT"])

            assertTrue(b.repo.resultsForOrder(order.id).all { it.value.isNullOrBlank() }, "no result written")
            assertEquals(0, b.unmatched().size, "nothing queued")
            assertEquals(logBefore, b.log().size, "no log row")
            assertEquals(0L, b.status(cfg).framesIn, "no counter moved")
        } finally { b.close() }
    }

    @Test
    fun `saving one analyzer does not bounce the others`() = runBlocking<Unit> {
        val b = Bench()
        try {
            val a = b.hl7("A")
            val c = b.hl7("C")
            val cBound = b.status(c).boundAt
            delay(20)
            b.engine.saveInstrument(a.copy(name = "A renamed"))
            waitFor("A restarted") { b.status(a).boundAt != null }
            assertEquals(cBound, b.status(c).boundAt)
            assertNotEquals(a.name, b.engine.instrumentById(a.id)!!.name)
        } finally { b.close() }
    }
}
