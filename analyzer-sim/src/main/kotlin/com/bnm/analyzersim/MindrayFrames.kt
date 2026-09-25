package com.bnm.analyzersim

import java.util.Base64

/**
 * Mindray **BC-5130 / BC-5000 / BC-5150** frames — the mirror image of
 * `com.bnm.lab.instruments.MindrayBc5x`.
 *
 * One HL7 v2.3.1 `ORU^R01` per sample, MLLP-framed over TCP, and the analyzer
 * is the CLIENT: it dials the host, sends, waits for an ACK on the same
 * socket, then hangs up. That direction is the single fact most commissioning
 * calls get wrong, and it is why [TcpClientTransport] dials out instead of
 * listening.
 *
 * Segment order MSH → PID → PV1 → OBR → OBX×N. The OBX rows mix four kinds
 * and only OBX-3's first component says which:
 *  - `NM` with a LOINC or 99MRC code → a numeric result;
 *  - `IS` 08001/08002/08003/01002 → run settings, 12011..12052 → alerts;
 *  - `NM` 15xxx → histogram meta lengths and discriminator channels;
 *  - `ED` 15000/15050/15100 → histogram binaries, 15200 → the DIFF bitmap.
 *
 * ### The histogram byte layout, and why it is what it is
 * The app's `decodeHistogram` reads `metaLength + N` bytes as one unsigned
 * byte per channel unless the remainder is longer than 256 bytes. So a 128-
 * channel curve must be sent as `metaLength` filler bytes followed by 128
 * single-byte counts — which is also why [Histograms] caps counts at 255.
 * Send 256 UInt16 channels instead and the decoder would take a different
 * branch; the fidelity test pins this.
 */
object MindrayFrames {

    /** What the analyzer calls itself in MSH-3. */
    const val SENDING_APPLICATION = "BC-5130"
    const val SENDING_FACILITY = "Mindray"

    /** Filler bytes before each histogram's channel data, per kind. */
    private val META_LENGTH = mapOf("wbc" to 1, "rbc" to 2, "plt" to 4)

    /** A numeric result row as the analyzer sends it. */
    data class Param(val code: String, val name: String, val system: String, val value: String, val unit: String, val range: String, val flag: String)

    /**
     * Every NM result row for [spec], in transmission order.
     *
     * Exposed (rather than kept inside [build]) so the cross-check test can
     * assert the parser read back exactly these values and units, instead of
     * re-deriving them and only proving that two copies of the same mistake
     * agree.
     */
    fun parameters(spec: SampleSpec): List<Param> {
        val c = spec.cbc
        val d = c.diff
        val rng = Rng(spec.seed xor 0x414C5901L)
        // ALY (atypical lymphocytes) and LIC (large immature cells) are research
        // flags on this analyzer, reported OUTSIDE the five-part sum — which is
        // why adding them here does not break "the differential sums to 100".
        val alyPct = round1(rng.between(0.0, 1.2))
        val licPct = round1(rng.between(0.0, 0.9))

        fun p(code: String, name: String, system: String, value: String, unit: String, range: String, low: Double? = null, high: Double? = null, raw: Double? = null): Param {
            val flag = when {
                raw == null || low == null || high == null -> "N"
                raw < low -> "L"
                raw > high -> "H"
                else -> "N"
            }
            return Param(code, name, system, value, unit, range, flag)
        }

        // HGB in g/L, HCT in L/L, MCHC in g/L, PCT in mL/L: the SI spellings a
        // BC-5130 leaves the factory with. The app converts them to the lab's
        // catalog units, and that conversion is exactly what we want exercised.
        val hgbUnit = if (spec.badUnits) "bogus/L" else "g/L"
        return listOf(
            p("6690-2", "WBC", "LN", fmt(c.wbc, 2), "10*9/L", "4.00-10.00", 4.0, 10.0, c.wbc),
            p("704-7", "BAS#", "LN", fmt(c.absolute(d.bas), 2), "10*9/L", "0.00-0.10", 0.0, 0.1, c.absolute(d.bas)),
            p("706-2", "BAS%", "LN", fmt(d.bas, 1), "%", "0.0-1.0", 0.0, 1.0, d.bas),
            p("751-8", "NEU#", "LN", fmt(c.absolute(d.neu), 2), "10*9/L", "2.00-7.00", 2.0, 7.0, c.absolute(d.neu)),
            p("770-8", "NEU%", "LN", fmt(d.neu, 1), "%", "50.0-70.0", 50.0, 70.0, d.neu),
            p("711-2", "EOS#", "LN", fmt(c.absolute(d.eos), 2), "10*9/L", "0.02-0.50", 0.02, 0.5, c.absolute(d.eos)),
            p("713-8", "EOS%", "LN", fmt(d.eos, 1), "%", "0.5-5.0", 0.5, 5.0, d.eos),
            p("731-0", "LYM#", "LN", fmt(c.absolute(d.lym), 2), "10*9/L", "0.80-4.00", 0.8, 4.0, c.absolute(d.lym)),
            p("736-9", "LYM%", "LN", fmt(d.lym, 1), "%", "20.0-40.0", 20.0, 40.0, d.lym),
            p("742-7", "MON#", "LN", fmt(c.absolute(d.mon), 2), "10*9/L", "0.12-1.20", 0.12, 1.2, c.absolute(d.mon)),
            p("5905-5", "MON%", "LN", fmt(d.mon, 1), "%", "3.0-12.0", 3.0, 12.0, d.mon),
            p("26477-0", "ALY#", "LN", fmt(c.absolute(alyPct), 2), "10*9/L", "0.00-0.20", 0.0, 0.2, c.absolute(alyPct)),
            p("13046-8", "ALY%", "LN", fmt(alyPct, 1), "%", "0.0-2.0", 0.0, 2.0, alyPct),
            p("10000", "LIC#", "99MRC", fmt(c.absolute(licPct), 2), "10*9/L", "0.00-0.20", 0.0, 0.2, c.absolute(licPct)),
            p("10001", "LIC%", "99MRC", fmt(licPct, 1), "%", "0.0-2.5", 0.0, 2.5, licPct),
            p("789-8", "RBC", "LN", fmt(c.rbc, 2), "10*12/L", "3.50-5.50", 3.5, 5.5, c.rbc),
            p("718-7", "HGB", "LN", fmt(c.hgb * 10.0, 0), hgbUnit, "110-150", 11.0, 15.0, c.hgb),
            p("4544-3", "HCT", "LN", fmt(c.hct / 100.0, 3), "L/L", "0.370-0.540", 37.0, 54.0, c.hct),
            p("787-2", "MCV", "LN", fmt(c.mcv, 1), "fL", "80.0-100.0", 80.0, 100.0, c.mcv),
            p("785-6", "MCH", "LN", fmt(c.mch, 1), "pg", "27.0-34.0", 27.0, 34.0, c.mch),
            p("786-4", "MCHC", "LN", fmt(c.mchc * 10.0, 0), "g/L", "320-360", 32.0, 36.0, c.mchc),
            p("788-0", "RDW-CV", "LN", fmt(c.rdwCv, 1), "%", "11.0-16.0", 11.0, 16.0, c.rdwCv),
            p("21000-5", "RDW-SD", "LN", fmt(c.rdwSd, 1), "fL", "35.0-56.0", 35.0, 56.0, c.rdwSd),
            p("777-3", "PLT", "LN", fmt(c.plt, 0), "10*9/L", "100-300", 100.0, 300.0, c.plt),
            p("32623-1", "MPV", "LN", fmt(c.mpv, 1), "fL", "6.5-12.0", 6.5, 12.0, c.mpv),
            p("32207-3", "PDW", "LN", fmt(c.pdw, 1), "fL", "9.0-17.0", 9.0, 17.0, c.pdw),
            p("10002", "PCT", "99MRC", fmt(c.pct * 10.0, 2), "mL/L", "1.08-2.82", 1.08, 2.82, c.pct * 10.0),
            p("10013", "PLCC", "99MRC", fmt(c.plcc, 0), "10*9/L", "30-90", 30.0, 90.0, c.plcc),
            p("10014", "PLCR", "99MRC", fmt(c.plcr, 1), "%", "11.0-45.0", 11.0, 45.0, c.plcr),
        )
    }

    /**
     * The alert flags the run would raise. Only the NAME reaches the app (the
     * driver collects `IS` rows whose value is "T"), so the codes here are
     * representative of the documented 12011..12052 band rather than pinned to
     * one firmware's table — see the README.
     */
    fun alerts(spec: SampleSpec): List<Pair<String, String>> {
        val c = spec.cbc
        val out = mutableListOf<Pair<String, String>>()
        if (c.wbc > 11.0) out += "12021" to "Leucocytosis"
        if (c.wbc < 4.0) out += "12022" to "Leucopenia"
        if (c.hgb < 11.0) out += "12031" to "Anemia"
        if (c.mcv < 80.0) out += "12033" to "Microcytosis"
        if (c.mcv > 100.0) out += "12034" to "Macrocytosis"
        if (c.rdwCv > 16.0) out += "12035" to "Anisocytosis"
        if (c.plt < 150.0) out += "12041" to "Thrombocytopenia"
        if (c.plt > 450.0) out += "12042" to "Thrombocytosis"
        if (c.diff.neu > 80.0) out += "34165-1" to "Left Shift?"
        if (c.diff.lym > 50.0) out += "15192-8" to "Abn Lympho?"
        return out
    }

    /** The run settings the driver files under meta: take/blood/test mode and ref group. */
    fun settings(spec: SampleSpec): List<Triple<String, String, String>> = listOf(
        Triple("08001", "Take Mode", "Open Vial"),
        Triple("08002", "Blood Mode", "Whole Blood"),
        Triple("08003", "Test Mode", if (spec.histograms) "CBC+5DIFF" else "CBC"),
        Triple("01002", "Ref Group", if (spec.ageYears < 12) "Child" else "General"),
    )

    /** The HL7 message text, CR-separated, WITHOUT MLLP framing. */
    fun build(spec: SampleSpec): String {
        val segments = mutableListOf<String>()
        val processingId = if (spec.qc) "Q" else "P"
        val controlId = "SIM${spec.timestamp}${spec.sequence.toString().padStart(3, '0')}"

        segments += "MSH|^~\\&|$SENDING_APPLICATION|$SENDING_FACILITY|||${spec.timestamp}||ORU^R01|" +
            "$controlId|$processingId|2.3.1||||||UNICODE"

        val (last, first) = spec.nameParts
        val sex = when (spec.sex.uppercase()) { "M" -> "Male"; "F" -> "Female"; else -> "" }
        segments += "PID|1||${spec.patientId.orEmpty()}${if (spec.patientId != null) "^^^^MR" else ""}||" +
            "$last${if (first.isNotEmpty()) "^$first" else ""}|||$sex"
        segments += "PV1|1||OPD"
        // OBR-3 is the specimen id the app matches to an accession; an empty
        // one is the "nobody keyed the barcode" case that fills the claim queue.
        segments += "OBR|1||${spec.specimenId.orEmpty()}|00001^Automated Count^99MRC||" +
            "${spec.timestamp}|${spec.timestamp}|||Operator|||||||||||||HM||||||||"

        var setId = 0
        fun obx(type: String, code: String, name: String, system: String, value: String, unit: String = "", range: String = "", flag: String = "") {
            setId++
            segments += "OBX|$setId|$type|$code^$name^$system||$value|$unit|$range|$flag|||F"
        }

        for ((code, name, value) in settings(spec)) obx("IS", code, name, "99MRC", value)
        // 30525-0 is the one demographic the driver keeps from the OBX stream.
        obx("NM", "30525-0", "Age", "LN", spec.ageYears.toString(), "Year")

        for (p in parameters(spec)) obx("NM", p.code, p.name, p.system, p.value, p.unit, p.range, p.flag)
        if (spec.unknownCode) {
            // A firmware that grew a parameter BNM has no code for. The driver
            // must keep running and file it under the analyzer's own label —
            // never map it onto a real parameter, and never drop the message.
            obx("NM", UNKNOWN_CODE, UNKNOWN_NAME, "99MRC", "1.23", "10*9/L", "0.00-2.00", "N")
        }
        for ((code, name) in alerts(spec)) obx("IS", code, name, if (code.contains('-')) "LN" else "99MRC", "T")

        if (spec.histograms) {
            val wbc = Histograms.wbc(spec.cbc, spec.seed)
            val rbc = Histograms.rbc(spec.cbc, spec.seed)
            val plt = Histograms.plt(spec.cbc, spec.seed)

            obx("NM", "15004", "WBC Histogram. Meta Length", "99MRC", META_LENGTH.getValue("wbc").toString())
            obx("NM", "15010", "WBC Lym left line.", "99MRC", wbc.lines[0].toString())
            obx("NM", "15011", "WBC Lym Mid line.", "99MRC", wbc.lines[1].toString())
            obx("NM", "15012", "WBC Mid Gran line.", "99MRC", wbc.lines[2].toString())
            obx("NM", "15013", "WBC Gran right line.", "99MRC", wbc.lines[3].toString())
            obx("ED", "15000", "WBC Histogram. Binary", "99MRC", edPayload(encodeHistogram(wbc.points, "wbc")))

            obx("NM", "15053", "RBC Histogram. Binary Meta Length", "99MRC", META_LENGTH.getValue("rbc").toString())
            obx("NM", "15051", "RBC left line.", "99MRC", rbc.lines[0].toString())
            obx("NM", "15052", "RBC right line.", "99MRC", rbc.lines[1].toString())
            obx("ED", "15050", "RBC Histogram. Binary", "99MRC", edPayload(encodeHistogram(rbc.points, "rbc")))

            obx("NM", "15113", "PLT Histogram. Binary Meta Length", "99MRC", META_LENGTH.getValue("plt").toString())
            obx("NM", "15111", "PLT left line.", "99MRC", plt.lines[0].toString())
            obx("NM", "15112", "PLT right line.", "99MRC", plt.lines[1].toString())
            obx("ED", "15100", "PLT Histogram. Binary", "99MRC", edPayload(encodeHistogram(plt.points, "plt")))
        }
        if (spec.image) {
            val bmp = Base64.getEncoder().encodeToString(Bmp.scattergram(spec.cbc, spec.seed))
            setId++
            segments += "OBX|$setId|ED|15200^WBC DIFF Scattergram. BMP^99MRC||^Image^BMP^Base64^$bmp||||||F"
        }

        return segments.joinToString("\r") + "\r"
    }

    /** [build] wrapped for the wire. */
    fun frame(spec: SampleSpec): ByteArray = SimMllp.wrap(build(spec))

    /** The code/name the `--unknown-code` fault emits. */
    const val UNKNOWN_CODE = "99991"
    const val UNKNOWN_NAME = "ZZ Research Param"

    /**
     * Channel counts → the ED byte payload: [META_LENGTH] zero filler bytes
     * then one unsigned byte per channel. See the class KDoc for why the
     * layout has to be exactly this.
     */
    fun encodeHistogram(points: List<Int>, kind: String): ByteArray {
        val meta = META_LENGTH[kind] ?: 0
        val bytes = ByteArray(meta + points.size)
        points.forEachIndexed { i, v -> bytes[meta + i] = v.coerceIn(0, 255).toByte() }
        return bytes
    }

    /** OBX-5 for an ED row: `^Application^Octer-stream^Base64^<data>` (the
     *  vendor's own spelling of "octet-stream", kept as the doc has it). */
    private fun edPayload(bytes: ByteArray): String =
        "^Application^Octer-stream^Base64^" + Base64.getEncoder().encodeToString(bytes)
}
