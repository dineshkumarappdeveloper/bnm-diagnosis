package com.bnm.lab

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.bnm.analyzersim.Cli
import com.bnm.analyzersim.RecordingPrinter
import com.bnm.analyzersim.Sender
import com.bnm.lab.api.ApiClient
import com.bnm.lab.db.AppDatabase
import com.bnm.lab.instruments.InstrumentConfig
import com.bnm.lab.instruments.InstrumentEngine
import com.bnm.lab.instruments.InstrumentTransport
import com.bnm.lab.instruments.StoredInstrumentFrame
import com.bnm.lab.lab.LabRepository
import com.bnm.lab.lab.Patient
import com.bnm.lab.lab.SeedCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The whole path, end to end, with a real socket: the app's listener bound to
 * a real TCP port over a real database, the simulator's own TCP client dialling
 * it exactly as a BC-5130 would, and the result landing where it should.
 *
 * Everything else in this repo tests the pieces. This is the one test that
 * fails if the simulator dials the wrong way round, if the ACK never comes
 * back on the same socket, or if a frame that parses perfectly still never
 * reaches the claim queue — which are precisely the three things that go wrong
 * at a client's bench.
 */
class AnalyzerSimTcpE2ETest {

    private val bench = Bench()

    @AfterTest
    fun tearDown() = runBlocking { bench.engine.stopAll() }

    private class Bench {
        val db: AppDatabase = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            .let { AppDatabase.Schema.create(it); AppDatabase(it) }
        val repo = LabRepository(db, ApiClient.json)
        val engine = InstrumentEngine(db, repo, ApiClient.json)

        /**
         * Ask the OS for a port, give it straight back, and bind the engine to
         * it. A hard-coded port would collide with whatever else the developer
         * has running — including a real BNM Lab on 5500.
         */
        fun freePort(): Int = ServerSocket(0).use { it.localPort }

        suspend fun listenOn(port: Int): InstrumentConfig {
            val id = engine.saveInstrument(
                InstrumentConfig(
                    id = "", name = "Sim BC-5130", driver = "mindray_hl7",
                    transport = InstrumentTransport.TCP, tcpPort = port,
                )
            )
            // saveInstrument reports "listening" optimistically; the bind happens on
            // a coroutine behind it, so wait for the socket to actually exist or the
            // simulator dials a port nothing is on yet. The traffic log is the thing
            // to wait on, not status.boundAt: saveInstrument's own status write can
            // land after the accept loop's and blank the stamp back out.
            awaitTrue({ "the listener never bound to port $port: status=${engine.status.value[id]} log=" +
                log().joinToString(" | ") { "${it.direction}:${it.summary}" } }) {
                log().any { it.summary == "Listening on TCP port $port" }
            }
            return engine.instrumentById(id)!!
        }

        fun unmatched() = db.instrumentsQueries.listUnmatched().executeAsList()
        fun log() = db.instrumentsQueries.recentLog(200).executeAsList()
    }

    /** Send one sample through the simulator's real TCP client. */
    private suspend fun send(port: Int, vararg extra: String): RecordingPrinter {
        val out = RecordingPrinter(verbose = false)
        val options = Cli.parse(
            listOf("mindray", "--host", "127.0.0.1", "--port", port.toString(), "--ack-timeout", "10") + extra
        )
        // Blocking sockets: keep them off the coroutine that the assertions run on.
        withContext(Dispatchers.IO) { Sender(options, out).run() }
        return out
    }

    @Test
    fun `a result for an accession nobody registered is ACKed and lands in the claim queue`() = runBlocking {
        val port = bench.freePort()
        bench.listenOn(port)

        val out = send(port, "--id", "ACC-S1-99999", "--profile", "anaemia", "--seed", "31")

        assertTrue(out.text.contains("MSA|AA"),
            "the app must ACK on the same socket, or a real analyzer marks the sample failed:\n${out.text}")

        awaitTrue({ "nothing reached the claim queue:\n" + bench.log().joinToString("\n") { "${it.direction}: ${it.summary}" } }) {
            bench.unmatched().isNotEmpty()
        }
        val row = bench.unmatched().single()
        assertEquals("ACC-S1-99999", row.specimen_id)
        assertEquals("unmatched", row.status)

        val stored = ApiClient.json.decodeFromString(StoredInstrumentFrame.serializer(), row.payload_json)
        assertEquals("mindray_hl7", stored.driver)
        assertEquals("ACC-S1-99999", stored.specimenId)
        assertEquals(setOf("wbc", "rbc", "plt"), stored.histograms.keys, "the curves did not survive the wire")
        assertEquals(128, stored.histograms.getValue("wbc").size)
        assertNotNull(stored.params["HGB"], "no HGB in ${stored.params.keys}")
        assertEquals("g/L", stored.units["HGB"], "the analyzer's own unit must be kept for conversion later")

        assertTrue(bench.log().any { it.direction == "info" && it.summary.contains("Queued for manual claim") },
            "the traffic log should say why it was queued")
    }

    @Test
    fun `a result for a registered accession lands on the order, in the catalog's units`() = runBlocking {
        val port = bench.freePort()
        bench.listenOn(port)

        SeedCatalog.seedIfEmpty(bench.repo)
        val patient = bench.repo.upsertPatient(Patient(id = "pat-sim", name = "Sim Patient", sex = "F", ageYears = 34))
        val order = bench.repo.createLabOrder(patient.id, testIds = listOf("seed-cbc")).getOrThrow()

        val out = send(port, "--id", order.accessionNo, "--profile", "normal", "--seed", "8")

        awaitTrue({ "no result was written to ${order.accessionNo}. sim said:\n" + out.text + "\nlog: " +
            bench.log().joinToString(" | ") { "${it.direction}:${it.summary}" } }) {
            bench.repo.resultsForOrder(order.id).any { it.value != null }
        }
        assertTrue(bench.unmatched().isEmpty(), "it was queued instead of applied: ${bench.unmatched().map { it.specimen_id }}")

        // The analyzer speaks SI and the catalog does not. HGB leaves as g/L and
        // must be stored as g/dL (138 -> 13.8); WBC leaves as 10^9/L and must be
        // stored as /cumm (7.2 -> 7200). A lab that reads 138 phones a patient
        // who is perfectly well, which is why this is asserted and not eyeballed.
        val sent = com.bnm.analyzersim.MindrayFrames.parameters(
            com.bnm.analyzersim.SampleSpec(specimenId = order.accessionNo, seed = 8L))
        val results = bench.repo.resultsForOrder(order.id)
        fun stored(key: String) = results.firstOrNull { it.parameterKey == key }?.value?.toDoubleOrNull()

        val hb = assertNotNull(stored("hb"), "HGB was not entered")
        assertEquals(sent.first { it.name == "HGB" }.value.toDouble() / 10.0, hb, 0.05,
            "g/L was not converted to g/dL")
        val wbc = assertNotNull(stored("wbc"), "WBC was not entered")
        assertEquals(sent.first { it.name == "WBC" }.value.toDouble() * 1000.0, wbc, 1.0,
            "10^9/L was not converted to cells/cumm")
    }

    @Test
    fun `a truncated frame reaches the app as bytes with no frame, and nothing is filed`() = runBlocking {
        val port = bench.freePort()
        val cfg = bench.listenOn(port)

        val out = send(port, "--id", "ACC-S1-99998", "--truncated")

        awaitTrue({ "the app never counted the bytes. sim said:\n" + out.text }) {
            (bench.engine.status.value[cfg.id]?.bytesIn ?: 0L) > 0L
        }
        val status = bench.engine.status.value.getValue(cfg.id)
        assertEquals(0L, status.framesIn, "half a frame must never frame")
        assertTrue(bench.unmatched().isEmpty(), "a truncated frame filed a result")
        assertTrue(
            bench.log().any { it.summary.contains("unframed bytes") },
            "the traffic log should name the unframed bytes so an engineer can see them arrive:\n" +
                bench.log().joinToString("\n") { "${it.direction}: ${it.summary}" },
        )
    }
}

/** Poll until [condition] holds, or fail with [message]. Beats a fixed sleep on a loaded CI box. */
private suspend fun awaitTrue(message: () -> String, timeoutMs: Long = 15_000, condition: suspend () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return
        delay(50)
    }
    throw AssertionError(message())
}
