package com.bnm.lab.remote

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.bnm.lab.api.ApiClient
import com.bnm.lab.db.AppDatabase
import com.bnm.lab.instruments.InstrumentConfig
import com.bnm.lab.instruments.InstrumentEngine
import com.bnm.lab.instruments.InstrumentTransport
import com.bnm.lab.instruments.Mllp
import com.bnm.lab.instruments.SerialHandle
import com.bnm.lab.instruments.openSerialPort
import com.bnm.lab.lab.LabOrder
import com.bnm.lab.lab.LabRepository
import com.bnm.lab.lab.Patient
import com.bnm.lab.lab.SeedCatalog
import com.bnm.lab.remote.RemoteTestFixtures.freePort
import com.bnm.lab.remote.RemoteTestFixtures.mispa
import com.bnm.lab.remote.RemoteTestFixtures.oru
import com.bnm.lab.remote.RemoteTestFixtures.waitFor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

    private class Bench(
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
        openSerial: (String, Int, (ByteArray) -> Unit, (String?) -> Unit) -> SerialHandle? = ::openSerialPort,
    ) {
        val db: AppDatabase = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).let { AppDatabase.Schema.create(it); AppDatabase(it) }
        val repo = LabRepository(db, ApiClient.json)
        // A huge heal interval: the tests call healOnce() themselves.
        val engine = InstrumentEngine(db, repo, ApiClient.json, healIntervalMs = 3_600_000L, dispatcher = dispatcher,
            openSerial = openSerial)
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

            // An aborted run: the head is filled — PatientID is a typed NAME on
            // some analyzers — but the 20 params are blank, so the driver finds
            // nothing. The excerpt must not carry the name into the activity log.
            b.sendRaw(cfg, "\$\$\$2026-09-25\$17\$0\$RAMESH KUMAR\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$###")
            val aborted = b.log().first { it.summary.startsWith("Frame not understood by mispa_count_x") && "2***9-25" in it.summary }
            assertFalse("RAMESH" in aborted.summary, aborted.summary)
            assertEquals(2L, b.status(cfg).framesIgnored)
        } finally { b.close() }
    }

    @Test
    fun `two analyzers counting at once never lose an increment`() = runBlocking<Unit> {
        val b = Bench()
        try {
            val a = b.hl7("A")
            val c = b.hl7("C")
            // QC frames: acknowledged and ignored, so no order or claim-queue
            // writes get in the way of the two assemblers hammering the map.
            val frame = Mllp.wrap(oru("QC-01", processingId = "Q"))
            val ack: suspend (ByteArray) -> Unit = { }
            val n = 40
            val pump: suspend (InstrumentConfig) -> Unit = { cfg ->
                repeat(n) { b.engine.ingestBytes(cfg, frame, chunk = 7, reply = ack) }
            }
            listOf(async(Dispatchers.Default) { pump(a) }, async(Dispatchers.Default) { pump(c) }).awaitAll()
            for (cfg in listOf(a, c)) {
                val st = b.status(cfg)
                assertEquals(frame.size.toLong() * n, st.bytesIn, "${cfg.name}: every byte counted")
                assertEquals(n.toLong(), st.framesIn, "${cfg.name}: every frame counted")
                assertEquals(n.toLong(), st.framesIgnored, "${cfg.name}: every QC frame counted as ignored")
                assertEquals(n.toLong(), st.acksSent, "${cfg.name}: every ACK counted")
            }
        } finally { b.close() }
    }

    @Test
    fun `a frame draining after the port reported a fault leaves the fault in place`() = runBlocking<Unit> {
        val b = Bench()
        val port = freePort()
        val blocker = ServerSocket(port)
        try {
            val order = b.newOrder()
            // A listener in `error` (the serial "cable pulled" case has the same
            // shape: the last chunk drains after jSerialComm reported the fault).
            val id = b.engine.saveInstrument(InstrumentConfig(id = "", name = "Unplugged", driver = "mindray_hl7",
                transport = InstrumentTransport.TCP, tcpPort = port))
            waitFor("bind failure noticed") { b.engine.status.value[id]?.state == "error" }
            val cfg = b.engine.instrumentById(id)!!

            b.send(cfg, oru(order.accessionNo))
            val st = b.status(cfg)
            assertEquals("error", st.state, "ingest stamps lastFrameAt; it never flips the state")
            assertNotNull(st.lastFrameAt)
            assertEquals(1L, st.framesParsed)
            assertEquals(1L, st.framesApplied)
            assertNull(st.boundAt)
            // …so the self-heal loop still sees it and brings it back once the port is free.
            blocker.close()
            b.engine.healOnce()
            waitFor("healed") { b.engine.status.value[id]?.let { it.state == "listening" && it.boundAt != null } == true }
        } finally { runCatching { blocker.close() }; b.close() }
    }

    @Test
    fun `a launcher never writes over the verdict of the job it just started`() = runBlocking<Unit> {
        // On an immediate dispatcher the accept loop binds — or fails to — inside
        // `scope.launch`, so every launcher below runs when the real verdict is
        // already in the map. That is the ordering the bench hits by chance when
        // a sibling's port is slow to open; here it happens every run.
        val b = Bench(dispatcher = Dispatchers.Unconfined)
        val port = freePort()
        val blocker = ServerSocket(port)
        try {
            val blocked = b.engine.saveInstrument(InstrumentConfig(id = "", name = "Blocked", driver = "mindray_hl7",
                transport = InstrumentTransport.TCP, tcpPort = port))
            b.engine.status.value.getValue(blocked).let {
                assertEquals("error", it.state, "restart(id): a phantom 'listening' has no listener behind it")
                assertNull(it.boundAt)
            }

            val free = b.engine.saveInstrument(InstrumentConfig(id = "", name = "Free", driver = "mindray_hl7",
                transport = InstrumentTransport.TCP, tcpPort = freePort()))
            b.engine.restartAll()
            assertEquals("error", b.engine.status.value.getValue(blocked).state,
                "restartAll: the bind failure must outlive the whole pass")
            assertNotNull(b.engine.status.value.getValue(free).boundAt,
                "restartAll: a listener that bound keeps its bind time")

            // The port is free now: the heal pass must keep what the accept loop
            // published, not write the old error back over a listener that works.
            blocker.close()
            b.engine.healOnce()
            b.engine.status.value.getValue(blocked).let {
                assertEquals("listening", it.state, "healOnce: a working listener would be closed again next pass")
                assertNotNull(it.boundAt)
            }
        } finally { runCatching { blocker.close() }; b.close() }
    }

    @Test
    fun `a serial port that dies while it is opening keeps its fault`() = runBlocking<Unit> {
        // jSerialComm reports a disconnect from its own thread, and on a
        // re-plugged USB adapter that can land while the port is still being
        // opened — i.e. before the launcher publishes. TCP defers its work and
        // is safe; serial opens inside prepareListener, so this is the one
        // place a launcher can still write over a verdict it did not make.
        var deadOnArrival = true
        val b = Bench(openSerial = { _, _, _, onClosed ->
            if (deadOnArrival) onClosed("Serial device disconnected")
            object : SerialHandle { override fun close() = Unit }
        })
        try {
            val id = b.engine.saveInstrument(InstrumentConfig(id = "", name = "Mispa", driver = "mispa_count_x",
                transport = InstrumentTransport.SERIAL, serialPort = "ttyUSB0", baud = 115200))
            b.engine.status.value.getValue(id).let {
                assertEquals("error", it.state, "a 'listening' here has no port behind it, and healOnce only retries errors")
                assertNull(it.boundAt)
            }

            // Adapter back in: the retry the surviving fault makes possible.
            deadOnArrival = false
            b.engine.healOnce()
            b.engine.status.value.getValue(id).let {
                assertEquals("listening", it.state)
                assertNotNull(it.boundAt)
            }
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
