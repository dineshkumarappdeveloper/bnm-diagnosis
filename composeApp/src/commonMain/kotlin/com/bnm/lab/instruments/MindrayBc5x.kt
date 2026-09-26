package com.bnm.lab.instruments

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.abs

/**
 * Mindray **BC-5130 / BC-5000 / BC-5150** (5-part hematology) result parser.
 *
 * The analyzer pushes one HL7 v2.3.1 `ORU^R01` per sample over TCP (MLLP):
 * MSH → PID → PV1 → OBR → OBX×N. The OBX list mixes four kinds of rows and
 * only the identifier (OBX-3, `code^name^99MRC|LN`) tells them apart:
 *
 * - `NM` numeric parameters — LOINC codes for the standard CBC items, Mindray
 *   `99MRC` codes for the vendor extras (PCT, PLCC, LIC#…).
 * - `IS` run settings and alert flags (`08001` take mode … `12045` alerts).
 * - `NM` histogram side data — discriminator lines and the "Meta Length" the
 *   analyzer prepends to the binary blob.
 * - `ED` binaries — WBC/RBC/PLT histograms as base64 bytes and the DIFF
 *   scattergram as a base64 BMP.
 *
 * This object only turns one [Hl7Message] into a [Frame]; framing, ACKs and
 * result entry stay in the engine. It never throws on odd content — a row it
 * doesn't understand is skipped, not fatal, because a lab keeps running even
 * when a firmware update adds a code we have not seen.
 */
object MindrayBc5x {
    const val DRIVER_KEY = "mindray_hl7"

    /**
     * OBX-3 identifier (component 1) → the analyzer parameter name the engine
     * keys results by. Both LN and 99MRC codes from the BC-5130 protocol.
     */
    val PARAM_CODES: Map<String, String> = mapOf(
        "6690-2" to "WBC",
        "704-7" to "BAS#", "706-2" to "BAS%",
        "751-8" to "NEU#", "770-8" to "NEU%",
        "711-2" to "EOS#", "713-8" to "EOS%",
        "731-0" to "LYM#", "736-9" to "LYM%",
        "742-7" to "MON#", "5905-5" to "MON%",
        "26477-0" to "ALY#", "13046-8" to "ALY%",
        "10000" to "LIC#", "10001" to "LIC%",
        "789-8" to "RBC", "718-7" to "HGB",
        "787-2" to "MCV", "785-6" to "MCH", "786-4" to "MCHC",
        "788-0" to "RDW-CV", "21000-5" to "RDW-SD",
        "4544-3" to "HCT", "777-3" to "PLT",
        "32623-1" to "MPV", "32207-3" to "PDW", "10002" to "PCT",
        "10013" to "PLCC", "10014" to "PLCR",
        "10027" to "MID#", "10029" to "MID%",
        "10028" to "GRAN#", "10030" to "GRAN%",
    )

    // Run-setting IS rows → meta keys.
    private val SETTING_CODES = mapOf(
        "08001" to "take_mode",
        "08002" to "blood_mode",
        "08003" to "test_mode",
        "01002" to "ref_group",
    )
    private const val AGE_CODE = "30525-0"

    // Alert IS rows: numeric 12011..12052, plus the two LN-coded ones.
    private val ALERT_RANGE = 12011..12052
    private val ALERT_LN_CODES = setOf("34165-1", "15192-8")

    // Histogram binaries / meta lengths / discriminator lines, by kind.
    private val HISTOGRAM_BINARY = mapOf("15000" to "wbc", "15050" to "rbc", "15100" to "plt")
    private val HISTOGRAM_META_LENGTH = mapOf(
        "15004" to "wbc",
        "15053" to "rbc",
        "15103" to "plt", "15113" to "plt",
    )
    // The doc's table numbers the WBC lines 15001-15003 while its sample uses
    // 15010-15013 — accept both, prefer the set that actually arrived.
    private val WBC_LINES_TABLE = listOf("15001", "15002", "15003")
    private val WBC_LINES_SAMPLE = listOf("15010", "15011", "15012", "15013")
    private val RBC_LINES = listOf("15051", "15052")
    private val PLT_LINES = listOf("15111", "15112")

    private val IMAGE_CODES = mapOf("15008" to "wbc", "15200" to "diff")

    /** Every 15xxx code is histogram / scattergram side data, never a result. */
    private val SIDE_DATA_RANGE = 15000..15299

    data class Frame(
        val specimenId: String?,
        val patientId: String?,
        val patientName: String?,
        /** PID-8, normalised to the app's own 'M' | 'F' | 'O'; null when unusable. */
        val patientSex: String?,
        val date: String?,
        val sequenceId: String?,
        /** Analyzer name → OBX-5 numeric text (NM rows only). */
        val params: Map<String, String>,
        /** Same keys → OBX-6 unit text exactly as sent; absent when blank. */
        val units: Map<String, String>,
        /** 'wbc' | 'rbc' | 'plt' → decoded points, see [decodeHistogram]. */
        val histograms: Map<String, List<Double>>,
        /** 'wbc' | 'diff' → base64 BMP text exactly as sent. */
        val images: Map<String, String>,
        val meta: Map<String, String>,
        /** MSH-11 == "Q" — a QC material run, not a patient sample. */
        val isQc: Boolean,
        /** The message text, for the traffic log. */
        val raw: String,
    )

    /**
     * Convenience for the engine's byte path: parse text, keep it as [Frame.raw].
     * Null when the text is not a parseable ORU.
     */
    fun parse(text: String): Frame? {
        val msg = runCatching { Hl7Message.parse(text) }.getOrNull() ?: return null
        return parse(msg, text)
    }

    /**
     * null unless MSH-9 starts with "ORU" (ORU^R01). QC messages parse too
     * ([Frame.isQc] = true) — the caller decides what to do with them.
     * [raw] is stored on the frame verbatim; [Hl7Message] does not keep its
     * source text, so pass it when you have it.
     */
    fun parse(msg: Hl7Message, raw: String = ""): Frame? {
        if (!msg.messageType.trim().startsWith("ORU")) return null

        val msh = msg.segment("MSH")
        val pid = msg.segment("PID")
        val obr = msg.segment("OBR")

        val params = LinkedHashMap<String, String>()
        val units = LinkedHashMap<String, String>()
        val flags = ArrayList<String>()
        val meta = LinkedHashMap<String, String>()
        val alerts = ArrayList<String>()
        val images = LinkedHashMap<String, String>()
        val histogramB64 = LinkedHashMap<String, String>()
        val metaLengths = HashMap<String, Int>()
        val lines = HashMap<String, String>()          // discriminator code → value

        for (obx in msg.segments("OBX")) {
            val type = obx.field(2).trim().uppercase()
            val code = obx.component(3, 1).trim()
            val name = obx.component(3, 2).trim()
            val value = obx.field(5).trim()
            val numericCode = code.toIntOrNull()

            when (type) {
                "NM" -> {
                    val paramName = PARAM_CODES[code]
                    when {
                        paramName != null -> {
                            if (value.isEmpty()) continue
                            params[paramName] = value
                            obx.field(6).trim().takeIf { it.isNotEmpty() }?.let { units[paramName] = it }
                            obx.field(8).trim().takeIf { it.isNotEmpty() && it != "N" }
                                ?.let { flags += "$paramName:$it" }
                        }
                        code == AGE_CODE -> {
                            if (value.isNotEmpty()) {
                                meta["age"] = listOf(value, obx.field(6).trim())
                                    .filter { it.isNotEmpty() }.joinToString(" ")
                            }
                        }
                        code in HISTOGRAM_META_LENGTH || name.contains("Meta Length", ignoreCase = true) -> {
                            val kind = HISTOGRAM_META_LENGTH[code] ?: kindFromName(name) ?: continue
                            value.toIntOrNull()?.let { metaLengths[kind] = it }
                        }
                        numericCode != null && numericCode in SIDE_DATA_RANGE -> {
                            // Discriminator lines (and anything else the doc files under 15xxx).
                            if (value.isNotEmpty()) lines[code] = value
                        }
                        name.isNotEmpty() -> {
                            // A numeric result we have no code for: keep it under
                            // the analyzer's own name so the param map can pick it up.
                            if (value.isEmpty()) continue
                            params[name] = value
                            obx.field(6).trim().takeIf { it.isNotEmpty() }?.let { units[name] = it }
                            obx.field(8).trim().takeIf { it.isNotEmpty() && it != "N" }
                                ?.let { flags += "$name:$it" }
                        }
                    }
                }
                "IS" -> {
                    val settingKey = SETTING_CODES[code]
                    val isAlert = (numericCode != null && numericCode in ALERT_RANGE) || code in ALERT_LN_CODES
                    when {
                        settingKey != null -> if (value.isNotEmpty()) meta[settingKey] = value
                        isAlert -> if (value == "T" && name.isNotEmpty()) alerts += name
                    }
                }
                "ED" -> {
                    // OBX-5 = ^Application^Octer-stream^Base64^<data>  (or ^Image^BMP^Base64^<data>)
                    val data = obx.component(5, 5).trim()
                    if (data.isEmpty()) continue
                    HISTOGRAM_BINARY[code]?.let { histogramB64[it] = data }
                    IMAGE_CODES[code]?.let { images[it] = data }
                }
            }
        }

        val histograms = LinkedHashMap<String, List<Double>>()
        for ((kind, b64) in histogramB64) {
            val metaLength = metaLengths[kind] ?: 0
            val points = decodeHistogram(b64, metaLength)
            if (points.isNotEmpty()) histograms[kind] = points
            // A layout the decoder does not recognise goes on record with its
            // raw size, so a commissioning engineer can see what the analyzer
            // actually sends instead of a blank box.
            else meta["${kind}_hist_error"] = describeUndecodable(b64, metaLength)
        }

        if (alerts.isNotEmpty()) meta["alerts"] = alerts.joinToString(";")
        joinLines(lines, if (WBC_LINES_SAMPLE.any { it in lines }) WBC_LINES_SAMPLE else WBC_LINES_TABLE)
            ?.let { meta["wbc_lines"] = it }
        joinLines(lines, RBC_LINES)?.let { meta["rbc_lines"] = it }
        joinLines(lines, PLT_LINES)?.let { meta["plt_lines"] = it }
        if (flags.isNotEmpty()) meta["flags"] = flags.joinToString(";")

        return Frame(
            specimenId = obr?.field(3)?.trim()?.takeIf { it.isNotEmpty() },
            patientId = pid?.component(3, 1)?.trim()?.takeIf { it.isNotEmpty() },
            patientName = patientName(pid),
            patientSex = patientSex(pid),
            date = obr?.field(7)?.trim()?.takeIf { it.isNotEmpty() }
                ?: msh?.field(7)?.trim()?.takeIf { it.isNotEmpty() },
            sequenceId = msg.controlId.trim().takeIf { it.isNotEmpty() },
            params = params,
            units = units,
            histograms = histograms,
            images = images,
            meta = meta,
            isQc = msg.processingId.trim() == "Q",
            raw = raw,
        )
    }

    /** PID-5 `Last^First^Middle` → "First Middle Last", or whatever is present. */
    private fun patientName(pid: Hl7Segment?): String? {
        pid ?: return null
        val last = pid.component(5, 1).trim()
        val first = pid.component(5, 2).trim()
        val middle = pid.component(5, 3).trim()
        return listOf(first, middle, last).filter { it.isNotEmpty() }
            .joinToString(" ").takeIf { it.isNotEmpty() }
    }

    /**
     * PID-8 → the app's own sex code, or null. The spelled-out forms are here
     * because the BC-5130 protocol's own sample message sends "Male".
     *
     * HL7's table 0001 also carries U (unknown), A (ambiguous) and N (not
     * applicable). None of them is "Other": they are the analyzer saying it does
     * not know, and turning that into a stored 'O' would silently pick the
     * reference range a report is printed against. Null instead — the form then
     * asks, which is the honest answer. Matched exactly rather than by first
     * letter for the same reason: "Unknown" must not become an 'O' either.
     */
    private fun patientSex(pid: Hl7Segment?): String? = when (pid?.field(8)?.trim()?.uppercase()) {
        "M", "MALE" -> "M"
        "F", "FEMALE" -> "F"
        "O", "OTHER" -> "O"
        else -> null
    }

    private fun kindFromName(name: String): String? = when {
        name.startsWith("WBC", ignoreCase = true) -> "wbc"
        name.startsWith("RBC", ignoreCase = true) -> "rbc"
        name.startsWith("PLT", ignoreCase = true) -> "plt"
        else -> null
    }

    private fun joinLines(lines: Map<String, String>, codes: List<String>): String? =
        codes.mapNotNull { lines[it] }.takeIf { it.isNotEmpty() }?.joinToString(",")

    /**
     * Histogram ED payload → points. OBX-5 for ED is
     * `^Application^Octer-stream^Base64^<data>`; the caller passes the base64
     * (component 5) and the "Meta Length" the analyzer sent alongside (0 when
     * absent).
     *
     * The vendor protocol never says what "Meta Length" measures, so two
     * readings are tried, in this order:
     * 1. ELEMENT WIDTH — when the payload is exactly `metaLength × 256` bytes
     *    (2..4), it is 256 little-endian integers of that width;
     * 2. PREFIX — drop [metaLength] leading bytes; an even remainder longer
     *    than 256 bytes is UInt16-LE per point, else one unsigned byte per
     *    point.
     * Only [ACCEPTED_CHANNELS] counts are believed; anything else is a layout
     * this decoder has not seen, and an empty list (the caller records the raw
     * size) beats a curve drawn from misaligned bytes. Invalid base64 → empty.
     * A trailing all-zero run is NOT trimmed — the x axis is the channel index.
     */
    @OptIn(ExperimentalEncodingApi::class)
    /**
     * What to say about a histogram no reading fits.
     *
     * The EXACT decoded length and the first bytes in hex, because that is
     * everything needed to work out the real layout — channel count, header
     * size, byte order — without another sample, another build and another
     * trip to the lab. A round number like "about 500 bytes" costs a day.
     */
    fun describeUndecodable(base64: String, metaLength: Int): String {
        val cleaned = base64.filterNot { it.isWhitespace() }
        val bytes = runCatching {
            Base64.Default.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL).decode(cleaned)
        }.getOrNull() ?: return "the data would not base64-decode (${cleaned.length} chars)"
        val head = bytes.take(16).joinToString("") { b ->
            val h = (b.toInt() and 0xFF).toString(16)
            if (h.length == 1) "0$h" else h
        }
        return "${bytes.size} bytes, meta length $metaLength, starts $head"
    }

    fun decodeHistogram(base64: String, metaLength: Int): List<Double> {
        val cleaned = base64.filterNot { it.isWhitespace() }
        if (cleaned.isEmpty()) return emptyList()
        val bytes = runCatching {
            Base64.Default.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL).decode(cleaned)
        }.getOrNull() ?: return emptyList()
        val skip = metaLength.coerceAtLeast(0)

        // WHY NOT "the first reading that fits".
        //
        // The layout is undocumented ("customized", manual 6.2) and the same
        // byte count is ambiguous: 256 bytes is either 256 one-byte channels
        // or 128 two-byte ones, and only the data can say which. Taking the
        // first reading of an acceptable SIZE picks wrong about a third of the
        // time and then never looks further — which is how a histogram the lab
        // can see in another LIS came out blank here.
        //
        // So every reading is scored. A histogram is SMOOTH: neighbouring
        // channels differ a little, because cells of nearly the same size are
        // nearly as common. Read with the wrong width, offset or byte order,
        // the same bytes become high-frequency noise. Roughness therefore
        // separates the true reading from its impostors without knowing the
        // layout in advance — and it is measured relative to the curve's own
        // height, so a 16-bit reading is not punished for having bigger
        // numbers than an 8-bit one.
        // The documented reading leads the field: it is right for a BC-5130
        // and it is what decides an all-zero payload, where smoothness cannot.
        val primary = when {
            skip in 2..4 && bytes.size == skip * 256 -> littleEndian(bytes, 0, skip)
            bytes.size <= skip -> emptyList()
            (bytes.size - skip) % 2 == 0 && bytes.size - skip > 256 -> littleEndian(bytes, skip, 2)
            else -> littleEndian(bytes, skip, 1)
        }
        val candidates = buildList {
            add(primary)
            for (from in linkedSetOf(0, skip)) {
                if (from >= bytes.size) continue
                add(littleEndian(bytes, from, 1))
                add(littleEndian(bytes, from, 2))
                add(littleEndian(bytes, from, 4))
                add(bigEndian(bytes, from, 2))
            }
        }
        val acceptable = candidates.filter { it.size in ACCEPTED_CHANNELS }
        // A blank run IS a legitimate histogram — a background count is all
        // zeros — so an empty curve is never grounds to reject a reading. It
        // simply cannot be scored, since roughness needs a height to divide
        // by, and there the documented reading decides.
        val measurable = acceptable.filter { it.any { v -> v > 0.0 } }
        return measurable.minByOrNull { roughness(it) }
            ?: acceptable.firstOrNull()
            ?: emptyList()
    }

    /**
     * How un-histogram-like a reading is: the average step between channels,
     * as a fraction of the curve's height. A real distribution scores low;
     * bytes read at the wrong width or offset score high, because every other
     * value is then a high byte that has nothing to do with its neighbour.
     */
    private fun roughness(points: List<Double>): Double {
        if (points.size < 2) return Double.MAX_VALUE
        val max = points.max()
        if (max <= 0.0) return Double.MAX_VALUE
        var steps = 0.0
        for (i in 1 until points.size) steps += kotlin.math.abs(points[i] - points[i - 1])
        return steps / (max * points.size)
    }

    /** Channel counts a hematology histogram can have (BC-5130: 256). */
    private val ACCEPTED_CHANNELS = setOf(64, 128, 256, 512, 1024)

    private fun bigEndian(bytes: ByteArray, from: Int, width: Int): List<Double> {
        val n = (bytes.size - from) / width
        if (n <= 0) return emptyList()
        return List(n) { i ->
            var v = 0L
            for (b in 0 until width) v = (v shl 8) or (bytes[from + i * width + b].toLong() and 0xFF)
            v.toDouble()
        }
    }

    private fun littleEndian(bytes: ByteArray, from: Int, width: Int): List<Double> {
        val n = (bytes.size - from) / width
        return List(n) { i ->
            var v = 0L
            for (b in width - 1 downTo 0) v = (v shl 8) or (bytes[from + i * width + b].toLong() and 0xFF)
            v.toDouble()
        }
    }
}

/**
 * Analyzer unit → catalog unit. Indian labs report "cells/cumm" and
 * "lakhs/cumm" where the analyzer says 10*9/L; HGB in g/dL where it says g/L.
 * Unknown pairs pass the value through unchanged; [needsConversion] tells the
 * engine, which logs the mismatch as an error.
 */
object AnalyzerUnits {

    /**
     * One convertible family: every canonical spelling → its factor to the
     * family's base unit. Two units convert only when they share a family, so
     * "/cumm" (which is both a 10^9 and a 10^12 spelling) resolves from the
     * unit on the other side.
     */
    private val FAMILIES: List<Map<String, Double>> = listOf(
        // Base 10^9/l — WBC, PLT, differential absolutes. The Mindray unit
        // menu also offers 10^2/µL and /nL; Sysmex prints 10^3/mm3.
        mapOf(
            "10^9/l" to 1.0, "10^3/ul" to 1.0, "10^3/cumm" to 1.0, "k/ul" to 1.0, "thou/cumm" to 1.0, "thou/ul" to 1.0,
            "/nl" to 1.0, "10^2/ul" to 0.1, "10^2/cumm" to 0.1,
            "/cumm" to 0.001, "/ul" to 0.001,
            "lakhs/cumm" to 100.0, "lakh/cumm" to 100.0, "lakhs/ul" to 100.0, "lakh/ul" to 100.0,
        ),
        // Base 10^12/l — RBC. Mindray also offers 10^4/µL and /pL.
        mapOf(
            "10^12/l" to 1.0, "10^6/ul" to 1.0, "10^6/cumm" to 1.0, "m/ul" to 1.0, "/pl" to 1.0,
            "million/cumm" to 1.0, "millions/cumm" to 1.0, "mill/cumm" to 1.0, "million/ul" to 1.0,
            "10^4/ul" to 0.01, "10^4/cumm" to 0.01,
            "/cumm" to 0.000001, "/ul" to 0.000001,
        ),
        // Base g/dl — HGB, MCHC. mmol/l is the haemoglobin (Fe) convention.
        mapOf("g/dl" to 1.0, "g/l" to 0.1, "mmol/l" to 1.611, "g%" to 1.0, "gm/dl" to 1.0, "gm%" to 1.0),
        // Base % — HCT, PCT, percentages.
        mapOf("%" to 1.0, "ml/l" to 0.1, "l/l" to 100.0),
        // Base fl — MCV, MPV.
        mapOf("fl" to 1.0, "um^3" to 1.0, "um3" to 1.0),
        // Base pg — MCH. 1 fmol of haemoglobin (64 458 g/mol) is 64.458 pg.
        mapOf("pg" to 1.0, "fmol" to 64.458, "amol" to 0.064458),
    )

    /**
     * True when [from] and [to] name different units that no family above can
     * bridge — [convert] passed the value through and the engine must say so.
     * Blank on either side is "no opinion", not a mismatch.
     */
    fun needsConversion(from: String?, to: String?): Boolean {
        if (from.isNullOrBlank() || to.isNullOrBlank()) return false
        val a = canonical(from)
        val b = canonical(to)
        if (a == b) return false
        return FAMILIES.none { a in it && b in it }
    }

    /**
     * [value] numeric text converted from [from] to [to]; either null/blank or
     * unparseable → [value] unchanged. Result keeps at most 2 decimals,
     * trailing zeros trimmed ("9550", "13.5", "3.81").
     */
    fun convert(value: String, from: String?, to: String?): String {
        if (from.isNullOrBlank() || to.isNullOrBlank()) return value
        val a = canonical(from)
        val b = canonical(to)
        if (a == b) return value
        val number = value.trim().toDoubleOrNull() ?: return value
        val family = FAMILIES.firstOrNull { a in it && b in it } ?: return value
        val factor = family.getValue(a) / family.getValue(b)
        if (factor == 1.0) return value
        return format2(number * factor)
    }

    /**
     * Canonical form of a unit string: lowercase, no spaces, "×"/"x" and
     * "*"/"^" folded ("10*9/L", "10^9/L", "x10^9/L", "10E9/L" → "10^9/l"),
     * "µ" → "u", "cumm"/"cu mm"/"mm3"/"mm^3" → "cumm", "cells/" prefix dropped
     * for count units.
     */
    fun canonical(u: String): String {
        var s = u.lowercase()
            .filterNot { it.isWhitespace() }
            .replace('µ', 'u')       // micro sign U+00B5
            .replace('μ', 'u')       // greek mu U+03BC
            .replace('×', 'x')
            .replace('*', '^')
        // "x10^9/l" → "10^9/l"; "10e9/l" → "10^9/l"
        if (s.startsWith("x10")) s = s.removePrefix("x")
        s = POWER_E.replace(s) { "10^${it.groupValues[1]}" }
        s = s.replace("cu.mm", "cumm")
            .replace("mm^3", "cumm")
            .replace("mm3", "cumm")
        s = s.replace("cells/", "/")
        return s
    }

    private val POWER_E = Regex("10e(\\d+)")

    /** Fixed-point with at most 2 decimals, trailing zeros trimmed; no locale, no java. */
    private fun format2(x: Double): String {
        if (x.isNaN() || x.isInfinite()) return x.toString()
        val negative = x < 0
        val scaled = (abs(x) * 100.0 + 0.5).toLong()     // round half up at the 2nd decimal
        val whole = scaled / 100
        val frac = (scaled % 100).toInt()
        val text = when {
            frac == 0 -> whole.toString()
            frac % 10 == 0 -> "$whole.${frac / 10}"
            else -> "$whole.${if (frac < 10) "0$frac" else frac.toString()}"
        }
        return if (negative && text != "0") "-$text" else text
    }
}
