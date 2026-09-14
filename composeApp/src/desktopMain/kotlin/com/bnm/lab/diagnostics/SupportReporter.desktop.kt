package com.bnm.lab.diagnostics

import com.bnm.lab.BuildInfo
import com.bnm.lab.db.appDataDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

actual object SupportReporter {

    actual val isAvailable: Boolean = true
    actual val supportEmail: String = "support@bnmapp.com"
    actual val logsLocation: String
        get() = LogRedactor.redact(DesktopDiagnostics.logDir.absolutePath)

    actual suspend fun createAndEmail(description: String): SupportReportResult = withContext(Dispatchers.IO) {
        AppLog.i("Support", "creating support report (description ${description.length} chars)")
        val created = ZonedDateTime.now()
        val summary = buildSummary(description, created)
        val zip = writeZip(summary, created)
        AppLog.i("Support", "support report written: ${zip.name} (${zip.length() / 1024} KB)")

        val revealed = revealFile(zip.absolutePath)
        val mailed = openMail(
            SupportMail.fittedMailtoUri(
                to = supportEmail,
                subject = "BNM Lab support report — ${DiagnosticsContext.headline().ifBlank { "BNM Lab" }}",
                appVersion = BuildInfo.VERSION,
                headline = DiagnosticsContext.headline(),
                fileName = zip.name,
                description = description,
                environment = DesktopDiagnostics.environmentLine(),
            ),
        )
        AppLog.i("Support", "report hand-off: revealed=$revealed mailClient=$mailed")
        SupportReportResult(
            filePath = LogRedactor.redact(zip.absolutePath),
            emailOpened = mailed,
            // "mailed" only means the OS accepted the mailto: link. On a Mac whose
            // lab uses Gmail in a browser, Mail.app starts its account wizard and no
            // draft appears — so never claim one did.
            message = if (mailed) {
                "We asked your email app to open a message to BNM support. If a draft appeared, attach " +
                    "${zip.name}${if (revealed) " (highlighted in the folder that just opened)" else ""} and send."
            } else {
                "The report is ready: ${zip.name}."
            },
        ).also { lastReport = zip }
    }

    /** The real path of the last report — the one in [SupportReportResult] is `~`-relative. */
    @Volatile private var lastReport: File? = null

    actual fun openLogsFolder(): Boolean = reveal(DesktopDiagnostics.logDir, select = false)

    actual fun revealFile(path: String): Boolean {
        val f = lastReport?.takeIf { LogRedactor.redact(it.absolutePath) == path || it.absolutePath == path }
            ?: File(path.replaceFirst("~", System.getProperty("user.home").orEmpty()))
        return reveal(f, select = true)
    }

    actual fun pendingCrash(): String? =
        DesktopDiagnostics.pendingCrashFile()?.let { runCatching { it.readText() }.getOrNull() }

    actual fun acknowledgeCrash() = DesktopDiagnostics.acknowledgeCrash()

    // ── building the report ──────────────────────────────────────────────────

    internal suspend fun buildSummary(description: String, created: ZonedDateTime): String = buildString {
        val data = runCatching { appDataDir() }.getOrNull()
        val db = data?.let { File(it, "bnm_chat.db") }
        val logs = DesktopDiagnostics.files.ownedFiles()
        append("BNM Lab — support report\n")
        append("Created:     ").append(created).append('\n')
        append("Version:     ").append(BuildInfo.VERSION).append('\n')
        append("System:      ").append(DesktopDiagnostics.environmentLine()).append('\n')
        append("Timezone:    ").append(TimeZone.getDefault().id).append(" · locale ").append(Locale.getDefault()).append('\n')
        if (data != null) {
            append("Data folder: ").append(data.absolutePath).append(" · database ")
            append(db?.takeIf { it.exists() }?.let { "${it.length() / 1024} KB" } ?: "MISSING").append(" · ")
            append("disk free ${data.usableSpace / 1024 / 1024 / 1024} GB\n")
        }
        append("Log folder:  ").append(DesktopDiagnostics.logDir.absolutePath)
            .append(" · ${logs.size} files, ${logs.sumOf { it.length() } / 1024} KB\n\n")
        append("What happened (from the lab):\n")
        append(description.ifBlank { "(not described)" }).append("\n\n")
        append(DiagnosticsContext.render())
    }.let(LogRedactor::redact)

    internal fun writeZip(summary: String, created: ZonedDateTime): File {
        val home = System.getProperty("user.home").orEmpty()
        val candidates = listOf(
            File(home, "Downloads"), File(home, "Desktop"), DesktopDiagnostics.logDir,
            File(System.getProperty("java.io.tmpdir").orEmpty()),
        ).filter { it.isDirectory }
        val files = DesktopDiagnostics.files.ownedFiles() + listOfNotNull(DesktopDiagnostics.pendingCrashFile())
        var last: Throwable? = null
        // TRY the write rather than trusting canWrite(): on Windows it ignores ACLs
        // and Controlled Folder Access, so an unwritable Downloads looks writable.
        for (dir in candidates) {
            try {
                return SupportZip.write(summary, files, dir, created)
            } catch (e: Exception) {
                last = e
                AppLog.w("Support", "could not write the report into one candidate folder: ${e::class.simpleName}")
            }
        }
        throw IllegalStateException("no folder accepted the report", last)
    }

    // ── handing it to the OS ──────────────────────────────────────────────────

    private fun openMail(mailtoUri: String): Boolean = runCatching {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.MAIL)) return false
        Desktop.getDesktop().mail(URI(mailtoUri))
        true
    }.getOrElse { AppLog.w("Support", "no mail client could be opened: ${it::class.simpleName}"); false }

    private fun reveal(file: File, select: Boolean): Boolean = runCatching {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val cmd = when {
            os.contains("mac") -> if (select) listOf("open", "-R", file.absolutePath) else listOf("open", file.absolutePath)
            os.contains("win") -> if (select) listOf("explorer.exe", "/select,", file.absolutePath) else listOf("explorer.exe", file.absolutePath)
            else -> listOf("xdg-open", (if (select) file.parentFile else file).absolutePath)
        }
        ProcessBuilder(cmd).start()
        true
    }.getOrElse {
        runCatching { Desktop.getDesktop().open(if (select) file.parentFile else file); true }.getOrDefault(false)
    }
}

/** The report archive, built from explicit inputs so tests never touch the real log folder. */
internal object SupportZip {
    /**
     * Seconds in the name, and never overwrite: a crash-prompt report followed a
     * minute later by one from the Help menu must not replace the file its still-
     * open mail draft names. Written to a temp file and moved into place, so a
     * half-written zip (disk full) never sits under the real name.
     */
    fun write(summary: String, logFiles: List<File>, targetDir: File, created: ZonedDateTime): File {
        val base = "BNMLab-support-${created.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}"
        var zip = File(targetDir, "$base.zip")
        var n = 2
        while (zip.exists()) zip = File(targetDir, "$base-${n++}.zip")
        val tmp = File.createTempFile("BNMLab-support-", ".part", targetDir)
        try {
            ZipOutputStream(tmp.outputStream().buffered()).use { out ->
                out.putNextEntry(ZipEntry("summary.txt"))
                out.write(summary.toByteArray(Charsets.UTF_8))
                out.closeEntry()
                for (f in logFiles) {
                    out.putNextEntry(ZipEntry("logs/${f.name}"))
                    // Redact AGAIN on the way out: files written by an older build,
                    // before a rule existed, must not ship what that rule now catches.
                    f.bufferedReader(Charsets.UTF_8).useLines { lines ->
                        for (line in lines) {
                            out.write(LogRedactor.redact(line).toByteArray(Charsets.UTF_8))
                            out.write('\n'.code)
                        }
                    }
                    out.closeEntry()
                }
            }
            try {
                java.nio.file.Files.move(tmp.toPath(), zip.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(tmp.toPath(), zip.toPath())
            }
            return zip
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }
}

/** Pure mail-composition helpers, split out so they can be tested. */
internal object SupportMail {

    /**
     * Windows hands mailto: URIs to the mail client through a path that truncates
     * around 2,000 characters. The budget is on the ENCODED URI: every non-Latin
     * character costs 9 characters once percent-encoded, so a 600-character Hindi
     * or Tamil description alone would be 5,000+.
     */
    const val MAX_URI_CHARS = 1_800

    fun body(appVersion: String, headline: String, fileName: String, description: String, environment: String): String =
        buildString {
            append("Hello BNM support,\n\n")
            append(description.trim().ifBlank { "(please describe what happened)" })
            append("\n\n---\n")
            append("App: BNM Lab ").append(appVersion).append('\n')
            if (headline.isNotBlank()) append(headline).append('\n')
            append(environment).append('\n')
            append("\nPlease find the support report attached: ").append(fileName).append('\n')
        }.let(LogRedactor::redact)

    /** RFC 6068: spaces are %20 (URLEncoder's `+` would reach the mail client literally). */
    fun mailtoUri(to: String, subject: String, body: String): String {
        fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
        return "mailto:$to?subject=${enc(subject)}&body=${enc(body)}"
    }

    /**
     * The mailto: URI with the lab's description shortened — never the attachment
     * line or the version — until the encoded URI fits [MAX_URI_CHARS]. Cuts on a
     * code-point boundary so a surrogate pair is never split.
     */
    fun fittedMailtoUri(
        to: String, subject: String, appVersion: String, headline: String,
        fileName: String, description: String, environment: String,
    ): String {
        var desc = description.trim()
        while (true) {
            val uri = mailtoUri(to, subject, body(appVersion, headline, fileName, desc, environment))
            if (uri.length <= MAX_URI_CHARS || desc.isEmpty()) return uri
            val cps = desc.codePointCount(0, desc.length)
            val keep = (cps * 3 / 4).coerceAtMost(cps - 1).coerceAtLeast(0)
            desc = desc.substring(0, desc.offsetByCodePoints(0, keep)).trimEnd() + if (keep > 0) "…" else ""
            if (keep == 0) desc = ""
        }
    }
}
