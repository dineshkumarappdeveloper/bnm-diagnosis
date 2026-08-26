package com.bnm.diagnosis.report

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Turns what the approver drew on the signature pad into the base64 PNG that
 * `staff.signature_png` carries.
 *
 * Deliberately Compose-free (plain float pairs, not `Offset`) so it can be
 * exercised without a UI, and so the `report` package stays a pure rendering
 * package. The pad hands strokes in NORMALISED coordinates — 0..1 across the
 * pad, whatever size it happened to be drawn at — so a signature captured on a
 * phone and one captured on a 27" lab monitor export identically.
 */
object SignatureInk {

    /** Export resolution. ~24 px/mm when printed at the report's 40 mm width —
     *  crisp on paper, and only ~36 KB of 1-bit PNG for a row that has to sync. */
    const val EXPORT_W = 960
    const val EXPORT_H = 300

    /** Pen half-width in export pixels. 5 gives a ~10 px nib, which reads as a
     *  medium ballpoint at print size. */
    private const val NIB_RADIUS = 5

    /**
     * Rasterise [strokes] and encode. Returns null for an empty pad (nothing
     * drawn, or only stray taps) — the caller then stores no signature rather
     * than a blank image that would print as an empty smudge.
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun toPngBase64(strokes: List<List<Pair<Float, Float>>>): String? {
        val ink = rasterise(strokes) ?: return null
        val png = PngWriter.grayscale1Bit(EXPORT_W, EXPORT_H) { x, y -> ink[y * EXPORT_W + x] }
        if (png.isEmpty()) return null
        return Base64.Default.encode(png)
    }

    /**
     * Stamp a round nib along every segment into an [EXPORT_W] x [EXPORT_H] ink
     * mask, or null when nothing was drawn.
     *
     * Stamping (walk the segment, fill a disc at each step) rather than testing
     * every pixel against every segment: the mask is 288k pixels and a busy
     * signature is hundreds of segments, so the per-pixel form would be tens of
     * millions of distance tests for identical output.
     */
    private fun rasterise(strokes: List<List<Pair<Float, Float>>>): BooleanArray? {
        val mask = BooleanArray(EXPORT_W * EXPORT_H)
        var inked = false
        for (stroke in strokes) {
            if (stroke.isEmpty()) continue
            if (stroke.size == 1) {
                // A dot — a full stop or a tittle. Still ink.
                stamp(mask, px(stroke[0].first, EXPORT_W), px(stroke[0].second, EXPORT_H))
                inked = true
                continue
            }
            for (i in 1 until stroke.size) {
                val x0 = px(stroke[i - 1].first, EXPORT_W)
                val y0 = px(stroke[i - 1].second, EXPORT_H)
                val x1 = px(stroke[i].first, EXPORT_W)
                val y1 = px(stroke[i].second, EXPORT_H)
                val dx = (x1 - x0).toFloat()
                val dy = (y1 - y0).toFloat()
                val steps = max(1, sqrt(dx * dx + dy * dy).roundToInt())
                for (s in 0..steps) {
                    val t = s.toFloat() / steps
                    stamp(mask, (x0 + dx * t).roundToInt(), (y0 + dy * t).roundToInt())
                }
                inked = true
            }
        }
        return if (inked) mask else null
    }

    /** Normalised 0..1 → pixel index, clamped so a stray gesture outside the
     *  pad cannot write past the edge of the mask. */
    private fun px(v: Float, extent: Int): Int =
        (v * extent).roundToInt().coerceIn(0, extent - 1)

    private fun stamp(mask: BooleanArray, cx: Int, cy: Int) {
        val r = NIB_RADIUS
        val rr = r * r
        for (dy in -r..r) {
            val y = cy + dy
            if (y < 0 || y >= EXPORT_H) continue
            for (dx in -r..r) {
                val x = cx + dx
                if (x < 0 || x >= EXPORT_W) continue
                if (dx * dx + dy * dy <= rr) mask[y * EXPORT_W + x] = true
            }
        }
    }
}
