package com.bnm.lab.remote

/**
 * What SQL the `db.query` support tool will run at all.
 *
 * The connection it runs on is opened READ-ONLY by the platform ([ReadOnlySql]),
 * so this guard is not what stops a write — it is what stops the engineer from
 * *trying*, gives a plain reason instead of a driver error, and enforces the
 * one rule the read-only mode cannot: without the owner's "Allow looking up
 * records" consent, the tables that hold patients and results are off limits
 * even for a SELECT.
 *
 * Rules, in order:
 * 1. one statement — a `;` anywhere but the very end is refused;
 * 2. it starts with SELECT, WITH, EXPLAIN or PRAGMA;
 * 3. a PRAGMA must be a read: a name from [READ_PRAGMAS]; never `= value`, and
 *    a `(…)` argument only for the pragmas that take a table/index name
 *    ([ARG_PRAGMAS]) — `PRAGMA cache_size(1000)` is the assignment form;
 * 4. without RECORDS consent, no identifier in the text may be one of
 *    [PHI_TABLES] (case-insensitive whole-word match — a column called
 *    `patients` would trip it too, which is the safe direction).
 */
object DbQueryGuard {

    /** Tables an engineer may only touch under "Allow looking up records". */
    val PHI_TABLES: Set<String> = setOf(
        "patients", "lab_orders", "lab_order_tests", "lab_results", "instrument_results",
        "lab_result_graphs", "emr_inbox", "ecom_entity", "lab_reports", "referrer_payouts",
        "billing_outbox",
    )

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

    private val LEADING_KEYWORD = Regex("""^\s*(select|with|explain|pragma)\b""", RegexOption.IGNORE_CASE)
    private val PRAGMA_SHAPE = Regex("""^\s*pragma\s+(?:[a-z_]+\.)?([a-z_]+)\s*(\(\s*[A-Za-z0-9_."']+\s*\))?\s*;?\s*$""", RegexOption.IGNORE_CASE)
    private val IDENTIFIER = Regex("""[A-Za-z_][A-Za-z0-9_]*""")

    sealed class Verdict {
        data object Allowed : Verdict()
        data class Refused(val reason: String) : Verdict()
    }

    fun check(sql: String, recordsConsent: Boolean): Verdict {
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
        if (!recordsConsent) {
            val hit = IDENTIFIER.findAll(text).map { it.value.lowercase() }.firstOrNull { it in PHI_TABLES }
            if (hit != null) {
                return Verdict.Refused("'$hit' holds patient records — the owner has not allowed looking up records in this session.")
            }
        }
        return Verdict.Allowed
    }

    /** Table names the text mentions — for the audit summary (never the SQL itself). */
    fun tablesMentioned(sql: String, known: Collection<String>): List<String> {
        val ids = IDENTIFIER.findAll(sql).map { it.value.lowercase() }.toSet()
        return known.filter { it.lowercase() in ids }.sorted()
    }

    fun clampRows(requested: Int?): Int = (requested ?: 50).coerceIn(1, MAX_ROWS)

    /** Cut long strings so one blob cell cannot fill the relay's message budget. */
    fun cut(value: String?): String? = value?.let { if (it.length > MAX_STRING_CHARS) it.take(MAX_STRING_CHARS) + "…" else it }
}
