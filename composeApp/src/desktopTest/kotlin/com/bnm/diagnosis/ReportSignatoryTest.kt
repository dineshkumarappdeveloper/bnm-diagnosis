package com.bnm.diagnosis

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.bnm.diagnosis.api.ApiClient
import com.bnm.diagnosis.db.AppDatabase
import com.bnm.diagnosis.lab.LabRepository
import com.bnm.diagnosis.lab.LabTest
import com.bnm.diagnosis.lab.Patient
import com.bnm.diagnosis.lab.RefRange
import com.bnm.diagnosis.lab.TestParameter
import com.bnm.diagnosis.report.ReportAssembler
import com.bnm.diagnosis.staff.Staff
import com.bnm.diagnosis.staff.StaffRepository
import com.bnm.diagnosis.staff.StaffRole
import com.bnm.diagnosis.sync.toStaff
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Who signs a report, and how the app finds THEIR signature.
 *
 * Two things the review of the verifier-signature change proved were wrong:
 * the staff sync doc silently dropped the signature (and the login), and the
 * name-only lookup could print a namesake's ink. Both are pinned here.
 */
@OptIn(ExperimentalEncodingApi::class)
class ReportSignatoryTest {

    private fun freshDb(): AppDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        return AppDatabase(driver)
    }

    private fun png(label: String) = Base64.Default.encode(label.encodeToByteArray())

    @Test
    fun `the staff push doc carries every column the apply side reads`() {
        // A row with all twelve columns set. Everything must survive
        // row → model → JSON → model, or a seat elsewhere applies nulls.
        val row = com.bnm.diagnosis.db.Staff(
            id = "s1", name = "S. Kumar", role = StaffRole.TECHNICIAN, pin_hash = "s1-salt-hash",
            active = 1L, created_at = "2026-01-01T00:00:00Z", updated_at = "2026-09-07T00:00:00Z", deleted_at = null,
            username = "skumar", signature_png = png("ink"), qualifications = "DMLT", registration_no = "TN/PMC/1",
        )
        val json = ApiClient.json
        val roundTrip = json.decodeFromJsonElement(Staff.serializer(), json.encodeToJsonElement(Staff.serializer(), row.toStaff()))
        assertEquals(
            Staff(
                id = "s1", name = "S. Kumar", role = StaffRole.TECHNICIAN, pinHash = "s1-salt-hash", active = true,
                createdAt = "2026-01-01T00:00:00Z", updatedAt = "2026-09-07T00:00:00Z", deletedAt = null,
                username = "skumar", signaturePng = png("ink"), qualifications = "DMLT", registrationNo = "TN/PMC/1",
            ),
            roundTrip,
        )
    }

    @Test
    fun `the signature follows the staff id stamped at sign-off, not whoever has the name today`() = runBlocking {
        val db = freshDb()
        val repo = LabRepository(db, ApiClient.json)
        val staff = StaffRepository(db, ApiClient.json)

        // Yesterday's technician, retired, and today's hire with the SAME name.
        val retired = staff.upsert(Staff(id = "", name = "S. Kumar", role = StaffRole.TECHNICIAN,
            signaturePng = png("old-ink"), qualifications = "DMLT", active = false))
        val hired = staff.upsert(Staff(id = "", name = "S. Kumar", role = StaffRole.TECHNICIAN,
            signaturePng = png("new-ink"), qualifications = "B.Sc. (MLT)"))
        val pathologist = staff.upsert(Staff(id = "", name = "Dr. A. Lakshmi", role = StaffRole.PATHOLOGIST,
            signaturePng = png("path-ink"), qualifications = "MD (Path)", registrationNo = "TN/12345"))
        val assembler = ReportAssembler(repo, staff)

        // Id wins, whatever the name lookup would have said.
        assertEquals("DMLT", assembler.signatureFor("S. Kumar", retired.id)?.qualifications)
        assertEquals("B.Sc. (MLT)", assembler.signatureFor("S. Kumar", hired.id)?.qualifications)
        // Rows from before ids were stamped: name match, active row first.
        assertEquals("B.Sc. (MLT)", assembler.signatureFor("S. Kumar", null)?.qualifications)
        assertEquals("B.Sc. (MLT)", assembler.signatureFor("s. kumar ", "")?.qualifications)
        // An id nobody has any more falls back to the name; an unknown name is nothing.
        assertEquals("B.Sc. (MLT)", assembler.signatureFor("S. Kumar", "gone")?.qualifications)
        assertNull(assembler.signatureFor("Nobody", null))

        // End to end through the repository: Verify/Approve stamp the ids, and
        // the assembled report carries the RETIRED namesake's credentials
        // because that is who actually verified.
        repo.upsertTest(LabTest(id = "t-sig-glu", code = "SGLU", name = "Glucose", price = 50.0,
            parameters = listOf(TestParameter(key = "glu", name = "Glucose", unit = "mg/dL", decimals = 0,
                ranges = listOf(RefRange(low = 70.0, high = 100.0))))))
        val patient = repo.upsertPatient(Patient(id = "pat-sig", name = "Sig Patient", sex = "M", ageYears = 40))
        val order = repo.createLabOrder(patient.id, testIds = listOf("t-sig-glu")).getOrThrow()
        repo.enterResult(order.id, "t-sig-glu", "glu", "90", enteredBy = "S. Kumar").getOrThrow()
        repo.verifyOrder(order.id, "S. Kumar", retired.id).getOrThrow()
        repo.approveOrder(order.id, "Dr. A. Lakshmi", pathologist.id).getOrThrow()
        val rows = repo.resultsForOrder(order.id)
        assertEquals(retired.id, rows.single().verifiedById)
        assertEquals(pathologist.id, rows.single().approvedById)

        val doc = assembler.assemble(order.id, labName = "Sig Lab", stampReportedNow = false)!!
        assertEquals("S. Kumar", doc.verifiedBy)
        assertEquals("DMLT", doc.verifierSignature?.qualifications, "the retired namesake's ink — by id")
        assertEquals("MD (Path)", doc.signature?.qualifications)
    }
}
