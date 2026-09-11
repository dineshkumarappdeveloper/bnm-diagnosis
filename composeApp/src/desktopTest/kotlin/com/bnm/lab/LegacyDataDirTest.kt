package com.bnm.lab

import com.bnm.lab.db.APP_DIR_NAME
import com.bnm.lab.db.LEGACY_APP_DIRS
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rename trap: SQLDelight is the system of record, and a standalone lab has
 * no server copy at all. If the app opens a data directory with no database it
 * creates an empty one — no crash, no warning, and a lab starts the day with
 * every patient, result and bill apparently gone.
 *
 * The BNM Lab rename hit exactly this: a blanket find-and-replace rewrote the
 * LEGACY directory list to the new name, so the adoption looked for the folder
 * it was already standing in. This test is what stops the next rename doing it.
 */
class LegacyDataDirTest {

    @Test
    fun `the legacy list carries every name this app has had, and never its current one`() {
        assertTrue("BNMDiagnosis" in LEGACY_APP_DIRS, "the pre-rename directory must still be adopted")
        assertTrue("BNMAdmin" in LEGACY_APP_DIRS, "the clone-chain leftover from the original skeleton")
        assertFalse(APP_DIR_NAME in LEGACY_APP_DIRS, "listing the current directory adopts nothing")
        // A blanket rename would leave a name with the product's new words in it.
        assertFalse(LEGACY_APP_DIRS.any { it.contains("Lab", ignoreCase = true) }, LEGACY_APP_DIRS.toString())
        assertTrue(LEGACY_APP_DIRS.none { it.isBlank() })
    }
}
