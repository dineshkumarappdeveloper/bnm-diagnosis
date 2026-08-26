package com.bnm.diagnosis.report

/**
 * Read back a PDF that [writeLabReportPdf] just produced.
 *
 * Only [ReportUploader] needs this: it re-renders a queued report and has to
 * put the bytes on the wire. Returns null when the file is missing or
 * unreadable — the row then stays queued instead of publishing nothing.
 */
expect fun readReportBytes(path: String): ByteArray?
