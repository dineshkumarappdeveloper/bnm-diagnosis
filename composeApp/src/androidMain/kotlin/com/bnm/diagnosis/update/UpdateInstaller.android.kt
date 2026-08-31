package com.bnm.diagnosis.update

/**
 * Mobile builds are distributed by the store, which owns updating. Self-updating
 * would duplicate it at best and violate store policy at worst, so this reports
 * "not supported" and the panel tells the user where updates come from instead of
 * showing a button that cannot work.
 */
actual suspend fun downloadAndLaunchInstaller(
    url: String,
    fileName: String,
    expectedSha256: String?,
    onProgress: (received: Long, total: Long?) -> Unit,
): UpdateInstall = UpdateInstall.NotSupported

actual fun currentUpdatePlatform(): UpdatePlatform = UpdatePlatform.STORE_MANAGED

/** Mobile updates are the store's job; nothing to quit for. */
actual fun quitForUpdate() = Unit
