package com.bnm.lab

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.bnm.lab.api.ApiClient
import com.bnm.lab.db.AppDatabase
import com.bnm.lab.lab.LabRepository
import com.bnm.lab.lab.LabTest
import com.bnm.lab.lab.Patient
import com.bnm.lab.lab.RefRange
import com.bnm.lab.lab.TestParameter
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The catalog comes from the master dataset; the PRICES come from the lab.
 * The dataset ships clinical content only — 223 tests, 791 analytes, not one
 * rupee — so the app has to be able to price the whole menu, and pricing it
 * must never disturb what the dataset put there.
 *
 * [LabRepository.setTestPrice] exists for exactly that: bulk pricing through
 * `upsertTest` would round-trip parameters_json for every row just to change a
 * number, and one bad round-trip loses a reference range.
 */
class CatalogPricingTest {

    private fun repo(): LabRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        return LabRepository(AppDatabase(driver), ApiClient.json)
    }

    /** A dataset row: real analytes and ranges, no price. */
    private fun imported(code: String) = LabTest(
        id = "t-$code", code = code, name = "Test $code", category = "Biochemistry",
        price = 0.0, sampleType = "serum",
        parameters = listOf(
            TestParameter(
                key = "${code.lowercase()}_1", name = "Analyte", unit = "mg/dL", decimals = 1,
                ranges = listOf(RefRange(low = 70.0, high = 110.0)),
            ),
        ),
    )

    @Test
    fun `pricing a test changes the price and nothing else`() = runBlocking {
        val repo = repo()
        repo.upsertTest(imported("GLU"))

        repo.setTestPrice("t-GLU", 120.0)

        val priced = assertNotNull(repo.testByCode("GLU"))
        assertEquals(120.0, priced.price)
        // The dataset's clinical content is untouched — this is the whole point
        // of a price-only write.
        assertEquals("Test GLU", priced.name)
        assertEquals("Biochemistry", priced.category)
        assertEquals("serum", priced.sampleType)
        assertTrue(priced.active)
        val param = priced.parameters.single()
        assertEquals("mg/dL", param.unit)
        assertEquals(70.0, param.ranges.single().low)
        assertEquals(110.0, param.ranges.single().high)
    }

    @Test
    fun `a negative price is floored at zero rather than stored`() = runBlocking {
        val repo = repo()
        repo.upsertTest(imported("URE").copy(price = 90.0))

        repo.setTestPrice("t-URE", -50.0)

        assertEquals(0.0, assertNotNull(repo.testByCode("URE")).price)
    }

    @Test
    fun `the unpriced count is what a freshly imported menu looks like`() = runBlocking {
        val repo = repo()
        listOf("A", "B", "C").forEach { repo.upsertTest(imported(it)) }
        assertEquals(3, repo.countUnpricedTests())

        repo.setTestPrice("t-A", 150.0)
        repo.setTestPrice("t-B", 200.0)
        assertEquals(1, repo.countUnpricedTests())

        // Zeroing one puts it back in the queue — the count is a live view of
        // the menu, not a one-time import tally.
        repo.setTestPrice("t-A", 0.0)
        assertEquals(2, repo.countUnpricedTests())
    }

    // ── The 40-test starter catalog is gone. It can't ship any more (the file
    // lives in desktopTest), but machines that already seeded it still carry
    // its rows, so the app sweeps them out at launch. ──

    private fun seedRow(code: String) = imported(code).copy(id = "seed-${code.lowercase()}")

    @Test
    fun `a starter-catalog test nothing ever ordered is deleted`() = runBlocking {
        val repo = repo()
        repo.upsertTest(seedRow("CBC"))

        val (deleted, kept) = repo.retireLegacySeedCatalog()

        assertEquals(1, deleted)
        assertEquals(0, kept)
        assertNull(repo.testByCode("CBC"))
        assertEquals(0L, repo.countTests())
    }

    @Test
    fun `a starter-catalog test an old order names survives, deactivated and out of the way`() = runBlocking {
        val repo = repo()
        repo.upsertTest(seedRow("CBC"))
        val patient = repo.upsertPatient(Patient(id = "p1", name = "Old Order", sex = "M", ageYears = 40))
        repo.createLabOrder(patient.id, testIds = listOf("seed-cbc")).getOrThrow()

        val (deleted, kept) = repo.retireLegacySeedCatalog()

        assertEquals(0, deleted)
        assertEquals(1, kept)
        // Results and graphs key on test_id — the row has to stay, or a
        // finished report is stranded.
        val row = assertNotNull(repo.testById("seed-cbc"))
        assertFalse(row.active, "a retired row must never be orderable again")
        // …and its code must stop squatting the master catalog's CBC.
        assertEquals("CBC-OLD", row.code)
        assertNull(repo.testByCode("CBC"))
    }

    @Test
    fun `the sweep runs at every launch and does nothing once it is done`() = runBlocking {
        val repo = repo()
        repo.upsertTest(seedRow("CBC"))
        val patient = repo.upsertPatient(Patient(id = "p1", name = "Old Order", sex = "M", ageYears = 40))
        repo.createLabOrder(patient.id, testIds = listOf("seed-cbc")).getOrThrow()
        repo.upsertTest(imported("GLU"))

        repo.retireLegacySeedCatalog()
        val second = repo.retireLegacySeedCatalog()

        assertEquals(0 to 1, second)
        // Master rows are none of the sweep's business.
        assertNotNull(repo.testByCode("GLU"))
        // No "CBC-OLD-OLD": a second pass must not rename what it renamed.
        assertEquals("CBC-OLD", assertNotNull(repo.testById("seed-cbc")).code)
    }

    @Test
    fun `a fresh install has nothing to sweep`() = runBlocking {
        val repo = repo()
        repo.upsertTest(imported("GLU"))
        assertEquals(0 to 0, repo.retireLegacySeedCatalog())
        assertEquals(1L, repo.countTests())
    }
}
