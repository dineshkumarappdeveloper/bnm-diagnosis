package com.bnm.lab.instruments

import com.bnm.lab.lab.LabOrder
import com.bnm.lab.lab.LabRepository
import com.bnm.lab.lab.LabTest
import com.bnm.lab.lab.Patient
import com.bnm.lab.lab.Referrer
import com.bnm.lab.license.LicenseState
import com.bnm.lab.license.ReadOnlyCopy
import com.bnm.lab.license.ReadOnlyReason
import com.bnm.lab.navigation.RouteGuard
import com.bnm.lab.navigation.Screen
import com.bnm.lab.staff.LabPermission
import com.bnm.lab.staff.Staff
import com.bnm.lab.staff.allows
import kotlinx.datetime.LocalDate

/**
 * "Create order from this result" — the claim queue's third way out, decided
 * here and executed by [InstrumentEngine.createOrderForResult].
 *
 * The lab owner's report: "I run the sample on the analyzer with the patient's
 * name and everything, but the order/billing was never created in the app."
 * The run then sits in the queue with nothing to assign it to, and the operator
 * has to leave the screen, register the patient, come back and assign. This is
 * that trip, collapsed into one dialog — but it is still a REGISTRATION, so it
 * answers to registration's rules ([CreateOrderGate]) and mints its accession
 * from the app's own per-seat series, never from the text somebody keyed on the
 * analyzer.
 *
 * Everything in this file is pure: the ranking, the validation and the gate are
 * functions of data already read, so they can be tested without a database and
 * cannot drift from the screen that draws them.
 */

// ── which test this run belongs to ───────────────────────────────────────────

/**
 * One catalog test the frame could be registered against, and how much of that
 * test this run would actually fill.
 *
 * [mapping] is [InstrumentEngine.mapParams]' own answer — the SAME mapping the
 * apply path will use — so "fills 13 of 29" is a promise the registration keeps
 * rather than a second opinion that can drift from it. Writing a second matcher
 * here is how the dialog would come to promise numbers the claim then refuses.
 */
data class TestFit(
    val test: LabTest,
    /** analyzerKey → catalog parameter_key. Its size is [fills]. */
    val mapping: Map<String, String>,
) {
    /** Frame parameters this test can take. */
    val fills: Int get() = mapping.size

    /** Parameters the test has in the catalog — the denominator the bench knows. */
    val of: Int get() = test.parameters.size

    /** Nothing of this run lands on this test. Never a legal choice — see [validateCreateOrder]. */
    val fitsNothing: Boolean get() = mapping.isEmpty()

    /**
     * "parameter" / "parameters" for [of] — decided ONCE, because both sentences
     * below count the same denominator. Pluralising only the fitting branch is
     * how the picker came to tell a lab that a result "fills none of the 1
     * parameters" of an ESR, and the 223-test master catalog is full of
     * single-analyte tests.
     */
    private val unit: String get() = if (of == 1) "parameter" else "parameters"

    /** "this result fills 13 of 29 parameters of Complete Blood Count". */
    val note: String
        get() = if (fitsNothing) "this result fills none of the $of $unit of ${test.name}"
        else "this result fills $fills of $of $unit of ${test.name}"
}

/**
 * Every test in the catalog, best fit first.
 *
 * Rank by how many of the frame's parameters the test takes, then — on a tie —
 * by the SMALLER test. A haemogram of 22 parameters and a 40-parameter panel
 * that both take the analyzer's 18 are not equally good guesses: the one with
 * fewer holes left in it is what the bench meant to run. Name last, purely so
 * the list is stable between two reads of the same catalog.
 *
 * Zero-fill tests are kept in the list rather than dropped: the operator may
 * search for one by name, and being told plainly that it takes none of these
 * parameters beats a test that silently is not there.
 */
fun rankTestsForFrame(
    analyzerKeys: Collection<String>,
    tests: List<LabTest>,
    overrides: Map<String, String> = emptyMap(),
): List<TestFit> =
    tests.map { TestFit(it, InstrumentEngine.mapParams(analyzerKeys, it, overrides)) }
        .sortedWith(
            compareByDescending<TestFit> { it.fills }
                .thenBy { it.of }
                .thenBy { it.test.name.lowercase() }
        )

/** The test to pre-select: the best fit that actually takes something. Null when none does. */
fun List<TestFit>.bestFit(): TestFit? = firstOrNull { !it.fitsNothing }

/** Name or catalog code, case-insensitive. A blank query filters nothing. */
fun List<TestFit>.filterForPick(query: String): List<TestFit> {
    val q = query.trim()
    if (q.isEmpty()) return this
    return filter { it.test.name.contains(q, true) || it.test.code.contains(q, true) }
}

/**
 * Everything the dialog opens with, read once: the run, the catalog ranked
 * against it, and the referrers the desk can pick from.
 *
 * Read once and held, not re-read per keystroke — ranking 223 tests against a
 * frame is one [InstrumentEngine.mapParams] per test, and the picker's search
 * box filters what is already here ([filterForPick]).
 */
data class CreateOrderContext(
    val frame: StoredInstrumentFrame,
    /** The analyzer's configured name — the `entered_by` the results will carry. */
    val instrumentName: String,
    /** The whole ACTIVE catalog, best fit first. */
    val fits: List<TestFit>,
    val referrers: List<Referrer>,
) {
    val frameParams: Int get() = frame.params.size

    /** True when nothing in the catalog takes a single one of these parameters. */
    val nothingFits: Boolean get() = fits.bestFit() == null

    /** What the analyzer itself said about the patient — the form opens on it. */
    val prefill: AnalyzerPrefill get() = analyzerPrefill(frame)

    /**
     * The line the form shows above the pre-filled fields; null when the
     * analyzer sent no demographics and there is nothing to warn about.
     *
     * Said out loud on purpose. These values are bench text typed at an
     * analyzer keypad, not the lab's record, and a form that silently arrives
     * filled in is a form nobody reads.
     */
    val prefillNote: String?
        get() = prefill.fieldsLabel?.let {
            "The $it below came from $instrumentName — what was keyed at the analyzer, " +
                "not the lab's record. Check it against the tube before registering."
        }
}

// ── what the analyzer already told us about the patient ──────────────────────

/**
 * The demographics the run itself carries, in the form's own shapes.
 *
 * The feature exists because "I run the sample on the analyzer with the
 * patient's name and everything" — so throwing that away and handing the
 * operator a blank form makes them read the name off the analyzer's screen and
 * retype it. [sameName] is exact (deliberately), so one retyped "Aasha" for
 * "Asha" walks straight past the duplicate guard and splits the patient in two.
 * Pre-filling is what keeps the two spellings one spelling.
 *
 * Everything here is a SUGGESTION: every field stays editable, and the form
 * says where the values came from ([CreateOrderContext.prefillNote]).
 */
data class AnalyzerPrefill(
    val name: String = "",
    /** 'M' | 'F' | 'O', already normalised by the driver; null when unknown. */
    val sex: String? = null,
    /** Whole years as digits, or "" — see [analyzerAgeYears]. */
    val ageText: String = "",
) {
    val isEmpty: Boolean get() = name.isBlank() && sex == null && ageText.isBlank()

    /** "name, age and sex" — the fields that arrived, in the order the form draws them. */
    val fieldsLabel: String?
        get() {
            val parts = buildList {
                if (name.isNotBlank()) add("name")
                if (sex != null) add("sex")
                if (ageText.isNotBlank()) add("age")
            }
            return when (parts.size) {
                0 -> null
                1 -> parts[0]
                else -> parts.dropLast(1).joinToString(", ") + " and " + parts.last()
            }
        }
}

fun analyzerPrefill(frame: StoredInstrumentFrame): AnalyzerPrefill = AnalyzerPrefill(
    name = frame.patientName?.trim().orEmpty(),
    sex = frame.patientSex?.trim()?.uppercase()?.takeIf { it in SEX_CODES },
    ageText = analyzerAgeYears(frame.meta),
)

private val SEX_CODES = setOf("M", "F", "O")

/**
 * The analyzer's age in WHOLE YEARS, or "" when it did not send one — or sent
 * one in a unit this form's box does not mean.
 *
 * The Mindray sends OBX `30525-0` as value plus OBX-6 unit, and the driver
 * stores the pair ("34 a", "6 mo"). Taking the leading integer regardless would
 * put 6 in an "Age (years)" box for a six-MONTH-old, and a paediatric haemogram
 * printed against adult reference ranges is a report that reads normal when it
 * is not. A unit that is not years is dropped and the operator asked.
 */
fun analyzerAgeYears(meta: Map<String, String>): String {
    val raw = meta["age"]?.trim().orEmpty()
    val digits = raw.takeWhile { it.isDigit() }
    if (digits.isEmpty()) return ""
    val unit = raw.drop(digits.length).trim().lowercase()
    return if (unit.isEmpty() || unit in AGE_YEAR_UNITS) digits else ""
}

/** HL7 table 0102 uses `a` for years; analyzers in the field also send these. */
private val AGE_YEAR_UNITS = setOf("a", "y", "yr", "yrs", "year", "years")

// ── who may do this, and on what licence ─────────────────────────────────────

/** Why "Create order" is refused — shown IN the dialog, never as a dead button. */
sealed interface CreateOrderRefusal {
    val title: String
    val detail: String

    /**
     * The seat may not start new work. Same words the refused New order route
     * uses, because it is the same refusal: a lapsed lab is read-only.
     */
    data class Licence(val reason: ReadOnlyReason) : CreateOrderRefusal {
        override val title: String get() = ReadOnlyCopy.title(reason)
        override val detail: String
            get() = ReadOnlyCopy.detail(reason) +
                " This result stays in the queue meanwhile — assign it to an order that already " +
                "exists, or print the worksheet for the bench."
    }

    /** The person may not. Carries the permission's own copy — never a bare "forbidden". */
    data class Permission(val permission: LabPermission) : CreateOrderRefusal {
        override val title: String get() = permission.title
        override val detail: String get() = permission.explanation
    }
}

/**
 * The door onto "Create order from this result".
 *
 * Registering from the bench is TWO acts at once — touching a queued clinical
 * run, and starting new work — so it answers to both rules, not the softer one.
 * Assign / Print / Discard keep their own gate ([LabPermission.RESULTS] alone):
 * they move data the lab already holds, and a lapsed licence must never stop a
 * lab printing what it has.
 */
object CreateOrderGate {

    /**
     * What the New order route itself asks of a person, read from the one place
     * that decides it rather than copied.
     *
     * Today that is nothing — registration is open to every role, so this reads
     * as a tautology beside [LabPermission.RESULTS]. It is asked anyway: the day
     * registration gains a permission, this door gains it in the same commit
     * instead of quietly becoming the way round it.
     */
    fun registrationRequirement(): LabPermission? = RouteGuard.requirement(Screen.NewOrder.route)

    /**
     * Null when this seat may create the order.
     *
     * The licence is answered FIRST on purpose: it is true of the whole computer
     * and everyone at it, and telling a technician "you may not" when nobody at
     * that lab may would send them to find the owner for nothing.
     */
    fun refusal(who: Staff?, licence: LicenseState): CreateOrderRefusal? {
        licence.readOnlyReason?.let { return CreateOrderRefusal.Licence(it) }
        if (!who.allows(LabPermission.RESULTS)) return CreateOrderRefusal.Permission(LabPermission.RESULTS)
        registrationRequirement()?.let { needed ->
            if (!who.allows(needed)) return CreateOrderRefusal.Permission(needed)
        }
        return null
    }

    fun allows(who: Staff?, licence: LicenseState): Boolean = refusal(who, licence) == null
}

// ── the form ─────────────────────────────────────────────────────────────────

const val PRIORITY_ROUTINE = "routine"
const val PRIORITY_URGENT = "urgent"

/**
 * What the operator typed, before anything is written.
 *
 * The fields are exactly the ones the registration desk's own new-patient form
 * collects ([com.bnm.lab.screens.lab.NewOrderScreen]) — name, sex, age or DOB,
 * phone — plus the order's referrer and priority. No more: a second, richer
 * registration form is a second registration path, and this one has to stay the
 * short way round, not a rival to the desk.
 *
 * [reusePatientId] set means the duplicate guard was answered with "use the
 * existing patient": the demographics below are then the typed ones that FOUND
 * them and are not written over the record the lab already has.
 */
data class CreateOrderDraft(
    val patientName: String = "",
    /** 'M' | 'F' | 'O'. Required — reference ranges depend on it. */
    val sex: String? = null,
    val ageText: String = "",
    /** ISO `YYYY-MM-DD`; beats [ageText] when both are given, as at the desk. */
    val dob: String = "",
    val phone: String = "",
    val referrerId: String? = null,
    val priority: String = PRIORITY_ROUTINE,
    val testId: String? = null,
    val reusePatientId: String? = null,
) {
    val name: String get() = patientName.trim()
    val dobClean: String? get() = dob.trim().ifBlank { null }
    val ageYears: Long? get() = ageText.trim().toLongOrNull()
    val phoneClean: String? get() = phone.trim().ifBlank { null }
    val reusingPatient: Boolean get() = !reusePatientId.isNullOrBlank()
}

/**
 * The first thing wrong with [draft], in the words the operator needs; null when
 * it is ready to write.
 *
 * The patient rules are the desk's rules, for the same reasons: a result whose
 * patient has no sex or age prints without a reference range, which is a report
 * nobody can act on.
 *
 * The test rule is this screen's own. A test the frame cannot fill AT ALL is
 * refused here rather than at the claim, because by then the order exists: the
 * lab would be left with a registered, billable order carrying no results and
 * the run still sitting in the queue — strictly worse than being told no.
 */
fun validateCreateOrder(draft: CreateOrderDraft, fits: List<TestFit>, frameParams: Int): String? {
    if (!draft.reusingPatient) {
        if (draft.name.isBlank()) return "Name is required"
        if (draft.sex.isNullOrBlank()) return "Sex is required — reference ranges depend on it"
        if (draft.dobClean == null && draft.ageYears == null) return "Enter the age in years, or a DOB"
        draft.dobClean?.let {
            if (runCatching { LocalDate.parse(it.take(10)) }.isFailure) return "DOB must be YYYY-MM-DD"
        }
    }
    val chosen = draft.testId?.let { id -> fits.firstOrNull { it.test.id == id } }
        ?: return "Pick the test this run belongs to"
    if (chosen.fitsNothing) {
        return "${chosen.test.name} takes none of the $frameParams parameters this analyzer sent. " +
            "Pick a test the result can fill, or close this and assign the run to an order that exists."
    }
    return null
}

/**
 * The sample reference carried onto the order: the specimen id AS KEYED on the
 * analyzer, in the order's note — the one place the registration path has for it.
 *
 * 🔴 It is never an accession. The analyzer's specimen field is free text typed
 * at the bench, unverified and often another lab's format or another seat's
 * series; adopting it would mint an accession outside
 * [LabRepository.ownAccessionSeries], collide with a number this seat will issue
 * later, and break the one promise a barcode has to keep. The app's own series
 * issues the accession, and this line is how the bench still finds the tube.
 */
fun sampleReferenceNote(specimenId: String?, instrumentName: String): String {
    val specimen = specimenId?.trim()?.takeIf { it.isNotEmpty() }
    return "Registered from an analyzer run · specimen " +
        (specimen ?: "not keyed") + " · " + instrumentName
}

// ── the duplicate-patient guard ──────────────────────────────────────────────

/**
 * Same person, two spellings? Case and runs of whitespace are ignored; nothing
 * else is.
 *
 * Deliberately strict. This decides whether the operator is OFFERED an existing
 * record, and a fuzzy match that quietly reuses "R Kumar" for "Ravi Kumar"
 * attaches a haemogram to somebody else's file — the exact failure the guard
 * exists to prevent. Near-misses are left to be two patients; a lab that wants
 * them merged does it deliberately, elsewhere.
 */
fun sameName(a: String, b: String): Boolean =
    a.trim().split(WHITESPACE).joinToString(" ").equals(
        b.trim().split(WHITESPACE).joinToString(" "), ignoreCase = true,
    )

private val WHITESPACE = Regex("\\s+")

/**
 * An existing patient the operator is about to duplicate, with what they have
 * been to the lab before.
 *
 * [recentOrders] is the evidence, not decoration: "Asha Menon, 34/F, 3 orders,
 * the last one this morning" is how a person recognises their own patient. A
 * bare name would have them guessing, and guessing is how a patient list fills
 * with near-duplicates and a result eventually reaches the wrong person.
 */
data class PatientMatch(
    val patient: Patient,
    val recentOrders: List<LabOrder>,
    val totalOrders: Int,
) {
    val summary: String
        get() = when (totalOrders) {
            0 -> "no orders yet"
            1 -> "1 order"
            else -> "$totalOrders orders"
        }
}

/**
 * The existing patients that a new registration for [name] / [phone] would
 * duplicate: same normalized phone AND the same name.
 *
 * Both, never either. A phone alone is a household's — BNM's own People roster
 * exists because families share one number, so "same phone" would offer a
 * mother's record for her son. A name alone is two strangers called Ravi Kumar.
 * Together they are one person often enough to be worth asking about, and rare
 * enough not to nag.
 *
 * Pure: the caller does the reading ([LabRepository.patientsByPhone] already
 * compares on digits, past the +91 and the spaces).
 */
fun List<Patient>.duplicatesOf(name: String): List<Patient> =
    filter { it.deletedAt == null && sameName(it.name, name) }

// ── what came out ────────────────────────────────────────────────────────────

/**
 * The order that now exists, and whether the run reached it.
 *
 * Two fields rather than a thrown error because the two halves are written
 * separately and the SECOND one can fail on its own (an order registered a
 * heartbeat before its status walked past entry, say). The order is real either
 * way, and a dialog that reported only "failed" would send the operator off to
 * register a second one.
 */
data class CreatedFromResult(
    val order: LabOrder,
    val patient: Patient,
    /** The duplicate guard was answered with "use existing" — no new record was written. */
    val reusedPatient: Boolean,
    val testName: String,
    /** [InstrumentEngine.claimUnmatched]'s own summary; null when the claim failed. */
    val claimSummary: String? = null,
    val claimError: String? = null,
) {
    val resultsLanded: Boolean get() = claimSummary != null

    /**
     * Billing, told honestly, in one sentence.
     *
     * Checked against the release path rather than assumed: `OrderDetailScreen`
     * blocks Print and WhatsApp on `amountDue > 0` — which reads a LINKED
     * invoice — so an order with no bill at all releases freely, and it is the
     * unpaid bill, not the missing one, that holds a report. Saying "the report
     * is blocked until you bill" would be a threat the app does not carry out;
     * saying nothing would let a lab hand over work it never charged for.
     */
    val billingNote: String
        get() = "No bill was raised — nothing has been charged for this order yet, and an " +
            "unpaid bill later holds the report at Print until it is settled."
}
