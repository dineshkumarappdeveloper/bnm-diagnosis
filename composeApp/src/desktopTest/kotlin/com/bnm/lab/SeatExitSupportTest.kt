package com.bnm.lab

import com.bnm.lab.staff.StaffRole
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which way of leaving the seat ends a running support session (App.kt
 * `lockSeat`). Only the owner's Sign out does: "Switch user" is how the bench
 * takes over mid-repair (RUNBOOK §3.7 — run one known sample, press Verified)
 * and the idle auto-lock fires while the engineer is still working.
 */
class SeatExitSupportTest {

    @Test
    fun `the owner signing out ends the session`() {
        assertTrue(seatExitEndsSupport(signOut = true, supportActive = true, role = StaffRole.OWNER))
    }

    @Test
    fun `switch user does not end the session, whoever is at the seat`() {
        for (role in StaffRole.ALL) {
            assertFalse(seatExitEndsSupport(signOut = false, supportActive = true, role = role), role)
        }
    }

    @Test
    fun `the idle auto-lock does not end the session`() {
        // The auto-lock calls lockSeat() with no arguments — the Switch user path.
        assertFalse(seatExitEndsSupport(signOut = false, supportActive = true, role = StaffRole.OWNER))
    }

    @Test
    fun `nothing to end when no session runs, and only the owner ends one`() {
        assertFalse(seatExitEndsSupport(signOut = true, supportActive = false, role = StaffRole.OWNER))
        assertFalse(seatExitEndsSupport(signOut = true, supportActive = true, role = null))
        for (role in StaffRole.ALL.filter { it != StaffRole.OWNER }) {
            assertFalse(seatExitEndsSupport(signOut = true, supportActive = true, role = role), role)
        }
    }
}
