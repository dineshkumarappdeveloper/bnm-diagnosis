package com.bnm.lab.instruments

/**
 * Agappe **Mispa Count X** (3-part hematology) LIS frame parser.
 *
 * Per the vendor's "LIS Document": one-way auto-transmission over RS-232
 * (115200-8-N-1, no flow control) after every run, in a proprietary
 * `$`-delimited format:
 *
 * ```
 * $$$Date$SequenceID$SpecimenID$PatientID$WBC$RBC$PLT$HGB$HCT$MCV$MCH$MCHC
 * $RDW-SD$RDW-CV$MPV$LYMP%$MID%$GRAN%$LYMP#$MID#$GRAN#$PCT$PDW$LPCR
 * #<WBC histogram — 128 values, '$'-separated>
 * #<RBC histogram — 128 values>
 * #<PLT histogram — 128 values>
 * #<discriminators AND histogram flags, '@'-separated>
 * #<disease flags>
 * #<parameter flags, '$'-separated>###
 * ```
 *
 * Frames start with `$$$` and end with `###`. The histograms are MEASURED
 * data — the whole reason report curves can be genuine.
 */
object MispaCountX {

    /** Header parameter order, exactly as the vendor doc lists it. */
    val PARAM_ORDER = listOf(
        "WBC", "RBC", "PLT", "HGB", "HCT", "MCV", "MCH", "MCHC",
        "RDW-SD", "RDW-CV", "MPV", "LYMP%", "MID%", "GRAN%",
        "LYMP#", "MID#", "GRAN#", "PCT", "PDW", "LPCR",
    )

    private const val FRAME_START = "$$$"
    private const val FRAME_END = "###"

    data class Frame(
        val date: String?,
        val sequenceId: String?,
        val specimenId: String?,
        val patientId: String?,
        /** Analyzer param name → value, in PARAM_ORDER. Blank values dropped. */
        val params: Map<String, String>,
        /** 'wbc' | 'rbc' | 'plt' → measured histogram points. */
        val histograms: Map<String, List<Double>>,
        val discriminators: String?,
        val diseaseFlags: List<String>,
        val raw: String,
    )

    /**
     * Pull the first complete frame out of an accumulation buffer.
     * Returns (frame-or-null, remaining buffer). Garbage before the first
     * `$$$` and consumed frames are dropped from the remainder, so a stream
     * of back-to-back frames drains one call at a time.
     */
    fun extractFrame(buffer: String): Pair<Frame?, String> {
        val (frameText, rest) = extractFrameText(buffer)
        return frameText?.let { parse(it) } to rest
    }

    /**
     * Same as [extractFrame] but hands back the frame TEXT, so the caller can
     * tell "no complete frame yet" (null) from "a frame arrived that [parse]
     * could not read" — the second is what the engine logs as an error.
     */
    fun extractFrameText(buffer: String): Pair<String?, String> {
        val start = buffer.indexOf(FRAME_START)
        if (start < 0) {
            // No frame start anywhere — keep only a tail in case `$$` arrived
            // and the third `$` is still in flight.
            return null to buffer.takeLast(8)
        }
        val end = buffer.indexOf(FRAME_END, start + FRAME_START.length)
        if (end < 0) return null to buffer.substring(start)
        val frameText = buffer.substring(start, end + FRAME_END.length)
        val rest = buffer.substring(end + FRAME_END.length)
        return frameText to rest
    }

    /** Parse one complete `$$$…###` frame. Null when structurally unusable. */
    fun parse(frameText: String): Frame? {
        if (!frameText.startsWith(FRAME_START)) return null
        val body = frameText
            .removePrefix(FRAME_START)
            .removeSuffix(FRAME_END)
            .trimEnd('#')            // tolerate `####` tails from '#'-final sections
        val sections = body.split('#')
        if (sections.isEmpty()) return null

        // Section 0: Date$Seq$Specimen$Patient$<20 params>
        val head = sections[0].split('$')
        fun headField(i: Int): String? = head.getOrNull(i)?.trim()?.takeIf { it.isNotEmpty() }
        val params = LinkedHashMap<String, String>()
        PARAM_ORDER.forEachIndexed { idx, name ->
            headField(4 + idx)?.let { params[name] = it }
        }

        val histograms = LinkedHashMap<String, List<Double>>()
        fun histSection(i: Int, kind: String) {
            val values = sections.getOrNull(i)
                ?.split('$')
                ?.mapNotNull { it.trim().toDoubleOrNull() }
                .orEmpty()
            if (values.size >= 8) histograms[kind] = values
        }
        histSection(1, "wbc")
        histSection(2, "rbc")
        histSection(3, "plt")

        val diseaseFlags = sections.getOrNull(5)
            ?.split('?', '\n', '\r')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

        if (params.isEmpty() && histograms.isEmpty()) return null
        return Frame(
            date = headField(0),
            sequenceId = headField(1),
            specimenId = headField(2)?.takeIf { it != "0" },  // '0' = not keyed on the analyzer
            patientId = headField(3)?.takeIf { it != "0" },
            params = params,
            histograms = histograms,
            discriminators = sections.getOrNull(4)?.trim()?.takeIf { it.isNotEmpty() },
            diseaseFlags = diseaseFlags,
            raw = frameText,
        )
    }
}
