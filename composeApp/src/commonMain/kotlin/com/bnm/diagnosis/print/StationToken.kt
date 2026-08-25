package com.bnm.diagnosis.print

import com.bnm.diagnosis.api.models.BillingStation
import com.bnm.diagnosis.api.models.Invoice
import com.bnm.diagnosis.api.models.InvoiceLineItem
import com.bnm.diagnosis.util.formatDecimal2
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Bucket a saved bill's line items by collection station (self-service
 * restaurants / food courts). Rules:
 *  • EXPLICIT WINS — a line whose productId is in any station's `product_ids`
 *    joins that station (first such station in config order), even if another
 *    station matches its category.
 *  • Else the line joins the first station whose `categories` contains the
 *    product's category name (case-insensitive, trimmed).
 *  • Custom/free-style lines (productId null) and unmatched lines stay on the
 *    MAIN receipt only — no token.
 * Returns only non-empty buckets, in station config order. No stations
 * configured → empty list → behavior is exactly as before.
 */
fun bucketLinesByStation(
    stations: List<BillingStation>,
    lines: List<InvoiceLineItem>,
    categoryByProductId: Map<String, String>,
): List<Pair<BillingStation, List<InvoiceLineItem>>> {
    if (stations.isEmpty()) return emptyList()
    fun norm(s: String?) = s?.trim()?.lowercase().orEmpty()
    val buckets = HashMap<Int, MutableList<InvoiceLineItem>>()
    for (line in lines) {
        val pid = line.productId?.takeIf { it.isNotBlank() } ?: continue
        var idx = stations.indexOfFirst { st -> pid in st.productIds }
        if (idx < 0) {
            val cat = norm(categoryByProductId[pid])
            if (cat.isNotEmpty()) idx = stations.indexOfFirst { st -> st.categories.any { norm(it) == cat } }
        }
        if (idx < 0) continue
        buckets.getOrPut(idx) { mutableListOf() }.add(line)
    }
    return stations.indices.mapNotNull { i -> buckets[i]?.let { stations[i] to it.toList() } }
}

/**
 * Render one small COLLECTION TOKEN for a station, printed after the main
 * receipt. The token number is the headline — on its own line, surrounded by
 * blank lines — so the counter reads it at arm's length:
 * ```
 * ===============
 *
 *    TOKEN 42
 *
 *   Juice Counter
 * ---------------
 * INV-C2-0031 · 12:45
 * ---------------
 * 2 x Cold Brew Coffee
 * 1 x Fresh Lime Soda
 * ---------------
 *   Collect here
 * ```
 * Time is the bill's createdAt as local HH:mm (omitted when unparseable).
 */
fun renderStationToken(
    stationName: String,
    tokenNo: Int,
    invoice: Invoice,
    lines: List<InvoiceLineItem>,
    widthChars: Int = 32,
): String {
    val w = widthChars.coerceIn(30, 80)
    val sb = StringBuilder()
    fun ln(s: String = "") { sb.append(s).append('\n') }
    fun rule(ch: Char = '-') = ln(ch.toString().repeat(w))
    fun center(s: String) = ln(" ".repeat(((w - s.length) / 2).coerceAtLeast(0)) + s)
    fun wrap(s: String) { var t = s.trim(); if (t.isEmpty()) return; while (t.length > w) { ln(t.take(w)); t = t.drop(w).trim() }; ln(t) }

    rule('=')
    ln()
    center("TOKEN $tokenNo")
    ln()
    center(stationName.trim().ifEmpty { "Counter" })
    rule()
    val time = invoice.createdAt?.let { raw ->
        runCatching { kotlin.time.Instant.parse(raw).toLocalDateTime(TimeZone.currentSystemDefault()) }.getOrNull()
    }?.let { "${it.hour.toString().padStart(2, '0')}:${it.minute.toString().padStart(2, '0')}" }
    wrap(invoice.displayNumber + (time?.let { " · $it" } ?: ""))
    rule()
    lines.forEach { li ->
        val qty = if (li.quantity == li.quantity.toLong().toDouble()) li.quantity.toLong().toString()
        else formatDecimal2(li.quantity)
        wrap("$qty x ${li.description}")
    }
    rule()
    center("Collect here")
    return sb.toString()
}
