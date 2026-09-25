package com.bnm.analyzersim

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/**
 * The measured curves an analyzer sends alongside the numbers.
 *
 * They are not decoration: BNM Lab stores them in `lab_result_graphs` and
 * prints them on the report, so commissioning has to prove they survive the
 * link intact — and a flat line or a spike train would not show a misaligned
 * byte layout the way a real three-humped WBC curve does.
 *
 * Every curve is 128 channels of 0..255. That range is not arbitrary: the
 * Mindray driver decodes an ED payload of `metaLength + 128` bytes as one
 * unsigned byte per channel (see MindrayBc5x.decodeHistogram), so a channel
 * that does not fit in a byte could not survive the round trip we are here to
 * prove.
 */
object Histograms {

    const val CHANNELS = 128
    const val MAX_COUNT = 255

    /** Where each curve's x axis ends, so a channel index means something. */
    const val WBC_FL_FULL_SCALE = 300.0
    const val RBC_FL_FULL_SCALE = 250.0
    const val PLT_FL_FULL_SCALE = 30.0

    /**
     * One curve plus the discriminator channels the analyzer reports with it.
     * [lines] are channel indices, in the order the vendor's OBX rows list them.
     */
    data class Curve(val points: List<Int>, val lines: List<Int>)

    /**
     * WBC: three populations — lymphocytes, the "mid" cluster (monocytes,
     * eosinophils, basophils) and granulocytes — with their areas in the
     * proportions the differential says. The discriminators are then READ OFF
     * the curve (the valley between neighbouring peaks), exactly as the
     * analyzer places them, so a stretched or shifted curve moves them too.
     */
    fun wbc(cbc: Cbc, seed: Long): Curve {
        val rng = Rng(seed xor 0x57424331L)
        val lymPeak = 26.0 + rng.jitter(2.0)
        val midPeak = 52.0 + rng.jitter(3.0)
        val granPeak = 84.0 + rng.jitter(4.0)
        val raw = DoubleArray(CHANNELS) { ch ->
            val x = ch.toDouble()
            gaussian(x, lymPeak, 6.5) * cbc.diff.lym +
                gaussian(x, midPeak, 8.0) * cbc.diff.mid +
                gaussian(x, granPeak, 11.5) * cbc.diff.gran
        }
        // Below the lysing threshold the analyzer counts nothing — a curve that
        // starts at channel 0 is the signature of a made-up histogram.
        for (ch in 0 until 12) raw[ch] = 0.0
        val points = scale(raw, rng)
        val lymLeft = firstAbove(points, from = 10, fractionOfPeak = 0.06)
        val lymMid = valley(points, lymPeak.toInt(), midPeak.toInt())
        val midGran = valley(points, midPeak.toInt(), granPeak.toInt())
        val granRight = lastAbove(points, fractionOfPeak = 0.04)
        return Curve(points, listOf(lymLeft, lymMid, midGran, granRight))
    }

    /**
     * RBC: one near-Gaussian population centred on MCV, its width set by
     * RDW-SD. A wide anaemic curve and a tight normal one look different on
     * the report, which is the only way to tell the histogram is really the
     * patient's and not a stock picture.
     */
    fun rbc(cbc: Cbc, seed: Long): Curve {
        val rng = Rng(seed xor 0x52424332L)
        val perFl = CHANNELS / RBC_FL_FULL_SCALE
        val centre = cbc.mcv * perFl
        val sigma = max(2.0, cbc.rdwSd / 3.45 * perFl)     // RDW-SD is ~3.45 SD wide
        val raw = DoubleArray(CHANNELS) { ch -> gaussian(ch.toDouble(), centre, sigma) }
        for (ch in 0 until 10) raw[ch] = 0.0               // debris below ~20 fL is gated out
        val points = scale(raw, rng)
        return Curve(points, listOf(firstAbove(points, from = 8, fractionOfPeak = 0.05),
            lastAbove(points, fractionOfPeak = 0.05)))
    }

    /**
     * PLT: log-normal, so the peak sits left of the mean and the tail runs
     * right into the small-red-cell region — the shape that makes MPV larger
     * than the modal volume, and the shape a linear curve would get wrong.
     */
    fun plt(cbc: Cbc, seed: Long): Curve {
        val rng = Rng(seed xor 0x504C5433L)
        val perFl = CHANNELS / PLT_FL_FULL_SCALE
        val shape = 0.42                                   // sigma of ln(volume); typical for platelets
        // Choose the log-normal whose MEAN is the reported MPV: mean = exp(mu + s²/2).
        val mu = ln(cbc.mpv) - shape * shape / 2.0
        val raw = DoubleArray(CHANNELS) { ch ->
            val fl = ch / perFl
            if (fl < 0.7) 0.0 else logNormal(fl, mu, shape)
        }
        val points = scale(raw, rng)
        return Curve(points, listOf(firstAbove(points, from = 1, fractionOfPeak = 0.05),
            lastAbove(points, fractionOfPeak = 0.03)))
    }

    // ── shaping helpers ──

    private fun gaussian(x: Double, centre: Double, sigma: Double): Double {
        val z = (x - centre) / sigma
        return exp(-0.5 * z * z)
    }

    private fun logNormal(x: Double, mu: Double, sigma: Double): Double {
        val z = (ln(x) - mu) / sigma
        return exp(-0.5 * z * z) / x
    }

    /**
     * Normalise to a realistic peak height and sprinkle counting noise. A
     * perfectly smooth curve is the other giveaway of a fake frame, and the
     * noise is seeded so the same sample always draws the same picture.
     */
    private fun scale(raw: DoubleArray, rng: Rng): List<Int> {
        val peak = raw.max()
        if (peak <= 0.0) return List(CHANNELS) { 0 }
        val target = 210.0 + rng.jitter(25.0)
        return raw.map { v ->
            val height = v / peak * target
            val noisy = if (height <= 0.0) 0.0 else height + rng.jitter(height * 0.06 + 1.2)
            noisy.toInt().coerceIn(0, MAX_COUNT)
        }
    }

    private fun firstAbove(points: List<Int>, from: Int, fractionOfPeak: Double): Int {
        val threshold = points.max() * fractionOfPeak
        return (from until points.size).firstOrNull { points[it] > threshold } ?: from
    }

    private fun lastAbove(points: List<Int>, fractionOfPeak: Double): Int {
        val threshold = points.max() * fractionOfPeak
        return points.indices.lastOrNull { points[it] > threshold } ?: (points.size - 1)
    }

    /** The lowest channel strictly between two peaks — where the analyzer cuts. */
    private fun valley(points: List<Int>, leftPeak: Int, rightPeak: Int): Int {
        val lo = (leftPeak + 1).coerceIn(1, points.size - 2)
        val hi = (rightPeak - 1).coerceIn(lo, points.size - 1)
        var best = lo
        for (ch in lo..hi) if (points[ch] < points[best]) best = ch
        return best
    }
}
