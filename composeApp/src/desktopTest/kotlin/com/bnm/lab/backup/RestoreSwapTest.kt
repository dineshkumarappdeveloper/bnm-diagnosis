package com.bnm.lab.backup

import com.russhwolf.settings.PropertiesSettings
import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The launch-time swap: the live database and its rollback journal are set
 * aside together (a journal deleted on its own would corrupt the very file
 * kept as the undo), the staged file takes their place, and only the newest
 * three set-aside sets are kept. Plus the two refusals and the preference
 * hand-over.
 */
class RestoreSwapTest {

    @Test
    fun `swap keeps the set-aside set, moves the journal with it, and consumes the marker`() {
        val dir = Files.createTempDirectory("bnm-swap").toFile()
        try {
            val db = File(dir, "bnm_chat.db").apply { writeText("live") }
            File(dir, "bnm_chat.db-journal").writeText("hot journal")
            File(dir, "bnm_chat.db.restore-pending").writeText("restored")
            File(dir, RestoreStaging.APPLIED_MARKER).writeText("seq=7")

            val applied = RestoreStaging.applyPending(db, ZonedDateTime.of(2026, 9, 15, 9, 12, 0, 0, ZoneId.of("Asia/Kolkata")))
            assertNotNull(applied)
            assertEquals(7L, applied.seq)
            assertEquals("restored", db.readText())
            assertFalse(File(dir, "bnm_chat.db-journal").exists(), "no stale journal beside the restored file")
            assertFalse(File(dir, "bnm_chat.db.restore-pending").exists())
            assertFalse(File(dir, RestoreStaging.APPLIED_MARKER).exists())
            val setAside = dir.listFiles()!!.filter { it.name.contains(".before-restore-") }.associate { it.name to it.readText() }
            assertEquals(
                mapOf(
                    "bnm_chat.db.before-restore-20260915-091200" to "live",
                    "bnm_chat.db-journal.before-restore-20260915-091200" to "hot journal",
                ),
                setAside,
            )
            assertNull(RestoreStaging.applyPending(db), "nothing pending now")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a fresh PC with no database at all just takes the staged file`() {
        val dir = Files.createTempDirectory("bnm-swap-fresh").toFile()
        try {
            val db = File(dir, "bnm_chat.db")
            File(dir, "bnm_chat.db.restore-pending").writeText("restored")
            val applied = RestoreStaging.applyPending(db)
            assertNotNull(applied)
            assertNull(applied.seq)
            assertEquals("restored", db.readText())
            assertTrue(dir.listFiles()!!.none { it.name.contains(".before-restore-") })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `only the newest three set-aside sets survive`() {
        val dir = Files.createTempDirectory("bnm-swap-prune").toFile()
        try {
            val db = File(dir, "bnm_chat.db")
            for (i in 1..5) {
                db.writeText("live $i")
                File(dir, "bnm_chat.db-journal").writeText("journal $i")
                File(dir, "bnm_chat.db.restore-pending").writeText("restored $i")
                RestoreStaging.applyPending(db, ZonedDateTime.of(2026, 9, i, 9, 0, 0, 0, ZoneId.of("Asia/Kolkata")))
            }
            val stamps = dir.listFiles()!!.filter { it.name.contains(".before-restore-") }
                .map { it.name.substringAfterLast(".before-restore-") }.toSortedSet()
            assertEquals(sortedSetOf("20260903-090000", "20260904-090000", "20260905-090000"), stamps)
            assertEquals(6, dir.listFiles()!!.count { it.name.contains(".before-restore-") }, "three sets of db + journal")
            assertEquals("restored 5", db.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a backup from a newer BNM Lab is refused, an older one is fine`() {
        assertTrue(RestoreGuards.isNewer("1.3.0", "1.2.0"))
        assertTrue(RestoreGuards.isNewer("2.0.0", "1.9.9"))
        assertTrue(RestoreGuards.isNewer("1.2.1", "1.2.0"))
        assertFalse(RestoreGuards.isNewer("1.2.0", "1.2.0"))
        assertFalse(RestoreGuards.isNewer("1.2.0", "1.10.0"), "numeric, not lexical")
        assertFalse(RestoreGuards.isNewer("1.1.9", "1.2.0"))
        assertFalse(RestoreGuards.isNewer("1.2.0-rc1", "1.2.0"))
        assertFalse(RestoreGuards.isNewer("garbage", "1.2.0"), "an unreadable version does not refuse")
    }

    @Test
    fun `licence fingerprint - compared only where this PC has one`() {
        assertNull(RestoreGuards.sameLicence(null, "abc"))
        assertNull(RestoreGuards.sameLicence("", "abc"))
        assertEquals(true, RestoreGuards.sameLicence("abc", "abc"))
        assertEquals(false, RestoreGuards.sameLicence("abc", "def"))
        assertEquals(false, RestoreGuards.sameLicence("abc", null))
    }

    @Test
    fun `carried prefs land, the device identity goes, a missing reports folder resets`() {
        val s = PropertiesSettings(Properties())
        s.putString("license_device_id", "old-device")
        s.putString("license_device_token", "old-token")
        s.putString("license_device_row_id", "row-1")
        s.putInt("license_seat_no", 3)
        s.putBoolean("license_blocked", true)
        s.putString("session_token", "this-pc-session")
        val notes = RestoreStaging.applyCarriedPrefs(
            mapOf(
                "report_lh_address" to "12 Main Rd",
                "license_jwt" to "a.b.c",
                "lab_license_fp" to "fp-1",
                "pref_accession_prefix" to "LAB",
                "license_device_id" to "cloned-device",
                "sync_pull_cursor" to "99",
                "lab_backup_dek" to "planted",
                "report_archive_dir" to File(System.getProperty("java.io.tmpdir"), "definitely-not-here-${System.nanoTime()}").absolutePath,
            ),
            s,
        )
        assertEquals("12 Main Rd", s.getStringOrNull("report_lh_address"))
        assertEquals("a.b.c", s.getStringOrNull("license_jwt"))
        assertEquals("fp-1", s.getStringOrNull("lab_license_fp"))
        assertEquals("LAB", s.getStringOrNull("pref_accession_prefix"))
        for (k in BackupAllowList.DEVICE_KEYS) assertFalse(s.hasKey(k), "$k must be gone — the next launch mints a fresh device")
        assertNull(s.getStringOrNull("sync_pull_cursor"))
        assertNull(s.getStringOrNull("lab_backup_dek"))
        assertNull(s.getStringOrNull("report_archive_dir"), "a folder that does not exist here goes back to the default")
        assertEquals(1, notes.size)
        assertTrue(notes[0].startsWith("Reports folder reset"))
        assertEquals("this-pc-session", s.getStringOrNull("session_token"), "this PC's own session is not the backup's business")
    }
}
