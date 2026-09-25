package com.bnm.lab

import com.bnm.lab.license.LicenseManager
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The guard that decides whether activating a key is a TENANT SWITCH — i.e.
 * whether this device is about to put one lab's records under another lab's
 * licence.
 *
 * Regression origin: the guard keyed solely on a stored licence fingerprint and
 * read "no fingerprint" as "fresh device". Installs activated before the
 * fingerprint existed — and installs whose database arrived through the
 * BNMDiagnosis data-dir migration — hold a full lab with no fingerprint, so a
 * brand-new licence was adopted silently and kept the previous lab's patients,
 * orders and results. Observed live on 2026-09-12.
 */
class TenantSwitchGuardTest {

    private val labA = "a".repeat(64)
    private val labB = "b".repeat(64)

    @Test
    fun `same licence re-activated is never a tenant switch`() {
        // Refresh, seat replacement, re-install against the same lab.
        assertFalse(LicenseManager.differentTenant(labA, labA, hasLocalData = true))
        assertFalse(LicenseManager.differentTenant(labA, labA, hasLocalData = false))
    }

    @Test
    fun `a different licence is a tenant switch`() {
        assertTrue(LicenseManager.differentTenant(labA, labB, hasLocalData = true))
        // Still "different" with an empty device; the caller decides there is
        // simply nothing to erase.
        assertTrue(LicenseManager.differentTenant(labA, labB, hasLocalData = false))
    }

    @Test
    fun `genuinely fresh device never prompts`() {
        // No fingerprint AND no records: the first activation on a new install.
        assertFalse(LicenseManager.differentTenant(null, labB, hasLocalData = false))
    }

    @Test
    fun `after a restore, the licence id in the tokens decides - never an unknown`() {
        // A PC restored from a backup pendrive carries the OLD key's fingerprint;
        // the key in hand may be a re-issued one of the same licence. Only the
        // licence id BNM signs into both tokens can say so.
        assertTrue(LicenseManager.sameLicenceId("lic-1", "lic-1"))
        assertFalse(LicenseManager.sameLicenceId("lic-1", "lic-2"))
        assertFalse(LicenseManager.sameLicenceId(null, null), "two unknowns are not the same licence")
        assertFalse(LicenseManager.sameLicenceId("", ""))
        assertFalse(LicenseManager.sameLicenceId("lic-1", null))
        assertFalse(LicenseManager.sameLicenceId(null, "lic-1"))
    }

    @Test
    fun `no fingerprint but records present IS treated as a tenant switch`() {
        // THE REGRESSION. An install predating the fingerprint, or one carrying a
        // migrated BNMDiagnosis database, has data but nothing to compare against.
        // Absent must mean "unknown", not "fresh" — otherwise another lab's
        // records are silently adopted by the new licence.
        assertTrue(LicenseManager.differentTenant(null, labB, hasLocalData = true))
    }
}
