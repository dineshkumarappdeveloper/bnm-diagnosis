package com.bnm.lab

import com.bnm.lab.lab.ResultGraph
import com.bnm.lab.report.ReportPagination
import com.bnm.lab.report.sampleReportDoc
import com.bnm.lab.report.toReportGraphs
import com.bnm.lab.report.writeLabReportPdf
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The histogram panel beside a CBC table — the analyzer's curves and scattergram on paper. */
class ReportGraphTest {

    private fun imagesAndText(path: String): List<Pair<Int, String>> =
        PDDocument.load(File(path)).use { pdf ->
            (0 until pdf.numberOfPages).map { i ->
                val res = pdf.getPage(i).resources
                val images = res.xObjectNames.count { res.isImageXObject(it) }
                images to PDFTextStripper().apply { startPage = i + 1; endPage = i + 1 }.getText(pdf)
            }
        }

    @Test
    fun `the CBC sheet carries the four graph titles and the scattergram bitmap`() {
        val pages = imagesAndText(writeLabReportPdf(sampleReportDoc(pagination = ReportPagination.PER_TEST)))
        val (images, text) = pages[0]
        assertEquals(3, images, "two signatures + the DIFF scattergram on the CBC sheet")
        for (t in listOf("WBC", "RBC", "PLT", "DIFF")) assertTrue(t in text, "$t title on the CBC sheet")
        // Sheets without graphs are untouched: signatures only, no panel titles.
        val (img2, text2) = pages[1]
        assertEquals(2, img2)
        assertTrue("DIFF" !in text2)
    }

    @Test
    fun `a doc without graphs draws no panel`() {
        val doc = sampleReportDoc(pagination = ReportPagination.PER_TEST)
        val bare = doc.copy(sections = doc.sections.map { it.copy(graphs = emptyList()) })
        val (images, text) = imagesAndText(writeLabReportPdf(bare))[0]
        assertEquals(2, images)
        assertTrue("DIFF" !in text)
    }

    @Test
    fun `stored graphs map in CBC reading order, with lines and a decoded bitmap`() {
        val rows = listOf(
            ResultGraph("o", "t", "diff", emptyList(), imageBase64 = "Qk0="),
            ResultGraph("o", "t", "plt", List(64) { it.toDouble() }),
            ResultGraph("o", "t", "wbc", List(256) { 1.0 }, meta = mapOf("wbc_lines" to "40, 100,196")),
            ResultGraph("o", "t", "rbc", List(256) { 2.0 }),
            ResultGraph("o", "t", "junk", emptyList(), imageBase64 = "not base64 %%%"),
        )
        val g = toReportGraphs(rows)
        assertEquals(listOf("WBC", "RBC", "PLT", "DIFF"), g.map { it.title }, "junk row (no curve, undecodable image) is dropped")
        assertEquals(listOf(40.0, 100.0, 196.0), g[0].lines)
        assertTrue(g[3].hasImage && !g[3].hasCurve)
        assertTrue(g.all { it.xLabel == null }, "raw channel axes carry no unit")
    }

    @Test
    fun `an undecodable bitmap leaves the box empty, never fails the print`() {
        val doc = sampleReportDoc(pagination = ReportPagination.PER_TEST)
        val broken = doc.copy(sections = doc.sections.map { s ->
            s.copy(graphs = toReportGraphs(listOf(ResultGraph("o", "t", "diff", emptyList(), imageBase64 = "Qk0="))))
        })
        val (images, _) = imagesAndText(writeLabReportPdf(broken))[0]
        assertEquals(2, images, "three bytes are not a bitmap; the signatures still print")
    }
}
