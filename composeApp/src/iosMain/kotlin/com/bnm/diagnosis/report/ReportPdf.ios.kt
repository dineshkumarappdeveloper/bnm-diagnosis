package com.bnm.diagnosis.report

// iOS target is staged (desktop-first LIMS); PDF report rendering ships later.

actual fun writeLabReportPdf(doc: ReportDoc): String = ""

actual fun openPdf(path: String): String = "PDF reports arrive on iOS later"

actual fun printPdf(path: String): String = "PDF reports arrive on iOS later"
