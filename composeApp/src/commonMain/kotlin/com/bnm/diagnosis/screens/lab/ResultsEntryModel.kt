package com.bnm.diagnosis.screens.lab

import com.bnm.diagnosis.lab.LabOrderTest
import com.bnm.diagnosis.lab.LabResult
import com.bnm.diagnosis.lab.ResultGraph

/**
 * The results-entry screen's table of contents: one [EntryGroup] per ordered
 * test, in order, each owning a contiguous run of the flat grid rows. Pure
 * functions, so the rail's counts, the provenance line and the Enter-key
 * order are testable without Compose.
 *
 * Row indices are GLOBAL (the flat grid the focus requesters are keyed by):
 * the tile shows one group's slice, the keyboard walks the whole order.
 */
internal data class EntryGroup(
    val testId: String,
    val name: String,
    /** Catalog code ("CBC", "ESR") for the narrow chip strip; blank when unknown. */
    val code: String,
    /** First and last global row index of this test (inclusive). */
    val first: Int,
    val last: Int,
    val entered: Int,
    val total: Int,
    /** Who put the values in and when; null until the first value lands. */
    val provenance: Provenance?,
    /** An analyzer touched this test — by name on `entered_by`, or because it
     *  left histograms behind (which survives an instrument rename). */
    val analyzer: Boolean,
    /** The analyzer's own alerts and abnormal flags, ready to print in a strip. */
    val alerts: List<String>,
) {
    val done: Boolean get() = total > 0 && entered >= total
    val started: Boolean get() = entered > 0
    val range: IntRange get() = first..last
}

/** Who entered a test's values: the name on most of its rows, how many other
 *  names also appear, the latest stamp, and whether the name is an analyzer. */
internal data class Provenance(
    val who: String,
    val at: String?,
    val analyzer: Boolean,
    val others: Int,
) {
    /** Rail line: "Mindray BC-5130 · 10:15", "S. Kumar +1 · 10:22". */
    fun short(time: (String) -> String): String = buildString {
        append(who)
        if (others > 0) append(" +").append(others)
        at?.let { append(" · ").append(time(it)) }
    }

    /** Tile line: "Filled by Mindray BC-5130 · 10:15", "Entered by S. Kumar and 1 more · 10:22".
     *  [asAnalyzer] lets the caller pass the GROUP's verdict (name match OR
     *  graphs), so a renamed instrument never reads "Entered by". */
    fun long(asAnalyzer: Boolean = analyzer, time: (String) -> String): String = buildString {
        append(if (asAnalyzer) "Filled by " else "Entered by ")
        append(who)
        if (others == 1) append(" and 1 more") else if (others > 1) append(" and $others more")
        at?.let { append(" · ").append(time(it)) }
    }
}

/**
 * Group the flat grid by test. [rowTestIds] is the test id of every grid row in
 * order (rows of one test are contiguous — [buildGrid] emits them that way);
 * tests with no rows at all (a catalog entry that vanished AND no frozen rows)
 * are skipped, they have nothing to enter.
 */
internal fun buildEntryGroups(
    tests: List<LabOrderTest>,
    codes: Map<String, String>,
    rowTestIds: List<String>,
    rowKeys: List<String>,
    results: Map<String, LabResult>,
    instrumentNames: Set<String>,
    graphs: List<ResultGraph>,
): List<EntryGroup> {
    val alertsByTest = analyzerAlerts(graphs)
    val graphTests = graphs.map { it.testId }.toSet()
    return tests.mapNotNull { t ->
        val first = rowTestIds.indexOf(t.testId)
        if (first < 0) return@mapNotNull null
        val last = rowTestIds.lastIndexOf(t.testId)
        val rows = (first..last).mapNotNull { results[rowKeys[it]] }
        val entered = rows.filter { it.isEntered }
        val prov = provenanceOf(entered, instrumentNames)
        EntryGroup(
            testId = t.testId,
            name = t.testName,
            code = codes[t.testId].orEmpty(),
            first = first, last = last,
            entered = entered.size, total = last - first + 1,
            provenance = prov,
            analyzer = (prov?.analyzer == true) || t.testId in graphTests,
            alerts = alertsByTest[t.testId].orEmpty(),
        )
    }
}

/** The name on most of [entered] rows wins; ties go to the latest stamp. */
internal fun provenanceOf(entered: List<LabResult>, instrumentNames: Set<String>): Provenance? {
    val named = entered.filter { !it.enteredBy.isNullOrBlank() }
    if (named.isEmpty()) return null
    val byName = named.groupBy { it.enteredBy!!.trim() }
    val who = byName.entries
        .sortedWith(compareByDescending<Map.Entry<String, List<LabResult>>> { it.value.size }
            .thenByDescending { e -> e.value.maxOfOrNull { it.enteredAt.orEmpty() }.orEmpty() })
        .first().key
    val lower = instrumentNames.map { it.trim().lowercase() }.toSet()
    return Provenance(
        who = who,
        at = entered.mapNotNull { it.enteredAt }.maxOrNull(),   // the latest entry, named or not
        analyzer = who.lowercase() in lower,
        others = byName.size - 1,
    )
}

/**
 * Per test: the analyzer's alert texts ("Multiple alerts") and its own abnormal
 * flags ("PLT:H" → "PLT H"), from the meta the engine stores beside the graphs.
 * Alerts first, then flags, no duplicates.
 */
internal fun analyzerAlerts(graphs: List<ResultGraph>): Map<String, List<String>> =
    graphs.groupBy { it.testId }.mapValues { (_, gs) ->
        val alerts = gs.flatMap { g -> g.meta["alerts"].orEmpty().split(';') }
        val flags = gs.flatMap { g -> g.meta["flags"].orEmpty().split(';') }
            .map { it.replace(':', ' ') }
        (alerts + flags).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }.filterValues { it.isNotEmpty() }

/**
 * Enter = "the next thing that still needs a value", staying on the test in
 * hand first: forward within the test, then an earlier gap in the same test,
 * then the next test with a gap, then wrap to an earlier test, else simply
 * the next row. Null when nothing is left (the caller drops focus, which
 * commits the field being left).
 */
internal fun nextEmptyIndex(groups: List<EntryGroup>, from: Int, size: Int, blank: (Int) -> Boolean): Int? {
    val g = groups.firstOrNull { from in it.range }
        ?: return (from + 1 until size).firstOrNull(blank) ?: (0 until from).firstOrNull(blank) ?: (from + 1).takeIf { it < size }
    return (from + 1..g.last).firstOrNull(blank)
        ?: (g.first until from).firstOrNull(blank)
        ?: (g.last + 1 until size).firstOrNull(blank)
        ?: (0 until g.first).firstOrNull(blank)
        ?: (from + 1).takeIf { it < size }
}

/**
 * May the live flow overwrite the box for [key]? Never while its commit is in
 * flight ([pending]: the DB still holds the OLD value, and the box the NEW
 * one), and never under a cursor that has typed ([focusedKey] + [dirty]). A
 * focused box nobody has typed in IS reseeded — that is how the technician
 * sees an analyzer value arrive in the very cell they were waiting on.
 */
internal fun reseedable(key: String, focusedKey: String?, dirty: Set<String>, pending: Set<String>): Boolean =
    key !in pending && !(key == focusedKey && key in dirty)

/**
 * Commit on blur ONLY what the operator edited. Comparing the box with the
 * stored value is not enough once results refresh live: a blank box over a
 * value the analyzer just wrote would otherwise commit "" and erase it.
 */
internal fun shouldCommitOnBlur(wasDirty: Boolean, text: String, stored: String?): Boolean =
    wasDirty && text.trim() != stored.orEmpty().trim()

/** Where the screen opens: the first test still missing a value, else the first test. */
internal fun initialTestId(groups: List<EntryGroup>): String? =
    (groups.firstOrNull { !it.done } ?: groups.firstOrNull())?.testId
