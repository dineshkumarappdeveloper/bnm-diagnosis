package com.bnm.diagnosis

import com.bnm.diagnosis.lab.LabOrder
import com.bnm.diagnosis.lab.LabOrderTest
import com.bnm.diagnosis.lab.LabResult
import com.bnm.diagnosis.lab.Patient
import com.bnm.diagnosis.print.renderLabReport
import com.bnm.diagnosis.report.ReportPagination
import com.bnm.diagnosis.report.buildReportDoc
import com.bnm.diagnosis.report.sampleReportDoc
import com.bnm.diagnosis.report.sampleTypeDisplay
import com.bnm.diagnosis.report.writeLabReportPdf
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The specimen each test was run on, on every sheet, the slip, and the screen model. */
class ReportSampleTypeTest {

    private fun pages(path: String): List<String> = PDDocument.load(File(path)).use { pdf ->
        (1..pdf.numberOfPages).map { n -> PDFTextStripper().apply { startPage = n; endPage = n }.getText(pdf) }
    }

    @Test
    fun `catalog sample types print as words, blank and other print nothing`() {
        assertEquals("Serum", sampleTypeDisplay("serum"))
        assertEquals("Urine", sampleTypeDisplay(" urine "))
        assertEquals("EDTA blood", sampleTypeDisplay("EDTA blood"), "a value with its own capitals is kept")
        assertNull(sampleTypeDisplay(""))
        assertNull(sampleTypeDisplay("other"))
        assertNull(sampleTypeDisplay(null))
    }

    @Test
    fun `every sheet names its specimen beside the test title`() {
        val pages = pages(writeLabReportPdf(sampleReportDoc(pagination = ReportPagination.PER_TEST)))
        val cbc = pages.first { "Complete Blood Count (CBC)" in it }
        assertTrue("Sample: Blood" in cbc, cbc.lines().take(30).joinToString("\n"))
        val lft = pages.first { "Liver Function Test (LFT)" in it }
        assertTrue("Sample: Serum" in lft)
        assertTrue("Sample: Blood" !in lft, "the specimen belongs to its own section")
    }

    @Test
    fun `the assembler-side lookup reaches the section and the thermal slip`() {
        val order = LabOrder(id = "o", accessionNo = "ACC-S1-00001", patientId = "p", createdAt = "2026-09-11T04:00:00Z")
        val patient = Patient(id = "p", name = "Sample Patient", sex = "F", ageYears = 30)
        val tests = listOf(LabOrderTest(id = "ot", orderId = "o", testId = "t-lft", testName = "Liver Function Test"))
        val results = listOf(LabResult(id = "r", orderId = "o", testId = "t-lft", parameterKey = "sgpt", value = "30", unit = "U/L"))
        val doc = buildReportDoc("Lab", order, patient, tests, results, sampleType = { "serum" })
        assertEquals("Serum", doc.sections.single().sampleType)
        assertNull(buildReportDoc("Lab", order, patient, tests, results).sections.single().sampleType)

        val slip = renderLabReport("Lab", order, patient, tests, results, sampleType = { "Serum" })
        assertTrue("Sample: Serum" in slip, slip)
        assertTrue("Sample:" !in renderLabReport("Lab", order, patient, tests, results))
    }
}
