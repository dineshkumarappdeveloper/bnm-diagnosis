package com.bnm.diagnosis

import com.bnm.diagnosis.print.StickerRender
import com.bnm.diagnosis.print.StickerSpec
import com.bnm.diagnosis.print.sampleSticker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A receipt printer with a label roll has no sensor, so the ONLY thing that
 * keeps stickers aligned is that every job advances the paper by a whole
 * number of pitches. This walks the byte stream the way the printer does and
 * adds up every feed.
 */
class StickerReceiptPitchTest {

    /** Total paper advance in dots: every ESC J n plus every raster's rows. */
    private fun advance(bytes: ByteArray): Int {
        var i = 0
        var total = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            when {
                b == 0x1B && i + 2 < bytes.size && bytes[i + 1] == 'J'.code.toByte() -> {
                    total += bytes[i + 2].toInt() and 0xFF; i += 3
                }
                b == 0x1D && i + 7 < bytes.size && bytes[i + 1] == 'v'.code.toByte() && bytes[i + 2] == '0'.code.toByte() -> {
                    val bytesPerRow = (bytes[i + 4].toInt() and 0xFF) or ((bytes[i + 5].toInt() and 0xFF) shl 8)
                    val rows = (bytes[i + 6].toInt() and 0xFF) or ((bytes[i + 7].toInt() and 0xFF) shl 8)
                    total += rows; i += 8 + bytesPerRow * rows
                }
                else -> i++
            }
        }
        return total
    }

    @Test
    fun `every job advances a whole number of pitches, whatever the shift`() {
        val pitch = (20 + 2) * 8
        for (shift in listOf(0f, 1.5f, -3f, 7f, -10f, 21.5f, -30f)) {
            val spec = StickerSpec(50, 20, gapMm = 2, shiftYmm = shift)
            val one = StickerRender.escPos(listOf(sampleSticker("Lab")), spec)
            assertEquals(2 * pitch, advance(one), "1 label + 1 blank at shift $shift")
            val three = StickerRender.escPos(List(3) { sampleSticker("Lab") }, spec)
            assertEquals(4 * pitch, advance(three), "3 labels + 1 blank at shift $shift")
            val copies = StickerRender.escPos(listOf(sampleSticker("Lab")), spec, copies = 2)
            assertEquals(3 * pitch, advance(copies), "2 copies + 1 blank at shift $shift")
        }
    }

    @Test
    fun `the shift moves the first feed and the last feed by the same amount, in opposite directions`() {
        fun feeds(bytes: ByteArray): List<Int> {
            val out = ArrayList<Int>()
            var i = 0
            while (i + 2 < bytes.size) {
                if (bytes[i] == 0x1B.toByte() && bytes[i + 1] == 'J'.code.toByte()) { out += bytes[i + 2].toInt() and 0xFF; i += 3 } else i++
            }
            return out
        }
        val pitch = 176
        val plain = feeds(StickerRender.escPos(listOf(sampleSticker("Lab")), StickerSpec(50, 20)))
        val shifted = feeds(StickerRender.escPos(listOf(sampleSticker("Lab")), StickerSpec(50, 20, shiftYmm = 2f)))
        assertEquals(0, plain.first()); assertEquals(pitch, plain.last())
        assertEquals(16, shifted.first(), "2 mm = 16 dots fed before the first label")
        assertEquals(pitch - 16, shifted.last(), "and un-fed after the last")
        assertEquals(plain.drop(1).dropLast(1), shifted.drop(1).dropLast(1), "the label block itself is unchanged")
    }

    @Test
    fun `the pitch never depends on the accession being encodable`() {
        val good = advance(StickerRender.escPos(listOf(sampleSticker("Lab")), StickerSpec(50, 20)))
        val bad = advance(StickerRender.escPos(listOf(sampleSticker("Lab").copy(accession = "")), StickerSpec(50, 20)))
        assertEquals(good, bad, "a blank raster of the same height keeps the phase")
        assertTrue(good == 2 * 176)
    }
}
