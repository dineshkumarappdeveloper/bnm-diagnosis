package com.bnm.diagnosis.report

// iOS renders no PDFs yet (see ReportPdf.ios.kt), so there is nothing to read
// back and nothing to upload.
actual fun readReportBytes(path: String): ByteArray? = null
