package com.bnm.lab

import com.bnm.lab.api.models.Invoice
import com.bnm.lab.chat.InvoiceBalance
import com.bnm.lab.lab.LabStatus
import com.bnm.lab.revenue.RevenueLine
import com.bnm.lab.revenue.RevenuePreset
import com.bnm.lab.revenue.RevenueRange
import com.bnm.lab.revenue.RevenueReportBuilder
import com.bnm.lab.revenue.groupIndian
import com.bnm.lab.revenue.inr
import com.bnm.lab.revenue.percentChange
import com.bnm.lab.revenue.renderRevenueCsv
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The revenue dashboard's arithmetic. Every figure an owner will act on — what
 * came in, what is still owed, what was never billed — is pinned here, against
 * the traps the local data really has: other apps' bills in the same business,
 * UTC stamps read by an IST lab, cancelled orders with live bills.
 */
class RevenueReportTest {

    private val ist = TimeZone.of("Asia/Kolkata")
    private fun d(s: String) = LocalDate.parse(s)
    private val sep10 = RevenueRange(d("2026-09-10"), d("2026-09-10"))

    private fun line(
        order: String, createdAt: String, price: Double,
        status: String = LabStatus.REGISTERED, referrer: String? = null, invoice: String? = null,
        test: String = "t-cbc", name: String = "CBC", pct: Double = 0.0,
    ) = RevenueLine(order, createdAt, status, referrer, invoice, test, name, price, pct)

    private fun bill(
        id: String, total: Double, issued: String = "2026-09-10", status: String = "pending",
        // What BillingRepository.paidAmountOf yields: a paid bill has collected its total.
        paid: Double = if (status == "paid") total else 0.0, queued: Double = 0.0, series: String? = "L1", method: String? = null,
        number: String? = "LAB-L1-0001",
    ) = InvoiceBalance(
        Invoice(id = id, total = total, issuedAt = issued, status = status, seriesCode = series,
            paymentMethod = method, invoiceNumber = number),
        paidAmount = paid, queuedAmount = queued,
    )

    private fun build(
        lines: List<RevenueLine>, bills: List<InvoiceBalance>, range: RevenueRange = sep10,
        linked: Set<String> = lines.mapNotNull { it.invoiceId }.toSet(), names: Map<String, String> = emptyMap(),
    ) = RevenueReportBuilder.build(range, lines, bills, linked, names, ist)

    @Test
    fun `billed, collected and due come from the bills dated in the range`() {
        val r = build(
            lines = listOf(line("o1", "2026-09-10T05:00:00Z", 1000.0, invoice = "b1"),
                line("o2", "2026-09-10T06:00:00Z", 500.0, invoice = "b2")),
            bills = listOf(
                bill("b1", 1000.0, status = "paid", paid = 1000.0, method = "cash"),
                bill("b2", 500.0, paid = 200.0, method = "upi"),
            ),
        )
        assertEquals(1500.0, r.current.billed)
        assertEquals(1200.0, r.current.collected)
        assertEquals(300.0, r.current.due)
        assertEquals(2, r.current.bills)
        assertEquals(1500.0, r.current.orderValue)
        assertEquals(2, r.current.orders)
    }

    @Test
    fun `counter and sales bills in the same business are not lab revenue`() {
        // A connected lab's business also holds BNM Billing (C1) and BNM Admin (S1) bills.
        val r = build(
            lines = listOf(line("o1", "2026-09-10T05:00:00Z", 400.0, invoice = "b1")),
            bills = listOf(
                bill("b1", 400.0, status = "paid"),
                bill("c1", 9000.0, status = "paid", series = "C1", number = "INV-C1-0007"),
                bill("s1", 5000.0, status = "paid", series = "S1", number = "INV-S1-0002"),
            ),
        )
        assertEquals(400.0, r.current.billed, "only the lab's bill counts")
        assertEquals(1, r.current.bills)
    }

    @Test
    fun `a lab bill whose order link was lost still counts by its series`() {
        val r = build(
            lines = listOf(line("o1", "2026-09-10T05:00:00Z", 400.0, invoice = null)),
            bills = listOf(bill("orphan", 400.0, status = "paid", series = "L2", number = "LAB-L2-0009")),
            linked = emptySet(),
        )
        assertEquals(400.0, r.current.billed)
    }

    @Test
    fun `drafts and cancelled bills are not money`() {
        val r = build(
            lines = emptyList(),
            bills = listOf(bill("b1", 700.0, status = "cancelled", paid = 700.0), bill("b2", 300.0, status = "draft")),
        )
        assertEquals(0.0, r.current.billed)
        assertEquals(0.0, r.current.collected)
        assertEquals(0.0, r.outstandingAll)
    }

    @Test
    fun `collected never exceeds the bill and change in the drawer is not revenue`() {
        val r = build(emptyList(), listOf(bill("b1", 1000.0, paid = 700.0, queued = 500.0)))
        assertEquals(1000.0, r.current.collected)
        assertEquals(0.0, r.current.due)
    }

    @Test
    fun `an IST lab's early-morning order belongs to its own day, not yesterday's`() {
        // 00:15 IST on 10 Sep is 18:45Z on 9 Sep. Bucketing by the UTC date string
        // would move it to the 9th.
        val r = build(listOf(line("o1", "2026-09-09T18:45:00.123456Z", 250.0)), emptyList())
        assertEquals(1, r.current.orders)
        assertEquals(250.0, r.current.orderValue)
    }

    @Test
    fun `the first second of a local day is not lost to sub-second precision`() {
        // Midnight IST exactly, with microseconds — lexically after the bare
        // "…T18:30:00Z" bound a string compare would use.
        val r = build(listOf(line("o1", "2026-09-09T18:30:00.807050Z", 90.0)), emptyList())
        assertEquals(1, r.current.orders)
    }

    @Test
    fun `a bill counts on the date printed on it, even when a pull stamped it with an IST offset`() {
        val pulled = InvoiceBalance(
            Invoice(id = "b1", total = 600.0, status = "paid", seriesCode = "L1", issuedAt = null,
                createdAt = "2026-09-10T00:20:00+05:30"),
            paidAmount = 600.0, queuedAmount = 0.0,
        )
        val r = build(emptyList(), listOf(pulled))
        assertEquals(600.0, r.current.billed, "created_at with an offset must parse as an instant")
    }

    @Test
    fun `cancelled orders drop out of order value but a live bill on one is flagged`() {
        val r = build(
            lines = listOf(
                line("o1", "2026-09-10T05:00:00Z", 800.0, status = LabStatus.CANCELLED, invoice = "b1"),
                line("o2", "2026-09-10T05:00:00Z", 200.0, invoice = "b2"),
            ),
            bills = listOf(bill("b1", 800.0, status = "paid"), bill("b2", 200.0, status = "paid")),
        )
        assertEquals(200.0, r.current.orderValue)
        assertEquals(1, r.current.orders)
        assertEquals(1000.0, r.current.billed, "the bill was never cancelled, so the money is real")
        assertEquals(1, r.cancelledStillBilled)
    }

    @Test
    fun `orders with no bill, or a cancelled one, are reported as not billed`() {
        val r = build(
            lines = listOf(
                line("o1", "2026-09-10T05:00:00Z", 300.0, invoice = null),
                line("o2", "2026-09-10T05:00:00Z", 450.0, invoice = "gone"),
                line("o3", "2026-09-10T05:00:00Z", 150.0, invoice = "b3"),
                line("o4", "2026-09-10T05:00:00Z", 100.0, invoice = "b4"),
            ),
            bills = listOf(bill("b3", 150.0, status = "cancelled"), bill("b4", 100.0, status = "paid")),
        )
        assertEquals(3, r.unbilledOrders)
        assertEquals(900.0, r.unbilledValue)
    }

    @Test
    fun `outstanding counts every owing lab bill whatever its date`() {
        val r = build(
            lines = emptyList(),
            bills = listOf(bill("old", 1000.0, issued = "2026-06-01", paid = 400.0), bill("new", 200.0)),
        )
        assertEquals(800.0, r.outstandingAll)
        assertEquals(2, r.outstandingAllBills)
        assertEquals(200.0, r.current.due, "the period's due only covers its own bills")
    }

    @Test
    fun `the previous period is the same number of days immediately before`() {
        val range = RevenueRange(d("2026-09-01"), d("2026-09-15"))
        assertEquals(RevenueRange(d("2026-08-17"), d("2026-08-31")), range.prior())
        val r = build(
            lines = listOf(line("o1", "2026-08-20T05:00:00Z", 100.0), line("o2", "2026-09-05T05:00:00Z", 150.0)),
            bills = listOf(bill("b0", 100.0, issued = "2026-08-20", status = "paid"), bill("b1", 150.0, issued = "2026-09-05", status = "paid")),
            range = range,
        )
        assertEquals(150.0, r.current.billed)
        assertEquals(100.0, r.prior.billed)
        assertEquals(50, percentChange(r.current.billed, r.prior.billed))
        assertNull(percentChange(10.0, 0.0), "no comparison against nothing")
    }

    @Test
    fun `commission is the frozen percentage on non-cancelled lines, by referrer`() {
        val r = build(
            lines = listOf(
                line("o1", "2026-09-10T05:00:00Z", 1000.0, referrer = "dr1", pct = 10.0),
                line("o2", "2026-09-10T05:00:00Z", 500.0, referrer = null),
                line("o3", "2026-09-10T05:00:00Z", 900.0, referrer = "dr1", pct = 10.0, status = LabStatus.CANCELLED),
            ),
            bills = emptyList(),
            names = mapOf("dr1" to "Dr Rao"),
        )
        assertEquals(100.0, r.commissionPayable)
        val rao = r.byReferrer.first { it.key == "dr1" }
        assertEquals("Dr Rao", rao.label)
        assertEquals(1, rao.count)
        assertEquals(100.0, rao.commission)
        assertEquals("Walk-in", r.byReferrer.first { it.key == RevenueReportBuilder.WALK_IN }.label)
    }

    @Test
    fun `queued tenders count under their own modes, not the bill's`() {
        // ₹300 cash advance at registration, ₹700 by UPI later — offline, both queued.
        val b = InvoiceBalance(
            Invoice(id = "b1", total = 1000.0, issuedAt = "2026-09-10", status = "pending", seriesCode = "LO1", paymentMethod = "cash"),
            paidAmount = 0.0, queuedAmount = 1000.0, queuedByMethod = mapOf("cash" to 300.0, "upi" to 700.0),
            offlineArchive = true,
        )
        val r = build(emptyList(), listOf(b))
        assertEquals(listOf("UPI" to 700.0, "Cash" to 300.0), r.byPaymentMode.map { it.label to it.amount })
        assertEquals(1000.0, r.current.collected)
    }

    @Test
    fun `offline series count as lab bills`() {
        val r = build(emptyList(), listOf(
            bill("b1", 100.0, status = "paid", series = "LO1", number = "LAB-LO1-0001"),
            bill("b2", 100.0, status = "paid", series = "LO7K2Q", number = "LAB-LO7K2Q-0001"),
        ), linked = emptySet())
        assertEquals(200.0, r.current.billed)
    }

    @Test
    fun `a typed range that would stall or mislead is refused`() {
        val today = d("2026-09-15")
        assertEquals(RevenueRange(d("2026-09-01"), today), RevenueRange.validated(d("2026-09-01"), today, today))
        assertNull(RevenueRange.validated(d("1026-09-01"), today, today), "a mistyped year must not build 12,000 bars")
        assertNull(RevenueRange.validated(d("2026-09-10"), d("2026-09-01"), today))
        assertNull(RevenueRange.validated(d("2026-09-01"), d("2026-10-01"), today), "no future")
        assertNull(RevenueRange.validated(null, today, today))
    }

    @Test
    fun `payment modes split the collected money`() {
        val r = build(
            lines = emptyList(),
            bills = listOf(
                bill("b1", 500.0, status = "paid", method = "cash"),
                bill("b2", 300.0, status = "paid", method = "UPI"),
                bill("b3", 200.0, paid = 50.0),
                bill("b4", 100.0),
            ),
        )
        assertEquals(listOf("Cash" to 500.0, "UPI" to 300.0, "Mode not recorded" to 50.0),
            r.byPaymentMode.map { it.label to it.amount })
    }

    @Test
    fun `a long tail of tests folds into one row so the column still adds up`() {
        val lines = (1..12).map { i -> line("o$i", "2026-09-10T05:00:00Z", 100.0 + i, test = "t$i", name = "Test $i") }
        val r = build(lines, emptyList())
        assertEquals(RevenueReportBuilder.TOP_ROWS, r.topTests.size)
        assertEquals(r.current.orderValue, r.topTests.sumOf { it.amount }, 0.001)
        assertTrue(r.topTests.last().label.startsWith("Other tests"))
    }

    @Test
    fun `ranges beyond two months chart by month`() {
        val range = RevenueRange(d("2026-04-01"), d("2026-09-15"))
        val r = build(emptyList(), listOf(bill("b1", 100.0, issued = "2026-07-04", status = "paid")), range = range)
        assertTrue(r.monthly)
        assertEquals(6, r.buckets.size)
        assertEquals(100.0, r.buckets.first { it.label == "Jul 2026" }.billed)
    }

    @Test
    fun `presets resolve to the days an owner means`() {
        val today = d("2026-09-15")
        assertEquals(RevenueRange(today, today), RevenuePreset.TODAY.rangeFor(today))
        assertEquals(RevenueRange(d("2026-09-09"), today), RevenuePreset.LAST_7_DAYS.rangeFor(today))
        assertEquals(RevenueRange(d("2026-09-01"), today), RevenuePreset.THIS_MONTH.rangeFor(today))
        assertEquals(RevenueRange(d("2026-08-01"), d("2026-08-31")), RevenuePreset.LAST_MONTH.rangeFor(today))
        assertEquals(RevenueRange(d("2026-02-01"), d("2026-02-28")), RevenuePreset.LAST_MONTH.rangeFor(d("2026-03-10")))
        assertNull(RevenuePreset.CUSTOM.rangeFor(today))
    }

    @Test
    fun `tests registered at zero are counted so the owner can price them`() {
        val r = build(listOf(line("o1", "2026-09-10T05:00:00Z", 0.0), line("o2", "2026-09-10T05:00:00Z", 10.0)), emptyList())
        assertEquals(1, r.unpricedTests)
    }

    @Test
    fun `rupees read the Indian way`() {
        // A non-breaking space, so the symbol never wraps away from the amount.
        assertEquals("₹\u00A00", inr(0.0))
        assertEquals("₹\u00A0999", inr(999.0))
        assertEquals("₹\u00A01,24,500", inr(124500.0))
        assertEquals("₹\u00A012,34,567.50", inr(1234567.5))
        assertEquals("−\u00A0₹\u00A0250", inr(-250.0))
        assertEquals("1,00,00,000", groupIndian(10_000_000))
    }

    @Test
    fun `the CSV carries plain numbers a spreadsheet can add`() {
        val r = build(listOf(line("o1", "2026-09-10T05:00:00Z", 1500.0, invoice = "b1", name = "Lipid, fasting")),
            listOf(bill("b1", 1500.0, status = "paid", method = "cash")))
        val csv = renderRevenueCsv(r)
        assertTrue("Billed,1500.00,0.00" in csv, csv)
        assertTrue("\"Lipid, fasting\",1500.00,1" in csv, "a comma in a test name must be quoted")
        assertTrue("₹" !in csv)
    }
}
