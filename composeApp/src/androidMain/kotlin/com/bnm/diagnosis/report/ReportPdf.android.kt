package com.bnm.diagnosis.report

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
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

/** Side of the QR SYMBOL in mm; the 4-module quiet zone is reserved around it.
 *  Mirrors the desktop renderer so both outputs measure the same. */
private const val QR_MM = 20f
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

    /** Column the QR + its caption own, left of "Verified by". */
    private val qrBlockW = 118f

    /**
     * Decoded ONCE and shared by the measure pass and the draw pass — both need
     * the dimensions, and decoding twice would double the peak memory of a
     * report on a phone for no gain. Null (undecodable or absent) simply means
     * the sign-off prints as text, exactly as it did before signatures existed.
     */
    private val signatureBitmap: Bitmap? by lazy {
        doc.signature?.imagePng?.takeIf { it.isNotEmpty() }?.let { bytes ->
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
        }
    }

    /** The verifier's, same rules. */
    private val verifierBitmap: Bitmap? by lazy {
        doc.verifierSignature?.imagePng?.takeIf { it.isNotEmpty() }?.let { bytes ->
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
        }
    }

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
        doc.pageGroups.forEachIndexed { i, group ->
            // Every group after the first starts on a fresh sheet — mirror of
            // the desktop renderer.
            if (i > 0) newPage()
            group.heading?.let { drawGroupHeading(it) }
            group.sections.forEachIndexed { j, s ->
                drawSection(s, closesGroup = j == group.sections.lastIndex)
            }
            drawSignatures(group.flagLegendLine, group.sections.lastOrNull())
        }
        finishPage()
        pdf = null
    }

    // ── page plumbing ──

    /** Fresh sheet: letterhead/footer band, title, and the patient block on
     *  EVERY page (see the desktop renderer for why). */
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
        drawTitle()
        drawPatientBlock()
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

        // No ensure(): drawn by newPage() at the top of a fresh sheet; a page
        // break from inside it would recurse straight back here.
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

    /** Department banner (PER_DEPARTMENT only) — mirror of the desktop one. */
    private fun drawGroupHeading(title: String) {
        ensure(30f)
        val p = paintFor(10f, bold = true, accent).apply { letterSpacing = 0.16f }
        val t = title.uppercase()
        canvas?.drawText(t, (pageW - p.measureText(t)) / 2f, y + 10f, p)
        y += 18f
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

    private fun drawSection(section: ReportSection, closesGroup: Boolean) {
        ensure(52f)
        sectionTitle(section.title)
        tableHeader()
        section.rows.forEachIndexed { i, row ->
            // KEEP-WITH-NEXT — the group's final row travels with the sign-off;
            // see the desktop renderer for why.
            val keepWith = if (closesGroup && i == section.rows.lastIndex) 10f + signOff.need else 0f
            drawRow(row, section, keepWith)
        }
        y += 10f
    }

    private fun sectionTitle(title: String) {
        text(left, y + 11f, title, 11f, bold = true, accent)
        y += 15f
        hline(left, right, y, accent, 0.9f)
        y += 5f
    }

    /** A spilled table re-states its test on the fresh sheet — mirror of the
     *  desktop renderer; see its KDoc. */
    private fun continueSection(section: ReportSection) {
        newPage()
        sectionTitle(section.title + " (contd.)")
        tableHeader()
    }

    /** [keepWith]: extra room that must follow this row on the same sheet. */
    private fun drawRow(row: ReportRow, section: ReportSection, keepWith: Float = 0f) {
        val paramLines = wrapText(row.param, 9f, false, wParam - 10f)
        val refLines = wrapText(row.ref.ifBlank { "-" }, 8.5f, false, wRef - 6f)
        val lineH = 11f
        val lines = maxOf(paramLines.size, refLines.size, 1)
        val rowH = lines * lineH + 3.5f
        if (y + rowH + keepWith > bottomY) continueSection(section)

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

    /**
     * Fill the QR modules as rectangles — resolution-independent and codec-free,
     * the same approach the desktop renderer takes. [x]/[yTop] are the top-left
     * of the SYMBOL; the quiet zone is the caller's business.
     *
     * Adjacent dark modules in a row collapse into one rectangle: identical ink,
     * a fraction of the draw calls on a 49x49 code.
     */
    private fun drawQrMatrix(matrix: QrMatrix, x: Float, yTop: Float, side: Float) {
        val c = canvas ?: return   // measure pass draws nothing
        val n = matrix.size
        if (n <= 0) return
        val cell = side / n
        val p = Paint().apply { color = 0xFF000000.toInt(); style = Paint.Style.FILL }
        for (my in 0 until n) {
            var mx = 0
            while (mx < n) {
                if (!matrix[mx, my]) { mx++; continue }
                var run = 1
                while (mx + run < n && matrix[mx + run, my]) run++
                c.drawRect(x + mx * cell, yTop + my * cell,
                    x + (mx + run) * cell, yTop + (my + 1) * cell, p)
                mx += run
            }
        }
    }

    /** Sign-off + report-download QR — mirror of the desktop renderer, with y
     *  growing DOWNWARD. See the desktop KDoc for why the layout is shaped this
     *  way; an unsigned, QR-less report collapses to the original measurements. */
    /** Sign-off geometry independent of where it lands — mirror of the desktop
     *  SignOffMetrics; [need] is what keep-with-next reserves. */
    private inner class SignOffMetrics {
        val sigBmp: Bitmap? = signatureBitmap?.takeIf { it.width > 0 && it.height > 0 }
        val verifierBmp: Bitmap? = verifierBitmap?.takeIf { it.width > 0 && it.height > 0 }
        val gap = if (sigBmp != null || verifierBmp != null) 48f else 34f

        val qr = doc.qr?.takeIf { it.matrix.size > 0 }
        val qrSide = QR_MM * MM
        val qrCaption = qr?.let { wrapText(it.caption, 7f, true, qrBlockW) }.orEmpty()
        val qrNote = qr?.let { wrapText(it.note, 6.2f, false, qrBlockW) }.orEmpty()
        val quiet = if (qr == null) 0f else qrSide / qr.matrix.size * 4f
        val qrH = if (qr == null) 0f else qrSide + quiet * 2f + 4f +
            qrCaption.size * 9f + qrNote.size * 8f

        // Outer columns stop short of the QR — mirror of the desktop metrics.
        val colW = if (qr != null) pageW / 2f - qrSide / 2f - quiet - 8f - left else contentW / 2f - 8f
        val verifierName = wrapText(doc.verifiedBy ?: "-", 10.5f, true, colW)
        val approverName = wrapText(doc.approvedBy ?: "Authorised Signatory", 10.5f, true, colW)
        val credentialLines = listOfNotNull(
            doc.signature?.qualifications?.takeIf { it.isNotBlank() },
            doc.signature?.registrationNo?.takeIf { it.isNotBlank() }?.let { "Reg. No. $it" },
            doc.approvedOn?.takeIf { it.isNotBlank() }?.let { "Approved on $it" },
        ).flatMap { wrapText(it, 8f, false, colW) }
        val verifierLines = listOfNotNull(
            doc.verifierSignature?.qualifications?.takeIf { it.isNotBlank() },
            doc.verifierSignature?.registrationNo?.takeIf { it.isNotBlank() }?.let { "Reg. No. $it" },
        ).flatMap { wrapText(it, 8f, false, colW) }
        val belowRule = maxOf(
            13f + (verifierName.size - 1) * 12f + 12f + verifierLines.size * 10f,
            13f + (approverName.size - 1) * 12f + 12f + credentialLines.size * 10f,
        )
        val signOffH = gap + belowRule
        val need = maxOf(signOffH, qrH) + 40f
    }

    private val signOff by lazy { SignOffMetrics() }

    private fun drawSignatures(flagLegendLine: String, lastSection: ReportSection?) {
        val m = signOff
        val sigBmp = m.sigBmp
        val verifierBmp = m.verifierBmp
        val gap = m.gap
        val credentialLines = m.credentialLines
        val verifierLines = m.verifierLines
        val qr = m.qr
        val qrSide = m.qrSide
        val qrCaption = m.qrCaption
        val qrNote = m.qrNote
        val quiet = m.quiet

        // Fallback for a section with no rows (keep-with-next covers the rest):
        // the fresh sheet must still name the test it signs off.
        if (y + m.need > bottomY) {
            newPage()
            lastSection?.let { sectionTitle(it.title + " (contd.)") }
        }

        val blockTop = y
        val lineY = blockTop + gap
        val sigW = 150f

        // Verifier left, approver right, QR centred — mirror of the desktop
        // three-column sign-off. Each image sits ON its rule.
        fun drawInk(bmp: Bitmap, rightAligned: Boolean) {
            val maxH = gap - 8f
            val scale = minOf(maxH / bmp.height, sigW / bmp.width)
            val w = bmp.width * scale
            val h = bmp.height * scale
            val x0 = if (rightAligned) right - w else left
            canvas?.drawBitmap(bmp, null, RectF(x0, lineY - 2f - h, x0 + w, lineY - 2f), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        if (verifierBmp != null) drawInk(verifierBmp, rightAligned = false)
        if (sigBmp != null) drawInk(sigBmp, rightAligned = true)

        hline(left, left + sigW, lineY, SIG_LINE, 0.8f)
        hline(right - sigW, right, lineY, SIG_LINE, 0.8f)

        var vy = lineY + 1f
        for (line in m.verifierName) { vy += 12f; text(left, vy, line, 10.5f, bold = true, INK) }
        vy += 12f
        text(left, vy, "Verified by", 8f, bold = false, GRAY)
        for (line in verifierLines) { vy += 10f; text(left, vy, line, 8f, bold = false, GRAY) }

        var cy = lineY + 1f
        for (line in m.approverName) { cy += 12f; textRight(right, cy, line, 10.5f, bold = true, INK) }
        cy += 12f
        textRight(right, cy, "Approved by (Pathologist)", 8f, bold = false, GRAY)
        for (line in credentialLines) { cy += 10f; textRight(right, cy, line, 8f, bold = false, GRAY) }

        var qrBottom = blockTop
        if (qr != null) {
            drawQrMatrix(qr.matrix, pageW / 2f - qrSide / 2f, blockTop + quiet, qrSide)
            var ty = blockTop + quiet * 2f + qrSide + 4f
            qrCaption.forEach { textCenter(ty, it, 7f, bold = true, INK); ty += 9f }
            qrNote.forEach { textCenter(ty, it, 6.2f, bold = false, GRAY); ty += 8f }
            qrBottom = ty
        }

        y = maxOf(lineY + m.belowRule + 15f, qrBottom + 6f)
        // Flag key — mirror of the desktop renderer (note: y grows DOWNWARD here).
        if (flagLegendLine.isNotEmpty()) {
            text(left, y, flagLegendLine, 7.5f, bold = false, GRAY)
            y += 12f
        }
        textCenter(y, "--- End of report ---", 7.5f, bold = false, GRAY)
        y += 12f
    }
}
