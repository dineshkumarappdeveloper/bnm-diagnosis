package com.bnm.lab.instruments

/**
 * Machine-only mode — the printed CBC sheet, as a fixed template.
 *
 * WHY THIS EXISTS. A lab in machine-only mode keys NOTHING: no patient, no
 * order, no catalog. It wires one analyzer to this PC and prints what the
 * analyzer sends. So the report's row names, section headings, printed units
 * and normal ranges cannot come from the lab's catalog — there is no catalog.
 * They come from here, and they are modelled on the reference-lab CBC sheet
 * the analyzer's own printout mirrors (rows, order and ranges taken from a
 * real BC-5130 report).
 *
 * THE UNITS ARE THE WHOLE POINT. An analyzer speaks SI over HL7 — WBC as
 * `8.1 10*9/L` — and an Indian CBC sheet prints `8100 cells/cumm`. Printing
 * 8.1 under a 4500–11000 range is not a cosmetic slip; it reads as a
 * catastrophic leukopenia. So every row carries the unit it must PRINT in, and
 * the value is converted out of whatever unit the analyzer actually declared
 * (OBX-6, kept in [StoredInstrumentFrame.units]) rather than out of an
 * assumption — the same analyzer model ships configured in different unit
 * systems, and a Mispa install in the field already stores values unconverted
 * because its format carries no units at all.
 *
 * When a value cannot be converted honestly (unknown unit on the wire), it is
 * printed AS SENT with the analyzer's own unit and flagged, never silently
 * multiplied. A wrong number is worse than an odd-looking one.
 */
object MachineTemplate {

    /** A section heading printed as its own full-width row, as on the sheet. */
    data class Row(
        /** Printed name, e.g. "TOTAL WBC COUNT". */
        val label: String,
        /** Analyzer parameter spellings, first match wins. Case-insensitive. */
        val aliases: List<String>,
        /** The unit this row must PRINT in. Blank for unitless rows (PDW). */
        val unit: String,
        val low: Double? = null,
        val high: Double? = null,
        /** Heading printed above this row, when it opens a section. */
        val section: String? = null,
        /** Decimal places for the printed value; null = keep what the analyzer sent. */
        val decimals: Int? = null,
    )

    /**
     * The BC-5130 / BC-5000 / BC-5150 CBC sheet, in print order.
     *
     * Aliases carry the spellings the Mindray HL7 map produces (see
     * [MindrayBc5x.PARAM_NAMES]) plus the obvious variants, because the
     * analyzer's own label is what reaches us when the LOINC code is unknown.
     */
    val BC5X_CBC: List<Row> = listOf(
        Row("TOTAL WBC COUNT", listOf("WBC"), "cells/cumm", 4500.0, 11000.0, decimals = 0),

        Row("NEUTROPHILS", listOf("NEU%", "NEUT%", "GRA%"), "%", 35.0, 80.0, section = "DIFFERENTIAL COUNT", decimals = 0),
        Row("EOSINOPHILS", listOf("EOS%"), "%", 0.0, 5.0, decimals = 0),
        Row("BASOPHILS", listOf("BAS%", "BASO%"), "%", 0.0, 2.0, decimals = 0),
        Row("LYMPHOCYTES", listOf("LYM%", "LYMPH%"), "%", 18.0, 44.0, decimals = 0),
        Row("MONOCYTES", listOf("MON%", "MONO%"), "%", 0.0, 10.0, decimals = 0),

        Row("ABSOLUTE NEUTROPHILS", listOf("NEU#", "NEUT#", "GRA#"), "cells/cumm", 1575.0, 8800.0, section = "ABSOLUTE DIFFERENTIAL COUNT", decimals = 0),
        Row("ABSOLUTE EOSINOPHILS", listOf("EOS#"), "cells/cumm", 40.0, 440.0, decimals = 0),
        Row("ABSOLUTE BASOPHILS", listOf("BAS#", "BASO#"), "cells/cumm", 0.0, 110.0, decimals = 0),
        Row("ABSOLUTE LYMPHOCYTES", listOf("LYM#", "LYMPH#"), "cells/cumm", 810.0, 4840.0, decimals = 0),
        Row("ABSOLUTE MONOCYTES", listOf("MON#", "MONO#"), "cells/cumm", 0.0, 660.0, decimals = 0),

        Row("TOTAL RBC COUNT", listOf("RBC"), "million cells/cu mm", 3.8, 5.1, section = "RED BLOOD CELLS", decimals = 2),
        Row("HAEMOGLOBIN", listOf("HGB", "HB"), "g/dl", 11.7, 15.5, decimals = 1),
        Row("PACKED CELL VOLUME (PCV)", listOf("HCT", "PCV"), "%", 35.0, 45.0, decimals = 1),
        Row("MEAN CELL VALUE (MCV)", listOf("MCV"), "fl", 80.0, 100.0, decimals = 1),
        Row("MEAN CELL HAEMOGLOBIN (MCH)", listOf("MCH"), "pg/cell", 27.0, 34.0, decimals = 1),
        Row("MCH CONCENTRATION (MCHC)", listOf("MCHC"), "g/dl", 32.0, 36.0, decimals = 1),
        Row("RED CELL DIS. WIDTH (RDW-CV)", listOf("RDW-CV", "RDWCV"), "%", 11.0, 16.0, decimals = 1),
        Row("RED CELL DIS. WIDTH (RDW-SD)", listOf("RDW-SD", "RDWSD"), "%", 35.0, 56.0, decimals = 1),

        Row("PLATELETS", listOf("PLT"), "Lakhs/cumm", 1.5, 4.0, section = "PLATELETS", decimals = 2),
        Row("MEAN PLATELET VOLUME (MPV)", listOf("MPV"), "fl", 7.0, 11.0, decimals = 1),
        Row("PLATELET DISTRIBUTION WIDTH (PDW)", listOf("PDW"), "", 9.0, 17.0, decimals = 1),
        Row("PLATELETCRIT (PCT)", listOf("PCT"), "mL/L", 1.08, 2.82, decimals = 2),
        Row("PLATELET LARGER CELL COUNT (P-LCC)", listOf("PLCC", "P-LCC"), "cells/cumm", 30000.0, 90000.0, decimals = 0),
        Row("PLATELET LARGER CELL RATIO (P-LCR)", listOf("PLCR", "P-LCR"), "%", 11.0, 45.0, decimals = 1),
    )

    /**
     * Templates by [InstrumentDriver.key]. Unknown driver = no machine-only
     * sheet, and the mode refuses to turn on rather than printing a blank one.
     */
    fun forDriver(driver: String): List<Row>? = when (driver) {
        "mindray_hl7" -> BC5X_CBC
        else -> null
    }
}

/**
 * Analyzer unit → printed unit.
 *
 * Deliberately a SMALL closed table of pairs we have actually seen on the
 * wire, not a general dimensional-analysis engine: an unknown pair must fail
 * loudly (return null) so the caller prints the analyzer's own number and
 * unit, rather than guess a factor and print a plausible wrong result.
 */
object MachineUnits {

    /** Canonical form: case-folded, spaces stripped, `10^9/L` ≡ `10*9/L`. */
    fun canon(unit: String): String = unit.trim().lowercase()
        .replace(" ", "")
        .replace("^", "*")
        .replace("μ", "u")
        .replace("µ", "u")

    private val FACTORS: Map<Pair<String, String>, Double> = mapOf(
        // Counts: 10^9/L is "thousands per microlitre"; cumm == µL.
        ("10*9/l" to "cells/cumm") to 1_000.0,
        ("10*3/ul" to "cells/cumm") to 1_000.0,
        ("/ul" to "cells/cumm") to 1.0,
        ("cells/cumm" to "cells/cumm") to 1.0,
        // Platelets printed in lakhs: 10^9/L → /µL (×1000) → lakh (÷100 000).
        ("10*9/l" to "lakhs/cumm") to 0.01,
        ("10*3/ul" to "lakhs/cumm") to 0.01,
        // RBC: 10^12/L == millions per µL, one for one.
        ("10*12/l" to "millioncells/cumm") to 1.0,
        ("10*6/ul" to "millioncells/cumm") to 1.0,
        // Haemoglobin: g/L → g/dL.
        ("g/l" to "g/dl") to 0.1,
        ("g/dl" to "g/dl") to 1.0,
        ("mmol/l" to "g/dl") to 1.61,
        // Plateletcrit: the analyzer sends a percentage, the sheet prints mL/L.
        ("%" to "ml/l") to 10.0,
        ("ml/l" to "ml/l") to 1.0,
        // Straight-through units.
        ("%" to "%") to 1.0,
        ("fl" to "fl") to 1.0,
        ("pg" to "pg/cell") to 1.0,
        ("pg/cell" to "pg/cell") to 1.0,
    )

    /**
     * The factor taking [from] to [to], or null when we do not know the pair.
     * A blank [from] (Mispa sends no units at all) is treated as ALREADY in
     * the printed unit — that is the documented behaviour for unit-less
     * formats, and the lab is told to configure the analyzer to match.
     */
    fun factor(from: String, to: String): Double? {
        val f = canon(from)
        val t = canon(to)
        if (t.isEmpty()) return 1.0
        if (f.isEmpty()) return 1.0
        if (f == t) return 1.0
        return FACTORS[f to t]
    }

    /** One converted value, or null when the pair is unknown. */
    fun convert(value: Double, from: String, to: String): Double? =
        factor(from, to)?.let { value * it }
}
