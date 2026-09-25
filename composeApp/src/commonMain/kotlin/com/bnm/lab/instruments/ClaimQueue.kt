package com.bnm.lab.instruments

import com.bnm.lab.lab.LabRepository

/**
 * The claim queue's domain models and its pure decisions — what a queued frame
 * looks like on the row, which orders it could belong to, and how a typed or
 * scanned string picks one.
 *
 * Everything here is a pure function of data already read: the engine does the
 * database work ([InstrumentEngine.claimCandidates]) and the screen does the
 * drawing, so the ranking and the search can be tested without either.
 */

/**
 * One row of "Waiting for an order": the stored frame, decoded, plus the two or
 * three numbers that let an operator tell two samples apart at a glance.
 *
 * The bench runs samples back to back and the analyzer keys nothing but a
 * number (often not even that). "Specimen —, 10:42" for four rows in a row is
 * not an identification; the haemoglobin usually is.
 */
data class QueuedFrame(
    val id: String,
    val instrumentId: String?,
    val specimenId: String?,
    val receivedAt: String,
    val frame: StoredInstrumentFrame,
) {
    val paramCount: Int get() = frame.params.size

    /**
     * The headline values, in the order a haematology bench reads them. Keys
     * are matched case-insensitively because the analyzers disagree on spelling
     * ("HGB" on both drivers today, "HB" on the ASTM ones to come), and the
     * unit is the ANALYZER's own — nothing here has been converted to a
     * catalog unit, because no catalog test is attached yet.
     */
    val headline: List<Pair<String, String>>
        get() = HEADLINE_KEYS.mapNotNull { wanted ->
            val key = frame.params.keys.firstOrNull { it.equals(wanted, ignoreCase = true) }
                ?: return@mapNotNull null
            val value = frame.params[key]?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            key to (value + (frame.units[key]?.trim()?.takeIf { it.isNotEmpty() }?.let { " $it" } ?: ""))
        }.distinctBy { it.first.uppercase() }.take(3)

    private companion object {
        val HEADLINE_KEYS = listOf("WBC", "HGB", "HB", "PLT")
    }
}

/**
 * One order the operator may assign a queued frame to.
 *
 * [matched] of [total] is why this row is where it is in the list: it counts
 * the frame's parameters that the order's tests can actually take, worked out
 * with the SAME mapping the apply path will use ([InstrumentEngine.selectMapping]),
 * so the suggestion cannot promise something the assignment then refuses.
 */
data class ClaimCandidate(
    val orderId: String,
    val accessionNo: String,
    val status: String,
    /** ISO instant the order was registered. */
    val registeredAt: String,
    val patientName: String,
    /** "42 y / F" — the same shape the report's patient block prints. */
    val ageSex: String,
    val phone: String?,
    /** Ordered tests, as snapshotted on the order ("Complete Blood Count · ESR"). */
    val tests: String,
    val matched: Int,
    val total: Int,
    /** False for an order past ENTERED: listed in the held-back count, never offered. */
    val canTakeResults: Boolean,
) {
    /** "matches 18 of 22 parameters" / "matches none of the 22 parameters". */
    val matchNote: String
        get() = if (matched == 0) "matches none of the $total parameters"
        else "matches $matched of $total parameters"
}

/** What the picker opens with: the frame it is placing, and where it can go. */
data class ClaimCandidates(
    val frame: StoredInstrumentFrame,
    /** Assignable orders, best guess first. */
    val open: List<ClaimCandidate>,
    /**
     * Orders the same read reached that are PAST result entry — named, not
     * hidden, and never silently dropped. Only ones that could plausibly have
     * been the target are here (see [InstrumentEngine.claimCandidates]): a
     * steady lab finishes everything it registers, and counting every signed-off
     * order in the window would print "70 more orders" under every dialog.
     */
    val locked: List<ClaimCandidate>,
    /** What the list answers — blank for the ranked worklist. */
    val query: String = "",
    /** The read filled its row cap: there may be older orders it never saw. */
    val windowFull: Boolean = false,
) {
    val lockedCount: Int get() = locked.size

    /**
     * The sentence under the list. A silently shortened list is how an operator
     * ends up believing the order "isn't in the app" and registering it twice.
     *
     * The wording is built from the statuses actually held back, never the bare
     * word "approved": an order at `verified` has the technician's signature and
     * is still waiting for the pathologist, and telling the bench it is approved
     * tells them the report is out. That is a claim a bench acts on.
     */
    val lockedNote: String?
        get() {
            if (locked.isEmpty()) return null
            val statuses = locked.map { it.status.replace('_', ' ') }.distinct().sorted().joinToString(", ")
            val scope = query.trim().takeIf { it.isNotEmpty() }?.let { " matching “$it”" }.orEmpty()
            return if (locked.size == 1) "1 more order$scope is past result entry ($statuses) and can no longer take results."
            else "${locked.size} more orders$scope are past result entry ($statuses) and can no longer take results."
        }

    /**
     * Said out loud whenever the read hit its cap, for the same reason as
     * [lockedNote]: an operator who reads "nothing matches" as "not in the app"
     * registers the patient a second time and bills them twice.
     */
    val windowNote: String?
        get() = when {
            !windowFull -> null
            query.isBlank() -> "Showing the most recent orders only — type a name, phone or accession to reach an older one."
            else -> "More orders match than fit here — type more of the name or the accession."
        }
}

/**
 * Rank: orders whose tests take these parameters first, then the newest.
 *
 * Both halves matter. Overlap alone would put last month's CBC above this
 * morning's when both take a haemogram; recency alone would put a freshly
 * registered urine routine above the CBC the analyzer is obviously reporting.
 */
fun List<ClaimCandidate>.rankedForClaim(): List<ClaimCandidate> =
    sortedWith(compareByDescending<ClaimCandidate> { it.matched > 0 }
        .thenByDescending { it.matched }
        .thenByDescending { it.registeredAt })

/**
 * Filter by what the operator typed: accession, patient name, or phone.
 *
 * The phone is compared on digits only ([LabRepository.normalizePhone] is for
 * identity, this is for searching) so "98765" finds "+91 98765 43210"; the
 * other two are plain case-insensitive substrings. A blank query filters
 * nothing — the ranked list IS the answer until someone types.
 *
 * The authoritative search is the SQL one ([InstrumentEngine.claimCandidates]),
 * which reaches past the loaded window; this is the same rule applied to the
 * rows already on screen so the list narrows on the keystroke instead of on the
 * round trip. Keep the two in step — SQL matches the same three fields and
 * likewise ignores a phone query shorter than three digits.
 */
fun List<ClaimCandidate>.filterForClaim(query: String): List<ClaimCandidate> {
    val q = query.trim()
    if (q.isEmpty()) return this
    val digits = q.filter { it.isDigit() }
    return filter { c ->
        c.accessionNo.contains(q, ignoreCase = true) ||
            c.patientName.contains(q, ignoreCase = true) ||
            (digits.length >= 3 && c.phone?.filter { ch -> ch.isDigit() }?.contains(digits) == true)
    }
}

/**
 * What pressing Enter assigns to — the bench barcode scanner's whole world,
 * and NOTHING else.
 *
 * A scanner types the accession and sends Enter, so an exact accession match
 * assigns in one motion, exactly as it did when this dialog was a bare text
 * field. Anything the scanner would not have produced goes to the engine as raw
 * text, and the engine's lookup is exact-accession too — so the worst a typed
 * name can do is come back "No order with accession Ravi".
 *
 * A query that merely NARROWS the list to one row is deliberately not a target.
 * The box now searches patient names and phone digits as well, and "7788" — the
 * only handle on a row that reads "Specimen BNMTEST-7788" — is a substring of
 * somebody's phone number in any real worklist. Enter is a reflex in a search
 * box; a claim is not reversible (the row is marked applied and [claimUnmatched]
 * refuses a second one), so a narrowed list stays a list and the operator taps
 * the row they meant. [narrowsToOneNonAccession] is how the screen says so.
 *
 * Null only for a blank query — there is nothing to act on.
 */
fun scanTargetFor(query: String, open: List<ClaimCandidate>): String? {
    val q = query.trim()
    if (q.isEmpty()) return null
    return open.firstOrNull { it.accessionNo.equals(q, ignoreCase = true) }?.accessionNo ?: q
}

/**
 * True when the typed text has left exactly one order standing but is not that
 * order's accession — the case Enter used to assign and now does not.
 *
 * The screen turns this into a line of help ("tap the row"), because a key that
 * silently stops working is its own bug report.
 */
fun narrowsToOneNonAccession(query: String, open: List<ClaimCandidate>): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return false
    if (open.any { it.accessionNo.equals(q, ignoreCase = true) }) return false
    return open.filterForClaim(q).size == 1
}

/** "42 y / F", "7 mo / M", "- / O" — the report's patient-block shape. */
internal fun ageSexLabel(dob: String?, ageYears: Long?, sex: String?): String {
    val age = LabRepository.resolveAgeYears(dob, ageYears)
    val label = when {
        age == null -> "-"
        age < 1.0 -> "${(age * 12).toInt().coerceAtLeast(0)} mo"
        else -> "${age.toInt()} y"
    }
    return "$label / ${sex?.uppercase()?.takeIf { it.isNotBlank() } ?: "O"}"
}
