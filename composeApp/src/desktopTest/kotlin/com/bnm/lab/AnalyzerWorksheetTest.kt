package com.bnm.lab

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.bnm.lab.api.ApiClient
import com.bnm.lab.db.AppDatabase
import com.bnm.lab.instruments.InstrumentConfig
import com.bnm.lab.instruments.InstrumentEngine
import com.bnm.lab.instruments.InstrumentTransport
import com.bnm.lab.instruments.Mllp
import com.bnm.lab.instruments.QueuedFrame
import com.bnm.lab.instruments.StoredInstrumentFrame
import com.bnm.lab.lab.LabRepository
import com.bnm.lab.print.A4Op
import com.bnm.lab.print.AnalyzerWorksheet
import com.bnm.lab.print.buildAnalyzerWorksheet
import com.bnm.lab.print.layoutAnalyzerWorksheetA4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The analyzer worksheet: a page of clinical numbers with no patient behind
 * them, which therefore has to be impossible to mistake for a report.
 *
 * Most of what follows asserts ABSENCE — no reference range, no signature, no
 * QR, no filing, no share token — because absence is the safety property. A
 * sheet that grows a "Ref. range" column one day is a sheet a patient can be
 * handed, and nothing else in the app would notice.
 */
class AnalyzerWorksheetTest {

    private val frame = StoredInstrumentFrame(
        driver = "mindray_hl7",
        specimenId = "77",
        params = linkedMapOf("WBC" to "12.40", "HGB" to "138", "PLT" to "245"),
        units = mapOf("WBC" to "10*9/L", "HGB" to "g/L", "PLT" to "10*9/L"),
        meta = mapOf("test_mode" to "CBC+5DIFF", "blood_mode" to "Whole Blood", "flags" to "WBC:H"),
    )

    private fun queued(f: StoredInstrumentFrame = frame, specimen: String? = f.specimenId) = QueuedFrame(
        id = "q1", instrumentId = "i1", specimenId = specimen,
        receivedAt = "2026-09-25T10:42:00Z", frame = f,
    )

    private fun sheet(q: QueuedFrame = queued()) =
        buildAnalyzerWorksheet("Sunrise Diagnostics", q, "BC-5130 bench 1", now = "2026-09-25 11:05")

    /** Every string the renderer would draw, in page order. */
    private fun printed(ws: AnalyzerWorksheet): List<String> =
        layoutAnalyzerWorksheetA4(ws).pages.flatten().filterIsInstance<A4Op.Text>().map { it.text }

    // ── what it says ─────────────────────────────────────────────────────────

    @Test
    fun `the page says what it is, twice, and carries the run's identity`() {
        val ws = sheet()
        val text = printed(ws)

        assertTrue(AnalyzerWorksheet.TITLE in text, "headed as a worksheet, never as a report")
        assertEquals(2, text.count { it == AnalyzerWorksheet.DISCLAIMER },
            "at the top where it is read, and in the footer where a photocopy of page 2 still shows it")
        assertTrue("Sunrise Diagnostics" in text, "the lab's own name — the sheet is not anonymous")
        assertTrue("77" in text, "the specimen id exactly as it was keyed on the analyzer")
        assertTrue(text.any { "BC-5130 bench 1" in it && "Mindray" in it }, "instrument and driver: $text")
        // When it ARRIVED, shown in the bench's own timezone (the raw instant is
        // UTC; a lab reads wall-clock time), and distinct from the print time.
        assertTrue(Regex("""^2026-09-2\d \d\d:\d\d$""").matches(ws.receivedAt), ws.receivedAt)
        assertTrue(ws.receivedAt in text, "the received stamp is on the page")
        assertTrue(text.any { "CBC+5DIFF" in it }, "the run mode the frame carried")
        assertTrue(text.any { "Printed 2026-09-25 11:05" in it })
    }

    @Test
    fun `the values are the analyzer's own - its names, its units, its flags, unconverted`() {
        val text = printed(sheet())
        assertTrue("WBC" in text && "12.40" in text && "10*9/L" in text,
            "10*9/L stays 10*9/L: there is no ordered test to convert it towards")
        assertTrue("HGB" in text && "138" in text && "g/L" in text)
        assertTrue("H" in text, "the analyzer's own abnormal mark on WBC")
        assertEquals(listOf("H"), sheet().lines.mapNotNull { it.flag }, "only the flag the frame actually carried")
    }

    @Test
    fun `nothing keyed on the analyzer says so, rather than printing an empty line`() {
        val ws = sheet(queued(frame.copy(specimenId = null), specimen = null))
        assertEquals(AnalyzerWorksheet.NO_SPECIMEN, ws.specimenLabel)
        assertTrue(AnalyzerWorksheet.NO_SPECIMEN in printed(ws))
    }

    @Test
    fun `a frame with no run mode simply has no run-mode line`() {
        val ws = sheet(queued(frame.copy(meta = emptyMap())))
        assertNull(ws.runMode)
        assertTrue(ws.lines.all { it.flag == null }, "no flags meta ⇒ no invented flags")
    }

    // ── what it must never say ───────────────────────────────────────────────

    @Test
    fun `no reference range, no interpretation, no sign-off and no QR`() {
        val ws = sheet(queued(frame.copy(
            // The Mispa sends its own verdicts. They are an opinion, and an
            // opinion on an unattributed sheet is the worst thing on it.
            meta = frame.meta + mapOf("disease_flags" to "Anemia?; Leukocytosis?"),
        )))
        val text = printed(ws)
        val joined = text.joinToString("\n")

        for (banned in listOf("Ref. range", "Reference", "Verified by", "Approved by",
                "Authorised Signatory", "Flag key", "Anemia", "Leukocytosis", "CRITICAL")) {
            assertFalse(banned in joined, "a worksheet must not print “$banned”:\n$joined")
        }
        assertFalse(joined.contains("report", ignoreCase = true) &&
            !joined.contains("Not a diagnostic report"),
            "the only time the word report appears is in the line denying it is one")

        // Structural, not stylistic: the op list this renderer emits has three
        // members — text, rules, filled rectangles. There is no image op, so a
        // QR code, a barcode or a signature bitmap cannot be drawn on this page
        // even by accident. The report renderer's doc model is the one that can.
        val ops = layoutAnalyzerWorksheetA4(ws).pages.flatten()
        assertTrue(ops.all { it is A4Op.Text || it is A4Op.Line || it is A4Op.Rect })

        // And every value is in the same ink; `gray` is the only tone the ops
        // carry, so no row can be coloured into a verdict.
        val values = ops.filterIsInstance<A4Op.Text>().filter { it.text == "12.40" }
        assertTrue(values.isNotEmpty() && values.all { it.gray == 0f }, "the high WBC prints black like the rest")
    }

    @Test
    fun `copy values carries the same content and the same warning`() {
        val copied = sheet().plainText()
        assertEquals(2, Regex(Regex.escape(AnalyzerWorksheet.DISCLAIMER)).findAll(copied).count(),
            "pasted into a chat it has to say what it is at least as loudly as the paper")
        assertTrue(AnalyzerWorksheet.TITLE in copied)
        assertTrue("Specimen id : 77" in copied, copied)
        assertTrue("WBC" in copied && "12.40 10*9/L" in copied && "[H]" in copied, copied)
        assertFalse("Approved" in copied)
    }

    // ── what printing must not do ────────────────────────────────────────────

    @Test
    fun `printing a worksheet files nothing, mints no share token and leaves the result waiting`() = runBlocking {
        val db = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            .let { AppDatabase.Schema.create(it); AppDatabase(it) }
        val repo = LabRepository(db, ApiClient.json)
        val engine = InstrumentEngine(db, repo, ApiClient.json)
        val cfg = InstrumentConfig(id = "i1", name = "BC-5130 bench 1",
            driver = "mindray_hl7", transport = InstrumentTransport.TCP)
        engine.saveInstrument(cfg)
        engine.ingestBytes(cfg, Mllp.wrap(com.bnm.lab.instruments.SampleFrames.mindrayOru()))
        val row = engine.queueFlow().first().single()

        // Exactly what the Print button runs, minus the platform print dialog:
        // build the document and lay it out. Nothing else is on this path —
        // ReportFiling.fileOrder and ReportShare's token both need an ORDER, and
        // the whole point of this sheet is that there isn't one.
        val doc = layoutAnalyzerWorksheetA4(buildAnalyzerWorksheet("Sunrise Diagnostics", row, cfg.name))
        assertTrue(doc.pages.isNotEmpty())

        assertTrue(repo.pendingReportUploads().isEmpty(), "no report was minted, so nothing can be published")
        val queue = engine.queueFlow().first()
        assertEquals(listOf(row.id), queue.map { it.id }, "the result is still waiting for an order")
        assertEquals("unmatched", db.instrumentsQueries.unmatchedById(row.id).executeAsOne().status)
    }

    @Test
    fun `a long frame paginates and every sheet repeats the warning`() {
        val many = StoredInstrumentFrame(
            driver = "mispa_count_x",
            specimenId = "12",
            params = (1..90).associate { "P$it" to "$it.0" },
        )
        val doc = layoutAnalyzerWorksheetA4(sheet(queued(many, specimen = "12")))
        assertTrue(doc.pages.size >= 2, "90 parameters do not fit one sheet")
        for (page in doc.pages) {
            val text = page.filterIsInstance<A4Op.Text>().map { it.text }
            assertTrue(AnalyzerWorksheet.DISCLAIMER in text, "every sheet has to stand on its own")
        }
    }
}
