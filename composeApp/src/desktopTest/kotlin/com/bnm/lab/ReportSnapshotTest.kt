package com.bnm.lab

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.bnm.lab.api.ApiClient
import com.bnm.lab.db.AppDatabase
import com.bnm.lab.lab.LabRepository
import com.bnm.lab.lab.LabTest
import com.bnm.lab.lab.Patient
import com.bnm.lab.lab.RefRange
import com.bnm.lab.lab.TestParameter
import com.bnm.lab.license.LicenseManager
import com.bnm.lab.report.PngWriter
import com.bnm.lab.report.ReportAssembler
import com.bnm.lab.report.ReportGraph
import com.bnm.lab.report.ReportRow
import com.bnm.lab.report.ReportSection
import com.bnm.lab.report.ReportShare
import com.bnm.lab.report.ReportSignature
import com.bnm.lab.report.ReportSnapshot
import com.bnm.lab.report.buildReportSnapshot
import com.bnm.lab.report.sampleReportDoc
import com.bnm.lab.staff.Staff
import com.bnm.lab.staff.StaffRepository
import com.bnm.lab.staff.StaffRole
import com.russhwolf.settings.PropertiesSettings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Properties
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The report as data — what the QR's web page draws. Pinned: the exact v1 keys
 * (a key the page does not know is a key the server drops), no internal id ever
 * reaching the patient's browser, the stored flags untouched, the 256 KB cap
 * shedding pictures before words, and "Lab Owner" never named as a signer.
 */
@OptIn(ExperimentalEncodingApi::class)
class ReportSnapshotTest {

    private fun JsonObject.obj(key: String) = getValue(key).jsonObject
    private fun JsonObject.arr(key: String) = getValue(key).jsonArray
    private fun JsonObject.str(key: String): String? = getValue(key).let { if (it is JsonNull) null else it.jsonPrimitive.content }

    /** A PNG-signatured blob of [size] bytes — the cap only measures it. */
    private fun bigPng(size: Int) = ByteArray(size).also {
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).copyInto(it)
    }

    @Test
    fun `the snapshot carries exactly the v1 keys`() {
        val s = buildReportSnapshot(sampleReportDoc(), generatedAt = "2026-09-15T08:30:00Z")
        assertEquals(setOf("v", "generatedAt", "letterhead", "pagination", "patient", "toFollow", "sections", "signoff"), s.keys)
        assertEquals(1, s.getValue("v").jsonPrimitive.content.toInt())
        assertEquals("2026-09-15T08:30:00Z", s.str("generatedAt"))
        assertEquals("continuous", s.str("pagination"))

        val lh = s.obj("letterhead")
        assertEquals(setOf("mode", "accent", "labName", "lines"), lh.keys)
        assertEquals("printed", lh.str("mode"))
        assertEquals("#0E8C8C", lh.str("accent"))
        assertEquals(3, lh.arr("lines").size)

        val p = s.obj("patient")
        assertEquals(setOf("name", "ageSex", "phone", "referrer", "accession", "registered", "reported", "priority"), p.keys)
        assertEquals("Kavitha Subramanian", p.str("name"))
        assertEquals(JsonNull, p.getValue("priority"), "routine prints nothing — and says so as null")

        val sections = s.arr("sections")
        assertEquals(4, sections.size)
        val cbc = sections[0].jsonObject
        assertEquals(setOf("title", "department", "sampleType", "rows", "graphs"), cbc.keys)
        assertEquals(setOf("param", "value", "unit", "ref", "flag"), cbc.arr("rows")[0].jsonObject.keys)
        val graphs = cbc.arr("graphs").map { it.jsonObject }
        assertEquals(listOf("wbc", "rbc", "plt", "diff"), graphs.map { it.str("kind") })
        graphs.forEach { assertEquals(setOf("kind", "title", "xLabel", "points", "lines", "imagePng"), it.keys) }
        assertEquals(JsonNull, graphs[0].getValue("imagePng"), "a curve is drawn from its points, never its bitmap")
        assertTrue(graphs[3].str("imagePng")!!.startsWith("iVBORw0KGgo"), "the scattergram rides as PNG base64")

        val so = s.obj("signoff")
        assertEquals(setOf("verifiedBy", "approvedBy", "approvedOn", "verifier", "approver"), so.keys)
        assertEquals(setOf("qualifications", "registrationNo", "signaturePng"), so.obj("approver").keys)
        assertEquals("TN/12345/2011", so.obj("approver").str("registrationNo"))
        assertEquals(JsonNull, so.obj("verifier").getValue("registrationNo"))
        assertNotNull(so.obj("verifier").str("signaturePng"))
    }

    @Test
    fun `stored flags pass through untouched, blank as null`() {
        val flags = listOf("N", "L", "H", "CL", "CH", "A", null, " ")
        val doc = sampleReportDoc().copy(sections = listOf(ReportSection("Flags",
            rows = flags.mapIndexed { i, f -> ReportRow("p$i", "1", "u", "0 - 2", f) })))
        val out = buildReportSnapshot(doc).arr("sections")[0].jsonObject.arr("rows").map { it.jsonObject.str("flag") }
        assertEquals(listOf("N", "L", "H", "CL", "CH", "A", null, null), out)
    }

    @Test
    fun `a curve is short numbers, and nothing that is not valid JSON`() {
        val doc = sampleReportDoc().copy(sections = listOf(ReportSection("CBC", rows = emptyList(),
            graphs = listOf(ReportGraph("wbc", "WBC", listOf(0.0, 12.0, 3.25, Double.NaN), lines = listOf(38.0))))))
        val g = buildReportSnapshot(doc).arr("sections")[0].jsonObject.arr("graphs")[0].jsonObject
        assertEquals("[0,12,3.25,0]", g.getValue("points").toString())
        assertEquals("[38]", g.getValue("lines").toString())
    }

    @Test
    fun `a bitmap that is not a PNG stays on the paper only`() {
        val bmp = "BM".encodeToByteArray() + ByteArray(64)
        val doc = sampleReportDoc().copy(sections = listOf(ReportSection("CBC", rows = emptyList(),
            graphs = listOf(ReportGraph("diff", "DIFF", emptyList(), image = bmp)))))
        assertEquals(JsonArray(emptyList()), buildReportSnapshot(doc).arr("sections")[0].jsonObject.arr("graphs"),
            "a graph with nothing drawable is left out, not sent as an empty box")
    }

    @Test
    fun `the sign-off never names the placeholder account`() {
        val doc = sampleReportDoc().copy(verifiedBy = "Lab Owner", approvedBy = " owner ")
        val so = buildReportSnapshot(doc).obj("signoff")
        assertEquals(JsonNull, so.getValue("verifiedBy"))
        assertEquals(JsonNull, so.getValue("approvedBy"))
        val real = buildReportSnapshot(sampleReportDoc().copy(approvedBy = "  Dr. Meena Iyer ")).obj("signoff")
        assertEquals("Dr. Meena Iyer", real.str("approvedBy"))
        assertEquals("Tech. S. Kumar", real.str("verifiedBy"))
    }

    @Test
    fun `over the cap, graph images go first and signature images next`() {
        val base = sampleReportDoc()
        val cbcWithBigScatter = base.sections[0].copy(graphs = base.sections[0].graphs.map {
            if (it.kind == "diff") ReportGraph("diff", "DIFF", emptyList(), image = bigPng(200_000)) else it
        })
        val heavyGraph = base.copy(sections = listOf(cbcWithBigScatter) + base.sections.drop(1))
        val full = buildReportSnapshot(heavyGraph, maxBytes = Int.MAX_VALUE)
        assertTrue(ReportSnapshot.byteSize(full) > ReportSnapshot.MAX_BYTES, "the fixture must actually be over the cap")

        val capped = buildReportSnapshot(heavyGraph)
        assertTrue(ReportSnapshot.byteSize(capped) <= ReportSnapshot.MAX_BYTES)
        val kinds = capped.arr("sections")[0].jsonObject.arr("graphs").map { it.jsonObject.str("kind") }
        assertEquals(listOf("wbc", "rbc", "plt"), kinds, "the scattergram went; the curves stayed")
        assertNotNull(capped.obj("signoff").obj("approver").str("signaturePng"), "signatures survive while graphs are enough")

        val heavyInk = heavyGraph.copy(
            signature = ReportSignature(bigPng(150_000), "MD (Pathology)", "TN/1"),
            verifierSignature = ReportSignature(bigPng(150_000), "DMLT", null),
        )
        val shed = buildReportSnapshot(heavyInk)
        assertTrue(ReportSnapshot.byteSize(shed) <= ReportSnapshot.MAX_BYTES)
        assertEquals(listOf("wbc", "rbc", "plt"), shed.arr("sections")[0].jsonObject.arr("graphs").map { it.jsonObject.str("kind") })
        val approver = shed.obj("signoff").obj("approver")
        assertEquals(JsonNull, approver.getValue("signaturePng"))
        assertEquals("MD (Pathology)", approver.str("qualifications"), "only the picture goes, never the credentials")
        assertEquals(JsonNull, shed.obj("signoff").obj("verifier").getValue("signaturePng"))
        assertEquals(heavyInk.sections.sumOf { it.rows.size },
            shed.arr("sections").sumOf { it.jsonObject.arr("rows").size }, "every result row is still there")
    }

    @Test
    fun `the content hash ignores when it was built, and nothing else`() {
        val doc = sampleReportDoc()
        val a = ReportSnapshot.contentSha256(buildReportSnapshot(doc, generatedAt = "2026-09-15T08:30:00Z"))
        val b = ReportSnapshot.contentSha256(buildReportSnapshot(doc, generatedAt = "2026-09-16T11:00:00Z"))
        assertEquals(a, b)
        assertEquals(64, a.length)
        val changed = doc.copy(toFollow = listOf("Serum Electrolytes"))
        assertNotEquals(a, ReportSnapshot.contentSha256(buildReportSnapshot(changed, generatedAt = "2026-09-15T08:30:00Z")))
    }

    @Test
    fun `the printed link is the app page with the token in the fragment, and no QR rides in the data`() {
        val token = ReportShare.newToken()
        val qr = assertNotNull(ReportShare.qrFor(token))
        assertEquals("https://app.bnmapp.com/r/#$token", qr.url)
        assertEquals(41, qr.matrix.size, "90 bytes at ECC M is a version-6 symbol")
        assertEquals("Scan to view this report", qr.caption)

        val sample = sampleReportDoc()
        val json = buildReportSnapshot(sample).toString()
        assertTrue(sample.qr!!.url.substringAfter('#') !in json, "the token never rides in the snapshot")
        assertTrue("bnmapp.com" !in json && "supabase" !in json)
    }

    @Test
    fun `an assembled report carries no internal id — order, patient, test, staff or token`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        val db = AppDatabase(driver)
        val repo = LabRepository(db, ApiClient.json)
        val staff = StaffRepository(db, ApiClient.json)
        val ink = Base64.Default.encode(PngWriter.grayscale1Bit(60, 20) { x, y -> x / 3 == y })
        val tech = staff.upsert(Staff(id = "staff-tech-3c1d", name = "S. Kumar", role = StaffRole.TECHNICIAN,
            signaturePng = ink, qualifications = "DMLT", pinHash = "s1\$salt\$deadbeef"))
        val path = staff.upsert(Staff(id = "staff-path-9e2f", name = "Dr. Meena Iyer", role = StaffRole.PATHOLOGIST,
            signaturePng = ink, qualifications = "MD (Pathology)", registrationNo = "TN/12345"))
        repo.upsertTest(LabTest(id = "test-glu-5b2c", code = "GLU", name = "Glucose", price = 100.0,
            parameters = listOf(TestParameter(key = "glu", name = "Glucose", unit = "mg/dL", decimals = 0,
                ranges = listOf(RefRange(low = 70.0, high = 100.0))))))
        val patient = repo.upsertPatient(Patient(id = "patient-7f3a-41aa", name = "Asha Raman", sex = "F", ageYears = 30, phone = "9876543210"))
        val order = repo.createLabOrder(patient.id, testIds = listOf("test-glu-5b2c")).getOrThrow()
        repo.enterResult(order.id, "test-glu-5b2c", "glu", "150").getOrThrow()
        repo.verifyOrder(order.id, tech.name, tech.id).getOrThrow()
        repo.approveOrder(order.id, path.name, path.id).getOrThrow()
        db.instrumentsQueries.upsertGraph(order.id, "test-glu-5b2c", "wbc", "[1.0,5.0,9.0,4.0]", null, "2026-09-15T08:00:00Z", null)

        val license = LicenseManager(PropertiesSettings(Properties()), { true }, { 1_800_000_000L })
        val doc = assertNotNull(ReportAssembler(repo, staff, license = license).assemble(order.id, "Sunrise Lab", stampReportedNow = false))
        val snapshot = buildReportSnapshot(doc, generatedAt = "2026-09-15T08:30:00Z")
        val json = snapshot.toString()

        val secrets = listOfNotNull(order.id, patient.id, "test-glu-5b2c", tech.id, path.id, "deadbeef",
            repo.reportShare(order.id)?.token)
        secrets.forEach { assertTrue(it !in json, "\"$it\" leaked into the snapshot") }
        // …while what the paper prints is all there.
        val row = snapshot.arr("sections")[0].jsonObject.arr("rows")[0].jsonObject
        assertEquals("150", row.str("value"))
        assertEquals("H", row.str("flag"))
        assertEquals(order.accessionNo, snapshot.obj("patient").str("accession"))
        assertEquals("Dr. Meena Iyer", snapshot.obj("signoff").str("approvedBy"))
        assertEquals("S. Kumar", snapshot.obj("signoff").str("verifiedBy"))
        assertEquals("TN/12345", snapshot.obj("signoff").obj("approver").str("registrationNo"))
        assertEquals("[1,5,9,4]", snapshot.arr("sections")[0].jsonObject.arr("graphs")[0].jsonObject.getValue("points").toString())
    }
}
