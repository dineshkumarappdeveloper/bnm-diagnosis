package com.bnm.diagnosis.instruments

/**
 * HL7 v2.3.1 over MLLP — the wire format of the TCP analyzers (Mindray
 * BC-5130, Erba CXL Pro Plus, most 5-part hematology and chemistry boxes).
 *
 * Pure text handling, no clock and no I/O: the listener hands us the socket
 * buffer, we hand back message text; the driver reads segments/fields; the
 * ACK builder produces the bytes the analyzer is waiting for before it will
 * send the next sample. Everything is tolerant by design — an analyzer that
 * sends a slightly-off message must never crash the listener, it must just
 * fail to match and land in the claim queue.
 */

/** MLLP: <VT 0x0B> message <FS 0x1C> <CR 0x0D>. */
object Mllp {
    const val SB: Char = '\u000B'
    const val EB: Char = '\u001C'
    const val CR: Char = '\r'

    /**
     * First complete <SB>…<EB><CR> in [buffer] → (message text WITHOUT framing, remainder).
     * Junk before the first SB is dropped. Incomplete → (null, buffer kept from its last SB —
     * or "" when there is no SB at all). A stream of back-to-back messages drains one call at
     * a time.
     */
    fun extract(buffer: String): Pair<String?, String> {
        val start = buffer.indexOf(SB)
        if (start < 0) return null to ""                       // nothing framed yet — junk only
        val end = buffer.indexOf(EB, start + 1)
        if (end < 0) {
            // Body still in flight. If the analyzer restarted a frame (a second SB), the
            // earlier partial can never complete — keep only from the last SB.
            return null to buffer.substring(buffer.lastIndexOf(SB))
        }
        // <EB> must be followed by <CR>; the CR may simply not have arrived yet.
        if (end + 1 >= buffer.length) return null to buffer.substring(start)
        val message = buffer.substring(start + 1, end)
        val afterEb = if (buffer[end + 1] == CR) end + 2 else end + 1   // tolerate a missing CR
        return message to buffer.substring(afterEb)
    }

    /** UTF-8 bytes of [message] wrapped in SB … EB CR. */
    fun wrap(message: String): ByteArray = "$SB$message$EB$CR".encodeToByteArray()
}

data class Hl7Delimiters(
    val field: Char = '|',
    val component: Char = '^',
    val repetition: Char = '~',
    val escape: Char = '\\',
    val subcomponent: Char = '&',
)

/**
 * One segment. [fields] is in HL7 numbering order with the segment name removed, so
 * `fields[0]` is field 1. For MSH the field separator itself is `fields[0]` and the
 * encoding characters are `fields[1]`, exactly as the spec numbers them.
 */
class Hl7Segment(val name: String, val fields: List<String>, val delimiters: Hl7Delimiters) {

    /**
     * HL7 numbering: field(1) is the first field after the segment name. For MSH, field(1) == the
     * field separator ("|") and field(2) == the encoding characters ("^~\\&"), so MSH-9 is field(9)
     * as in the spec. Out of range → "".
     */
    fun field(n: Int): String = if (n < 1) "" else fields.getOrNull(n - 1) ?: ""

    /** [c]-th component (1-based) of field [n]'s FIRST repetition, unescaped. Out of range → "". */
    fun component(n: Int, c: Int): String {
        if (c < 1) return ""
        val first = repetitions(n).firstOrNull() ?: return ""
        val raw = first.split(delimiters.component).getOrNull(c - 1) ?: return ""
        return Hl7Escape.unescape(raw, delimiters)
    }

    /** Field [n] split on the repetition delimiter. */
    fun repetitions(n: Int): List<String> {
        val f = field(n)
        return if (f.isEmpty()) emptyList() else f.split(delimiters.repetition)
    }

    override fun toString(): String = name + delimiters.field + fields.joinToString(delimiters.field.toString())
}

class Hl7Message(val segments: List<Hl7Segment>, val delimiters: Hl7Delimiters) {
    val msh: Hl7Segment? get() = segment("MSH")
    /** MSH-9, e.g. "ORU^R01"; "" when absent. */
    val messageType: String get() = msh?.field(9) ?: ""
    /** MSH-10. */
    val controlId: String get() = msh?.field(10) ?: ""
    /** MSH-11 ("P" results, "Q" QC). */
    val processingId: String get() = msh?.field(11) ?: ""
    /** MSH-3. */
    val sendingApplication: String get() = msh?.field(3) ?: ""
    /** MSH-4. */
    val sendingFacility: String get() = msh?.field(4) ?: ""

    fun segment(name: String): Hl7Segment? = segments.firstOrNull { it.name == name }
    fun segments(name: String): List<Hl7Segment> = segments.filter { it.name == name }

    companion object {
        /**
         * Segments split on CR (LF and CRLF tolerated; blank lines skipped). Delimiters come from
         * MSH-1/MSH-2; a message that does not start with MSH still parses with the defaults.
         * Never throws.
         */
        fun parse(text: String): Hl7Message {
            val lines = text.split('\r', '\n').filter { it.isNotBlank() }
            val d = delimitersOf(lines.firstOrNull())
            val segments = lines.map { line ->
                if (line.startsWith("MSH") && line.length >= 4) {
                    // MSH-1 IS the separator char, so the split has to start after it — otherwise
                    // the encoding characters would land one field too early.
                    Hl7Segment("MSH", listOf(d.field.toString()) + line.substring(4).split(d.field), d)
                } else {
                    val parts = line.split(d.field)
                    Hl7Segment(parts[0], parts.drop(1), d)
                }
            }
            return Hl7Message(segments, d)
        }

        /** MSH|^~\& — positions 3..7 define every delimiter; anything shorter keeps the defaults. */
        private fun delimitersOf(first: String?): Hl7Delimiters {
            if (first == null || !first.startsWith("MSH") || first.length < 4) return Hl7Delimiters()
            val field = first[3]
            fun enc(i: Int, default: Char): Char = first.getOrNull(4 + i)?.takeIf { it != field } ?: default
            return Hl7Delimiters(
                field = field,
                component = enc(0, '^'),
                repetition = enc(1, '~'),
                escape = enc(2, '\\'),
                subcomponent = enc(3, '&'),
            )
        }
    }
}

object Hl7Escape {
    /** \F\ \S\ \T\ \R\ \E\ \.br\ → the delimiter / CR; unknown sequences are left untouched. */
    fun unescape(s: String, d: Hl7Delimiters): String {
        if (s.indexOf(d.escape) < 0) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch != d.escape) { out.append(ch); i++; continue }
            val close = s.indexOf(d.escape, i + 1)
            if (close < 0) { out.append(s, i, s.length); break }   // dangling escape — keep verbatim
            val replacement = when (s.substring(i + 1, close)) {
                "F" -> d.field.toString()
                "S" -> d.component.toString()
                "T" -> d.subcomponent.toString()
                "R" -> d.repetition.toString()
                "E" -> d.escape.toString()
                ".br" -> "\r"
                else -> null
            }
            if (replacement == null) {
                out.append(ch)          // unknown sequence: emit the opening escape, rescan from the next char
                i++
            } else {
                out.append(replacement)
                i = close + 1
            }
        }
        return out.toString()
    }
}

object Hl7Ack {
    /**
     * The ACK for [received]:
     * MSH|<enc>|<sendingApp>||<received MSH-3>|<received MSH-4>|<timestamp>||ACK^<event>|<ackControlId>|<received MSH-11>|2.3.1||||||UNICODE
     * then MSA|<code>|<received MSH-10>. <event> is the received MSH-9 event ("R01" for ORU^R01,
     * else "R01"). Fields not listed are empty. Segments end with CR. Returned MLLP-wrapped.
     * [timestamp] is yyyyMMddHHmmss (caller supplies it — no clock in here, so tests are
     * deterministic).
     */
    fun forMessage(
        received: Hl7Message,
        code: String = "AA",
        sendingApp: String = "BNMDiagnosis",
        ackControlId: String,
        timestamp: String,
    ): ByteArray {
        val event = received.messageType
            .split(received.delimiters.component)
            .getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?: "R01"
        // Always the standard encoding characters — the analyzer's own MSH-2 only governs how we
        // READ its message; ours is written in the form every vendor doc shows.
        val msh = "MSH|^~\\&|$sendingApp||${received.sendingApplication}|${received.sendingFacility}|" +
            "$timestamp||ACK^$event|$ackControlId|${received.processingId}|2.3.1||||||UNICODE"
        val msa = "MSA|$code|${received.controlId}"
        return Mllp.wrap("$msh\r$msa\r")
    }
}
