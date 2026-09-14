package com.bnm.lab.revenue

import kotlin.math.absoluteValue
import kotlin.math.roundToLong

/**
 * Rupees the way an Indian owner reads them: lakh grouping (₹ 1,24,500), and
 * paise only when there are any — a revenue figure of "₹ 1,24,500.00" is noise.
 */
fun inr(value: Double): String {
    val paise = (value * 100.0).roundToLong()
    val negative = paise < 0
    val abs = paise.absoluteValue
    val rupees = abs / 100
    val frac = abs % 100
    return buildString {
        // Non-breaking spaces: a wrapped chip must never leave "₹" on one line
        // and the amount on the next.
        if (negative) append("−\u00A0")
        append("₹\u00A0")
        append(groupIndian(rupees))
        if (frac != 0L) append('.').append(frac.toString().padStart(2, '0'))
    }
}

/** 1234567 → "12,34,567": last three digits, then pairs. */
internal fun groupIndian(n: Long): String {
    val s = n.toString()
    if (s.length <= 3) return s
    val head = s.dropLast(3)
    val tail = s.takeLast(3)
    val pairs = head.reversed().chunked(2).joinToString(",").reversed()
    return "$pairs,$tail"
}

/**
 * The period as CSV for a spreadsheet or an accountant: headline figures, then
 * one row per chart bucket, then the breakdowns. Plain numbers (no ₹, no
 * grouping) so a spreadsheet can add them up.
 */
fun renderRevenueCsv(r: RevenueReport): String = buildString {
    fun cell(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    fun num(x: Double) = com.bnm.lab.util.formatDecimal2(x)
    fun row(vararg cells: String) = appendLine(cells.joinToString(",") { cell(it) })

    row("Revenue", "${r.range.from}", "${r.range.to}")
    row("Figure", "This period", "Previous period")
    row("Billed", num(r.current.billed), num(r.prior.billed))
    row("Collected", num(r.current.collected), num(r.prior.collected))
    row("Due on these bills", num(r.current.due), num(r.prior.due))
    row("Bills", "${r.current.bills}", "${r.prior.bills}")
    row("Order value", num(r.current.orderValue), num(r.prior.orderValue))
    row("Orders", "${r.current.orders}", "${r.prior.orders}")
    row("Tests", "${r.current.tests}", "${r.prior.tests}")
    row("Not billed", num(r.unbilledValue), "")
    row("Referral commission", num(r.commissionPayable), "")
    row("Due across all bills", num(r.outstandingAll), "")
    appendLine()
    row(if (r.monthly) "Month" else "Day", "Billed", "Collected", "Order value")
    r.buckets.forEach { row(it.start.toString(), num(it.billed), num(it.collected), num(it.orderValue)) }
    appendLine()
    row("Payment mode", "Collected", "Bills")
    r.byPaymentMode.forEach { row(it.label, num(it.amount), "${it.count}") }
    appendLine()
    row("Test", "Value", "Times ordered")
    r.topTests.forEach { row(it.label, num(it.amount), "${it.count}") }
    appendLine()
    row("Referrer", "Order value", "Orders", "Commission")
    r.byReferrer.forEach { row(it.label, num(it.amount), "${it.count}", num(it.commission)) }
}
