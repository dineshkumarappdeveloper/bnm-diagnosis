package com.bnm.lab

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.bnm.lab.api.ApiClient
import com.bnm.lab.db.AppDatabase
import com.bnm.lab.instruments.CreateOrderDraft
import com.bnm.lab.instruments.CreateOrderGate
import com.bnm.lab.instruments.CreateOrderRefusal
import com.bnm.lab.instruments.InstrumentConfig
import com.bnm.lab.instruments.InstrumentEngine
import com.bnm.lab.instruments.InstrumentTransport
import com.bnm.lab.instruments.Mllp
import com.bnm.lab.instruments.PRIORITY_URGENT
import com.bnm.lab.instruments.SampleFrames
import com.bnm.lab.instruments.StoredInstrumentFrame
import com.bnm.lab.instruments.TestFit
import com.bnm.lab.instruments.analyzerAgeYears
import com.bnm.lab.instruments.analyzerPrefill
import com.bnm.lab.instruments.bestFit
import com.bnm.lab.instruments.duplicatesOf
import com.bnm.lab.instruments.rankTestsForFrame
import com.bnm.lab.instruments.sameName
import com.bnm.lab.instruments.validateCreateOrder
import com.bnm.lab.lab.LabOrder
import com.bnm.lab.lab.LabRepository
import com.bnm.lab.lab.LabStatus
import com.bnm.lab.lab.Patient
import com.bnm.lab.lab.SeedCatalog
import com.bnm.lab.license.LicenseState
import com.bnm.lab.staff.Staff
import com.bnm.lab.staff.StaffRole
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "Create order from this result" — the claim queue's third way out.
 *
 * The lab owner's report: the sample was run on the analyzer with the patient's
 * name keyed in, but nobody ever registered the order, so the run has nothing
 * to be assigned to. This is the one-step version of the trip the operator used
 * to make — and because it REGISTERS, the things pinned here are the things a
 * shortcut round the registration desk could quietly get wrong: whose accession
 * it is, whether the result still goes through the single write path, whether a
 * second copy of the patient appears, and who may do it at all.
 */
class CreateOrderFromResultTest {

    /** Numeric parameters in [SampleFrames.mindrayOru] — the CBC + 5-part diff. */
    private val PARAMS_IN_FRAME = 18

    /** The seeded Complete Blood Count's analyte count — the note's denominator. */
    private val CBC_PARAMS = 13

    private val currentLicence = LicenseState(licensed = true)
    private val owner = Staff(id = "s-owner", name = "Dr Rao", role = StaffRole.OWNER)
    private val tech = Staff(id = "s-tech", name = "Meera Nair", role = StaffRole.TECHNICIAN)

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

        /** A stranded run, put in the queue by the real listener path (no socket:
         *  the config carries no TCP port). Specimen BNMTEST-1 matches no order.
         *  The demographics are what the bench keyed at the analyzer, if any. */
        suspend fun queueOne(
            patientName: String? = null,
            sex: String? = null,
            ageYears: Int? = null,
        ): String {
            engine.saveInstrument(cfg)
            engine.ingestBytes(cfg, Mllp.wrap(SampleFrames.mindrayOru(patientName, sex, ageYears)))
            return db.instrumentsQueries.listUnmatched().executeAsList().single().id
        }

        fun queueRow(id: String) = db.instrumentsQueries.unmatchedById(id).executeAsOneOrNull()
        fun log() = db.instrumentsQueries.recentLog(50).executeAsList().map { it.summary }
        fun orderCount() = db.labOrdersQueries.countsByStatus().executeAsList().sumOf { it.n }
    }

    private suspend fun stocked(): Bench = Bench().also { SeedCatalog.seedIfEmpty(it.repo) }

    /** The form as an operator would leave it: name, sex, age, phone, the pre-selected test. */
    private fun draft(testId: String?, name: String = "Asha Menon", phone: String = "+91 98765 43210") =
        CreateOrderDraft(
            patientName = name, sex = "F", ageText = "34", phone = phone, testId = testId,
        )

    // ── which test the run belongs to ────────────────────────────────────────

    @Test
    fun `the catalog is ranked by what this run fills, and the best fit is pre-selected`() = runBlocking {
        val b = stocked()
        val resultId = b.queueOne()
        val ctx = b.engine.createOrderContext(resultId).getOrThrow()

        val best = assertNotNull(ctx.fits.bestFit(), "a haematology analyzer must find a haemogram")
        assertEquals("CBC", best.test.code, "the CBC takes more of a 5-part haemogram than anything else seeded")
        assertEquals(ctx.fits.first(), best, "and it is the head of the list, not merely somewhere in it")
        assertEquals(CBC_PARAMS, best.of)
        assertEquals(CBC_PARAMS, best.fills, "every analyte the seeded CBC has is in this frame")
        assertEquals("this result fills $CBC_PARAMS of $CBC_PARAMS parameters of Complete Blood Count", best.note)
        assertEquals(PARAMS_IN_FRAME, ctx.frameParams)
        assertFalse(ctx.nothingFits)

        // ONE matcher, not two. Register an order carrying the same test and the
        // Assign picker counts exactly what this dialog just promised — the two
        // screens cannot drift into offering different numbers for one frame.
        b.patient("p-x", "Someone Else", "F", 30, null)
        val sameTest = b.order("p-x", "CBC")
        val candidate = b.engine.claimCandidates(resultId).getOrThrow()
            .open.single { it.accessionNo == sameTest.accessionNo }
        assertEquals(best.fills, candidate.matched)

        // A one-analyte test is honestly a worse guess than the whole haemogram,
        // and ranks below it even though it fills 100% of itself.
        val hb = assertNotNull(ctx.fits.firstOrNull { it.test.code == "HB" })
        assertEquals(1, hb.fills)
        assertEquals("this result fills 1 of 1 parameter of Haemoglobin", hb.note)
        assertTrue(ctx.fits.indexOf(best) < ctx.fits.indexOf(hb))

        // And an ESR takes nothing a cell counter sent — offered only to a search.
        // Both sentences count the same denominator, so both pluralise it: the
        // master catalog is full of one-analyte tests and "none of the 1
        // parameters" is the wart an operator reads first.
        val esr = assertNotNull(ctx.fits.firstOrNull { it.test.code == "ESR" })
        assertTrue(esr.fitsNothing)
        assertEquals("this result fills none of the 1 parameter of Erythrocyte Sedimentation Rate", esr.note)
        assertEquals("this result fills none of the $CBC_PARAMS parameters of Complete Blood Count",
            TestFit(best.test, emptyMap()).note)
    }

    @Test
    fun `a test the frame cannot fill at all is refused before anything is written`() = runBlocking {
        val b = stocked()
        val resultId = b.queueOne()
        val ctx = b.engine.createOrderContext(resultId).getOrThrow()
        val esr = ctx.fits.single { it.test.code == "ESR" }

        // The pure rule, in the words the dialog shows.
        val why = assertNotNull(validateCreateOrder(draft(esr.test.id), ctx.fits, ctx.frameParams))
        assertTrue("takes none of the $PARAMS_IN_FRAME parameters" in why, why)

        // And the write path refuses the same thing, so a stale dialog cannot
        // walk past it: an order registered here with no result on it is worse
        // than the refusal — it is billable, and the run is still in the queue.
        val err = b.engine.createOrderForResult(resultId, draft(esr.test.id), tech, currentLicence).exceptionOrNull()
        assertNotNull(err)
        assertTrue("takes none of" in err.message.orEmpty(), err.message.orEmpty())
        assertEquals(0L, b.orderCount(), "no order was left behind")
        assertEquals("unmatched", b.queueRow(resultId)?.status)
        assertTrue(b.repo.searchPatients("Asha").isEmpty(), "and no patient either")
    }

    @Test
    fun `the form asks for what a reference range needs, and nothing more`() = runBlocking {
        val b = stocked()
        val ctx = b.engine.createOrderContext(b.queueOne()).getOrThrow()
        val cbc = ctx.fits.first().test.id
        fun check(d: CreateOrderDraft) = validateCreateOrder(d, ctx.fits, ctx.frameParams)

        assertEquals("Name is required", check(draft(cbc).copy(patientName = "  ")))
        assertEquals("Sex is required — reference ranges depend on it", check(draft(cbc).copy(sex = null)))
        assertEquals("Enter the age in years, or a DOB", check(draft(cbc).copy(ageText = "")))
        assertEquals("DOB must be YYYY-MM-DD", check(draft(cbc).copy(ageText = "", dob = "34 years")))
        assertEquals("Pick the test this run belongs to", check(draft(null)))
        assertNull(check(draft(cbc)), "name, sex, age and a test that fits is the whole bar")
        assertNull(check(draft(cbc).copy(ageText = "", dob = "1992-04-11")), "a DOB is an age")
        assertNull(check(draft(cbc).copy(phone = "")), "the phone stays optional, as at the desk")

        // Reusing a patient skips the demographics: they belong to the record on
        // file and are not retyped, let alone written over.
        assertNull(check(CreateOrderDraft(testId = cbc, reusePatientId = "p-1")))
    }

    // ── what the analyzer already told us about the patient ──────────────────

    @Test
    fun `the form opens on the demographics the analyzer was given, marked as the analyzer's`() = runBlocking {
        val b = stocked()
        // The feature's own premise: the bench DID key the patient in.
        val resultId = b.queueOne(patientName = "MENON^ASHA", sex = "Female", ageYears = 34)
        val ctx = b.engine.createOrderContext(resultId).getOrThrow()

        // Parsed by the driver AND carried onto the stored frame. Dropping them
        // between the two was what made the operator read the name off the
        // analyzer's screen and retype it.
        assertEquals("ASHA MENON", ctx.frame.patientName)
        assertEquals("F", ctx.frame.patientSex)

        val pre = ctx.prefill
        assertEquals("ASHA MENON", pre.name)
        assertEquals("F", pre.sex)
        assertEquals("34", pre.ageText)
        assertFalse(pre.isEmpty)

        // Seeded, never silently: bench text is not the lab's record, and a form
        // that arrives filled in unannounced is a form nobody checks.
        val note = assertNotNull(ctx.prefillNote)
        assertTrue("name, sex and age" in note, note)
        assertTrue("BC-5130 bench 1" in note, note)
        assertTrue("Check it" in note, note)

        // Every field stays editable and the seeded form validates as it stands.
        val seeded = CreateOrderDraft(
            patientName = pre.name, sex = pre.sex, ageText = pre.ageText,
            testId = ctx.fits.first().test.id,
        )
        assertNull(validateCreateOrder(seeded, ctx.fits, ctx.frameParams))
        val out = b.engine.createOrderForResult(resultId, seeded, tech, currentLicence).getOrThrow()
        assertEquals("ASHA MENON", out.patient.name, "the name the analyzer was given, not a retyped one")
        assertEquals("F", out.patient.sex)
        assertEquals(34L, out.patient.ageYears)
    }

    @Test
    fun `an analyzer that keyed nothing pre-fills nothing, and says nothing`() = runBlocking {
        val b = stocked()
        val ctx = b.engine.createOrderContext(b.queueOne()).getOrThrow()
        assertNull(ctx.frame.patientName)
        assertNull(ctx.frame.patientSex)
        assertTrue(ctx.prefill.isEmpty)
        assertNull(ctx.prefillNote, "no note where there is nothing to check")
        assertNull(analyzerPrefill(StoredInstrumentFrame(driver = "mindray_hl7")).fieldsLabel)
    }

    @Test
    fun `the analyzer's age is seeded only when the analyzer said years`() {
        assertEquals("34", analyzerAgeYears(mapOf("age" to "34 a")), "HL7 table 0102: 'a' is years")
        assertEquals("42", analyzerAgeYears(mapOf("age" to "42 yr")))
        assertEquals("7", analyzerAgeYears(mapOf("age" to "7")), "no unit at all is years, as the box is")
        // 🔴 The whole reason this is not `takeWhile { it.isDigit() }`: a
        // six-MONTH-old seeded as six YEARS prints a paediatric haemogram
        // against adult reference ranges, and reads normal when it is not.
        assertEquals("", analyzerAgeYears(mapOf("age" to "6 mo")))
        assertEquals("", analyzerAgeYears(mapOf("age" to "18 d")))
        assertEquals("", analyzerAgeYears(mapOf("age" to "unknown")))
        assertEquals("", analyzerAgeYears(emptyMap()))
    }

    @Test
    fun `an analyzer sex the app cannot store is left for the operator to answer`() = runBlocking {
        val b = stocked()
        // HL7 table 0001 also carries U / A / N. None of them is "Other": they
        // are the analyzer saying it does not know, and a guessed 'O' silently
        // picks the reference range the report is printed against.
        val ctx = b.engine.createOrderContext(b.queueOne(patientName = "KUMAR^RAVI", sex = "U")).getOrThrow()
        assertEquals("RAVI KUMAR", ctx.frame.patientName)
        assertNull(ctx.frame.patientSex)
        assertNull(ctx.prefill.sex)
        assertEquals("name", ctx.prefill.fieldsLabel)
        // …and the form still refuses to register without one.
        assertEquals(
            "Sex is required — reference ranges depend on it",
            validateCreateOrder(
                CreateOrderDraft(patientName = ctx.prefill.name, ageText = "51",
                    testId = ctx.fits.first().test.id),
                ctx.fits, ctx.frameParams,
            ),
        )
    }

    // ── what creating actually writes ────────────────────────────────────────

    @Test
    fun `creating registers the patient and the order, mints OUR accession, and lands the run through enterResult`() = runBlocking {
        val b = stocked()
        val resultId = b.queueOne()
        val ctx = b.engine.createOrderContext(resultId).getOrThrow()
        val cbc = ctx.fits.first().test

        val out = b.engine.createOrderForResult(
            resultId,
            draft(cbc.id).copy(priority = PRIORITY_URGENT),
            by = tech, licence = currentLicence,
        ).getOrThrow()

        // The patient the analyzer's operator described, now a record.
        assertFalse(out.reusedPatient)
        assertEquals("Asha Menon", out.patient.name)
        assertEquals("F", out.patient.sex)
        assertEquals(34L, out.patient.ageYears)
        assertEquals("+91 98765 43210", out.patient.phone)

        // 🔴 The accession is the app's, never the text keyed on the analyzer.
        val order = assertNotNull(b.repo.orderById(out.order.id))
        assertTrue(order.accessionNo.startsWith(b.repo.ownAccessionSeries()), order.accessionNo)
        assertFalse(SampleFrames.SPECIMEN in order.accessionNo,
            "the analyzer's specimen text must never become an accession: ${order.accessionNo}")
        assertEquals(PRIORITY_URGENT, order.priority)
        // …and it is not lost either: it rides along as the sample reference.
        assertTrue(SampleFrames.SPECIMEN in order.notes.orEmpty(), order.notes.orEmpty())
        assertTrue("BC-5130 bench 1" in order.notes.orEmpty(), order.notes.orEmpty())

        // The numbers went through enterResult, exactly as the claim path does:
        // the instrument is the author, the units are the catalog's.
        assertTrue(out.resultsLanded, out.claimError.orEmpty())
        val results = b.repo.resultsForOrder(order.id).filter { it.isEntered }
        assertTrue(results.isNotEmpty())
        assertTrue(results.all { it.enteredBy == "BC-5130 bench 1" },
            "entered_by is the analyzer, as it is for a frame that matched on its own")
        assertEquals("13.8", results.single { it.parameterKey == "hb" }.value, "138 g/L became the catalog's g/dL")
        assertNotNull(results.first().refDisplay?.takeIf { it.isNotBlank() }, "ranges were resolved and frozen")

        // The queue row is consumed and says who chose the order.
        assertTrue(b.db.instrumentsQueries.listUnmatched().executeAsList().isEmpty())
        val row = assertNotNull(b.queueRow(resultId))
        assertEquals("applied", row.status)
        assertEquals(order.id, row.matched_order_id)
        assertEquals("Meera Nair", row.claimed_by)
        assertEquals("s-tech", row.claimed_by_id)

        // One audit line saying where this order came from — before the claim
        // line, and carrying counts, never values or the patient's name.
        val created = b.log().single { it.startsWith("Order ") && "created from a queued analyzer result" in it }
        assertTrue(order.accessionNo in created, created)
        assertTrue("Meera Nair (technician)" in created, created)
        assertTrue("new patient" in created, created)
        assertTrue("Complete Blood Count" in created, created)
        assertFalse("Asha" in created, "the traffic log goes to support — no patient names: $created")
        assertFalse("13.8" in created, created)
        assertTrue(b.log().any { it.startsWith("Claimed by") }, "and the ordinary claim line still follows")
    }

    @Test
    fun `a patient is never left behind by an order that failed to register`() = runBlocking {
        val b = stocked()
        val ravi = Patient(id = "p-ravi", name = "Ravi Kumar", sex = "M", ageYears = 51)

        // The order half fails after the patient half would have written. As two
        // transactions this committed the patient anyway, said only "failed", and
        // the operator's retry wrote a SECOND Ravi Kumar — past the duplicate
        // guard, which returns nothing at all when no phone was typed.
        val failed = b.repo.createPatientAndOrder(ravi, testIds = listOf("no-such-test"))
        assertTrue(failed.isFailure)
        assertTrue("Test not found" in failed.exceptionOrNull()?.message.orEmpty(),
            failed.exceptionOrNull()?.message.orEmpty())
        assertNull(b.repo.patientById("p-ravi"), "no orphan: either both rows exist or neither does")
        assertEquals(0L, b.orderCount())
        assertTrue(b.engine.duplicatePatients("Ravi Kumar", null).isEmpty())

        // And the retry, which is now the FIRST write, leaves exactly one of each.
        val (saved, order) = b.repo.createPatientAndOrder(ravi, testIds = listOf("seed-cbc")).getOrThrow()
        assertEquals("p-ravi", saved.id)
        assertEquals("p-ravi", order.patientId)
        assertTrue(order.accessionNo.startsWith(b.repo.ownAccessionSeries()), order.accessionNo)
        assertEquals(1, b.repo.searchPatients("Ravi Kumar").size)
        assertEquals(1, b.repo.ordersForPatient("p-ravi").size)
        assertEquals("Complete Blood Count", b.repo.orderTests(order.id).single().testName)
        assertTrue(b.repo.resultsForOrder(order.id).isNotEmpty(), "the empty result rows came with it")
    }

    // ── the duplicate-patient guard ──────────────────────────────────────────

    @Test
    fun `the same name and phone offers the patient on file, and reusing makes them a NEW order`() = runBlocking {
        val b = stocked()
        val existing = b.patient("p-asha", "Asha Menon", "F", 34, "+91 98765 43210")
        val oldOrder = b.order(existing.id, "CBC")
        val resultId = b.queueOne()
        val cbc = b.engine.createOrderContext(resultId).getOrThrow().fits.first().test

        // Asked BEFORE anything is written, on the digits, past the +91 and spaces.
        val matches = b.engine.duplicatePatients("asha  menon", "9876543210")
        val match = matches.single()
        assertEquals("p-asha", match.patient.id)
        assertEquals(1, match.totalOrders)
        assertEquals(oldOrder.accessionNo, match.recentOrders.single().accessionNo,
            "what the record has been to this lab is the evidence a person recognises them by")

        val out = b.engine.createOrderForResult(
            resultId, draft(cbc.id).copy(reusePatientId = match.patient.id), tech, currentLicence,
        ).getOrThrow()

        assertTrue(out.reusedPatient)
        assertEquals(1, b.repo.searchPatients("Asha Menon").size, "no second Asha Menon was written")
        // 🔴 Reuse is about the PATIENT, never the order: this run gets its own
        // accession. Landing it on the old order would rewrite a sample the lab
        // already ran, under an accession already on a printed barcode.
        assertFalse(out.order.id == oldOrder.id)
        assertEquals(2, b.repo.ordersForPatient("p-asha").size)
        assertTrue(b.repo.resultsForOrder(oldOrder.id).none { it.isEntered }, "the old order was not touched")
        assertTrue(b.repo.resultsForOrder(out.order.id).any { it.isEntered })
        assertTrue(b.log().any { "existing patient" in it && out.order.accessionNo in it })
    }

    @Test
    fun `a different name on the same phone is a different person, and a near miss is not offered`() = runBlocking {
        val b = stocked()
        b.patient("p-asha", "Asha Menon", "F", 34, "+91 98765 43210")
        b.patient("p-ravi", "Ravi Kumar", "M", 51, "9000011111")

        // One household, one number: the People-roster reality. Matching on the
        // phone alone would offer the mother's record for her son's sample.
        assertTrue(b.engine.duplicatePatients("Kiran Menon", "9876543210").isEmpty())
        // Two strangers of the same name are not the same person either.
        assertTrue(b.engine.duplicatePatients("Asha Menon", "9000022222").isEmpty())
        // Deliberately strict: an initial is not a match, because reusing the
        // wrong record is how a haemogram reaches somebody else's file.
        assertTrue(b.engine.duplicatePatients("A Menon", "9876543210").isEmpty())
        // Case and stray spacing are not a different person, though.
        assertEquals(1, b.engine.duplicatePatients("  asha   MENON ", "+91 98765 43210").size)
        assertTrue(sameName("Asha  Menon", "asha menon"))
        assertFalse(sameName("Asha Menon", "Asha Menon Rao"))
        // A phone nobody typed is no evidence at all — nothing is offered.
        assertTrue(b.engine.duplicatePatients("Asha Menon", null).isEmpty())
        assertTrue(b.engine.duplicatePatients("Asha Menon", " ").isEmpty())

        // And a soft-deleted record is never offered back.
        b.repo.softDeletePatient("p-asha")
        assertTrue(b.repo.patientsByPhone("9876543210").duplicatesOf("Asha Menon").isEmpty())
    }

    // ── who may, and on what licence ─────────────────────────────────────────

    @Test
    fun `a lapsed licence refuses the registration and leaves the run claimable`() = runBlocking {
        val b = stocked()
        val resultId = b.queueOne()
        val cbc = b.engine.createOrderContext(resultId).getOrThrow().fits.first().test
        val lapsed = LicenseState(lapsed = true)

        // The same refusal the New order route gives, in the same words — this
        // IS new work, whoever is standing at the bench.
        val refusal = assertNotNull(CreateOrderGate.refusal(owner, lapsed)) as CreateOrderRefusal.Licence
        assertTrue("read-only" in refusal.title, refusal.title)
        assertTrue("stays in the queue" in refusal.detail, refusal.detail)

        val err = b.engine.createOrderForResult(resultId, draft(cbc.id), owner, lapsed).exceptionOrNull()
        assertNotNull(err, "a screen is not a gate — the write path refuses too")
        assertEquals(0L, b.orderCount())
        assertEquals("unmatched", b.queueRow(resultId)?.status,
            "read-only never meant losing the run: it is still assignable and still printable")

        // A device BNM blocked is refused for its own reason, which renewing
        // would not fix, so the copy has to differ.
        val blocked = assertNotNull(
            CreateOrderGate.refusal(owner, LicenseState(licensed = true, blocked = true))
        )
        assertTrue("deactivated" in blocked.title, blocked.title)
    }

    @Test
    fun `the front desk may not, the bench and the owner on a current licence may`() = runBlocking {
        val b = stocked()
        val resultId = b.queueOne()
        val cbc = b.engine.createOrderContext(resultId).getOrThrow().fits.first().test
        fun who(role: String) = Staff(id = "s-$role", name = role, role = role)

        val desk = who(StaffRole.RECEPTIONIST)
        val refusal = assertNotNull(CreateOrderGate.refusal(desk, currentLicence)) as CreateOrderRefusal.Permission
        assertTrue("bench" in refusal.title, refusal.title)
        assertNotNull(
            b.engine.createOrderForResult(resultId, draft(cbc.id), desk, currentLicence).exceptionOrNull(),
        )
        assertEquals(0L, b.orderCount())
        assertNull(CreateOrderGate.refusal(owner, currentLicence))
        assertNull(CreateOrderGate.refusal(tech, currentLicence))
        assertNull(CreateOrderGate.refusal(who(StaffRole.PATHOLOGIST), currentLicence))
        assertNotNull(CreateOrderGate.refusal(null, currentLicence), "nobody signed in ⇒ no")

        // Registration's OWN requirement is asked of the one place that decides
        // it, so this door cannot become the way round a permission the desk
        // gains later. Today it asks for none — pinned, so the day that changes
        // the change is deliberate.
        assertNull(CreateOrderGate.registrationRequirement(),
            "New order is open to every role; if that changes, this gate must gain the same rule")

        // The owner's run goes through, on the same licence.
        val out = b.engine.createOrderForResult(resultId, draft(cbc.id), owner, currentLicence).getOrThrow()
        assertTrue(out.resultsLanded)
        assertEquals("Dr Rao", b.queueRow(resultId)?.claimed_by)
    }

    // ── the run is claimed once, whichever door it leaves by ─────────────────

    @Test
    fun `a run already assigned cannot be registered a second time`() = runBlocking {
        val b = stocked()
        val resultId = b.queueOne()
        val cbc = b.engine.createOrderContext(resultId).getOrThrow().fits.first().test
        b.engine.createOrderForResult(resultId, draft(cbc.id), tech, currentLicence).getOrThrow()

        val err = b.engine.createOrderForResult(resultId, draft(cbc.id, name = "Someone Else"), tech, currentLicence)
            .exceptionOrNull()
        assertNotNull(err)
        assertTrue("Already applied" in err.message.orEmpty(), err.message.orEmpty())
        assertEquals(1L, b.orderCount(), "a double tap must not register a second order for the same tube")
    }

    @Test
    fun `an order registered here is an ordinary order - it walks the same statuses`() = runBlocking {
        val b = stocked()
        val resultId = b.queueOne()
        val cbc = b.engine.createOrderContext(resultId).getOrThrow().fits.first().test

        val out = b.engine.createOrderForResult(resultId, draft(cbc.id), tech, currentLicence).getOrThrow()

        // enterResult walks the order off `registered` on its own, exactly as it
        // does for a bench entry — nothing about this route is a special case.
        val order = assertNotNull(b.repo.orderById(out.order.id))
        assertTrue(order.status in LabStatus.ENTRY_OPEN, order.status)
        assertFalse(order.status == LabStatus.REGISTERED, "results landed: it has moved on")
        assertEquals(1, b.repo.orderTests(order.id).size)
        assertEquals("Complete Blood Count", b.repo.orderTests(order.id).single().testName)
    }
}
