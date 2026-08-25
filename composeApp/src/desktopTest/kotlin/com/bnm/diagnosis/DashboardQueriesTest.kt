package com.bnm.diagnosis

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.bnm.diagnosis.api.ApiClient
import com.bnm.diagnosis.db.AppDatabase
import com.bnm.diagnosis.lab.LabRepository
import com.bnm.diagnosis.lab.LabStatus
import com.bnm.diagnosis.lab.LabTest
import com.bnm.diagnosis.lab.Patient
import com.bnm.diagnosis.lab.RefRange
import com.bnm.diagnosis.lab.TestParameter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Home-dashboard queries (P1a style, real in-memory SQLDelight): the open-order
 * worklist keeps every non-terminal order (approved-but-unreported included)
 * and drops reported ones; the criticals call-out list carries the patient's
 * phone so the lab can ring the result out.
 */
class DashboardQueriesTest {

    private fun freshDb(): AppDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        return AppDatabase(driver)
    }

    @Test
    fun openOrders_and_criticalsToday() = runBlocking {
        val repo = LabRepository(freshDb(), ApiClient.json)
        repo.upsertTest(
            LabTest(
                id = "t-dash-glu", code = "XGLU", name = "Dash Glucose",
                parameters = listOf(
                    TestParameter(
                        key = "glu", name = "Glucose (Fasting)", unit = "mg/dL", decimals = 0,
                        ranges = listOf(RefRange(low = 70.0, high = 100.0, criticalHigh = 400.0)),
                    )
                ),
            )
        )
        val patient = repo.upsertPatient(
            Patient(id = "pat-dash", name = "Dash Patient", sex = "M", ageYears = 40, phone = "9876543210")
        )

        val o1 = repo.createLabOrder(patient.id, testIds = listOf("t-dash-glu")).getOrThrow()
        val o2 = repo.createLabOrder(patient.id, testIds = listOf("t-dash-glu")).getOrThrow()

        // Both registered orders are open; patient identity rides along.
        var open = repo.openOrdersFlow(12).first()
        assertEquals(2, open.size)
        assertEquals("Dash Patient", open.first().patientName)
        assertEquals(1L, open.first().testCount)

        // Tab-badge rollup: one map, per status.
        assertEquals(mapOf(LabStatus.REGISTERED to 2L), repo.statusCountsFlow().first())

        // A critical entry (480 > criticalHigh 400) lands on today's call-out
        // list with the patient's phone number.
        repo.enterResult(o2.id, "t-dash-glu", "glu", "480", enteredBy = "Tech").getOrThrow()
        val crit = repo.criticalsTodayFlow(6).first()
        assertEquals(1, crit.size)
        assertEquals("CH", crit.first().flag)
        assertEquals("glu", crit.first().parameterKey)
        assertEquals("9876543210", crit.first().patientPhone)

        // Walk o1 to approved — it must STAY open (not yet reported)…
        repo.enterResult(o1.id, "t-dash-glu", "glu", "90").getOrThrow()
        repo.verifyOrder(o1.id, "Tech").getOrThrow()
        repo.approveOrder(o1.id, "Dr. Path").getOrThrow()
        open = repo.openOrdersFlow(12).first()
        assertEquals(2, open.size)

        // …and drop off once reported. o2 (entered) remains.
        repo.setOrderStatus(o1.id, LabStatus.REPORTED).getOrThrow()
        open = repo.openOrdersFlow(12).first()
        assertEquals(1, open.size)
        assertEquals(o2.id, open.first().order.id)

        // The rollup tracks the walk: o1 reported, o2 fully entered.
        assertEquals(
            mapOf(LabStatus.ENTERED to 1L, LabStatus.REPORTED to 1L),
            repo.statusCountsFlow().first(),
        )
    }
}
