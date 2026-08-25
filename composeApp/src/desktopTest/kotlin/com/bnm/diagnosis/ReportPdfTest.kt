package com.bnm.diagnosis

import com.bnm.diagnosis.report.LetterheadMode
import com.bnm.diagnosis.report.ReportPalette
import com.bnm.diagnosis.report.sampleReportDoc
import com.bnm.diagnosis.report.writeLabReportPdf
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The styled A4 PDF engine must produce a real, non-trivial PDF in BOTH
 * letterhead modes (drawn letterhead vs blank reserved space). The sample doc
 * covers every row emphasis (N/L/H/CL/CH/A) and three sections, so a
 * successful write exercises the full layout path incl. pagination inputs.
 */
class ReportPdfTest {

    @Test
    fun writesPrintedLetterheadPdf() = assertRealPdf(LetterheadMode.PRINTED)

    @Test
    fun writesPreprintedLetterpadPdf() = assertRealPdf(LetterheadMode.PREPRINTED)

    private fun assertRealPdf(mode: LetterheadMode) {
        val path = writeLabReportPdf(
            sampleReportDoc(
                labName = "Sunrise Diagnostics",
                mode = mode,
                headerMm = 40f,
                footerMm = 20f,
                accentRgb = ReportPalette.TEAL,
            )
        )
        val file = File(path)
        assertTrue(file.isAbsolute, "path must be absolute: $path")
        assertTrue(file.exists(), "PDF file must exist: $path")
        assertTrue(file.length() > 5_120, "PDF should be non-trivial (>5KB), was ${file.length()} bytes")
        val head = ByteArray(5)
        file.inputStream().use { it.read(head) }
        assertEquals("%PDF-", head.decodeToString(), "file must start with the PDF magic")
        assertTrue(file.name == "ACC-S1-00042-report.pdf", "filename is <accession>-report.pdf, was ${file.name}")
    }
}
