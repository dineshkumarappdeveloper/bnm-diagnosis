package com.bnm.diagnosis

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.bnm.diagnosis.api.ApiClient
import com.bnm.diagnosis.db.AppDatabase
import com.bnm.diagnosis.lab.LabRepository
import com.bnm.diagnosis.lab.LabStatus
import com.bnm.diagnosis.lab.LabTest
import com.bnm.diagnosis.lab.Patient
import com.bnm.diagnosis.lab.RefRange
import com.bnm.diagnosis.lab.SeedCatalog
import com.bnm.diagnosis.lab.TestParameter
import com.bnm.diagnosis.print.renderLabReport
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end LIMS flow against a REAL (in-memory) SQLDelight database:
 * seed → patient → order/accession → result entry with range flagging →
 * verify/approve guards → printable report. This is the offline core a lab
 * lives on — no network, fully deterministic.
 */
class LabFlowE2ETest {

    private fun freshDb(): AppDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        return AppDatabase(driver)
    }

    @Test
    fun fullLabFlow_offline() = runBlocking {
        val repo = LabRepository(freshDb(), ApiClient.json)

        // Seeded catalog is substantial and panels expand.
        SeedCatalog.seedIfEmpty(repo)
        assertTrue(repo.countTests() >= 40, "seed should install the standard catalog")
        assertTrue(repo.countPanels() >= 5, "seed should install panels")

        // Deterministic custom test (independent of seed range details).
        repo.upsertTest(
            LabTest(
                id = "t-e2e-glu", code = "XGLU", name = "E2E Glucose", price = 120.0,
                parameters = listOf(
                    TestParameter(
                        key = "glu", name = "Glucose (Fasting)", unit = "mg/dL", decimals = 0,
                        ranges = listOf(RefRange(low = 70.0, high = 100.0, criticalHigh = 400.0)),
                    )
                ),
            )
        )
        val patient = repo.upsertPatient(
            Patient(id = "pat-e2e", name = "E2E Patient", sex = "F", ageYears = 34, phone = "9999999999")
        )

        // Order → accession from the per-seat never-rewind series.
        val order = repo.createLabOrder(patient.id, testIds = listOf("t-e2e-glu")).getOrThrow()
        assertTrue(order.accessionNo.matches(Regex("""ACC-S\d+-\d{5}""")), order.accessionNo)
        assertEquals(LabStatus.REGISTERED, order.status)

        // Approval before any entry must be refused.
        assertTrue(repo.approveOrder(order.id, "Dr. Early").isFailure, "approve must require entered+verified")

        // Entry: 210 against 70–100 ⇒ High; frozen range printed.
        val high = repo.enterResult(order.id, "t-e2e-glu", "glu", "210", enteredBy = "Tech A").getOrThrow()
        assertEquals("H", high.flag)
        assertTrue((high.refDisplay ?: "").contains("70"), "ref range must freeze onto the row")

        // Single-parameter order auto-walks to ENTERED; verify then locks entry.
        assertEquals(LabStatus.ENTERED, repo.orderById(order.id)!!.status)
        repo.verifyOrder(order.id, "Tech A").getOrThrow()
        assertTrue(
            repo.enterResult(order.id, "t-e2e-glu", "glu", "99").isFailure,
            "entry must lock after verification",
        )
        repo.approveOrder(order.id, "Dr. Pathologist").getOrThrow()
        assertEquals(LabStatus.APPROVED, repo.orderById(order.id)!!.status)

        // Critical flagging on a second order.
        val order2 = repo.createLabOrder(patient.id, testIds = listOf("t-e2e-glu")).getOrThrow()
        val critical = repo.enterResult(order2.id, "t-e2e-glu", "glu", "480").getOrThrow()
        assertEquals("CH", critical.flag)

        // Report renders the license-bound lab name, accession, value and flag.
        val results = repo.resultsForOrder(order.id)
        val tests = repo.orderTests(order.id)
        val report = renderLabReport(
            labName = "Sunrise Diagnostics",
            order = repo.orderById(order.id)!!,
            patient = patient,
            tests = tests,
            results = results,
            paramName = { r -> "Glucose (Fasting)".takeIf { r.parameterKey == "glu" } ?: r.parameterKey },
        )
        assertTrue(report.contains("Sunrise Diagnostics", ignoreCase = true), report.take(400))
        assertTrue(report.contains(order.accessionNo), "accession missing:\n" + report.take(400))
        assertTrue(report.contains("Glucose", ignoreCase = true))
        assertTrue(report.contains("210"))
        assertTrue(report.contains("Dr. Pathologist", ignoreCase = true))
    }
}
