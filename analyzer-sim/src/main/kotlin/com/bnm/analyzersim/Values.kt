package com.bnm.analyzersim

import java.util.Locale
import kotlin.math.roundToLong

/**
 * The blood count the simulator pretends to have measured.
 *
 * Every frame this tool emits is rendered from ONE [Cbc]. That is the whole
 * point: a commissioning engineer who sees HCT on the report has to be able to
 * check it against RBC and MCV, and a report that disagrees with itself teaches
 * nobody anything about the link. So only the seven primaries are chosen — the
 * rest are DERIVED here, by the same arithmetic a real analyzer does, and the
 * two frame builders merely format them in their own units.
 *
 * Percentages are stored already rounded to one decimal and summing to exactly
 * 100.0 (see [diff]); absolutes are recomputed from those rounded percentages,
 * so the printed frame is self-consistent, not just the doubles behind it.
 */
data class Cbc(
    /** 10^9/L */
    val wbc: Double,
    /** 10^12/L */
    val rbc: Double,
    /** g/dL — the Indian lab convention; Mindray renders it ×10 as g/L. */
    val hgb: Double,
    /** fL */
    val mcv: Double,
    /** 10^9/L */
    val plt: Double,
    /** fL */
    val mpv: Double,
    /** % */
    val rdwCv: Double,
    val diff: Diff,
) {
    /** % — RBC (10^12/L) × MCV (fL) / 10. */
    val hct: Double get() = rbc * mcv / 10.0

    /** pg — haemoglobin per red cell. */
    val mch: Double get() = hgb * 10.0 / rbc

    /** g/dL — haemoglobin per unit packed cell volume. */
    val mchc: Double get() = hgb / hct * 100.0

    /**
     * fL — the width of the red-cell distribution at 20% of peak height.
     * RDW-CV is SD/MCV as a percent, and the 20%-height width of a Gaussian is
     * about 3.45 SD, so RDW-SD ≈ 3.45 × RDW-CV × MCV / 100.
     */
    val rdwSd: Double get() = 3.45 * rdwCv * mcv / 100.0

    /** % (plateletcrit) — PLT (10^9/L) × MPV (fL) / 10000. */
    val pct: Double get() = plt * mpv / 10_000.0

    /** fL — platelet distribution width; tracks MPV on a real analyzer. */
    val pdw: Double get() = mpv * 1.35

    /** % of platelets larger than 12 fL. Rises with MPV. */
    val plcr: Double get() = ((mpv - 6.0) * 7.0).coerceIn(2.0, 65.0)

    /** 10^9/L — the large-platelet count PLCR is the ratio of. */
    val plcc: Double get() = plt * plcr / 100.0

    /** 10^9/L for a differential percentage: WBC × pct / 100. */
    fun absolute(percent: Double): Double = wbc * percent / 100.0
}

/**
 * A five-part differential in percent. Constructed only through [of], which
 * guarantees the five values are one-decimal numbers summing to exactly 100.0
 * — the first thing anyone checks on a differential, and the first thing a
 * naively-generated test frame gets wrong.
 */
data class Diff(
    val neu: Double,
    val lym: Double,
    val mon: Double,
    val eos: Double,
    val bas: Double,
) {
    /** The three-part view a Mispa Count X reports: MID = mono + eos + baso. */
    val mid: Double get() = round1(mon + eos + bas)
    val gran: Double get() = neu

    companion object {
        /**
         * Normalise five raw weights to 100 and round to one decimal. The
         * residual goes on neutrophils — the largest bucket, where a 0.1
         * adjustment is invisible, and the only placement that cannot push a
         * small population negative.
         */
        fun of(neu: Double, lym: Double, mon: Double, eos: Double, bas: Double): Diff {
            val total = neu + lym + mon + eos + bas
            fun scaled(x: Double) = round1(x * 100.0 / total)
            val l = scaled(lym)
            val m = scaled(mon)
            val e = scaled(eos)
            val b = scaled(bas)
            return Diff(neu = round1(100.0 - l - m - e - b), lym = l, mon = m, eos = e, bas = b)
        }
    }
}

/**
 * The clinical pictures the tool can emit. A profile fixes the primaries; the
 * seed then jitters them, so two runs of the same profile with different seeds
 * are different patients and the same seed is the same patient forever.
 */
enum class Profile(val cliName: String, val summary: String) {
    NORMAL("normal", "healthy adult — everything inside the reference range"),
    ANAEMIA("anaemia", "microcytic hypochromic anaemia — low HGB/MCV, wide RDW"),
    LEUKOCYTOSIS("leukocytosis", "bacterial picture — WBC ~22, neutrophil shift"),
    THROMBOCYTOPENIA("thrombocytopenia", "platelets ~40 with large MPV"),
    CRITICAL("critical", "pancytopenia at panic values — trips the app's CL flags"),
    RANDOM("random", "a plausible patient drawn anywhere across the ranges");

    companion object {
        fun of(name: String): Profile? = entries.firstOrNull { it.cliName.equals(name, ignoreCase = true) }
        val names: String get() = entries.joinToString(", ") { it.cliName }
    }
}

/**
 * Build the blood count for [profile], reproducible from [seed].
 *
 * The CRITICAL profile is tuned against the values the app actually treats as
 * panic results (catalog critical lows: HGB 7 g/dL, WBC 1000 /cumm, PLT
 * 20 000 /cumm) — so "send a critical and watch it reach the call-out list" is
 * a rehearsal an engineer can actually run, not a guess.
 */
fun cbcFor(profile: Profile, seed: Long): Cbc {
    val rng = Rng(seed)
    return when (profile) {
        Profile.NORMAL -> Cbc(
            wbc = round2(7.2 + rng.jitter(1.4)),
            rbc = round2(4.65 + rng.jitter(0.35)),
            hgb = round1(13.9 + rng.jitter(1.0)),
            mcv = round1(89.5 + rng.jitter(4.0)),
            plt = round0(248.0 + rng.jitter(55.0)),
            mpv = round1(9.4 + rng.jitter(1.0)),
            rdwCv = round1(13.1 + rng.jitter(1.0)),
            diff = Diff.of(62.0 + rng.jitter(6.0), 30.0 + rng.jitter(5.0), 5.0 + rng.jitter(1.5),
                2.4 + rng.jitter(1.2), 0.5 + rng.jitter(0.3)),
        )
        Profile.ANAEMIA -> Cbc(
            wbc = round2(6.4 + rng.jitter(1.2)),
            rbc = round2(3.75 + rng.jitter(0.35)),
            hgb = round1(8.3 + rng.jitter(1.1)),
            mcv = round1(70.5 + rng.jitter(5.0)),     // microcytic
            plt = round0(345.0 + rng.jitter(70.0)),   // reactive thrombocytosis
            mpv = round1(8.9 + rng.jitter(0.8)),
            rdwCv = round1(17.2 + rng.jitter(1.6)),   // anisocytosis
            diff = Diff.of(58.0 + rng.jitter(6.0), 33.0 + rng.jitter(5.0), 6.0 + rng.jitter(1.5),
                2.4 + rng.jitter(1.0), 0.6 + rng.jitter(0.3)),
        )
        Profile.LEUKOCYTOSIS -> Cbc(
            wbc = round2(22.4 + rng.jitter(5.0)),
            rbc = round2(4.5 + rng.jitter(0.3)),
            hgb = round1(13.2 + rng.jitter(0.9)),
            mcv = round1(88.0 + rng.jitter(3.5)),
            plt = round0(410.0 + rng.jitter(90.0)),
            mpv = round1(9.8 + rng.jitter(0.9)),
            rdwCv = round1(13.6 + rng.jitter(1.0)),
            diff = Diff.of(85.0 + rng.jitter(4.0), 8.5 + rng.jitter(2.0), 4.5 + rng.jitter(1.2),
                1.2 + rng.jitter(0.6), 0.4 + rng.jitter(0.2)),   // left shift
        )
        Profile.THROMBOCYTOPENIA -> Cbc(
            wbc = round2(6.8 + rng.jitter(1.3)),
            rbc = round2(4.4 + rng.jitter(0.3)),
            hgb = round1(13.1 + rng.jitter(0.9)),
            mcv = round1(90.0 + rng.jitter(3.5)),
            plt = round0(42.0 + rng.jitter(16.0)),
            mpv = round1(11.4 + rng.jitter(0.9)),     // young, large platelets
            rdwCv = round1(13.4 + rng.jitter(1.0)),
            diff = Diff.of(60.0 + rng.jitter(6.0), 31.0 + rng.jitter(5.0), 5.5 + rng.jitter(1.5),
                2.5 + rng.jitter(1.0), 0.6 + rng.jitter(0.3)),
        )
        Profile.CRITICAL -> Cbc(
            wbc = round2(0.75 + rng.jitter(0.2)),     // < 1.0 → 750 /cumm, below the catalog's critical low
            rbc = round2(1.55 + rng.jitter(0.2)),
            hgb = round1(4.2 + rng.jitter(0.5)),      // < 7.0 g/dL
            mcv = round1(84.0 + rng.jitter(5.0)),
            plt = round0(9.0 + rng.jitter(4.0)),      // < 20 000 /cumm
            mpv = round1(10.6 + rng.jitter(1.0)),
            rdwCv = round1(19.5 + rng.jitter(2.0)),
            diff = Diff.of(28.0 + rng.jitter(6.0), 62.0 + rng.jitter(6.0), 7.0 + rng.jitter(2.0),
                2.0 + rng.jitter(1.0), 1.0 + rng.jitter(0.5)),
        )
        Profile.RANDOM -> {
            // HGB is DERIVED here, not drawn. Three free uniforms for hgb, rbc
            // and mcv are each individually plausible and jointly impossible:
            // at the default seed they used to give MCHC 55.7 g/dL, a
            // haemoglobin concentration no red cell can hold (the solubility
            // ceiling is around 37). The frame parsed, the arithmetic agreed
            // with itself, and a pathologist reading the rehearsal result would
            // have concluded the app had corrupted the numbers. A real
            // patient's haemoglobin follows the cell volume, so draw the
            // CONCENTRATION across its physiological range and let HGB fall out
            // of it — which lands MCH (= MCV × MCHC / 100) in range for free.
            val rbc = round2(rng.between(3.1, 6.0))
            val mcv = round1(rng.between(68.0, 104.0))
            val mchc = rng.between(31.0, 35.5)
            Cbc(
                wbc = round2(rng.between(2.5, 19.0)),
                rbc = rbc,
                hgb = round1(rbc * mcv / 10.0 * mchc / 100.0),
                mcv = mcv,
                plt = round0(rng.between(60.0, 520.0)),
                mpv = round1(rng.between(7.0, 12.5)),
                rdwCv = round1(rng.between(11.5, 19.0)),
                diff = Diff.of(rng.between(35.0, 80.0), rng.between(12.0, 45.0), rng.between(2.0, 11.0),
                    rng.between(0.4, 7.0), rng.between(0.1, 1.5)),
            )
        }
    }
}

// ── formatting ──
//
// Locale.ROOT everywhere: a lab PC set to a comma-decimal locale would
// otherwise emit "7,20" and every parser downstream would read it as a
// field separator or a zero.

fun fmt(value: Double, decimals: Int): String = String.format(Locale.ROOT, "%.${decimals}f", value)

fun round0(x: Double): Double = x.roundToLong().toDouble()
fun round1(x: Double): Double = (x * 10.0).roundToLong() / 10.0
fun round2(x: Double): Double = (x * 100.0).roundToLong() / 100.0

/**
 * xorshift64* — a dozen lines instead of a dependency, and unlike
 * `java.util.Random` its stream is defined here, so a seed printed in a bug
 * report reproduces the exact frame on any JVM, forever.
 */
class Rng(seed: Long) {
    private var state: Long = if (seed == 0L) GOLDEN else seed

    fun nextLong(): Long {
        var x = state
        x = x xor (x shl 13)
        x = x xor (x ushr 7)
        x = x xor (x shl 17)
        state = x
        return x * 0x2545F4914F6CDD1DL
    }

    /** Uniform in [0, 1). */
    fun nextDouble(): Double = (nextLong() ushr 11).toDouble() / (1L shl 53).toDouble()

    /** Uniform in [lo, hi]. */
    fun between(lo: Double, hi: Double): Double = lo + nextDouble() * (hi - lo)

    /**
     * Symmetric noise in [-spread, +spread], bell-shaped rather than flat
     * (the mean of three uniforms) — patients cluster near the middle of a
     * profile, they do not spread evenly across it.
     */
    fun jitter(spread: Double): Double =
        ((nextDouble() + nextDouble() + nextDouble()) / 3.0 - 0.5) * 2.0 * spread

    private companion object {
        const val GOLDEN = -0x61c8864680b583ebL
    }
}
