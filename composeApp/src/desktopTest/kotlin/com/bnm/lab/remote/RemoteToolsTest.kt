package com.bnm.lab.remote

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.bnm.lab.api.ApiClient
import com.bnm.lab.db.AppDatabase
import com.bnm.lab.db.addColumn
import com.bnm.lab.instruments.InstrumentConfig
import com.bnm.lab.instruments.InstrumentEngine
import com.bnm.lab.instruments.InstrumentTransport
import com.bnm.lab.instruments.Mllp
import com.bnm.lab.lab.LabOrder
import com.bnm.lab.lab.LabRepository
import com.bnm.lab.lab.Patient
import com.bnm.lab.lab.SeedCatalog
import com.bnm.lab.license.LicenseManager
import com.bnm.lab.remote.RemoteTestFixtures.freePort
import com.bnm.lab.remote.RemoteTestFixtures.oru
import com.bnm.lab.remote.RemoteTestFixtures.waitFor
import com.bnm.lab.update.ReleaseInfo
import com.bnm.lab.update.UpdateCheck
import com.bnm.lab.update.UpdateInstall
import com.russhwolf.settings.PropertiesSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.ServerSocket
import java.nio.file.Files
import java.util.Base64
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every tool in the v1 vocabulary (all but `app.screenshot`, which needs the
 * window) driven through [RemoteToolHost.call] against a real SQLite file
 * with the real schema — once with nothing consented, once with everything —
 * plus the PHI rules that hold either way and the audit summaries that must
 * never carry more than counts and names.
 */
class RemoteToolsTest {

    // ── fakes ──

    private class FakePlatform : RemoteToolPlatform {
        var logLines: List<String> = (1..300).map { "2026-09-25T10:00:$it I/Sync: sweep $it" }
        override fun environmentLine() = "TestOS 1.0 (x64) · Java 21 · heap max 512 MB"
        override fun localIpv4() = listOf("192.168.1.20")
        override fun diskFreeBytes() = 123_456_789L
        override fun dbSizeBytes() = 4_096L
        override fun uptimeSeconds() = 77L
        override fun timezoneId() = "Asia/Kolkata"
        override fun localTimeIso() = "2026-09-25T15:30:00+05:30"
        override fun logTail(lines: Int, day: String?) = if (day == "1999-01-01") null else logLines.takeLast(lines).joinToString("\n")
        var ports = listOf(SerialPortInfo("COM3", "USB Serial Port"), SerialPortInfo("COM9", "Spare"))
        override fun serialPorts() = ports
        override fun probeSerial(portName: String): String? = if (portName == "COM9") null else "Could not open $portName — in use"
    }

    private class FakeUpdates(var check: UpdateCheck) : UpdateFlow {
        var downloaded: String? = null
        override suspend fun check() = check
        override suspend fun checksum(release: ReleaseInfo, assetName: String) = "ab".repeat(32)
        override suspend fun download(url: String, fileName: String, sha256: String?): UpdateInstall {
            downloaded = fileName
            return UpdateInstall.LaunchedQuitNow("/tmp/$fileName")
        }
    }

    private class FakeController : RemoteSupportController {
        override val status: StateFlow<RemoteSupportStatus> = MutableStateFlow(
            RemoteSupportStatus(phase = RemoteSupportStatus.Phase.ENGINEER_CONNECTED, remainingS = 1234,
                peerConnected = true, actions = 3, clockSkewMs = 12),
        )
        var ended: String? = null
        override suspend fun start(consent: SupportConsent, durationS: Long, startedBy: SupportStarter): Result<SupportSession> =
            Result.failure(UnsupportedOperationException())
        override suspend fun end(reason: String) { ended = reason }
        override suspend fun history(limit: Int): List<SupportAuditRow> = emptyList()
        override fun attachToolHost(host: RemoteToolHost) = Unit
        override fun attachAuditStore(store: SupportAuditStore) = Unit
    }

    private class Bench {
        val file = Files.createTempFile("bnmlab-tools", ".db").toFile().also { it.deleteOnExit() }
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        val db: AppDatabase = AppDatabase(driver).also { AppDatabase.Schema.create(driver) }
        val repo = LabRepository(db, ApiClient.json)
        val engine = InstrumentEngine(db, repo, ApiClient.json, healIntervalMs = 3_600_000L)
        val licence = LicenseManager(PropertiesSettings(Properties()), { true }, { 1_800_000_000L }).apply {
            saveActivation(fakeJwt(), "device-token", "row-1", "Bench Lab", "perpetual", 1, null, null)
        }
        val platform = FakePlatform()
        val updates = FakeUpdates(UpdateCheck.UpToDate("1.2.0"))
        val controller = FakeController()
        val host: RemoteToolHost = remoteToolHost(
            db, engine, repo, licence, controller = controller, platform = platform,
            readOnlySql = JdbcReadOnlySql(file), updates = updates, supportKeyLabel = { "dev" },
        )

        fun session(consent: SupportConsent) = SupportSession(
            id = "sess-1", code = "ABCDEFGH", startedAtMs = 1_800_000_000_000L, durationS = 3600,
            consent = consent, startedBy = SupportStarter("owner-1", "Owner"),
        )

        suspend fun call(name: String, args: String = "{}", consent: SupportConsent = SupportConsent()): ToolResult =
            host.call(ToolCall(name, args, session(consent), "req-1"))

        suspend fun hl7(port: Int = freePort()): InstrumentConfig {
            val id = engine.saveInstrument(InstrumentConfig(id = "", name = "BC-5130", driver = "mindray_hl7",
                transport = InstrumentTransport.TCP, tcpPort = port))
            waitFor("bound") { engine.status.value[id]?.boundAt != null }
            return engine.instrumentById(id)!!
        }

        suspend fun newOrder(): LabOrder {
            SeedCatalog.seedIfEmpty(repo)
            val p = repo.upsertPatient(Patient(id = "pat-1", name = "Kavitha Raman", sex = "F", ageYears = 42))
            return repo.createLabOrder(p.id, testIds = listOf("seed-cbc")).getOrThrow()
        }

        suspend fun ingest(cfg: InstrumentConfig, text: String) = engine.ingestBytes(cfg, Mllp.wrap(text), 1460) { }

        fun close() = runBlocking<Unit> { engine.stopAll(); driver.close() }

        companion object {
            /** Unsigned ES256-shaped token; the bench's verifier says yes to everything. */
            fun fakeJwt(): String {
                val b64 = Base64.getUrlEncoder().withoutPadding()
                val header = b64.encodeToString("""{"alg":"ES256","typ":"JWT"}""".toByteArray())
                val payload = b64.encodeToString("""{"iss":"bnm-lab-license","lid":"L1","lab":"Bench Lab","mode":"perpetual","ed":"standalone","seats":1}""".toByteArray())
                return "$header.$payload.c2ln"
            }
        }
    }

    private val ALL = SupportConsent(analyzerData = true, records = true, screen = true)
    private val NONE = SupportConsent()

    private fun payload(r: ToolResult): JsonElement {
        assertIs<ToolResult.Ok>(r, "expected Ok, got $r")
        val content = Json.parseToJsonElement(r.contentJson).jsonArray
        assertEquals("text", content[0].jsonObject.getValue("type").jsonPrimitive.content)
        return Json.parseToJsonElement(content[0].jsonObject.getValue("text").jsonPrimitive.content)
    }
    private fun JsonElement.str(key: String): String? = (jsonObject[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    private fun JsonElement.bool(key: String): Boolean? = (jsonObject[key] as? JsonPrimitive)?.booleanOrNull

    // ── the vocabulary ──

    @Test
    fun `the vocabulary is the contract's, every schema is a JSON object, annotations are honest`() = runBlocking<Unit> {
        val b = Bench()
        try {
            val specs = b.host.tools()
            assertEquals(
                listOf("lab.overview", "instruments.list", "instruments.log", "instruments.unmatched", "instruments.ports",
                    "instruments.probe", "instruments.dry_run", "catalog.tests", "db.query", "logs.tail", "session.info",
                    "instruments.restart", "instruments.set_config", "instruments.delete", "app.update.prefetch", "session.end"),
                specs.map { it.name },
            )
            assertEquals(specs.size, specs.map { it.name }.toSet().size, "names must not clash")
            for (s in specs) {
                val schema = Json.parseToJsonElement(s.inputSchemaJson).jsonObject
                assertEquals("object", schema.getValue("type").jsonPrimitive.content, s.name)
                assertTrue(s.description.length > 30, "${s.name} needs a description the engineer can act on")
                assertTrue(s.readOnly != s.destructive, "${s.name}: read-only XOR destructive")
            }
            assertTrue(specs.first { it.name == "instruments.restart" }.destructive)
            assertTrue(specs.first { it.name == "db.query" }.readOnly)
            assertFalse(specs.any { it.name == "app.screenshot" }, "the screenshot tool belongs to the session engine")
        } finally { b.close() }
    }

    @Test
    fun `every tool answers with a ToolResult, with and without consent, never a throw`() = runBlocking<Unit> {
        val b = Bench()
        try {
            val cfg = b.hl7()
            val args = mapOf(
                "instruments.probe" to """{"serial_port":"COM9"}""",
                "instruments.dry_run" to """{"instrument_id":"${cfg.id}","frame_text":"x"}""",
                "db.query" to """{"sql":"SELECT 1 AS one"}""",
                "instruments.restart" to """{"instrument_id":"${cfg.id}"}""",
                "instruments.set_config" to """{"id":"${cfg.id}","name":"BC-5130","driver_key":"mindray_hl7","transport":"tcp","tcp_port":${cfg.tcpPort}}""",
                "instruments.delete" to """{"id":"does-not-exist"}""",
            )
            for (consent in listOf(NONE, ALL)) for (spec in b.host.tools()) {
                val r = b.call(spec.name, args[spec.name] ?: "{}", consent)
                assertTrue(r is ToolResult.Ok || r is ToolResult.Refused || r is ToolResult.Failed, "${spec.name}: $r")
                if (r is ToolResult.Ok) assertTrue(r.summaryForAudit.length in 1..200, "${spec.name} audit summary: '${r.summaryForAudit}'")
            }
            // Bad arguments are a Failed, not a crash; an unknown tool likewise.
            assertIs<ToolResult.Failed>(b.call("db.query", "not json"))
            assertIs<ToolResult.Failed>(b.call("no.such.tool"))
            assertIs<ToolResult.Failed>(b.call("instruments.dry_run", """{"frame_text":"x"}"""))
        } finally { b.close() }
    }

    // ── read tools ──

    @Test
    fun `lab overview - lab, licence, machine, counts, network, clock, and what is not in this build`() = runBlocking<Unit> {
        val b = Bench()
        try {
            b.newOrder()
            val o = payload(b.call("lab.overview"))
            assertEquals("Bench Lab", o.str("lab"))
            assertEquals("standalone", o.str("edition"))
            assertEquals("current", o.jsonObject.getValue("licence").str("standing"))
            assertEquals("perpetual", o.jsonObject.getValue("licence").str("mode"))
            assertEquals("dev", o.str("support_key"))
            assertTrue(o.str("environment")!!.startsWith("TestOS"))
            assertEquals("77", o.str("uptime_s"))
            assertEquals("1", o.jsonObject.getValue("records").str("patients"))
            assertEquals("1", o.jsonObject.getValue("records").str("orders"))
            assertEquals("192.168.1.20", o.jsonObject.getValue("local_ipv4").jsonArray[0].jsonPrimitive.content)
            assertEquals("Asia/Kolkata", o.str("timezone"))
            assertEquals("12", o.str("clock_skew_ms"))
            assertEquals("not available in this build", o.str("backup"))
            assertFalse("Kavitha" in o.toString(), "never a name")
        } finally { b.close() }
    }

    @Test
    fun `instruments list - config, verify flag and live counters`() = runBlocking<Unit> {
        val b = Bench()
        try {
            val order = b.newOrder()
            val cfg = b.hl7()
            b.ingest(cfg, oru(order.accessionNo))
            val row = payload(b.call("instruments.list")).jsonArray.single().jsonObject
            assertEquals(cfg.id, row.str("id"))
            assertEquals("mindray_hl7", row.str("driver_key"))
            assertEquals(false, row.bool("verify_pending"))
            val st = row.getValue("status").jsonObject
            assertEquals("listening", st.str("state"))
            assertEquals("1", st.str("frames_applied"))
            assertEquals("1", st.str("acks_sent"))
            assertNotNull(st.str("bound_at"))
        } finally { b.close() }
    }

    @Test
    fun `instruments log - ids masked without consent, raw only with consent and include_raw, and scrubbed`() = runBlocking<Unit> {
        val b = Bench()
        try {
            val order = b.newOrder()
            val cfg = b.hl7()
            b.ingest(cfg, oru(order.accessionNo))

            val plain = payload(b.call("instruments.log", """{"limit":50}""", NONE))
            assertEquals(true, plain.bool("ids_masked"))
            assertEquals(false, plain.bool("raw_included"))
            val summaries = plain.jsonObject.getValue("rows").jsonArray.map { it.str("summary")!! }
            assertTrue(summaries.any { "specimen A***" in it }, summaries.toString())
            assertFalse(summaries.any { order.accessionNo in it }, "the accession must be masked: $summaries")
            assertTrue(plain.jsonObject.getValue("rows").jsonArray.none { "raw" in it.jsonObject }, "no raw without consent")

            // include_raw without consent: still nothing.
            val asked = payload(b.call("instruments.log", """{"include_raw":true}""", NONE))
            assertEquals(false, asked.bool("raw_included"))

            // With consent + include_raw: raw arrives, scrubbed of the name.
            val shared = payload(b.call("instruments.log", """{"include_raw":true,"instrument_id":"${cfg.id}"}""", ALL))
            assertEquals(true, shared.bool("raw_included"))
            assertEquals(false, shared.bool("ids_masked"))
            val raws = shared.jsonObject.getValue("rows").jsonArray.mapNotNull { it.str("raw") }
            assertTrue(raws.isNotEmpty())
            assertTrue(raws.any { order.accessionNo in it && "6690-2^WBC^LN" in it }, "the frame itself is there")
            assertFalse(raws.any { "Patient^Mindray" in it || "Gandhi" in it }, "…without the patient's name or address")

            // since_id: only rows newer than the one named.
            val rows = shared.jsonObject.getValue("rows").jsonArray
            val oldest = rows.last().str("id")!!
            val newer = payload(b.call("instruments.log", """{"since_id":"$oldest"}""", ALL)).jsonObject.getValue("rows").jsonArray
            assertEquals(rows.size - 1, newer.size)
        } finally { b.close() }
    }

    @Test
    fun `instruments unmatched - masked specimen, the reason, the keys, never a value`() = runBlocking<Unit> {
        val b = Bench()
        try {
            b.newOrder()
            val cfg = b.hl7()
            b.ingest(cfg, oru("SPEC-99887"))
            val masked = payload(b.call("instruments.unmatched", consent = NONE)).jsonArray.single()
            assertEquals("S***9887", masked.str("specimen_id"))
            assertTrue("no order matches" in masked.str("reason")!!, masked.str("reason")!!)
            assertFalse("SPEC-99887" in masked.str("reason")!!)
            assertTrue(masked.jsonObject.getValue("param_keys").jsonArray.any { it.jsonPrimitive.content == "WBC" })
            assertFalse("9.55" in masked.toString(), "values never leave")

            val open = payload(b.call("instruments.unmatched", consent = ALL)).jsonArray.single()
            assertEquals("SPEC-99887", open.str("specimen_id"))
        } finally { b.close() }
    }

    @Test
    fun `instruments ports and probe - held ports are named and refused, free ones open, tcp is tried from the PC`() = runBlocking<Unit> {
        val b = Bench()
        try {
            val serialId = b.engine.saveInstrument(InstrumentConfig(id = "", name = "Mispa", driver = "mispa_count_x",
                transport = InstrumentTransport.SERIAL, serialPort = "COM3", baud = 115200))
            val cfg = b.hl7()
            val ports = payload(b.call("instruments.ports"))
            val serial = ports.jsonObject.getValue("serial").jsonArray.associateBy { it.str("name")!! }
            assertEquals(serialId, serial.getValue("COM3").str("held_by"))
            assertNull(serial.getValue("COM9").str("held_by"))
            val tcp = ports.jsonObject.getValue("tcp").jsonArray.single()
            assertEquals(cfg.tcpPort.toString(), tcp.str("port"))
            assertEquals(true, tcp.bool("bound"))

            val refused = b.call("instruments.probe", """{"serial_port":"COM3"}""")
            assertIs<ToolResult.Refused>(refused)
            assertTrue("Mispa" in refused.reason, refused.reason)
            assertEquals(true, payload(b.call("instruments.probe", """{"serial_port":"COM9"}""")).bool("opened"))

            ServerSocket(0).use { srv ->
                val up = payload(b.call("instruments.probe", """{"tcp_host":"127.0.0.1","tcp_port":${srv.localPort}}"""))
                assertEquals(true, up.bool("reachable"))
            }
            val closedPort = freePort()
            val down = payload(b.call("instruments.probe", """{"tcp_host":"127.0.0.1","tcp_port":$closedPort}"""))
            assertEquals(false, down.bool("reachable"))
            assertIs<ToolResult.Failed>(b.call("instruments.probe", "{}"))
        } finally { b.close() }
    }

    @Test
    fun `a port name is reduced to what the OS means by it`() {
        assertEquals("com3", SerialPortNames.normalize(" \\\\.\\COM3 "))
        assertEquals("ttyusb0", SerialPortNames.normalize("/dev/ttyUSB0"))
        assertNull(SerialPortNames.normalize("  "))
        assertNull(SerialPortNames.normalize(null))
        assertTrue(SerialPortNames.same("/dev/ttyUSB0", "ttyUSB0"))
        assertTrue(SerialPortNames.same("com3", "COM3"))
        assertFalse(SerialPortNames.same("ttyUSB0", "ttyUSB1"))
        assertFalse(SerialPortNames.same(null, null), "a missing port owns nothing")
    }

    @Test
    fun `the port guard reads a spelling the way the OS does, not character by character`() = runBlocking<Unit> {
        val b = Bench()
        try {
            val mispa = b.engine.saveInstrument(InstrumentConfig(id = "", name = "Mispa", driver = "mispa_count_x",
                transport = InstrumentTransport.SERIAL, serialPort = "ttyUSB0", baud = 115200))
            val erba = b.engine.saveInstrument(InstrumentConfig(id = "", name = "Erba", driver = "mispa_count_x",
                transport = InstrumentTransport.SERIAL, serialPort = "COM3", baud = 115200))

            // The listing spells them the other way round from the rows.
            b.platform.ports = listOf(SerialPortInfo("/dev/ttyUSB0", "USB-Serial"), SerialPortInfo("com3", "USB Serial Port"),
                SerialPortInfo("ttyUSB7", "Spare"))
            val serial = payload(b.call("instruments.ports")).jsonObject.getValue("serial").jsonArray.associateBy { it.str("name")!! }
            assertEquals(mispa, serial.getValue("/dev/ttyUSB0").str("held_by"))
            assertEquals(erba, serial.getValue("com3").str("held_by"))
            assertNull(serial.getValue("ttyUSB7").str("held_by"))

            // jSerialComm opens all of these; so the guard has to refuse all of them.
            for (spelling in listOf("ttyUSB0", "/dev/ttyUSB0", "/dev/ttyusb0", "COM3", "com3")) {
                val r = b.call("instruments.probe", """{"serial_port":"$spelling"}""")
                assertIs<ToolResult.Refused>(r, "probing $spelling took a port from a running analyzer")
                assertTrue("Mispa" in r.reason || "Erba" in r.reason, r.reason)
            }
            assertFalse(b.call("instruments.probe", """{"serial_port":"ttyUSB7"}""") is ToolResult.Refused,
                "a port no analyzer owns is still probed")

            // And a second analyzer cannot be pointed at a taken port under another name.
            val clash = b.call("instruments.set_config",
                """{"name":"Clone","driver_key":"mispa_count_x","transport":"serial","serial_port":"/dev/ttyUSB0","baud":115200,"enabled":true}""")
            assertIs<ToolResult.Failed>(clash)
            assertTrue("Mispa" in clash.message, clash.message)
        } finally { b.close() }
    }

    @Test
    fun `instruments dry_run - lookup only, masked accession, nothing written`() = runBlocking<Unit> {
        val b = Bench()
        try {
            val order = b.newOrder()
            val cfg = b.hl7()
            val r = payload(b.call("instruments.dry_run", Json.encodeToString(JsonElement.serializer(), kotlinx.serialization.json.buildJsonObject {
                put("instrument_id", JsonPrimitive(cfg.id)); put("frame_text", JsonPrimitive(oru(order.accessionNo)))
            })))
            assertEquals(true, r.bool("parsed"))
            assertEquals(true, r.bool("would_match"))
            assertEquals(FrameScrubber.maskId(order.accessionNo), r.str("accession"))
            assertEquals("wbc", r.jsonObject.getValue("mapped").str("WBC"))
            assertTrue(b.repo.resultsForOrder(order.id).all { it.value.isNullOrBlank() })
            assertEquals(0, b.db.instrumentsQueries.listUnmatched().executeAsList().size)
        } finally { b.close() }
    }

    @Test
    fun `catalog tests - keys and units, no prices`() = runBlocking<Unit> {
        val b = Bench()
        try {
            b.newOrder()
            val tests = payload(b.call("catalog.tests")).jsonArray
            val cbc = tests.first { it.str("code") == "CBC" }
            assertNull(cbc.jsonObject["price"])
            assertTrue(cbc.jsonObject.getValue("parameters").jsonArray.any { it.str("key") == "hb" && it.str("unit") == "g/dL" })
            assertFalse("price" in tests.toString())
        } finally { b.close() }
    }

    @Test
    fun `db query - refuses writes and patient tables without consent, allows reads, and audits table names only`() = runBlocking<Unit> {
        val b = Bench()
        try {
            b.newOrder()
            assertIs<ToolResult.Refused>(b.call("db.query", """{"sql":"DELETE FROM instruments"}""", ALL))
            val phi = b.call("db.query", """{"sql":"SELECT name FROM patients"}""", NONE)
            assertIs<ToolResult.Refused>(phi)
            assertTrue("patients" in phi.reason)

            val ok = b.call("db.query", """{"sql":"SELECT name FROM patients WHERE name LIKE 'Kav%'","max_rows":5}""", ALL)
            val p = payload(ok)
            assertEquals(listOf("name"), p.jsonObject.getValue("columns").jsonArray.map { it.jsonPrimitive.content })
            assertEquals("Kavitha Raman", p.jsonObject.getValue("rows").jsonArray[0].jsonArray[0].jsonPrimitive.content)
            val summary = (ok as ToolResult.Ok).summaryForAudit
            assertTrue("patients" in summary && "1 row" in summary, summary)
            assertFalse("Kav" in summary || "SELECT" in summary, "the SQL text never reaches the audit: $summary")

            val meta = payload(b.call("db.query", """{"sql":"SELECT count(*) AS n FROM instruments"}""", NONE))
            assertEquals("0", meta.jsonObject.getValue("rows").jsonArray[0].jsonArray[0].jsonPrimitive.content)
            assertIs<ToolResult.Failed>(b.call("db.query", """{"sql":"SELECT * FROM no_such_table"}""", ALL))
        } finally { b.close() }
    }

    @Test
    fun `db query - frames need the analyzer tick and staff the records one, PIN hashes never leave, errors carry no literal`() = runBlocking<Unit> {
        val b = Bench()
        try {
            val order = b.newOrder()
            val cfg = b.hl7()
            b.ingest(cfg, oru(order.accessionNo))
            b.driver.execute(null, "INSERT INTO staff(id, name, role, pin_hash, active, created_at, updated_at, signature_png, registration_no) " +
                "VALUES ('owner-1', 'Dr. Meena Rao', 'owner', 's1\$salt\$deadbeef', 1, 'a', 'b', 'iVBORw0KGgo=', 'TN-12345')", 0)

            // The raw frame — the bytes instruments.log only shares scrubbed and
            // under analyzer-data consent — is not one SELECT away, and records
            // consent is not the tick that opens it.
            val sql = """{"sql":"SELECT raw FROM instrument_log WHERE direction = 'rx'"}"""
            val raw = b.call("db.query", sql, NONE)
            assertIs<ToolResult.Refused>(raw)
            assertTrue("instrument_log" in raw.reason, raw.reason)
            assertIs<ToolResult.Refused>(b.call("db.query", sql, SupportConsent(records = true)))
            val shared = payload(b.call("db.query", sql, SupportConsent(analyzerData = true)))
            assertTrue("PID" in shared.toString(), "with the analyzer tick the frame comes through: $shared")

            // Staff go the other way: the records tick, and the analyzer one does nothing for them.
            val staff = b.call("db.query", """{"sql":"SELECT id, name, role FROM staff"}""", NONE)
            assertIs<ToolResult.Refused>(staff)
            assertTrue("staff" in staff.reason, staff.reason)
            assertIs<ToolResult.Refused>(b.call("db.query", """{"sql":"SELECT id, name FROM staff"}""", SupportConsent(analyzerData = true)))

            // Even with every box ticked, the PIN hash and the signature are refused by name…
            val pin = b.call("db.query", """{"sql":"SELECT id, pin_hash FROM staff"}""", ALL)
            assertIs<ToolResult.Refused>(pin)
            assertTrue("pin_hash" in pin.reason, pin.reason)
            assertIs<ToolResult.Refused>(b.call("db.query", """{"sql":"SELECT signature_png FROM staff"}""", ALL))
            // …and blanked when a SELECT * would carry them.
            val star = payload(b.call("db.query", """{"sql":"SELECT * FROM staff"}""", ALL))
            val columns = star.jsonObject.getValue("columns").jsonArray.map { it.jsonPrimitive.content }
            val row = star.jsonObject.getValue("rows").jsonArray[0].jsonArray.map { (it as? JsonPrimitive)?.contentOrNull }
            assertEquals(DbQueryGuard.HIDDEN_CELL, row[columns.indexOf("pin_hash")])
            assertEquals(DbQueryGuard.HIDDEN_CELL, row[columns.indexOf("signature_png")])
            assertEquals("Dr. Meena Rao", row[columns.indexOf("name")], "everything else in the row is what was asked for")
            assertEquals(listOf("pin_hash", "signature_png"), star.jsonObject.getValue("hidden_columns").jsonArray.map { it.jsonPrimitive.content })
            assertFalse("deadbeef" in star.toString() || "iVBOR" in star.toString())

            // An unterminated literal: SQLite echoes it back, the tool does not…
            val broken = b.call("db.query", """{"sql":"SELECT * FROM patients WHERE name = 'Kavitha Raman"}""", ALL)
            assertIs<ToolResult.Failed>(broken)
            assertTrue(broken.message.startsWith("SQL error — check the statement"), broken.message)
            assertFalse("Kavitha" in broken.message, broken.message)
            assertEquals(RemoteTools.AUDIT_SQL_ERROR, broken.summaryForAudit, "the audit row keeps a fixed line")
            // …and a bare-word name, which has no quotes to strip, reaches the
            // engineer but still not the audit row that outlives the session.
            val bareWord = b.call("db.query", """{"sql":"SELECT * FROM patients WHERE name = Kavitha"}""", ALL)
            assertIs<ToolResult.Failed>(bareWord)
            assertEquals(RemoteTools.AUDIT_SQL_ERROR, bareWord.summaryForAudit)
            assertFalse("Kavitha" in bareWord.summaryForAudit)
        } finally { b.close() }
    }

    @Test
    fun `logs tail - lines capped at 500, day selects a file, diagnostics only when lines is omitted`() = runBlocking<Unit> {
        val b = Bench()
        try {
            val some = payload(b.call("logs.tail", """{"lines":3}"""))
            assertEquals(3, some.str("log")!!.lines().size)
            assertNull(some.jsonObject["diagnostics"])
            val all = payload(b.call("logs.tail"))
            assertNotNull(all.jsonObject["diagnostics"])
            assertEquals("200", all.str("lines"))
            assertEquals(300, payload(b.call("logs.tail", """{"lines":9999}""")).str("log")!!.lines().size.coerceAtMost(300))
            assertEquals("(no activity log on this device)", payload(b.call("logs.tail", """{"lines":5,"day":"1999-01-01"}""")).str("log"))
        } finally { b.close() }
    }

    @Test
    fun `session info echoes consent and the engine's live numbers`() = runBlocking<Unit> {
        val b = Bench()
        try {
            val s = payload(b.call("session.info", consent = SupportConsent(records = true)))
            assertEquals(true, s.jsonObject.getValue("consent").bool("records"))
            assertEquals(false, s.jsonObject.getValue("consent").bool("screen"))
            assertEquals("1234", s.str("remaining_s"))
            assertEquals("3", s.str("actions"))
            assertEquals(true, s.bool("peer_connected"))
        } finally { b.close() }
    }

    // ── write tools ──

    @Test
    fun `instruments restart - refused seconds after a result unless forced`() = runBlocking<Unit> {
        val b = Bench()
        try {
            val order = b.newOrder()
            val cfg = b.hl7()
            b.ingest(cfg, oru(order.accessionNo))
            val refused = b.call("instruments.restart", """{"instrument_id":"${cfg.id}"}""")
            assertIs<ToolResult.Refused>(refused)
            assertTrue("force" in refused.reason, refused.reason)
            assertIs<ToolResult.Refused>(b.call("instruments.restart"), "restart-all is refused for the same reason")

            val forced = b.call("instruments.restart", """{"instrument_id":"${cfg.id}","force":true}""")
            assertEquals("restart BC-5130 (forced)", (forced as ToolResult.Ok).summaryForAudit)
            assertEquals("listening", payload(forced).jsonObject.getValue("status").str("state"))
            assertIs<ToolResult.Failed>(b.call("instruments.restart", """{"instrument_id":"nope"}"""))
        } finally { b.close() }
    }

    @Test
    fun `instruments set_config - validates, creates, flags verify_pending on driver or map changes, audits field names only`() = runBlocking<Unit> {
        val b = Bench()
        try {
            val port = freePort()
            // Validation.
            assertIs<ToolResult.Failed>(b.call("instruments.set_config", """{"name":"X","driver_key":"astm","transport":"tcp","tcp_port":$port}"""))
            assertIs<ToolResult.Failed>(b.call("instruments.set_config", """{"name":"X","driver_key":"mindray_hl7","transport":"serial","serial_port":"COM3"}"""), "Mindray is TCP-only")
            assertIs<ToolResult.Failed>(b.call("instruments.set_config", """{"name":"X","driver_key":"mispa_count_x","transport":"serial"}"""), "serial needs a port")
            assertIs<ToolResult.Failed>(b.call("instruments.set_config", """{"name":"X","driver_key":"mindray_hl7","transport":"tcp","tcp_port":70000}"""))
            assertIs<ToolResult.Failed>(b.call("instruments.set_config", """{"name":"X","driver_key":"mindray_hl7","transport":"tcp","tcp_port":$port,"param_map_json":"[1,2]"}"""))

            // Create: a new row is verify_pending from the start.
            val created = b.call("instruments.set_config", """{"name":"Mispa bench 1","driver_key":"mispa_count_x","transport":"serial","serial_port":"COM3","baud":115200}""")
            val c = payload(created)
            val id = c.str("id")!!
            assertEquals(true, c.bool("verify_pending"))
            assertTrue("created" in (created as ToolResult.Ok).summaryForAudit)

            // A second enabled analyzer on the same serial port is refused.
            assertIs<ToolResult.Failed>(b.call("instruments.set_config", """{"name":"Dup","driver_key":"mispa_count_x","transport":"serial","serial_port":"COM3"}"""))

            // Clear the flag at the bench, then change only the name: no flag.
            b.engine.setVerifyPending(id, false)
            val renamed = b.call("instruments.set_config", """{"id":"$id","name":"Mispa bench 2","driver_key":"mispa_count_x","transport":"serial","serial_port":"COM3"}""")
            assertEquals(false, payload(renamed).bool("verify_pending"))
            assertEquals("set_config Mispa bench 2: name", (renamed as ToolResult.Ok).summaryForAudit)

            // Change the param map: flag set, audit names the field, never the map.
            val mapped = b.call("instruments.set_config", """{"id":"$id","name":"Mispa bench 2","driver_key":"mispa_count_x","transport":"serial","serial_port":"COM3","param_map_json":"{\"GRAN%\":\"neut\"}"}""")
            assertEquals(true, payload(mapped).bool("verify_pending"))
            val summary = (mapped as ToolResult.Ok).summaryForAudit
            assertTrue("param_map_json" in summary && "verify_pending" in summary, summary)
            assertFalse("GRAN" in summary || "neut" in summary, summary)
            assertEquals("""{"GRAN%":"neut"}""", b.engine.instrumentById(id)!!.paramMapJson)
            assertIs<ToolResult.Failed>(b.call("instruments.set_config", """{"id":"ghost","name":"X","driver_key":"mispa_count_x","transport":"tcp","tcp_port":$port}"""))
        } finally { b.close() }
    }

    @Test
    fun `instruments delete - removes the row and its status`() = runBlocking<Unit> {
        val b = Bench()
        try {
            val cfg = b.hl7()
            val r = b.call("instruments.delete", """{"id":"${cfg.id}"}""")
            assertEquals("delete BC-5130", (r as ToolResult.Ok).summaryForAudit)
            assertNull(b.engine.instrumentById(cfg.id))
            assertFalse(cfg.id in b.engine.status.value)
            assertIs<ToolResult.Failed>(b.call("instruments.delete", """{"id":"${cfg.id}"}"""))
        } finally { b.close() }
    }

    @Test
    fun `app update prefetch - up to date, wrong version refused, newest downloaded and opened but never installed by itself`() = runBlocking<Unit> {
        val b = Bench()
        try {
            assertEquals("up_to_date", payload(b.call("app.update.prefetch")).str("status"))

            val release = ReleaseInfo("1.3.0", "lab-v1.3.0", "notes", "https://github.com/x/y/releases/download/lab-v1.3.0/BNMLab-macos-arm64.dmg", 1_000L, "https://github.com/x/y/checksums.txt")
            b.updates.check = UpdateCheck.Available(release)
            val wrong = b.call("app.update.prefetch", """{"version":"1.2.5"}""")
            assertIs<ToolResult.Refused>(wrong)
            assertNull(b.updates.downloaded)

            val r = b.call("app.update.prefetch", """{"version":"1.3.0"}""")
            val p = payload(r)
            assertEquals("installer_opened", p.str("status"))
            assertEquals(true, p.bool("verified"))
            assertTrue("Someone at the lab PC" in p.str("next")!!)
            assertEquals("BNMLab-macos-arm64.dmg", b.updates.downloaded)
            assertEquals("update prefetch 1.3.0 → installer_opened", (r as ToolResult.Ok).summaryForAudit)

            b.updates.check = UpdateCheck.Failed("GitHub is rate-limiting")
            assertIs<ToolResult.Failed>(b.call("app.update.prefetch"))
        } finally { b.close() }
    }

    @Test
    fun `session end reaches the engine`() = runBlocking<Unit> {
        val b = Bench()
        try {
            assertEquals(true, payload(b.call("session.end")).bool("ended"))
            // The end is deferred a moment so the reply leaves the socket first.
            waitFor("engine told to end") { b.controller.ended != null }
            assertEquals("Ended by the BNM engineer", b.controller.ended)
        } finally { b.close() }
    }

    // ── audit store + schema ──

    @Test
    fun `the audit store keeps sessions and rows, newest first, and a tenant wipe leaves them alone`() = runBlocking<Unit> {
        val b = Bench()
        try {
            val store = SqlSupportAuditStore(b.db)
            val session = b.session(ALL)
            store.sessionStarted(session)
            store.append(SupportAuditRow("a1", session.id, 1_800_000_000_000L, "lab.overview", "overview", SupportAuditRow.Outcome.OK, 12, "owner-1"))
            store.append(SupportAuditRow("a2", session.id, 1_800_000_001_000L, "db.query", "query 3 row(s) · patients", SupportAuditRow.Outcome.REFUSED, 1, "owner-1"))
            store.sessionEnded(session.id, 1_800_000_002_000L, "expired")

            val recent = store.recent(10)
            assertEquals(listOf("a2", "a1"), recent.map { it.id })
            assertEquals(SupportAuditRow.Outcome.REFUSED, recent[0].outcome)
            assertEquals(1_800_000_001_000L, recent[0].atMs)
            val s = b.db.supportAuditQueries.recentSessions(5).executeAsList().single()
            assertEquals("expired", s.end_reason)
            assertNotNull(s.ended_at)
            assertTrue("\"analyzer_data\":true" in s.consent_json)

            b.newOrder()
            b.repo.resetForNewTenant()
            assertEquals(2, store.recent(10).size, "support history outlives a tenant switch")
            assertEquals(0L, b.repo.tenantRowCounts().patients)
        } finally { b.close() }
    }

    @Test
    fun `an installed database gains verify_pending through the column self-heal, twice`() = runBlocking<Unit> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        driver.execute(null, "DROP TABLE instruments", 0)
        driver.execute(null,
            "CREATE TABLE instruments (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, driver_key TEXT NOT NULL, " +
                "transport TEXT NOT NULL, serial_port TEXT, baud INTEGER NOT NULL DEFAULT 115200, tcp_port INTEGER, " +
                "enabled INTEGER NOT NULL DEFAULT 1, param_map_json TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL)", 0)
        driver.execute(null, "INSERT INTO instruments VALUES ('old', 'Old', 'mispa_count_x', 'serial', 'COM3', 115200, NULL, 1, NULL, 'a', 'b')", 0)
        repeat(2) {
            driver.addColumn("instruments", "verify_pending", "INTEGER NOT NULL DEFAULT 0")
            driver.addColumn("instruments", "analyzer_host", "TEXT")   // SELECT * needs every later column too
        }
        val db = AppDatabase(driver)
        val row = db.instrumentsQueries.instrumentById("old").executeAsOne()
        assertEquals(0L, row.verify_pending)
        db.instrumentsQueries.setVerifyPending(1L, "c", "old")
        assertEquals(1L, db.instrumentsQueries.instrumentById("old").executeAsOne().verify_pending)
    }
}
