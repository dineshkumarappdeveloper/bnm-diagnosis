package com.bnm.lab.report

// iOS target is staged (desktop-first LIMS); PDF report rendering ships later.

actual fun writeLabReportPdf(doc: ReportDoc): String = ""

actual fun renderLabReportPdfBytes(doc: ReportDoc): ByteArray? = null

actual fun openPdf(path: String): String = "PDF reports arrive on iOS later"

actual fun printPdf(path: String): String = "PDF reports arrive on iOS later"

/** Mobile has no unattended printing path; auto-print is desktop-only. */
actual fun printPdfSilently(path: String, printerName: String?): String =
    "Automatic printing is available on the lab PC only."

actual fun availablePrinters(): List<String> = emptyList()
