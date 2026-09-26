package com.bnm.lab

import com.bnm.lab.instruments.MindrayBc5x
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Which histogram layouts can this decoder actually read?
 *
 * We do not have the BC-5130's bytes, and the vendor documents the envelope
 * only ("customized", manual 6.2) — but the lab's own sheet proves another LIS
 * reads them, so the layout is ordinary, not exotic. Rather than wait for a
 * sample and guess again, this enumerates the layouts a hematology analyzer
 * plausibly uses and reports which survive the round trip.
 *
 * It prints a matrix so a human can see the coverage, and FAILS on the
 * layouts we have decided must work. The value is in what it rules out: every
 * shape that decodes here is one the field cannot surprise us with.
 */
@OptIn(ExperimentalEncodingApi::class)
class HistogramLayoutMatrixTest {

    /** A recognisable curve: a single peak, so a misparse looks obviously wrong. */
    private fun channels(n: Int): IntArray = IntArray(n) { i ->
        val x = (i - n / 3).toDouble() / (n / 12.0)
        (1000 * kotlin.math.exp(-0.5 * x * x)).toInt()
    }

    private fun encode(
        counts: IntArray,
        width: Int,
        headerBytes: Int,
        bigEndian: Boolean,
    ): String {
        val out = ByteArray(headerBytes + counts.size * width)
        // A header of plausible junk, not zeros — zeros would let a decoder
        // that ignores the header accidentally look correct.
        for (i in 0 until headerBytes) out[i] = (0x40 + i).toByte()
        val scale = if (width == 1) 200.0 / (counts.max()) else 1.0
        counts.forEachIndexed { i, v0 ->
            val v = (v0 * scale).toInt()
            val capped = if (width == 1) v.coerceAtMost(255) else v
            for (b in 0 until width) {
                val shift = if (bigEndian) (width - 1 - b) * 8 else b * 8
                out[headerBytes + i * width + b] = ((capped shr shift) and 0xFF).toByte()
            }
        }
        return Base64.Default.encode(out)
    }

    private data class Layout(
        val channels: Int,
        val width: Int,
        val header: Int,
        val bigEndian: Boolean,
        /** What the analyzer puts in the "Meta Length" OBX for this shape. */
        val metaLength: Int,
    ) {
        override fun toString() =
            "$channels ch x ${width}B ${if (bigEndian) "BE" else "LE"}, header $header, meta $metaLength"
    }

    @Test
    fun `the layouts a real analyzer plausibly uses all decode`() {
        val layouts = buildList {
            // 256 channels is what the BC-5130 uses; the rest are the family.
            for (ch in listOf(128, 256)) {
                for (width in listOf(1, 2)) {
                    // No header, meta length reported as the byte width or as 0.
                    add(Layout(ch, width, header = 0, bigEndian = false, metaLength = 0))
                    add(Layout(ch, width, header = 0, bigEndian = false, metaLength = width))
                    // A leading header, meta length reporting its size.
                    for (h in listOf(2, 4, 8)) {
                        add(Layout(ch, width, header = h, bigEndian = false, metaLength = h))
                    }
                }
            }
            // Big-endian 16-bit, which some firmware uses.
            add(Layout(256, 2, header = 0, bigEndian = true, metaLength = 0))
            add(Layout(256, 2, header = 4, bigEndian = true, metaLength = 4))
        }

        val failures = ArrayList<String>()
        println("── histogram layout coverage ──")
        for (l in layouts) {
            val counts = channels(l.channels)
            val b64 = encode(counts, l.width, l.header, l.bigEndian)
            val points = MindrayBc5x.decodeHistogram(b64, l.metaLength)
            // Decoded is only useful if it has the right SHAPE: the right
            // number of channels and the peak in the right place. A reading
            // that returns 256 numbers from misaligned bytes is worse than
            // none, so the peak position is what is actually checked.
            val peakAt = points.indexOf(points.maxOrNull() ?: 0.0)
            val wantPeak = l.channels / 3
            val ok = points.size == l.channels && kotlin.math.abs(peakAt - wantPeak) <= 2
            println("  ${if (ok) "OK  " else "MISS"}  $l  → ${points.size} pts, peak $peakAt (want $wantPeak)")
            if (!ok) failures += "$l → ${points.size} pts, peak $peakAt"
        }

        assertTrue(
            failures.isEmpty(),
            "layouts a real analyzer could use that this decoder cannot read:\n" +
                failures.joinToString("\n"),
        )
    }
}
