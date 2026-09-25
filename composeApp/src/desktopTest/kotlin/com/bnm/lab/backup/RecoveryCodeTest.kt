package com.bnm.lab.backup

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecoveryCodeTest {

    @Test
    fun `125 bits become 25 Crockford characters and back`() {
        val bits = ByteArray(16).also { Random(7).nextBytes(it) }
        bits[15] = (bits[15].toInt() and 0xF8).toByte() // the three bits no character covers
        val code = RecoveryCode.fromBits(bits)
        assertEquals(25, code.length)
        assertTrue(code.all { it in RecoveryCode.ALPHABET }, code)
        assertContentEquals(bits, RecoveryCode.toBits(code))
        val generated = RecoveryCode.generate()
        assertEquals(25, generated.length)
        assertTrue(generated.all { it in RecoveryCode.ALPHABET })
        assertTrue(generated.none { it in "ILOU" })
    }

    @Test
    fun `entry forgives case, dashes, spaces and the letters people type for 1 and 0`() {
        val code = "0123456789ABCDEFGHJKMNPQR"
        assertEquals(code, RecoveryCode.normalise(RecoveryCode.format(code)))
        assertEquals(code, RecoveryCode.normalise(RecoveryCode.format(code).lowercase()))
        assertEquals(code, RecoveryCode.normalise("01234 56789 abcde fghjk mnpqr"))
        assertEquals(code, RecoveryCode.normalise("OI234-56789-ABCDE-FGHJK-MNPQR"), "O→0, I→1")
        assertEquals(code, RecoveryCode.normalise("0L234-56789-ABCDE-FGHJK-MNPQR"), "L→1")
        assertEquals("01234-56789-ABCDE-FGHJK-MNPQR", RecoveryCode.format(code))
    }

    @Test
    fun `anything else is not a code`() {
        assertNull(RecoveryCode.normalise("0123456789ABCDEFGHJKMNPQ"), "24 characters")
        assertNull(RecoveryCode.normalise("0123456789ABCDEFGHJKMNPQRS"), "26 characters")
        assertNull(RecoveryCode.normalise("U123456789ABCDEFGHJKMNPQR"), "U is not in the alphabet")
        assertNull(RecoveryCode.normalise("0123456789ABCDEFGHJKMNPQ*"))
        assertNull(RecoveryCode.normalise(""))
        assertTrue(RecoveryCode.looksValid(RecoveryCode.generate()))
    }
}
