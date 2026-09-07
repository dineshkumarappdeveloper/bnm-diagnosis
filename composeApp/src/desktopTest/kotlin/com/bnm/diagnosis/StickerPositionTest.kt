package com.bnm.diagnosis

import com.bnm.diagnosis.print.LabelLanguage
import com.bnm.diagnosis.print.StickerRender
import com.bnm.diagnosis.print.StickerSpec
import com.bnm.diagnosis.print.sampleSticker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "It prints, but lines land on the next sticker" — the field report that
 * shaped these rules: one header per job, a bottom keep-out, and the
 * calibrate / nudge / rotate knobs.
 */
class StickerPositionTest {

    private val one = listOf(sampleSticker("Lab"))

    private fun tsplY(line: String): Int = line.substringAfter(',').substringBefore(',').trim().toInt()

    @Test
    fun `the TSPL job has one header with the printer's stored offsets reset, then CLS-PRINT per label`() {
        val job = StickerRender.tspl(one + one, StickerSpec(50, 25), copies = 1)
        val lines = job.lines()
        val header = lines.takeWhile { it != "CLS" }
        assertEquals(listOf("SIZE 50 mm,25 mm", "GAP 2 mm,0 mm", "DIRECTION 1", "REFERENCE 0,0", "OFFSET 0 mm", "SHIFT 0"), header)
        assertEquals(1, lines.count { it.startsWith("GAP ") }, "GAP must never be re-issued mid-job")
        assertEquals(2, lines.count { it == "CLS" })
        assertEquals(2, lines.count { it == "PRINT 1,1" })
    }

    @Test
    fun `every preset keeps the last line 2_5 mm clear of the bottom edge`() {
        for (spec in StickerSpec.PRESETS) {
            val h = spec.heightMm * 8
            val job = StickerRender.tspl(one, spec)
            val texts = job.lines().filter { it.startsWith("TEXT ") }
            val footer = texts.last()
            val y = tsplY(footer)
            val font = footer.substringAfter("\"").substringBefore("\"")
            val fontH = when (font) { "2" -> 20; "3" -> 24; "4" -> 32; else -> 32 }
            assertTrue(y + fontH <= h - 20, "${spec.widthMm}x${spec.heightMm}: footer ends at ${y + fontH} of $h")
        }
    }

    @Test
    fun `50x20 stock carries all four rows inside 160 dots`() {
        // The lab's actual roll (field photo, 2026-09-08). Laid out for 25 mm the
        // time line fell onto the next sticker; the compact tier must fit it.
        val job = StickerRender.tspl(one, StickerSpec(50, 20))
        val lines = job.lines()
        assertEquals(3, lines.count { it.startsWith("TEXT ") }, job)
        val bar = lines.first { it.startsWith("BARCODE ") }
        val barH = bar.split(",")[3].toInt()
        assertTrue(barH >= 35, "barcode must stay tall enough to scan: $barH")
        val footer = lines.filter { it.startsWith("TEXT ") }.last()
        assertTrue(tsplY(footer) + 20 <= 160 - 20, "footer must clear the 2.5 mm keep-out: $footer")
        assertTrue(tsplY(lines.first { it.startsWith("TEXT ") }) >= 4)
        assertTrue(StickerSpec(50, 20) in StickerSpec.PRESETS)
    }

    @Test
    fun `shift moves every coordinate and never goes negative`() {
        val base = StickerRender.tspl(one, StickerSpec(50, 25))
        val down = StickerRender.tspl(one, StickerSpec(50, 25, shiftYmm = 1.5f, shiftXmm = -0.5f))
        val by = base.lines().filter { it.startsWith("TEXT ") || it.startsWith("BARCODE ") }.map { tsplY(it) }
        val dy = down.lines().filter { it.startsWith("TEXT ") || it.startsWith("BARCODE ") }.map { tsplY(it) }
        assertEquals(by.map { it + 12 }, dy, "1.5 mm at 8 dots/mm = 12 dots down")
        val xs = down.lines().filter { it.startsWith("TEXT ") }.map { it.substringAfter(' ').substringBefore(',').toInt() }
        assertTrue(xs.all { it == 12 }, "16 - 4 dots: $xs")
        val up = StickerRender.tspl(one, StickerSpec(50, 25, shiftYmm = -10f))
        assertTrue(up.lines().filter { it.startsWith("TEXT ") }.all { tsplY(it) >= 0 }, "a huge upward shift clamps at the edge")
    }

    @Test
    fun `rotate flips the direction, 300 dpi scales the dots, mm stay mm`() {
        assertTrue("DIRECTION 0" in StickerRender.tspl(one, StickerSpec(50, 25, rotate = true)))
        assertTrue("^POI" in StickerRender.zpl(one, StickerSpec(50, 25, rotate = true)))
        assertTrue("^PON" in StickerRender.zpl(one, StickerSpec(50, 25)))
        val hi = StickerRender.zpl(one, StickerSpec(50, 25, dpi = 300))
        assertTrue("^PW600" in hi && "^LL300" in hi, hi)
        assertTrue("SIZE 50 mm,25 mm" in StickerRender.tspl(one, StickerSpec(50, 25, dpi = 300)))
        assertEquals(32, StickerRender.minWidthMm(12, 300))
    }

    @Test
    fun `calibration is the printer's own command, or nothing for a receipt roll`() {
        val t = StickerRender.calibrate(LabelLanguage.TSPL, StickerSpec(50, 25))!!.decodeToString()
        assertTrue(t.endsWith("GAPDETECT\r\n") && "SIZE 50 mm,25 mm" in t, t)
        assertEquals("~JC\n", StickerRender.calibrate(LabelLanguage.ZPL, StickerSpec(50, 25))!!.decodeToString())
        assertNull(StickerRender.calibrate(LabelLanguage.ESCPOS, StickerSpec(50, 25)))
    }
}
