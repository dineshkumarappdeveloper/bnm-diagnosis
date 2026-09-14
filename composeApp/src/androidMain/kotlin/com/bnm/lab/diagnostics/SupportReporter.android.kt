package com.bnm.lab.diagnostics

/**
 * BNM Lab's support report is a desktop feature (the lab PC is the product).
 * Here it reports itself unavailable and the Report-a-problem entry points hide.
 */
actual object SupportReporter {
    actual val isAvailable: Boolean = false
    actual val supportEmail: String = "support@bnmapp.com"
    actual val logsLocation: String = ""
    actual suspend fun createAndEmail(description: String): SupportReportResult =
        SupportReportResult(null, false, "Support reports are available in the desktop app.")
    actual fun openLogsFolder(): Boolean = false
    actual fun revealFile(path: String): Boolean = false
    actual fun pendingCrash(): String? = null
    actual fun acknowledgeCrash() {}
}
