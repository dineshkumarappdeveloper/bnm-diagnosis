package com.bnm.lab

import com.bnm.lab.report.LetterheadMode
import com.bnm.lab.report.ReportPagination
import com.bnm.lab.report.ReportDoc
import com.bnm.lab.report.ReportPalette
import com.bnm.lab.report.ReportRow
import com.bnm.lab.report.ReportSection
import com.bnm.lab.report.renderLabReportPdfBytes
import com.bnm.lab.report.reportPdfBase64
import com.bnm.lab.report.sampleReportDoc
import com.bnm.lab.report.writeLabReportPdf
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import java.io.File
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The styled A4 PDF engine must produce a real, non-trivial PDF in BOTH
 * letterhead modes (drawn letterhead vs blank reserved space). The sample doc
 * covers every row emphasis (N/L/H/CL/CH/A) and four sections, so a
 * successful write exercises the full layout path incl. pagination inputs.
 *
 * The pagination tests read the text back per page: what a lab hands over is
 * a sheet, so the assertions are about sheets — which test is on which, and
 * that every sheet identifies the patient and closes with the sign-off.
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

    // ── pagination ──

    private val titles = listOf(
        "Complete Blood Count (CBC)",
        "Liver Function Test (LFT)",
        "Fasting Blood Sugar (FBS)",
        "Serology",
    )

    /** Text of every page, in order. */
    private fun pageTexts(doc: ReportDoc): List<String> {
        val path = writeLabReportPdf(doc)
        return PDDocument.load(File(path)).use { pdf ->
            (1..pdf.numberOfPages).map { n ->
                PDFTextStripper().apply { startPage = n; endPage = n }.getText(pdf)
            }
        }
    }

    private fun pageTexts(pagination: ReportPagination, mode: LetterheadMode = LetterheadMode.PRINTED): List<String> =
        pageTexts(sampleReportDoc(pagination = pagination, mode = mode))

    /** Character-spaced titles come back from the stripper with spaces between
     *  letters; compare with all whitespace removed. */
    private fun String.squash() = filterNot { it.isWhitespace() }

    private fun assertEverySheetStandsAlone(pages: List<String>) {
        pages.forEachIndexed { i, p ->
            assertTrue("Kavitha Subramanian" in p, "page ${i + 1} must identify the patient")
            assertTrue("ACC-S1-00042" in p, "page ${i + 1} must carry the accession")
            assertTrue("Page ${i + 1} of ${pages.size}" in p, "page ${i + 1} must be numbered")
            // A results table (its "Parameter" header) on every sheet: a sheet
            // holding only the patient block and a signature — which the
            // sign-off's own page break used to produce — is a signed page
            // that shows no result. Every header is preceded by a test title
            // (or "<test> (contd.)"), so this also proves the sheet is named.
            assertTrue("Parameter" in p, "page ${i + 1} carries no results table — a signed sheet without results")
        }
    }

    /** Lines that ARE the given department banner / title (squashed, upper-cased):
     *  the banner is drawn with letter spacing, so it may come back as "B I O…";
     *  a section title shares its line with "Sample: …", which is dropped first. */
    private fun bannerLines(page: String, name: String): Int =
        page.lines().count { it.squash().uppercase().substringBefore("SAMPLE:") == name }

    @Test
    fun `one test per page puts every test on its own sheet, each closed by the sign-off`() {
        val pages = pageTexts(ReportPagination.PER_TEST)
        assertEverySheetStandsAlone(pages)
        // No department banners in this layout.
        assertTrue(pages.all { bannerLines(it, "HEMATOLOGY") == 0 && bannerLines(it, "BIOCHEMISTRY") == 0 })
        assertEquals(1, pages.sumOf { bannerLines(it, "SEROLOGY") }, "only the Serology TITLE, no banner")

        // Each title on exactly one page, that page carrying no OTHER title,
        // and the pages in order-line order.
        val pageOf = titles.map { t -> pages.indexOfFirst { t in it } }
        assertTrue(pageOf.all { it >= 0 }, "every test must be printed: $pageOf")
        assertEquals(pageOf.sorted(), pageOf, "tests must keep their order across sheets")
        assertEquals(titles.size, pageOf.distinct().size, "no two tests may share a sheet: $pageOf")
        pageOf.forEachIndexed { i, pg ->
            val others = titles.filterIndexed { j, _ -> j != i }
            assertTrue(others.none { it in pages[pg] }, "sheet ${pg + 1} carries a foreign test")
        }
        // Sign-off per group: one "End of report" per test, and every page that
        // holds a test ends with the approver's block.
        assertEquals(titles.size, pages.count { "End of report" in it })
        pageOf.forEach { pg -> assertTrue("Approved by" in pages[pg], "sheet ${pg + 1} lacks the sign-off") }
        // A page without a test would be a blank sheet — there must be none.
        assertEquals(titles.size, pages.size, "one sheet per test, no blank sheets")
    }

    @Test
    fun `continuous flows tests together, still identifying the patient on every sheet`() {
        val pages = pageTexts(ReportPagination.CONTINUOUS)
        assertEverySheetStandsAlone(pages)
        assertTrue(pages.size < titles.size, "continuous must use fewer sheets than one-per-test, used ${pages.size}")
        assertEquals(1, pages.count { "End of report" in it }, "continuous signs off once, at the end")
        // The single sign-off must be on the last page.
        assertTrue("Approved by" in pages.last())
        assertTrue(pages.all { bannerLines(it, "HEMATOLOGY") == 0 && bannerLines(it, "BIOCHEMISTRY") == 0 })
    }

    @Test
    fun `one department per page shares a sheet between the two biochemistry tests`() {
        val pages = pageTexts(ReportPagination.PER_DEPARTMENT)
        assertEverySheetStandsAlone(pages)
        // LFT and FBS are both Biochemistry in the sample → same sheet, under a
        // BIOCHEMISTRY banner; the CBC and the serology are on their own sheets.
        val bio = pages.indexOfFirst { "Liver Function Test (LFT)" in it }
        assertTrue(bio >= 0)
        assertTrue("Fasting Blood Sugar (FBS)" in pages[bio], "FBS must sit on the LFT sheet")
        assertTrue("Complete Blood Count (CBC)" !in pages[bio] && "Serology" !in pages[bio])
        assertEquals(3, pages.count { "End of report" in it }, "three departments, three sign-offs")
        assertEquals(3, pages.size)
        // Each banner heads exactly ITS sheet and no other.
        val cbc = pages.indexOfFirst { "Complete Blood Count (CBC)" in it }
        val sero = pages.indexOfFirst { "Serology" in it }
        pages.forEachIndexed { i, p ->
            assertEquals(if (i == cbc) 1 else 0, bannerLines(p, "HEMATOLOGY"), "HEMATOLOGY banner on sheet ${i + 1}")
            assertEquals(if (i == bio) 1 else 0, bannerLines(p, "BIOCHEMISTRY"), "BIOCHEMISTRY banner on sheet ${i + 1}")
            // The serology sheet has the banner AND the title line; others neither.
            assertEquals(if (i == sero) 2 else 0, bannerLines(p, "SEROLOGY"), "SEROLOGY lines on sheet ${i + 1}")
        }
    }

    @Test
    fun `a table that spills onto a fresh sheet re-states its test as continued`() {
        // Sixty rows cannot fit under the patient block on one A4 sheet. The
        // overflow sheet must say WHICH test it continues — a page of bare
        // rows under "Parameter / Result" does not read on its own.
        val long = ReportSection(
            "Extended Chemistry Panel",
            rows = (1..60).map { ReportRow("Analyte $it", "1.0", "mg/dL", "0.5 - 2.0", "N") },
            department = "Biochemistry",
        )
        val doc = sampleReportDoc(pagination = ReportPagination.PER_TEST).let { it.copy(sections = listOf(long) + it.sections) }
        val pages = pageTexts(doc)
        assertEverySheetStandsAlone(pages)
        val panel = pages.takeWhile { "Extended Chemistry Panel" in it }
        assertTrue(panel.size >= 2, "sixty rows must spill: ${panel.size} sheet(s)")
        assertTrue("(contd.)" !in panel[0], "sheet 1 opens the panel")
        assertTrue(panel.drop(1).all { "Extended Chemistry Panel (contd.)" in it }, "every overflow sheet re-states the test")
        // ONE sign-off for the panel, on its LAST sheet, and that sheet still
        // shows results — the final row travels with the signature.
        assertTrue(panel.dropLast(1).none { "End of report" in it }, "no sign-off before the panel's last sheet")
        assertTrue("End of report" in panel.last() && "Analyte 60" in panel.last(), "last row and sign-off share a sheet")
        // The spill must not bleed into the next test's sheet.
        assertEquals(panel.size, pages.indexOfFirst { "Complete Blood Count (CBC)" in it }, "the CBC starts right after the panel")
        assertEquals(panel.size + titles.size, pages.size)
        assertEquals(titles.size + 1, pages.count { "End of report" in it })
    }

    @Test
    fun `a short table beside a tall graph panel keeps its sign-off on the same sheet`() {
        // Two rows and four graphs: the section ends at the PANEL's bottom, far
        // below its last row, so the keep-with-next on that row cannot see the
        // sign-off coming. Pushed down by a filler of every length, the CBC must
        // still sign on the sheet that shows it — never a sheet of signatures.
        val base = sampleReportDoc(pagination = ReportPagination.CONTINUOUS)
        val cbc = base.sections.first { it.graphs.isNotEmpty() }.let { it.copy(rows = it.rows.take(2)) }
        for (n in 0..34) {
            val filler = ReportSection("Filler", rows = (1..n).map { ReportRow("Analyte $it", "1.0", "g/dL", "0.5 - 2.0", "N") })
            val pages = pageTexts(base.copy(sections = listOf(filler, cbc)))
            assertEverySheetStandsAlone(pages)
            val signed = pages.single { "End of report" in it }
            assertTrue(cbc.title in signed && "WBC" in signed, "filler $n rows: the signed sheet must carry the CBC and its panel")
        }
    }

    @Test
    fun `a test whose rows just fill a sheet keeps its last row with the sign-off`() {
        // 28 single-line rows fit under the patient block, but the sign-off no
        // longer does. Without keep-with-next this printed as: sheet 1 = every
        // result and no signature, sheet 2 = signature and no result — for an
        // ordinary full CBC, in the default layout.
        val cbcSized = ReportSection(
            "Full Blood Count",
            rows = (1..28).map { ReportRow("Analyte $it", "1.0", "g/dL", "0.5 - 2.0", "N") },
        )
        val doc = sampleReportDoc(pagination = ReportPagination.PER_TEST).copy(sections = listOf(cbcSized))
        val pages = pageTexts(doc)
        assertEverySheetStandsAlone(pages)
        val signed = pages.single { "End of report" in it }
        assertTrue("Analyte 28" in signed, "the last row must sit on the signed sheet")
        assertTrue("Full Blood Count" in signed, "the signed sheet must name the test")
        assertTrue(pages.size <= 2, "two sheets at most, got ${pages.size}")
    }

    @Test
    fun `both signatories' signatures are drawn on every signed sheet`() {
        // The verifier's ink on the left, the approver's on the right: two image
        // objects per sheet, and the technician's credentials in the text. With
        // no verifier signature on file the sheet still prints, approver only.
        fun imagesPerPage(doc: ReportDoc): List<Pair<Int, String>> =
            PDDocument.load(File(writeLabReportPdf(doc))).use { pdf ->
                (0 until pdf.numberOfPages).map { i ->
                    val res = pdf.getPage(i).resources
                    val images = res.xObjectNames.count { res.isImageXObject(it) }
                    images to PDFTextStripper().apply { startPage = i + 1; endPage = i + 1 }.getText(pdf)
                }
            }
        val signed = imagesPerPage(sampleReportDoc(pagination = ReportPagination.PER_TEST))
        signed.forEachIndexed { i, (images, text) ->
            // Two signatures on every sheet; the CBC sheet (first) also carries
            // the DIFF scattergram bitmap in its graph panel.
            assertEquals(if (i == 0) 3 else 2, images, "sheet ${i + 1}: signature images (+ scattergram on the CBC sheet)")
            assertTrue("Verified by" in text && "DMLT, B.Sc. (MLT)" in text, "sheet ${i + 1} names the verifier's credentials")
            assertTrue("Tech. S. Kumar" in text && "Dr. A. Lakshmi" in text)
        }
        val approverOnly = imagesPerPage(sampleReportDoc(pagination = ReportPagination.PER_TEST).copy(verifierSignature = null))
        approverOnly.forEachIndexed { i, (images, text) ->
            assertEquals(if (i == 0) 2 else 1, images, "sheet ${i + 1}: approver image only (+ scattergram on the CBC sheet)")
            assertTrue("Tech. S. Kumar" in text, "the verifier's name still prints without an image")
        }
    }

    @Test
    fun `a long signatory name wraps inside its column instead of running under the QR`() {
        // Free-typed approver names carry degrees; the verifier can fall back
        // to a station name. Either could run 250pt+ at 10.5pt bold — straight
        // across the centred QR — unless the column wraps them.
        val longApprover = "Dr. Venkatasubramaniam Krishnamoorthy, MD (Pathology)"
        val longVerifier = "Reception Desk Workstation (Lab Reception Counter 2)"
        val doc = sampleReportDoc(pagination = ReportPagination.PER_TEST)
            .copy(approvedBy = longApprover, verifiedBy = longVerifier)
        val text = pageTexts(doc)[0]
        assertTrue(text.lines().none { longApprover in it }, "the approver name must be wrapped onto more than one line")
        assertTrue(text.lines().none { longVerifier in it }, "the verifier name must be wrapped onto more than one line")
        assertTrue("Krishnamoorthy" in text && "Counter 2" in text, "no part of either name may be dropped")
        assertTrue("Scan to view" in text, "the QR caption still prints between them")
    }

    // ── in-memory render (the WhatsApp Business send) ──

    @Test
    fun `the PDF for a send is rendered in memory — same document, no file to be locked`() {
        // Windows: "Open PDF" leaves <accession>-report.pdf open (and locked) in
        // Acrobat. The send must not write that path, or saving over it throws.
        val doc = sampleReportDoc(pagination = ReportPagination.PER_TEST).copy(accession = "ACC-MEM-00077")
        val onDisk = File(System.getProperty("java.io.tmpdir"), "bnm-diagnosis-reports/ACC-MEM-00077-report.pdf")
        onDisk.delete()

        val bytes = assertNotNull(renderLabReportPdfBytes(doc))
        assertFalse(onDisk.exists(), "an in-memory render must not touch the temp file")
        assertEquals("%PDF-", bytes.copyOf(5).decodeToString())
        val fromMemory = PDDocument.load(bytes).use { pdf -> PDFTextStripper().getText(pdf) to pdf.numberOfPages }
        val fromFile = PDDocument.load(File(writeLabReportPdf(doc))).use { pdf -> PDFTextStripper().getText(pdf) to pdf.numberOfPages }
        assertEquals(fromFile, fromMemory, "the send carries the same report the print path writes")
        onDisk.delete()
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun `the send's PDF helper never throws — a failed render is a result the dialog can show`() {
        val doc = sampleReportDoc()
        val failed = reportPdfBase64(doc) { throw java.io.IOException("The process cannot access the file") }
        assertTrue(failed.isFailure)
        assertEquals("The process cannot access the file", failed.exceptionOrNull()?.message)

        assertEquals(null, reportPdfBase64(doc) { null }.getOrThrow(), "no PDF on this platform: send without one")
        assertEquals(null, reportPdfBase64(doc) { ByteArray(0) }.getOrThrow(), "an empty render is not a document")

        val b64 = assertNotNull(reportPdfBase64(doc).getOrThrow())
        assertEquals("%PDF-", Base64.Default.decode(b64).copyOf(5).decodeToString())
    }

    @Test
    fun `preprinted letterpads paginate the same way`() {
        // The blank header band must not change WHICH sheet a test lands on.
        val printed = pageTexts(ReportPagination.PER_TEST, LetterheadMode.PRINTED)
        val blank = pageTexts(ReportPagination.PER_TEST, LetterheadMode.PREPRINTED)
        assertEquals(printed.size, blank.size)
        titles.forEach { t ->
            assertEquals(printed.indexOfFirst { t in it }, blank.indexOfFirst { t in it }, t)
        }
    }
}
