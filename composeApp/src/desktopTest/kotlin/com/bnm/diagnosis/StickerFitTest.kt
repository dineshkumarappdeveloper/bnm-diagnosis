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
        val esc = StickerRender.escPos(s, StickerSpec(38, 25))
        val gsW = esc.indices.firstOrNull { i -> esc[i] == 0x1D.toByte() && esc[i + 1] == 'w'.code.toByte() }
        assertTrue(gsW != null && esc[gsW + 2] == 2.toByte(), "ESC/POS module width must be 2")
    }
}
