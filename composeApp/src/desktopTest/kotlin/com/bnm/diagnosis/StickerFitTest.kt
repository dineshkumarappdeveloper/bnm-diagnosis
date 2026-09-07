package com.bnm.diagnosis

import com.bnm.diagnosis.print.StickerRender
import com.bnm.diagnosis.print.StickerSpec
import com.bnm.diagnosis.print.sampleSticker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The barcode's physical floor. A 1-dot module (0.125 mm) is below what desk
 * scanners read, so the renderer never shrinks to it — narrow stock is refused
 * in Settings via [StickerRender.minWidthMm] instead.
 */
class StickerFitTest {

    @Test
    fun `minimum stock width follows the accession length`() {
        assertEquals(47, StickerRender.minWidthMm(12)) // ACC-S1-00042
        assertEquals(50, StickerRender.minWidthMm(13)) // ACC-S12-00042
        assertTrue(StickerSpec.PRESETS.all { it.widthMm >= StickerRender.minWidthMm(13) }, "every preset must carry a 13-char accession")
    }

    @Test
    fun `bars never drop below two dots, even on stock that is too narrow`() {
        val s = listOf(sampleSticker("Lab"))
        val narrow = StickerRender.tspl(s, StickerSpec(38, 25))
        val barcode = narrow.lines().first { it.startsWith("BARCODE") }
        assertTrue(",2,2,\"ACC-S1-00042\"" in barcode, "narrow/wide must stay 2 dots: $barcode")
        // Pushed to the quiet zone, not the text margin, and never negative.
        assertTrue(barcode.startsWith("BARCODE ${StickerRender.QUIET_ZONE_DOTS},"), barcode)
        val fifty = StickerRender.tspl(s, StickerSpec(50, 25)).lines().first { it.startsWith("BARCODE") }
        assertTrue(fifty.startsWith("BARCODE 33,"), "centred on 50 mm stock: $fifty")
        assertTrue("^BY2," in StickerRender.zpl(s, StickerSpec(38, 25)))
        // ESC/POS draws the bars itself at 2 dots per module, centred in the label.
        val raster = StickerRender.barcodeRaster("ACC-S1-00042", 400, 40)
        val bytesPerRow = raster[4].toInt() and 0xFF
        assertEquals(50, bytesPerRow)
        val firstRow = raster.copyOfRange(8, 8 + bytesPerRow)
        val bits = firstRow.flatMap { byte -> (7 downTo 0).map { (byte.toInt() shr it) and 1 } }
        val first = bits.indexOf(1); val last = bits.lastIndexOf(1)
        assertEquals(33, first, "bars start at the quiet-zone-centred x of 50 mm stock")
        assertTrue(last - first + 1 == 167 * 2, "167 modules x 2 dots wide: ${last - first + 1}")
    }
}
