package com.bnm.lab.backup

import com.bnm.lab.backup.BackupExitFlow.Decision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Closing the window: exit, flush behind an overlay, or ask — decided from
 * the status alone, so Main.kt never has to reason about phases.
 */
class BackupExitFlowTest {
    private val now = BackupFixtures.now

    @Test
    fun `no engine or nothing set up just exits`() {
        assertEquals(Decision.Exit, BackupExitFlow.decide(null))
        assertEquals(Decision.Exit, BackupExitFlow.decide(BackupStatus()))
    }

    @Test
    fun `nothing unsaved exits without touching the pendrive`() {
        assertEquals(Decision.Exit, BackupExitFlow.decide(BackupFixtures.ok))
        assertEquals(Decision.Exit, BackupExitFlow.decide(BackupFixtures.ok.copy(phase = BackupStatus.Phase.DRIVE_MISSING)))
    }

    @Test
    fun `unsaved work with the pendrive present is flushed first`() {
        val dirty = BackupFixtures.ok.copy(phase = BackupStatus.Phase.PENDING, dirtySince = now - 30_000)
        assertEquals(Decision.Flush(BackupExitFlow.FLUSH_MAX_WAIT_MS), BackupExitFlow.decide(dirty))
        // Working and even failing-with-drive both get one more try.
        assertEquals(Decision.Flush(), BackupExitFlow.decide(dirty.copy(phase = BackupStatus.Phase.WORKING)))
        assertEquals(Decision.Flush(), BackupExitFlow.decide(dirty.copy(phase = BackupStatus.Phase.FAILING)))
    }

    @Test
    fun `unsaved work with no pendrive asks instead of blocking`() {
        val dirty = BackupFixtures.ok.copy(phase = BackupStatus.Phase.DRIVE_MISSING, dirtySince = now - 30_000)
        assertEquals(Decision.Confirm, BackupExitFlow.decide(dirty))
        // A broken database cannot be snapshotted: no point flushing.
        assertEquals(Decision.Confirm, BackupExitFlow.decide(dirty.copy(phase = BackupStatus.Phase.DB_PROBLEM)))
    }

    @Test
    fun `a flush that did not finish falls through to the question`() {
        assertEquals(Decision.Exit, BackupExitFlow.afterFlush(true))
        assertEquals(Decision.Confirm, BackupExitFlow.afterFlush(false))
    }

    @Test
    fun `the question names when the unsaved work began`() {
        val since = now - 90 * 60_000L
        val s = BackupFixtures.ok.copy(phase = BackupStatus.Phase.DRIVE_MISSING, dirtySince = since)
        assertEquals("Backup pendrive is not connected", BackupExitFlow.confirmTitle(s))
        val body = BackupExitFlow.confirmBody(s, now)
        assertTrue(body.startsWith("Changes since ${BackupCopy.timeLabel(since, now)} exist only on this computer."), body)
        assertTrue(body.contains("Plug in the backup pendrive"), body)
        assertEquals("Changes are not backed up", BackupExitFlow.confirmTitle(s.copy(phase = BackupStatus.Phase.FAILING)))
    }
}
