package com.bnm.diagnosis

import com.bnm.diagnosis.license.LicenseManager
import com.bnm.diagnosis.license.LicenseState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Edition drives whether the app ever touches the network after activation.
 * A licence sold as STANDALONE must read as offline-only; anything older (no
 * `ed` claim) must keep behaving exactly as a connected licence, or existing
 * labs would silently lose sync on upgrade.
 */
class LicenseEditionTest {

    @Test
    fun standaloneEdition_isOfflineOnly() {
        val s = LicenseState(licensed = true, edition = LicenseManager.EDITION_STANDALONE)
        assertTrue(s.isStandalone)
    }

    @Test
    fun legacyLicenceWithoutEditionClaim_staysConnected() {
        val s = LicenseState(licensed = true) // default, as a pre-edition JWT yields
        assertEquals(LicenseManager.EDITION_CONNECTED, s.edition)
        assertFalse(s.isStandalone, "an older licence must not be downgraded to offline-only")
    }
}
