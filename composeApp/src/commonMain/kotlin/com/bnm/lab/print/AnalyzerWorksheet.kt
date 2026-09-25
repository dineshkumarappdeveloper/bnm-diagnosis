package com.bnm.lab.print

import com.bnm.lab.instruments.QueuedFrame
import com.bnm.lab.instruments.driverFor
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * The ANALYZER WORKSHEET — what the bench can print for a result that arrived
 * with no order behind it.
 *
 * 🔴 THIS IS NOT A REPORT AND MUST NEVER BE MISTAKEN FOR ONE. A sheet of
 * clinical numbers on lab paper is, to everyone who is not the person who
 * printed it, a result. This one has no patient, no ordered test, therefore no
 * reference ranges, no verification and no pathologist's approval — so it says
 * so in the heading, under the heading, and again in the footer, and it
 * deliberately withholds every mark that would make it read as a report:
 *
 *  - no reference range column (there is no ordered test to take one from),
 *  - no high/low emphasis and no flag key (a judgement needs a range),
 *  - no interpretation — the Mispa's own "disease flags" are dropped here on
 *    purpose, because a printed "Anemia?" beside a number IS an opinion,
 *  - no signature block, no QR, no barcode: nobody has signed this, and there
 *    is nothing to look up.
 *
 * What it DOES carry is everything needed to marry the run back up with the
 * patient by hand: the specimen id exactly as it was keyed on the analyzer, the
 * instrument, when it was received, the run mode, and the values with the
 * ANALYZER's own parameter names, units and flags — unconverted, because no
 * catalog test is attached to convert them to.
 *
 * Rendered through the shared [A4Doc] op list ([printA4]) rather than
 * [com.bnm.lab.report.ReportDoc]: same A4 page setup and the same platform
 * print path as a report, but its own small renderer — bending the report
 * document into a shape with no patient and no ranges is exactly how the two
 * would end up looking alike.
 */
data class AnalyzerWorksheet(
    val labName: String,
    /** As keyed on the analyzer; null when the operator keyed nothing. */
    val specimenId: String?,
    val instrumentName: String,
    /** The driver's shop name ("Agappe Mispa Count X"), or its key if unknown. */
    val driverLabel: String,
    /** When the frame reached this PC, "yyyy-MM-dd HH:mm". */
    val receivedAt: String,
    /** "CBC+5DIFF · Whole Blood", or null when the frame carried no mode. */
    val runMode: String?,
    val lines: List<WorksheetLine>,
    val printedAt: String,
) {
    val specimenLabel: String get() = specimenId?.takeIf { it.isNotBlank() } ?: NO_SPECIMEN

    /**
     * The same content as plain text, for "Copy values".
     *
     * Not a nicety: without it the operator photographs the screen with a
     * phone, and a photo of a screen is the one copy of a lab value nobody can
     * audit. The disclaimer travels with it — pasted into WhatsApp it needs to
     * say what it is even harder than the paper does.
     */
    fun plainText(): String = buildString {
        appendLine(TITLE)
        appendLine(labName.trim().ifEmpty { "BNM Lab" })
        appendLine(DISCLAIMER)
        appendLine()
        appendLine("Specimen id : $specimenLabel")
        appendLine("Instrument  : $instrumentName ($driverLabel)")
        appendLine("Received    : $receivedAt")
        runMode?.let { appendLine("Run mode    : $it") }
        appendLine()
        val width = (lines.maxOfOrNull { it.param.length } ?: 0).coerceAtLeast(9)
        for (l in lines) {
            append(l.param.padEnd(width)).append("  ").append(l.value)
            if (l.unit.isNotBlank()) append(' ').append(l.unit)
            l.flag?.takeIf { it.isNotBlank() }?.let { append("  [").append(it).append(']') }
            appendLine()
        }
        appendLine()
        appendLine(DISCLAIMER)
        appendLine("Printed $printedAt")
    }

    companion object {
        const val TITLE = "ANALYZER WORKSHEET"

        /**
         * The line that has to survive every retelling — the paper, the
         * clipboard, the screen preview. Changing its wording is a clinical
         * change, not a copy tweak.
         */
        const val DISCLAIMER =
            "Not a diagnostic report. No patient is linked to this result. Not verified or approved."

        const val NO_SPECIMEN = "no specimen id keyed"
    }
}

/** One parameter exactly as the analyzer sent it. [flag] is the ANALYZER's own mark. */
data class WorksheetLine(
    val param: String,
    val value: String,
    val unit: String,
    val flag: String?,
)

/**
 * Build the worksheet for a queued frame. [instrumentName] is the analyzer's
 * configured name (the engine's fallback "Analyzer" when its row is gone), and
 * [now] is the print time — passed in so a test renders the same page twice.
 */
fun buildAnalyzerWorksheet(
    labName: String,
    queued: QueuedFrame,
    instrumentName: String,
    now: String = nowStamp(),
): AnalyzerWorksheet {
    val frame = queued.frame
    // "WBC:H;PLT:L" as the HL7 driver stores OBX-8. A driver that sends none
    // (the Mispa) simply has no flag column filled in — never an invented one.
    val flags = frame.meta["flags"].orEmpty().split(';')
        .mapNotNull { entry ->
            val at = entry.lastIndexOf(':')
            if (at <= 0) null else entry.take(at).trim() to entry.substring(at + 1).trim()
        }
        .filter { it.second.isNotEmpty() }
        .toMap()
    return AnalyzerWorksheet(
        labName = labName,
        specimenId = queued.specimenId ?: frame.specimenId,
        instrumentName = instrumentName,
        driverLabel = driverFor(frame.driver)?.label ?: frame.driver,
        receivedAt = worksheetStamp(queued.receivedAt) ?: queued.receivedAt,
        runMode = listOfNotNull(
            frame.meta["test_mode"], frame.meta["blood_mode"], frame.meta["take_mode"],
        ).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { null },
        lines = frame.params.map { (key, value) ->
            WorksheetLine(
                param = key,
                value = value.trim(),
                unit = frame.units[key]?.trim().orEmpty(),
                flag = flags[key],
            )
        },
        printedAt = now,
    )
}

// ── A4 layout ───────────────────────────────────────────────────────────────

private const val M = 40f
private const val RIGHT = A4_W - M
private const val BOTTOM_LIMIT = A4_H - 64f

// Column edges: Parameter | Value | Unit | Flag. No reference-range column —
// its absence is the point, so there is nothing here to "fill in later".
private const val C_PARAM = M
private const val C_VALUE = 300f
private const val C_UNIT = 320f
private const val C_FLAG = RIGHT

/**
 * Lay the worksheet out on A4. Deliberately plain: no accent band, no
 * letterhead lines, no logo — the visual vocabulary of a BNM Lab report is
 * exactly what this page must not borrow.
 */
fun layoutAnalyzerWorksheetA4(ws: AnalyzerWorksheet): A4Doc {
    val pages = mutableListOf<MutableList<A4Op>>()
    var ops = mutableListOf<A4Op>()
    pages.add(ops)
    var y = M + 16f
    // An empty "Analyzer flag" column would read as a judgement that was not
    // made; the Mispa simply sends no flags, so the column is not there.
    val anyFlags = ws.lines.any { !it.flag.isNullOrBlank() }

    fun disclaimerBand() {
        // A filled band, not italics: this has to survive a fax, a photocopy
        // and a phone photo of the sheet.
        ops.add(A4Op.Rect(M, y - 11f, RIGHT - M, 30f, gray = 0.88f))
        ops.add(A4Op.Text(A4_W / 2f, y, AnalyzerWorksheet.DISCLAIMER, 9f, bold = true, align = A4Align.CENTER))
        ops.add(A4Op.Text(A4_W / 2f, y + 12f, "Use it to match the run to an order, never as a result to hand over.",
            8f, align = A4Align.CENTER, gray = 0.25f))
        y += 34f
    }

    fun tableHeader() {
        ops.add(A4Op.Line(M, y, RIGHT, y))
        y += 13f
        ops.add(A4Op.Text(C_PARAM, y, "Parameter", 8.5f, bold = true))
        ops.add(A4Op.Text(C_VALUE, y, "Value", 8.5f, bold = true, align = A4Align.RIGHT))
        ops.add(A4Op.Text(C_UNIT, y, "Unit (as sent)", 8.5f, bold = true))
        if (anyFlags) ops.add(A4Op.Text(C_FLAG, y, "Analyzer flag", 8.5f, bold = true, align = A4Align.RIGHT))
        y += 5f
        ops.add(A4Op.Line(M, y, RIGHT, y))
        y += 13f
    }

    fun newPage() {
        ops = mutableListOf()
        pages.add(ops)
        y = M + 12f
        ops.add(A4Op.Text(M, y, "${AnalyzerWorksheet.TITLE} — continued", 9f, bold = true))
        ops.add(A4Op.Text(RIGHT, y, "Specimen ${ws.specimenLabel}", 9f, align = A4Align.RIGHT, gray = 0.35f))
        y += 14f
        disclaimerBand()
        tableHeader()
    }

    // ── Heading ──
    ops.add(A4Op.Text(A4_W / 2f, y, ws.labName.trim().ifEmpty { "BNM Lab" }, 12f, align = A4Align.CENTER, gray = 0.25f))
    y += 20f
    ops.add(A4Op.Text(A4_W / 2f, y, AnalyzerWorksheet.TITLE, 17f, bold = true, align = A4Align.CENTER))
    y += 8f
    ops.add(A4Op.Line(M, y, RIGHT, y, width = 1.1f))
    y += 20f
    disclaimerBand()

    // ── What ran ──
    fun fact(label: String, value: String) {
        ops.add(A4Op.Text(M, y, label, 9f, gray = 0.35f))
        ops.add(A4Op.Text(M + 110f, y, value, 9.5f, bold = true))
        y += 13f
    }
    y += 4f
    fact("Specimen id", ws.specimenLabel)
    fact("Instrument", "${ws.instrumentName}  ·  ${ws.driverLabel}")
    fact("Received", ws.receivedAt)
    ws.runMode?.let { fact("Run mode", it) }
    y += 6f

    // ── Values, exactly as sent ──
    tableHeader()
    for (l in ws.lines) {
        if (y + 14f > BOTTOM_LIMIT) newPage()
        // Every row in the same ink. Colouring a value here would be a verdict
        // against a range this page does not have.
        ops.add(A4Op.Text(C_PARAM, y, l.param, 9.5f))
        ops.add(A4Op.Text(C_VALUE, y, l.value, 9.5f, align = A4Align.RIGHT))
        if (l.unit.isNotBlank()) ops.add(A4Op.Text(C_UNIT, y, l.unit, 9f, gray = 0.3f))
        l.flag?.takeIf { it.isNotBlank() }?.let {
            ops.add(A4Op.Text(C_FLAG, y, it, 9f, align = A4Align.RIGHT, gray = 0.25f))
        }
        y += 13f
    }
    if (ws.lines.isEmpty()) {
        ops.add(A4Op.Text(M, y, "The analyzer sent no numeric parameters in this frame.", 9f, gray = 0.35f))
        y += 13f
    }
    ops.add(A4Op.Line(M, y, RIGHT, y, width = 0.4f))

    // ── Footer on every sheet: the warning again, and no signature line ──
    val total = pages.size
    pages.forEachIndexed { i, p ->
        p.add(A4Op.Line(M, A4_H - 52f, RIGHT, A4_H - 52f, width = 0.4f))
        p.add(A4Op.Text(A4_W / 2f, A4_H - 40f, AnalyzerWorksheet.DISCLAIMER, 8.5f, bold = true, align = A4Align.CENTER))
        p.add(A4Op.Text(A4_W / 2f, A4_H - 28f,
            "Printed ${ws.printedAt}  ·  page ${i + 1} of $total", 8f, align = A4Align.CENTER, gray = 0.45f))
    }
    return A4Doc(jobName = "worksheet-${ws.specimenId ?: "unkeyed"}", pages = pages)
}

/** ISO instant → "yyyy-MM-dd HH:mm" in the device timezone (null if unparseable). */
private fun worksheetStamp(iso: String): String? =
    runCatching { kotlin.time.Instant.parse(iso).toLocalDateTime(TimeZone.currentSystemDefault()) }
        .getOrNull()?.let {
            "${it.date} ${it.hour.toString().padStart(2, '0')}:${it.minute.toString().padStart(2, '0')}"
        }

private fun nowStamp(): String {
    val dt = kotlin.time.Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    return "${dt.date} ${dt.hour.toString().padStart(2, '0')}:${dt.minute.toString().padStart(2, '0')}"
}
