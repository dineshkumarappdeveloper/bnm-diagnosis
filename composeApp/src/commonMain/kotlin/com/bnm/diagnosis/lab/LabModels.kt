package com.bnm.diagnosis.lab

import kotlinx.serialization.Serializable

/**
 * Lab domain models. The SQLDelight tables are the system of record; these are
 * the typed views the app works with. `TestParameter`/`RefRange` also define
 * the `lab_tests.parameters_json` wire format (camelCase keys — a purely local
 * format, never sent to an edge fn), (de)serialized via the app's shared Json.
 */

/** One reference range. All bounds optional: a range with only `text` is a
 *  qualitative expectation ("Non-reactive"); numeric ranges may be one-sided
 *  (`high` only = "< high"). `sex` null = any; age bounds in YEARS (fractional
 *  allowed for paediatric bands). A patient with unknown age matches only
 *  ranges without age bounds. */
@Serializable
data class RefRange(
    val sex: String? = null,          // 'M' | 'F' | null = any
    val ageMinY: Double? = null,      // inclusive
    val ageMaxY: Double? = null,      // inclusive
    val low: Double? = null,
    val high: Double? = null,
    val criticalLow: Double? = null,
    val criticalHigh: Double? = null,
    val text: String? = null,         // qualitative expected value
)

/** One measurable parameter of a test (a test = 1..n parameters). */
@Serializable
data class TestParameter(
    val key: String,                  // stable id within the test, e.g. "hb"
    val name: String,                 // printed name, e.g. "Haemoglobin"
    val unit: String? = null,
    val decimals: Int = 1,            // result formatting hint
    val ranges: List<RefRange> = emptyList(),
)

@Serializable
data class LabTest(
    val id: String,
    val code: String,                 // unique order code, e.g. "CBC"
    val name: String,
    val category: String? = null,
    val price: Double = 0.0,
    val sampleType: String = "blood", // blood|serum|urine|stool|swab|other
    val method: String? = null,
    val active: Boolean = true,
    val sortOrder: Int = 0,
    val parameters: List<TestParameter> = emptyList(),
)

@Serializable
data class LabPanel(
    val id: String,
    val code: String,
    val name: String,
    val price: Double = 0.0,
    val testIds: List<String> = emptyList(),
    val active: Boolean = true,
)

@Serializable
data class Patient(
    val id: String,
    val name: String,
    val sex: String = "O",            // 'M' | 'F' | 'O'
    val dob: String? = null,          // ISO date, preferred age source
    val ageYears: Long? = null,       // fallback when dob unknown
    val phone: String? = null,
    val address: String? = null,
    val createdAt: String = "",
    val updatedAt: String = "",
    val deletedAt: String? = null,
)

@Serializable
data class Referrer(
    val id: String,
    val name: String,
    val kind: String = "doctor",      // doctor | clinic | walk_in
    val phone: String? = null,
    val commissionPct: Double = 0.0,
    val createdAt: String = "",
    val deletedAt: String? = null,
    /** Stamped on every write so edits reach the lab's other seats. */
    val updatedAt: String? = null,
)

/** P4 · one line of a referrer's commission statement. Both figures are summed
 *  from the ORDER LINE SNAPSHOTS in the range (historical truth — already at
 *  the price AND percentage that applied when the order was registered). */
data class ReferrerCommissionRow(
    val referrerId: String,
    val referrerName: String,
    val kind: String = "doctor",
    val phone: String? = null,
    val ordersCount: Long = 0,
    val gross: Double = 0.0,
    /** The referrer's CURRENT headline rate — display context only. */
    val commissionPct: Double = 0.0,
    /**
     * Commission actually owed, summed per line from the percentage frozen on
     * each order line at registration. This is the payable figure; `gross *
     * commissionPct` is NOT, because lines may carry per-test overrides and
     * because the headline rate may have changed since the order was taken.
     */
    val payableSnapshot: Double = 0.0,
) {
    val payable: Double get() = payableSnapshot
    /** Blended rate actually earned over the period, for the statement header. */
    val effectivePct: Double get() = if (gross > 0) payableSnapshot / gross * 100.0 else 0.0
}

/** P4 · commission-report drill-down row: one of a referrer's orders in range. */
data class ReferrerOrderRow(
    val orderId: String,
    val accessionNo: String,
    val patientName: String,
    val createdAt: String,
    val status: String,
    val amount: Double,
    val reportedAt: String? = null,
)

// ── Commission (round-1 items 7 + 8) ────────────────────────────────────────

/**
 * The three commission levels for ONE referrer, read together so the editor can
 * show not just what a test pays but WHERE that number came from.
 *
 * [overrides] holds only the per-(referrer, test) rows that actually exist. A
 * missing key means INHERIT — never a stored copy of the inherited value, which
 * is what lets the lab raise [labBasePct] and have it flow through everywhere.
 */
data class CommissionRateSheet(
    val referrerId: String,
    val labBasePct: Double = 0.0,
    /** null = this referrer has no rate of their own and rides the lab base. */
    val referrerPct: Double? = null,
    val overrides: Map<String, Double> = emptyMap(),
) {
    /** What a test with NO per-test row pays — the editor's "inherited" hint. */
    val inheritedPct: Double get() = LabRepository.resolveCommissionPct(labBasePct, referrerPct, null)

    /** Where [inheritedPct] comes from, said in words for the editor caption. */
    val inheritedFrom: String
        get() = if (referrerPct != null) "this referrer's rate" else "the lab base"
}

/**
 * One line of a referrer's PER-TEST commission breakdown (feedback item 7 —
 * "well detail"). [effectivePct] is BLENDED from the frozen per-line
 * percentages, so a test whose rate was renegotiated mid-period honestly reads
 * as something between the old and the new rate rather than as today's.
 */
data class CommissionTestRow(
    val testId: String,
    val testName: String,
    val timesOrdered: Long = 0,
    val gross: Double = 0.0,
    val payable: Double = 0.0,
) {
    val effectivePct: Double get() = if (gross > 0) payable / gross * 100.0 else 0.0
}

/**
 * One SETTLEMENT against a referrer's commission. Earned commission is derived
 * from orders; commission PAID is a fact, and only recording it lets the lab
 * answer "what do I still owe Dr Rao?". Several payouts may cover the same
 * period — a lab that pays half now and half later gets two rows, and the
 * period's paid total is their sum.
 */
data class ReferrerPayout(
    val id: String,
    val referrerId: String,
    val periodFrom: String,           // inclusive ISO date
    val periodTo: String,             // inclusive ISO date
    val gross: Double = 0.0,          // period totals, frozen at settlement time
    val payable: Double = 0.0,
    val paidAmount: Double = 0.0,
    val paidAt: String? = null,
    val method: String? = null,       // cash | upi | bank | adjustment
    val notes: String? = null,
    val createdAt: String = "",
    val updatedAt: String? = null,
)

/** Lab-wide commission rollup over a range — the home dashboard's tile. */
data class CommissionRollup(
    val gross: Double = 0.0,
    val payable: Double = 0.0,
    val ordersCount: Long = 0,
    /** Settled inside the range (payouts whose whole period falls in it). */
    val paid: Double = 0.0,
) {
    /** Negative = the lab has paid ahead; shown as an advance, not clamped. */
    val outstanding: Double get() = payable - paid
    val effectivePct: Double get() = if (gross > 0) payable / gross * 100.0 else 0.0
}

/**
 * Everything one referrer's statement shows: the period totals, the per-test
 * breakdown, the orders behind them, and what has already been settled.
 *
 * [gross]/[payable] are summed from the frozen order lines — NEVER gross × the
 * doctor's current rate, which is the bug this whole round exists to kill.
 */
data class ReferrerStatement(
    val referrerId: String,
    val referrerName: String,
    val fromDate: String,
    val toDate: String,
    val gross: Double = 0.0,
    val payable: Double = 0.0,
    val paid: Double = 0.0,
    val tests: List<CommissionTestRow> = emptyList(),
    val orders: List<ReferrerOrderRow> = emptyList(),
    val payouts: List<ReferrerPayout> = emptyList(),
    /** The referrer's CURRENT headline rate — context, not arithmetic. */
    val headlinePct: Double? = null,
    val labBasePct: Double = 0.0,
) {
    val ordersCount: Int get() = orders.size
    val outstanding: Double get() = payable - paid
    /** Blended rate actually earned over the period. */
    val effectivePct: Double get() = if (gross > 0) payable / gross * 100.0 else 0.0
}

@Serializable
data class LabOrder(
    val id: String,
    val accessionNo: String,
    val patientId: String,
    val referrerId: String? = null,
    val invoiceId: String? = null,    // the GST bill this order is billed on
    val status: String = LabStatus.REGISTERED,
    val priority: String = "routine", // routine | urgent | stat
    val notes: String? = null,
    val createdAt: String = "",
    val updatedAt: String = "",
    val collectedAt: String? = null,
    val approvedAt: String? = null,
    val reportedAt: String? = null,
)

@Serializable
data class LabOrderTest(
    val id: String,
    val orderId: String,
    val testId: String,
    val testName: String,             // snapshot at order time
    val price: Double = 0.0,          // snapshot at order time
    val status: String = "pending",   // pending | in_progress | entered
    val commissionPct: Double = 0.0,  // snapshot at order time — never recomputed
)

@Serializable
data class LabResult(
    val id: String,
    val orderId: String,
    val testId: String,
    val parameterKey: String,
    val value: String? = null,        // null/blank = not entered yet
    val unit: String? = null,
    val flag: String? = null,         // N | L | H | CL | CH | A
    val refDisplay: String? = null,   // range as printed, frozen at entry
    val notes: String? = null,
    val enteredBy: String? = null,
    val enteredAt: String? = null,
    val verifiedBy: String? = null,
    val verifiedAt: String? = null,
    val approvedBy: String? = null,
    val approvedAt: String? = null,
) {
    val isEntered: Boolean get() = !value.isNullOrBlank()
}

/**
 * One clinic order in the local EMR inbox (P3 bridge; `emr_inbox` table).
 *
 * P3b: the clinic now shares WHO the order is for ([patientName]/[patientPhone]/
 * [patientSex]/[patientDob], [visitNumber]) and WHICH catalog entry the doctor
 * picked ([testCode]) — so the desk identifies the patient and resolves the test
 * without retyping or fuzzy guessing. Every one of those is nullable: a LEGACY
 * row (or a server that predates the additive change) has them all null and the
 * desk behaves exactly as it did before — hand-typed patient, name matching.
 */
data class EmrInboxItem(
    val id: String,                   // clinical_lab_orders.id
    val visitId: String?,
    val testName: String,
    val instructions: String?,
    val status: String?,
    val labStatus: String?,
    val accessionNo: String?,
    val matchedOrderId: String?,      // local lab_orders.id once registered
    val done: Boolean = false,
    val createdAt: String? = null,
    // ── identity block (null on legacy rows) ──
    val testCode: String? = null,     // the lab's own catalog code, exact
    val visitNumber: String? = null,  // human visit no. the patient quotes
    val patientName: String? = null,
    val patientPhone: String? = null,
    val patientSex: String? = null,   // 'M' | 'F' | 'O'
    val patientDob: String? = null,   // ISO date
) {
    /** True when the clinic sent enough to skip retyping the patient. */
    val hasIdentity: Boolean
        get() = !patientName.isNullOrBlank() || !patientPhone.isNullOrBlank()

    /** Everything a desk search should look at, lowercased once. */
    fun searchBlob(): String = listOfNotNull(
        patientName, patientPhone, patientPhone?.filter { it.isDigit() },
        visitNumber, testName, testCode, accessionNo,
    ).joinToString(" ").lowercase()
}

/** How an EMR row's test was resolved onto the local catalog — the desk tells
 *  the tech which of these happened rather than silently pre-ticking a box. */
enum class EmrTestMatchKind {
    /** Exact `test_code` hit — the doctor picked this catalog entry. */
    CODE,
    /** Legacy free-text row: name (or code) matched exactly. */
    NAME,
    /** Legacy free-text row: substring match — likely right, worth a glance. */
    FUZZY,
    /** Nothing matched — the test rides on the order note. */
    NONE,
}

/** [EmrTestMatchKind] + the test it resolved to (null for [EmrTestMatchKind.NONE]). */
data class EmrTestMatch(val test: LabTest?, val kind: EmrTestMatchKind) {
    /** One line for the desk: "matched Complete Blood Count (CBC)". */
    fun label(): String = when (kind) {
        EmrTestMatchKind.CODE -> "matched ${test?.name} (${test?.code}) by code"
        EmrTestMatchKind.NAME -> "matched ${test?.name} (${test?.code}) by name"
        EmrTestMatchKind.FUZZY -> "closest match ${test?.name} (${test?.code}) — check it"
        EmrTestMatchKind.NONE -> "no catalog match — added as a note"
    }
}

/** One critical result (CL/CH) with the identity a lab needs to PHONE it out.
 *  Drives the home dashboard's "Critical results today" card. */
data class CriticalResult(
    val orderId: String,
    val testId: String,
    val parameterKey: String,
    val value: String?,
    val unit: String?,
    val flag: String?,            // CL | CH
    val accessionNo: String,
    val patientName: String,
    val patientPhone: String?,
)

/** One worklist row: the order + joined patient identity + test count. */
data class WorklistEntry(
    val order: LabOrder,
    val patientName: String,
    val patientSex: String,
    val patientDob: String?,
    val patientAgeYears: Long?,
    val testCount: Long,
    /** Tests whose every parameter already has a value — the "3 of 7" numerator. */
    val doneCount: Long = 0,
)

/** The linear order pipeline. Guards live in [LabRepository.setOrderStatus]. */
object LabStatus {
    const val REGISTERED = "registered"
    const val COLLECTED = "collected"
    const val IN_PROGRESS = "in_progress"
    const val ENTERED = "entered"
    const val VERIFIED = "verified"
    const val APPROVED = "approved"
    const val REPORTED = "reported"
    const val DELIVERED = "delivered"
    const val CANCELLED = "cancelled"

    /** Pipeline order; cancelled sits outside the line. */
    val FLOW = listOf(REGISTERED, COLLECTED, IN_PROGRESS, ENTERED, VERIFIED, APPROVED, REPORTED, DELIVERED)
}
