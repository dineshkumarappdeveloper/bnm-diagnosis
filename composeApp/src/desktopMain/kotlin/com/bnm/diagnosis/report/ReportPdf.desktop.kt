package com.bnm.diagnosis.report

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.printing.PDFPageable
import java.awt.Color
import java.awt.Desktop
import java.awt.print.PrinterJob
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

actual fun writeLabReportPdf(doc: ReportDoc): String {
    val dir = File(System.getProperty("java.io.tmpdir"), "bnm-diagnosis-reports").apply { mkdirs() }
    val safe = doc.accession.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "lab" }
    val file = File(dir, "$safe-report.pdf")
    PDDocument().use { pdf ->
        A4ReportWriter(pdf, doc).render()
        pdf.save(file)
    }
    return file.absolutePath
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
}

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
    "Print failed: ${e.message}"
}

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

    private val accent = awt(doc.accentRgb)
    private val ink = Color(0x21, 0x25, 0x29)
    private val gray = Color(0x64, 0x6C, 0x74)
    private val lightRule = Color(0xE2, 0xE6, 0xEA)
    private val headFill = Color(0xEF, 0xF1, 0xF4)
    private val boxFill = Color(0xF6, 0xF7, 0xF9)
    private val boxStroke = Color(0xD5, 0xDA, 0xE0)

    // Result table columns: Parameter | Result | Unit | Ref. range | Flag
    private val wParam = contentW * 0.36f
    private val wValue = contentW * 0.15f
    private val wUnit = contentW * 0.11f
    private val wRef = contentW * 0.24f
    private val xParam = left + 4f
    private val xValue = left + wParam
    private val xUnit = xValue + wValue
    private val xRef = xUnit + wUnit
    private val xFlag = xRef + wRef

    private var cs: PDPageContentStream? = null
    private var y = 0f

    fun render() {
        newPage()
        drawTitle()
        drawPatientBlock()
        doc.sections.forEach { drawSection(it) }
        drawSignatures()
        cs?.close(); cs = null
        stampFooters()
    }

    // ── page plumbing ──

    private fun newPage() {
        cs?.close()
        val page = PDPage(pageRect)
        pdf.addPage(page)
        // compress=false: local temp artifacts; keeps the output trivially inspectable.
        cs = PDPageContentStream(pdf, page, AppendMode.OVERWRITE, false)
        if (doc.mode == LetterheadMode.PRINTED) drawLetterhead()
        y = topY - 6f
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
        val boxH = maxOf(leftH, rightH) + pad * 2f

        ensure(boxH + 8f)
        fillRect(left, y - boxH, contentW, boxH, boxFill)
        cs?.let { c ->
            c.setStrokingColor(boxStroke); c.setLineWidth(0.8f)
            c.addRect(left, y - boxH, contentW, boxH); c.stroke()
        }

        fun drawColumn(col: List<Pair<Cell, List<String>>>, x0: Float) {
            var by = y - pad - 9f
            for ((cell, lines) in col) {
                text(x0, by, cell.label, fontR, 7.3f, gray)
                lines.forEach { l ->
                    text(x0 + labelW, by, l, cell.font, cell.size, cell.color)
                    by -= lineH
                }
            }
        }
        drawColumn(leftCol, left + pad)
        drawColumn(rightCol, left + colW + pad)
        y -= boxH + 14f
    }

    private fun tableHeader() {
        val h = 15f
        ensure(h + 14f)
        fillRect(left, y - h, contentW, h, headFill)
        val by = y - h + 4.5f
        text(xParam, by, "Parameter", fontB, 8f, ink)
        text(xValue, by, "Result", fontB, 8f, ink)
        text(xUnit, by, "Unit", fontB, 8f, ink)
        text(xRef, by, "Ref. range", fontB, 8f, ink)
        text(xFlag, by, "Flag", fontB, 8f, ink)
        y -= h + 3f
    }

    private fun drawSection(section: ReportSection) {
        ensure(52f) // title + rule + header + first row
        text(left, y - 11f, section.title, fontB, 11f, accent)
        y -= 15f
        hline(left, right, y, accent, 0.9f)
        y -= 5f
        tableHeader()
        for (row in section.rows) drawRow(row)
        y -= 10f
    }

    private fun drawRow(row: ReportRow) {
        val paramLines = wrapText(row.param, fontR, 9f, wParam - 10f)
        val refLines = wrapText(row.ref.ifBlank { "-" }, fontR, 8.5f, wRef - 6f)
        val lineH = 11f
        val lines = maxOf(paramLines.size, refLines.size, 1)
        val rowH = lines * lineH + 3.5f
        if (y - rowH < bottomY) { newPage(); tableHeader() }

        val emphasis = flagEmphasisRgb(row.flag)
        val vColor = emphasis?.let { awt(it) } ?: ink
        val vFont = if (emphasis != null) fontB else fontR
        val base = y - 9f

        paramLines.forEachIndexed { i, l -> text(xParam, base - i * lineH, l, fontR, 9f, ink) }
        text(xValue, base, row.value, vFont, 9f, vColor)
        text(xUnit, base, row.unit, fontR, 8.5f, ink)
        refLines.forEachIndexed { i, l -> text(xRef, base - i * lineH, l, fontR, 8.5f, gray) }
        val fl = flagLabel(row.flag)
        if (fl.isNotEmpty()) {
            text(xFlag, base, fl, if (emphasis != null) fontB else fontR, 8f, if (emphasis != null) vColor else gray)
        }
        hline(left, right, y - rowH, lightRule, 0.4f)
        y -= rowH + 1.5f
    }

    private fun drawSignatures() {
        ensure(96f)
        y -= 34f // physical signature gap
        val lineY = y
        val sigW = 150f
        hline(left, left + sigW, lineY, Color(0x9A, 0xA2, 0xAA), 0.8f)
        hline(right - sigW, right, lineY, Color(0x9A, 0xA2, 0xAA), 0.8f)
        text(left, lineY - 12f, "Verified by: ${doc.verifiedBy ?: "-"}", fontR, 9f, ink)
        val approved = doc.approvedBy ?: "Authorised Signatory"
        textRight(right, lineY - 13f, approved, fontB, 10.5f, ink)
        textRight(right, lineY - 25f, "Approved by (Pathologist)", fontR, 8f, gray)
        y = lineY - 40f
        textCenter(y, "--- End of report ---", fontR, 7.5f, gray)
        y -= 12f
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
