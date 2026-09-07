package com.bnm.diagnosis

import com.bnm.diagnosis.print.Code128
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Accession labels are scanned back by handheld scanners at every bench, so
 * the encoder is proven the way a scanner proves it: the module stream is
 * decoded by an independent reference decoder (run lengths -> pattern table
 * -> values -> checksum -> Code B/C state machine) and must yield the input.
 */
class Code128Test {

    // ---- reference decoder ------------------------------------------------

    /** Module stream -> run-length widths (first run is a bar by construction). */
    private fun runLengths(modules: BooleanArray): List<Int> {
        assertTrue(modules.isNotEmpty() && modules[0], "symbol must start with a bar")
        val runs = ArrayList<Int>()
        var current = modules[0]
        var n = 0
        for (m in modules) {
            if (m == current) n++ else { runs += n; current = m; n = 1 }
        }
        runs += n
        return runs
    }

    /** Run-length widths -> symbol values, matching each group against PATTERNS. */
    private fun decodeValues(modules: BooleanArray): List<Int> {
        val runs = runLengths(modules)
        assertEquals(1, runs.size % 6, "run count must be 6n+7 (n data symbols + 7-element STOP)")
        val patternIndex = Code128.PATTERNS.withIndex().associate { (i, p) -> p to i }
        val values = ArrayList<Int>()
        var i = 0
        while (i < runs.size - 7) {
            val key = runs.subList(i, i + 6).joinToString("")
            values += patternIndex[key] ?: error("unknown pattern $key at run $i")
            i += 6
        }
        val stop = runs.subList(i, i + 7).joinToString("")
        assertEquals(Code128.PATTERNS[106], stop, "trailing symbol must be STOP")
        values += 106
        return values
    }

    /** Values -> text. Verifies the checksum, then walks the Code B / Code C state machine. */
    private fun decodeText(values: List<Int>): String {
        assertTrue(values.size >= 4, "start + data + checksum + stop")
        assertEquals(106, values.last())
        val data = values.subList(0, values.size - 2)
        val checksum = values[values.size - 2]
        var sum = data[0]
        for (idx in 1 until data.size) sum += data[idx] * idx
        assertEquals(sum % 103, checksum, "checksum mismatch")

        var inC = when (data[0]) {
            104 -> false
            105 -> true
            else -> error("unsupported start code ${data[0]}")
        }
        val sb = StringBuilder()
        for (v in data.drop(1)) {
            if (inC) {
                when (v) {
                    100 -> inC = false
                    in 0..99 -> sb.append((v / 10).toString()).append((v % 10).toString())
                    else -> error("bad Code C value $v")
                }
            } else {
                when (v) {
                    99 -> inC = true
                    in 0..94 -> sb.append((v + 32).toChar())
                    else -> error("bad Code B value $v")
                }
            }
        }
        return sb.toString()
    }

    private fun roundTrip(text: String) {
        val values = Code128.values(text)
        val modules = Code128.modules(text)
        assertEquals(11 * values.size + 2, modules.size, "module count for '$text'")
        assertTrue(values[values.size - 2] < 103, "checksum symbol out of range for '$text'")
        assertEquals(106, values.last())
        val decodedValues = decodeValues(modules)
        assertEquals(values.toList(), decodedValues, "values via module decode for '$text'")
        assertEquals(text, decodeText(decodedValues), "round trip for '$text'")
    }

    // ---- tests -------------------------------------------------------------

    @Test
    fun patternTableIsWellFormed() {
        assertEquals(107, Code128.PATTERNS.size)
        for (i in 0..105) {
            val p = Code128.PATTERNS[i]
            assertEquals(6, p.length, "pattern $i length")
            assertEquals(11, p.sumOf { it - '0' }, "pattern $i modules")
        }
        assertEquals("2331112", Code128.PATTERNS[106])
        assertEquals(107, Code128.PATTERNS.toSet().size, "patterns must be unique for decoding")
    }

    @Test
    fun exactVectorCodeB() {
        assertContentEquals(intArrayOf(104, 33, 34, 35, 1, 106), Code128.values("ABC"))
    }

    @Test
    fun exactVectorCodeC() {
        assertContentEquals(intArrayOf(105, 12, 34, 82, 106), Code128.values("1234"))
    }

    @Test
    fun accessionNumbersRoundTrip() {
        roundTrip("ACC-S1-00042")
        roundTrip("ACC-S12-00007")
        roundTrip("LAB/2026/000123")
    }

    @Test
    fun mixedAndDigitRunsRoundTrip() {
        roundTrip("A1B2C3")
        roundTrip("12345")
        roundTrip("00042")
    }

    @Test
    fun fortyCharMixedRoundTrip() {
        val text = "BNM-DX/2026/09/07-ACC#000123-Q9z (v1.00)"
        assertEquals(40, text.length)
        roundTrip(text)
    }

    @Test
    fun digitRunsUseCodeC() {
        // "ACC-S1-00042": run "00042" (5 digits) -> Code C for "0004", back to B for "2".
        val v = Code128.values("ACC-S1-00042").toList()
        assertEquals(104, v[0])
        assertTrue(99 in v, "switch to Code C expected")
        assertTrue(100 in v, "switch back to Code B expected for the odd trailing digit")
        // "00042" alone: odd length so not pure Code C.
        assertEquals(104, Code128.values("00042")[0])
        // "A1B2C3": runs shorter than 4 stay in Code B.
        assertFalse(99 in Code128.values("A1B2C3").toList())
    }

    @Test
    fun rejectsEmptyAndNonAscii() {
        assertFalse(Code128.isEncodable(""))
        assertFalse(Code128.isEncodable("ACC-é-001"))
        assertFalse(Code128.isEncodable("TAB\tX"))
        assertTrue(Code128.isEncodable("ACC-S1-00042"))
        assertFailsWith<IllegalArgumentException> { Code128.values("") }
        assertFailsWith<IllegalArgumentException> { Code128.values("ACC-é-001") }
        assertFailsWith<IllegalArgumentException> { Code128.modules("") }
    }
}
