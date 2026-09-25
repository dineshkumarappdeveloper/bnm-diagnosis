package com.bnm.lab.backup

import com.bnm.lab.diagnostics.AppLog
import com.bnm.lab.report.openPdf
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType1Font
import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * The A6 "backup card": lab name, the recovery code, the pendrive id and the
 * four restore steps — for the drawer, NOT the PC. Rendered with the same
 * PDFBox base-14 fonts as the reports, into the temp folder only, and opened
 * in the system viewer to print from. It is never filed into the reports
 * archive (that folder is the very data the card protects, and on Windows 11
 * it is often OneDrive-synced).
 */
internal object BackupCard {
    private const val MM = 72f / 25.4f

    fun render(labName: String?, recoveryCode: String, backupId: String, createdAt: ZonedDateTime = ZonedDateTime.now()): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "bnm-lab-backup").apply { mkdirs() }
        val file = File(dir, "backup-card.pdf")
        file.deleteOnExit()
        PDDocument().use { pdf ->
            val page = PDPage(PDRectangle.A6)
            pdf.addPage(page)
            PDPageContentStream(pdf, page).use { cs ->
                val left = 10f * MM
                val width = PDRectangle.A6.width - 2 * left
                var y = PDRectangle.A6.height - 12f * MM
                fun line(text: String, font: PDFont, size: Float, gapAfter: Float = 4.5f * MM, centred: Boolean = false) {
                    cs.beginText()
                    cs.setFont(font, size)
                    val w = font.getStringWidth(text) / 1000f * size
                    cs.newLineAtOffset(if (centred) left + (width - w) / 2 else left, y)
                    cs.showText(text)
                    cs.endText()
                    y -= gapAfter
                }
                line("BNM Lab — Backup card", PDType1Font.HELVETICA_BOLD, 13f, 6f * MM)
                line(safe(labName ?: "Your laboratory"), PDType1Font.HELVETICA, 10f, 8f * MM)
                line("Recovery code", PDType1Font.HELVETICA, 9f, 6f * MM)
                line(RecoveryCode.format(recoveryCode), PDType1Font.COURIER_BOLD, 12.5f, 9f * MM, centred = true)
                line("Backup pendrive: ${backupId.take(8)}   ·   Made ${createdAt.format(DateTimeFormatter.ofPattern("d MMM yyyy"))}",
                    PDType1Font.HELVETICA, 8f, 7f * MM)
                line("If this computer fails:", PDType1Font.HELVETICA_BOLD, 9f, 5f * MM)
                line("1. Install BNM Lab on the new computer.", PDType1Font.HELVETICA, 8.5f)
                line("2. Plug in the backup pendrive.", PDType1Font.HELVETICA, 8.5f)
                line("3. Choose \"Restore from a backup pendrive\".", PDType1Font.HELVETICA, 8.5f)
                line("4. Unlock with your licence key, or this code.", PDType1Font.HELVETICA, 8.5f, 7f * MM)
                line("Keep this card away from the computer.", PDType1Font.HELVETICA_BOLD, 8.5f, 4f * MM)
                line("Anyone with the pendrive AND this code can read", PDType1Font.HELVETICA, 7.5f, 3.5f * MM)
                line("the lab's records.", PDType1Font.HELVETICA, 7.5f)
            }
            pdf.save(file)
        }
        AppLog.i("Backup", "backup card rendered (${file.length() / 1024} KB)")
        return file
    }

    /** Render and hand to the system viewer; the operator prints from there. */
    fun renderAndOpen(labName: String?, recoveryCode: String, backupId: String): Result<Unit> = runCatching {
        val file = render(labName, recoveryCode, backupId)
        val outcome = openPdf(file.absolutePath)
        if (!outcome.startsWith("Opened")) error(outcome)
    }

    /** Base-14 fonts are WinAnsi only; a Tamil or Hindi lab name must not throw. */
    private fun safe(s: String): String = buildString(s.length) {
        for (ch in s) append(if (ch.code in 32..126 || ch.code in 160..255) ch else '?')
    }
}
