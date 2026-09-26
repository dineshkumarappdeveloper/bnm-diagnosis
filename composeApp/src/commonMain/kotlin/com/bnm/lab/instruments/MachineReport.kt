package com.bnm.lab.instruments

import com.bnm.lab.report.ReportGraph
import com.bnm.lab.report.ReportRow
import com.bnm.lab.report.ReportSection

/**
 * Machine-only mode — one analyzer frame → the rows and graphs of a CBC sheet.
 *
 * Pure, and deliberately free of the database: there is no order, no patient
 * record and no catalog in this mode, so everything printed comes from the
 * frame ([StoredInstrumentFrame]) and the fixed [MachineTemplate]. The caller
 * wraps the result in a `ReportDoc` with the lab's own letterhead and print
 * settings, which are unchanged from the ordered path.
 *
 * WHAT IT REFUSES TO DO. It never invents a value and never converts one it
 * cannot convert honestly: a parameter the analyzer did not send is left off
 * the sheet entirely, and a parameter whose unit we do not recognise is
 * printed with the ANALYZER's number and the ANALYZER's unit, marked
 * [Unconverted]. Printing 8.1 where a lab expects 8100 is the failure mode
 * that matters here — it reads as a catastrophic result rather than a
 * formatting slip — so the honest odd-looking row is the safe one.
 */
object MachineReport {

    /** Section heading rows are [ReportRow.heading]; kept for call sites that filter. */
    fun isHeading(row: ReportRow): Boolean = row.heading

    /**
     * A BACKGROUND count, not a patient sample.
     *
     * The analyzer runs a blank to check its own cleanliness and transmits it
     * like any other record — same HL7 shape, an id of "Background" (or a
     * blank one) and near-zero values. It is a QC reading about the machine,
     * so it must never be offered as a printable patient report: a lab pressing
     * Comm once and seeing two rows would reasonably conclude the app is
     * duplicating its work.
     *
     * Detected by the id the analyzer keys, never by "the numbers look low" —
     * a genuinely pancytopenic patient also has near-zero counts, and hiding
     * THAT report would be a far worse failure than showing a blank.
     */
    fun isBackgroundRun(frame: StoredInstrumentFrame): Boolean {
        val id = frame.specimenId?.trim().orEmpty()
        return id.equals("background", ignoreCase = true) ||
            id.equals("blank", ignoreCase = true) ||
            frame.patientName?.trim().equals("background", ignoreCase = true)
    }

    /** A row whose unit could not be reconciled with the template's. */
    data class Unconverted(val param: String, val analyzerUnit: String, val printedUnit: String)

    data class Built(
        val sections: List<ReportSection>,
        /** Template rows the analyzer sent nothing for — shown in the UI, never printed. */
        val missing: List<String>,
        /** Rows printed in the analyzer's own unit because the pair is unknown. */
        val unconverted: List<Unconverted>,
        /**
         * Why the graph panel is short, in words the bench can act on, or null
         * when all four arrived. Silence here would be the wrong answer: a
         * sheet with two graphs instead of four looks like the app lost them,
         * when it is an analyzer setting that CANNOT send the other two.
         */
        val graphNote: String? = null,
    )

    /**
     * Build the CBC section for [frame]. Returns null when the driver has no
     * machine-only template — the mode should not have been offered at all.
     */
    fun build(frame: StoredInstrumentFrame, title: String = "COMPLETE BLOOD COUNT (CBC)"): Built? {
        val template = MachineTemplate.forDriver(frame.driver) ?: return null

        // Analyzer params, indexed case-insensitively: the same analyzer sends
        // "WBC" and "Wbc" depending on whether the LOINC code was recognised.
        val byKey = frame.params.entries.associateBy { it.key.trim().uppercase() }
        val unitsByKey = frame.units.entries.associateBy { it.key.trim().uppercase() }

        val rows = ArrayList<ReportRow>()
        val missing = ArrayList<String>()
        val unconverted = ArrayList<Unconverted>()
        var pendingSection: String? = null

        for (t in template) {
            t.section?.let { pendingSection = it }

            val hit = t.aliases.firstNotNullOfOrNull { byKey[it.trim().uppercase()] }
            if (hit == null) {
                missing += t.label
                continue
            }
            val raw = hit.value.trim()
            val numeric = raw.toDoubleOrNull()
            val analyzerUnit = t.aliases.firstNotNullOfOrNull { unitsByKey[it.trim().uppercase()] }
                ?.value?.trim().orEmpty()

            val converted = if (numeric == null) null else MachineUnits.convert(numeric, analyzerUnit, t.unit)
            val printedUnit: String
            val printedValue: String
            if (numeric == null) {
                // Non-numeric (a censored "+++" or an analyzer note) — pass through.
                printedUnit = t.unit
                printedValue = raw
            } else if (converted == null) {
                unconverted += Unconverted(t.label, analyzerUnit, t.unit)
                printedUnit = analyzerUnit.ifBlank { t.unit }
                printedValue = raw
            } else {
                printedUnit = t.unit
                printedValue = format(converted, t.decimals)
            }

            // Emit the heading only once we know the section has a row under it:
            // a sheet with an empty "PLATELETS" heading looks like lost data.
            pendingSection?.let {
                rows += ReportRow(param = it, value = "", unit = "", ref = "", flag = null, heading = true)
                pendingSection = null
            }

            rows += ReportRow(
                param = t.label,
                value = printedValue,
                unit = printedUnit,
                ref = refText(t),
                flag = flagFor(converted ?: numeric, t, converted != null),
            )
        }

        val section = ReportSection(
            title = title,
            rows = rows,
            department = "HAEMATOLOGY",
            graphs = graphs(frame),
            sampleType = "Blood (EDTA)",
        )
        return Built(listOf(section), missing, unconverted, graphNote(frame, section.graphs))
    }

    /**
     * The note under a short graph panel.
     *
     * The protocol defines a BITMAP code for the WBC histogram (15008) and the
     * DIFF scattergram (15200) and for nothing else: RBC (15050) and PLT
     * (15100) exist only as channel data. So an analyzer set to send histograms
     * "as Bitmap" can physically deliver at most two pictures, and no amount of
     * work on this side changes that — only the analyzer's own setting does.
     */
    fun graphNote(frame: StoredInstrumentFrame, drawn: List<ReportGraph>): String? {
        val kinds = drawn.map { it.kind }.toSet()
        val missing = listOf("rbc" to "RBC", "plt" to "PLT").filterNot { it.first in kinds }
        if (missing.isEmpty()) return null
        val names = missing.joinToString(" and ") { it.second }

        // The driver ALREADY records why it dropped a histogram it could not
        // parse (`<kind>_hist_error`, with the byte count). That is the single
        // most useful sentence on this screen when graphs are missing, and it
        // was being written to the frame and never shown to anyone. It comes
        // first: a decode failure is OUR problem, and blaming the analyzer's
        // Bitmap setting for it would send the bench to change a setting that
        // is already correct.
        val decodeErrors = missing.mapNotNull { (kind, label) ->
            frame.meta["${'$'}{kind}_hist_error"]?.takeIf { it.isNotBlank() }?.let { "$label ($it)" }
        }
        if (decodeErrors.isNotEmpty()) {
            return "The analyzer sent ${'$'}{decodeErrors.joinToString("; ")} but BNM Lab could not read " +
                "the layout. Send this line to BNM support — the data is arriving, it is the decoding " +
                "that needs fixing."
        }
        // Bitmap mode is proved by a WBC BITMAP, and by nothing else.
        //
        // The earlier test — "some picture arrived and no curves did" — was
        // wrong, because the DIFF scattergram is ALWAYS a bitmap. An analyzer
        // correctly switched to Data whose histogram we then failed to read
        // would still have its scattergram, and would be told to go and change
        // a setting it had already changed.
        val bitmapMode = frame.images.containsKey("wbc")
        return if (bitmapMode) {
            "$names histograms are missing because the analyzer is sending graphs as Bitmap, " +
                "which the protocol only supports for WBC and DIFF. On the analyzer set " +
                "\"Histogram Transmitted as\" to Data (leave Scattergram on Bitmap) to get all four."
        } else {
            // No curve, no bitmap and no decode error: nothing for these
            // arrived at all. Name both settings that cause it, because from
            // here they are indistinguishable.
            "$names histograms were not sent by the analyzer. On the analyzer check " +
                "\"Histogram Transmitted as\" is set to Data (not \"Not transmitted\"), " +
                "and that the change was saved."
        }
    }

    /**
     * The four graphs of a 5-part CBC sheet, in the order they are printed
     * beside the table: the three histograms as curves, the DIFF scattergram
     * as the analyzer's own bitmap. A kind the analyzer did not send is simply
     * absent — the renderer draws only what it is given.
     */
    fun graphs(frame: StoredInstrumentFrame): List<ReportGraph> {
        val out = ArrayList<ReportGraph>(4)
        fun curve(kind: String, title: String, xLabel: String?, axisMax: Double? = null, tick: Double? = null): Boolean {
            val points = frame.histograms[kind].orEmpty()
            if (points.size < 2 || points.none { it > 0.0 }) return false
            out += ReportGraph(
                kind = kind, title = title, points = points, xLabel = xLabel,
                xAxisMax = axisMax, xTickStep = tick,
            )
            return true
        }
        // The analyzer's FIXED measuring ranges, which is why they can be
        // constants: the BC-5x plots RBC over 0-300 fL and PLT over 0-40 fL on
        // every run. The WBC histogram is plotted in arbitrary channels and the
        // analyzer's own printout carries no numbers under it either, so
        // neither do we — a made-up axis would be worse than none.
        // A CURVE is preferred wherever the analyzer sent channel data: it
        // carries an axis, so a reader can see where a population sits.
        //
        // The WBC fallback matters because of an asymmetry in the protocol: it
        // defines a bitmap code for the WBC histogram (15008) and the DIFF
        // scattergram (15200) and for NOTHING ELSE — RBC (15050) and PLT
        // (15100) exist only as channel data. So an analyzer set to send
        // histograms "as Bitmap" can physically produce at most two pictures,
        // and a lab wanting all four must send histograms as Data.
        if (!curve("wbc", "WBC", null)) {
            frame.images["wbc"]?.takeIf { it.isNotBlank() }?.let { b64 ->
                out += ReportGraph(kind = "wbc", title = "WBC", points = emptyList(), image = decodeBase64(b64))
            }
        }
        curve("rbc", "RBC", "fL", axisMax = RBC_AXIS_MAX_FL, tick = 100.0)
        curve("plt", "PLT", "fL", axisMax = PLT_AXIS_MAX_FL, tick = 10.0)
        frame.images["diff"]?.takeIf { it.isNotBlank() }?.let { b64 ->
            // The analyzer's own bitmap, with the plane's axes named: MAS
            // (medium-angle scatter) across, LAS (light absorption) up. The
            // app draws the NAMES; the plot itself is the analyzer's pixels
            // and nothing here touches them.
            out += ReportGraph(
                kind = "diff", title = "DIFF", points = emptyList(),
                image = decodeBase64(b64), xLabel = "MAS", yLabel = "LAS",
            )
        }
        return out
    }

    /**
     * "4500 - 11000", "3.8 - 5.1", "1.5 - 4" — as the lab's own sheet prints
     * them. A range is read, not computed, so it is written the short way a
     * human would: trailing zeros are dropped rather than padded to the row's
     * decimal places, which is why this does NOT reuse the value formatter.
     */
    fun refText(t: MachineTemplate.Row): String {
        val lo = t.low
        val hi = t.high
        return when {
            lo != null && hi != null -> "${format(lo, null)} - ${format(hi, null)}"
            lo != null -> "> ${format(lo, null)}"
            hi != null -> "< ${format(hi, null)}"
            else -> ""
        }
    }

    /**
     * L / H / null against the template range — the mark the sheet prints in
     * red. Only ever computed on a CONVERTED value: comparing 8.1 against a
     * 4500–11000 range would flag every normal count as critically low, which
     * is exactly the mistake this mode has to avoid.
     */
    fun flagFor(value: Double?, t: MachineTemplate.Row, converted: Boolean): String? {
        if (value == null || !converted) return null
        val lo = t.low
        val hi = t.high
        return when {
            lo != null && value < lo -> "L"
            hi != null && value > hi -> "H"
            else -> null
        }
    }

    /** Fixed-decimal formatting without java.util — [decimals] null keeps it terse. */
    fun format(v: Double, decimals: Int?): String {
        val d = decimals ?: return trimTrailing(v)
        if (d <= 0) return kotlin.math.round(v).toLong().toString()
        var scale = 1.0
        repeat(d) { scale *= 10 }
        val scaled = kotlin.math.round(v * scale).toLong()
        val whole = scaled / scale.toLong()
        val frac = kotlin.math.abs(scaled % scale.toLong()).toString().padStart(d, '0')
        val sign = if (scaled < 0 && whole == 0L) "-" else ""
        return "$sign$whole.$frac"
    }

    private fun trimTrailing(v: Double): String {
        val s = v.toString()
        return if (s.endsWith(".0")) s.dropLast(2) else s
    }

    /** Base64 → bytes. The analyzer's BMP arrives base64 in OBX-5 (ED type). */
    fun decodeBase64(s: String): ByteArray? {
        val clean = s.filter { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }
        if (clean.isEmpty()) return null
        val table = IntArray(128) { -1 }
        ALPHABET.forEachIndexed { i, c -> table[c.code] = i }
        val out = ArrayList<Byte>(clean.length * 3 / 4)
        var buffer = 0
        var bits = 0
        for (c in clean) {
            if (c == '=') break
            val v = if (c.code < 128) table[c.code] else -1
            if (v < 0) return null
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out += ((buffer shr bits) and 0xFF).toByte()
            }
        }
        return out.toByteArray()
    }

    /** BC-5x RBC histogram full scale, femtolitres. */
    const val RBC_AXIS_MAX_FL = 300.0

    /** BC-5x PLT histogram full scale, femtolitres. */
    const val PLT_AXIS_MAX_FL = 40.0

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
}
