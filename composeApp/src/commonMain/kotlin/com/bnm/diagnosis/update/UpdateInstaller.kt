package com.bnm.diagnosis.update

/**
 * Platform half of the in-app updater.
 *
 * HONEST SCOPE: on desktop this downloads the installer, verifies it, hands it to
 * the OS and asks the app to quit. It does NOT replace the running application in
 * place — a running Compose Desktop app cannot overwrite its own bundle, and doing
 * it properly needs a helper process plus code signing (Sparkle on macOS, Squirrel
 * on Windows). The installers are currently unsigned, so an in-place swap would
 * also be a binary of unverified provenance replacing itself. Launching the real
 * OS installer keeps the user in the loop and keeps Gatekeeper/SmartScreen in the
 * path, which is the right trade until signing exists.
 */
expect suspend fun downloadAndLaunchInstaller(
    url: String,
    fileName: String,
    expectedSha256: String?,
    onProgress: (received: Long, total: Long?) -> Unit,
): UpdateInstall

/** Which installer this build wants from a release. */
expect fun currentUpdatePlatform(): UpdatePlatform

sealed interface UpdateInstall {
    /**
     * The installer is downloaded, verified and now open. The app should QUIT —
     * on Windows an MSI cannot replace files that are in use, and on macOS the
     * user cannot drag over a running .app.
     */
    data class LaunchedQuitNow(val path: String) : UpdateInstall
    /** Downloaded and verified, but the OS would not open it; tell the user where it is. */
    data class DownloadedOnly(val path: String, val reason: String) : UpdateInstall
    data class Failed(val message: String) : UpdateInstall
    /** Mobile: updates come from the store. */
    data object NotSupported : UpdateInstall
}

/**
 * Quit the app so a just-launched installer can replace it.
 *
 * Necessary, not cosmetic: an MSI cannot overwrite files that are in use, and on
 * macOS the user cannot drag a new .app over one that is running. No-op on mobile,
 * which never reaches this path.
 */
expect fun quitForUpdate()
