package com.bnm.diagnosis

import com.bnm.diagnosis.print.LabelLanguage
import com.bnm.diagnosis.print.SIDE_MARGIN
import com.bnm.diagnosis.print.SampleSticker
import com.bnm.diagnosis.print.StickerRender
import com.bnm.diagnosis.print.StickerSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The tube sticker is what the analyzer scans — a wrong SIZE/GAP mis-feeds the
 * whole roll, an un-sanitised name breaks the TSPL command, and an over-wide
 * line prints off the edge. These pin the three printer-language outputs to
 * the contract so a layout tweak can't silently regress any of them.
 */
class SampleStickerTest {

    private val spec = StickerSpec(50, 25)
    private val sticker = SampleSticker(
        accession = "ACC-S1-00042",
        patientName = "Kavitha Subramañian",
        ageSex = "42 y / F",
        sampleType = "SERUM",
        registeredAt = "07/09/26 09:12",
        labName = "BNM Diagnostics Pvt Ltd, Chennai",
    )

    @Test
    fun tsplCarriesStockBarcodeNameAndCopies() {
        val out = StickerRender.tspl(listOf(sticker), spec, copies = 2)
        assertTrue(out.contains("SIZE 50 mm,25 mm"), out)
        assertTrue(out.contains("GAP 2 mm,0 mm"), out)
        val barcode = out.lines().first { it.startsWith("BARCODE ") }
        assertTrue(barcode.contains("\"128\""), barcode)
        assertTrue(barcode.endsWith("\"ACC-S1-00042\""), barcode)
        assertTrue(out.contains(StickerRender.sanitize(sticker.patientName)), out)
        assertTrue(out.contains("Kavitha Subramanian"), out)
        assertTrue(out.contains("PRINT 1,2"), out)
    }

    @Test
    fun zplCarriesDimensionsBarcodeAndQuantity() {
        val out = StickerRender.zpl(listOf(sticker), spec, copies = 2)
        assertTrue(out.contains("^PW400"), out)
        assertTrue(out.contains("^LL200"), out)
        assertTrue(out.contains("^BC"), out)
        assertTrue(out.contains("^FDACC-S1-00042^FS"), out)
        assertTrue(out.contains("^PQ2"), out)
    }

    @Test
    fun escPosCarriesCode128AccessionAndName() {
        val bytes = StickerRender.escPos(listOf(sticker), spec)
        // The barcode is a GS v 0 raster (its advance is its row count — the
        // pitch arithmetic depends on that); GS k is deliberately not used.
        assertTrue(bytes.indexOf(byteArrayOf(0x1D, 'v'.code.toByte(), '0'.code.toByte(), 0)) >= 0, "GS v 0 raster missing")
        assertTrue(bytes.indexOf(byteArrayOf(0x1D, 'k'.code.toByte())) < 0, "GS k must not be used")
        assertTrue(bytes.indexOf("ACC-S1-00042".encodeToByteArray()) >= 0, "accession missing (printed as text under the raster)")
        assertTrue(bytes.indexOf("Kavitha Subramanian".encodeToByteArray()) >= 0, "name missing")
        assertTrue(bytes.indexOf(byteArrayOf(0x1D, 'V'.code.toByte())) < 0, "a label roll is never cut")
        // Text stays ASCII; the raster bitmap and the GS P motion-unit
        // arguments are binary by nature and are skipped here.
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            when {
                b == 0x1D && i + 7 < bytes.size && bytes[i + 1] == 'v'.code.toByte() -> {
                    val bpr = (bytes[i + 4].toInt() and 0xFF) or ((bytes[i + 5].toInt() and 0xFF) shl 8)
                    val rows = (bytes[i + 6].toInt() and 0xFF) or ((bytes[i + 7].toInt() and 0xFF) shl 8)
                    i += 8 + bpr * rows
                }
                // ESC J n: a feed of up to 255 dots — binary by nature.
                b == 0x1B && i + 2 < bytes.size && bytes[i + 1] == 'J'.code.toByte() -> i += 3
                // GS P (motion units), GS L (left margin), GS W (print width): two binary args each.
                b == 0x1D && i + 3 < bytes.size && bytes[i + 1].toInt().toChar() in "PLW" -> i += 4
                else -> { assertTrue(b < 0x80, "non-ASCII byte $b at $i in ESC/POS text"); i++ }
            }
        }
    }

    @Test
    fun renderEmitsOneBlockPerSticker() {
        val two = listOf(sticker, sticker.copy(accession = "ACC-S1-00043", patientName = "Ravi Kumar"))
        val tspl = StickerRender.render(LabelLanguage.TSPL, two, spec, copies = 2).decodeToString()
        // ONE job header, then one CLS…PRINT block per sticker.
        assertEquals(1, tspl.lines().count { it.startsWith("SIZE ") }, tspl)
        assertEquals(2, tspl.lines().count { it == "CLS" }, tspl)
        assertEquals(2, tspl.lines().count { it == "PRINT 1,2" }, tspl)
        val zpl = StickerRender.render(LabelLanguage.ZPL, two, spec, copies = 2).decodeToString()
        assertEquals(2, zpl.lines().count { it == "^XA" }, zpl)
        assertEquals(2, zpl.lines().count { it == "^PQ2" }, zpl)
        // ESC/POS has no quantity command: 2 stickers x 2 copies = 4 label blocks (4 rasters).
        val esc = StickerRender.render(LabelLanguage.ESCPOS, two, spec, copies = 2)
        assertEquals(4, esc.countOf(byteArrayOf(0x1D, 'v'.code.toByte(), '0'.code.toByte(), 0)))
    }

    @Test
    fun longNameIsTruncatedToLabelWidth() {
        val long = sticker.copy(patientName = "Kavitha Subramanian ".repeat(3).trim().padEnd(60, 'X'))
        assertEquals(60, long.patientName.length)
        val contentW = 50 * 8 - 2 * SIDE_MARGIN
        for (s in StickerSpec.PRESETS) {
            val cw = s.widthMm * 8 - 2 * SIDE_MARGIN
            for (line in StickerRender.tspl(listOf(long), s).lines().filter { it.startsWith("TEXT ") }) {
                val m = TSPL_TEXT.matchEntire(line) ?: error("unparseable TSPL line: $line")
                val (font, xmul, text) = m.destructured
                val width = text.length * StickerRender.tsplGlyphWidth(font) * xmul.toInt()
                assertTrue(width <= cw, "TSPL line too wide for ${s.widthMm}x${s.heightMm}: $line")
            }
            for (line in StickerRender.zpl(listOf(long), s).lines().filter { it.contains("^A0N") }) {
                val m = ZPL_TEXT.matchEntire(line) ?: error("unparseable ZPL line: $line")
                val (w, text) = m.destructured
                val width = text.length * StickerRender.zplGlyphWidth(w.toInt())
                assertTrue(width <= cw, "ZPL line too wide for ${s.widthMm}x${s.heightMm}: $line")
            }
        }
        // And the name really was shortened rather than the label widened.
        val nameLine = StickerRender.tspl(listOf(long), spec).lines().first { it.startsWith("TEXT ") }
        assertTrue(!nameLine.contains(long.patientName), nameLine)
        assertTrue(contentW < 60 * 8, "a 60-char name cannot fit even in the narrowest font")
    }

    @Test
    fun sanitizeProducesPrinterSafeAscii() {
        val out = StickerRender.sanitize("Kavitha Subramañian · ₹")
        assertEquals("Kavitha Subramanian - Rs", out)
        assertTrue(out.all { it.code in 32..126 }, out)
        // Delimiters can never leak into a TSPL/ZPL command.
        assertEquals("O'Brien - x - y", StickerRender.sanitize("O\"Brien ^ x ~ y"))
        assertEquals("Jose Munoz", StickerRender.sanitize("José Muñoz"))
    }

    @Test
    fun languageSlugAndDefaultSpec() {
        assertEquals(LabelLanguage.TSPL, LabelLanguage.fromSlug(null))
        assertEquals(LabelLanguage.TSPL, LabelLanguage.fromSlug("bogus"))
        assertEquals(LabelLanguage.ZPL, LabelLanguage.fromSlug("zpl"))
        assertEquals(LabelLanguage.ESCPOS, LabelLanguage.fromSlug("ESCPOS"))
        assertEquals(StickerSpec(50, 25, 2), StickerSpec.DEFAULT)
        assertEquals(StickerSpec.DEFAULT, StickerSpec.PRESETS.first())
    }

    private companion object {
        val TSPL_TEXT = Regex("""TEXT \d+,\d+,"(\d)",0,(\d+),\d+,"(.*)"""")
        val ZPL_TEXT = Regex("""\^FO\d+,\d+\^A0N,\d+,(\d+)\^FD(.*)\^FS""")

        fun ByteArray.indexOf(needle: ByteArray): Int {
            if (needle.isEmpty()) return 0
            outer@ for (i in 0..size - needle.size) {
                for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
                return i
            }
            return -1
        }

        fun ByteArray.countOf(needle: ByteArray): Int {
            var n = 0
            var from = 0
            while (from <= size - needle.size) {
                val i = copyOfRange(from, size).indexOf(needle)
                if (i < 0) break
                n++
                from += i + needle.size
            }
            return n
        }
    }
}
