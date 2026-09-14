package com.bnm.lab.revenue

import com.bnm.lab.api.models.Invoice
import com.bnm.lab.chat.InvoiceBalance
import com.bnm.lab.lab.LabStatus
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.math.round
import kotlin.time.Instant

/**
 * The revenue dashboard's arithmetic, kept pure so every rule below is pinned by
 * a test rather than by a screen.
 *
 * ONE BRAIN FOR BOTH EDITIONS. Everything is computed from this computer's own
 * database — the offline edition has nothing else, and a connected seat's
 * database already receives the lab's orders (every sync sweep) and its bills
 * (the five-minute invoice pull). The editions differ only in what has arrived.
 *
 * TWO CLOCKS, deliberately:
 *  • money (billed / collected / due) follows the BILL DATE — `issued_at`, the
 *    date printed on the bill — so a bill raised days after its order counts on
 *    the day it was raised, which is what a cash book shows;
 *  • work (orders, tests, order value, commission) follows REGISTRATION, the
 *    local calendar day `lab_orders.created_at` falls on, which is what the
 *    commission statement already uses.
 * The screen labels each figure with its clock.
 */

/** An inclusive range of local calendar days. */
data class RevenueRange(val from: LocalDate, val to: LocalDate) {
    init {
        require(from <= to) { "Range starts after it ends: $from > $to" }
    }

    companion object {
        /** Ten years: enough for any lab's history, small enough to chart. */
        const val MAX_DAYS = 3660

        /**
         * A range typed by hand, or null when it would mislead or stall: reversed,
         * longer than [MAX_DAYS] (a mistyped 1026 would build twelve thousand
         * monthly bars and freeze the screen), or reaching past [today].
         */
        fun validated(from: LocalDate?, to: LocalDate?, today: LocalDate): RevenueRange? {
            if (from == null || to == null || from > to || to > today) return null
            return RevenueRange(from, to).takeIf { it.days <= MAX_DAYS }
        }
    }

    val days: Int get() = from.daysUntil(to) + 1

    /** The same number of days immediately before — what the deltas compare against. */
    fun prior(): RevenueRange {
        val end = from.minus(DatePeriod(days = 1))
        return RevenueRange(end.minus(DatePeriod(days = days - 1)), end)
    }

    operator fun contains(day: LocalDate): Boolean = day >= from && day <= to
}

enum class RevenuePreset(val label: String) {
    TODAY("Today"),
    YESTERDAY("Yesterday"),
    LAST_7_DAYS("Last 7 days"),
    THIS_MONTH("This month"),
    LAST_MONTH("Last month"),
    CUSTOM("Custom");

    /** Null for [CUSTOM] — the screen owns those dates. */
    fun rangeFor(today: LocalDate): RevenueRange? = when (this) {
        TODAY -> RevenueRange(today, today)
        YESTERDAY -> today.minus(DatePeriod(days = 1)).let { RevenueRange(it, it) }
        LAST_7_DAYS -> RevenueRange(today.minus(DatePeriod(days = 6)), today)
        THIS_MONTH -> RevenueRange(firstOfMonth(today), today)
        LAST_MONTH -> {
            val lastOfPrev = firstOfMonth(today).minus(DatePeriod(days = 1))
            RevenueRange(firstOfMonth(lastOfPrev), lastOfPrev)
        }
        CUSTOM -> null
    }
}

internal fun firstOfMonth(d: LocalDate): LocalDate = LocalDate(d.year, d.month, 1)

/** One test on one order, as `revenueLines` returns it. */
data class RevenueLine(
    val orderId: String,
    val orderCreatedAt: String,
    val orderStatus: String,
    val referrerId: String?,
    val invoiceId: String?,
    val testId: String,
    val testName: String,
    val price: Double,
    val commissionPct: Double,
)

data class RevenueTotals(
    /** Σ bill totals (net of discount, tax included) dated in the range. */
    val billed: Double = 0.0,
    /** Money in against those bills, capped at each bill's total. */
    val collected: Double = 0.0,
    /** What those bills still owe. */
    val due: Double = 0.0,
    val bills: Int = 0,
    /** Σ test prices on orders registered in the range (cancelled orders excluded). */
    val orderValue: Double = 0.0,
    val orders: Int = 0,
    val tests: Int = 0,
)

data class RevenueBucket(
    val start: LocalDate,
    val label: String,
    val billed: Double,
    val collected: Double,
    val orderValue: Double,
)

/** A breakdown row. What [amount] and [count] mean depends on the list it is in. */
data class RevenueSlice(
    val key: String,
    val label: String,
    val amount: Double,
    val count: Int,
    /** Referrer rows: commission payable on that referrer's orders. */
    val commission: Double = 0.0,
)

data class RevenueReport(
    val range: RevenueRange,
    val priorRange: RevenueRange,
    val current: RevenueTotals,
    val prior: RevenueTotals,
    /** Orders registered in the range with no live bill on this computer. */
    val unbilledValue: Double,
    val unbilledOrders: Int,
    val commissionPayable: Double,
    /** Every lab bill still owing, whenever it was raised — the collections to chase. */
    val outstandingAll: Double,
    val outstandingAllBills: Int,
    /** Cancelled orders whose bill was never cancelled — money that may need refunding. */
    val cancelledStillBilled: Int,
    /** Tests registered at ₹0 — usually an unpriced master-catalog test. */
    val unpricedTests: Int,
    /** Buckets are calendar months (long ranges) rather than days. */
    val monthly: Boolean,
    val buckets: List<RevenueBucket>,
    /** amount = collected, count = bills. */
    val byPaymentMode: List<RevenueSlice>,
    /** amount = order value, count = times ordered. */
    val topTests: List<RevenueSlice>,
    /** amount = order value, count = orders. */
    val byReferrer: List<RevenueSlice>,
) {
    val isEmpty: Boolean get() = current.orders == 0 && current.bills == 0
}

object RevenueReportBuilder {
    /** Rows shown per breakdown; the rest fold into one "Others" row. */
    const val TOP_ROWS = 8

    /** Beyond two months a day-by-day chart is a comb, not a chart. */
    private const val MAX_DAY_BUCKETS = 62

    fun build(
        range: RevenueRange,
        lines: List<RevenueLine>,
        balances: List<InvoiceBalance>,
        linkedInvoiceIds: Set<String>,
        referrerNames: Map<String, String>,
        timeZone: TimeZone,
    ): RevenueReport {
        val priorRange = range.prior()
        val labBills = balances.filter { isLabBill(it.invoice, linkedInvoiceIds) }
        val billById = labBills.associateBy { it.invoice.id }

        // Orders, one entry each, dated by registration in local time.
        val orders = lines.groupBy { it.orderId }.mapNotNull { (_, ls) ->
            val first = ls.first()
            val day = localDayOf(first.orderCreatedAt, timeZone) ?: return@mapNotNull null
            OrderRow(first, day, ls)
        }
        val liveBills = labBills.filter { it.invoice.status !in NOT_MONEY }

        fun totals(r: RevenueRange): RevenueTotals {
            val bills = liveBills.filter { b -> billDay(b.invoice, timeZone)?.let { it in r } == true }
            val work = orders.filter { it.day in r && it.head.orderStatus != LabStatus.CANCELLED }
            return RevenueTotals(
                billed = money(bills.sumOf { it.invoice.total }),
                collected = money(bills.sumOf { collectedOn(it) }),
                due = money(bills.sumOf { dueOn(it) }),
                bills = bills.size,
                orderValue = money(work.sumOf { o -> o.lines.sumOf { it.price } }),
                orders = work.size,
                tests = work.sumOf { it.lines.size },
            )
        }

        val inRange = orders.filter { it.day in range }
        val work = inRange.filter { it.head.orderStatus != LabStatus.CANCELLED }
        val workLines = work.flatMap { it.lines }

        val unbilled = work.filter { o ->
            val bill = o.head.invoiceId?.let { billById[it] }
            bill == null || bill.invoice.status in NOT_MONEY
        }
        val cancelledStillBilled = inRange.count { o ->
            o.head.orderStatus == LabStatus.CANCELLED &&
                o.head.invoiceId?.let { billById[it] }?.invoice?.status?.let { it !in NOT_MONEY } == true
        }
        val owing = liveBills.filter { dueOn(it) > HALF_PAISA }

        val rangeBills = liveBills.filter { b -> billDay(b.invoice, timeZone)?.let { it in range } == true }

        return RevenueReport(
            range = range,
            priorRange = priorRange,
            current = totals(range),
            prior = totals(priorRange),
            unbilledValue = money(unbilled.sumOf { o -> o.lines.sumOf { it.price } }),
            unbilledOrders = unbilled.size,
            commissionPayable = money(workLines.sumOf { it.price * it.commissionPct / 100.0 }),
            outstandingAll = money(owing.sumOf { dueOn(it) }),
            outstandingAllBills = owing.size,
            cancelledStillBilled = cancelledStillBilled,
            unpricedTests = workLines.count { it.price <= 0.0 },
            monthly = range.days > MAX_DAY_BUCKETS,
            buckets = buckets(range, rangeBills, work, timeZone),
            byPaymentMode = paymentModes(rangeBills),
            topTests = topTests(workLines),
            byReferrer = referrers(work, referrerNames),
        )
    }

    private data class OrderRow(val head: RevenueLine, val day: LocalDate, val lines: List<RevenueLine>)

    private fun buckets(
        range: RevenueRange,
        bills: List<InvoiceBalance>,
        work: List<OrderRow>,
        timeZone: TimeZone,
    ): List<RevenueBucket> {
        val monthly = range.days > MAX_DAY_BUCKETS
        fun keyOf(d: LocalDate) = if (monthly) firstOfMonth(d) else d

        val starts = buildList {
            var d = keyOf(range.from)
            while (d <= range.to) {
                add(d)
                d = if (monthly) d.plus(DatePeriod(months = 1)) else d.plus(DatePeriod(days = 1))
            }
        }
        val billedBy = HashMap<LocalDate, Double>()
        val collectedBy = HashMap<LocalDate, Double>()
        for (b in bills) {
            val k = keyOf(billDay(b.invoice, timeZone) ?: continue)
            billedBy[k] = (billedBy[k] ?: 0.0) + b.invoice.total
            collectedBy[k] = (collectedBy[k] ?: 0.0) + collectedOn(b)
        }
        val workBy = HashMap<LocalDate, Double>()
        for (o in work) {
            val k = keyOf(o.day)
            workBy[k] = (workBy[k] ?: 0.0) + o.lines.sumOf { it.price }
        }
        return starts.map { s ->
            RevenueBucket(
                start = s,
                label = if (monthly) "${MONTHS[s.month.ordinal]} ${s.year}" else "${s.day} ${MONTHS[s.month.ordinal]}",
                billed = money(billedBy[s] ?: 0.0),
                collected = money(collectedBy[s] ?: 0.0),
                orderValue = money(workBy[s] ?: 0.0),
            )
        }
    }

    /**
     * Collected money by the mode each tender was taken in. A tender still queued
     * on this computer carries its own method — always true on the offline
     * edition, where the queue IS the payment record. What the server already
     * counted is attributed to the bill's recorded mode, which the server keeps as
     * its latest tender's; that split is only as fine as the bill row.
     */
    private fun paymentModes(bills: List<InvoiceBalance>): List<RevenueSlice> {
        val amounts = LinkedHashMap<String, Double>()
        val billCounts = LinkedHashMap<String, MutableSet<String>>()
        for (b in bills) {
            val collected = collectedOn(b)
            if (collected <= HALF_PAISA) continue
            val parts = buildList {
                if (b.paidAmount > HALF_PAISA) add(paymentModeKey(b.invoice.paymentMethod) to b.paidAmount)
                b.queuedByMethod.forEach { (m, amt) -> if (amt > HALF_PAISA) add(paymentModeKey(m) to amt) }
                if (isEmpty()) add(paymentModeKey(b.invoice.paymentMethod) to collected)
            }
            // Over-tendered bills are capped at their total; shrink every part alike.
            val scale = collected / parts.sumOf { it.second }
            for ((key, amt) in parts) {
                amounts[key] = (amounts[key] ?: 0.0) + amt * scale
                billCounts.getOrPut(key) { HashSet() }.add(b.invoice.id)
            }
        }
        return amounts.map { (key, amt) -> RevenueSlice(key, paymentModeLabel(key), money(amt), billCounts[key]?.size ?: 0) }
            .sortedByDescending { it.amount }
    }

    private fun topTests(lines: List<RevenueLine>): List<RevenueSlice> =
        lines.groupBy { it.testId }
            .map { (id, ls) -> RevenueSlice(id, ls.last().testName, money(ls.sumOf { it.price }), ls.size) }
            .sortedWith(compareByDescending<RevenueSlice> { it.amount }.thenByDescending { it.count })
            .foldTail("Other tests")

    private fun referrers(work: List<OrderRow>, names: Map<String, String>): List<RevenueSlice> =
        work.groupBy { it.head.referrerId }
            .map { (id, os) ->
                val ls = os.flatMap { it.lines }
                RevenueSlice(
                    key = id ?: WALK_IN,
                    label = if (id == null) "Walk-in" else names[id] ?: "Removed referrer",
                    amount = money(ls.sumOf { it.price }),
                    count = os.size,
                    commission = money(ls.sumOf { it.price * it.commissionPct / 100.0 }),
                )
            }
            .sortedByDescending { it.amount }
            .foldTail("Other referrers")

    /** Keep [TOP_ROWS]; beyond that one row carries the rest, so the column still adds up. */
    private fun List<RevenueSlice>.foldTail(label: String): List<RevenueSlice> {
        if (size <= TOP_ROWS) return this
        val tail = drop(TOP_ROWS - 1)
        return take(TOP_ROWS - 1) + RevenueSlice(
            key = OTHERS, label = "$label (${tail.size})",
            amount = money(tail.sumOf { it.amount }), count = tail.sumOf { it.count },
            commission = money(tail.sumOf { it.commission }),
        )
    }

    const val WALK_IN = "walk-in"
    const val OTHERS = "others"
}

/** Drafts are not bills yet; a cancelled bill is money that never was. */
private val NOT_MONEY = setOf("draft", "cancelled")

private const val HALF_PAISA = 0.005

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** L1, L2… from the server; LO1 / LO7K2Q minted on an offline computer (BillingScope.offlineSeriesCode). */
private val LAB_SERIES = Regex("^L(\\d+|O[0-9A-Z]+)$")

/**
 * A LAB bill. A connected lab's business may also carry BNM Billing counter bills
 * (C1…) and BNM Admin sales bills (S1…) — not the lab's revenue. An order link
 * is the strongest evidence; the L-series and LAB- number are the fallback for a
 * bill whose link was lost (order sync is last-writer-wins on the whole row).
 */
internal fun isLabBill(inv: Invoice, linkedInvoiceIds: Set<String>): Boolean =
    inv.id in linkedInvoiceIds ||
        inv.seriesCode?.let { LAB_SERIES.matches(it) } == true ||
        inv.invoiceNumber?.startsWith("LAB-") == true

/** Collected against one bill, never negative and never more than the bill. */
internal fun collectedOn(b: InvoiceBalance): Double =
    b.collected.coerceIn(0.0, b.invoice.total.coerceAtLeast(0.0))

internal fun dueOn(b: InvoiceBalance): Double =
    (b.invoice.total - collectedOn(b)).coerceAtLeast(0.0)

/**
 * The calendar day a bill belongs to: the date printed on it. `created_at` is
 * only a fallback — it mixes `Z` stamps made on this PC with `+05:30` stamps a
 * pull brings back, so it is parsed as an instant, never compared as text.
 */
internal fun billDay(inv: Invoice, timeZone: TimeZone): LocalDate? =
    inv.issuedAt?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?: inv.createdAt?.let { localDayOf(it, timeZone) }

/** The local calendar day an instant string falls on, or null if it is not one. */
internal fun localDayOf(instant: String, timeZone: TimeZone): LocalDate? =
    runCatching { Instant.parse(instant).toLocalDateTime(timeZone).date }.getOrNull()

internal fun paymentModeKey(method: String?): String = method?.trim()?.lowercase()?.ifBlank { null } ?: "unrecorded"

internal fun paymentModeLabel(key: String): String = when (key) {
    "cash" -> "Cash"
    "upi" -> "UPI"
    "card" -> "Card"
    "payment_link" -> "Payment link"
    "unrecorded" -> "Mode not recorded"
    else -> key.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

/** Money is reported to the paisa; summed floats are not. */
internal fun money(x: Double): Double = round(x * 100.0) / 100.0

/** Change against the previous period as a whole percent, or null when there is nothing to compare. */
fun percentChange(now: Double, before: Double): Int? =
    if (before <= HALF_PAISA) null else round((now - before) / before * 100.0).toInt()
