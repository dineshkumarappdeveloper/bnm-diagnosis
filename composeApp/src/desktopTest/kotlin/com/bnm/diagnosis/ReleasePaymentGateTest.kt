package com.bnm.diagnosis

import com.bnm.diagnosis.api.models.Invoice
import com.bnm.diagnosis.chat.InvoiceBalance
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The release gate is only as good as [InvoiceBalance.isSettled], so the edges
 * that decide whether a patient walks out with their report are pinned here.
 */
class ReleasePaymentGateTest {

    private fun bill(total: Double, paid: Double, queued: Double = 0.0, status: String = "pending") =
        InvoiceBalance(
            invoice = Invoice(id = "i1", total = total, status = status),
            paidAmount = paid,
            queuedAmount = queued,
        )

    @Test
    fun `an unpaid or part-paid bill blocks release`() {
        assertFalse(bill(1000.0, 0.0).isSettled, "unpaid must block")
        assertFalse(bill(1000.0, 300.0).isSettled, "a 300 advance on 1000 must still block")
        assertTrue(bill(1000.0, 300.0).isPartPaid)
    }

    @Test
    fun `a settled bill releases`() {
        assertTrue(bill(1000.0, 1000.0).isSettled)
        assertTrue(bill(1000.0, 1200.0).isSettled, "over-tender is change, not a block")
    }

    @Test
    fun `money queued offline counts immediately`() {
        // The whole point of offline-first: cash taken at the counter releases the
        // report NOW, not when the outbox happens to drain.
        val b = bill(total = 1000.0, paid = 300.0, queued = 700.0)
        assertTrue(b.isSettled, "queued tender must count toward release")
        assertTrue(b.hasQueuedPayment)
    }

    @Test
    fun `the settle tolerance is half a paisa — documented, not assumed`() {
        // Binary float dust from summing tenders IS absorbed…
        val dust = bill(total = 1000.0, paid = 100.0 + 200.0 + 700.0)
        assertTrue(dust.isSettled, "float noise on an exact split must settle; balance=${dust.balance}")

        // …but a REAL one-paisa shortfall is not. 333.33 x 3 = 999.99, so a bill
        // split three ways leaves ₹0.01 outstanding and the report stays locked.
        //
        // This is deliberate-by-omission rather than designed: the tolerance is
        // 0.005 (half a paisa) purely to absorb float error, and nobody chose a
        // rounding policy. Whether a lab should be able to release over a ₹0.01
        // gap is a MONEY decision, not a code one — widening it here would change
        // `isSettled` everywhere (bills list, dues totals), so it is left alone
        // and surfaced instead.
        val paisaShort = bill(total = 1000.0, paid = 333.33 * 3)
        assertFalse(paisaShort.isSettled,
            "current behaviour: ₹0.01 short still blocks; balance=${paisaShort.balance}")
    }

    @Test
    fun `a cancelled bill never blocks`() {
        assertTrue(bill(1000.0, 0.0, status = "cancelled").isSettled,
            "a cancelled bill owes nothing and must not trap the report")
    }
}
