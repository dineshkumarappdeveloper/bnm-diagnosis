package com.bnm.lab.report

import com.bnm.lab.diagnostics.AppLog

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.printing.PDFPageable
import com.bnm.lab.report.ReportGraph
import java.awt.Color
import java.awt.Desktop
import java.awt.print.PrinterJob
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Desktop A4 report renderer — Apache PDFBox 2.0.x, base-14 Helvetica family
 * (no font embedding, WinAnsi-safe text only). Layout is a single top-down
 * cursor with manual pagination; the letterhead (or reserved blank space)
 * repeats on every page, and footers ("Page X of Y") are stamped in a second
 * pass once the page count is known.
 */

private const val MM = 72f / 25.4f
private const val MARGIN_MM = 14f

/** Side of the QR SYMBOL itself, in mm. The 4-module quiet zone is reserved
 *  around it in the layout, so the printed block is a little wider than this.
 *  20 mm keeps a ~49-module code at ~5 px per module on a 300 dpi print. */
private const val QR_MM = 20f

/** Height of the accession barcode in the patient box, mm. 8 mm scans from a
 *  desk CCD reader without dominating the box. */
private const val BARCODE_MM = 8f
/** Code 128 module width, mm — 0.3 mm is the safe X-dimension for laser
 *  output; narrower only when a long accession would not fit the column. */
private const val BARCODE_MODULE_MM = 0.3f

/** The analyzer-graph panel beside a test's table: 46 mm wide, like the
 *  HISTOGRAM column of the reference CBC sheets. */
private const val PANEL_MM = 46f
private const val PANEL_GAP = 8f
private const val GRAPH_H = 36f          // a histogram box
private const val GRAPH_TITLE_H = 9f
private const val GRAPH_GAP = 5f

actual fun writeLabReportPdf(doc: ReportDoc): String {
    val dir = File(System.getProperty("java.io.tmpdir"), "bnm-diagnosis-reports").apply { mkdirs() }
    val safe = doc.accession.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "lab" }
    val file = File(dir, "$safe-report.pdf")
    renderReport(doc) { it.save(file) }
    AppLog.i("Report", "PDF written for ${doc.accession} (${file.length() / 1024} KB)")
    return file.absolutePath
}

actual fun renderLabReportPdfBytes(doc: ReportDoc): ByteArray? {
    val out = ByteArrayOutputStream()
    renderReport(doc) { it.save(out) }
    return out.toByteArray()
}

/** Lay [doc] out and hand the finished document to [save]; a failure is logged
 *  and rethrown, whichever way the bytes were going. */
private inline fun renderReport(doc: ReportDoc, save: (PDDocument) -> Unit) {
    try {
        PDDocument().use { pdf ->
            A4ReportWriter(pdf, doc).render()
            save(pdf)
        }
    } catch (e: Throwable) {
        AppLog.e("Report", "PDF generation failed for ${doc.accession}", e)
        throw e
    }
}

actual fun openPdf(path: String): String = try {
    val f = File(path)
    when {
        !f.exists() -> "PDF not found: $path"
        Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN) -> {
            Desktop.getDesktop().open(f); "Opened ${f.name}"
        }
        else -> "No system PDF viewer — saved at $path"
    }
} catch (e: Exception) {
    "Open failed: ${e.message}"
}.also { AppLog.i("Report", "open PDF: $it") }

actual fun printPdf(path: String): String = try {
    val f = File(path)
    if (!f.exists()) "PDF not found: $path"
    else PDDocument.load(f).use { pdf ->
        val job = PrinterJob.getPrinterJob()
        job.jobName = f.name
        job.setPageable(PDFPageable(pdf))
        if (job.printDialog()) { job.print(); "Sent to printer" } else "Print cancelled"
    }
} catch (e: Exception) {
    AppLog.e("Report", "printing failed", e)
    "Print failed: ${e.message}"
}.also { AppLog.i("Report", "print: $it") }

// ─────────────────────────────────────────────────────────────────────────────

/** Replace/strip anything the base-14 WinAnsi encoding can't show. */
private fun winAnsi(s: String): String = buildString(s.length) {
    for (ch in s) when (ch) {
        '₹' -> append("Rs.")                    // ₹ — not in WinAnsi
        '–', '—', '−' -> append('-')  // en/em dash, minus
        '‘', '’' -> append('\'')
        '“', '”' -> append('"')
        '…' -> append("...")
        '•' -> append('-')
        '\u00A0' -> append(' ') // NBSP
        else -> append(if (ch.code in 32..126 || ch.code in 160..255) ch else '?')
    }
}

private fun awt(rgb: Int) = Color((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)

private fun textWidth(s: String, font: PDFont, size: Float): Float =
    font.getStringWidth(winAnsi(s)) / 1000f * size

/** Greedy word wrap (hard-splits over-long words) in WinAnsi-sanitized space. */
private fun wrapText(text: String, font: PDFont, size: Float, maxW: Float): List<String> {
    val clean = winAnsi(text).trim()
    if (clean.isEmpty()) return listOf("")
    val out = ArrayList<String>(2)
    var line = ""
    fun flush() { if (line.isNotEmpty()) { out.add(line); line = "" } }
    for (word in clean.split(' ')) {
        if (word.isEmpty()) continue
        var w = word
        while (textWidth(w, font, size) > maxW && w.length > 1) {
            flush() // hard-break a single over-wide token
            var cut = w.length - 1
            while (cut > 1 && textWidth(w.take(cut), font, size) > maxW) cut--
            out.add(w.take(cut))
            w = w.drop(cut)
        }
        val candidate = if (line.isEmpty()) w else "$line $w"
        if (textWidth(candidate, font, size) <= maxW) line = candidate
        else { flush(); line = w }
    }
    flush()
    return out.ifEmpty { listOf("") }
}

private class A4ReportWriter(private val pdf: PDDocument, private val doc: ReportDoc) {
    private val pageRect = PDRectangle.A4
    private val pageW = pageRect.width
    private val pageH = pageRect.height
    private val left = MARGIN_MM * MM
    private val right = pageW - MARGIN_MM * MM
    private val contentW = right - left
    private val topY get() = pageH - doc.headerMm * MM
    private val bottomY = doc.footerMm * MM + 8f

    private val fontR: PDFont = PDType1Font.HELVETICA
    private val fontB: PDFont = PDType1Font.HELVETICA_BOLD
    private val fontMonoB: PDFont = PDType1Font.COURIER_BOLD

    /** Column the QR + its caption own, left of "Verified by". */
    private val qrBlockW = 118f

    private val accent = awt(doc.accentRgb)
    private val ink = Color(0x21, 0x25, 0x29)
    private val gray = Color(0x64, 0x6C, 0x74)
    private val lightRule = Color(0xE2, 0xE6, 0xEA)
    private val headFill = Color(0xEF, 0xF1, 0xF4)
    private val boxFill = Color(0xF6, 0xF7, 0xF9)
    private val boxStroke = Color(0xD5, 0xDA, 0xE0)

    // Result table columns: Parameter | Result | Unit | Ref. range | Flag.
    // Sized per section: a test with analyzer graphs gives the right-hand
    // panel its width and the columns squeeze into what is left.
    private inner class Cols(val tableW: Float) {
        val wParam = tableW * 0.36f
        val wValue = tableW * 0.11f
        val wUnit = tableW * 0.19f
        val wRef = tableW * 0.19f
        val xParam = left + 4f
        val xValue = left + wParam
        val xUnit = xValue + wValue
        val xRef = xUnit + wUnit
        val xFlag = xRef + wRef
        val right = left + tableW
    }
    private var cols = Cols(contentW)
    private val wParam get() = cols.wParam
    private val wUnit get() = cols.wUnit
    private val wRef get() = cols.wRef
    private val xParam get() = cols.xParam
    private val xValue get() = cols.xValue
    private val xUnit get() = cols.xUnit
    private val xRef get() = cols.xRef
    private val xFlag get() = cols.xFlag
    private val panelW = PANEL_MM * MM
    private var pageIndex = 0

    private var cs: PDPageContentStream? = null
    private var y = 0f

    fun render() {
        newPage()
        doc.pageGroups.forEachIndexed { i, group ->
            // Every group after the first starts on a fresh sheet — that is
            // what makes it a report a lab can hand over on its own.
            if (i > 0) newPage()
            group.heading?.let { drawGroupHeading(it) }
            group.sections.forEachIndexed { j, s ->
                drawSection(s, closesGroup = j == group.sections.lastIndex)
            }
            drawSignatures(group.flagLegendLine, group.sections.lastOrNull())
        }
        cs?.close(); cs = null
        stampFooters()
    }

    // ── page plumbing ──

    /**
     * Fresh sheet: letterhead (or the reserved blank band), then the title and
     * the patient block. The patient block is on EVERY page, not just the
     * first — sheets get separated (a department page goes to one consultant,
     * one page is photographed), and ISO 15189 / NABL want each page to
     * identify the patient and the report by itself.
     */
    private fun newPage() {
        cs?.close()
        val page = PDPage(pageRect)
        pdf.addPage(page)
        pageIndex++
        // compress=false: local temp artifacts; keeps the output trivially inspectable.
        cs = PDPageContentStream(pdf, page, AppendMode.OVERWRITE, false)
        if (doc.mode == LetterheadMode.PRINTED) drawLetterhead()
        y = topY - 6f
        drawTitle()
        drawPatientBlock()
    }

    private fun ensure(need: Float) {
        if (y - need < bottomY) newPage()
    }

    // ── low-level draw helpers ──

    private fun text(x: Float, baseline: Float, s: String, font: PDFont, size: Float, color: Color) {
        val clean = winAnsi(s)
        if (clean.isBlank()) return
        val c = cs ?: return
        c.setNonStrokingColor(color)
        c.beginText()
        c.setFont(font, size)
        c.newLineAtOffset(x, baseline)
        c.showText(clean)
        c.endText()
    }

    private fun textRight(xEnd: Float, baseline: Float, s: String, font: PDFont, size: Float, color: Color) =
        text(xEnd - textWidth(s, font, size), baseline, s, font, size, color)

    /** Centred on an arbitrary x — axis ticks sit under the channel they mark. */
    private fun textCentered(atX: Float, baseline: Float, s: String, font: PDFont, size: Float, color: Color) =
        text(atX - textWidth(s, font, size) / 2f, baseline, s, font, size, color)

    private fun textCenter(baseline: Float, s: String, font: PDFont, size: Float, color: Color) =
        text((pageW - textWidth(s, font, size)) / 2f, baseline, s, font, size, color)

    private fun hline(x1: Float, x2: Float, atY: Float, color: Color, width: Float) {
        val c = cs ?: return
        c.setStrokingColor(color); c.setLineWidth(width)
        c.moveTo(x1, atY); c.lineTo(x2, atY); c.stroke()
    }

    private fun fillRect(x: Float, yBottom: Float, w: Float, h: Float, color: Color) {
        val c = cs ?: return
        c.setNonStrokingColor(color); c.addRect(x, yBottom, w, h); c.fill()
    }

    // ── letterhead (PRINTED mode, every page) ──

    private fun drawLetterhead() {
        val bandH = 3.2f * MM
        fillRect(0f, pageH - bandH, pageW, bandH, accent)
        val ruleY = topY + 4f
        hline(left, right, ruleY, accent, 1.1f)

        // Lab name: large + bold, shrink-to-fit, ALWAYS the license lab name.
        val name = doc.labName.uppercase()
        var size = 19f
        while (size > 11f && textWidth(name, fontB, size) > contentW) size -= 1f
        var ly = pageH - bandH - 24f
        if (ly > ruleY + 6f) textCenter(ly, name, fontB, size, ink)
        ly -= 13f
        for (line in doc.letterheadLines) {
            if (ly < ruleY + 7f) break
            textCenter(ly, line, fontR, 8.6f, gray)
            ly -= 11f
        }
    }

    // ── content blocks ──

    private fun drawTitle() {
        val c = cs ?: return
        val t = "LABORATORY REPORT"
        val spacing = 2.2f
        val w = textWidth(t, fontB, 10.5f) + spacing * (t.length - 1)
        c.setNonStrokingColor(ink)
        c.setCharacterSpacing(spacing)
        c.beginText()
        c.setFont(fontB, 10.5f)
        c.newLineAtOffset((pageW - w) / 2f, y - 10f)
        c.showText(t)
        c.endText()
        c.setCharacterSpacing(0f)
        y -= 22f
    }

    private fun drawPatientBlock() {
        val pad = 9f
        val lineH = 13f
        val labelW = 62f
        val colW = contentW / 2f
        val valueW = colW - labelW - pad * 2f

        data class Cell(val label: String, val value: String, val font: PDFont, val size: Float, val color: Color)

        val leftCells = listOf(
            Cell("Patient", doc.patientName, fontB, 9.5f, ink),
            Cell("Age / Sex", doc.ageSex, fontR, 9f, ink),
            Cell("Phone", doc.phone ?: "-", fontR, 9f, ink),
            Cell("Referred by", doc.referrer ?: "-", fontR, 9f, ink),
        )
        val rightCells = listOf(
            Cell("Accession", doc.accession, fontMonoB, 9.5f, ink),
            Cell("Registered", doc.registered, fontR, 9f, ink),
            Cell("Reported", doc.reported ?: "-", fontR, 9f, ink),
            Cell("Priority", doc.priority ?: "Routine", if (doc.priority != null) fontB else fontR, 9f,
                if (doc.priority != null) awt(ReportColors.HIGH_RED) else ink),
        )

        fun columnLines(cells: List<Cell>): List<Pair<Cell, List<String>>> =
            cells.map { it to wrapText(it.value, it.font, it.size, valueW) }

        val leftCol = columnLines(leftCells)
        val rightCol = columnLines(rightCells)
        val leftH = leftCol.sumOf { it.second.size } * lineH
        val rightH = rightCol.sumOf { it.second.size } * lineH
        // The accession barcode sits at the top of the right column, above
        // the rows; the "Accession" row right under it is its readable text.
        val bars = accessionBarcode(doc.accession)
        val barH = if (bars != null) BARCODE_MM * MM else 0f
        val barGap = if (bars != null) 6f else 0f
        val boxH = maxOf(leftH, barH + barGap + rightH) + pad * 2f

        // No ensure(): this is drawn by newPage() at the top of a fresh sheet,
        // and a page break from inside it would recurse straight back here.
        fillRect(left, y - boxH, contentW, boxH, boxFill)
        cs?.let { c ->
            c.setStrokingColor(boxStroke); c.setLineWidth(0.8f)
            c.addRect(left, y - boxH, contentW, boxH); c.stroke()
        }

        if (bars != null) {
            val module = minOf(BARCODE_MODULE_MM * MM, (colW - pad * 2f) / bars.size)
            val bw = module * bars.size
            drawBars(bars, right - pad - bw, y - pad - barH, module, barH)
        }

        fun drawColumn(col: List<Pair<Cell, List<String>>>, x0: Float, top: Float) {
            var by = top
            for ((cell, lines) in col) {
                text(x0, by, cell.label, fontR, 7.3f, gray)
                lines.forEach { l ->
                    text(x0 + labelW, by, l, cell.font, cell.size, cell.color)
                    by -= lineH
                }
            }
        }
        drawColumn(leftCol, left + pad, y - pad - 9f)
        drawColumn(rightCol, left + colW + pad, y - pad - barH - barGap - 9f)
        y -= boxH + 14f
        // Per-test release: what this sheet does NOT cover, on every sheet, so
        // a partial report can never be mistaken for the whole order.
        if (doc.toFollow.isNotEmpty()) {
            for (line in wrapText("To follow in a separate report: " + doc.toFollow.joinToString(", "), fontR, 8f, contentW)) {
                text(left, y - 8f, line, fontR, 8f, gray)
                y -= 11f
            }
            y -= 4f
        }
    }

    /** Code 128 bars as filled rectangles (runs merged) — resolution-independent
     *  like the QR, and no image codec. [x]/[yBottom] = bottom-left of the symbol. */
    private fun drawBars(bars: BooleanArray, x: Float, yBottom: Float, module: Float, h: Float) {
        val c = cs ?: return
        c.setNonStrokingColor(Color.BLACK)
        var i = 0
        while (i < bars.size) {
            if (!bars[i]) { i++; continue }
            var run = 1
            while (i + run < bars.size && bars[i + run]) run++
            c.addRect(x + i * module, yBottom, module * run, h)
            i += run
        }
        c.fill()
    }

    /** Department banner (PER_DEPARTMENT only): centred spaced capitals in the
     *  accent, the way the reference labs head a "BIO CHEMISTRY" sheet. */
    private fun drawGroupHeading(title: String) {
        ensure(30f)
        val c = cs ?: return
        val t = winAnsi(title.uppercase())
        val spacing = 1.6f
        val w = textWidth(t, fontB, 10f) + spacing * (t.length - 1)
        c.setNonStrokingColor(accent)
        c.setCharacterSpacing(spacing)
        c.beginText()
        c.setFont(fontB, 10f)
        c.newLineAtOffset((pageW - w) / 2f, y - 10f)
        c.showText(t)
        c.endText()
        c.setCharacterSpacing(0f)
        y -= 18f
    }

    private fun tableHeader() {
        val h = 15f
        ensure(h + 14f)
        fillRect(left, y - h, cols.tableW, h, headFill)
        val by = y - h + 4.5f
        text(xParam, by, "Parameter", fontB, 8f, ink)
        text(xValue, by, "Result", fontB, 8f, ink)
        text(xUnit, by, "Unit", fontB, 8f, ink)
        text(xRef, by, "Ref. range", fontB, 8f, ink)
        text(xFlag, by, "Flag", fontB, 8f, ink)
        y -= h + 3f
    }

    private fun drawSection(section: ReportSection, closesGroup: Boolean) {
        val panel = section.graphs.filter { it.hasCurve || it.hasImage }
        cols = Cols(if (panel.isEmpty()) contentW else contentW - panelW - PANEL_GAP)
        val panelH = if (panel.isEmpty()) 0f else panelHeight(panel)
        // The graph panel never splits across sheets: it needs its full height
        // under the title, or the whole section moves to a fresh sheet. When the
        // section closes its group the sign-off must fit under the panel too —
        // a two-row table beside a four-graph panel ends at the PANEL's bottom,
        // and without this the signatures went to a sheet of their own (review).
        val tail = if (closesGroup && panel.isNotEmpty()) 16f + signOff.need else 0f
        ensure(maxOf(52f, 20f + panelH + tail))
        sectionTitle(section.title, section.sampleType)
        val panelTop = y
        val panelPage = pageIndex
        if (panel.isNotEmpty()) drawPanel(panel, right - panelW, panelTop)
        tableHeader()
        section.rows.forEachIndexed { i, row ->
            // KEEP-WITH-NEXT: the group's final row travels with the sign-off.
            // Otherwise a test whose rows just fill the sheet gets its results
            // on one page and its signature on the next — a results sheet
            // nobody signed, and a signed sheet that shows no result. Found by
            // review: a 24-32 row CBC did exactly that under the defaults.
            val keepWith = if (closesGroup && i == section.rows.lastIndex) 10f + signOff.need else 0f
            drawRow(row, section, keepWith)
        }
        y -= 10f
        // A short table still clears the panel before whatever comes next —
        // unless the rows already moved on to a later sheet.
        if (panel.isNotEmpty() && pageIndex == panelPage) y = minOf(y, panelTop - panelH - 6f)
        cols = Cols(contentW)
    }

    private fun panelHeight(graphs: List<ReportGraph>): Float =
        graphs.sumOf { g -> (GRAPH_TITLE_H + (if (g.hasCurve) GRAPH_H else panelW) + GRAPH_GAP).toDouble() }.toFloat()

    /** Stacked graph boxes down the panel: title, framed box, curve or bitmap. */
    private fun drawPanel(graphs: List<ReportGraph>, x: Float, top: Float) {
        var gy = top
        for (g in graphs) {
            text(x + 2f, gy - 7f, g.title, fontB, 7f, gray)
            gy -= GRAPH_TITLE_H
            val boxH = if (g.hasCurve) GRAPH_H else panelW
            val boxBottom = gy - boxH
            cs?.let { c ->
                c.setStrokingColor(boxStroke); c.setLineWidth(0.6f)
                c.addRect(x, boxBottom, panelW, boxH); c.stroke()
            }
            if (g.hasCurve) drawCurve(g, x, boxBottom, panelW, boxH)
            else g.image?.let { drawGraphImage(it, x, boxBottom, panelW, boxH, g.kind) }
            // Axis numbers UNDER the box, so a reader can see where a
            // population sits rather than only that one exists. Drawn outside
            // the frame because the curve already uses the full inner width.
            val ticks = g.xTicks
            if (ticks.isNotEmpty()) {
                val max = g.xAxisMax ?: 0.0
                val pad = 3f
                val innerW = panelW - 2 * pad
                ticks.forEachIndexed { i, v ->
                    val tx = x + pad + (innerW * (v / max)).toFloat()
                    val label = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
                    when (i) {
                        0 -> text(tx, boxBottom - 6f, label, fontR, 5f, gray)
                        ticks.lastIndex -> textRight(tx, boxBottom - 6f, label, fontR, 5f, gray)
                        else -> textCentered(tx, boxBottom - 6f, label, fontR, 5f, gray)
                    }
                }
                g.xLabel?.let { textRight(x + panelW, boxBottom + 3f, it, fontR, 5.5f, gray) }
                gy = boxBottom - GRAPH_GAP - 7f
            } else {
                g.xLabel?.let { textRight(x + panelW - 2f, boxBottom + 2f, it, fontR, 5.5f, gray) }
                gy = boxBottom - GRAPH_GAP
            }
            // Named axes (the DIFF plane): LAS up the left, MAS along the
            // bottom right, drawn INSIDE the box so they sit over the
            // analyzer's own white margin rather than stealing table width.
            g.yLabel?.let { text(x + 3f, boxBottom + boxH - 8f, it, fontB, 5.5f, ink) }
        }
    }

    /**
     * The histogram as the analyzer measured it: channels left to right,
     * heights normalised to the tallest channel, a tinted fill under an
     * outline, dashed discriminator lines where the analyzer put them.
     */
    private fun drawCurve(g: ReportGraph, x: Float, yBottom: Float, w: Float, h: Float) {
        val c = cs ?: return
        val pts = g.points
        val max = pts.maxOrNull()?.takeIf { it > 0.0 } ?: return
        val pad = 3f
        val innerW = w - 2 * pad
        val innerH = h - 2 * pad - 4f
        val n = pts.size
        val span = (n - 1).coerceAtLeast(1)
        fun px(i: Int) = x + pad + innerW * i / span
        fun py(v: Double) = yBottom + pad + (innerH * (v / max)).toFloat()
        val (stroke, fill) = graphColors(g.kind)
        c.setNonStrokingColor(fill)
        c.moveTo(px(0), yBottom + pad)
        for (i in 0 until n) c.lineTo(px(i), py(pts[i]))
        c.lineTo(px(n - 1), yBottom + pad)
        c.closePath(); c.fill()
        c.setStrokingColor(stroke); c.setLineWidth(0.7f)
        c.moveTo(px(0), py(pts[0]))
        for (i in 1 until n) c.lineTo(px(i), py(pts[i]))
        c.stroke()
        if (g.lines.isNotEmpty()) {
            c.setLineDashPattern(floatArrayOf(1.5f, 1.5f), 0f)
            c.setStrokingColor(gray); c.setLineWidth(0.5f)
            for (l in g.lines) {
                if (l < 0.0 || l > span) continue
                val lx = x + pad + innerW * (l / span).toFloat()
                c.moveTo(lx, yBottom + pad); c.lineTo(lx, yBottom + h - pad); c.stroke()
            }
            c.setLineDashPattern(floatArrayOf(), 0f)
        }
    }

    /** Outline colour and a light tint of it for the fill — WBC in the accent,
     *  RBC red, PLT green, the convention every analyzer screen uses. */
    private fun graphColors(kind: String): Pair<Color, Color> {
        val base = when (kind) {
            "rbc" -> awt(ReportColors.HIGH_RED)
            "plt" -> awt(ReportPalette.GREEN)
            else -> accent
        }
        val tint = Color(255 - (255 - base.red) * 22 / 100, 255 - (255 - base.green) * 22 / 100, 255 - (255 - base.blue) * 22 / 100)
        return base to tint
    }

    /** The analyzer's bitmap (BMP/PNG), fitted inside the box. A bitmap that
     *  will not decode leaves the framed box empty rather than failing the print. */
    private fun drawGraphImage(bytes: ByteArray, x: Float, yBottom: Float, w: Float, h: Float, tag: String) {
        val c = cs ?: return
        runCatching {
            val img = PDImageXObject.createFromByteArray(pdf, bytes, "graph-$tag")
            if (img.width <= 0 || img.height <= 0) return
            val scale = minOf((w - 4f) / img.width, (h - 4f) / img.height)
            val dw = img.width * scale; val dh = img.height * scale
            c.drawImage(img, x + (w - dw) / 2f, yBottom + (h - dh) / 2f, dw, dh)
        }
    }

    /** Test name left, "Sample: Serum" right — the specimen on every sheet. */
    private fun sectionTitle(title: String, sampleType: String? = null) {
        text(left, y - 11f, title, fontB, 11f, accent)
        sampleType?.let { textRight(right, y - 11f, "Sample: $it", fontR, 8.5f, gray) }
        y -= 15f
        hline(left, right, y, accent, 0.9f)
        y -= 5f
    }

    /**
     * A table that spills onto a fresh sheet re-states which test it belongs
     * to. Every sheet has to read on its own, and a page of bare rows under a
     * "Parameter / Result" header does not — the reader cannot tell a
     * continued LFT from a continued lipid profile.
     */
    private fun continueSection(section: ReportSection) {
        newPage()
        sectionTitle(section.title + " (contd.)", section.sampleType)
        tableHeader()
    }

    /** [keepWith]: extra room that must follow this row on the same sheet. */
    private fun drawRow(row: ReportRow, section: ReportSection, keepWith: Float = 0f) {
        if (row.heading) {
            val hH = 13f
            if (y - hH - keepWith < bottomY) continueSection(section)
            text(xParam, y - 9f, row.param, fontB, 8.5f, accent)
            y -= hH
            return
        }
        val paramLines = wrapText(row.param, fontR, 9f, wParam - 10f)
        val unitLines = if (row.unit.isBlank()) emptyList() else wrapText(row.unit, fontR, 8.5f, wUnit - 6f)
        val refLines = wrapText(row.ref.ifBlank { "-" }, fontR, 8.5f, wRef - 6f)
        val lineH = 11f
        val lines = maxOf(paramLines.size, unitLines.size, refLines.size, 1)
        val rowH = lines * lineH + 3.5f
        if (y - rowH - keepWith < bottomY) continueSection(section)

        val emphasis = flagEmphasisRgb(row.flag)
        val vColor = emphasis?.let { awt(it) } ?: ink
        val vFont = if (emphasis != null) fontB else fontR
        val base = y - 9f

        paramLines.forEachIndexed { i, l -> text(xParam, base - i * lineH, l, fontR, 9f, ink) }
        text(xValue, base, row.value, vFont, 9f, vColor)
        unitLines.forEachIndexed { i, l -> text(xUnit, base - i * lineH, l, fontR, 8.5f, ink) }
        refLines.forEachIndexed { i, l -> text(xRef, base - i * lineH, l, fontR, 8.5f, gray) }
        val fl = flagLabel(row.flag)
        if (fl.isNotEmpty()) {
            text(xFlag, base, fl, if (emphasis != null) fontB else fontR, 8f, if (emphasis != null) vColor else gray)
        }
        hline(left, cols.right, y - rowH, lightRule, 0.4f)
        y -= rowH + 1.5f
    }

    /**
     * Sign-off block, and the report-download QR when the report has one.
     *
     * Layout, left to right: the QR (only when [ReportDoc.qr] is set — standalone
     * licences have none and get the pre-QR layout back, byte for byte), then
     * "Verified by", then the approver's block hard against the right margin.
     *
     * An unsigned report is the baseline, not a degraded case: with no signature
     * image, no credentials and no approval date, every measurement below
     * collapses to exactly what this function drew before signatures existed.
     */
    /**
     * The sign-off block's geometry — everything about it that does not depend
     * on where it lands, computed once. [need] is the room the block, the flag
     * key and the end-of-report line take together; it is what the
     * keep-with-next rule in [drawSection] reserves under the group's last row.
     */
    private inner class SignOffMetrics {
        val sigImage: ByteArray? = doc.signature?.imagePng?.takeIf { it.isNotEmpty() }
        val verifierImage: ByteArray? = doc.verifierSignature?.imagePng?.takeIf { it.isNotEmpty() }
        /** The gap is where a pen would go; an image needs a little more room.
         *  One gap for both columns, so the two rules stay level. */
        val gap = if (sigImage != null || verifierImage != null) 48f else 34f

        val qr = doc.qr?.takeIf { it.matrix.size > 0 }
        val qrSide = QR_MM * MM
        val qrCaption = qr?.let { wrapText(it.caption, fontB, 7f, qrBlockW) }.orEmpty()
        val qrNote = qr?.let { wrapText(it.note, fontR, 6.2f, qrBlockW) }.orEmpty()
        // Quiet zone: 4 modules of white on every side, per the QR spec. Nothing
        // is DRAWN for it — the page is already white — but it must be reserved,
        // or the caption crowds the code and scanners start missing it.
        val quiet = if (qr == null) 0f else qrSide / qr.matrix.size * 4f
        val qrH = if (qr == null) 0f else qrSide + quiet * 2f + 4f +
            qrCaption.size * 9f + qrNote.size * 8f

        /** Each outer column stops 8pt short of the QR's quiet zone (or splits
         *  the width when there is no code), so a long name or degree string
         *  WRAPS instead of running under the code and blinding the scanner. */
        val colW = if (qr != null) pageW / 2f - qrSide / 2f - quiet - 8f - left else contentW / 2f - 8f
        val verifierName = wrapText(doc.verifiedBy ?: "-", fontB, 10.5f, colW)
        val approverName = wrapText(doc.approvedBy ?: "Authorised Signatory", fontB, 10.5f, colW)
        val credentialLines = listOfNotNull(
            doc.signature?.qualifications?.takeIf { it.isNotBlank() },
            doc.signature?.registrationNo?.takeIf { it.isNotBlank() }?.let { "Reg. No. $it" },
            doc.approvedOn?.takeIf { it.isNotBlank() }?.let { "Approved on $it" },
        ).flatMap { wrapText(it, fontR, 8f, colW) }
        val verifierLines = listOfNotNull(
            doc.verifierSignature?.qualifications?.takeIf { it.isNotBlank() },
            doc.verifierSignature?.registrationNo?.takeIf { it.isNotBlank() }?.let { "Reg. No. $it" },
        ).flatMap { wrapText(it, fontR, 8f, colW) }
        /** Ink below a rule: name lines at a 12pt pitch (first baseline 13pt
         *  down), the role label, then credentials at 10pt. The taller column
         *  sets the block. */
        val belowRule = maxOf(
            13f + (verifierName.size - 1) * 12f + 12f + verifierLines.size * 10f,
            13f + (approverName.size - 1) * 12f + 12f + credentialLines.size * 10f,
        )
        val signOffH = gap + belowRule

        /** 40pt under the taller column: the 15pt drop below its last line,
         *  then the flag key and the end-of-report line (12pt each) still
         *  land above the footer band. */
        val need = maxOf(signOffH, qrH) + 40f
    }

    private val signOff by lazy { SignOffMetrics() }

    private fun drawSignatures(flagLegendLine: String, lastSection: ReportSection?) {
        val m = signOff
        val sigImage = m.sigImage
        val verifierImage = m.verifierImage
        val gap = m.gap
        val credentialLines = m.credentialLines
        val verifierLines = m.verifierLines
        val qr = m.qr
        val qrSide = m.qrSide
        val qrCaption = m.qrCaption
        val qrNote = m.qrNote
        val quiet = m.quiet

        // Keep-with-next in drawSection normally guarantees the room. This
        // fallback is for a section with no result rows at all — the fresh
        // sheet must still name the test it signs off.
        if (y - m.need < bottomY) {
            newPage()
            lastSection?.let { sectionTitle(it.title + " (contd.)") }
        }

        val blockTop = y
        val lineY = blockTop - gap
        val sigW = 150f
        val ruleColor = Color(0x9A, 0xA2, 0xAA)

        // Three columns, the way the reference labs lay a sign-off out: the
        // technician who VERIFIED on the left, the pathologist who APPROVED on
        // the right, the download code between them. Each image sits ON its
        // rule like an inked signature, clamped to the gap so it can never run
        // up into the last result row.
        if (verifierImage != null) {
            drawSignatureImage(verifierImage, left, lineY + 2f, gap - 8f, sigW, rightAligned = false, tag = "verifier-signature")
        }
        if (sigImage != null) {
            drawSignatureImage(sigImage, right, lineY + 2f, gap - 8f, sigW, rightAligned = true, tag = "approver-signature")
        }

        hline(left, left + sigW, lineY, ruleColor, 0.8f)
        hline(right - sigW, right, lineY, ruleColor, 0.8f)

        var vy = lineY - 1f
        for (line in m.verifierName) { vy -= 12f; text(left, vy, line, fontB, 10.5f, ink) }
        vy -= 12f
        text(left, vy, "Verified by", fontR, 8f, gray)
        for (line in verifierLines) { vy -= 10f; text(left, vy, line, fontR, 8f, gray) }

        var cy = lineY - 1f
        for (line in m.approverName) { cy -= 12f; textRight(right, cy, line, fontB, 10.5f, ink) }
        cy -= 12f
        textRight(right, cy, "Approved by (Pathologist)", fontR, 8f, gray)
        for (line in credentialLines) { cy -= 10f; textRight(right, cy, line, fontR, 8f, gray) }

        var qrBottom = blockTop
        if (qr != null) {
            drawQrMatrix(qr.matrix, pageW / 2f - qrSide / 2f, blockTop - quiet - qrSide, qrSide)
            var ty = blockTop - quiet * 2f - qrSide - 4f
            qrCaption.forEach { textCenter(ty, it, fontB, 7f, ink); ty -= 9f }
            qrNote.forEach { textCenter(ty, it, fontR, 6.2f, gray); ty -= 8f }
            qrBottom = ty
        }

        y = minOf(lineY - m.belowRule - 15f, qrBottom - 6f)
        // Flag key. Only present when something on this report is actually
        // abnormal — a legend explaining marks that aren't there is noise on a
        // patient's paper. Already WinAnsi-safe (ASCII + U+00B7), which matters
        // because winAnsi() collapses anything else to '?'.
        if (flagLegendLine.isNotEmpty()) {
            text(left, y, flagLegendLine, fontR, 7.5f, gray)
            y -= 12f
        }
        textCenter(y, "--- End of report ---", fontR, 7.5f, gray)
        y -= 12f
    }

    /**
     * Draw a signatory's signature PNG anchored at [xAnchor] (its right edge
     * when [rightAligned], else its left), sitting on [baseY], scaled to fit
     * inside [maxH] x [maxW].
     *
     * Failure is swallowed on purpose. A signature image that will not decode
     * must not stop a pathologist-approved report from printing — the typed
     * name and the rule under it are the part that carries weight, and they are
     * already on the page.
     */
    private fun drawSignatureImage(
        bytes: ByteArray, xAnchor: Float, baseY: Float, maxH: Float, maxW: Float,
        rightAligned: Boolean, tag: String,
    ) {
        val c = cs ?: return
        runCatching {
            val img = PDImageXObject.createFromByteArray(pdf, bytes, tag)
            if (img.width <= 0 || img.height <= 0) return
            val scale = minOf(maxH / img.height.toFloat(), maxW / img.width.toFloat())
            val w = img.width * scale
            c.drawImage(img, if (rightAligned) xAnchor - w else xAnchor, baseY, w, img.height * scale)
        }
    }

    /**
     * Fill the QR modules as rectangles — resolution-independent, no image
     * codec, and it survives any printer scaling. [x]/[yBottom] are the bottom-
     * left of the SYMBOL (the quiet zone is the caller's business).
     *
     * Runs of adjacent dark modules become one rectangle: identical ink, far
     * fewer path operations on a 49x49 code.
     */
    private fun drawQrMatrix(matrix: QrMatrix, x: Float, yBottom: Float, side: Float) {
        val c = cs ?: return
        val n = matrix.size
        if (n <= 0) return
        val cell = side / n
        c.setNonStrokingColor(Color.BLACK)
        for (my in 0 until n) {
            var mx = 0
            while (mx < n) {
                if (!matrix[mx, my]) { mx++; continue }
                var run = 1
                while (mx + run < n && matrix[mx + run, my]) run++
                // Matrix row 0 is the TOP; PDF y grows upward, hence the flip.
                c.addRect(x + mx * cell, yBottom + (n - 1 - my) * cell, cell * run, cell)
                mx += run
            }
        }
        c.fill()
    }

    /** Second pass: footer rule + note + "Page X of Y" (PRINTED mode only —
     *  PREPRINTED letterpads keep the whole footer band blank). */
    private fun stampFooters() {
        if (doc.mode != LetterheadMode.PRINTED) return
        val total = pdf.numberOfPages
        val ruleY = doc.footerMm * MM
        if (ruleY < 18f) return // not enough reserved space to draw anything
        for (i in 0 until total) {
            PDPageContentStream(pdf, pdf.getPage(i), AppendMode.APPEND, false, true).use { c ->
                cs = c
                hline(left, right, ruleY, accent, 1.0f)
                text(left, ruleY - 11f, "This is a computer-generated report · Generated ${doc.generatedAt}",
                    fontR, 7.5f, gray)
                textRight(right, ruleY - 11f, "Page ${i + 1} of $total", fontR, 7.5f, gray)
            }
        }
        cs = null
    }
}
