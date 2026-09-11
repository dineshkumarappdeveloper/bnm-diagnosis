package com.bnm.diagnosis

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.bnm.diagnosis.api.ApiClient
import com.bnm.diagnosis.db.AppDatabase
import com.bnm.diagnosis.db.addColumn
import com.bnm.diagnosis.lab.LabRepository
import com.bnm.diagnosis.lab.LabResult
import com.bnm.diagnosis.lab.LabStatus
import com.bnm.diagnosis.lab.LabTest
import com.bnm.diagnosis.lab.Patient
import com.bnm.diagnosis.lab.RefRange
import com.bnm.diagnosis.lab.TestParameter
import com.bnm.diagnosis.lab.TestStage
import com.bnm.diagnosis.report.ReportAssembler
import com.bnm.diagnosis.staff.StaffRepository
import com.bnm.diagnosis.sync.incomingResultWins
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Per-test release: an order whose outsourced test is still out lets the
 * finished tests be verified, approved and reported on their own; the order's
 * status is the least advanced of its tests; a partial report names what is
 * still to follow; sync never removes a release stamp.
 */
class PerTestReleaseTest {

    private fun freshDb(): AppDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        return AppDatabase(driver)
    }

    private fun glucose(id: String, name: String, fulfillment: String? = null) = LabTest(
        id = id, code = id.uppercase(), name = name, price = 100.0, fulfillment = fulfillment,
        parameters = listOf(
            TestParameter(key = "a", name = "$name A", unit = "mg/dL", decimals = 0, ranges = listOf(RefRange(low = 1.0, high = 100.0))),
            TestParameter(key = "b", name = "$name B", unit = "mg/dL", decimals = 0, ranges = listOf(RefRange(low = 1.0, high = 100.0))),
        ),
    )

    @Test
    fun `a finished test is signed and reported while the outsourced one is still out`() = runBlocking {
        val db = freshDb()
        val repo = LabRepository(db, ApiClient.json)
        repo.upsertTest(glucose("t-cbc", "Blood Count"))
        repo.upsertTest(glucose("t-out", "Vitamin D", fulfillment = "outsourced"))
        val patient = repo.upsertPatient(Patient(id = "p", name = "Partial Patient", sex = "F", ageYears = 40))
        val order = repo.createLabOrder(patient.id, testIds = listOf("t-cbc", "t-out")).getOrThrow()
        fun status() = runBlocking { repo.orderById(order.id)!!.status }
        fun rows(test: String) = runBlocking { repo.resultsForOrder(order.id).filter { it.testId == test } }

        // Nothing can be signed before its values are in.
        assertTrue(repo.verifyTest(order.id, "t-cbc", "Tech").isFailure)
        repo.enterResult(order.id, "t-cbc", "a", "50", enteredBy = "Tech").getOrThrow()
        assertTrue(repo.verifyTest(order.id, "t-cbc", "Tech").isFailure, "one of two values")
        repo.enterResult(order.id, "t-cbc", "b", "60", enteredBy = "Tech").getOrThrow()
        assertEquals(LabStatus.IN_PROGRESS, status(), "the outsourced test is still empty")

        // Verify the finished test alone: its rows are stamped, the order is not.
        repo.verifyTest(order.id, "t-cbc", "Tech", "s-tech").getOrThrow()
        assertTrue(rows("t-cbc").all { it.verifiedAt != null && it.verifiedById == "s-tech" })
        assertTrue(rows("t-out").all { it.verifiedAt == null })
        assertEquals(LabStatus.IN_PROGRESS, status())
        assertTrue(repo.verifyTest(order.id, "t-cbc", "Tech").isFailure, "already verified")
        assertTrue(repo.enterResult(order.id, "t-cbc", "a", "55").isFailure, "a verified test is locked")
        repo.enterResult(order.id, "t-out", "a", "30").getOrThrow()   // the other test stays open

        // Approve and report it alone.
        assertTrue(repo.markReported(order.id, listOf("t-cbc")).isFailure, "nothing unsigned leaves the lab")
        repo.approveTest(order.id, "t-cbc", "Dr. P", "s-path").getOrThrow()
        assertTrue(rows("t-cbc").all { it.approvedAt != null })
        assertEquals(LabStatus.IN_PROGRESS, status())
        assertNull(repo.orderById(order.id)!!.approvedAt, "the ORDER is not approved while a test is pending")
        assertEquals(setOf("t-cbc"), repo.approvedTestIds(order.id))
        repo.markReported(order.id, listOf("t-cbc")).getOrThrow()
        assertTrue(rows("t-cbc").all { it.reportedAt != null })
        assertEquals(TestStage.REPORTED, TestStage.of(rows("t-cbc")))
        assertEquals(TestStage.IN_PROGRESS, TestStage.of(rows("t-out")))
        assertEquals(LabStatus.IN_PROGRESS, status())
        // Reporting again is a no-op, not an error (a reprint).
        val firstStamp = rows("t-cbc").first().reportedAt
        repo.markReported(order.id, listOf("t-cbc")).getOrThrow()
        assertEquals(firstStamp, rows("t-cbc").first().reportedAt)

        // The partial report: only the released test, the other named as to-follow.
        val assembler = ReportAssembler(repo, StaffRepository(db, ApiClient.json))
        val partial = assembler.assemble(order.id, "Lab", stampReportedNow = false, testIds = setOf("t-cbc"))!!
        assertEquals(listOf("Blood Count"), partial.sections.map { it.title })
        assertEquals(listOf("Vitamin D"), partial.toFollow)
        assertNotNull(partial.reported, "the released test's own reported time")
        assertEquals("Dr. P", partial.approvedBy)

        // The outsourced result arrives: the order walks up test by test.
        repo.enterResult(order.id, "t-out", "b", "40").getOrThrow()
        assertEquals(LabStatus.ENTERED, status(), "every value in; the slowest test is 'entered'")
        repo.verifyTest(order.id, "t-out", "Tech").getOrThrow()
        assertEquals(LabStatus.VERIFIED, status(), "both tests at least verified")
        repo.approveTest(order.id, "t-out", "Dr. P").getOrThrow()
        assertEquals(LabStatus.APPROVED, status())
        assertNotNull(repo.orderById(order.id)!!.approvedAt)
        repo.markReported(order.id, listOf("t-out")).getOrThrow()
        assertEquals(LabStatus.REPORTED, status())
        assertNotNull(repo.orderById(order.id)!!.reportedAt)
        val whole = assembler.assemble(order.id, "Lab", stampReportedNow = false)!!
        assertEquals(2, whole.sections.size)
        assertTrue(whole.toFollow.isEmpty())
    }

    @Test
    fun `a partial report prints what is still to follow on the paper`() = runBlocking {
        val db = freshDb()
        val repo = LabRepository(db, ApiClient.json)
        repo.upsertTest(glucose("t-cbc", "Blood Count"))
        repo.upsertTest(glucose("t-out", "Vitamin D", fulfillment = "outsourced"))
        val patient = repo.upsertPatient(Patient(id = "p", name = "Paper Patient", sex = "F", ageYears = 40))
        val order = repo.createLabOrder(patient.id, testIds = listOf("t-cbc", "t-out")).getOrThrow()
        for (k in listOf("a", "b")) repo.enterResult(order.id, "t-cbc", k, "50").getOrThrow()
        repo.verifyTest(order.id, "t-cbc", "Tech").getOrThrow()
        repo.approveTest(order.id, "t-cbc", "Dr. P").getOrThrow()
        val doc = ReportAssembler(repo, StaffRepository(db, ApiClient.json)).assemble(order.id, "Lab", testIds = setOf("t-cbc"))!!
        val pdf = com.bnm.diagnosis.report.writeLabReportPdf(doc)
        val text = org.apache.pdfbox.pdmodel.PDDocument.load(java.io.File(pdf)).use { org.apache.pdfbox.text.PDFTextStripper().getText(it) }
        assertTrue("To follow in a separate report: Vitamin D" in text, text.take(600))
        assertTrue("Blood Count" in text && "Vitamin D A" !in text, "only the released test's rows print")
        val slip = com.bnm.diagnosis.print.renderLabReport("Lab", repo.orderById(order.id)!!, patient,
            repo.orderTests(order.id).filter { it.testId == "t-cbc" }, repo.resultsForOrder(order.id).filter { it.testId == "t-cbc" },
            toFollow = listOf("Vitamin D"))
        assertTrue("To follow : Vitamin D" in slip, slip)
    }

    @Test
    fun `the whole-order path still works and never overwrites a per-test signature`() = runBlocking {
        val db = freshDb()
        val repo = LabRepository(db, ApiClient.json)
        repo.upsertTest(glucose("t1", "One")); repo.upsertTest(glucose("t2", "Two"))
        val patient = repo.upsertPatient(Patient(id = "p", name = "Whole", sex = "M", ageYears = 30))
        val order = repo.createLabOrder(patient.id, testIds = listOf("t1", "t2")).getOrThrow()
        for (t in listOf("t1", "t2")) for (k in listOf("a", "b")) repo.enterResult(order.id, t, k, "10").getOrThrow()
        assertEquals(LabStatus.ENTERED, repo.orderById(order.id)!!.status)
        repo.verifyTest(order.id, "t1", "Early Tech", "s-early").getOrThrow()
        // Order-wide verify: stamps the rest, leaves the earlier signature alone.
        repo.verifyOrder(order.id, "Late Tech", "s-late").getOrThrow()
        val rows = repo.resultsForOrder(order.id)
        assertTrue(rows.filter { it.testId == "t1" }.all { it.verifiedBy == "Early Tech" })
        assertTrue(rows.filter { it.testId == "t2" }.all { it.verifiedBy == "Late Tech" })
        assertEquals(LabStatus.VERIFIED, repo.orderById(order.id)!!.status)
        repo.approveOrder(order.id, "Dr. P").getOrThrow()
        repo.markReported(order.id, listOf("t1", "t2")).getOrThrow()
        assertEquals(LabStatus.REPORTED, repo.orderById(order.id)!!.status)
    }

    @Test
    fun `sync never removes a release stamp the local row carries`() {
        val base = LabResult(id = "r", orderId = "o", testId = "t", parameterKey = "a", value = "1",
            enteredAt = "2026-09-11T01:00:00Z")
        val local = base.copy(approvedAt = "2026-09-11T02:00:00Z", reportedAt = "2026-09-11T03:00:00Z")
        val laterButUnreported = base.copy(value = "2", enteredAt = "2026-09-11T04:00:00Z", approvedAt = "2026-09-11T04:00:00Z")
        assertFalse(incomingResultWins(local, laterButUnreported), "a newer copy without the release stamp loses")
        val laterAndReported = laterButUnreported.copy(reportedAt = "2026-09-11T05:00:00Z")
        assertTrue(incomingResultWins(local, laterAndReported))
        assertFalse(incomingResultWins(laterAndReported, local), "older never wins")
        assertTrue(incomingResultWins(base, base.copy(enteredAt = "2026-09-11T02:00:00Z")))
    }

    @Test
    fun `an existing database gains the reported_at column in place`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "CREATE TABLE lab_results (id TEXT NOT NULL PRIMARY KEY, order_id TEXT NOT NULL, " +
            "test_id TEXT NOT NULL, parameter_key TEXT NOT NULL, value TEXT, unit TEXT, flag TEXT, ref_display TEXT, " +
            "notes TEXT, entered_by TEXT, entered_at TEXT, verified_by TEXT, verified_at TEXT, approved_by TEXT, approved_at TEXT)", 0)
        driver.execute(null, "INSERT INTO lab_results(id, order_id, test_id, parameter_key, value) VALUES ('r1','o','t','a','5')", 0)
        repeat(2) {
            driver.addColumn("lab_results", "verified_by_id", "TEXT")
            driver.addColumn("lab_results", "approved_by_id", "TEXT")
            driver.addColumn("lab_results", "reported_at", "TEXT")
        }
        val db = AppDatabase(driver)
        val row = db.resultsQueries.resultsForOrder("o").executeAsOne()
        assertEquals("5", row.value_)
        assertNull(row.reported_at)
        db.resultsQueries.markReportedForTest("2026-09-11T05:00:00Z", "o", "t")
        assertEquals("2026-09-11T05:00:00Z", db.resultsQueries.resultsForOrder("o").executeAsOne().reported_at)
    }
}
