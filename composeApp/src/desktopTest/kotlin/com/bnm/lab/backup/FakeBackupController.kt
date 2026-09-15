package com.bnm.lab.backup

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A scriptable [BackupController] for the screen tests: every answer is a
 * field the test sets, every call is recorded. No files, no prefs, no clock.
 */
class FakeBackupController(initial: BackupStatus = BackupStatus()) : BackupController {
    val statusFlow = MutableStateFlow(initial)
    override val status: StateFlow<BackupStatus> = statusFlow

    var candidates: List<DriveCandidate> = emptyList()
    var pickedFolder: String? = null
    var setUpResult: Result<String> = Result.success("ABCDE-FGHJK-MNPQR-STVWX-YZ234")
    var backupNowResult: Result<Unit> = Result.success(Unit)
    var stopResult: Result<Unit> = Result.success(Unit)
    var recoveryCode: String? = "ABCDE-FGHJK-MNPQR-STVWX-YZ234"
    var printResult: Result<Unit> = Result.success(Unit)
    var purgeResult: Result<Unit> = Result.success(Unit)
    var generations: List<BackupGeneration> = emptyList()
    var findResult: Result<List<BackupGeneration>>? = null
    var previewResult: Result<RestorePreview> = Result.failure(IllegalStateException("no preview scripted"))
    var stageResult: Result<RestoreStaged> = Result.success(RestoreStaged(emptyList()))
    var offer: BackupGeneration? = null
    var flushResult: Boolean = true

    val calls = mutableListOf<String>()
    var closed = false

    override suspend fun listDriveCandidates(): List<DriveCandidate> { calls += "listDriveCandidates"; return candidates }
    override suspend fun pickFolder(title: String): String? { calls += "pickFolder"; return pickedFolder }
    override suspend fun setUp(folder: String, licenceKey: String): Result<String> { calls += "setUp:$folder"; return setUpResult }
    override suspend fun backupNow(): Result<Unit> { calls += "backupNow"; return backupNowResult }
    override suspend fun stop(): Result<Unit> { calls += "stop"; return stopResult }
    override suspend fun recoveryCode(): String? { calls += "recoveryCode"; return recoveryCode }
    override suspend fun printBackupCard(): Result<Unit> { calls += "printBackupCard"; return printResult }
    override suspend fun purgeDrive(): Result<Unit> { calls += "purgeDrive"; return purgeResult }
    override suspend fun findBackupsAt(folder: String): Result<List<BackupGeneration>> {
        calls += "findBackupsAt:$folder"; return findResult ?: Result.success(generations)
    }
    override suspend fun preview(gen: BackupGeneration, unlock: Unlock): Result<RestorePreview> {
        calls += "preview:${gen.seq}:${unlock::class.simpleName}"; return previewResult
    }
    override suspend fun stageRestore(gen: BackupGeneration, unlock: Unlock): Result<RestoreStaged> {
        calls += "stageRestore:${gen.seq}"; return stageResult
    }
    override suspend fun restoreOfferAtLaunch(): BackupGeneration? { calls += "restoreOfferAtLaunch"; return offer }
    override fun closeApp() { calls += "closeApp"; closed = true }
    override suspend fun flushOnExit(maxWaitMs: Long): Boolean { calls += "flushOnExit:$maxWaitMs"; return flushResult }
    override suspend fun beforeTenantWipe() { calls += "beforeTenantWipe" }
    override suspend fun afterTenantWipe() { calls += "afterTenantWipe" }
    override fun dismissBannerForSession() {
        calls += "dismissBannerForSession"
        statusFlow.value = statusFlow.value.copy(bannerDismissed = true)
    }
    override fun markRegisteredOnline() {
        calls += "markRegisteredOnline"
        statusFlow.value = statusFlow.value.copy(restoredFromBackup = false, restoredFromDeviceRowId = null)
    }
}

/** Shared fixtures for the backup screen tests. */
object BackupFixtures {
    /** A fixed "now": 15 Sep 2026 10:30 local, expressed via today's real date so "today" labels hold. */
    val now: Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
    const val HOUR = 3_600_000L

    val pendrive = DriveCandidate(
        path = "/Volumes/BNM BACKUP", displayName = "BNM BACKUP (E:)", fsType = "exFAT",
        totalBytes = 32L shl 30, freeBytes = 29L shl 30, removableLikely = true,
        sameVolumeAsData = false, existingBackupOf = null,
    )
    val systemDisk = DriveCandidate(
        path = "/", displayName = "Macintosh HD", fsType = "APFS",
        totalBytes = 500L shl 30, freeBytes = 120L shl 30, removableLikely = false,
        sameVolumeAsData = true, existingBackupOf = null,
    )
    val otherLabsDrive = DriveCandidate(
        path = "/Volumes/OLD", displayName = "OLD (F:)", fsType = "FAT32",
        totalBytes = 8L shl 30, freeBytes = 5L shl 30, removableLikely = true,
        sameVolumeAsData = false, existingBackupOf = "Sunrise Diagnostics",
    )
    val tinyDrive = DriveCandidate(
        path = "/Volumes/TINY", displayName = "TINY (G:)", fsType = "FAT32",
        totalBytes = 1L shl 30, freeBytes = 300L shl 20, removableLikely = true,
        sameVolumeAsData = false, existingBackupOf = null,
    )

    fun generation(seq: Long, hoursAgo: Long, damaged: Boolean = false, lab: String? = "Demo Lab") = BackupGeneration(
        seq = seq, createdAt = now - hoursAgo * HOUR, bytes = 12L shl 20,
        path = "/Volumes/BNM BACKUP/BNM Lab Backup/snapshots/2026-09/bnmlab-$seq.bnmlab",
        labName = lab, damaged = damaged,
    )

    val preview = RestorePreview(
        labName = "Demo Lab", createdAt = now - 2 * HOUR, seq = 1204, appVersion = "1.2.0",
        patients = 1204, orders = 3410, results = 22118, staff = 6, tests = 223,
        sameLicence = true, newerThanThisApp = false, currentPatientsHere = 0,
    )

    val ok = BackupStatus(
        phase = BackupStatus.Phase.OK, driveName = "BNM BACKUP (E:)",
        lastOkAt = now - 20 * 60_000L, lastVerifiedAt = now - 17 * 60_000L,
        generations = 14, bytesOnDrive = 128L shl 20,
    )
}
