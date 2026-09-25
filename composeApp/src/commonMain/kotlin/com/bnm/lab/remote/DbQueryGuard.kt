package com.bnm.lab.remote

/**
 * What SQL the `db.query` support tool will run at all.
 *
 * The connection it runs on is opened READ-ONLY by the platform ([ReadOnlySql]),
 * so this guard is not what stops a write — it is what stops the engineer from
 * *trying*, gives a plain reason instead of a driver error, and enforces the
 * two rules the read-only mode cannot: a table is off limits even for a SELECT
 * until the owner ticked the box that covers what it holds — records for
 * patients, results and staff, analyzer data for the frames as they arrived —
 * and a few columns are never shared whatever the consent.
 *
 * Rules, in order:
 * 1. one statement — a `;` anywhere but the very end is refused;
 * 2. it starts with SELECT, WITH, EXPLAIN or PRAGMA;
 * 3. a PRAGMA must be a read: a name from [READ_PRAGMAS]; never `= value`, and
 *    a `(…)` argument only for the pragmas that take a table/index name
 *    ([ARG_PRAGMAS]) — `PRAGMA cache_size(1000)` is the assignment form;
 * 4. no identifier in the text may be one of [DENIED_COLUMNS] — support never
 *    needs a PIN hash or a signature image, so they are refused with or
 *    without consent (and a `SELECT *` that would carry them is blanked by
 *    the tool, see [hiddenColumns]);
 * 5. no identifier may be a table from [RECORD_TABLES] without the owner's
 *    RECORDS consent, nor one from [ANALYZER_TABLES] without ANALYZER_DATA
 *    (case-insensitive whole-word match — a column called `patients` would
 *    trip it too, which is the safe direction).
 */
object DbQueryGuard {

    /**
     * Tables an engineer may only touch under "Allow looking up records".
     * `staff` is here because it carries PIN hashes, signature images and
     * council registration numbers — the people, not the patients, but the
     * owner's tick is the same one.
     */
    val RECORD_TABLES: Set<String> = setOf(
        "patients", "lab_orders", "lab_order_tests", "lab_results", "instrument_results",
        "lab_result_graphs", "emr_inbox", "ecom_entity", "lab_reports", "referrer_payouts",
        "billing_outbox", "staff",
    )

    /**
     * Tables that hold analyzer frames, gated by "Share analyzer data" — the
     * same tick `instruments.log` needs, because `instrument_log.raw` IS the
     * frame as it arrived: HL7 PID segments with the patient's name, the Mispa
     * PatientID. `instruments.log` hands those bytes over scrubbed
     * ([FrameScrubber.scrubRaw]); a SELECT would not, so the tick at least has
     * to be there.
     */
    val ANALYZER_TABLES: Set<String> = setOf("instrument_log")

    /** Every table that needs some tick — for tests and for callers that list them. */
    val PHI_TABLES: Set<String> = RECORD_TABLES + ANALYZER_TABLES

    /**
     * Columns support never sees, consent or not: a salted single-round SHA-256
     * of a short numeric PIN is recovered offline in well under a second, and
     * the owner's PIN is the credential that starts a support session; the
     * signature image is what prints on every report.
     */
    val DENIED_COLUMNS: Set<String> = setOf("pin_hash", "signature_png")

    /** PRAGMAs that only read. Everything else — journal_mode=…, foreign_keys=…, wal_checkpoint — is refused. */
    val READ_PRAGMAS: Set<String> = setOf(
        "table_info", "table_xinfo", "table_list", "index_list", "index_info", "index_xinfo",
        "foreign_key_list", "database_list", "collation_list", "compile_options", "function_list",
        "schema_version", "user_version", "page_size", "page_count", "freelist_count", "journal_mode",
        "encoding", "application_id", "integrity_check", "quick_check", "data_version", "cache_size",
        "busy_timeout", "synchronous", "auto_vacuum", "read_uncommitted", "foreign_keys",
    )

    /** Read pragmas that take a `(table_or_index)` argument; every other pragma must be bare. */
    val ARG_PRAGMAS: Set<String> = setOf(
        "table_info", "table_xinfo", "index_list", "index_info", "index_xinfo", "foreign_key_list",
        "integrity_check", "quick_check",
    )

    const val MAX_ROWS = 200
    const val MAX_STRING_CHARS = 2_000

    /** What a blanked cell of a denied column reads as. */
    const val HIDDEN_CELL = "<not shared with support>"

    private val LEADING_KEYWORD = Regex("""^\s*(select|with|explain|pragma)\b""", RegexOption.IGNORE_CASE)
    private val PRAGMA_SHAPE = Regex("""^\s*pragma\s+(?:[a-z_]+\.)?([a-z_]+)\s*(\(\s*[A-Za-z0-9_."']+\s*\))?\s*;?\s*$""", RegexOption.IGNORE_CASE)
    private val IDENTIFIER = Regex("""[A-Za-z_][A-Za-z0-9_]*""")
    /** A quoted span, terminated or not — what SQLite echoes back in "unrecognized token: …". */
    private val QUOTED = Regex("""'[^']*'?|"[^"]*"?""")

    sealed class Verdict {
        data object Allowed : Verdict()
        data class Refused(val reason: String) : Verdict()
    }

    fun check(sql: String, consent: SupportConsent): Verdict {
        val text = sql.trim()
        if (text.isEmpty()) return Verdict.Refused("No SQL given.")
        val semi = text.indexOf(';')
        if (semi >= 0 && semi != text.length - 1) {
            return Verdict.Refused("One statement at a time — a ';' inside the text is refused.")
        }
        val keyword = LEADING_KEYWORD.find(text)?.groupValues?.get(1)?.lowercase()
            ?: return Verdict.Refused("Only SELECT, WITH, EXPLAIN or a read-only PRAGMA may run here — this connection is read-only.")
        if (keyword == "pragma") {
            val m = PRAGMA_SHAPE.find(text)
                ?: return Verdict.Refused("Only read-only PRAGMAs run here (PRAGMA name or PRAGMA name(table)); assignments are refused.")
            val name = m.groupValues[1].lowercase()
            if (name !in READ_PRAGMAS) return Verdict.Refused("PRAGMA $name is not on the read-only list.")
            if (m.groupValues[2].isNotEmpty() && name !in ARG_PRAGMAS) {
                return Verdict.Refused("PRAGMA $name(value) sets the value — only PRAGMA $name (bare) may run here.")
            }
        }
        val identifiers = IDENTIFIER.findAll(text).map { it.value.lowercase() }.toList()
        identifiers.firstOrNull { it in DENIED_COLUMNS }?.let { hit ->
            return Verdict.Refused("'$hit' is never shared with support, whatever the owner allowed — leave it out of the query.")
        }
        for (id in identifiers) {
            if (id in ANALYZER_TABLES && !consent.analyzerData) {
                return Verdict.Refused("'$id' holds analyzer frames as they arrived — the owner has not shared analyzer data in this session.")
            }
            if (id in RECORD_TABLES && !consent.records) {
                return Verdict.Refused("'$id' holds patient or staff records — the owner has not allowed looking up records in this session.")
            }
        }
        return Verdict.Allowed
    }

    /** Which result columns a `SELECT *` must blank — by label, case-insensitively. */
    fun hiddenColumns(columns: List<String>): List<String> = columns.filter { it.lowercase() in DENIED_COLUMNS }

    /** Table names the text mentions — for the audit summary (never the SQL itself). */
    fun tablesMentioned(sql: String, known: Collection<String>): List<String> {
        val ids = IDENTIFIER.findAll(sql).map { it.value.lowercase() }.toSet()
        return known.filter { it.lowercase() in ids }.sorted()
    }

    fun clampRows(requested: Int?): Int = (requested ?: 50).coerceIn(1, MAX_ROWS)

    /** Cut long strings so one blob cell cannot fill the relay's message budget. */
    fun cut(value: String?): String? = value?.let { if (it.length > MAX_STRING_CHARS) it.take(MAX_STRING_CHARS) + "…" else it }

    /**
     * A driver error worded for the engineer: what went wrong, without the
     * text that went wrong. SQLite echoes the offending span back — an
     * unterminated literal such as `WHERE name = 'Ramesh Kumar` comes back as
     * `unrecognized token: "'Ramesh Kumar"` — so every quoted span becomes
     * `'…'`. Table and column names stay; they are schema, and the engineer
     * needs them. The audit row never sees this string at all: `db.query`
     * gives it a fixed summary, because a bare-word literal (`WHERE name =
     * Ramesh` → `no such column: Ramesh`) has no quotes to strip.
     */
    fun describeError(message: String?): String {
        val m = message?.let { QUOTED.replace(it, "'…'") }?.replace(Regex("""\s+"""), " ")?.trim()?.take(160)
        return "SQL error — check the statement" + (if (m.isNullOrEmpty()) "" else " ($m)")
    }
}
