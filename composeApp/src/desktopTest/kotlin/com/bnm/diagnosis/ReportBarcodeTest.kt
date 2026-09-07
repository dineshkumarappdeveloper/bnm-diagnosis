package com.bnm.diagnosis

import com.bnm.diagnosis.report.ReportPagination
import com.bnm.diagnosis.report.accessionBarcode
import com.bnm.diagnosis.report.sampleReportDoc
import com.bnm.diagnosis.report.writeLabReportPdf
import org.apache.pdfbox.contentstream.operator.Operator
import org.apache.pdfbox.pdfparser.PDFStreamParser
import org.apache.pdfbox.pdmodel.PDDocument
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The accession barcode in the patient box: the tube's code, again on the paper. */
class ReportBarcodeTest {

    @Test
    fun `the accession encodes, and junk does not`() {
        assertEquals(11 * 15 + 2, accessionBarcode("ACC-S1-00042")!!.size, "15 symbols incl. start/check/stop")
        assertNull(accessionBarcode(""))
        assertNull(accessionBarcode("ACC-₹1"))
    }

    /** Filled-rectangle operators on page 1 — bars and QR modules are the only
     *  things drawn that way, and the QR lives at the sign-off. */
    private fun rectOps(accession: String): Int {
        val doc = sampleReportDoc(pagination = ReportPagination.PER_TEST).copy(accession = accession)
        return PDDocument.load(File(writeLabReportPdf(doc))).use { pdf ->
            val parser = PDFStreamParser(pdf.getPage(0))
            parser.parse()
            parser.tokens.count { it is Operator && it.name == "re" }
        }
    }

    @Test
    fun `every sheet's patient box carries the accession bars`() {
        val with = rectOps("ACC-S1-00042")
        val without = rectOps("") // unencodable → text-only box, everything else identical
        // 15 symbols (start + 11 data/switch + check ... ) — every Code 128 symbol
        // has exactly 3 bars, the stop has 4: 14 x 3 + 4 = 46 filled runs.
        assertEquals(46, with - without, "bar runs drawn for ACC-S1-00042")
    }
}
