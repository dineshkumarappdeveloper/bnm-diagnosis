package com.bnm.lab

import com.bnm.lab.lab.TenantRowCounts
import com.bnm.lab.license.EditionSwitch
import com.bnm.lab.license.LicenseManager
import com.bnm.lab.license.editionNoticeBody
import com.bnm.lab.license.editionNoticeTitle
import com.bnm.lab.license.editionSwitch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Migrating a lab between editions: when the app speaks, and what it says. */
class EditionSwitchTest {

    private val OFF = LicenseManager.EDITION_STANDALONE
    private val ON = LicenseManager.EDITION_CONNECTED

    @Test
    fun `the notice fires on a real change and never on an ordinary start`() {
        assertEquals(EditionSwitch.WENT_ONLINE, editionSwitch(OFF, ON))
        assertEquals(EditionSwitch.WENT_OFFLINE, editionSwitch(ON, OFF))
        assertEquals(EditionSwitch.NONE, editionSwitch(ON, ON))
        assertEquals(EditionSwitch.NONE, editionSwitch(OFF, OFF))
        // First run after this shipped: nothing is stored, so nothing is claimed
        // to have changed — otherwise every lab would see the notice once.
        assertEquals(EditionSwitch.NONE, editionSwitch("", ON))
        assertEquals(EditionSwitch.NONE, editionSwitch(null, OFF))
        assertEquals(EditionSwitch.NONE, editionSwitch(OFF, "nonsense"))
        assertNull(editionNoticeTitle(EditionSwitch.NONE))
    }

    @Test
    fun `going online names what will upload, in counts the lab recognises`() {
        val counts = TenantRowCounts(patients = 412, orders = 1180, results = 9033, staff = 4, tests = 61)
        val body = editionNoticeBody(EditionSwitch.WENT_ONLINE, counts)
        assertEquals("This lab is now connected", editionNoticeTitle(EditionSwitch.WENT_ONLINE))
        assertTrue("412 patients" in body && "1180 orders" in body && "9033 results" in body, body)
        assertTrue("nothing is re-entered and nothing is deleted" in body, body)
        // The one thing a migrating lab must not be surprised by later.
        assertTrue("Reports printed before today keep no download link" in body, body)
        // Counts unavailable: the fact still matters more than the figures.
        val bare = editionNoticeBody(EditionSwitch.WENT_ONLINE, null)
        assertTrue("will now upload to BNM." in bare, bare)
        assertTrue("patients" !in bare)
        // One record reads as one, not "1 patients".
        val single = editionNoticeBody(EditionSwitch.WENT_ONLINE, TenantRowCounts(1, 1, 1, 1, 1))
        assertTrue("1 patient," in single && "1 order," in single && "1 result." in single, single)
    }

    @Test
    fun `going offline promises silence and is honest about what already left`() {
        val body = editionNoticeBody(EditionSwitch.WENT_OFFLINE, null)
        assertEquals("This lab is now offline only", editionNoticeTitle(EditionSwitch.WENT_OFFLINE))
        assertTrue("Nothing further will leave this computer" in body, body)
        assertTrue("already reached BNM stays there" in body, body)
        assertEquals("", editionNoticeBody(EditionSwitch.NONE, null))
    }
}
