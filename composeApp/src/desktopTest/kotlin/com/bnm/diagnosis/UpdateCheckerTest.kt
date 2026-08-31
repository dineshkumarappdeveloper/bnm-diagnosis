package com.bnm.diagnosis

import com.bnm.diagnosis.update.UpdateChecker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The update check fails SILENTLY when it gets version ordering wrong — it just
 * says "you're on the latest version" forever — so the comparison is pinned here.
 */
class UpdateCheckerTest {

    @Test
    fun `versions compare numerically, not as strings`() {
        // The whole reason this is not a string compare: lexicographically
        // "1.10.0" < "1.9.0", which would hide every release after 1.9 and the
        // app would report itself up to date indefinitely.
        assertTrue(UpdateChecker.compareSemver("1.10.0", "1.9.0") > 0,
            "1.10.0 must be newer than 1.9.0")
        assertTrue(UpdateChecker.compareSemver("2.0.0", "1.99.99") > 0)
        assertTrue(UpdateChecker.compareSemver("1.0.10", "1.0.9") > 0)
        assertEquals(0, UpdateChecker.compareSemver("1.2.3", "1.2.3"))
        assertTrue(UpdateChecker.compareSemver("1.0.0", "1.0.1") < 0)
    }

    @Test
    fun `a leading v is tolerated`() {
        assertEquals(Triple(1, 2, 3), UpdateChecker.parseSemver("v1.2.3"))
        assertEquals(0, UpdateChecker.compareSemver("v1.2.3", "1.2.3"))
    }

    @Test
    fun `unparseable versions sort oldest and never look like an update`() {
        assertNull(UpdateChecker.parseSemver("1.2"))
        assertNull(UpdateChecker.parseSemver("latest"))
        assertNull(UpdateChecker.parseSemver("1.2.x"))
        // A junk tag must never be treated as newer than the running build —
        // that would offer an "update" to something that does not exist.
        assertTrue(UpdateChecker.compareSemver("latest", "1.0.0") < 0)
    }

    @Test
    fun `current version is the baked build version, not a placeholder`() {
        val v = UpdateChecker.currentVersion
        assertTrue(UpdateChecker.parseSemver(v) != null,
            "BuildInfo.VERSION must be semver so the comparison works; got '$v'")
    }
}
