package com.bnm.diagnosis

import com.bnm.diagnosis.print.EscPos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Locks in the ESC/POS byte stream for LAN thermal printing (TVS RP3200 etc.). */
class EscPosTest {
    @Test
    fun startsWithInitAndEndsWithCut() {
        val b = EscPos.encode("Hi\n")
        // ESC @  (init)
        assertEquals(0x1B.toByte(), b[0])
        assertEquals(0x40.toByte(), b[1])
        // GS V 0  (full cut) at the very end
        val n = b.size
        assertEquals(0x1D.toByte(), b[n - 3])
        assertEquals(0x56.toByte(), b[n - 2])
        assertEquals(0x00.toByte(), b[n - 1])
    }

    @Test
    fun bodyAsciiIsEmitted() {
        val b = EscPos.encode("Hi")
        assertEquals('H'.code.toByte(), b[2])
        assertEquals('i'.code.toByte(), b[3])
    }

    @Test
    fun rupeeGlyphIsStrippedNotGarbled() {
        // ₹ (U+20B9) must contribute zero bytes — not a '?' fallback — so columns stay aligned.
        assertEquals(EscPos.encode("").size, EscPos.encode("₹").size)
        assertTrue(EscPos.encode("₹150").none { it == '?'.code.toByte() })
    }

    @Test
    fun cutCanBeDisabled() {
        val withCut = EscPos.encode("x", cut = true)
        val noCut = EscPos.encode("x", cut = false)
        assertEquals(withCut.size - 3, noCut.size) // the 3-byte GS V 0
    }
}
