package com.bnm.analyzersim

/**
 * Agappe **Mispa Count X** frames — the mirror image of
 * `com.bnm.lab.instruments.MispaCountX`.
 *
 * Wire format, straight from the vendor LIS document the parser quotes:
 *
 * ```
 * $$$Date$Seq$Specimen$Patient$<20 params in PARAM_ORDER>
 * #<WBC histogram, 128 values '$'-separated>
 * #<RBC histogram, 128>
 * #<PLT histogram, 128>
 * #<discriminators, '@'-separated>
 * #<disease flags, '?'-separated>
 * #<parameter flags, '$'-separated>###
 * ```
 *
 * Transport is RS-232 at 115200-8-N-1, one-way: the analyzer pushes a frame
 * after every run and never waits for anything back. [MISPA_PARAM_ORDER] is
 * copied field-for-field from the parser's own PARAM_ORDER — the cross-check
 * test in composeApp is what keeps the two from drifting apart.
 */
object MispaFrames {

    /** The 20 header fields, in the vendor's order. */
    val MISPA_PARAM_ORDER = listOf(
        "WBC", "RBC", "PLT", "HGB", "HCT", "MCV", "MCH", "MCHC",
        "RDW-SD", "RDW-CV", "MPV", "LYMP%", "MID%", "GRAN%",
        "LYMP#", "MID#", "GRAN#", "PCT", "PDW", "LPCR",
    )

    const val FRAME_START = "$$$"
    const val FRAME_END = "###"
    private const val SEP = "$"

    /** "not keyed on the analyzer" — the parser reads this exact text as null. */
    const val NO_SPECIMEN = "0"

    /**
     * The 20 header values for [spec], keyed by parameter name. Exposed so the
     * fidelity test can assert the parser read back what we meant to send,
     * without re-deriving the arithmetic on its own (which would only test
     * that two copies of a bug agree).
     */
    fun parameters(spec: SampleSpec): Map<String, String> {
        val c = spec.cbc
        val d = c.diff
        return linkedMapOf(
            "WBC" to fmt(c.wbc, 2),
            "RBC" to fmt(c.rbc, 2),
            "PLT" to fmt(c.plt, 0),
            "HGB" to fmt(c.hgb, 1),
            "HCT" to fmt(c.hct, 1),
            "MCV" to fmt(c.mcv, 1),
            "MCH" to fmt(c.mch, 1),
            "MCHC" to fmt(c.mchc, 1),
            "RDW-SD" to fmt(c.rdwSd, 1),
            "RDW-CV" to fmt(c.rdwCv, 1),
            "MPV" to fmt(c.mpv, 1),
            "LYMP%" to fmt(d.lym, 1),
            "MID%" to fmt(d.mid, 1),
            "GRAN%" to fmt(d.gran, 1),
            "LYMP#" to fmt(c.absolute(d.lym), 2),
            "MID#" to fmt(c.absolute(d.mid), 2),
            "GRAN#" to fmt(c.absolute(d.gran), 2),
            "PCT" to fmt(c.pct, 2),
            "PDW" to fmt(c.pdw, 1),
            "LPCR" to fmt(c.plcr, 1),
        )
    }

    /** The disease flags this picture would raise, in the order the analyzer prints them. */
    fun diseaseFlags(spec: SampleSpec): List<String> {
        val c = spec.cbc
        val flags = mutableListOf<String>()
        if (c.hgb < 11.0) flags += "Anemia"
        if (c.mcv < 80.0) flags += "Microcytosis"
        if (c.mcv > 100.0) flags += "Macrocytosis"
        if (c.wbc > 11.0) flags += "Leucocytosis"
        if (c.wbc < 4.0) flags += "Leucopenia"
        if (c.plt < 150.0) flags += "Thrombocytopenia"
        if (c.plt > 450.0) flags += "Thrombocytosis"
        return flags
    }

    /**
     * One complete frame for [spec].
     *
     * QC runs are not modelled: the Mispa format carries no processing-id
     * field, so a QC material simply arrives as another sample — which is
     * itself worth knowing during commissioning, and is why the CLI says so
     * rather than silently ignoring `--qc`.
     */
    fun build(spec: SampleSpec): String {
        val params = parameters(spec).toMutableMap()
        val values = MISPA_PARAM_ORDER.map { params[it] ?: "" }.toMutableList()
        if (spec.unknownCode) {
            // A firmware that grew a 21st field. The parser reads exactly 20
            // and drops the rest — proving the app survives a firmware update
            // is the whole reason this switch exists.
            values += "99.9"
        }

        val head = listOf(
            spec.timestamp,
            spec.sequence.toString(),
            spec.specimenId ?: NO_SPECIMEN,
            spec.patientId ?: NO_SPECIMEN,
        ) + values

        val sections = mutableListOf(head.joinToString(SEP))
        if (spec.histograms) {
            val wbc = Histograms.wbc(spec.cbc, spec.seed)
            val rbc = Histograms.rbc(spec.cbc, spec.seed)
            val plt = Histograms.plt(spec.cbc, spec.seed)
            sections += wbc.points.joinToString(SEP)
            sections += rbc.points.joinToString(SEP)
            sections += plt.points.joinToString(SEP)
            // Discriminators and histogram flags share one '@'-separated section.
            sections += (wbc.lines + rbc.lines + plt.lines).joinToString("@")
        } else {
            // Still emit the section markers: a frame missing them would be a
            // different shape, and we are here to imitate the analyzer, not to
            // hand the parser an easier problem.
            sections += ""
            sections += ""
            sections += ""
            sections += ""
        }
        sections += diseaseFlags(spec).joinToString("?")
        sections += parameterFlags(spec).joinToString(SEP)

        return FRAME_START + sections.joinToString("#") + FRAME_END
    }

    /** H / L / N per header parameter, in PARAM_ORDER. The parser ignores this
     *  section; a real analyzer sends it, so we do too. */
    private fun parameterFlags(spec: SampleSpec): List<String> {
        val c = spec.cbc
        fun flag(value: Double, low: Double, high: Double) = when {
            value < low -> "L"
            value > high -> "H"
            else -> "N"
        }
        return listOf(
            flag(c.wbc, 4.0, 11.0), flag(c.rbc, 3.8, 5.8), flag(c.plt, 150.0, 450.0),
            flag(c.hgb, 12.0, 16.0), flag(c.hct, 36.0, 48.0), flag(c.mcv, 80.0, 100.0),
            flag(c.mch, 27.0, 34.0), flag(c.mchc, 32.0, 36.0), flag(c.rdwSd, 35.0, 56.0),
            flag(c.rdwCv, 11.0, 16.0), flag(c.mpv, 6.5, 12.0),
            "N", "N", "N", "N", "N", "N",
            flag(c.pct, 0.108, 0.282), flag(c.pdw, 9.0, 17.0), flag(c.plcr, 13.0, 43.0),
        )
    }
}
