package com.bnm.lab.report

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

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

/**
 * Render [doc] to PDF bytes in memory — the same document [writeLabReportPdf]
 * writes, but no file. For bytes that go on the wire: nothing is left in the
 * temp dir, and a copy of `<accession>-report.pdf` held open by a PDF viewer
 * (Windows locks it) cannot fail the send. Null on platforms without PDF
 * support (iOS stub). Throws when rendering fails, like [writeLabReportPdf].
 */
expect fun renderLabReportPdfBytes(doc: ReportDoc): ByteArray?

/**
 * [doc] as a base64 PDF for a JSON body — the file the WhatsApp Business send
 * hands the server, which passes it straight to Meta. Never throws: a render
 * that fails comes back as a failure for the dialog to explain; success(null)
 * means this platform renders no PDF.
 */
@OptIn(ExperimentalEncodingApi::class)
fun reportPdfBase64(
    doc: ReportDoc,
    render: (ReportDoc) -> ByteArray? = ::renderLabReportPdfBytes,
): Result<String?> = runCatching {
    render(doc)?.takeIf { it.isNotEmpty() }?.let { Base64.Default.encode(it) }
}

/** Open the PDF in the system viewer. Returns a short operator-facing status. */
expect fun openPdf(path: String): String

/** Send the PDF down the OS print path (native dialog where the OS has one).
 *  Returns a short operator-facing status. */
expect fun printPdf(path: String): String

/**
 * Print with NO dialog, to [printerName] when given and the system default
 * otherwise. For auto-print, where a modal dialog per sample would defeat the
 * entire point — nobody is standing at the bench to dismiss it.
 *
 * Returns a status that NAMES the printer it used. A lab running this
 * unattended has to be able to answer "where did that go?", and on a PC with
 * a PDF writer, a label printer and an A4 laser attached, "Sent to printer"
 * is not an answer.
 */
expect fun printPdfSilently(path: String, printerName: String?): String

/** Printers this computer can see, for the settings picker. */
expect fun availablePrinters(): List<String>
