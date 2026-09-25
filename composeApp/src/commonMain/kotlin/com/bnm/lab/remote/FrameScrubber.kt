package com.bnm.lab.remote

/**
 * What of an analyzer frame may leave the lab PC during a support session.
 *
 * Two levels, matching the two things the owner can consent to:
 *
 * - [maskIds] — for text that is ALWAYS shareable (log summaries, unmatched
 *   rows): every identifier-shaped token keeps its shape and its last four
 *   characters (`ACC-S1-00042` → `A***0042`), so the engineer can see that an
 *   id arrived, roughly what it looks like and whether two rows carry the same
 *   one, without learning the id.
 * - [scrubRaw] — for the raw frame, shared only under "analyzer data" consent:
 *   the patient's NAME and demographics go, everything a mapping problem needs
 *   stays (values, units, codes, sample ids, framing). HL7: PID-5 (name), PID-7
 *   (DOB), PID-8 (sex), PID-11 (address), PID-13 (phone), PID-19 (SSN), the
 *   whole NK1 segment and every free-text OBX (ST/TX/FT) are blanked. Mispa
 *   Count X: field 3 of the head section (PatientID) is blanked. A driver this
 *   object does not know gets NO raw at all — summaries only.
 *
 * Pure text, no I/O. Never throws on odd input — a half-cut excerpt scrubs as
 * far as it goes.
 */
object FrameScrubber {

    /** Drivers whose raw frames [scrubRaw] knows how to clean. */
    val SCRUBBABLE_DRIVERS: Set<String> = setOf("mindray_hl7", "mispa_count_x")

    private val HL7_PID_BLANK = setOf(5, 7, 8, 11, 13, 19)
    private val HL7_TEXT_TYPES = setOf("ST", "TX", "FT")

    /** Identifier-shaped: ≥ 5 chars, letters/digits/`_`/`-` only, at least one digit. */
    private val ID_TOKEN = Regex("""[A-Za-z0-9_-]{5,}""")

    /** `ACC-S1-00042` → `A***0042`; anything shorter than five characters is starred out. */
    fun maskId(id: String): String {
        val s = id.trim()
        if (s.isEmpty()) return s
        if (s.length <= 4) return "*".repeat(s.length)
        return s.first() + "***" + s.takeLast(4)
    }

    /**
     * Mask every identifier-shaped token in free text. Decimals (`9.55`),
     * units (`10*9/L`), ports (`5500`) and words without digits pass through —
     * a summary must stay readable, only the ids become shapes.
     */
    fun maskIds(text: String): String =
        ID_TOKEN.replace(text) { m ->
            val t = m.value
            if (t.any { it.isDigit() }) maskId(t) else t
        }

    /**
     * The raw frame with names and demographics removed, or null when the
     * driver is unknown (the caller then shares summaries only).
     */
    fun scrubRaw(driverKey: String, raw: String): String? = when (driverKey) {
        "mindray_hl7" -> scrubHl7(raw)
        "mispa_count_x" -> scrubMispa(raw)
        else -> null
    }

    // ── HL7 v2 ──

    private val SEGMENT_LINE = Regex("[^\r\n]+")

    /**
     * Segment by segment, separators kept as they were. PID fields are blanked
     * in place so the field count (and therefore every later field's position)
     * is unchanged; NK1 is removed outright; free-text OBX-5 is blanked but the
     * row stays so OBX numbering still reads.
     */
    fun scrubHl7(raw: String): String = SEGMENT_LINE.replace(raw) { m ->
        val line = m.value
        val fieldSep = fieldSeparatorOf(raw)
        val parts = line.split(fieldSep).toMutableList()
        when (parts[0]) {
            "PID" -> {
                for (n in HL7_PID_BLANK) if (n < parts.size) parts[n] = ""
                parts.joinToString(fieldSep.toString())
            }
            "NK1" -> ""
            "OBX" -> {
                val type = parts.getOrNull(2)?.trim()?.uppercase()
                if (type in HL7_TEXT_TYPES && parts.size > 5) parts[5] = ""
                parts.joinToString(fieldSep.toString())
            }
            else -> line
        }
    }

    /** MSH-1 is the field separator; anything without an MSH keeps `|`. */
    private fun fieldSeparatorOf(raw: String): Char {
        val i = raw.indexOf("MSH")
        return if (i >= 0 && i + 3 < raw.length) raw[i + 3] else '|'
    }

    // ── Mispa Count X ──

    /**
     * `$$$Date$Seq$Specimen$Patient$…#…###` — the head section is the first
     * `#`-delimited section; its `$`-delimited field 3 is the PatientID.
     */
    fun scrubMispa(raw: String): String {
        val start = raw.indexOf("$$$")
        if (start < 0) return raw
        val bodyStart = start + 3
        val headEnd = raw.indexOf('#', bodyStart).let { if (it < 0) raw.length else it }
        val head = raw.substring(bodyStart, headEnd).split('$').toMutableList()
        if (head.size > 3) head[3] = ""
        return raw.substring(0, bodyStart) + head.joinToString("$") + raw.substring(headEnd)
    }
}
