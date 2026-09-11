package com.bnm.lab.print

import com.bnm.lab.api.models.Invoice
import com.bnm.lab.api.models.InvoiceSettings
import com.bnm.lab.util.formatDecimal2

/**
 * A4 sheet billing — the NON-thermal design. Where the thermal receipt is a
 * narrow monospace docket, this lays out a proper full-page GST tax invoice:
 * letterhead, bill-to / meta columns, a ruled items table, per-rate tax
 * breakup, bank & UPI block and a signatory block.
 *
 * The layout is computed HERE, once, in resolution-independent A4 points
 * (595 × 842) as a list of primitive draw ops; each platform's [printA4] just
 * replays them onto its own canvas (AWT / android PdfDocument / UIKit). One
 * design, three renderers of ~a screen each — and the same file is portable
 * across the app family (Billing / Diagnosis / Admin) like the rest of the
 * print stack.
 *
 * Text wrapping uses an average-glyph-width estimate (Helvetica ≈ 0.52 em)
 * because commonMain has no font metrics; alignment is exact — RIGHT/CENTER
 * ops carry the anchor and the replayer measures the real string.
 */

const val A4_W = 595f
const val A4_H = 842f

enum class A4Align { LEFT, CENTER, RIGHT }

sealed interface A4Op {
    /** [y] is the BASELINE, from the top of the page. */
    data class Text(
        val x: Float, val y: Float, val text: String,
        val size: Float = 9f, val bold: Boolean = false,
        val align: A4Align = A4Align.LEFT, val gray: Float = 0f,
    ) : A4Op
    data class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val width: Float = 0.7f) : A4Op
    /** Filled rectangle; [gray] 0=black … 1=white. */
    data class Rect(val x: Float, val y: Float, val w: Float, val h: Float, val gray: Float = 0.93f) : A4Op
}

data class A4Doc(val jobName: String, val pages: List<List<A4Op>>)

private const val M = 40f            // page margin
private const val RIGHT = A4_W - M
private const val BOTTOM_LIMIT = A4_H - 56f  // keep clear of the page footer

// Items table column edges (x): # | Description | HSN | Qty | Rate | GST% | Amount
private const val C_NO = M
private const val C_DESC = M + 24f
private const val C_HSN = 306f
private const val C_QTY = 366f
private const val C_RATE = 432f
private const val C_GST = 470f
private const val C_AMT = RIGHT

private fun estChars(width: Float, size: Float): Int = (width / (size * 0.52f)).toInt().coerceAtLeast(8)

private fun wrapText(s: String, maxChars: Int): List<String> {
    val out = mutableListOf<String>()
    var line = StringBuilder()
    for (word in s.trim().split(Regex("\\s+"))) {
        when {
            line.isEmpty() && word.length > maxChars -> word.chunked(maxChars).forEach { out.add(it) }
            line.isEmpty() -> line.append(word)
            line.length + 1 + word.length <= maxChars -> line.append(' ').append(word)
            else -> { out.add(line.toString()); line = StringBuilder(word.take(maxChars)) }
        }
    }
    if (line.isNotEmpty()) out.add(line.toString())
    return out.ifEmpty { listOf("") }
}

fun layoutInvoiceA4(settings: InvoiceSettings?, businessName: String, inv: Invoice): A4Doc {
    val pages = mutableListOf<MutableList<A4Op>>()
    var ops = mutableListOf<A4Op>()
    pages.add(ops)
    var y = 0f

    val supplierName = settings?.supplierDisplayName ?: businessName
    val gstin = settings?.taxId?.takeIf { it.isNotBlank() }
    val title = if (gstin != null) "TAX INVOICE" else "INVOICE"
    fun money(v: Double) = "Rs. " + formatDecimal2(v)
    fun pct(v: Double) = if (v == v.toLong().toDouble()) v.toLong().toString() else formatDecimal2(v)

    fun tableHeader() {
        ops.add(A4Op.Rect(M, y, RIGHT - M, 16f, gray = 0.90f))
        val by = y + 11.5f
        ops.add(A4Op.Text(C_NO + 2f, by, "#", 8.5f, bold = true))
        ops.add(A4Op.Text(C_DESC, by, "Description", 8.5f, bold = true))
        ops.add(A4Op.Text(C_HSN, by, "HSN", 8.5f, bold = true))
        ops.add(A4Op.Text(C_QTY, by, "Qty", 8.5f, bold = true, align = A4Align.RIGHT))
        ops.add(A4Op.Text(C_RATE, by, "Rate", 8.5f, bold = true, align = A4Align.RIGHT))
        ops.add(A4Op.Text(C_GST, by, "GST%", 8.5f, bold = true, align = A4Align.RIGHT))
        ops.add(A4Op.Text(C_AMT, by, "Amount", 8.5f, bold = true, align = A4Align.RIGHT))
        y += 16f
        ops.add(A4Op.Line(M, y, RIGHT, y))
    }

    fun newPage(continued: Boolean = true) {
        ops = mutableListOf()
        pages.add(ops)
        y = M
        if (continued) {
            ops.add(A4Op.Text(M, y, supplierName, 10f, bold = true))
            ops.add(A4Op.Text(RIGHT, y, "$title ${inv.displayNumber} — continued", 9f, align = A4Align.RIGHT, gray = 0.35f))
            y += 8f
            ops.add(A4Op.Line(M, y, RIGHT, y))
            y += 10f
            tableHeader()
        }
    }

    fun need(h: Float) { if (y + h > BOTTOM_LIMIT) newPage() }

    // ── Letterhead ──────────────────────────────────────────────────────────
    y = M + 14f
    ops.add(A4Op.Text(A4_W / 2f, y, supplierName, 17f, bold = true, align = A4Align.CENTER))
    y += 14f
    settings?.supplierAddress?.split("\n")?.map { it.trim() }?.filter { it.isNotEmpty() }?.forEach { line ->
        wrapText(line, estChars(A4_W - 2 * M, 9f)).forEach {
            ops.add(A4Op.Text(A4_W / 2f, y, it, 9f, align = A4Align.CENTER, gray = 0.25f)); y += 11f
        }
    }
    val idBits = listOfNotNull(
        gstin?.let { "GSTIN: $it" },
        settings?.supplierPhone?.takeIf { it.isNotBlank() }?.let { "Phone: $it" },
    ).joinToString("   ·   ")
    if (idBits.isNotEmpty()) { ops.add(A4Op.Text(A4_W / 2f, y, idBits, 9f, align = A4Align.CENTER, gray = 0.25f)); y += 12f }

    y += 2f
    ops.add(A4Op.Line(M, y, RIGHT, y, width = 1.1f))
    y += 15f
    ops.add(A4Op.Text(A4_W / 2f, y, title, 12.5f, bold = true, align = A4Align.CENTER))
    y += 7f
    ops.add(A4Op.Line(M, y, RIGHT, y, width = 1.1f))
    y += 16f

    // ── Bill-to (left) · invoice meta (right) ───────────────────────────────
    val metaTop = y
    ops.add(A4Op.Text(M, y, "Bill to", 8.5f, bold = true, gray = 0.35f)); y += 12f
    ops.add(A4Op.Text(M, y, inv.resolvedCustomer, 10.5f, bold = true)); y += 12f
    inv.customerPhone?.takeIf { it.isNotBlank() }?.let { ops.add(A4Op.Text(M, y, "Phone: $it", 9f)); y += 11f }
    inv.customerGstin?.takeIf { it.isNotBlank() }?.let { ops.add(A4Op.Text(M, y, "GSTIN: $it", 9f)); y += 11f }
    var leftEnd = y

    y = metaTop
    fun meta(label: String, value: String) {
        ops.add(A4Op.Text(RIGHT - 150f, y, label, 9f, gray = 0.35f))
        ops.add(A4Op.Text(RIGHT, y, value, 9f, bold = true, align = A4Align.RIGHT))
        y += 12f
    }
    meta("Invoice no.", inv.displayNumber)
    meta("Date", inv.issuedAt ?: "")
    placeOfSupplyLabel(inv.placeOfSupply ?: settings?.taxId)?.let { meta("Place of supply", it) }
    meta("Reverse charge", "No")
    inv.dueAt?.takeIf { inv.status != "paid" }?.let { meta("Due", it) }
    y = maxOf(leftEnd, y) + 10f

    // ── Items table ─────────────────────────────────────────────────────────
    tableHeader()
    val descChars = estChars(C_HSN - C_DESC - 6f, 9f)
    inv.lineItems.forEachIndexed { i, li ->
        val descLines = wrapText(li.description.ifBlank { "Item" }, descChars)
        val rowH = 6f + descLines.size * 11f
        need(rowH + 4f)
        var by = y + 12f
        ops.add(A4Op.Text(C_NO + 2f, by, "${i + 1}", 9f, gray = 0.35f))
        descLines.forEach { dl -> ops.add(A4Op.Text(C_DESC, by, dl, 9f)); by += 11f }
        val fy = y + 12f
        ops.add(A4Op.Text(C_HSN, fy, li.hsn?.takeIf { it.isNotBlank() } ?: "—", 9f, gray = 0.35f))
        ops.add(A4Op.Text(C_QTY, fy, formatDecimal2(li.quantity), 9f, align = A4Align.RIGHT))
        ops.add(A4Op.Text(C_RATE, fy, formatDecimal2(li.rate), 9f, align = A4Align.RIGHT))
        ops.add(A4Op.Text(C_GST, fy, pct(li.gstRate), 9f, align = A4Align.RIGHT))
        ops.add(A4Op.Text(C_AMT, fy, formatDecimal2(li.amount), 9f, align = A4Align.RIGHT))
        y += rowH
        ops.add(A4Op.Line(M, y, RIGHT, y, width = 0.4f))
    }

    // ── Tax summary (right-aligned block) ───────────────────────────────────
    val taxRows = inv.taxBreakup.sumOf { if (it.igst > 0) 1 else 2.toInt() }
    need(70f + taxRows * 12f)
    y += 14f
    fun sum(label: String, value: String, bold: Boolean = false, size: Float = 9.5f) {
        ops.add(A4Op.Text(RIGHT - 170f, y, label, size, bold = bold))
        ops.add(A4Op.Text(RIGHT, y, value, size, bold = bold, align = A4Align.RIGHT))
        y += 13f
    }
    sum("Taxable value", money(inv.subtotal - inv.discount))
    if (inv.discount > 0) sum("Discount", "-" + money(inv.discount))
    inv.taxBreakup.forEach { b ->
        if (b.igst > 0) sum("IGST @ ${pct(b.rate)}%", money(b.igst))
        else {
            sum("CGST @ ${pct(b.rate / 2)}%", money(b.cgst))
            sum("SGST @ ${pct(b.rate / 2)}%", money(b.sgst))
        }
    }
    ops.add(A4Op.Line(RIGHT - 170f, y - 9f, RIGHT, y - 9f))
    y += 3f
    sum("TOTAL", money(inv.total), bold = true, size = 12f)
    if (inv.paymentMethod != null) {
        sum("Paid via", (inv.paymentMethod ?: "").replace('_', ' ').uppercase(), size = 9f)
        inv.amountTendered?.takeIf { it > 0 }?.let { sum("Cash received", money(it), size = 9f) }
        inv.changeDue?.takeIf { it > 0.005 }?.let { sum("Change", money(it), size = 9f) }
    }

    need(24f)
    y += 4f
    wrapText(amountInWords(inv.total), estChars(RIGHT - M, 9f)).forEach {
        ops.add(A4Op.Text(M, y, it, 9f, gray = 0.25f)); y += 11f
    }

    // ── Bank / UPI (left) · signatory (right) ───────────────────────────────
    val bankLines = buildList {
        settings?.bankName?.takeIf { it.isNotBlank() }?.let { add("Bank: $it" + (settings.branch?.takeIf { b -> b.isNotBlank() }?.let { b -> ", $b" } ?: "")) }
        settings?.accountName?.takeIf { it.isNotBlank() }?.let { add("Account name: $it") }
        settings?.accountNumber?.takeIf { it.isNotBlank() }?.let { add("A/c no: $it" + (settings.ifsc?.takeIf { i -> i.isNotBlank() }?.let { i -> "   IFSC: $i" } ?: "")) }
        settings?.upiVpa?.takeIf { it.isNotBlank() }?.let { add("UPI: $it") }
    }
    need(66f + bankLines.size * 11f)
    y += 12f
    val blockTop = y
    if (bankLines.isNotEmpty()) {
        ops.add(A4Op.Text(M, y, "Payment details", 8.5f, bold = true, gray = 0.35f)); y += 12f
        bankLines.forEach { ops.add(A4Op.Text(M, y, it, 9f)); y += 11f }
    }
    var sy = blockTop
    ops.add(A4Op.Text(RIGHT, sy, "For $supplierName", 9.5f, bold = true, align = A4Align.RIGHT)); sy += 40f
    ops.add(A4Op.Text(RIGHT, sy, "Authorised Signatory", 9f, align = A4Align.RIGHT, gray = 0.35f))
    y = maxOf(y, sy) + 14f

    // ── Footer note / terms ─────────────────────────────────────────────────
    settings?.footerNote?.takeIf { it.isNotBlank() }?.let { note ->
        val lines = wrapText(note, estChars(RIGHT - M, 8.5f))
        need(lines.size * 10f + 6f)
        lines.forEach { ops.add(A4Op.Text(M, y, it, 8.5f, gray = 0.3f)); y += 10f }
        y += 4f
    }
    settings?.terms?.takeIf { it.isNotBlank() }?.let { terms ->
        val lines = terms.split("\n").flatMap { wrapText(it, estChars(RIGHT - M, 8f)) }
        need(lines.size * 9.5f + 12f)
        ops.add(A4Op.Text(M, y, "Terms & conditions", 8f, bold = true, gray = 0.35f)); y += 10f
        lines.forEach { ops.add(A4Op.Text(M, y, it, 8f, gray = 0.35f)); y += 9.5f }
    }

    // ── Page footers ("Page X of Y" + rule), stamped once the count is known ─
    val total = pages.size
    pages.forEachIndexed { i, p ->
        p.add(A4Op.Line(M, A4_H - 40f, RIGHT, A4_H - 40f, width = 0.4f))
        p.add(A4Op.Text(A4_W / 2f, A4_H - 28f, "Page ${i + 1} of $total", 8f, align = A4Align.CENTER, gray = 0.45f))
    }
    return A4Doc(jobName = inv.displayNumber, pages = pages)
}
