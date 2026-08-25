package com.bnm.diagnosis

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.bnm.diagnosis.api.ApiClient
import com.bnm.diagnosis.db.AppDatabase
import com.bnm.diagnosis.lab.LabRepository
import com.bnm.diagnosis.lab.LabTest
import com.bnm.diagnosis.lab.Patient
import com.bnm.diagnosis.lab.RefRange
import com.bnm.diagnosis.lab.SeedCatalog
import com.bnm.diagnosis.lab.TestParameter
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The results grid must show a parameter's reference range BEFORE anything is
 * typed — the frozen `ref_display` only exists after entry, so the technician
 * used to type blind. [LabRepository.refDisplayFor] resolves the very range the
 * entry path will freeze, from the catalog + the patient's age/sex.
 *
 * Sex-split Haemoglobin (13-17 male, 12-15 female) from the SEEDED catalog is
 * the canonical proof: one catalog parameter, two different printed ranges
 * depending on who is in the chair.
 */
class RefRangePreviewTest {

    private fun freshDb(): AppDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        return AppDatabase(driver)
    }

    private fun adult(sex: String) = Patient(id = "p-$sex", name = "Adult $sex", sex = sex, ageYears = 34)

    @Test
    fun refDisplayFor_splitsBySex_beforeAnyValueIsEntered() = runBlocking {
        val repo = LabRepository(freshDb(), ApiClient.json)
        SeedCatalog.seedIfEmpty(repo)
        val hb = assertNotNull(repo.testByCode("HB"), "seed catalog must carry the Haemoglobin test")
        val param = hb.parameters.single()

        assertEquals("13 - 17", LabRepository.refDisplayFor(hb, param, adult("M")))
        assertEquals("12 - 15", LabRepository.refDisplayFor(hb, param, adult("F")))

        // The picked range carries the criticals the live flag chip needs.
        val male = LabRepository.rangeFor(hb, param, adult("M"))
        assertEquals(13.0, male?.low)
        assertEquals(17.0, male?.high)
        assertEquals("H", LabRepository.computeFlag("18.2", male))
        assertEquals("CL", LabRepository.computeFlag("6.4", male))
        // 12.5 is normal for a woman and LOW for a man — the preview has to be
        // patient-specific, not one catalog string for everybody.
        assertEquals("L", LabRepository.computeFlag("12.5", male))
        assertEquals("N", LabRepository.computeFlag("12.5", LabRepository.rangeFor(hb, param, adult("F"))))
    }

    @Test
    fun refDisplayFor_handlesAgeBands_openEndsAndNoMatch() {
        val test = LabTest(
            id = "t-band", code = "XBAND", name = "Banded",
            parameters = listOf(
                TestParameter(
                    key = "v", name = "Value", unit = "mg/dL", decimals = 0,
                    ranges = listOf(
                        RefRange(ageMinY = 0.0, ageMaxY = 12.0, low = 3.0, high = 5.0),
                        RefRange(ageMinY = 12.0, high = 200.0),
                    ),
                ),
                // No ranges at all → nothing to print.
                TestParameter(key = "n", name = "Note", ranges = emptyList()),
            ),
        )
        val child = Patient(id = "p-c", name = "Child", sex = "M", ageYears = 8)
        val grown = Patient(id = "p-a", name = "Adult", sex = "M", ageYears = 40)

        assertEquals("3 - 5", LabRepository.refDisplayFor(test, test.parameters[0], child))
        assertEquals("< 200", LabRepository.refDisplayFor(test, test.parameters[0], grown))
        assertEquals(LabRepository.NO_RANGE, LabRepository.refDisplayFor(test, test.parameters[1], grown))
        assertNull(LabRepository.rangeFor(test, test.parameters[1], grown))
    }
}
