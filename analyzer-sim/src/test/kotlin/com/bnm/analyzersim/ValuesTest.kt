package com.bnm.analyzersim

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The blood counts have to hold together, or the simulator teaches a lab to
 * trust a report that could not exist. Every profile, over a spread of seeds.
 */
class ValuesTest {

    private val seeds = listOf(1L, 2L, 7L, 42L, 1234L, -99L, 0L)

    @Test
    fun `the differential always sums to exactly 100`() {
        for (profile in Profile.entries) for (seed in seeds) {
            val d = cbcFor(profile, seed).diff
            val sum = round1(d.neu + d.lym + d.mon + d.eos + d.bas)
            assertEquals(100.0, sum, "$profile seed $seed summed to $sum")
        }
    }

    @Test
    fun `the three-part view also sums to 100 and folds mono, eos and baso into MID`() {
        for (profile in Profile.entries) for (seed in seeds) {
            val d = cbcFor(profile, seed).diff
            assertEquals(round1(d.mon + d.eos + d.bas), d.mid, "$profile seed $seed")
            assertEquals(100.0, round1(d.lym + d.mid + d.gran), "$profile seed $seed three-part sum")
        }
    }

    @Test
    fun `no population is ever negative`() {
        for (profile in Profile.entries) for (seed in seeds) {
            val d = cbcFor(profile, seed).diff
            for ((name, v) in listOf("neu" to d.neu, "lym" to d.lym, "mon" to d.mon, "eos" to d.eos, "bas" to d.bas)) {
                assertTrue(v >= 0.0, "$profile seed $seed had $name = $v")
            }
        }
    }

    @Test
    fun `the red-cell indices are derived, not invented`() {
        for (profile in Profile.entries) for (seed in seeds) {
            val c = cbcFor(profile, seed)
            close(c.rbc * c.mcv / 10.0, c.hct, "HCT = RBC x MCV / 10 ($profile/$seed)")
            close(c.hgb * 10.0 / c.rbc, c.mch, "MCH = HGB / RBC ($profile/$seed)")
            close(c.hgb / c.hct * 100.0, c.mchc, "MCHC = HGB / HCT ($profile/$seed)")
            close(c.plt * c.mpv / 10_000.0, c.pct, "PCT = PLT x MPV ($profile/$seed)")
        }
    }

    @Test
    fun `absolute counts are the WBC times the percentage`() {
        for (profile in Profile.entries) for (seed in seeds) {
            val c = cbcFor(profile, seed)
            close(c.wbc * c.diff.neu / 100.0, c.absolute(c.diff.neu), "NEU# ($profile/$seed)")
            val total = c.absolute(c.diff.neu) + c.absolute(c.diff.lym) +
                c.absolute(c.diff.mon) + c.absolute(c.diff.eos) + c.absolute(c.diff.bas)
            close(c.wbc, total, "the absolutes add back up to WBC ($profile/$seed)", tolerance = 0.01)
        }
    }

    /**
     * Derived-and-consistent is not the same as possible. Three independent
     * draws for HGB, RBC and MCV agree with each other perfectly and still
     * describe a patient who cannot exist: the random profile used to emit
     * MCHC 55.7 g/dL at the default seed, roughly half again the concentration
     * haemoglobin can reach in solution. A lab reading that rehearsal result
     * concludes the app corrupted it.
     */
    @Test
    fun `the red-cell indices are ones a patient could actually have`() {
        // Physics, not a reference range. MCHC is haemoglobin per unit packed
        // cell volume, and above roughly 37 g/dL it is past what a red cell can
        // hold in solution — a value no analyzer has ever measured, whatever the
        // patient has. These bounds hold for EVERY profile, critical included:
        // "critical" means panic WBC, HGB and PLT, not impossible arithmetic.
        for (profile in Profile.entries) for (seed in -200L..200L) {
            val c = cbcFor(profile, seed)
            assertTrue(c.mchc in 25.0..39.0,
                "$profile seed $seed: MCHC ${c.mchc} g/dL cannot exist in vivo ($c)")
            assertTrue(c.mch in 18.0..40.0, "$profile seed $seed: MCH ${c.mch} pg ($c)")
            assertTrue(c.mcv in 50.0..130.0, "$profile seed $seed: MCV ${c.mcv} fL ($c)")
        }
    }

    @Test
    fun `every profile but critical keeps MCHC, MCH and MCV in the everyday envelope`() {
        // The tighter envelope, for every profile the engineer might leave
        // selected by accident. `random` is the one that has to earn this: it
        // draws freely, and three independent uniforms for HGB, RBC and MCV are
        // each plausible alone and jointly impossible — which is how a rehearsal
        // once produced MCHC 185 g/L against a 320-360 range and looked to a
        // pathologist like the app had corrupted the numbers. Vary the picture,
        // not the physics; deliberately extreme values live in `critical`.
        val everyday = Profile.entries.filter { it != Profile.CRITICAL }
        for (profile in everyday) for (seed in -500L..500L) {
            val c = cbcFor(profile, seed)
            assertTrue(c.mchc in 28.0..37.0,
                "$profile seed $seed: MCHC ${c.mchc} g/dL is outside the everyday envelope ($c)")
            assertTrue(c.mch in 18.0..38.0, "$profile seed $seed: MCH ${c.mch} pg ($c)")
            assertTrue(c.mcv in 60.0..120.0, "$profile seed $seed: MCV ${c.mcv} fL ($c)")
            // The indices have to agree with the primaries they are derived
            // from, or the frame teaches nobody anything about the link.
            assertEquals(round1(c.rbc * c.mcv / 10.0), round1(c.hct), "$profile seed $seed: HCT")
            assertTrue(c.hgb > 0.0 && c.rbc > 0.0, "$profile seed $seed: a count at or below zero ($c)")
        }
    }

    /**
     * The random profile is the one with nothing holding it in place, so it is
     * held to the tighter band a real analyzer's population sits in.
     */
    @Test
    fun `the random profile stays inside a normal analyzer's spread`() {
        for (seed in -200L..200L) {
            val c = cbcFor(Profile.RANDOM, seed)
            assertTrue(c.mchc in 29.0..37.0, "seed $seed drew MCHC ${c.mchc} ($c)")
            assertTrue(c.mch in 20.0..40.0, "seed $seed drew MCH ${c.mch} ($c)")
            assertTrue(c.hgb > 0.0 && c.hct > 0.0, "seed $seed drew $c")
        }
    }

    @Test
    fun `a seed is a patient — the same seed is the same blood count forever`() {
        for (profile in Profile.entries) {
            assertEquals(cbcFor(profile, 99L), cbcFor(profile, 99L), "$profile is not deterministic")
        }
        assertTrue(cbcFor(Profile.NORMAL, 1L) != cbcFor(Profile.NORMAL, 2L),
            "two seeds produced identical patients — the seed is not reaching the values")
    }

    @Test
    fun `each profile really is the picture it claims`() {
        for (seed in seeds) {
            assertTrue(cbcFor(Profile.ANAEMIA, seed).hgb < 11.0, "anaemia seed $seed was not anaemic")
            assertTrue(cbcFor(Profile.ANAEMIA, seed).mcv < 80.0, "anaemia seed $seed was not microcytic")
            assertTrue(cbcFor(Profile.LEUKOCYTOSIS, seed).wbc > 11.0, "leukocytosis seed $seed")
            assertTrue(cbcFor(Profile.LEUKOCYTOSIS, seed).diff.neu > 70.0, "leukocytosis seed $seed had no left shift")
            assertTrue(cbcFor(Profile.THROMBOCYTOPENIA, seed).plt < 100.0, "thrombocytopenia seed $seed")
            val normal = cbcFor(Profile.NORMAL, seed)
            assertTrue(normal.hgb in 11.5..16.5 && normal.wbc in 4.0..11.0 && normal.plt in 150.0..450.0,
                "the normal profile drifted out of range at seed $seed: $normal")
        }
    }

    /**
     * The whole point of the CRITICAL profile: it must trip the panic
     * thresholds the seeded catalog carries for an adult (HGB 7 g/dL, WBC
     * 1000 /cumm, PLT 20 000 /cumm), or "rehearse a critical call-out" is not
     * a rehearsal.
     */
    @Test
    fun `the critical profile is below every adult panic threshold`() {
        for (seed in seeds) {
            val c = cbcFor(Profile.CRITICAL, seed)
            assertTrue(c.hgb < 7.0, "seed $seed HGB ${c.hgb} would not flag CL")
            assertTrue(c.wbc * 1000 < 1000.0, "seed $seed WBC ${c.wbc} (${c.wbc * 1000}/cumm) would not flag CL")
            assertTrue(c.plt * 1000 < 20_000.0, "seed $seed PLT ${c.plt} would not flag CL")
        }
    }

    @Test
    fun `values are formatted with a dot, whatever the machine's locale says`() {
        val original = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)   // comma decimals
            assertEquals("7.20", fmt(7.2, 2))
        } finally {
            java.util.Locale.setDefault(original)
        }
    }

    private fun close(expected: Double, actual: Double, what: String, tolerance: Double = 0.001) {
        assertTrue(abs(expected - actual) <= tolerance, "$what: expected $expected, got $actual")
    }
}
