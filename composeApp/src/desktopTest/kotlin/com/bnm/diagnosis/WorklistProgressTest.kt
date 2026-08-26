package com.bnm.diagnosis

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.bnm.diagnosis.api.ApiClient
import com.bnm.diagnosis.db.AppDatabase
import com.bnm.diagnosis.lab.LabRepository
import com.bnm.diagnosis.lab.LabTest
import com.bnm.diagnosis.lab.Patient
import com.bnm.diagnosis.lab.TestParameter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The worklist's "done/total" counts TESTS THAT ARE FULLY ENTERED — a
 * two-parameter test with one value filled is still in progress, not done.
 * That distinction is the whole point of the column.
 */
class WorklistProgressTest {

    private fun repo(): LabRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        return LabRepository(AppDatabase(driver), ApiClient.json)
    }

    @Test
    fun doneCount_countsOnlyFullyEnteredTests() = runBlocking {
        val repo = repo()
        // One single-parameter test and one two-parameter test.
        repo.upsertTest(
            LabTest(
                id = "t-single", code = "XS", name = "Single", price = 50.0,
                parameters = listOf(TestParameter(key = "a", name = "A", unit = "u")),
            )
        )
        repo.upsertTest(
            LabTest(
                id = "t-pair", code = "XP", name = "Pair", price = 80.0,
                parameters = listOf(
                    TestParameter(key = "p1", name = "P1", unit = "u"),
                    TestParameter(key = "p2", name = "P2", unit = "u"),
                ),
            )
        )
        val pat = repo.upsertPatient(Patient(id = "pat-1", name = "Progress Patient", sex = "M", ageYears = 40))
        val order = repo.createLabOrder(pat.id, testIds = listOf("t-single", "t-pair")).getOrThrow()

        suspend fun done(): Long =
            repo.openOrdersFlow(50).first().single { it.order.id == order.id }.doneCount

        assertEquals(0, done(), "nothing entered yet")

        repo.enterResult(order.id, "t-single", "a", "5").getOrThrow()
        assertEquals(1, done(), "the single-parameter test is complete")

        repo.enterResult(order.id, "t-pair", "p1", "6").getOrThrow()
        assertEquals(1, done(), "a half-filled test must NOT count as done")

        repo.enterResult(order.id, "t-pair", "p2", "7").getOrThrow()
        assertEquals(2, done(), "both tests complete")

        val entry = repo.openOrdersFlow(50).first().single { it.order.id == order.id }
        assertEquals(2, entry.testCount)
    }
}
