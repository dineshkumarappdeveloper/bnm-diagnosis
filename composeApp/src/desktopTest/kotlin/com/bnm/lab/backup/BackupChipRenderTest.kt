package com.bnm.lab.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bnm.lab.screens.backup.BackupExitFlowUi
import com.bnm.lab.screens.backup.BackupNotBackedUpBanner
import com.bnm.lab.screens.backup.BackupStatusChip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The five looks of the home chip, wide and compact, one scene per state —
 * plus the banner and the close-window flush, which run their effects only
 * when actually composed.
 */
class BackupChipRenderTest {
    private val now = BackupFixtures.now

    private fun chips(name: String, status: BackupStatus) = renderScene(name, width = 900, height = 160) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row { BackupStatusChip(status, onClick = {}) }
            Row { BackupStatusChip(status, onClick = {}, compact = true) }
        }
    }

    @Test
    fun `chip - not set up`() = chips("chip-not-set-up", BackupStatus())

    @Test
    fun `chip - backed up`() = chips("chip-ok", BackupFixtures.ok)

    @Test
    fun `chip - working`() = chips("chip-working", BackupFixtures.ok.copy(phase = BackupStatus.Phase.WORKING, dirtySince = now - 10_000))

    @Test
    fun `chip - pendrive missing`() = chips("chip-drive-missing", BackupFixtures.ok.copy(phase = BackupStatus.Phase.DRIVE_MISSING, dirtySince = now - 60_000))

    @Test
    fun `chip - failing`() = chips(
        "chip-failing",
        BackupFixtures.ok.copy(phase = BackupStatus.Phase.FAILING, lastError = "Pendrive full — use a bigger pendrive", dirtySince = now - 60_000),
    )

    @Test
    fun `banner renders for stale unsaved work and dismisses for the session`() {
        val fake = FakeBackupController(
            BackupFixtures.ok.copy(phase = BackupStatus.Phase.DRIVE_MISSING, dirtySince = now - 5 * BackupFixtures.HOUR),
        )
        renderScene("banner", width = 1100, height = 120) {
            BackupNotBackedUpBanner(fake, onOpenBackup = {})
        }
        fake.dismissBannerForSession()
        assertTrue(fake.status.value.bannerDismissed)
    }

    @Test
    fun `close-window flush runs the engine flush then exits`() {
        val fake = FakeBackupController(BackupFixtures.ok.copy(phase = BackupStatus.Phase.PENDING, dirtySince = now - 30_000))
        var exited = 0
        var cancelled = 0
        renderScene("exit-flush", width = 800, height = 400, frames = 10) {
            BackupExitFlowUi(fake, onExit = { exited++ }, onCancel = { cancelled++ })
        }
        assertEquals(listOf("flushOnExit:${BackupExitFlow.FLUSH_MAX_WAIT_MS}"), fake.calls)
        assertEquals(1, exited)
        assertEquals(0, cancelled)
    }

    @Test
    fun `close-window with no pendrive asks and never flushes`() {
        val fake = FakeBackupController(BackupFixtures.ok.copy(phase = BackupStatus.Phase.DRIVE_MISSING, dirtySince = now - 30_000))
        var exited = 0
        renderScene("exit-confirm", width = 800, height = 400, frames = 10) {
            BackupExitFlowUi(fake, onExit = { exited++ }, onCancel = {})
        }
        assertTrue(fake.calls.isEmpty(), fake.calls.toString())
        assertEquals(0, exited)
    }
}
