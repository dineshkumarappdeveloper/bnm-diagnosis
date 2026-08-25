package com.bnm.diagnosis

import com.bnm.diagnosis.billing.GstLine
import com.bnm.diagnosis.billing.GstTaxEngine
import kotlin.test.Test
import kotlin.test.assertEquals

class GstTaxEngineTest {

    @Test
    fun intraStateSingleLine18() {
        val r = GstTaxEngine.compute(
            lines = listOf(GstLine("Item A", "1234", 2.0, 100.0, 18.0)),
            supplierStateCode = "33", placeOfSupplyStateCode = "33",
        )
        assertEquals(200.0, r.subtotal)
        assertEquals(36.0, r.tax)
        assertEquals(236.0, r.total)
        assertEquals(1, r.taxBreakup.size)
        assertEquals(18.0, r.taxBreakup[0].cgst)
        assertEquals(18.0, r.taxBreakup[0].sgst)
        assertEquals(0.0, r.taxBreakup[0].igst)
    }

    @Test
    fun interStateSingleLine18() {
        val r = GstTaxEngine.compute(
            lines = listOf(GstLine("Item A", null, 2.0, 100.0, 18.0)),
            supplierStateCode = "33", placeOfSupplyStateCode = "27",
        )
        assertEquals(36.0, r.tax)
        assertEquals(0.0, r.taxBreakup[0].cgst)
        assertEquals(0.0, r.taxBreakup[0].sgst)
        assertEquals(36.0, r.taxBreakup[0].igst)
    }

    @Test
    fun multiRateGroupsBreakup() {
        val r = GstTaxEngine.compute(
            lines = listOf(
                GstLine("A", null, 2.0, 100.0, 18.0), // taxable 200, tax 36
                GstLine("B", null, 2.0, 100.0, 5.0),  // taxable 200, tax 10
            ),
            supplierStateCode = "33", placeOfSupplyStateCode = "33",
        )
        assertEquals(400.0, r.subtotal)
        assertEquals(46.0, r.tax)
        assertEquals(2, r.taxBreakup.size)
    }

    @Test
    fun oddTaxRoundsCgstSgstSymmetric() {
        // 99 * 18% = 17.82 → cgst 8.91, sgst 8.91, sum 17.82
        val r = GstTaxEngine.compute(
            lines = listOf(GstLine("Odd", null, 1.0, 99.0, 18.0)),
            supplierStateCode = "33", placeOfSupplyStateCode = "33",
        )
        assertEquals(17.82, r.tax)
        assertEquals(r.taxBreakup[0].cgst + r.taxBreakup[0].sgst, r.tax)
    }

    @Test
    fun invoiceDiscountIsAppliedBeforeTax() {
        // ₹1000 value − ₹100 discount = ₹900 taxable; 18% → ₹162 tax; total ₹1062.
        val r = GstTaxEngine.compute(
            lines = listOf(GstLine("A", "1234", 1.0, 1000.0, 18.0)),
            supplierStateCode = "33", placeOfSupplyStateCode = "33",
            discount = 100.0,
        )
        assertEquals(1000.0, r.subtotal)
        assertEquals(100.0, r.discount)
        assertEquals(162.0, r.tax)            // on 900, not 1000
        assertEquals(1062.0, r.total)
        assertEquals(900.0, r.taxBreakup[0].taxable)
    }

    @Test
    fun discountApportionedAcrossLinesSumsBack() {
        // ₹150 discount split across two lines; apportioned discounts must sum to 150.
        val r = GstTaxEngine.compute(
            lines = listOf(
                GstLine("A", null, 1.0, 700.0, 18.0),
                GstLine("B", null, 1.0, 300.0, 18.0),
            ),
            supplierStateCode = "33", placeOfSupplyStateCode = "33",
            discount = 150.0,
        )
        assertEquals(1000.0, r.subtotal)
        assertEquals(850.0, r.taxBreakup[0].taxable)   // 1000 − 150
        assertEquals(153.0, r.tax)                      // 18% of 850
    }

    @Test
    fun blankPlaceOfSupplyDefaultsIntra() {
        val r = GstTaxEngine.compute(
            lines = listOf(GstLine("A", null, 1.0, 100.0, 18.0)),
            supplierStateCode = "33", placeOfSupplyStateCode = null,
        )
        assertEquals(9.0, r.taxBreakup[0].cgst)
        assertEquals(9.0, r.taxBreakup[0].sgst)
        assertEquals(0.0, r.taxBreakup[0].igst)
    }
}
