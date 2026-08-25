package com.bnm.diagnosis.billing

import com.bnm.diagnosis.api.models.InvoiceLineItem
import com.bnm.diagnosis.api.models.TaxBreakupRow
import kotlin.math.roundToLong

/** Raw line input for the GST engine (before tax is computed). */
data class GstLine(
    val description: String,
    val hsn: String?,
    val quantity: Double,
    val rate: Double,
    val gstRate: Double,
    val productId: String? = null,
)

/** Result of a GST computation: priced line items + grouped breakup + totals. */
data class GstResult(
    val lineItems: List<InvoiceLineItem>,
    val taxBreakup: List<TaxBreakupRow>,
    val subtotal: Double,
    val discount: Double,
    val tax: Double,
    val total: Double,
)

/**
 * Deterministic, offline GST tax engine. Pure function: identical inputs →
 * identical outputs on every platform. Computes per-line CGST/SGST (intra-state)
 * vs IGST (inter-state), in integer paise, rounding PER LINE then summing (to
 * match GSTR-1 line-item expectations). A blank/equal place-of-supply vs supplier
 * state is treated as intra-state (the common counter case).
 */
object GstTaxEngine {

    private fun paise(x: Double): Long = (x * 100.0).roundToLong()
    private fun rupees(p: Long): Double = p / 100.0

    fun compute(
        lines: List<GstLine>,
        supplierStateCode: String?,
        placeOfSupplyStateCode: String?,
        discount: Double = 0.0,
    ): GstResult {
        val supplier = supplierStateCode?.trim()?.take(2)
        val pos = placeOfSupplyStateCode?.trim()?.take(2)
        // Intra-state when both are known and equal, OR when POS is unknown
        // (default to local supply at the counter).
        val intra = pos.isNullOrBlank() || supplier.isNullOrBlank() || supplier == pos

        // Gross per line + subtotal (in paise).
        val grossP = LongArray(lines.size) { paise(lines[it].quantity * lines[it].rate) }
        val subtotalP = grossP.sum()

        // GST is charged on the TAXABLE value = value of supply LESS any discount
        // recorded on the invoice (CGST Rule 46 / s.15). Apportion an invoice-level
        // discount across lines in proportion to value; the last line absorbs the
        // rounding remainder so the apportioned discounts sum back to the total.
        val discountP = paise(discount).coerceAtLeast(0)
        val lineDiscP = LongArray(lines.size)
        if (discountP > 0 && subtotalP > 0) {
            var allocated = 0L
            for (i in lines.indices) {
                lineDiscP[i] = if (i == lines.lastIndex) discountP - allocated
                else (discountP * grossP[i] / subtotalP)
                allocated += lineDiscP[i]
            }
        }

        val outLines = ArrayList<InvoiceLineItem>(lines.size)
        // rate -> [taxablePaise, cgstPaise, sgstPaise, igstPaise]
        val byRate = LinkedHashMap<Double, LongArray>()
        var taxP = 0L

        for (i in lines.indices) {
            val li = lines[i]
            val taxableP = (grossP[i] - lineDiscP[i]).coerceAtLeast(0)
            val totalLineTaxP = (taxableP * li.gstRate / 100.0).roundToLong()
            val cgstP: Long; val sgstP: Long; val igstP: Long
            if (intra) {
                cgstP = (totalLineTaxP / 2.0).roundToLong()
                sgstP = totalLineTaxP - cgstP
                igstP = 0
            } else {
                igstP = totalLineTaxP
                cgstP = 0; sgstP = 0
            }
            taxP += cgstP + sgstP + igstP
            outLines.add(
                InvoiceLineItem(
                    description = li.description, hsn = li.hsn,
                    quantity = li.quantity, rate = li.rate, amount = rupees(grossP[i]),
                    gstRate = li.gstRate,
                    cgst = rupees(cgstP), sgst = rupees(sgstP), igst = rupees(igstP),
                    productId = li.productId,
                )
            )
            val g = byRate.getOrPut(li.gstRate) { LongArray(4) }
            g[0] += taxableP; g[1] += cgstP; g[2] += sgstP; g[3] += igstP
        }

        val breakup = byRate.map { (rate, g) ->
            TaxBreakupRow(rate = rate, taxable = rupees(g[0]), cgst = rupees(g[1]), sgst = rupees(g[2]), igst = rupees(g[3]))
        }
        val subtotal = rupees(subtotalP)
        val tax = rupees(taxP)
        // total = taxable value (subtotal − discount) + tax.
        val total = rupees(subtotalP - discountP + taxP)
        return GstResult(outLines, breakup, subtotal, rupees(discountP), tax, total)
    }
}
