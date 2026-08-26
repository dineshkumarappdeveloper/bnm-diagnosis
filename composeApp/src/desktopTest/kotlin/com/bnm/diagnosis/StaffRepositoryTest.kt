package com.bnm.diagnosis

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.bnm.diagnosis.api.ApiClient
import com.bnm.diagnosis.db.AppDatabase
import com.bnm.diagnosis.staff.Staff
import com.bnm.diagnosis.staff.StaffRepository
import com.bnm.diagnosis.staff.StaffRole
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Staff accounts + local RBAC (P4), against a real in-memory SQLDelight DB:
 * the anti-lockout owner seed, PIN verification both ways, and the rule that
 * matters most for the audit trail — deactivating someone keeps their row.
 */
class StaffRepositoryTest {

    private fun freshRepo(): StaffRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        return StaffRepository(AppDatabase(driver), ApiClient.json)
    }

    @Test
    fun seedOwnerIfEmpty_createsOnePinlessOwner_thenNoOps() = runBlocking {
        val repo = freshRepo()

        val seeded = repo.seedOwnerIfEmpty("Demo Diagnostics")
        assertNotNull(seeded, "a fresh install must never be locked out")
        assertEquals(StaffRole.OWNER, seeded.role)
        assertFalse(seeded.hasPin, "the seeded owner must be tap-to-enter")
        assertTrue(seeded.canApprove)
        assertTrue(seeded.canManageStaff)
        assertEquals(1, repo.listActive().size)

        // Second call is a no-op — the table already has someone.
        assertNull(repo.seedOwnerIfEmpty("Demo Diagnostics"))
        assertEquals(1, repo.listActive().size)
    }

    @Test
    fun verifyPin_trueForTheRightPin_falseForTheWrong_andOpenWhenUnset() = runBlocking {
        val repo = freshRepo()
        val tech = repo.upsert(Staff(id = "", name = "Ravi Technician", role = StaffRole.TECHNICIAN))

        // No PIN yet: tap-to-enter is the contract, so anything verifies.
        assertTrue(repo.verifyPin(tech.id, ""))

        repo.setPin(tech.id, "2468").getOrThrow()
        assertTrue(repo.byId(tech.id)!!.hasPin)
        assertTrue(repo.verifyPin(tech.id, "2468"))
        assertFalse(repo.verifyPin(tech.id, "1357"))
        assertFalse(repo.verifyPin("no-such-staff", "2468"))

        // Too-short PINs are refused outright, and the old one still stands.
        assertTrue(repo.setPin(tech.id, "12").isFailure)
        assertTrue(repo.verifyPin(tech.id, "2468"))

        // Clearing drops back to tap-to-enter.
        repo.setPin(tech.id, null).getOrThrow()
        assertFalse(repo.byId(tech.id)!!.hasPin)
        assertTrue(repo.verifyPin(tech.id, "anything"))
    }

    @Test
    fun deactivate_keepsTheRow_soAttributionStaysReadable() = runBlocking {
        val repo = freshRepo()
        repo.seedOwnerIfEmpty("Demo Diagnostics")
        val leaver = repo.upsert(Staff(id = "", name = "Priya Pathologist", role = StaffRole.PATHOLOGIST))
        assertEquals(2, repo.listActive().size)

        repo.setActive(leaver.id, false).getOrThrow()

        // Gone from the sign-in grid…
        assertTrue(repo.listActive().none { it.id == leaver.id })
        // …but the row (and the name printed on every report they signed) stays.
        val kept = repo.byId(leaver.id)
        assertNotNull(kept)
        assertEquals("Priya Pathologist", kept.name)
        assertFalse(kept.active)
        assertTrue(repo.listAll().any { it.id == leaver.id })

        // Reactivating is symmetric.
        repo.setActive(leaver.id, true).getOrThrow()
        assertTrue(repo.listActive().any { it.id == leaver.id })
    }

    @Test
    fun rbac_onlyPathologistOrOwnerApproves_onlyOwnerManagesStaff() {
        val owner = Staff(id = "o", name = "O", role = StaffRole.OWNER)
        val path = Staff(id = "p", name = "P", role = StaffRole.PATHOLOGIST)
        val tech = Staff(id = "t", name = "T", role = StaffRole.TECHNICIAN)
        val recep = Staff(id = "r", name = "R", role = StaffRole.RECEPTIONIST)

        assertTrue(owner.canApprove); assertTrue(path.canApprove)
        assertFalse(tech.canApprove); assertFalse(recep.canApprove)

        // canVerify was deleted in round 1 — it was declared but never called,
        // so it was a permission that lied. The money gate replaces it as the
        // role distinction that actually does something: the lab owner's ask was
        // that an employee cannot see commission.
        assertTrue(owner.canSeeMoney)
        assertFalse(path.canSeeMoney); assertFalse(tech.canSeeMoney); assertFalse(recep.canSeeMoney)
        assertTrue(owner.canEditCatalog); assertFalse(recep.canEditCatalog)

        assertTrue(owner.canManageStaff)
        assertFalse(path.canManageStaff); assertFalse(tech.canManageStaff)
    }
}
