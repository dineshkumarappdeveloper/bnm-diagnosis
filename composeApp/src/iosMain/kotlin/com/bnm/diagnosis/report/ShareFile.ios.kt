package com.bnm.diagnosis.report

/** iOS renders no report PDF yet ([writeLabReportPdf] returns ""), so there is
 *  nothing to attach; the caller falls back to sending the link. */
actual fun shareFile(path: String, mimeType: String, text: String, waPhone: String?): String =
    "Sharing the file arrives on iOS later"
