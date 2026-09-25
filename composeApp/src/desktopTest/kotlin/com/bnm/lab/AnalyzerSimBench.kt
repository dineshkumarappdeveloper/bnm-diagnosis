package com.bnm.lab

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.bnm.lab.api.ApiClient
import com.bnm.lab.db.AppDatabase
import com.bnm.lab.instruments.InstrumentConfig
import com.bnm.lab.instruments.InstrumentEngine
import com.bnm.lab.instruments.InstrumentTransport
import com.bnm.lab.lab.LabRepository
import com.bnm.lab.lab.Patient
import com.bnm.lab.lab.SeedCatalog
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test

/**
 * A live listener you can point the SHIPPED analyzer-sim jar at.
 *
 * [AnalyzerSimTcpE2ETest] proves the path in one JVM. This proves the other
 * half — that the packaged jar, its start scripts and its bundled native
 * libraries actually work as a separate process against a real engine, which
 * is what a field engineer will be holding. It is also the rehearsal the
 * simulator's README describes, run for real.
 *
 * Opt-in, and a no-op otherwise: nothing here runs in a normal test pass.
 *
 * ```
 * BNM_SIM_BENCH=15700 BNM_SIM_BENCH_SECONDS=90 \
 *   BNM_SIM_BENCH_OUT=/tmp/bench.txt \
 *   ./gradlew --no-daemon :composeApp:desktopTest --tests 'com.bnm.lab.AnalyzerSimBench'
 * # then, in another shell:
 * java -jar analyzer-sim/build/dist/analyzer-sim.jar mindray --port 15700 --id <accession from /tmp/bench.txt>
 * ```
 *
 * (`--no-daemon` because test workers inherit the Gradle daemon's environment,
 * and a daemon started earlier would not have these variables.)
 */
class AnalyzerSimBench {

    @Test
    fun `hold a listener open for a real analyzer-sim process`() = runBlocking {
        val port = System.getenv("BNM_SIM_BENCH")?.toIntOrNull() ?: return@runBlocking
        val seconds = System.getenv("BNM_SIM_BENCH_SECONDS")?.toLongOrNull() ?: 60L
        val handshakeFile = System.getenv("BNM_SIM_BENCH_OUT")

        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        val db = AppDatabase(driver)
        val repo = LabRepository(db, ApiClient.json)
        val engine = InstrumentEngine(db, repo, ApiClient.json)

        SeedCatalog.seedIfEmpty(repo)
        val patient = repo.upsertPatient(Patient(id = "bench-pat", name = "Bench Patient", sex = "F", ageYears = 34))
        val order = repo.createLabOrder(patient.id, testIds = listOf("seed-cbc")).getOrThrow()

        // Mispa is a serial analyzer in the field, but the driver is transport
        // agnostic and TCP is how it gets rehearsed without a USB adapter.
        val driverKey = System.getenv("BNM_SIM_BENCH_DRIVER") ?: "mindray_hl7"
        val id = engine.saveInstrument(
            InstrumentConfig(id = "", name = "Bench analyzer", driver = driverKey,
                transport = InstrumentTransport.TCP, tcpPort = port)
        )
        println("[bench] $driverKey listening on TCP $port · registered accession ${order.accessionNo}")
        handshakeFile?.let { File(it).writeText("port=$port\naccession=${order.accessionNo}\n") }

        // Report as things happen, so the transcript shows the app's side of the
        // conversation next to the simulator's.
        var seenLog = 0
        val deadline = System.currentTimeMillis() + seconds * 1000
        while (System.currentTimeMillis() < deadline) {
            delay(250)
            val rows = db.instrumentsQueries.recentLog(200).executeAsList().reversed()
            if (rows.size > seenLog) {
                rows.drop(seenLog).forEach { println("[app] ${it.direction}: ${it.summary}") }
                seenLog = rows.size
            }
        }

        val status = engine.status.value[id]
        println("[bench] counters: bytes=${status?.bytesIn} frames=${status?.framesIn} " +
            "parsed=${status?.framesParsed} applied=${status?.framesApplied} " +
            "unmatched=${status?.framesUnmatched} ignored=${status?.framesIgnored} acks=${status?.acksSent}")
        db.instrumentsQueries.listUnmatched().executeAsList().forEach {
            println("[bench] claim queue: ${it.specimen_id} (${it.status})")
        }
        repo.resultsForOrder(order.id).filter { it.value != null }.forEach {
            println("[bench] ${order.accessionNo} ${it.parameterKey} = ${it.value}")
        }
        engine.stopAll()
    }
}
