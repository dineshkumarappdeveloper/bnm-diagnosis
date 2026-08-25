package com.bnm.diagnosis

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.bnm.diagnosis.api.ApiClient
import com.bnm.diagnosis.db.AppDatabase
import com.bnm.diagnosis.db.addColumn
import com.bnm.diagnosis.lab.EmrTestMatchKind
import com.bnm.diagnosis.lab.LabRepository
import com.bnm.diagnosis.lab.LabTest
import com.bnm.diagnosis.lab.Patient
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P3b EMR-inbox identity: the clinic now sends WHO the order is for and WHICH
 * catalog entry the doctor picked, so the desk stops retyping the patient and
 * stops guessing the test by name. Everything here runs against a REAL
 * in-memory SQLDelight DB with no network — that is the whole point of the
 * feature (the walk-in is registered off the locally-synced row).
 */
class EmrIdentityTest {

    private fun freshDb(): AppDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        return AppDatabase(driver)
    }

    private fun AppDatabase.putEmrRow(
        id: String,
        testName: String,
        testCode: String? = null,
        visitNumber: String? = null,
        patientName: String? = null,
        patientPhone: String? = null,
        patientSex: String? = null,
        patientDob: String? = null,
        seq: Long = 1,
    ) = emrInboxQueries.insert(
        id, "visit-$id", testName, null, "ordered", "pending", null, seq, 0L,
        "2026-08-26T09:00:00Z", testCode, visitNumber, patientName, patientPhone, patientSex, patientDob,
    )

    // ── 1. Age derived from the clinic's dob ─────────────────────────────────

    @Test
    fun dobPrefillsWholeYears_atAFixedToday() {
        // The row the ticket describes: dob 1990-05-10, sex F.
        val dob = "1990-05-10"
        // Birthday already passed this year.
        assertEquals(36L, LabRepository.ageYearsFromDob(dob, LocalDate(2026, 8, 26)))
        // On the birthday itself the patient IS the next age.
        assertEquals(36L, LabRepository.ageYearsFromDob(dob, LocalDate(2026, 5, 10)))
        // The day before, they are not.
        assertEquals(35L, LabRepository.ageYearsFromDob(dob, LocalDate(2026, 5, 9)))
        // A full ISO timestamp is accepted (only the date part is read).
        assertEquals(36L, LabRepository.ageYearsFromDob("1990-05-10T00:00:00Z", LocalDate(2026, 8, 26)))
        // Nothing to derive from → blank age field, never an invented number.
        assertNull(LabRepository.ageYearsFromDob(null, LocalDate(2026, 8, 26)))
        assertNull(LabRepository.ageYearsFromDob("", LocalDate(2026, 8, 26)))
        assertNull(LabRepository.ageYearsFromDob("not-a-date", LocalDate(2026, 8, 26)))
        assertNull(LabRepository.ageYearsFromDob("2030-01-01", LocalDate(2026, 8, 26)))
    }

    @Test
    fun inboxRowCarriesDemographics_andPrefillsAge() = runBlocking {
        val db = freshDb()
        val repo = LabRepository(db, ApiClient.json)
        db.putEmrRow(
            id = "emr-1", testName = "Complete Blood Count", testCode = "CBC", visitNumber = "V-2026-114",
            patientName = "Meera Nair", patientPhone = "+91 98765 43210", patientSex = "F", patientDob = "1990-05-10",
        )

        val row = repo.emrById("emr-1")!!
        assertEquals("Meera Nair", row.patientName)
        assertEquals("+91 98765 43210", row.patientPhone)
        assertEquals("F", row.patientSex)
        assertEquals("1990-05-10", row.patientDob)
        assertEquals("V-2026-114", row.visitNumber)
        assertEquals("CBC", row.testCode)
        assertTrue(row.hasIdentity)
        // What the new-patient form pre-types into "Age (years)".
        assertEquals(36L, LabRepository.ageYearsFromDob(row.patientDob, LocalDate(2026, 8, 26)))

        // A legacy row (no identity block) still reads fine and asks for nothing.
        db.putEmrRow(id = "emr-legacy", testName = "Lipid Profile", seq = 2)
        val legacy = repo.emrById("emr-legacy")!!
        assertNull(legacy.patientName)
        assertNull(legacy.testCode)
        assertTrue(!legacy.hasIdentity)
    }

    // ── 2. Code match beats name match ───────────────────────────────────────

    @Test
    fun codeMatchBeatsNameMatch_whenBothArePossible() = runBlocking {
        val repo = LabRepository(freshDb(), ApiClient.json)
        // The trap: one test is NAMED "CBC", another is CODED "CBC".
        repo.upsertTest(LabTest(id = "t-cbc", code = "CBC", name = "Complete Blood Count"))
        repo.upsertTest(LabTest(id = "t-decoy", code = "XCBC", name = "CBC"))
        val tests = repo.listTests()

        // The doctor picked the catalog entry — code wins, decoy loses.
        val byCode = LabRepository.matchEmrTest(tests, testCode = "CBC", testName = "CBC")
        assertEquals("t-cbc", byCode.test?.id)
        assertEquals(EmrTestMatchKind.CODE, byCode.kind)
        assertEquals("matched Complete Blood Count (CBC) by code", byCode.label())
        // Codes are matched case-insensitively but still EXACTLY.
        assertEquals("t-cbc", LabRepository.matchEmrTest(tests, "cbc", null).test?.id)

        // Legacy free-text row: no code, so the old name matching stands and
        // the decoy (whose NAME is "CBC") is the right answer.
        val byName = LabRepository.matchEmrTest(tests, testCode = null, testName = "CBC")
        assertEquals("t-decoy", byName.test?.id)
        assertEquals(EmrTestMatchKind.NAME, byName.kind)

        // An unknown code falls back to name matching rather than giving up.
        val fallback = LabRepository.matchEmrTest(tests, testCode = "NOPE", testName = "Complete Blood Count")
        assertEquals("t-cbc", fallback.test?.id)
        assertEquals(EmrTestMatchKind.NAME, fallback.kind)

        // Substring is the last resort and is labelled as such.
        val fuzzy = LabRepository.matchEmrTest(tests, null, "Complete Blood Count (EDTA)")
        assertEquals("t-cbc", fuzzy.test?.id)
        assertEquals(EmrTestMatchKind.FUZZY, fuzzy.kind)

        // Nothing at all → the order note carries the doctor's words.
        val none = LabRepository.matchEmrTest(tests, "ZZZ", "Serum Unobtainium")
        assertNull(none.test)
        assertEquals(EmrTestMatchKind.NONE, none.kind)
        assertEquals("no catalog match — added as a note", none.label())

        // Same answer through the repository's catalog-loading wrapper.
        val row = com.bnm.diagnosis.lab.EmrInboxItem(
            id = "e", visitId = null, testName = "CBC", instructions = null, status = null,
            labStatus = null, accessionNo = null, matchedOrderId = null, testCode = "CBC",
        )
        assertEquals("t-cbc", repo.resolveEmrTest(row).test?.id)
    }

    // ── 3. Patient identified by phone, on digits only ───────────────────────

    @Test
    fun phoneIdentifiesThePatient_onNormalizedDigits() = runBlocking {
        val repo = LabRepository(freshDb(), ApiClient.json)
        repo.upsertPatient(Patient(id = "p1", name = "Meera Nair", sex = "F", ageYears = 36, phone = "9876543210"))
        repo.upsertPatient(Patient(id = "p2", name = "Anil Kumar", sex = "M", ageYears = 50, phone = "+91 90000 11111"))
        repo.upsertPatient(Patient(id = "p3", name = "No Phone", sex = "O", ageYears = 20, phone = null))

        // Every way a clinic might write the same Indian mobile → one patient.
        listOf("9876543210", "+91 98765 43210", "+919876543210", "098765 43210", "91-9876543210").forEach { form ->
            assertEquals(listOf("p1"), repo.patientsByPhone(form).map { it.id }, "form=$form")
        }
        // …and the stored side may be the punctuated one.
        assertEquals(listOf("p2"), repo.patientsByPhone("9000011111").map { it.id })

        // A PREFIX is not an identity — never silently preselect on one.
        assertTrue(repo.patientsByPhone("987654").isEmpty())
        assertTrue(repo.patientsByPhone("98765").isEmpty())
        // Too short / absent / non-numeric identifies nobody.
        assertTrue(repo.patientsByPhone(null).isEmpty())
        assertTrue(repo.patientsByPhone("").isEmpty())
        assertTrue(repo.patientsByPhone("n/a").isEmpty())
        assertNull(LabRepository.normalizePhone("12345"))
        assertEquals("9876543210", LabRepository.normalizePhone("+91 98765 43210"))

        // A shared family phone returns BOTH — the desk must ask, not guess.
        repo.upsertPatient(Patient(id = "p4", name = "Ravi Nair", sex = "M", ageYears = 12, phone = "9876543210"))
        assertEquals(setOf("p1", "p4"), repo.patientsByPhone("+91 98765 43210").map { it.id }.toSet())

        // A soft-deleted patient stops being an identity.
        repo.softDeletePatient("p2")
        assertTrue(repo.patientsByPhone("9000011111").isEmpty())
    }

    // ── 4. The pull must not clobber local (or already-landed) state ─────────

    @Test
    fun updateFromServer_keepsLocalTrio_andNeverNullsIdentity() {
        val db = freshDb()
        val q = db.emrInboxQueries
        db.putEmrRow(
            id = "emr-9", testName = "Complete Blood Count", testCode = "CBC", visitNumber = "V-9",
            patientName = "Meera Nair", patientPhone = "9876543210", patientSex = "F", patientDob = "1990-05-10",
        )
        q.setMatched("local-order-1", "ACC-S1-00042", "emr-9")
        q.markStatusPushed("emr-9")

        // A sweep from a server that has NOT shipped the identity block yet:
        // all six arrive null and must leave what we already have alone.
        q.updateFromServer(
            visit_id = "visit-emr-9", test_name = "Complete Blood Count", instructions = "fasting",
            status = "ordered", lab_status = "in_progress", accession_no = "ACC-S1-00042", seq = 7, done = 0,
            test_code = null, visit_number = null, patient_name = null, patient_phone = null,
            patient_sex = null, patient_dob = null, id = "emr-9",
        )

        val row = q.byId("emr-9").executeAsOne()
        // LOCAL trio survives the pull, exactly as before this change.
        assertEquals("local-order-1", row.matched_order_id)
        assertEquals(1L, row.status_pushed)
        assertEquals(0L, row.done)
        // Identity survives a null-carrying refresh.
        assertEquals("CBC", row.test_code)
        assertEquals("Meera Nair", row.patient_name)
        assertEquals("1990-05-10", row.patient_dob)
        // Server fields do update.
        assertEquals(7L, row.seq)
        assertEquals("fasting", row.instructions)

        // And a later sweep that DOES carry the block overwrites it.
        q.updateFromServer(
            visit_id = "visit-emr-9", test_name = "Complete Blood Count", instructions = "fasting",
            status = "ordered", lab_status = "in_progress", accession_no = "ACC-S1-00042", seq = 8, done = 0,
            test_code = "CBC2", visit_number = "V-9b", patient_name = "Meera R Nair", patient_phone = "9876543210",
            patient_sex = "F", patient_dob = "1990-05-10", id = "emr-9",
        )
        assertEquals("CBC2", q.byId("emr-9").executeAsOne().test_code)
        assertEquals("Meera R Nair", q.byId("emr-9").executeAsOne().patient_name)
    }

    // ── 5. Devices whose emr_inbox predates the identity columns ─────────────

    @Test
    fun legacyTableSelfHeals_toTheSameColumnOrder() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        // Re-create emr_inbox exactly as an already-installed device has it.
        driver.execute(null, "DROP TABLE emr_inbox", 0)
        driver.execute(null,
            "CREATE TABLE emr_inbox (id TEXT NOT NULL PRIMARY KEY, visit_id TEXT, " +
            "test_name TEXT NOT NULL, instructions TEXT, status TEXT, lab_status TEXT, accession_no TEXT, " +
            "matched_order_id TEXT, seq INTEGER NOT NULL DEFAULT 0, done INTEGER NOT NULL DEFAULT 0, " +
            "status_pushed INTEGER NOT NULL DEFAULT 0, created_at TEXT)", 0)

        // The self-heal, run TWICE — the second pass must be a harmless no-op.
        repeat(2) {
            driver.addColumn("emr_inbox", "test_code", "TEXT")
            driver.addColumn("emr_inbox", "visit_number", "TEXT")
            driver.addColumn("emr_inbox", "patient_name", "TEXT")
            driver.addColumn("emr_inbox", "patient_phone", "TEXT")
            driver.addColumn("emr_inbox", "patient_sex", "TEXT")
            driver.addColumn("emr_inbox", "patient_dob", "TEXT")
        }

        // SELECT * maps positionally, so the upgraded table must behave like a
        // freshly created one end to end.
        val db = AppDatabase(driver)
        db.putEmrRow(
            id = "emr-old", testName = "Lipid Profile", testCode = "LIPID", visitNumber = "V-1",
            patientName = "Anil Kumar", patientPhone = "9000011111", patientSex = "M", patientDob = "1976-01-02",
        )
        val row = LabRepository(db, ApiClient.json).emrById("emr-old")!!
        assertEquals("Lipid Profile", row.testName)
        assertEquals("LIPID", row.testCode)
        assertEquals("Anil Kumar", row.patientName)
        assertEquals("M", row.patientSex)
        assertEquals("1976-01-02", row.patientDob)
        assertEquals(50L, LabRepository.ageYearsFromDob(row.patientDob, LocalDate(2026, 8, 26)))
    }

    // ── 6. The inbox search the desk types into ──────────────────────────────

    @Test
    fun inboxSearchBlob_findsTheWalkInByEveryHandle() = runBlocking {
        val db = freshDb()
        val repo = LabRepository(db, ApiClient.json)
        db.putEmrRow(
            id = "emr-a", testName = "Complete Blood Count", testCode = "CBC", visitNumber = "V-2026-114",
            patientName = "Meera Nair", patientPhone = "+91 98765 43210", patientSex = "F", patientDob = "1990-05-10",
        )
        db.putEmrRow(id = "emr-b", testName = "Lipid Profile", seq = 2)

        val rows = repo.emrOpen()
        assertEquals(2, rows.size)
        val a = rows.first { it.id == "emr-a" }
        val blob = a.searchBlob()
        listOf("meera", "nair", "9876543210", "98765 43210", "v-2026-114", "complete blood", "cbc")
            .forEach { assertTrue(blob.contains(it), "search blob should contain '$it': $blob") }
        // A legacy row is still findable by its test name.
        assertTrue(rows.first { it.id == "emr-b" }.searchBlob().contains("lipid"))
    }
}
