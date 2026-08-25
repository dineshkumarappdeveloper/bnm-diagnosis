package com.bnm.diagnosis.util

import kotlin.math.absoluteValue
import kotlin.math.roundToLong

/**
 * KMP-compatible number formatting (replaces JVM-only String.format).
 */

/** Format a Double to N decimal places, e.g. formatDecimal(3.14159, 2) → "3.14" */
fun formatDecimal(value: Double, decimals: Int = 2): String {
    val factor = pow10(decimals)
    val rounded = (value * factor).roundToLong()
    val isNegative = rounded < 0
    val abs = rounded.absoluteValue
    val intPart = abs / factor
    val fracPart = abs % factor
    val fracStr = fracPart.toString().padStart(decimals, '0')
    return buildString {
        if (isNegative) append('-')
        append(intPart)
        if (decimals > 0) {
            append('.')
            append(fracStr)
        }
    }
}

/** Format as "%.0f" — no decimal places */
fun formatWhole(value: Double): String = formatDecimal(value, 0)

/** Format as "%.1f" — one decimal place */
fun formatDecimal1(value: Double): String = formatDecimal(value, 1)

/** Format as "%.2f" — two decimal places */
fun formatDecimal2(value: Double): String = formatDecimal(value, 2)

private fun pow10(n: Int): Long {
    var result = 1L
    repeat(n) { result *= 10 }
    return result
}
