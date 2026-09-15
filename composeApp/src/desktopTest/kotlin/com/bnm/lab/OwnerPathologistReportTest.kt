package com.bnm.lab

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.bnm.lab.api.ApiClient
import com.bnm.lab.db.AppDatabase
import com.bnm.lab.lab.LabRepository
import com.bnm.lab.lab.LabTest
import com.bnm.lab.lab.Patient
import com.bnm.lab.lab.RefRange
import com.bnm.lab.lab.TestParameter
import com.bnm.lab.print.renderLabReport
import com.bnm.lab.report.ReportAssembler
import com.bnm.lab.report.Signatory
import com.bnm.lab.report.writeLabReportPdf
import com.bnm.lab.staff.StaffRepository
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An owner who is also the lab's pathologist signs reports as a named
 * pathologist. The word "Owner" appears nowhere on the paper — not on the A4
 * PDF, not on the thermal slip — even while the account still carries the
 * seeded "Lab Owner" name.
 */
class OwnerPathologistReportTest {

    @Test
    fun `placeholder names are recognised and never printable`() {
        assertTrue(Signatory.isPlaceholder("Lab Owner"))
        assertTrue(Signatory.isPlaceholder("  lab owner "))
        assertTrue(Signatory.isPlaceholder("Owner"))
        assertFalse(Signatory.isPlaceholder("Dr. Meena Iyer"))
        assertNull(Signatory.printable("Lab Owner"))
        assertNull(Signatory.printable(""))
        assertEquals("Dr. Meena Iyer", Signatory.printable(" Dr. Meena Iyer "))
        assertNotNull(Signatory.pathologistNameProblem("Lab Owner"))
        assertNotNull(Signatory.pathologistNameProblem(""))
        assertNull(Signatory.pathologistNameProblem("Dr. Meena Iyer"))
    }

    @Test
    fun `an owner-pathologist's report never says Owner, before or after they enter their name`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        val db = AppDatabase(driver)
        val repo = LabRepository(db, ApiClient.json)
        val staff = StaffRepository(db, ApiClient.json)

        // The seeded owner, marked as the pathologist while still named "Lab Owner"
        // (e.g. ticked by an earlier build, or synced in from another seat).
        staff.seedOwnerIfEmpty("Sunrise Lab")
        val owner = staff.upsert(staff.byId(StaffRepository.DEFAULT_OWNER_ID)!!.copy(alsoPathologist = true))

        repo.upsertTest(LabTest(id = "t-glu", code = "GLU", name = "Glucose", price = 100.0,
            parameters = listOf(TestParameter(key = "glu", name = "Glucose", unit = "mg/dL", decimals = 0,
                ranges = listOf(RefRange(low = 70.0, high = 100.0))))))
        val patient = repo.upsertPatient(Patient(id = "p1", name = "Asha", sex = "F", ageYears = 30))
        val order = repo.createLabOrder(patient.id, testIds = listOf("t-glu")).getOrThrow()
        repo.enterResult(order.id, "t-glu", "glu", "90", enteredBy = owner.name).getOrThrow()
        repo.verifyOrder(order.id, owner.name, owner.id).getOrThrow()
        repo.approveOrder(order.id, owner.name, owner.id).getOrThrow()

        val assembler = ReportAssembler(repo, staff)
        fun pdfText(): String {
            val doc = runBlocking { assembler.assemble(order.id, labName = "Sunrise Lab", stampReportedNow = false)!! }
            return PDDocument.load(File(writeLabReportPdf(doc))).use { PDFTextStripper().getText(it) }
        }
        fun slip(): String = runBlocking {
            val rows = repo.resultsForOrder(order.id)
            renderLabReport(
                "Sunrise Lab", repo.orderById(order.id)!!, patient, repo.orderTests(order.id), rows,
                verifiedByName = rows.firstNotNullOfOrNull { it.verifiedById }?.let { staff.byId(it)?.name },
                approvedByName = rows.firstNotNullOfOrNull { it.approvedById }?.let { staff.byId(it)?.name },
            )
        }

        // Still "Lab Owner": the sign-off carries no name rather than the placeholder.
        val beforeDoc = assembler.assemble(order.id, labName = "Sunrise Lab", stampReportedNow = false)!!
        assertNull(beforeDoc.approvedBy)
        assertNull(beforeDoc.verifiedBy)
        assertFalse("Owner" in pdfText(), pdfText())
        assertFalse("Owner" in slip(), slip())

        // The owner enters their name as the pathologist — reprints follow it.
        staff.upsert(staff.byId(owner.id)!!.copy(name = "Dr. Meena Iyer", qualifications = "MD (Pathology)"))
        val pdf = pdfText()
        assertTrue("Dr. Meena Iyer" in pdf, pdf)
        assertTrue("MD (Pathology)" in pdf, pdf)
        assertFalse("Owner" in pdf, pdf)
        val after = slip()
        assertTrue("Dr. Meena Iyer" in after, after)
        assertFalse("Owner" in after, after)
    }
}
