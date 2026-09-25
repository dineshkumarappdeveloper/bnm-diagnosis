package com.bnm.lab

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.bnm.lab.api.ApiClient
import com.bnm.lab.db.AppDatabase
import com.bnm.lab.instruments.InstrumentConfig
import com.bnm.lab.instruments.InstrumentEngine
import com.bnm.lab.instruments.InstrumentTransport
import com.bnm.lab.instruments.Mllp
import com.bnm.lab.instruments.SampleFrames
import com.bnm.lab.instruments.filterForClaim
import com.bnm.lab.instruments.narrowsToOneNonAccession
import com.bnm.lab.instruments.scanTargetFor
import com.bnm.lab.lab.LabOrder
import com.bnm.lab.lab.LabRepository
import com.bnm.lab.lab.LabStatus
import com.bnm.lab.lab.Patient
import com.bnm.lab.lab.SeedCatalog
import com.bnm.lab.license.LicenseState
import com.bnm.lab.navigation.LicenceGate
import com.bnm.lab.navigation.Screen
import com.bnm.lab.staff.LabPermission
import com.bnm.lab.staff.Staff
import com.bnm.lab.staff.StaffRole
import com.bnm.lab.staff.allows
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The claim queue's picker: which orders a stranded analyzer result is offered,
 * in what order, and what assigning one actually writes.
 *
 * The bug this answers is the lab owner's: a sample runs on the analyzer with
 * the patient's name keyed in, but nobody registered the order, so the result
 * lands here — and the only way out used to be typing an accession number from
 * memory. The picker has to put the RIGHT order first without ever offering one
 * that would refuse the results, and assigning has to remain an ordinary bench
 * entry down to the audit trail.
 */
class ClaimQueueTest {

    /** Numeric parameters in [SampleFrames.mindrayOru] — the CBC + 5-part diff. */
    private val PARAMS_IN_FRAME = 18

    private class Bench {
        val db: AppDatabase = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            .let { AppDatabase.Schema.create(it); AppDatabase(it) }
        val repo = LabRepository(db, ApiClient.json)
        val engine = InstrumentEngine(db, repo, ApiClient.json)
        val cfg = InstrumentConfig(id = "bench-1", name = "BC-5130 bench 1",
            driver = "mindray_hl7", transport = InstrumentTransport.TCP)

        suspend fun patient(id: String, name: String, sex: String, age: Long, phone: String?) =
            repo.upsertPatient(Patient(id = id, name = name, sex = sex, ageYears = age, phone = phone))

        suspend fun order(patientId: String, vararg testCodes: String): LabOrder =
            repo.createLabOrder(patientId, testIds = testCodes.map { "seed-${it.lowercase()}" }).getOrThrow()

        /**
         * Park an order past the entry window. Straight to the column on
         * purpose: approval needs a pathologist and signed results, and all the
         * picker reads is the status.
         */
        fun lock(order: LabOrder, status: String = LabStatus.APPROVED) =
            db.labOrdersQueries.setStatus(status, nowIso(), order.id)

        /**
         * An unmatched result, put in the queue by the real listener path.
         *
         * The analyzer is saved first because the claim path looks its config
         * back up by id — that lookup is where the instrument's NAME (the
         * `entered_by` attribution) and its explicit param map come from.
         * No socket is opened: the config carries no TCP port.
         */
        suspend fun queueOne(): String {
            engine.saveInstrument(cfg)
            engine.ingestBytes(cfg, Mllp.wrap(SampleFrames.mindrayOru()))   // specimen BNMTEST-1: no order has it
            return db.instrumentsQueries.listUnmatched().executeAsList().single().id
        }

        fun queueRow(id: String) = db.instrumentsQueries.unmatchedById(id).executeAsOneOrNull()

        private fun nowIso() = kotlin.time.Clock.System.now().toString()
    }

    /** A bench with three patients: a CBC, an ESR, and an approved CBC. */
    private suspend fun stocked(): Triple<Bench, LabOrder, LabOrder> {
        val b = Bench()
        SeedCatalog.seedIfEmpty(b.repo)
        b.patient("p-asha", "Asha Menon", "F", 34, "+91 98765 43210")
        b.patient("p-ravi", "Ravi Kumar", "M", 51, "9000011111")
        b.patient("p-old", "Latha Rao", "F", 60, "9000022222")
        val cbc = b.order("p-asha", "CBC")
        val esr = b.order("p-ravi", "ESR")
        b.lock(b.order("p-old", "CBC"))
        return Triple(b, cbc, esr)
    }

    // ── which orders, in what order ──────────────────────────────────────────

    @Test
    fun `the order whose tests take these parameters comes first, and a locked one is counted not hidden`() = runBlocking {
        val (b, cbc, esr) = stocked()
        val resultId = b.queueOne()

        val c = b.engine.claimCandidates(resultId).getOrThrow()

        assertEquals(listOf(cbc.accessionNo, esr.accessionNo), c.open.map { it.accessionNo },
            "the CBC takes the haemogram's parameters; the ESR takes none of them")
        assertEquals(PARAMS_IN_FRAME, c.open.first().total)
        assertTrue(c.open.first().matched >= 10, "matched ${c.open.first().matched} of ${c.open.first().total}")
        assertEquals("matches ${c.open.first().matched} of $PARAMS_IN_FRAME parameters", c.open.first().matchNote)
        assertEquals(0, c.open.last().matched, "an ESR takes nothing a haematology analyzer sent")
        assertEquals("matches none of the $PARAMS_IN_FRAME parameters", c.open.last().matchNote)

        // The approved CBC would have matched every parameter and still must not
        // be offered — saying so is what stops the desk registering a duplicate.
        assertEquals(1, c.lockedCount)
        assertEquals("1 more order is past result entry (approved) and can no longer take results.", c.lockedNote)
        assertTrue(c.open.all { it.canTakeResults })
        assertFalse(c.windowFull, "three orders is not a full window — nothing to warn about")
        assertNull(c.windowNote)

        // The row carries who the operator is actually looking for.
        val first = c.open.first()
        assertEquals("Asha Menon", first.patientName)
        assertEquals("34 y / F", first.ageSex)
        assertEquals("+91 98765 43210", first.phone)
        assertTrue("Complete Blood Count" in first.tests, first.tests)
    }

    @Test
    fun `search matches the accession, the patient name and the phone`() = runBlocking {
        val (b, cbc, esr) = stocked()
        val open = b.engine.claimCandidates(b.queueOne()).getOrThrow().open

        assertEquals(listOf(esr.accessionNo), open.filterForClaim(esr.accessionNo).map { it.accessionNo })
        assertEquals(listOf(cbc.accessionNo), open.filterForClaim("asha").map { it.accessionNo }, "case-insensitive")
        assertEquals(listOf(cbc.accessionNo), open.filterForClaim("98765").map { it.accessionNo },
            "digits only: the stored number carries a +91 and spaces")
        assertEquals(listOf(esr.accessionNo), open.filterForClaim("Kumar").map { it.accessionNo })
        assertTrue(open.filterForClaim("nobody").isEmpty())
        assertEquals(open, open.filterForClaim("  "), "a blank query filters nothing")
    }

    @Test
    fun `a scanned accession assigns directly`() = runBlocking {
        val (b, cbc, _) = stocked()
        val resultId = b.queueOne()
        val open = b.engine.claimCandidates(resultId).getOrThrow().open

        // What the scanner types, Enter included.
        assertEquals(cbc.accessionNo, scanTargetFor(cbc.accessionNo, open))
        assertEquals(cbc.accessionNo, scanTargetFor(cbc.accessionNo.lowercase(), open), "scanners and prefixes vary in case")
        assertNull(scanTargetFor("   ", open), "nothing typed, nothing to do")
        assertEquals("ACC-S1-99999", scanTargetFor(" ACC-S1-99999 ", open),
            "unknown text goes to the engine, which knows the case variants and says what it found")

        // And the scan path is the assign path — no second way in.
        val summary = b.engine.claimUnmatched(resultId, scanTargetFor(cbc.accessionNo, open)!!).getOrThrow()
        assertTrue(cbc.accessionNo in summary, summary)
    }

    @Test
    fun `Enter never assigns on a name or a phone that happens to leave one order standing`() = runBlocking {
        val (b, cbc, esr) = stocked()
        val resultId = b.queueOne()
        val open = b.engine.claimCandidates(resultId).getOrThrow().open

        // "Ravi" leaves exactly one order on screen — and Enter must STILL not
        // write to it. The operator narrowed a list; they did not pick a patient.
        assertEquals(listOf(esr.accessionNo), open.filterForClaim("Ravi").map { it.accessionNo })
        assertEquals("Ravi", scanTargetFor("Ravi", open), "the raw text goes to the engine, not Ravi's order")
        assertTrue(narrowsToOneNonAccession("Ravi", open), "…and the screen says to tap the row instead")

        // The real bite: the row reads "Specimen BNMTEST-1", the operator types
        // the only handle they have, and it is a substring of somebody's PHONE.
        assertEquals(listOf(cbc.accessionNo), open.filterForClaim("98765").map { it.accessionNo },
            "the phone search still narrows — that part is wanted")
        assertEquals("98765", scanTargetFor("98765", open), "but a phone digit run is never an assign target")

        // And nothing lands: the engine refuses text that is not an accession.
        val err = b.engine.claimUnmatched(resultId, scanTargetFor("98765", open)!!).exceptionOrNull()
        assertNotNull(err)
        assertTrue("No order with accession" in err.message.orEmpty(), err.message.orEmpty())
        assertEquals("unmatched", b.queueRow(resultId)?.status, "the run is still claimable by the right person")

        // A partial barcode — a scanner that dropped the first character — is
        // likewise never a target. Note what this fixture does unprompted: the
        // fragment's DIGITS ("00001") are also inside Ravi's phone, so the
        // "narrowed" list is two orders belonging to two different patients.
        // That collision is not contrived; it is what a worklist looks like.
        val partial = cbc.accessionNo.drop(1)
        assertEquals(listOf(cbc.accessionNo, esr.accessionNo).sorted(),
            open.filterForClaim(partial).map { it.accessionNo }.sorted(),
            "an accession fragment reaches a phone number — exactly why near-enough must not write")
        assertEquals(partial, scanTargetFor(partial, open))

        // Even a fragment that DOES leave one row standing is only a hint.
        val lone = esr.accessionNo.drop(1)
        assertEquals(listOf(esr.accessionNo), open.filterForClaim(lone).map { it.accessionNo })
        assertEquals(lone, scanTargetFor(lone, open), "near-enough is not the same as scanned")
        assertTrue(narrowsToOneNonAccession(lone, open))

        // An exact accession is never mistaken for a narrowing — no hint, direct assign.
        assertFalse(narrowsToOneNonAccession(cbc.accessionNo, open))
    }

    // ── reaching past the loaded window ──────────────────────────────────────

    @Test
    fun `the search runs in SQL, so an order older than the window is still findable`() = runBlocking {
        val b = Bench()
        SeedCatalog.seedIfEmpty(b.repo)
        b.patient("p-asha", "Asha Menon", "F", 34, "+91 98765 43210")
        val wanted = b.order("p-asha", "CBC")
        // Bury it: every later order is newer, so `wanted` falls outside a small window.
        repeat(6) { i ->
            b.patient("p-$i", "Other Patient $i", "M", 30, "90000000$i")
            b.order("p-$i", "CBC")
        }
        val resultId = b.queueOne()

        val unfiltered = b.engine.claimCandidates(resultId, limit = 3).getOrThrow()
        assertEquals(3, unfiltered.open.size)
        assertFalse(wanted.accessionNo in unfiltered.open.map { it.accessionNo },
            "out of the window — this is the state the old code answered “nothing matches” from")
        assertTrue(unfiltered.windowFull, "and it says so, instead of letting the list read as the whole worklist")
        assertTrue(unfiltered.windowNote.orEmpty().contains("most recent"), unfiltered.windowNote.orEmpty())

        // Typing her name reaches her anyway, because the LIKE is in the query.
        val byName = b.engine.claimCandidates(resultId, query = "Asha", limit = 3).getOrThrow()
        assertEquals(listOf(wanted.accessionNo), byName.open.map { it.accessionNo })
        assertFalse(byName.windowFull, "one match does not fill the window")
        assertNull(byName.windowNote)

        // So does her phone, digits only, past the +91 and the spaces.
        assertEquals(listOf(wanted.accessionNo),
            b.engine.claimCandidates(resultId, query = "98765", limit = 3).getOrThrow().open.map { it.accessionNo })
        // Two digits is a fragment of every phone number, not a phone number.
        assertTrue(b.engine.claimCandidates(resultId, query = "98", limit = 3).getOrThrow().open.isEmpty(),
            "under three digits the phone column is not searched — same threshold as filterForClaim")
    }

    @Test
    fun `held-back orders are named by the status they are actually at, and only when they could have been the target`() = runBlocking {
        val b = Bench()
        SeedCatalog.seedIfEmpty(b.repo)
        b.patient("p-asha", "Asha Menon", "F", 34, "+91 98765 43210")
        b.lock(b.order("p-asha", "CBC"), LabStatus.VERIFIED)
        // Finished work that takes NONE of a haemogram's parameters: real in every
        // lab, and never the order this frame was meant for.
        repeat(5) { i ->
            b.patient("p-u$i", "Urine Patient $i", "M", 30, null)
            b.lock(b.order("p-u$i", "URINE-R"), LabStatus.APPROVED)
        }
        val resultId = b.queueOne()

        val c = b.engine.claimCandidates(resultId).getOrThrow()
        assertTrue(c.open.isEmpty())
        assertEquals(1, c.lockedCount,
            "only the CBC — five approved urine routines are not what a haemogram was meant for")
        val note = c.lockedNote.orEmpty()
        assertTrue("verified" in note, "the note names the status it is actually at: $note")
        assertFalse("approved" in note, "an order waiting for the pathologist is NOT approved: $note")
        assertTrue("past result entry" in note, note)

        // A search is the operator naming an order, so everything it reaches counts.
        val searched = b.engine.claimCandidates(resultId, query = "Urine").getOrThrow()
        assertEquals(5, searched.lockedCount)
        assertTrue("matching “Urine”" in searched.lockedNote.orEmpty(), searched.lockedNote.orEmpty())
        assertTrue("approved" in searched.lockedNote.orEmpty(), searched.lockedNote.orEmpty())
    }

    // ── what assigning writes ────────────────────────────────────────────────

    @Test
    fun `assigning goes through enterResult - instrument attribution, frozen flags and ranges, queue row consumed`() = runBlocking {
        val (b, cbc, _) = stocked()
        val resultId = b.queueOne()

        b.engine.claimUnmatched(resultId, cbc.accessionNo).getOrThrow()

        val results = b.repo.resultsForOrder(cbc.id).filter { it.isEntered }
        assertTrue(results.isNotEmpty())
        assertTrue(results.all { it.enteredBy == "BC-5130 bench 1" },
            "entered_by is the analyzer's configured name, exactly as the live path stamps it")
        val hb = results.single { it.parameterKey == "hb" }
        assertEquals("13.8", hb.value, "138 g/L converted to the catalog's g/dL")

        // "Frozen as for a bench entry" means literally that: the same value
        // typed by a person onto an identical patient produces the same flag and
        // the same printed range.
        b.patient("p-twin", "Bench Twin", "F", 34, null)
        val typed = b.order("p-twin", "CBC")
        b.repo.enterResult(typed.id, "seed-cbc", "hb", "13.8", enteredBy = "Tech. S. Kumar").getOrThrow()
        val byHand = b.repo.resultsForOrder(typed.id).single { it.parameterKey == "hb" }
        assertEquals(byHand.flag, hb.flag)
        assertEquals(byHand.refDisplay, hb.refDisplay)
        assertEquals(byHand.unit, hb.unit)
        assertNotNull(hb.refDisplay?.takeIf { it.isNotBlank() }, "a range was resolved and printed")

        // And the row leaves the queue, marked against the order it landed on.
        assertTrue(b.db.instrumentsQueries.listUnmatched().executeAsList().isEmpty())
        val row = b.queueRow(resultId)!!
        assertEquals("applied", row.status)
        assertEquals(cbc.id, row.matched_order_id)
    }

    // ── who did it ───────────────────────────────────────────────────────────

    @Test
    fun `a manual claim records the person, and reads differently from a frame that matched itself`() = runBlocking {
        val (b, cbc, _) = stocked()
        val resultId = b.queueOne()
        val tech = Staff(id = "s-1", name = "Meera Nair", role = StaffRole.TECHNICIAN)

        b.engine.claimUnmatched(resultId, cbc.accessionNo, by = tech).getOrThrow()

        // On the row: who, by name and by id.
        val row = b.queueRow(resultId)!!
        assertEquals("Meera Nair", row.claimed_by)
        assertEquals("s-1", row.claimed_by_id)

        // And in the trail, in words — the line the automatic path never writes.
        val log = b.db.instrumentsQueries.recentLog(50).executeAsList().map { it.summary }
        val claim = log.single { it.startsWith("Claimed by") }
        assertTrue("Meera Nair" in claim, claim)
        assertTrue("(manual)" in claim, "a human chose this order — that is the whole point: $claim")
        assertTrue(cbc.accessionNo in claim, claim)

        // enteredBy stays the ANALYZER: the instrument produced the numbers.
        assertTrue(b.repo.resultsForOrder(cbc.id).filter { it.isEntered }
            .all { it.enteredBy == "BC-5130 bench 1" })
    }

    @Test
    fun `discarding and printing a run are both written down`() = runBlocking {
        val b = Bench()
        SeedCatalog.seedIfEmpty(b.repo)
        val owner = Staff(id = "s-9", name = "Dr Rao", role = StaffRole.OWNER)
        val printed = b.queueOne()

        // A cancelled print dialog is not a print, and must leave no trace.
        b.engine.logWorksheetPrinted(printed, "Print cancelled", by = owner)
        assertTrue(b.db.instrumentsQueries.recentLog(50).executeAsList()
            .none { it.summary.startsWith("Worksheet printed") },
            "the operator backed out — the trail must not claim a page went out")
        b.engine.logWorksheetPrinted(printed, "Print failed: no printer", by = owner)
        assertTrue(b.db.instrumentsQueries.recentLog(50).executeAsList()
            .none { it.summary.startsWith("Worksheet printed") })

        b.engine.logWorksheetPrinted(printed, "Sent to printer", by = owner)
        b.engine.discardUnmatched(printed, by = owner)

        val row = b.queueRow(printed)!!
        assertEquals("discarded", row.status)
        assertEquals("Dr Rao", row.claimed_by)
        assertEquals("s-9", row.claimed_by_id)

        val log = b.db.instrumentsQueries.recentLog(50).executeAsList().map { it.summary }
        val discard = log.single { it.startsWith("Discarded by") }
        val sheet = log.single { it.startsWith("Worksheet printed by") }
        assertTrue("Dr Rao" in discard && "owner" in discard, discard)
        assertTrue("Dr Rao" in sheet, sheet)
        // Counts, never values — this log is read by support.
        assertTrue("parameters" in discard && "13.8" !in discard, discard)
    }

    @Test
    fun `an unattributed claim still says so rather than leaving a blank`() = runBlocking {
        val (b, cbc, _) = stocked()
        val resultId = b.queueOne()

        b.engine.claimUnmatched(resultId, cbc.accessionNo).getOrThrow()

        assertNull(b.queueRow(resultId)!!.claimed_by)
        val claim = b.db.instrumentsQueries.recentLog(50).executeAsList()
            .map { it.summary }.single { it.startsWith("Claimed by") }
        assertTrue("unidentified operator" in claim, claim)
    }

    @Test
    fun `an order that can no longer take results is refused even when its accession is typed by hand`() = runBlocking {
        val b = Bench()
        SeedCatalog.seedIfEmpty(b.repo)
        b.patient("p-old", "Latha Rao", "F", 60, null)
        val locked = b.order("p-old", "CBC")
        b.lock(locked)
        val resultId = b.queueOne()

        val err = b.engine.claimUnmatched(resultId, locked.accessionNo).exceptionOrNull()
        assertNotNull(err)
        assertTrue("locked" in err.message.orEmpty(), err.message.orEmpty())
        assertEquals("unmatched", b.queueRow(resultId)!!.status, "a refused claim leaves the result waiting")
    }

    @Test
    fun `the queue row shows what tells two samples apart`() = runBlocking {
        val b = Bench()
        b.engine.ingestBytes(b.cfg, Mllp.wrap(SampleFrames.mindrayOru()))
        val row = b.engine.queueFlow().first().single()

        assertEquals(SampleFrames.SPECIMEN, row.specimenId)
        assertEquals(PARAMS_IN_FRAME, row.paramCount)
        assertEquals(
            listOf("WBC" to "7.20 10*9/L", "HGB" to "138 g/L", "PLT" to "245 10*9/L"),
            row.headline,
            "the three numbers a haematology bench recognises a sample by, in the analyzer's own units",
        )
    }

    // ── who may touch it, and on what licence ────────────────────────────────

    @Test
    fun `the queue is bench work - the front desk may look, not act`() {
        fun who(role: String) = Staff(id = "s-$role", name = role, role = role)
        assertTrue(who(StaffRole.TECHNICIAN).allows(LabPermission.RESULTS))
        assertTrue(who(StaffRole.PATHOLOGIST).allows(LabPermission.RESULTS))
        assertTrue(who(StaffRole.OWNER).allows(LabPermission.RESULTS))
        assertFalse(who(StaffRole.RECEPTIONIST).allows(LabPermission.RESULTS),
            "assigning a run to the wrong patient is not a front-desk risk to take")
        assertFalse((null as Staff?).allows(LabPermission.RESULTS), "nobody signed in ⇒ no")
        assertFalse(who(StaffRole.TECHNICIAN).copy(active = false).allows(LabPermission.RESULTS))
    }

    @Test
    fun `a lapsed licence still reaches the queue - read-only never meant stop printing`() {
        val lapsed = LicenseState(lapsed = true)
        assertFalse(lapsed.canStartNewWork, "registering a new order is what a lapse refuses")
        assertFalse(LicenceGate.startsNewWork(Screen.Instruments.route))
        assertTrue(LicenceGate.allows(Screen.Instruments.route, lapsed),
            "the worksheet prints data the lab already has — exportable regardless of licence state")
        assertTrue(LicenceGate.allows(Screen.Instruments.route, LicenseState(licensed = true, blocked = true)))
    }
}
