package com.bnm.diagnosis

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.bnm.diagnosis.api.ApiClient
import com.bnm.diagnosis.db.AppDatabase
import com.bnm.diagnosis.lab.LabRepository
import com.bnm.diagnosis.lab.LabStatus
import com.bnm.diagnosis.lab.LabTest
import com.bnm.diagnosis.lab.Patient
import com.bnm.diagnosis.lab.Referrer
import com.bnm.diagnosis.screens.lab.renderCommissionCsv
import com.bnm.diagnosis.screens.lab.renderCommissionStatement
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P4 · B2B referrer economics against a REAL (in-memory) SQLDelight database:
 *  1. a rate-list override beats the catalog price through the ONE pricing brain
 *     (`effectivePrice`), and the order lines snapshot the EFFECTIVE price;
 *  2. the commission report sums those LINE-ITEM SNAPSHOTS — so a 10%-commission
 *     referrer with two orders gets exactly 10% of what was actually billed,
 *     even after the catalog is repriced underneath.
 */
class ReferrerRatesTest {

    private fun freshDb(): AppDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        return AppDatabase(driver)
    }

    private fun today(): String =
        kotlin.time.Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()

    private fun test(id: String, code: String, price: Double) = LabTest(
        id = id, code = code, name = "Test $code", price = price,
    )

    @Test
    fun rateOverride_beatsCatalogPrice() = runBlocking {
        val repo = LabRepository(freshDb(), ApiClient.json)
        repo.upsertTest(test("t-cbc", "RCBC", 300.0))
        repo.upsertTest(test("t-lft", "RLFT", 500.0))
        val doc = repo.upsertReferrer(
            Referrer(id = "ref-1", name = "Dr. Rate", kind = "doctor", commissionPct = 10.0)
        )

        // No rate list yet → catalog price for everyone.
        assertEquals(300.0, repo.effectivePrice("t-cbc"))
        assertEquals(300.0, repo.effectivePrice("t-cbc", doc.id))

        // Negotiated B2B rate wins for THIS referrer only.
        repo.setRate(doc.id, "t-cbc", 200.0)
        assertEquals(200.0, repo.effectivePrice("t-cbc", doc.id), "referrer rate must beat the catalog price")
        assertEquals(300.0, repo.effectivePrice("t-cbc", null), "walk-in still pays the catalog price")
        // A test with no override falls back to catalog even for a rated referrer.
        assertEquals(500.0, repo.effectivePrice("t-lft", doc.id))

        // Bulk form agrees with the single-test form (same brain).
        val list = repo.priceList(doc.id)
        assertEquals(200.0, list["t-cbc"])
        assertEquals(500.0, list["t-lft"])
        assertEquals(mapOf("t-cbc" to 200.0), repo.ratesFor(doc.id))

        // Clearing the override restores the catalog price (no frozen copy).
        repo.clearRate(doc.id, "t-cbc")
        assertEquals(300.0, repo.effectivePrice("t-cbc", doc.id))
        assertEquals(0L, repo.rateCount(doc.id))
    }

    @Test
    fun orderLines_snapshotEffectivePrice_andCommissionSumsThem() = runBlocking {
        val repo = LabRepository(freshDb(), ApiClient.json)
        repo.upsertTest(test("t-cbc", "CCBC", 300.0))
        repo.upsertTest(test("t-lft", "CLFT", 500.0))
        val doc = repo.upsertReferrer(
            Referrer(id = "ref-c", name = "Dr. Commission", kind = "doctor", commissionPct = 10.0)
        )
        val patient = repo.upsertPatient(Patient(id = "pat-c", name = "Comm Patient", sex = "F", ageYears = 30))

        // Rate list: CBC discounted to 200 (LFT stays at the 500 catalog price).
        repo.setRate(doc.id, "t-cbc", 200.0)

        // Order 1 — CBC + LFT ⇒ 200 + 500 = 700 (NOT the 800 catalog total).
        val o1 = repo.createLabOrder(
            patient.id, testIds = listOf("t-cbc", "t-lft"), referrerId = doc.id
        ).getOrThrow()
        val lines1 = repo.orderTests(o1.id).associate { it.testId to it.price }
        assertEquals(200.0, lines1["t-cbc"], "order line must snapshot the referrer rate")
        assertEquals(500.0, lines1["t-lft"], "un-rated test keeps the catalog price")

        // Order 2 — CBC only ⇒ 200.
        val o2 = repo.createLabOrder(patient.id, testIds = listOf("t-cbc"), referrerId = doc.id).getOrThrow()
        assertEquals(200.0, repo.orderTests(o2.id).single().price)

        // A walk-in order (no referrer) must NOT land on the statement.
        repo.createLabOrder(patient.id, testIds = listOf("t-cbc")).getOrThrow()

        // Re-pricing the catalog AND the rate list afterwards must not rewrite
        // history — the statement reads the line snapshots.
        repo.upsertTest(test("t-cbc", "CCBC", 999.0))
        repo.setRate(doc.id, "t-cbc", 111.0)

        val day = today()
        val report = repo.commissionReport(day, day)
        assertEquals(1, report.size, "only the referred orders produce a statement row")
        val row = report.single()
        assertEquals("Dr. Commission", row.referrerName)
        assertEquals(2L, row.ordersCount)
        assertEquals(900.0, row.gross, 0.001, "gross = 700 + 200 from the line snapshots")
        assertEquals(10.0, row.commissionPct)
        assertEquals(90.0, row.payable, 0.001, "payable = gross x 10%")

        // Drill-down lists both orders with their billed amounts.
        val orders = repo.referrerOrders(doc.id, day, day)
        assertEquals(2, orders.size)
        assertEquals(setOf(700.0, 200.0), orders.map { it.amount }.toSet())
        assertTrue(orders.all { it.patientName == "Comm Patient" })
        assertTrue(orders.any { it.accessionNo == o1.accessionNo })

        // Cancelling an order drops it (and its gross) off the statement.
        repo.setOrderStatus(o2.id, LabStatus.CANCELLED).getOrThrow()
        val after = repo.commissionReport(day, day).single()
        assertEquals(1L, after.ordersCount)
        assertEquals(700.0, after.gross, 0.001)
        assertEquals(70.0, after.payable, 0.001)

        // Statement + CSV render the same numbers the report carries.
        val text = renderCommissionStatement(day, day, listOf(after))
        assertTrue(text.contains("Dr. Commission"), text)
        assertTrue(text.contains("700.00"), text)
        assertTrue(text.contains("70.00"), text)
        val csv = renderCommissionCsv(day, day, listOf(after))
        assertTrue(csv.lineSequence().first().startsWith("Referrer,Kind,Phone,Orders"), csv)
        assertTrue(csv.contains("Dr. Commission,doctor,,1,700.00,10.0,70.00"), csv)
    }
}
