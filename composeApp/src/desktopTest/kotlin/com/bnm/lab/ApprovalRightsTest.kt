package com.bnm.lab

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.bnm.lab.api.ApiClient
import com.bnm.lab.db.AppDatabase
import com.bnm.lab.db.addColumn
import com.bnm.lab.lab.LabRepository
import com.bnm.lab.lab.LabStatus
import com.bnm.lab.lab.LabTest
import com.bnm.lab.lab.Patient
import com.bnm.lab.lab.RefRange
import com.bnm.lab.lab.TestParameter
import com.bnm.lab.staff.Staff
import com.bnm.lab.staff.StaffRepository
import com.bnm.lab.staff.StaffRole
import com.bnm.lab.sync.applyPulledStaff
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Only a pathologist approves. An owner is not a pathologist by owning the lab —
 * but an owner can be one, and then approves. Enforced where results are
 * signed, not only by which buttons show.
 */
class ApprovalRightsTest {

    private class Lab {
        val db: AppDatabase = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).let { AppDatabase.Schema.create(it); AppDatabase(it) }
        val repo = LabRepository(db, ApiClient.json)
        val staff = StaffRepository(db, ApiClient.json)

        /** An order walked to VERIFIED, ready for someone to approve. */
        fun verifiedOrder(): String = runBlocking {
            if (repo.testById("t-glu") == null) {
                repo.upsertTest(LabTest(id = "t-glu", code = "GLU", name = "Glucose", price = 100.0,
                    parameters = listOf(TestParameter(key = "glu", name = "Glucose", unit = "mg/dL", decimals = 0,
                        ranges = listOf(RefRange(low = 70.0, high = 100.0))))))
                repo.upsertPatient(Patient(id = "p1", name = "Asha", sex = "F", ageYears = 30))
            }
            val o = repo.createLabOrder("p1", testIds = listOf("t-glu")).getOrThrow()
            repo.enterResult(o.id, "t-glu", "glu", "90").getOrThrow()
            repo.verifyOrder(o.id, "Tech").getOrThrow()
            o.id
        }
    }

    @Test
    fun `an owner who is not marked as the pathologist cannot approve — not even by calling the repository`() = runBlocking {
        val lab = Lab()
        val owner = lab.staff.upsert(Staff(id = "", name = "Meena (owner)", role = StaffRole.OWNER))
        val order = lab.verifiedOrder()

        val refused = lab.repo.approveOrder(order, owner.name, owner.id)
        assertTrue(refused.isFailure)
        assertEquals(LabRepository.ONLY_PATHOLOGIST, refused.exceptionOrNull()?.message)
        assertEquals(LabStatus.VERIFIED, lab.repo.orderById(order)!!.status)
        assertTrue(lab.repo.approveTest(order, "t-glu", owner.name, owner.id).isFailure, "per-test approval too")
    }

    @Test
    fun `an owner marked as the lab's pathologist approves, and the stamp names them from the staff table`() = runBlocking {
        val lab = Lab()
        val owner = lab.staff.upsert(Staff(id = "", name = "Dr. Meena Iyer", role = StaffRole.OWNER, alsoPathologist = true))
        val order = lab.verifiedOrder()

        lab.repo.approveOrder(order, "whatever the screen typed", owner.id).getOrThrow()
        assertEquals(LabStatus.APPROVED, lab.repo.orderById(order)!!.status)
        val row = lab.repo.resultsForOrder(order).single()
        assertEquals(owner.id, row.approvedById)
        assertEquals("Dr. Meena Iyer", row.approvedBy, "the caller's text is not the signature")
    }

    @Test
    fun `technicians, receptionists, retired pathologists and unknown ids cannot approve`() = runBlocking {
        val lab = Lab()
        val tech = lab.staff.upsert(Staff(id = "", name = "Ravi", role = StaffRole.TECHNICIAN))
        val desk = lab.staff.upsert(Staff(id = "", name = "Lata", role = StaffRole.RECEPTIONIST))
        val retired = lab.staff.upsert(Staff(id = "", name = "Dr. Old", role = StaffRole.PATHOLOGIST))
        lab.staff.upsert(Staff(id = "", name = "Dr. Now", role = StaffRole.PATHOLOGIST)) // keeps the lab staffed
        lab.staff.setActive(retired.id, false).getOrThrow()
        val order = lab.verifiedOrder()

        for (id in listOf(tech.id, desk.id, retired.id, "no-such-person", null)) {
            assertTrue(lab.repo.approveOrder(order, "X", id).isFailure, "approver id $id must be refused")
        }
        assertEquals(LabStatus.VERIFIED, lab.repo.orderById(order)!!.status)
    }

    @Test
    fun `the pathologist tick exists only on owner rows and survives every other edit`() = runBlocking {
        val lab = Lab()
        // A non-owner can never carry it.
        val tech = lab.staff.upsert(Staff(id = "", name = "Ravi", role = StaffRole.TECHNICIAN, alsoPathologist = true))
        assertEquals(false, lab.staff.byId(tech.id)!!.alsoPathologist)

        val owner = lab.staff.upsert(Staff(id = "", name = "Meena", role = StaffRole.OWNER, alsoPathologist = true))
        lab.staff.setPin(owner.id, "2468").getOrThrow()
        lab.staff.setActive(owner.id, true).getOrThrow()
        // An edit that does not mention the tick keeps it.
        lab.staff.upsert(lab.staff.byId(owner.id)!!.copy(name = "Dr. Meena", alsoPathologist = null))
        assertTrue(lab.staff.byId(owner.id)!!.canApprove, "a PIN, an activation or a rename must not strip approval")

        // Demoting the owner drops the tick with the role.
        lab.staff.upsert(lab.staff.byId(owner.id)!!.copy(role = StaffRole.TECHNICIAN))
        assertEquals(false, lab.staff.byId(owner.id)!!.alsoPathologist)
    }

    @Test
    fun `countApprovers sees pathologists and owner-pathologists, and nobody else`() = runBlocking {
        val lab = Lab()
        assertEquals(0L, lab.staff.countApprovers())
        lab.staff.seedOwnerIfEmpty("Lab")
        assertEquals(0L, lab.staff.countApprovers(), "the seeded owner is not a pathologist")
        val seeded = lab.staff.byId(StaffRepository.DEFAULT_OWNER_ID)!!
        lab.staff.upsert(seeded.copy(alsoPathologist = true))
        assertEquals(1L, lab.staff.countApprovers())
        val path = lab.staff.upsert(Staff(id = "", name = "Dr. P", role = StaffRole.PATHOLOGIST))
        assertEquals(2L, lab.staff.countApprovers())
        lab.staff.setActive(path.id, false).getOrThrow()
        assertEquals(1L, lab.staff.countApprovers())
    }

    @Test
    fun `a staff doc from an older seat does not strip an owner's pathologist tick`() = runBlocking {
        val lab = Lab()
        val owner = lab.staff.upsert(Staff(id = "", name = "Meena", role = StaffRole.OWNER, alsoPathologist = true))
        // An older build edits her PIN and pushes a doc that has never heard of the tick.
        val olderDoc = lab.staff.byId(owner.id)!!.copy(
            pinHash = "s1\$salt\$hash", updatedAt = "2099-01-01T00:00:00Z", alsoPathologist = null,
        )
        applyPulledStaff(lab.db.staffQueries, olderDoc, null, ::isoMs)
        val after = lab.staff.byId(owner.id)!!
        assertEquals("s1\$salt\$hash", after.pinHash, "the newer edit applied")
        assertTrue(after.canApprove, "absent must not read as 'not a pathologist'")

        // A newer seat that explicitly unticks does win.
        applyPulledStaff(lab.db.staffQueries, after.copy(updatedAt = "2099-02-01T00:00:00Z", alsoPathologist = false), null, ::isoMs)
        assertFalse(lab.staff.byId(owner.id)!!.canApprove)
    }

    @Test
    fun `an upgraded staff table gains the column in place and keeps its people`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        driver.execute(null, "DROP TABLE staff", 0)
        // The staff table exactly as a 1.2.0 install has it: twelve columns.
        driver.execute(null,
            "CREATE TABLE staff (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, " +
            "role TEXT NOT NULL DEFAULT 'receptionist', pin_hash TEXT, active INTEGER NOT NULL DEFAULT 1, " +
            "created_at TEXT NOT NULL, updated_at TEXT NOT NULL, deleted_at TEXT, username TEXT, " +
            "signature_png TEXT, qualifications TEXT, registration_no TEXT)", 0)
        driver.execute(null,
            "INSERT INTO staff VALUES ('staff-default-owner','Lab Owner','owner',NULL,1," +
            "'2026-09-01T00:00:00Z','2026-09-01T00:00:00Z',NULL,NULL,NULL,NULL,NULL)", 0)
        repeat(2) { driver.addColumn("staff", "also_pathologist", "INTEGER NOT NULL DEFAULT 0") }

        val staff = StaffRepository(AppDatabase(driver), ApiClient.json)
        val owner = staff.byId(StaffRepository.DEFAULT_OWNER_ID)!!
        assertEquals("Lab Owner", owner.name)
        assertFalse(owner.canApprove, "upgrading never grants approval on its own")
        staff.upsert(owner.copy(alsoPathologist = true))
        assertTrue(staff.byId(owner.id)!!.canApprove)
    }
}

/** Same stamp reading the sync engine uses: ISO instant → epoch ms, blank → 0. */
private fun isoMs(iso: String?): Long =
    if (iso.isNullOrBlank()) 0L else runCatching { kotlin.time.Instant.parse(iso).toEpochMilliseconds() }.getOrDefault(0L)
