package com.bnm.diagnosis.report

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Android A4 report renderer — android.graphics.pdf.PdfDocument + Canvas.
 * Same layout system as the desktop PDFBox actual (top-down mm cursor, manual
 * pagination, letterhead repeated per page); page totals come from a dry
 * measure pass (finished PdfDocument pages can't be appended to later).
 */

private var reportContext: Context? = null

/** Call once from MainActivity (mirrors initPrintContext). Keep the ACTIVITY
 *  context — PrintManager.print() requires one (single-activity app). */
fun initReportContext(context: Context) { reportContext = context }

private const val MM = 72f / 25.4f
private const val MARGIN_MM = 14f
private const val PAGE_W = 595
private const val PAGE_H = 842

private const val INK = 0xFF212529.toInt()
private const val GRAY = 0xFF646C74.toInt()
private const val LIGHT_RULE = 0xFFE2E6EA.toInt()
private const val HEAD_FILL = 0xFFEFF1F4.toInt()
private const val BOX_FILL = 0xFFF6F7F9.toInt()
private const val BOX_STROKE = 0xFFD5DAE0.toInt()
private const val SIG_LINE = 0xFF9AA2AA.toInt()

private fun opaque(rgb: Int) = 0xFF000000.toInt() or rgb

actual fun writeLabReportPdf(doc: ReportDoc): String {
    val ctx = reportContext ?: return ""
    val dir = File(ctx.cacheDir, "reports").apply { mkdirs() }
    val safe = doc.accession.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "lab" }
    val file = File(dir, "$safe-report.pdf")
    val painter = AndroidReportPainter(doc)
    val total = painter.paginate()
    val pdf = PdfDocument()
    try {
        painter.renderInto(pdf, total)
        FileOutputStream(file).use { pdf.writeTo(it) }
    } finally {
        pdf.close()
    }
    return file.absolutePath
}

actual fun openPdf(path: String): String {
    val ctx = reportContext ?: return "Viewer not ready"
    val f = File(path)
    if (!f.exists()) return "PDF not found"
    return try {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
        "Opened PDF"
    } catch (e: ActivityNotFoundException) {
        "No PDF viewer installed — use Print instead"
    } catch (e: Exception) {
        "Open failed: ${e.message}"
    }
}

actual fun printPdf(path: String): String {
    val ctx = reportContext ?: return "Print not ready"
    val f = File(path)
    if (!f.exists()) return "PDF not found"
    // PrintManager must run on the main thread with an Activity context.
    Handler(Looper.getMainLooper()).post {
        runCatching {
            val pm = ctx.getSystemService(Context.PRINT_SERVICE) as PrintManager
            val attrs = PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                .build()
            pm.print(f.nameWithoutExtension, PdfFilePrintAdapter(f), attrs)
        }
    }
    return "Print dialog opened"
}

/** Streams an already-rendered PDF file into the system print pipeline. */
private class PdfFilePrintAdapter(private val file: File) : PrintDocumentAdapter() {
    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes?,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback,
        extras: Bundle?,
    ) {
        if (cancellationSignal?.isCanceled == true) { callback.onLayoutCancelled(); return }
        val info = PrintDocumentInfo.Builder(file.name)
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .build()
        callback.onLayoutFinished(info, true)
    }

    override fun onWrite(
        pages: Array<out PageRange>?,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback,
    ) {
        try {
            FileInputStream(file).use { input ->
                FileOutputStream(destination.fileDescriptor).use { out -> input.copyTo(out) }
            }
            callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (e: Exception) {
            callback.onWriteFailed(e.message)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────

private class AndroidReportPainter(private val doc: ReportDoc) {
    private val pageW = PAGE_W.toFloat()
    private val pageH = PAGE_H.toFloat()
    private val left = MARGIN_MM * MM
    private val right = pageW - MARGIN_MM * MM
    private val contentW = right - left
    private val topY = doc.headerMm * MM          // content starts below the header band
    private val bottomY = pageH - doc.footerMm * MM - 8f

    private val accent = opaque(doc.accentRgb)

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

    private var pdf: PdfDocument? = null
    private var page: PdfDocument.Page? = null
    private var canvas: Canvas? = null
    private var pageNo = 0
    private var totalPages = 1
    private var y = 0f

    /** Dry measure pass — no canvas, just pagination. Returns the page count. */
    fun paginate(): Int { runPass(null); return pageNo }

    /** Draw pass with the known page total ("Page X of Y"). */
    fun renderInto(target: PdfDocument, total: Int) { totalPages = total; runPass(target) }

    private fun runPass(target: PdfDocument?) {
        pdf = target; page = null; canvas = null; pageNo = 0
        newPage()
        drawTitle()
        drawPatientBlock()
        doc.sections.forEach { drawSection(it) }
        drawSignatures()
        finishPage()
        pdf = null
    }

    // ── page plumbing ──

    private fun newPage() {
        finishPage()
        pageNo++
        pdf?.let { d ->
            val pg = d.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
            page = pg
            canvas = pg.canvas
        }
        if (doc.mode == LetterheadMode.PRINTED) {
            drawLetterhead()
            drawFooter()
        }
        y = topY + 6f
    }

    private fun finishPage() {
        val pg = page ?: return
        pdf?.finishPage(pg)
        page = null; canvas = null
    }

    private fun ensure(need: Float) {
        if (y + need > bottomY) newPage()
    }

    // ── low-level helpers (draw calls no-op in the measure pass) ──

    private fun paintFor(size: Float, bold: Boolean, color: Int, mono: Boolean = false) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = if (mono) Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
            else Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
            textSize = size
            this.color = color
        }

    private fun width(s: String, size: Float, bold: Boolean, mono: Boolean = false): Float =
        paintFor(size, bold, INK, mono).measureText(s)

    private fun text(x: Float, baseline: Float, s: String, size: Float, bold: Boolean, color: Int, mono: Boolean = false) {
        if (s.isBlank()) return
        canvas?.drawText(s, x, baseline, paintFor(size, bold, color, mono))
    }

    private fun textRight(xEnd: Float, baseline: Float, s: String, size: Float, bold: Boolean, color: Int) =
        text(xEnd - width(s, size, bold), baseline, s, size, bold, color)

    private fun textCenter(baseline: Float, s: String, size: Float, bold: Boolean, color: Int) =
        text((pageW - width(s, size, bold)) / 2f, baseline, s, size, bold, color)

    private fun hline(x1: Float, x2: Float, atY: Float, color: Int, strokeW: Float) {
        val p = Paint().apply { this.color = color; strokeWidth = strokeW; style = Paint.Style.STROKE }
        canvas?.drawLine(x1, atY, x2, atY, p)
    }

    private fun fillRect(x: Float, yTop: Float, w: Float, h: Float, color: Int) {
        val p = Paint().apply { this.color = color; style = Paint.Style.FILL }
        canvas?.drawRect(x, yTop, x + w, yTop + h, p)
    }

    private fun strokeRect(x: Float, yTop: Float, w: Float, h: Float, color: Int, strokeW: Float) {
        val p = Paint().apply { this.color = color; strokeWidth = strokeW; style = Paint.Style.STROKE }
        canvas?.drawRect(x, yTop, x + w, yTop + h, p)
    }

    private fun wrapText(textIn: String, size: Float, bold: Boolean, maxW: Float): List<String> {
        val clean = textIn.trim()
        if (clean.isEmpty()) return listOf("")
        val out = ArrayList<String>(2)
        var line = ""
        fun flush() { if (line.isNotEmpty()) { out.add(line); line = "" } }
        for (word in clean.split(' ')) {
            if (word.isEmpty()) continue
            var w = word
            while (width(w, size, bold) > maxW && w.length > 1) {
                flush()
                var cut = w.length - 1
                while (cut > 1 && width(w.take(cut), size, bold) > maxW) cut--
                out.add(w.take(cut))
                w = w.drop(cut)
            }
            val candidate = if (line.isEmpty()) w else "$line $w"
            if (width(candidate, size, bold) <= maxW) line = candidate
            else { flush(); line = w }
        }
        flush()
        return out.ifEmpty { listOf("") }
    }

    // ── letterhead + footer (PRINTED mode, every page) ──

    private fun drawLetterhead() {
        val bandH = 3.2f * MM
        fillRect(0f, 0f, pageW, bandH, accent)
        val ruleY = topY - 4f
        hline(left, right, ruleY, accent, 1.1f)

        val name = doc.labName.uppercase()
        var size = 19f
        while (size > 11f && width(name, size, bold = true) > contentW) size -= 1f
        var ly = bandH + 24f
        if (ly < ruleY - 6f) textCenter(ly, name, size, bold = true, INK)
        ly += 13f
        for (line in doc.letterheadLines) {
            if (ly > ruleY - 7f) break
            textCenter(ly, line, 8.6f, bold = false, GRAY)
            ly += 11f
        }
    }

    private fun drawFooter() {
        val ruleY = pageH - doc.footerMm * MM
        if (pageH - ruleY < 18f) return // not enough reserved space
        hline(left, right, ruleY, accent, 1.0f)
        text(left, ruleY + 11f, "This is a computer-generated report · Generated ${doc.generatedAt}",
            7.5f, bold = false, GRAY)
        textRight(right, ruleY + 11f, "Page $pageNo of $totalPages", 7.5f, bold = false, GRAY)
    }

    // ── content blocks (mirrors the desktop layout) ──

    private fun drawTitle() {
        val p = paintFor(10.5f, bold = true, INK).apply { letterSpacing = 0.2f }
        val t = "LABORATORY REPORT"
        val w = p.measureText(t)
        canvas?.drawText(t, (pageW - w) / 2f, y + 10f, p)
        y += 22f
    }

    private fun drawPatientBlock() {
        val pad = 9f
        val lineH = 13f
        val labelW = 62f
        val colW = contentW / 2f
        val valueW = colW - labelW - pad * 2f

        data class Cell(val label: String, val value: String, val bold: Boolean, val size: Float, val color: Int, val mono: Boolean = false)

        val leftCells = listOf(
            Cell("Patient", doc.patientName, true, 9.5f, INK),
            Cell("Age / Sex", doc.ageSex, false, 9f, INK),
            Cell("Phone", doc.phone ?: "-", false, 9f, INK),
            Cell("Referred by", doc.referrer ?: "-", false, 9f, INK),
        )
        val rightCells = listOf(
            Cell("Accession", doc.accession, true, 9.5f, INK, mono = true),
            Cell("Registered", doc.registered, false, 9f, INK),
            Cell("Reported", doc.reported ?: "-", false, 9f, INK),
            Cell("Priority", doc.priority ?: "Routine", doc.priority != null, 9f,
                if (doc.priority != null) opaque(ReportColors.HIGH_RED) else INK),
        )

        fun columnLines(cells: List<Cell>) = cells.map { it to wrapText(it.value, it.size, it.bold, valueW) }
        val leftCol = columnLines(leftCells)
        val rightCol = columnLines(rightCells)
        val leftH = leftCol.sumOf { it.second.size } * lineH
        val rightH = rightCol.sumOf { it.second.size } * lineH
        val boxH = maxOf(leftH, rightH) + pad * 2f

        ensure(boxH + 8f)
        fillRect(left, y, contentW, boxH, BOX_FILL)
        strokeRect(left, y, contentW, boxH, BOX_STROKE, 0.8f)

        fun drawColumn(col: List<Pair<Cell, List<String>>>, x0: Float) {
            var by = y + pad + 9f
            for ((cell, lines) in col) {
                text(x0, by, cell.label, 7.3f, bold = false, GRAY)
                lines.forEach { l ->
                    text(x0 + labelW, by, l, cell.size, cell.bold, cell.color, cell.mono)
                    by += lineH
                }
            }
        }
        drawColumn(leftCol, left + pad)
        drawColumn(rightCol, left + colW + pad)
        y += boxH + 14f
    }

    private fun tableHeader() {
        val h = 15f
        ensure(h + 14f)
        fillRect(left, y, contentW, h, HEAD_FILL)
        val by = y + h - 4.5f
        text(xParam, by, "Parameter", 8f, bold = true, INK)
        text(xValue, by, "Result", 8f, bold = true, INK)
        text(xUnit, by, "Unit", 8f, bold = true, INK)
        text(xRef, by, "Ref. range", 8f, bold = true, INK)
        text(xFlag, by, "Flag", 8f, bold = true, INK)
        y += h + 3f
    }

    private fun drawSection(section: ReportSection) {
        ensure(52f)
        text(left, y + 11f, section.title, 11f, bold = true, accent)
        y += 15f
        hline(left, right, y, accent, 0.9f)
        y += 5f
        tableHeader()
        section.rows.forEach { drawRow(it) }
        y += 10f
    }

    private fun drawRow(row: ReportRow) {
        val paramLines = wrapText(row.param, 9f, false, wParam - 10f)
        val refLines = wrapText(row.ref.ifBlank { "-" }, 8.5f, false, wRef - 6f)
        val lineH = 11f
        val lines = maxOf(paramLines.size, refLines.size, 1)
        val rowH = lines * lineH + 3.5f
        if (y + rowH > bottomY) { newPage(); tableHeader() }

        val emphasis = flagEmphasisRgb(row.flag)
        val vColor = emphasis?.let { opaque(it) } ?: INK
        val vBold = emphasis != null
        val base = y + 9f

        paramLines.forEachIndexed { i, l -> text(xParam, base + i * lineH, l, 9f, false, INK) }
        text(xValue, base, row.value, 9f, vBold, vColor)
        text(xUnit, base, row.unit, 8.5f, false, INK)
        refLines.forEachIndexed { i, l -> text(xRef, base + i * lineH, l, 8.5f, false, GRAY) }
        val fl = flagLabel(row.flag)
        if (fl.isNotEmpty()) text(xFlag, base, fl, 8f, vBold, if (vBold) vColor else GRAY)
        hline(left, right, y + rowH, LIGHT_RULE, 0.4f)
        y += rowH + 1.5f
    }

    private fun drawSignatures() {
        ensure(96f)
        y += 34f // physical signature gap
        val lineY = y
        val sigW = 150f
        hline(left, left + sigW, lineY, SIG_LINE, 0.8f)
        hline(right - sigW, right, lineY, SIG_LINE, 0.8f)
        text(left, lineY + 12f, "Verified by: ${doc.verifiedBy ?: "-"}", 9f, bold = false, INK)
        val approved = doc.approvedBy ?: "Authorised Signatory"
        textRight(right, lineY + 13f, approved, 10.5f, bold = true, INK)
        textRight(right, lineY + 25f, "Approved by (Pathologist)", 8f, bold = false, GRAY)
        y = lineY + 40f
        textCenter(y, "--- End of report ---", 7.5f, bold = false, GRAY)
        y += 12f
    }
}
