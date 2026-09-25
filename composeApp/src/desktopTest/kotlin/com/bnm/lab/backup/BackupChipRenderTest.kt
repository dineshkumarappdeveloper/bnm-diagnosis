package com.bnm.lab.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import com.bnm.lab.screens.backup.BackupExitFlowUi
import com.bnm.lab.screens.backup.BackupFlushOverlay
import com.bnm.lab.screens.backup.BackupNotBackedUpBanner
import com.bnm.lab.screens.backup.BackupStatusChip
import com.bnm.lab.ui.theme.AppTheme
import com.bnm.lab.ui.theme.ThemeChoice
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The five looks of the home chip, wide and compact, one scene per state —
 * plus the banner and the close-window flush, which run their effects only
 * when actually composed, and the flush scrim as a wall against the app
 * beneath it.
 */
class BackupChipRenderTest {
    private val now = BackupFixtures.now

    /**
     * A "Save result" button with, or without, the flush scrim over it; a
     * click where the button is. Returns how many times the button fired.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun clickBeneath(name: String, overlay: Boolean): Int {
        var clicks = 0
        val out = File("build/backup-render").apply { mkdirs() }
        ImageComposeScene(600, 300, Density(1f)) {
            AppTheme(themeChoice = ThemeChoice.LIGHT) {
                Box(Modifier.fillMaxSize()) {
                    Button(onClick = { clicks++ }, modifier = Modifier.align(Alignment.TopStart).padding(16.dp)) { Text("Save result") }
                    if (overlay) BackupFlushOverlay()
                }
            }
        }.use { scene ->
            var img = scene.render(0)
            val at = Offset(60f, 40f)
            scene.sendPointerEvent(PointerEventType.Move, at, timeMillis = 1)
            scene.sendPointerEvent(PointerEventType.Press, at, timeMillis = 2, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, at, timeMillis = 3, buttons = PointerButtons(), button = PointerButton.Primary)
            repeat(3) { i -> Thread.sleep(20); img = scene.render((i + 1) * 50_000_000L) }
            File(out, "$name.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
        return clicks
    }

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
    fun `a flush that ran past the deadline but finished is not a question`() {
        // The engine answered false at the cap; by the time the screen reads the
        // status, the snapshot has landed and nothing is unsaved any more.
        val fake = FakeBackupController(BackupFixtures.ok.copy(phase = BackupStatus.Phase.PENDING, dirtySince = now - 30_000))
        fake.flushResult = false
        fake.onFlush = { fake.statusFlow.value = BackupFixtures.ok }
        var exited = 0
        var cancelled = 0
        renderScene("exit-flush-late", width = 800, height = 400, frames = 10) {
            BackupExitFlowUi(fake, onExit = { exited++ }, onCancel = { cancelled++ })
        }
        assertEquals(1, exited)
        assertEquals(0, cancelled)
    }

    @Test
    fun `a flush that failed with changes still unsaved asks`() {
        val fake = FakeBackupController(BackupFixtures.ok.copy(phase = BackupStatus.Phase.PENDING, dirtySince = now - 30_000))
        fake.flushResult = false
        var exited = 0
        renderScene("exit-flush-failed", width = 800, height = 400, frames = 10) {
            BackupExitFlowUi(fake, onExit = { exited++ }, onCancel = {})
        }
        assertEquals(listOf("flushOnExit:${BackupExitFlow.FLUSH_MAX_WAIT_MS}"), fake.calls)
        assertEquals(0, exited, "still unsaved: the question, not the exit")
    }

    @Test
    fun `control - the click lands on the button when nothing covers it`() {
        assertEquals(1, clickBeneath("overlay-control", overlay = false))
    }

    @Test
    fun `the flush scrim swallows the click that would have saved a result beneath it`() {
        assertEquals(0, clickBeneath("overlay-scrim", overlay = true))
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
