package com.bnm.lab.backup

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bnm.lab.screens.backup.BackupSetupActions
import com.bnm.lab.screens.backup.BackupSetupBody
import com.bnm.lab.screens.backup.BackupSetupState
import com.bnm.lab.screens.backup.RestoreActions
import com.bnm.lab.screens.backup.RestoreBody
import com.bnm.lab.screens.backup.RestoreFlowState
import kotlin.test.Test

/**
 * The main steps of the set-up wizard and the restore flow, each built
 * directly in its state and drawn as the dialog body (the AlertDialog shell
 * is Material's; the content is ours).
 */
class BackupDialogsRenderTest {
    private fun body(name: String, content: @androidx.compose.runtime.Composable () -> Unit) =
        renderScene(name, width = 620, height = 700) {
            Surface(Modifier.padding(16.dp)) { content() }
        }

    // ── Set-up wizard ──

    @Test
    fun `setup - choose the pendrive, with every verdict on show`() {
        val s = BackupSetupState().apply {
            loadingCandidates = false
            candidates = listOf(BackupFixtures.pendrive, BackupFixtures.systemDisk, BackupFixtures.otherLabsDrive, BackupFixtures.tinyDrive)
            folder = BackupFixtures.pendrive.path
        }
        body("setup-drive") { BackupSetupBody(s, BackupSetupActions()) }
    }

    @Test
    fun `setup - no pendrive found, folder refused`() {
        val s = BackupSetupState().apply {
            loadingCandidates = false
            folderError = "That is this computer's own disk — a backup there is lost with it"
        }
        body("setup-drive-empty") { BackupSetupBody(s, BackupSetupActions()) }
    }

    @Test
    fun `setup - type the licence key, after a wrong attempt`() {
        val s = BackupSetupState().apply {
            step = BackupSetupState.Step.KEY
            keyError = "That is not this lab's licence key — check it and try again"
        }
        body("setup-key") { BackupSetupBody(s, BackupSetupActions()) }
    }

    @Test
    fun `setup - working`() {
        val s = BackupSetupState().apply { step = BackupSetupState.Step.WORKING }
        body("setup-working") { BackupSetupBody(s, BackupSetupActions()) }
    }

    @Test
    fun `setup - recovery code with the first backup done`() {
        val s = BackupSetupState().apply {
            step = BackupSetupState.Step.CODE
            recoveryCode = "ABCDE-FGHJK-MNPQR-STVWX-YZ234"
            firstBackupLine = BackupCopy.firstBackupLine(12L shl 20, 1204)
            printMessage = "Backup card sent to the printer"
        }
        body("setup-code") { BackupSetupBody(s, BackupSetupActions()) }
    }

    // ── Restore ──

    @Test
    fun `restore - pick the pendrive`() {
        val s = RestoreFlowState().apply {
            loadingCandidates = false
            candidates = listOf(BackupFixtures.pendrive.copy(existingBackupOf = "Demo Lab"), BackupFixtures.systemDisk)
        }
        body("restore-folder") { RestoreBody(s, RestoreActions()) }
    }

    @Test
    fun `restore - generations newest first, one damaged`() {
        val s = RestoreFlowState().apply {
            step = RestoreFlowState.Step.GENERATIONS
            generations = listOf(
                BackupFixtures.generation(1204, 2),
                BackupFixtures.generation(1203, 5, damaged = true),
                BackupFixtures.generation(1190, 30),
            )
        }
        body("restore-generations") { RestoreBody(s, RestoreActions()) }
    }

    @Test
    fun `restore - generations from a folder on this computer's own disk`() {
        val s = RestoreFlowState().apply {
            step = RestoreFlowState.Step.GENERATIONS
            folderOnOwnDisk = true
            generations = listOf(BackupFixtures.generation(1204, 2), BackupFixtures.generation(1190, 30))
        }
        body("restore-generations-own-disk") { RestoreBody(s, RestoreActions()) }
    }

    @Test
    fun `restore - unlock with the recovery code`() {
        val s = RestoreFlowState(BackupFixtures.generation(1204, 2)).apply {
            useRecoveryCode = true
            secret = "ABCDE-FGHJK"
            error = "That code doesn't open this backup — check it and try again"
        }
        body("restore-unlock-code") { RestoreBody(s, RestoreActions()) }
    }

    @Test
    fun `restore - preview, allowed`() {
        val s = RestoreFlowState(BackupFixtures.generation(1204, 2)).apply {
            step = RestoreFlowState.Step.PREVIEW
            preview = BackupFixtures.preview.copy(currentPatientsHere = 37)
        }
        body("restore-preview") { RestoreBody(s, RestoreActions()) }
    }

    @Test
    fun `restore - preview refused, newer app`() {
        val s = RestoreFlowState(BackupFixtures.generation(1204, 2)).apply {
            step = RestoreFlowState.Step.PREVIEW
            preview = BackupFixtures.preview.copy(newerThanThisApp = true, appVersion = "9.9.9")
        }
        body("restore-preview-refused") { RestoreBody(s, RestoreActions()) }
    }

    @Test
    fun `restore - done`() {
        val s = RestoreFlowState(BackupFixtures.generation(1204, 2)).apply {
            step = RestoreFlowState.Step.DONE
            staged = RestoreStaged(listOf("Reports folder reset to the default."))
        }
        body("restore-done") { RestoreBody(s, RestoreActions()) }
    }
}
