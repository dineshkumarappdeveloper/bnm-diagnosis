package com.bnm.lab.backup

import com.bnm.lab.api.LabSeatDevice
import com.bnm.lab.screens.license.restoredSeatToReplace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Registering a restored PC: when BNM answers "all seats in use", the seat to
 * offer first is the old computer's own — matched by the row id the backup
 * carried, never guessed from names or dates.
 */
class RestoredSeatTest {
    private val oldPc = LabSeatDevice(id = "row-old-pc", deviceName = "Reception PC", replaceable = false)
    private val other = LabSeatDevice(id = "row-other", deviceName = "Doctor's laptop", replaceable = true)

    @Test
    fun `the old computer's seat is offered when the server lists it`() {
        assertEquals(oldPc, restoredSeatToReplace(listOf(other, oldPc), "row-old-pc"))
    }

    @Test
    fun `the server decides whether it may be taken over now - not the replaceable hint`() {
        // A dead PC's last heartbeat can be recent; replace_cooldown is the server's answer.
        assertEquals(oldPc, restoredSeatToReplace(listOf(oldPc), "row-old-pc"))
    }

    @Test
    fun `nothing is pre-selected without a restore, with a blank id, or when the seat is gone`() {
        assertNull(restoredSeatToReplace(listOf(other, oldPc), null))
        assertNull(restoredSeatToReplace(listOf(other, oldPc), ""))
        assertNull(restoredSeatToReplace(listOf(other), "row-old-pc"), "already replaced or deactivated: the list is shown as usual")
    }
}
