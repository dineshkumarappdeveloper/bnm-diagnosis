package com.bnm.analyzersim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The curves have to be curves. A flat line or a spike train would still
 * travel down the wire and still decode — and would prove nothing about
 * whether the report draws the patient's own histogram.
 */
class HistogramsTest {

    private val seeds = listOf(1L, 8L, 77L, -3L)

    @Test
    fun `every curve is 128 channels that fit in a byte`() {
        for (profile in Profile.entries) for (seed in seeds) {
            val c = cbcFor(profile, seed)
            for ((kind, curve) in curves(c, seed)) {
                assertEquals(Histograms.CHANNELS, curve.points.size, "$kind $profile/$seed")
                assertTrue(curve.points.all { it in 0..Histograms.MAX_COUNT },
                    "$kind $profile/$seed went outside 0..255")
            }
        }
    }

    @Test
    fun `the WBC curve has three populations in ascending channel order`() {
        for (seed in seeds) {
            val c = cbcFor(Profile.NORMAL, seed)
            val lines = Histograms.wbc(c, seed).lines
            assertEquals(4, lines.size, "lym-left, lym-mid, mid-gran, gran-right")
            assertTrue(lines.zipWithNext().all { (a, b) -> a < b },
                "discriminators must climb across the x axis: $lines")
        }
    }

    @Test
    fun `the WBC discriminators sit in the valleys, not on the peaks`() {
        val c = cbcFor(Profile.NORMAL, 4L)
        val curve = Histograms.wbc(c, 4L)
        val (_, lymMid, midGran, _) = curve.lines.let { listOf(it[0], it[1], it[2], it[3]) }
        val peak = curve.points.max()
        assertTrue(curve.points[lymMid] < peak / 2, "the lym/mid cut landed on a population")
        assertTrue(curve.points[midGran] < peak / 2, "the mid/gran cut landed on a population")
    }

    @Test
    fun `the RBC curve is centred where MCV says it should be`() {
        for (profile in listOf(Profile.NORMAL, Profile.ANAEMIA)) for (seed in seeds) {
            val c = cbcFor(profile, seed)
            val points = Histograms.rbc(c, seed).points
            val peakChannel = points.indexOf(points.max())
            val expected = c.mcv / Histograms.RBC_FL_FULL_SCALE * Histograms.CHANNELS
            assertTrue(kotlin.math.abs(peakChannel - expected) < 4.0,
                "$profile/$seed: MCV ${c.mcv} fL should peak near channel $expected, peaked at $peakChannel")
        }
    }

    @Test
    fun `a microcytic sample really does sit left of a normal one`() {
        val normal = cbcFor(Profile.NORMAL, 3L)
        val anaemic = cbcFor(Profile.ANAEMIA, 3L)
        val normalPeak = Histograms.rbc(normal, 3L).points.let { it.indexOf(it.max()) }
        val anaemicPeak = Histograms.rbc(anaemic, 3L).points.let { it.indexOf(it.max()) }
        assertTrue(anaemicPeak < normalPeak,
            "MCV ${anaemic.mcv} peaked at $anaemicPeak, MCV ${normal.mcv} at $normalPeak")
    }

    @Test
    fun `the PLT curve is left-skewed — the mode sits below the reported MPV`() {
        for (seed in seeds) {
            val c = cbcFor(Profile.NORMAL, seed)
            val points = Histograms.plt(c, seed).points
            val modeFl = points.indexOf(points.max()) / (Histograms.CHANNELS / Histograms.PLT_FL_FULL_SCALE)
            assertTrue(modeFl < c.mpv,
                "seed $seed: mode ${fmt(modeFl, 1)} fL is not below MPV ${c.mpv} — the curve is not skewed")
            assertTrue(points.takeLast(20).sum() < points.take(60).sum(), "the tail outweighs the body")
        }
    }

    @Test
    fun `curves start at zero below the counting threshold`() {
        val c = cbcFor(Profile.NORMAL, 6L)
        assertTrue(Histograms.wbc(c, 6L).points.take(10).all { it == 0 }, "WBC counted below the lyse threshold")
        assertTrue(Histograms.rbc(c, 6L).points.take(8).all { it == 0 }, "RBC counted debris")
    }

    @Test
    fun `the same seed draws the same curve`() {
        val c = cbcFor(Profile.NORMAL, 12L)
        assertEquals(Histograms.wbc(c, 12L).points, Histograms.wbc(c, 12L).points)
        assertTrue(Histograms.wbc(c, 12L).points != Histograms.wbc(c, 13L).points)
    }

    private fun curves(c: Cbc, seed: Long) = listOf(
        "wbc" to Histograms.wbc(c, seed),
        "rbc" to Histograms.rbc(c, seed),
        "plt" to Histograms.plt(c, seed),
    )
}
