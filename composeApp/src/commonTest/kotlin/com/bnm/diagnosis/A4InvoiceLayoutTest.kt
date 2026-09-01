package com.bnm.diagnosis

import com.bnm.diagnosis.api.models.Invoice
import com.bnm.diagnosis.api.models.InvoiceLineItem
import com.bnm.diagnosis.api.models.TaxBreakupRow
import com.bnm.diagnosis.print.A4Op
import com.bnm.diagnosis.print.layoutInvoiceA4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The A4 sheet design: one page for a small bill, clean pagination for a long
 *  one, and the load-bearing content (title, total, signatory) always present. */
class A4InvoiceLayoutTest {

    private fun inv(lines: Int) = Invoice(
        id = "i1", invoiceNumber = "INV-C1-0042",
        customerName = "Asha Stores", customerPhone = "98400 00000",
        subtotal = 100.0 * lines, tax = 5.0 * lines, total = 105.0 * lines,
        lineItems = (1..lines).map {
            InvoiceLineItem(description = "Item $it with a reasonably long descriptive name", quantity = 1.0, rate = 100.0, amount = 100.0, gstRate = 5.0, cgst = 2.5, sgst = 2.5)
        },
        taxBreakup = listOf(TaxBreakupRow(rate = 5.0, taxable = 100.0 * lines, cgst = 2.5 * lines, sgst = 2.5 * lines)),
    )

    private fun texts(doc: com.bnm.diagnosis.print.A4Doc) =
        doc.pages.flatten().filterIsInstance<A4Op.Text>().map { it.text }

    @Test
    fun smallBill_isOnePage_withTheLoadBearingBlocks() {
        val doc = layoutInvoiceA4(null, "Demo Store", inv(3))
        assertEquals(1, doc.pages.size)
        val t = texts(doc)
        assertTrue(t.contains("INVOICE"), "unregistered supplier (no GSTIN) must NOT print TAX INVOICE")
        assertTrue(t.contains("Asha Stores"))
        assertTrue(t.any { it.startsWith("CGST") } && t.any { it.startsWith("SGST") })
        assertTrue(t.contains("TOTAL"))
        assertTrue(t.contains("Authorised Signatory"))
        assertTrue(t.contains("Page 1 of 1"))
    }

    @Test
    fun longBill_paginates_andEveryPageCarriesTheFooter() {
        val doc = layoutInvoiceA4(null, "Demo Store", inv(60))
        assertTrue(doc.pages.size > 1, "60 rows cannot fit one A4 page")
        doc.pages.forEachIndexed { i, p ->
            val t = p.filterIsInstance<A4Op.Text>().map { it.text }
            assertTrue(t.contains("Page ${i + 1} of ${doc.pages.size}"))
        }
        // Continuation pages re-print the table header.
        val page2 = doc.pages[1].filterIsInstance<A4Op.Text>().map { it.text }
        assertTrue(page2.contains("Description"))
        // Every item row landed somewhere.
        val all = texts(doc)
        assertTrue((1..60).all { n -> all.any { it.startsWith("Item $n ") } })
    }
}
