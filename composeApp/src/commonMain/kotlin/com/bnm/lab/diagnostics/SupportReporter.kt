package com.bnm.lab.diagnostics

/** What happened when a support report was created and handed to the mail client. */
data class SupportReportResult(
    /** Where the zip landed; null if it could not be written. */
    val filePath: String?,
    /** True if a mail client was opened with the message pre-filled. */
    val emailOpened: Boolean,
    /** One line for the operator. */
    val message: String,
)

/**
 * Builds the support report and hands it to the operator's own mail client.
 *
 * Email rather than an upload, deliberately: it works identically on the
 * OFFLINE edition (the app itself makes no network call — the lab's mail client
 * does, when the lab chooses to send) and on the connected edition, and the lab
 * can see exactly what it is sending before it goes.
 */
expect object SupportReporter {
    /** False where there is nothing to report from (the UI hides itself). */
    val isAvailable: Boolean
    val supportEmail: String
    /** Human-readable location of the log files, `~`-relative. */
    val logsLocation: String

    suspend fun createAndEmail(description: String): SupportReportResult
    fun openLogsFolder(): Boolean
    fun revealFile(path: String): Boolean

    /** A crash captured in an earlier session that the operator has not seen yet. */
    fun pendingCrash(): String?
    /** Mark it seen; the crash record stays on disk (and in reports) until it ages out. */
    fun acknowledgeCrash()
}
