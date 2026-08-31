package com.bnm.diagnosis

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.bnm.diagnosis.api.ApiClient
import com.bnm.diagnosis.db.AppDatabase
import com.bnm.diagnosis.lab.LabRepository
import com.bnm.diagnosis.lab.Patient
import com.bnm.diagnosis.lab.Referrer
import com.bnm.diagnosis.lab.SeedCatalog
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression cover for the cross-tenant leak found in the field.
 *
 * Activating a DIFFERENT licence used to rewrite only the licence prefs, leaving
 * the previous lab's patients, orders, results and staff in the local database.
 * They then showed under the new lab's name AND were pushed into the new lab's
 * tenant on the next sync — patient data and a staff pin hash crossing labs.
 */
class TenantResetTest {

    private fun freshDb(): AppDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        return AppDatabase(driver)
    }

    @Test
    fun `reset empties every tenant-scoped table`() = runBlocking {
        val db = freshDb()
        val repo = LabRepository(db, ApiClient.json)

        // Populate the shapes a real lab accumulates.
        val p = repo.upsertPatient(Patient(id = "", name = "Alaguraj", sex = "M", phone = "9622589653"))
        repo.upsertReferrer(Referrer(id = "", name = "Dr Sathish", kind = "doctor", commissionPct = 10.0))
        SeedCatalog.seedIfEmpty(repo)
        val tests = repo.listTests()
        assertTrue(tests.isNotEmpty(), "catalog should have seeded")
        repo.createLabOrder(patientId = p.id, testIds = listOf(tests.first().id)).getOrThrow()

        val before = repo.tenantRowCounts()
        assertTrue(before.patients > 0 && before.orders > 0 && before.tests > 0,
            "fixture should not be empty: $before")

        repo.resetForNewTenant()

        val after = repo.tenantRowCounts()
        assertEquals(0L, after.patients, "patients must be gone")
        assertEquals(0L, after.orders, "orders must be gone")
        assertEquals(0L, after.results, "results must be gone")
        assertEquals(0L, after.tests, "catalog must be gone")
        assertEquals(0L, after.staff, "staff must be gone")
        assertTrue(after.isEmpty, "summary should report empty")
    }

    @Test
    fun `accession numbering restarts for the new lab`() = runBlocking {
        val db = freshDb()
        val repo = LabRepository(db, ApiClient.json)
        SeedCatalog.seedIfEmpty(repo)
        val testId = repo.listTests().first().id
        val p1 = repo.upsertPatient(Patient(id = "", name = "First", sex = "M"))
        val first = repo.createLabOrder(patientId = p1.id, testIds = listOf(testId)).getOrThrow()

        repo.resetForNewTenant()

        SeedCatalog.seedIfEmpty(repo)
        val p2 = repo.upsertPatient(Patient(id = "", name = "Second", sex = "F"))
        val afterReset = repo.createLabOrder(
            patientId = p2.id, testIds = listOf(repo.listTests().first().id),
        ).getOrThrow()

        // Without wiping accession_series the new lab would continue the old
        // lab's sequence, and its very first report would not be …00001.
        assertEquals(first.accessionNo, afterReset.accessionNo,
            "a reset lab must restart numbering, so its first accession matches the original first")
    }

    @Test
    fun `sync watermarks are cleared so the new tenant pushes from scratch`() = runBlocking {
        val db = freshDb()
        val repo = LabRepository(db, ApiClient.json)
        db.syncStateQueries.upsertState("patient", "4242", "2026-08-01T00:00:00Z")
        assertTrue(db.syncStateQueries.getState("patient").executeAsOneOrNull() != null)

        repo.resetForNewTenant()

        // A watermark left ahead of an empty tenant means the new lab's first
        // genuine rows are never pushed at all.
        assertEquals(null, db.syncStateQueries.getState("patient").executeAsOneOrNull(),
            "watermarks must not survive a tenant switch")
    }
}
