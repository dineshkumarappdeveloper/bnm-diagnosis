package com.bnm.diagnosis.print

import kotlin.math.roundToLong

// ── Amount in words (Indian system: crore / lakh / thousand) ──
private val ONES = arrayOf(
    "", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten",
    "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen", "Eighteen", "Nineteen",
)
private val TENS = arrayOf("", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety")

private fun twoDigits(n: Int): String =
    if (n < 20) ONES[n] else (TENS[n / 10] + " " + ONES[n % 10]).trim()

private fun numToWords(n: Long): String {
    if (n == 0L) return ""
    val crore = n / 10_000_000
    val lakh = ((n / 100_000) % 100).toInt()
    val thousand = ((n / 1_000) % 100).toInt()
    val hundred = ((n / 100) % 10).toInt()
    val rest = (n % 100).toInt()
    val sb = StringBuilder()
    if (crore > 0) sb.append(numToWords(crore)).append(" Crore ")
    if (lakh > 0) sb.append(twoDigits(lakh)).append(" Lakh ")
    if (thousand > 0) sb.append(twoDigits(thousand)).append(" Thousand ")
    if (hundred > 0) sb.append(ONES[hundred]).append(" Hundred ")
    if (rest > 0) { if (sb.isNotEmpty()) sb.append("and "); sb.append(twoDigits(rest)) }
    return sb.toString().trim()
}

/** "₹1,062.50" → "Rupees One Thousand and Sixty Two and Fifty Paise only". */
fun amountInWords(amount: Double): String {
    val rupees = amount.toLong()
    val paise = ((amount - rupees) * 100.0).roundToLong()
    var s = "Rupees " + (if (rupees == 0L) "Zero" else numToWords(rupees))
    if (paise > 0) s += " and " + numToWords(paise) + " Paise"
    return "$s only"
}

// ── GST state code → state name (for "place of supply", Rule 46(m)) ──
private val GST_STATES = mapOf(
    "01" to "Jammu & Kashmir", "02" to "Himachal Pradesh", "03" to "Punjab", "04" to "Chandigarh",
    "05" to "Uttarakhand", "06" to "Haryana", "07" to "Delhi", "08" to "Rajasthan", "09" to "Uttar Pradesh",
    "10" to "Bihar", "11" to "Sikkim", "12" to "Arunachal Pradesh", "13" to "Nagaland", "14" to "Manipur",
    "15" to "Mizoram", "16" to "Tripura", "17" to "Meghalaya", "18" to "Assam", "19" to "West Bengal",
    "20" to "Jharkhand", "21" to "Odisha", "22" to "Chhattisgarh", "23" to "Madhya Pradesh", "24" to "Gujarat",
    "25" to "Daman & Diu", "26" to "Dadra & Nagar Haveli", "27" to "Maharashtra", "28" to "Andhra Pradesh (Old)",
    "29" to "Karnataka", "30" to "Goa", "31" to "Lakshadweep", "32" to "Kerala", "33" to "Tamil Nadu",
    "34" to "Puducherry", "35" to "Andaman & Nicobar", "36" to "Telangana", "37" to "Andhra Pradesh",
    "38" to "Ladakh", "97" to "Other Territory",
)

/** Format a place-of-supply value (a GST state code, or already a name) as "33 - Tamil Nadu". */
fun placeOfSupplyLabel(value: String?): String? {
    val v = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val code = v.take(2)
    val name = GST_STATES[code]
    return if (name != null) "$code - $name" else v
}
