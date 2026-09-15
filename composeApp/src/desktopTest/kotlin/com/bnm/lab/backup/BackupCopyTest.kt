package com.bnm.lab.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The words on the chip, the banner rule and the set-up refusals, pinned. */
class BackupCopyTest {
    private val now = BackupFixtures.now
    private val h = BackupFixtures.HOUR

    @Test
    fun `chip copy per phase`() {
        val ok = BackupFixtures.ok
        val t = BackupCopy.timeLabel(ok.lastOkAt!!, now)
        assertEquals("Backup not set up", BackupCopy.chipLabel(BackupStatus(), now))
        assertEquals("Backed up $t · BNM BACKUP (E:)", BackupCopy.chipLabel(ok, now))
        assertEquals("Changes waiting…", BackupCopy.chipLabel(ok.copy(phase = BackupStatus.Phase.PENDING), now))
        assertEquals("Backing up…", BackupCopy.chipLabel(ok.copy(phase = BackupStatus.Phase.WORKING), now))
        assertEquals(
            "Backup pendrive not connected · last backup $t",
            BackupCopy.chipLabel(ok.copy(phase = BackupStatus.Phase.DRIVE_MISSING), now),
        )
        assertEquals(
            "Backup failing: Pendrive full — use a bigger pendrive",
            BackupCopy.chipLabel(ok.copy(phase = BackupStatus.Phase.FAILING, lastError = "Pendrive full — use a bigger pendrive"), now),
        )
        assertEquals("Database problem detected — contact BNM", BackupCopy.chipLabel(ok.copy(phase = BackupStatus.Phase.DB_PROBLEM), now))
        assertEquals("Pendrive not connected", BackupCopy.chipLabelShort(ok.copy(phase = BackupStatus.Phase.DRIVE_MISSING), now))
    }

    @Test
    fun `row subtitle adds today's verification only when backed up`() {
        val ok = BackupFixtures.ok
        assertTrue(BackupCopy.rowSubtitle(ok, now).contains("Verified restorable today"))
        assertFalse(BackupCopy.rowSubtitle(ok.copy(phase = BackupStatus.Phase.DRIVE_MISSING), now).contains("Verified"))
        assertFalse(BackupCopy.rowSubtitle(ok.copy(lastVerifiedAt = now - 30 * h), now).contains("Verified"))
    }

    @Test
    fun `banner shows only for stale unsaved work nothing is taking`() {
        val base = BackupFixtures.ok.copy(phase = BackupStatus.Phase.DRIVE_MISSING, lastOkAt = now - h)
        assertNull(BackupCopy.bannerText(base, now), "nothing unsaved")
        assertNull(BackupCopy.bannerText(base.copy(dirtySince = now - h), now), "recent work, recent backup")
        val stale = base.copy(dirtySince = now - 5 * h)
        assertEquals("Work since ${BackupCopy.timeLabel(stale.dirtySince!!, now)} is not backed up. Plug in the backup pendrive.",
            BackupCopy.bannerText(stale, now))
        assertNotNull(BackupCopy.bannerText(base.copy(dirtySince = now - h, lastOkAt = now - 5 * h), now), "old last success")
        assertNull(BackupCopy.bannerText(stale.copy(bannerDismissed = true), now), "dismissed")
        assertNull(BackupCopy.bannerText(stale.copy(phase = BackupStatus.Phase.PENDING), now), "pendrive present — engine's job")
        assertNull(BackupCopy.bannerText(BackupStatus(dirtySince = now - 5 * h), now), "not set up")
        assertTrue(BackupCopy.bannerText(stale.copy(phase = BackupStatus.Phase.DB_PROBLEM), now)!!.endsWith("Contact BNM."))
    }

    @Test
    fun `set-up refuses the system disk, another lab's stick and a tiny stick`() {
        assertFalse(BackupCopy.driveVerdict(BackupFixtures.systemDisk, "Demo Lab").allowed)
        val other = BackupCopy.driveVerdict(BackupFixtures.otherLabsDrive, "Demo Lab")
        assertFalse(other.allowed)
        assertEquals("This pendrive already holds backups of Sunrise Diagnostics. Use Restore, or choose another pendrive.", other.note)
        assertFalse(BackupCopy.driveVerdict(BackupFixtures.tinyDrive, "Demo Lab").allowed)
        // Same lab's stick is fine (a re-bind keeps its generations); a non-FAT volume only warns.
        val same = BackupCopy.driveVerdict(BackupFixtures.otherLabsDrive.copy(existingBackupOf = "demo lab"), "Demo Lab")
        assertTrue(same.allowed); assertNotNull(same.note)
        val ntfs = BackupCopy.driveVerdict(BackupFixtures.pendrive.copy(fsType = "NTFS", removableLikely = false), "Demo Lab")
        assertTrue(ntfs.allowed); assertNotNull(ntfs.note)
        assertNull(BackupCopy.driveVerdict(BackupFixtures.pendrive, "Demo Lab").note)
    }

    @Test
    fun `a hand-picked folder is matched to the volume it lives on`() {
        val all = listOf(BackupFixtures.systemDisk, BackupFixtures.pendrive)
        assertEquals(BackupFixtures.pendrive, BackupCopy.matchCandidate("/Volumes/BNM BACKUP/BNM Lab Backup", all))
        assertEquals(BackupFixtures.systemDisk, BackupCopy.matchCandidate("/Users/lab/Documents", all))
        assertEquals(BackupFixtures.pendrive, BackupCopy.matchCandidate("/Volumes/BNM BACKUP", all))
        assertNull(BackupCopy.matchCandidate("/Volumes/BNM BACKUP", listOf(BackupFixtures.tinyDrive)))
        val win = BackupFixtures.pendrive.copy(path = "E:\\")
        assertEquals(win, BackupCopy.matchCandidate("E:\\BNM Lab Backup", listOf(win)))
    }

    @Test
    fun `sizes counts and codes read like a person wrote them`() {
        assertEquals("12 MB", BackupCopy.sizeLabel(12L shl 20))
        assertEquals("1.5 MB", BackupCopy.sizeLabel((1.5 * (1L shl 20)).toLong()))
        assertEquals("less than 1 MB", BackupCopy.sizeLabel(1000))
        assertEquals("2.0 GB", BackupCopy.sizeLabel(2L shl 30))
        assertEquals("32 GB", BackupCopy.sizeLabel(32L shl 30))
        assertEquals("1,204 patients", BackupCopy.countOf(1204, "patient"))
        assertEquals("1 patient", BackupCopy.countOf(1, "patient"))
        assertEquals("22,118", BackupCopy.count(22118))
        assertEquals("ABCDE-FGHJK-MNPQR-STVWX-YZ234", BackupCopy.recoveryCodeInput("abcde fghjk-mnpqr stvwx yz234"))
        assertEquals("BNMD-ABCD-EFGH", BackupCopy.licenceKeyInput(" bnmd-abcd-efgh "))
        assertEquals("First backup done — 12 MB, 1,204 patients ✓", BackupCopy.firstBackupLine(12L shl 20, 1204))
    }

    @Test
    fun `restore preview wording and refusals`() {
        val p = BackupFixtures.preview
        assertTrue(BackupCopy.restorePreviewText(p).startsWith("Restore Demo Lab as of "))
        assertTrue(BackupCopy.restorePreviewText(p).endsWith("— 1,204 patients, 3,410 orders, 22,118 results, 6 staff?"))
        assertEquals("The data on this computer (empty) will be set aside.", BackupCopy.setAsideText(p))
        assertEquals("The data on this computer (37 patients) will be set aside.", BackupCopy.setAsideText(p.copy(currentPatientsHere = 37)))
        assertNull(BackupCopy.restoreRefusal(p))
        assertEquals("This backup was made by a newer BNM Lab. Update BNM Lab first.", BackupCopy.restoreRefusal(p.copy(newerThanThisApp = true)))
        assertEquals("This backup belongs to a different lab licence.", BackupCopy.restoreRefusal(p.copy(sameLicence = false)))
        assertNull(BackupCopy.restoreRefusal(p.copy(sameLicence = null)), "not activated: nothing to compare")
    }
}
