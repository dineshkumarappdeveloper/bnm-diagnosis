package com.bnm.diagnosis.report

/**
 * Styled A4 lab-report PDF engine (per-platform actuals):
 *  • Desktop → Apache PDFBox; open via java.awt.Desktop; print via PDFPageable
 *    + the native PrinterJob dialog.
 *  • Android → android.graphics.pdf.PdfDocument; open via FileProvider
 *    ACTION_VIEW; print via PrintManager with a file-streaming adapter.
 *  • iOS → stubs (target unused today).
 *
 * The thermal text path (`renderLabReport` + ESC/POS) stays untouched — this
 * engine is the A4 "real report" sibling, not a replacement.
 */

/**
 * Render [doc] to `<accession>-report.pdf` in a platform temp/cache dir and
 * return the ABSOLUTE file path (reprints overwrite the same file).
 * Returns "" on platforms without PDF support (iOS stub).
 */
expect fun writeLabReportPdf(doc: ReportDoc): String

/** Open the PDF in the system viewer. Returns a short operator-facing status. */
expect fun openPdf(path: String): String

/** Send the PDF down the OS print path (native dialog where the OS has one).
 *  Returns a short operator-facing status. */
expect fun printPdf(path: String): String
