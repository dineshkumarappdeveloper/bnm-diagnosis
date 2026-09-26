package com.bnm.lab.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.security.MessageDigest

private const val GITHUB_DOWNLOAD_HOST = "github.com"
private const val GITHUB_CDN_HOST = "objects.githubusercontent.com"

actual fun currentUpdatePlatform(): UpdatePlatform {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    return when {
        os.contains("win") -> UpdatePlatform.WINDOWS
        os.contains("mac") -> UpdatePlatform.MACOS
        else -> UpdatePlatform.LINUX
    }
}

actual suspend fun downloadAndLaunchInstaller(
    url: String,
    fileName: String,
    expectedSha256: String?,
    onProgress: (received: Long, total: Long?) -> Unit,
): UpdateInstall = withContext(Dispatchers.IO) {
    runCatching {
        // ── 1. Only ever fetch from where WE publish ─────────────────────────
        // This binary is about to be executed, so the origin is a security
        // boundary, not a detail. HTTPS + a known host is the floor; a redirect
        // to some other host is refused rather than followed.
        val uri = URI(url)
        if (!uri.scheme.equals("https", ignoreCase = true)) {
            return@runCatching UpdateInstall.Failed("Refusing a non-HTTPS update URL.")
        }
        if (uri.host != GITHUB_DOWNLOAD_HOST) {
            return@runCatching UpdateInstall.Failed("Refusing an update from an unexpected host: ${uri.host}")
        }

        val dir = File(System.getProperty("java.io.tmpdir"), "bnm-diagnosis-update").apply { mkdirs() }
        val target = File(dir, fileName)
        if (target.exists()) target.delete()

        // ── 2. Download ──────────────────────────────────────────────────────
        val conn = (uri.toURL().openConnection() as java.net.HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "BNM Lab-Updater")
        }
        val finalHost = conn.url.host
        if (finalHost != GITHUB_DOWNLOAD_HOST && finalHost != GITHUB_CDN_HOST) {
            conn.disconnect()
            return@runCatching UpdateInstall.Failed("Update redirected to an unexpected host: $finalHost")
        }
        val total = conn.contentLengthLong.takeIf { it > 0 }
        val digest = MessageDigest.getInstance("SHA-256")
        var received = 0L
        conn.inputStream.use { input ->
            target.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    digest.update(buf, 0, n)
                    received += n
                    onProgress(received, total)
                }
            }
        }
        conn.disconnect()

        // ── 3. Verify ────────────────────────────────────────────────────────
        // The installers are UNSIGNED, so the published checksum is the only
        // integrity evidence there is. A mismatch means the bytes are not what
        // CI built — delete rather than hand a corrupt or tampered installer to
        // the OS. When the release has no checksum we say so instead of silently
        // pretending the download was verified.
        val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
        var unverified = false
        if (expectedSha256 != null) {
            if (!actualSha.equals(expectedSha256, ignoreCase = true)) {
                target.delete()
                return@runCatching UpdateInstall.Failed(
                    "Downloaded file failed its checksum — discarded. Try again, " +
                        "or download it from the website."
                )
            }
        } else {
            unverified = true
        }

        // ── 4. Hand to the OS ────────────────────────────────────────────────
        // Windows gets a script rather than Desktop.open(). Three reasons, all
        // of them things a lab actually hit:
        //  · Desktop.open() on an .msi can return having launched nothing, and
        //    the app then quits for an installer that never appeared — which
        //    reads as "it downloaded and did nothing".
        //  · The installer cannot replace files this process still holds, so
        //    something has to wait for us to exit. Nothing did.
        //  · Nothing started the app again afterwards, so an update looked like
        //    a crash and the next launch was still the old build — and the
        //    download began all over again.
        val opened = if (currentUpdatePlatform() == UpdatePlatform.WINDOWS) {
            launchWindowsInstaller(target)
        } else {
            runCatching {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                    Desktop.getDesktop().open(target); true
                } else false
            }.getOrDefault(false)
        }

        val note = if (unverified) " (no checksum published for this release — unverified)" else ""
        if (opened) {
            UpdateInstall.LaunchedQuitNow(target.absolutePath + note)
        } else {
            UpdateInstall.DownloadedOnly(
                target.absolutePath,
                "Saved, but this desktop could not open it automatically$note.",
            )
        }
    }.getOrElse { e ->
        UpdateInstall.Failed(e.message ?: "Download failed.")
    }
}

actual fun quitForUpdate() {
    // Hard exit rather than closing the window: SQLDelight has already committed
    // (every write is its own transaction), and lingering non-daemon threads —
    // Ktor's engine, the printer pool — would otherwise keep the JVM alive and
    // the installer blocked on files still in use.
    kotlin.system.exitProcess(0)
}

/**
 * Install on Windows and come back up.
 *
 * A .bat is used rather than a ProcessBuilder chain because the sequence has
 * to OUTLIVE this process: wait for the app to exit so its files unlock, run
 * msiexec, then start the new build. A child process started directly would
 * be competing with its own parent for the files it is trying to replace.
 *
 * `/passive` shows a progress bar and asks nothing — except UAC, which
 * Windows raises on its own and which the operator must accept. A silent
 * `/qn` would leave a cancelled elevation looking identical to a success.
 */
private fun launchWindowsInstaller(msi: File): Boolean = runCatching {
    // Where THIS build runs from, so the script can start it again. Absent in
    // odd launch setups; the update still installs, it just does not relaunch.
    val exe = ProcessHandle.current().info().command().orElse(null)
    val script = File(msi.parentFile, "bnm-lab-update.bat")
    script.writeText(
        buildString {
            appendLine("@echo off")
            // Give the app time to exit and release its files. msiexec would
            // otherwise ask for a reboot, which a bench will simply decline.
            appendLine("ping 127.0.0.1 -n 4 >nul")
            appendLine("msiexec /i \"${msi.absolutePath}\" /passive")
            if (exe != null) {
                appendLine("ping 127.0.0.1 -n 3 >nul")
                appendLine("start \"\" \"$exe\"")
            }
            // Leave no litter in the lab's Downloads folder.
            appendLine("del \"%~f0\"")
        },
    )
    ProcessBuilder("cmd", "/c", "start", "\"\"", "/min", script.absolutePath)
        .directory(msi.parentFile)
        .start()
    true
}.getOrDefault(false)
