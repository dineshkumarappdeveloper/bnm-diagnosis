package com.bnm.lab

import com.bnm.lab.instruments.AnalyzerUnits
import com.bnm.lab.instruments.MindrayBc5x
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The unit menu a Mindray operator can pick from, and the histogram layouts the decoder believes. */
@OptIn(ExperimentalEncodingApi::class)
class AnalyzerUnitsVendorTest {

    @Test
    fun `every unit on the BC-5130 menu reaches the catalog unit`() {
        assertEquals("9550", AnalyzerUnits.convert("95.5", "10*2/uL", "/cumm"))
        assertEquals("9.55", AnalyzerUnits.convert("9.55", "/nL", "10^9/L"))
        assertEquals("381000", AnalyzerUnits.convert("381", "/nL", "cells/cumm"))
        assertEquals("4.51", AnalyzerUnits.convert("451", "10*4/uL", "mill/cumm"))
        assertEquals("4510000", AnalyzerUnits.convert("451", "10*4/µL", "/cumm"))
        assertEquals("4.51", AnalyzerUnits.convert("4.51", "/pL", "million/cumm"))
        assertEquals("9.55", AnalyzerUnits.convert("9.55", "10*3/mm3", "10^9/L"))
        assertEquals("30", AnalyzerUnits.convert("0.4654", "fmol", "pg"))
        assertEquals("0.47", AnalyzerUnits.convert("30", "pg", "fmol"))
    }

    @Test
    fun `needsConversion is true only for a pair no family can bridge`() {
        assertTrue(AnalyzerUnits.needsConversion("bogus/L", "g/dL"))
        assertTrue(AnalyzerUnits.needsConversion("g/L", "/cumm"), "a mass unit into a count unit")
        assertFalse(AnalyzerUnits.needsConversion("10*9/L", "/cumm"))
        assertFalse(AnalyzerUnits.needsConversion("g/L", "g/dL"))
        assertFalse(AnalyzerUnits.needsConversion("fL", "fl"), "same unit, different case")
        assertFalse(AnalyzerUnits.needsConversion(null, "g/dL"))
        assertFalse(AnalyzerUnits.needsConversion("g/L", ""))
    }

    @Test
    fun `the decoder believes only histogram-shaped payloads`() {
        val b64 = { bytes: ByteArray -> Base64.Default.encode(bytes) }
        // Meta length as ELEMENT WIDTH: exactly 4 × 256 bytes → 256 × Int32-LE.
        val wide = ByteArray(1024) { i -> if (i % 4 == 0) (i / 4).toByte() else 0 }
        assertEquals(List(256) { it.toDouble() }, MindrayBc5x.decodeHistogram(b64(wide), 4))
        // Meta length as PREFIX: 4 + 256 bytes → 256 byte-points.
        assertEquals(256, MindrayBc5x.decodeHistogram(b64(ByteArray(260)), 4).size)
        // 1 + 256 and 2 + 512 — the shapes in the protocol sample.
        assertEquals(256, MindrayBc5x.decodeHistogram(b64(ByteArray(257)), 1).size)
        assertEquals(256, MindrayBc5x.decodeHistogram(b64(ByteArray(514)), 2).size)
        // Not a channel count anyone uses → nothing, rather than noise.
        assertTrue(MindrayBc5x.decodeHistogram(b64(ByteArray(100)), 0).isEmpty())
        assertTrue(MindrayBc5x.decodeHistogram(b64(ByteArray(300)), 0).isEmpty())
        assertTrue(MindrayBc5x.decodeHistogram("not base64 %%%", 0).isEmpty())
        assertTrue(MindrayBc5x.decodeHistogram("", 1).isEmpty())
    }
}
