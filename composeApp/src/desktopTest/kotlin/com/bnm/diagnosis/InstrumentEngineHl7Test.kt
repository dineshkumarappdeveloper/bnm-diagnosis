package com.bnm.diagnosis

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.bnm.diagnosis.api.ApiClient
import com.bnm.diagnosis.db.AppDatabase
import com.bnm.diagnosis.instruments.InstrumentConfig
import com.bnm.diagnosis.instruments.InstrumentEngine
import com.bnm.diagnosis.instruments.InstrumentTransport
import com.bnm.diagnosis.instruments.Mllp
import com.bnm.diagnosis.instruments.StoredInstrumentFrame
import com.bnm.diagnosis.lab.LabOrder
import com.bnm.diagnosis.lab.LabRepository
import com.bnm.diagnosis.lab.LabStatus
import com.bnm.diagnosis.lab.Patient
import com.bnm.diagnosis.lab.SeedCatalog
import com.bnm.diagnosis.report.toReportGraphs
import kotlinx.coroutines.runBlocking
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The engine glue the parser test cannot see: MLLP bytes in → ACK out → the
 * order found by accession → results in CATALOG units → graph rows, against
 * a real SQLDelight database. What a simulated BC-5130 proved by hand on the
 * installed app, pinned — plus the two failure paths the review asked for:
 * a message far bigger than the old buffer cap, and a frame that never ends.
 */
@OptIn(ExperimentalEncodingApi::class)
class InstrumentEngineHl7Test {

    private class Bench {
        val db: AppDatabase = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).let { AppDatabase.Schema.create(it); AppDatabase(it) }
        val repo = LabRepository(db, ApiClient.json)
        val engine = InstrumentEngine(db, repo, ApiClient.json)
        val cfg = InstrumentConfig(id = "bench-bc5130", name = "BC-5130", driver = "mindray_hl7", transport = InstrumentTransport.TCP)
        val acks = mutableListOf<String>()
        val reply: suspend (ByteArray) -> Unit = { acks += it.decodeToString() }

        suspend fun newOrder(): LabOrder {
            SeedCatalog.seedIfEmpty(repo)
            val patient = repo.upsertPatient(Patient(id = "pat-hl7", name = "Mindray Patient", sex = "F", ageYears = 42))
            return repo.createLabOrder(patient.id, testIds = listOf("seed-cbc")).getOrThrow()
        }

        suspend fun send(text: String, chunk: Int = Int.MAX_VALUE) = engine.ingestBytes(cfg, Mllp.wrap(text), chunk, reply)

        suspend fun value(order: LabOrder, key: String): Double? =
            repo.resultsForOrder(order.id).first { it.parameterKey == key }.value?.toDoubleOrNull()

        fun unmatched() = db.instrumentsQueries.listUnmatched().executeAsList()
        fun errors() = db.instrumentsQueries.recentLog(100).executeAsList().filter { it.direction == "error" }.map { it.summary }
    }

    // ── the BC-5130 message, with real binaries ──

    private val wbcB64 = Base64.Default.encode(ByteArray(257) { i -> if (i == 0) 1 else (i - 1).toByte() })          // 1 meta byte + 256
    private val rbcB64 = Base64.Default.encode(ByteArray(514) { i ->                                                   // 2 meta bytes + 256 × UInt16-LE (channel × 3)
        if (i < 2) 0 else { val v = ((i - 2) / 2) * 3; if ((i - 2) % 2 == 0) (v and 0xFF).toByte() else ((v shr 8) and 0xFF).toByte() }
    })
    private val pltB64 = Base64.Default.encode(ByteArray(260) { i -> if (i < 4) 0 else ((i - 4) % 200).toByte() })   // 4 meta bytes + 256

    private fun oru(
        specimen: String,
        processingId: String = "P",
        diffBmp: String = DIFF_BMP,
        wbc: Pair<String, String> = "9.55" to "10*9/L",
        rbc: Pair<String, String> = "4.51" to "10*12/L",
        hgb: Pair<String, String> = "135" to "g/L",
        plt: Pair<String, String> = "381" to "10*9/L",
        mch: Pair<String, String> = "30.0" to "pg",
    ): String = listOf(
        "MSH|^~\\&|BC-5130|Mindray|||20260908101500||ORU^R01|$CONTROL_ID|$processingId|2.3.1||||||UNICODE",
        "PID|1||E2E-001^^^^MR||Patient^Mindray||19840101000000|Female",
        "PV1|1||OPD",
        "OBR|1||$specimen|00001^Automated Count^99MRC||20260908101000|20260908101400|||Tester|||||||||||||HM||||||||",
        "OBX|1|IS|08003^Test Mode^99MRC||CBC+5DIFF||||||F",
        "OBX|2|NM|6690-2^WBC^LN||${wbc.first}|${wbc.second}|4.00-10.00|N|||F",
        "OBX|3|NM|704-7^BAS#^LN||0.10|10*9/L|0.00-0.10|N|||F",
        "OBX|4|NM|706-2^BAS%^LN||1.0|%|0.0-1.0|N|||F",
        "OBX|5|NM|751-8^NEU#^LN||5.92|10*9/L|2.00-7.00|N|||F",
        "OBX|6|NM|770-8^NEU%^LN||62.0|%|50.0-70.0|N|||F",
        "OBX|7|NM|711-2^EOS#^LN||0.29|10*9/L|0.02-0.50|N|||F",
        "OBX|8|NM|713-8^EOS%^LN||3.0|%|0.5-5.0|N|||F",
        "OBX|9|NM|731-0^LYM#^LN||2.10|10*9/L|0.80-4.00|N|||F",
        "OBX|10|NM|736-9^LYM%^LN||22.0|%|20.0-40.0|N|||F",
        "OBX|11|NM|742-7^MON#^LN||0.57|10*9/L|0.12-1.20|N|||F",
        "OBX|12|NM|5905-5^MON%^LN||6.0|%|3.0-12.0|N|||F",
        "OBX|13|NM|789-8^RBC^LN||${rbc.first}|${rbc.second}|3.50-5.50|N|||F",
        "OBX|14|NM|718-7^HGB^LN||${hgb.first}|${hgb.second}|110-150|N|||F",
        "OBX|15|NM|4544-3^HCT^LN||0.412|L/L|0.370-0.540|N|||F",
        "OBX|16|NM|787-2^MCV^LN||88.0|fL|80.0-100.0|N|||F",
        "OBX|17|NM|785-6^MCH^LN||${mch.first}|${mch.second}|27.0-34.0|N|||F",
        "OBX|18|NM|786-4^MCHC^LN||340|g/L|320-360|N|||F",
        "OBX|19|NM|788-0^RDW-CV^LN||13.2|%|11.0-16.0|N|||F",
        "OBX|20|NM|777-3^PLT^LN||${plt.first}|${plt.second}|100-300|H|||F",
        "OBX|21|NM|32623-1^MPV^LN||9.1|fL|6.5-12.0|N|||F",
        "OBX|22|NM|10002^PCT^99MRC||3.10|mL/L|1.08-2.82|H|||F",
        "OBX|23|NM|15004^WBC Histogram. Meta Length^99MRC||1||||||F",
        "OBX|24|NM|15010^WBC Lym left line.^99MRC||38||||||F",
        "OBX|25|NM|15011^WBC Lym Mid line.^99MRC||104||||||F",
        "OBX|26|NM|15012^WBC Mid Gran line.^99MRC||196||||||F",
        "OBX|27|ED|15000^WBC Histogram. Binary^99MRC||^Application^Octer-stream^Base64^$wbcB64||||||F",
        "OBX|28|NM|15053^RBC Histogram. Binary Meta Length^99MRC||2||||||F",
        "OBX|29|ED|15050^RBC Histogram. Binary^99MRC||^Application^Octer-stream^Base64^$rbcB64||||||F",
        "OBX|30|NM|15113^PLT Histogram. Binary Meta Length^99MRC||4||||||F",
        "OBX|31|ED|15100^PLT Histogram. Binary^99MRC||^Application^Octer-stream^Base64^$pltB64||||||F",
        "OBX|32|ED|15200^WBC DIFF Scattergram. BMP^99MRC||^Image^BMP^Base64^$diffBmp||||||F",
    ).joinToString("\r") + "\r"

    @Test
    fun `an ORU over MLLP is ACKed, lands on the order in catalog units, and stores the graphs`() = runBlocking {
        val b = Bench()
        val order = b.newOrder()
        b.send(oru(order.accessionNo), chunk = 1460)      // fragmented like a real TCP stack

        val ack = b.acks.single()
        assertTrue(ack.startsWith("\u000B") && ack.endsWith("\u001C\r"), "MLLP-framed ACK: $ack")
        assertTrue("MSA|AA|$CONTROL_ID" in ack, ack)
        assertTrue("|ACK^R01|" in ack && "|BC-5130|Mindray|" in ack, ack)

        assertEquals(9550.0, b.value(order, "wbc"), "10*9/L → /cumm")
        assertEquals(381000.0, b.value(order, "plt"))
        assertEquals(4.51, b.value(order, "rbc"), "10*12/L → mill/cumm")
        assertEquals(13.5, b.value(order, "hb"), "g/L → g/dL")
        assertEquals(34.0, b.value(order, "mchc"))
        assertEquals(41.2, b.value(order, "pcv"), "L/L → %")
        assertEquals(88.0, b.value(order, "mcv"))
        assertEquals(30.0, b.value(order, "mch"))
        assertEquals(22.0, b.value(order, "lymph"), "LYM% feeds the % parameter — never LYM#")
        assertEquals(62.0, b.value(order, "neut"))
        assertEquals(6.0, b.value(order, "mono"))
        assertEquals(3.0, b.value(order, "eos"))
        assertEquals(1.0, b.value(order, "baso"))
        assertEquals(LabStatus.ENTERED, b.repo.orderById(order.id)!!.status, "every CBC parameter entered → order is 'entered'")
        assertTrue(b.repo.resultsForOrder(order.id).all { it.enteredBy == "BC-5130" })

        val graphs = b.repo.graphsForOrder(order.id).associateBy { it.kind }
        assertEquals(setOf("wbc", "rbc", "plt", "diff"), graphs.keys)
        assertEquals(256, graphs.getValue("wbc").points.size)
        assertEquals(30.0, graphs.getValue("rbc").points[10], "UInt16-LE channel × 3")
        assertEquals(256, graphs.getValue("plt").points.size, "4-byte meta prefix + 256 channels")
        assertEquals(DIFF_BMP, graphs.getValue("diff").imageBase64)
        assertEquals(listOf(38.0, 104.0, 196.0), toReportGraphs(listOf(graphs.getValue("wbc"))).single().lines,
            "the discriminator lines reach the report through the stored meta")
        assertTrue(b.unmatched().isEmpty())
        assertEquals(emptyList(), b.errors())
    }

    @Test
    fun `a result far larger than the old 512 KB buffer cap still frames and is ACKed`() = runBlocking {
        val b = Bench()
        val order = b.newOrder()
        // ~540 KB decoded: the order of size of a real result with every graph on.
        val bigBmp = "Qk0" + "A".repeat(720_000)
        b.send(oru(order.accessionNo, diffBmp = bigBmp), chunk = 1460)
        assertEquals(1, b.acks.size, "one ACK for one message")
        assertTrue("MSA|AA|$CONTROL_ID" in b.acks[0])
        assertEquals(bigBmp, b.repo.graphsForOrder(order.id).single { it.kind == "diff" }.imageBase64)
        assertEquals(9550.0, b.value(order, "wbc"))
    }

    @Test
    fun `no matching order goes to the claim queue with units and images intact, and QC is ACKed and dropped`() = runBlocking {
        val b = Bench()
        val order = b.newOrder()
        b.send(oru("NOPE-123"))
        assertTrue("MSA|AA|" in b.acks.single(), "unmatched is still the analyzer's success — the sample is safe in the queue")
        val row = b.unmatched().single()
        val stored = ApiClient.json.decodeFromString(StoredInstrumentFrame.serializer(), row.payload_json)
        assertEquals("10*9/L", stored.units["WBC"])
        assertEquals(DIFF_BMP, stored.images["diff"])
        assertEquals(256, stored.histograms["wbc"]?.size)
        assertTrue(b.repo.resultsForOrder(order.id).all { it.value.isNullOrBlank() })

        // The desk claims it for the right accession: same conversions, same graphs.
        b.engine.claimUnmatched(row.id, order.accessionNo).getOrThrow()
        assertEquals(9550.0, b.value(order, "wbc"))
        assertEquals(13.5, b.value(order, "hb"))
        assertEquals("applied", b.db.instrumentsQueries.unmatchedById(row.id).executeAsOne().status)
        assertEquals(4, b.repo.graphsForOrder(order.id).size)

        // QC: acknowledged, nothing stored anywhere.
        b.acks.clear()
        val queued = b.unmatched().size
        b.send(oru(order.accessionNo, processingId = "Q", wbc = "1.00" to "10*9/L"))
        assertTrue("MSA|AA|" in b.acks.single())
        assertEquals(9550.0, b.value(order, "wbc"), "a QC run never overwrites a patient result")
        assertEquals(queued, b.unmatched().size)
    }

    @Test
    fun `vendor units convert, and a unit the app cannot bridge lands as sent and is logged as an error`() = runBlocking {
        val b = Bench()
        val order = b.newOrder()
        b.send(oru(order.accessionNo,
            wbc = "95.5" to "10*2/uL", rbc = "451" to "10*4/uL", plt = "381" to "/nL",
            mch = "0.4654" to "fmol", hgb = "13.5" to "bogus"))
        assertEquals(9550.0, b.value(order, "wbc"), "10*2/uL")
        assertEquals(4.51, b.value(order, "rbc"), "10*4/uL → mill/cumm")
        assertEquals(381000.0, b.value(order, "plt"), "/nL → /cumm")
        assertEquals(30.0, b.value(order, "mch"), "fmol → pg")
        assertEquals(13.5, b.value(order, "hb"), "unknown pair: the value still lands, unchanged")
        val err = b.errors().single()
        assertTrue("Unit not converted" in err && "HGB bogus → g/dL" in err, err)
    }

    @Test
    fun `a frame that never ends is abandoned with MSA|AE, and the next message still lands`() = runBlocking {
        val b = Bench()
        val order = b.newOrder()
        val runaway = ("\u000BMSH|^~\\&|BC-5130|Mindray|||20260908120000||ORU^R01|RUNAWAY|P|2.3.1||||||UNICODE\r" +
            "A".repeat(9 * 1024 * 1024)).encodeToByteArray()
        b.engine.ingestBytes(b.cfg, runaway, chunk = 64 * 1024, reply = b.reply)
        val nak = b.acks.single()
        assertTrue("MSA|AE|RUNAWAY" in nak, nak)
        assertTrue(b.errors().any { "no end-of-block" in it })

        b.acks.clear()
        b.send(oru(order.accessionNo))
        assertTrue("MSA|AA|$CONTROL_ID" in b.acks.single())
        assertEquals(9550.0, b.value(order, "wbc"))
    }

    private companion object {
        const val CONTROL_ID = "20260908101500001"
        const val DIFF_BMP = "Qk0="
    }
}
