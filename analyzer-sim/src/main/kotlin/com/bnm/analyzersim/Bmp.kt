package com.bnm.analyzersim

import kotlin.math.roundToInt

/**
 * The DIFF scattergram, as an uncompressed 24-bit BMP.
 *
 * A BC-5130 sends its scattergram as a base64 bitmap inside an ED OBX, and it
 * is tens of kilobytes — far bigger than everything else in the message put
 * together. That size is the point: it is what made the app's old 512 KB
 * assembler cap drop messages mid-frame, so a commissioning rehearsal that
 * never sends one has not tested the link the lab will actually run.
 *
 * Written by hand rather than through ImageIO: no AWT, no headless surprises
 * on a lab PC, and the byte layout stays visible.
 */
object Bmp {

    private const val HEADER_BYTES = 54

    /**
     * A five-cluster differential scatterplot sized [size]x[size]. Cluster
     * populations follow the sample's own differential, so a neutrophilia
     * looks like one.
     */
    fun scattergram(cbc: Cbc, seed: Long, size: Int = 100): ByteArray {
        require(size > 0 && size * 3 % 4 == 0) { "width must keep BMP rows 4-byte aligned" }
        val rng = Rng(seed xor 0x424D5001L)
        val pixels = IntArray(size * size)                 // 0xRRGGBB, black background

        // x = side scatter (granularity), y = fluorescence (nucleic acid) — the
        // two axes a 5-part analyzer separates leucocytes on.
        fun cluster(cx: Double, cy: Double, spread: Double, percent: Double, colour: Int) {
            val dots = (percent * 14).roundToInt().coerceIn(0, size * size / 4)
            repeat(dots) {
                val x = (cx + rng.jitter(spread)).roundToInt()
                val y = (cy + rng.jitter(spread)).roundToInt()
                if (x in 0 until size && y in 0 until size) pixels[y * size + x] = colour
            }
        }
        val u = size / 100.0
        cluster(28 * u, 70 * u, 9 * u, cbc.diff.lym, 0x3AA0FF)    // lymphocytes — blue
        cluster(46 * u, 52 * u, 8 * u, cbc.diff.mon, 0x35C46A)    // monocytes — green
        cluster(70 * u, 34 * u, 11 * u, cbc.diff.neu, 0xB06CFF)   // neutrophils — violet
        cluster(84 * u, 60 * u, 7 * u, cbc.diff.eos, 0xFF5A46)    // eosinophils — red
        cluster(20 * u, 30 * u, 5 * u, cbc.diff.bas, 0xFFD23A)    // basophils — amber

        return encode(pixels, size, size)
    }

    /** BITMAPFILEHEADER + BITMAPINFOHEADER + bottom-up BGR rows. */
    private fun encode(pixels: IntArray, width: Int, height: Int): ByteArray {
        val rowBytes = width * 3
        val out = ByteArray(HEADER_BYTES + rowBytes * height)
        var at = 0
        fun u8(v: Int) { out[at++] = (v and 0xFF).toByte() }
        fun u16(v: Int) { u8(v); u8(v shr 8) }
        fun u32(v: Int) { u16(v); u16(v shr 16) }

        u8('B'.code); u8('M'.code)
        u32(out.size)
        u32(0)                       // reserved
        u32(HEADER_BYTES)            // pixel data offset
        u32(40)                      // BITMAPINFOHEADER size
        u32(width); u32(height)
        u16(1)                       // planes
        u16(24)                      // bits per pixel
        u32(0)                       // BI_RGB, no compression
        u32(rowBytes * height)
        u32(2835); u32(2835)         // 72 dpi in pixels per metre
        u32(0); u32(0)               // palette: none used, none important

        // BMP rows run bottom to top.
        for (y in height - 1 downTo 0) {
            for (x in 0 until width) {
                val rgb = pixels[y * width + x]
                u8(rgb); u8(rgb shr 8); u8(rgb shr 16)   // BGR
            }
        }
        return out
    }
}
