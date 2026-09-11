package com.bnm.lab.print

/**
 * Code 128 symbol encoder for accession / sample labels.
 *
 * Pure common Kotlin on purpose: the same module stream feeds the desktop
 * label renderer, the Android BT printer path and the report PDF, and thermal
 * label printers only speak ASCII/Latin-1 — so the input is restricted to
 * printable ASCII (32..126), which is exactly Code B's alphabet.
 *
 * Code B is the base set; Code C (two digits per symbol) is used for digit
 * runs to keep accession barcodes short enough for 25mm labels. Code A and
 * the SHIFT symbol are never emitted.
 */
object Code128 {

    /** Symbol value of START B (ASCII 32..126, one char per symbol). */
    private const val START_B = 104

    /** Symbol value of START C (00..99, two digits per symbol). */
    private const val START_C = 105

    /** Switch-to-Code-C symbol (valid inside Code B). */
    private const val CODE_C = 99

    /** Switch-to-Code-B symbol (valid inside Code C). */
    private const val CODE_B = 100

    /** Terminating symbol; its pattern carries the 2-module termination bar. */
    private const val STOP = 106

    /** Digit runs shorter than this stay in Code B — the switch symbol would cost more than it saves. */
    private const val MIN_C_RUN = 4

    /**
     * Element widths per symbol value (index = value). Six elements
     * "bar,space,bar,space,bar,space" summing to 11 modules; STOP (106) has a
     * seventh element — the termination bar — for 13 modules total.
     * Straight from ISO/IEC 15417 Table 1.
     */
    val PATTERNS: Array<String> = arrayOf(
        "212222", "222122", "222221", "121223", "121322", "131222", "122213", "122312", "132212", "221213",
        "221312", "231212", "112232", "122132", "122231", "113222", "123122", "123221", "223211", "221132",
        "221231", "213212", "223112", "312131", "311222", "321122", "321221", "312212", "322112", "322211",
        "212123", "212321", "232121", "111323", "131123", "131321", "112313", "132113", "132311", "211313",
        "231113", "231311", "112133", "112331", "132131", "113123", "113321", "133121", "313121", "211331",
        "231131", "213113", "213311", "213131", "311123", "311321", "331121", "312113", "312311", "332111",
        "314111", "221411", "431111", "111224", "111422", "121124", "121421", "141122", "141221", "112214",
        "112412", "122114", "122411", "142112", "142211", "241211", "221114", "413111", "241112", "134111",
        "111242", "121142", "121241", "114212", "124112", "124211", "411212", "421112", "421211", "212141",
        "214121", "412121", "111143", "111341", "131141", "114113", "114311", "411113", "411311", "113141",
        "114131", "311141", "411131", "211412", "211214", "211232", "2331112",
    )

    /** True when every char is printable ASCII (32..126) and the text is non-empty. */
    fun isEncodable(text: String): Boolean =
        text.isNotEmpty() && text.all { it.code in 32..126 }

    /**
     * Symbol values for [text]: start code, data symbols (including any
     * Code C / Code B switches), the checksum symbol, then STOP.
     *
     * Digit handling: an all-digit, even-length text is pure Code C. Otherwise
     * the symbol starts in Code B and any run of >= [MIN_C_RUN] digits is
     * encoded in Code C for its longest even-length prefix, switching back to
     * Code B for whatever follows (an odd leftover digit or other text).
     *
     * @throws IllegalArgumentException for an empty string or a char outside ASCII 32..126.
     */
    fun values(text: String): IntArray {
        require(text.isNotEmpty()) { "Code 128: text is empty" }
        val bad = text.firstOrNull { it.code !in 32..126 }
        require(bad == null) { "Code 128: char U+${bad!!.code.toString(16).uppercase().padStart(4, '0')} is outside ASCII 32..126" }

        val symbols = ArrayList<Int>(text.length + 4)
        if (text.length % 2 == 0 && text.all { it.isAsciiDigit() }) {
            symbols += START_C
            for (i in text.indices step 2) symbols += pair(text, i)
        } else {
            symbols += START_B
            var i = 0
            var inC = false
            while (i < text.length) {
                val run = digitRunLength(text, i)
                if (run >= MIN_C_RUN) {
                    val even = run - (run % 2)
                    if (!inC) { symbols += CODE_C; inC = true }
                    var j = i
                    while (j < i + even) { symbols += pair(text, j); j += 2 }
                    i += even
                } else {
                    if (inC) { symbols += CODE_B; inC = false }
                    symbols += text[i].code - 32
                    i++
                }
            }
        }

        // Checksum: start value + sum(value_i * position_i) over every data
        // symbol (switches included), mod 103. Positions start at 1.
        var sum = symbols[0]
        for (idx in 1 until symbols.size) sum += symbols[idx] * idx
        symbols += sum % 103
        symbols += STOP
        return symbols.toIntArray()
    }

    /**
     * Bar/space modules left to right (true = bar) for the whole symbol,
     * WITHOUT quiet zones — the caller pads 10+ modules each side to suit its
     * printer. Length is always `11 * values.size + 2`.
     */
    fun modules(text: String): BooleanArray {
        val vals = values(text)
        val out = BooleanArray(11 * vals.size + 2)
        var pos = 0
        for (v in vals) {
            val pattern = PATTERNS[v]
            var bar = true
            for (ch in pattern) {
                val width = ch - '0'
                if (bar) out.fill(true, pos, pos + width)
                pos += width
                bar = !bar
            }
        }
        return out
    }

    /** Two-digit Code C value for text[i], text[i+1]. */
    private fun pair(text: String, i: Int): Int =
        (text[i] - '0') * 10 + (text[i + 1] - '0')

    /** Length of the consecutive ASCII-digit run starting at [from]. */
    private fun digitRunLength(text: String, from: Int): Int {
        var n = 0
        while (from + n < text.length && text[from + n].isAsciiDigit()) n++
        return n
    }

    /** Char.isDigit() also accepts Unicode digits; Code C only takes '0'..'9'. */
    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'
}
