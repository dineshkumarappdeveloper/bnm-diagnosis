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
        assertEquals("1 more order is approved and can no longer take results.", c.lockedNote)
        assertTrue(c.open.all { it.canTakeResults })

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
    fun `a scanned accession assigns directly, and so does a search that narrows to one order`() = runBlocking {
        val (b, cbc, esr) = stocked()
        val resultId = b.queueOne()
        val open = b.engine.claimCandidates(resultId).getOrThrow().open

        // What the scanner types, Enter included.
        assertEquals(cbc.accessionNo, scanTargetFor(cbc.accessionNo, open))
        assertEquals(cbc.accessionNo, scanTargetFor(cbc.accessionNo.lowercase(), open), "scanners and prefixes vary in case")
        assertEquals(esr.accessionNo, scanTargetFor("Ravi", open), "one match left ⇒ that is the intent")
        assertNull(scanTargetFor("   ", open), "nothing typed, nothing to do")
        assertEquals("ACC-S1-99999", scanTargetFor(" ACC-S1-99999 ", open),
            "unknown text goes to the engine, which knows the case variants and says what it found")

        // And the scan path is the assign path — no second way in.
        val summary = b.engine.claimUnmatched(resultId, scanTargetFor(cbc.accessionNo, open)!!).getOrThrow()
        assertTrue(cbc.accessionNo in summary, summary)
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
