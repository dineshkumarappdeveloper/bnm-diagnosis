package com.bnm.diagnosis

import com.bnm.diagnosis.api.models.Invoice
import com.bnm.diagnosis.api.models.InvoiceSettings
import com.bnm.diagnosis.billing.GstLine
import com.bnm.diagnosis.billing.GstTaxEngine
import com.bnm.diagnosis.print.renderReceiptText
import kotlin.test.Test
import kotlin.test.assertTrue

class ReceiptRenderTest {
    @Test
    fun taxInvoiceCarriesRule46Fields() {
        val settings = InvoiceSettings(
            taxId = "33AABCD1234E1Z5", legalName = "Demo Store", supplierName = "Demo Store",
            supplierAddress = "No.12, Demo Street, Anna Nagar, Chennai, Tamil Nadu, 600040",
            supplierPhone = "919000000000", footerNote = "Goods once sold are not returnable.",
        )
        val gst = GstTaxEngine.compute(
            listOf(GstLine("Cold Brew Coffee 250ml", "2202", 1.0, 150.0, 18.0)),
            supplierStateCode = "33", placeOfSupplyStateCode = "33",
        )
        val inv = Invoice(
            id = "x", invoiceNumber = "INV-C1-0020", customerName = "Guest", customerPhone = "9876543210",
            issuedAt = "2026-06-24", subtotal = gst.subtotal, discount = gst.discount,
            tax = gst.tax, total = gst.total, lineItems = gst.lineItems, taxBreakup = gst.taxBreakup,
            seriesCode = "C1",
        )
        val text = renderReceiptText(settings, "Demo Store", inv, 48)
        println("\n===== RENDERED TAX INVOICE =====\n$text===== END =====\n")
        for (field in listOf(
            "TAX INVOICE", "GSTIN: 33AABCD1234E1Z5", "No.12, Demo Street",
            "Invoice No : INV-C1-0020", "Place of supply: 33 - Tamil Nadu", "Reverse charge : No",
            "HSN 2202", "1.00 Nos x 150.00", "CGST @ 9%", "SGST @ 9%", "Taxable value", "TOTAL", "Rupees", "Authorised Signatory",
        )) assertTrue(text.contains(field), "Tax invoice missing required field: '$field'")
    }
}
