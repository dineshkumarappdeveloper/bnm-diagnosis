package com.bnm.lab

import com.bnm.lab.license.LicenseManager
import com.bnm.lab.license.LicenseState
import com.bnm.lab.license.OfflinePolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The offline edition's promise: after activation, nothing leaves the PC.
 *
 * Every background path that could reach the network has to ask here first, so
 * this test is what stops a new loop being added without a decision.
 */
class OfflinePolicyTest {

    @Test
    fun `an offline licence refuses every automatic network path`() {
        val off = true
        assertFalse(OfflinePolicy.allowsSync(off), "lab data sync")
        assertFalse(OfflinePolicy.allowsReportUpload(off), "report PDF upload")
        assertFalse(OfflinePolicy.allowsHeartbeat(off), "licence heartbeat")
        assertFalse(OfflinePolicy.allowsBillingSync(off), "billing outbox + directories")
        assertFalse(OfflinePolicy.allowsUpdateCheck(off), "update check")
        assertFalse(OfflinePolicy.allowsWhatsappApi(off), "WhatsApp Business API")
    }

    @Test
    fun `a connected licence allows them all`() {
        val on = false
        assertTrue(OfflinePolicy.allowsSync(on))
        assertTrue(OfflinePolicy.allowsReportUpload(on))
        assertTrue(OfflinePolicy.allowsHeartbeat(on))
        assertTrue(OfflinePolicy.allowsBillingSync(on))
        assertTrue(OfflinePolicy.allowsUpdateCheck(on))
        assertTrue(OfflinePolicy.allowsWhatsappApi(on))
    }

    @Test
    fun `the edition comes from the licence, and connected is the default`() {
        // An unsigned/absent edition claim must never silently mean "offline"
        // (a connected lab would stop syncing) nor let a lab turn sync on.
        assertEquals("standalone", LicenseManager.EDITION_STANDALONE)
        assertEquals("connected", LicenseManager.EDITION_CONNECTED)
        assertTrue(LicenseState(edition = LicenseManager.EDITION_STANDALONE).isStandalone)
        assertFalse(LicenseState(edition = LicenseManager.EDITION_CONNECTED).isStandalone)
        assertFalse(LicenseState().isStandalone, "no edition on the licence = connected")
    }

    @Test
    fun `a remote support session runs only because the owner started it`() {
        // Same category as the catalog pull: the operator's press is the whole gate.
        assertTrue(OfflinePolicy.allowsRemoteSupport(userInitiated = true))
        assertFalse(OfflinePolicy.allowsRemoteSupport(userInitiated = false), "never on a timer, at start-up or from a sync sweep")
        val offline = OfflinePolicy.summary(true)
        assertTrue("remote support session" in offline && "started one" in offline,
            "the offline summary must say support only runs while the owner has started it: $offline")
    }

    @Test
    fun `the operator is told in one line what this licence does with the network`() {
        val offline = OfflinePolicy.summary(true)
        assertTrue("Offline edition" in offline && "nothing leaves this computer" in offline, offline)
        for (word in listOf("sync", "upload", "QR", "licence check", "update check")) {
            assertTrue(word in offline, "the summary must name $word: $offline")
        }
        assertTrue("Connected edition" in OfflinePolicy.summary(false))
    }
}
