package com.bnm.lab.backup

import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The generation number is the only order that matters, and it must never go
 * backwards — not when the preferences are behind the drive (a restored PC),
 * not when the drive is behind the preferences (a freshly wiped stick).
 */
class BackupSeqTest {

    @Test
    fun `seq continues from whichever is ahead`() {
        assertEquals(10L, BackupNaming.nextSeq(prefsSeq = 5, driveMaxSeq = 9), "drive ahead (this PC restored from it)")
        assertEquals(13L, BackupNaming.nextSeq(prefsSeq = 12, driveMaxSeq = 3), "prefs ahead (older generations pruned)")
        assertEquals(1L, BackupNaming.nextSeq(prefsSeq = 0, driveMaxSeq = 0), "fresh vault")
        assertEquals(8L, BackupNaming.nextSeq(prefsSeq = 7, driveMaxSeq = 7))
    }

    @Test
    fun `file names carry the date and an eight-digit seq, and parse back`() {
        val created = ZonedDateTime.of(2026, 9, 15, 9, 12, 30, 0, ZoneId.of("Asia/Kolkata"))
        val name = BackupNaming.fileName(created, 42)
        assertEquals("bnmlab-20260915-091230-00000042.bnmlab", name)
        assertEquals("2026-09", BackupNaming.monthDir(created))
        val parsed = BackupNaming.parse(name)
        assertEquals(42L, parsed?.seq)
        assertEquals(LocalDate.of(2026, 9, 15), parsed?.date)
        assertNull(BackupNaming.parse("bnmlab-20260915-091230-00000042.bnmlab.part"))
        assertNull(BackupNaming.parse("notes.txt"))
        assertNull(BackupNaming.parse("bnmlab-20261315-091230-00000042.bnmlab"), "month 13 is not a date")
    }

    @Test
    fun `the drive's highest seq comes from finished generation files only`() {
        val vault = Files.createTempDirectory("vault").toFile()
        try {
            val month = File(DriveScan.snapshotsDir(vault), "2026-09").apply { mkdirs() }
            File(month, "bnmlab-20260915-091200-00000007.bnmlab").writeText("x")
            File(month, "bnmlab-20260915-091300-00000012.bnmlab").writeText("x")
            File(month, "bnmlab-20260915-091400-00000099.bnmlab.part").writeText("x") // in progress: not a generation
            File(month, "bnmlab-20260915-091500-00000098.bnmlab.bad").writeText("x")  // failed read-back: not one either
            File(month, "Thumbs.db").writeText("x")
            val older = File(DriveScan.snapshotsDir(vault), "2026-08").apply { mkdirs() }
            File(older, "bnmlab-20260801-091200-00000003.bnmlab").writeText("x")
            assertEquals(12L, DriveScan.maxSeq(vault))
            assertEquals(13L, BackupNaming.nextSeq(prefsSeq = 2, driveMaxSeq = DriveScan.maxSeq(vault)))
            assertEquals(3, DriveScan.generationFiles(vault).size)
        } finally {
            vault.deleteRecursively()
        }
    }
}
