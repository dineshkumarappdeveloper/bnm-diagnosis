package com.bnm.lab.backup

import com.bnm.lab.license.LicenseManager
import com.bnm.lab.screens.backup.BackupSettingsScreen
import com.bnm.lab.staff.Staff
import com.bnm.lab.staff.StaffRole
import com.russhwolf.settings.PropertiesSettings
import java.util.Properties
import kotlin.test.Test

/**
 * The Backup page with the fake engine in each state a lab will actually
 * see. Nothing here touches real preferences: the LicenseManager sits on an
 * in-memory store (the no-arg one on JVM is the operator's real prefs).
 */
class BackupSettingsRenderTest {
    private val now = BackupFixtures.now
    private val owner = Staff(id = "o1", name = "Lab Owner", role = StaffRole.OWNER)
    private val receptionist = Staff(id = "r1", name = "Asha", role = StaffRole.RECEPTIONIST)

    private fun licence() = LicenseManager(PropertiesSettings(Properties()), { true }, { now / 1000 })

    private fun page(name: String, status: BackupStatus, who: Staff?) = renderScene(name, width = 1100, height = 1000) {
        BackupSettingsScreen(
            controller = FakeBackupController(status),
            licenseManager = licence(),
            labName = "Demo Lab",
            signedInStaff = who,
            verifyPin = { _, _ -> true },
            rowCounts = { null },
            onBack = {},
        )
    }

    @Test
    fun `page - not set up, owner`() = page("page-not-set-up", BackupStatus(), owner)

    @Test
    fun `page - backed up, owner`() = page("page-ok-owner", BackupFixtures.ok, owner)

    @Test
    fun `page - pendrive missing, receptionist sees owner rows disabled`() =
        page("page-drive-missing-receptionist", BackupFixtures.ok.copy(phase = BackupStatus.Phase.DRIVE_MISSING, driveName = null, dirtySince = now - 60_000), receptionist)

    @Test
    fun `page - failing with the row-count guard tripped`() = page(
        "page-failing-paused",
        BackupFixtures.ok.copy(
            phase = BackupStatus.Phase.FAILING,
            lastError = "Replace the pendrive; the last good backup is 09:12",
            retentionPaused = true,
            restoredFromBackup = true,
        ),
        owner,
    )

    @Test
    fun `page - nobody signed in`() = page("page-no-session", BackupFixtures.ok.copy(phase = BackupStatus.Phase.WORKING), null)
}
