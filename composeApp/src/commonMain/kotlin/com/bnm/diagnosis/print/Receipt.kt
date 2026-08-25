package com.bnm.diagnosis.print

import com.bnm.diagnosis.api.models.Invoice
import com.bnm.diagnosis.api.models.InvoiceSettings
import com.bnm.diagnosis.util.formatDecimal2

/**
 * Render a GST-compliant **Tax Invoice** as monospace text (58/80mm rolls + A4).
 * Carries the Rule-46 fields: supplier name/GSTIN/address, "Tax Invoice" title,
 * invoice no + date, place of supply, reverse-charge declaration, recipient
 * details, per-line description/HSN/qty/rate/taxable + rate, CGST/SGST or IGST
 * breakup, total, amount in words, and an authorised-signatory line.
 */
fun renderReceiptText(settings: InvoiceSettings?, businessName: String, inv: Invoice, widthChars: Int = 48): String {
    val w = widthChars.coerceIn(30, 80)
    val sb = StringBuilder()
    fun ln(s: String = "") { sb.append(s).append('\n') }
    fun rule(ch: Char = '-') = ln(ch.toString().repeat(w))
    fun center(s: String) = ln(" ".repeat(((w - s.length) / 2).coerceAtLeast(0)) + s)
    fun lr(l: String, r: String) = ln(l + " ".repeat((w - l.length - r.length).coerceAtLeast(1)) + r)
    fun wrap(s: String) { var t = s.trim(); if (t.isEmpty()) return; while (t.length > w) { ln(t.take(w)); t = t.drop(w).trim() }; ln(t) }
    fun money(v: Double) = "₹" + formatDecimal2(v)
    fun pct(v: Double) = if (v == v.toLong().toDouble()) v.toLong().toString() else formatDecimal2(v)

    val supplierName = settings?.supplierDisplayName ?: businessName
    val gstin = settings?.taxId?.takeIf { it.isNotBlank() }
    // ── Supplier identity ──
    center(supplierName)
    settings?.supplierAddress?.split("\n")?.forEach { wrap(it) }
    gstin?.let { center("GSTIN: $it") }
    settings?.supplierPhone?.takeIf { it.isNotBlank() }?.let { center("Phone: $it") }
    rule('=')
    // A registered supplier (with GSTIN) issues a "Tax Invoice"; without a GSTIN
    // it must not be labelled one (it's a plain bill / bill of supply).
    center(if (gstin != null) "TAX INVOICE" else "INVOICE")
    rule('=')
    ln("Invoice No : ${inv.displayNumber}")
    ln("Date       : ${inv.issuedAt ?: ""}")
    placeOfSupplyLabel(inv.placeOfSupply ?: settings?.taxId)?.let { ln("Place of supply: $it") }
    ln("Reverse charge : No")
    rule()
    // ── Recipient ──
    ln("Bill to: ${inv.resolvedCustomer}")
    inv.customerPhone?.takeIf { it.isNotBlank() }?.let { ln("Phone  : $it") }
    inv.customerGstin?.takeIf { it.isNotBlank() }?.let { ln("GSTIN  : $it") }
    rule()
    // ── Line items: desc + HSN, qty x rate, taxable value, rate% ──
    inv.lineItems.forEach { li ->
        wrap(li.description)
        val hsn = li.hsn?.takeIf { it.isNotBlank() }?.let { "HSN $it  " } ?: ""
        // Quantity with a UQC (Rule 46(h)); default "Nos" until per-product units exist.
        lr("  $hsn${formatDecimal2(li.quantity)} Nos x ${formatDecimal2(li.rate)}", money(li.amount) + "  ${pct(li.gstRate)}%")
    }
    rule()
    // ── Tax summary ──
    lr("Taxable value", money(inv.subtotal - inv.discount))
    if (inv.discount > 0) lr("Discount", "-" + money(inv.discount))
    inv.taxBreakup.forEach { b ->
        if (b.igst > 0) lr("IGST @ ${pct(b.rate)}%", money(b.igst))
        else {
            lr("CGST @ ${pct(b.rate / 2)}%", money(b.cgst))
            lr("SGST @ ${pct(b.rate / 2)}%", money(b.sgst))
        }
    }
    rule('=')
    lr("TOTAL", money(inv.total))
    rule('=')
    // ── Payment (recorded by the Collect-payment sheet at save time) ──
    if (inv.paymentMethod != null || inv.amountTendered != null) {
        inv.paymentMethod?.let { lr("Paid via", it.replace('_', ' ').uppercase()) }
        inv.amountTendered?.takeIf { it > 0 }?.let { lr("Cash received", money(it)) }
        inv.changeDue?.takeIf { it > 0.005 }?.let { lr("Change", money(it)) }
        rule()
    }
    wrap(amountInWords(inv.total))
    ln()
    settings?.footerNote?.takeIf { it.isNotBlank() }?.let { wrap(it); ln() }
    center("For $supplierName")
    ln(); ln()
    center("Authorised Signatory")
    rule()
    center("Thank you for your business!")
    return sb.toString()
}

/**
 * Print a rendered text receipt on the platform's printer.
 *  • Desktop → the OS print dialog (any installed/USB/network printer).
 *  • Android → opens the print/share chooser (direct ESC-POS thermal is a follow-up).
 *  • iOS → staged.
 * Returns a short human-readable status to show the operator.
 */
expect fun printReceipt(title: String, body: String): String
