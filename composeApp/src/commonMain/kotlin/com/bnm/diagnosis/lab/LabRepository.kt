package com.bnm.diagnosis.lab

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import com.bnm.diagnosis.db.AppDatabase
import com.bnm.diagnosis.db.Emr_inbox
import com.bnm.diagnosis.db.Lab_order_tests
import com.bnm.diagnosis.db.Lab_orders
import com.bnm.diagnosis.db.Lab_panels
import com.bnm.diagnosis.db.Lab_results
import com.bnm.diagnosis.db.Lab_tests
import com.bnm.diagnosis.db.OpenOrders
import com.bnm.diagnosis.db.Patients
import com.bnm.diagnosis.db.Referrers
import com.bnm.diagnosis.db.WorklistByStatus
import kotlin.math.floor
import kotlin.math.round
import kotlin.math.roundToLong
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Offline-first LIMS data layer. SQLDelight is the SYSTEM OF RECORD — every
 * method here works with zero network, indefinitely. Sync (P3) will be
 * additive on top of these tables, never required by them.
 *
 * Key invariants:
 *  - Accession numbers come from the per-seat never-rewind series
 *    (`accession_series`, bump-then-read inside the order transaction).
 *  - Order creation pre-creates EMPTY `lab_results` rows for every parameter
 *    of every ordered test, so results entry is a fill-in-the-grid worklist.
 *  - Flags + ref_display are computed at ENTRY time against the patient's
 *    then-age/sex and frozen on the row (the printed report never shifts when
 *    the catalog's ranges are later edited).
 */
@OptIn(ExperimentalUuidApi::class)
class LabRepository(
    private val db: AppDatabase,
    private val json: Json,
    private val prefs: DiagnosisPrefs = DiagnosisPrefs(),
) {
    private val pQ get() = db.patientsQueries
    private val rQ get() = db.referrersQueries
    private val tQ get() = db.testCatalogQueries
    private val oQ get() = db.labOrdersQueries
    private val resQ get() = db.resultsQueries
    private val accQ get() = db.accessionSeriesQueries
    private val emrQ get() = db.emrInboxQueries

    private val paramsSerializer = ListSerializer(TestParameter.serializer())
    private val idsSerializer = ListSerializer(String.serializer())

    // ── Patients ─────────────────────────────────────────────────────────────

    suspend fun upsertPatient(p: Patient): Patient = withContext(Dispatchers.Default) {
        val now = nowIso()
        val existing = pQ.byId(p.id).executeAsOneOrNull()
        val saved = p.copy(
            createdAt = existing?.created_at ?: p.createdAt.ifBlank { now },
            updatedAt = now,
        )
        pQ.upsert(saved.id, saved.name, saved.sex, saved.dob, saved.ageYears, saved.phone,
            saved.address, saved.createdAt, saved.updatedAt, saved.deletedAt)
        saved
    }

    suspend fun patientById(id: String): Patient? = withContext(Dispatchers.Default) {
        pQ.byId(id).executeAsOneOrNull()?.toModel()
    }

    /** Registration-desk lookup: name/phone prefix, 50 newest. Blank query = recents. */
    suspend fun searchPatients(query: String): List<Patient> = withContext(Dispatchers.Default) {
        val q = query.trim()
        if (q.isEmpty()) pQ.recent().executeAsList().map { it.toModel() }
        else pQ.search(q).executeAsList().map { it.toModel() }
    }

    suspend fun softDeletePatient(id: String) = withContext(Dispatchers.Default) {
        pQ.softDelete(nowIso(), nowIso(), id)
    }

    // ── Referrers ────────────────────────────────────────────────────────────

    suspend fun upsertReferrer(r: Referrer): Referrer = withContext(Dispatchers.Default) {
        val saved = r.copy(createdAt = r.createdAt.ifBlank { nowIso() })
        rQ.upsert(saved.id, saved.name, saved.kind, saved.phone, saved.commissionPct,
            saved.createdAt, saved.deletedAt)
        saved
    }

    suspend fun referrerById(id: String): Referrer? = withContext(Dispatchers.Default) {
        rQ.byId(id).executeAsOneOrNull()?.toModel()
    }

    suspend fun listReferrers(): List<Referrer> = withContext(Dispatchers.Default) {
        rQ.list().executeAsList().map { it.toModel() }
    }

    suspend fun softDeleteReferrer(id: String) = withContext(Dispatchers.Default) {
        rQ.softDelete(nowIso(), id)
    }

    // ── Test catalog ─────────────────────────────────────────────────────────

    suspend fun upsertTest(t: LabTest) = withContext(Dispatchers.Default) {
        tQ.upsertTest(t.id, t.code, t.name, t.category, t.price, t.sampleType, t.method,
            if (t.active) 1L else 0L, t.sortOrder.toLong(), json.encodeToString(paramsSerializer, t.parameters))
    }

    suspend fun testById(id: String): LabTest? = withContext(Dispatchers.Default) {
        tQ.testById(id).executeAsOneOrNull()?.toModel()
    }

    suspend fun testByCode(code: String): LabTest? = withContext(Dispatchers.Default) {
        tQ.testByCode(code).executeAsOneOrNull()?.toModel()
    }

    suspend fun listTests(includeInactive: Boolean = false): List<LabTest> = withContext(Dispatchers.Default) {
        (if (includeInactive) tQ.listAllTests() else tQ.listTests()).executeAsList().map { it.toModel() }
    }

    suspend fun setTestActive(id: String, active: Boolean) = withContext(Dispatchers.Default) {
        tQ.setTestActive(if (active) 1L else 0L, id)
    }

    suspend fun deleteTest(id: String) = withContext(Dispatchers.Default) { tQ.deleteTest(id) }

    suspend fun countTests(): Long = withContext(Dispatchers.Default) { tQ.countTests().executeAsOne() }

    suspend fun upsertPanel(p: LabPanel) = withContext(Dispatchers.Default) {
        tQ.upsertPanel(p.id, p.code, p.name, p.price,
            json.encodeToString(idsSerializer, p.testIds), if (p.active) 1L else 0L)
    }

    suspend fun panelById(id: String): LabPanel? = withContext(Dispatchers.Default) {
        tQ.panelById(id).executeAsOneOrNull()?.toModel()
    }

    suspend fun listPanels(includeInactive: Boolean = false): List<LabPanel> = withContext(Dispatchers.Default) {
        (if (includeInactive) tQ.listAllPanels() else tQ.listPanels()).executeAsList().map { it.toModel() }
    }

    suspend fun deletePanel(id: String) = withContext(Dispatchers.Default) { tQ.deletePanel(id) }

    suspend fun countPanels(): Long = withContext(Dispatchers.Default) { tQ.countPanels().executeAsOne() }

    // ── Orders ───────────────────────────────────────────────────────────────

    /**
     * Register a new lab order in ONE transaction: allocate the accession
     * number (per-seat bump-then-read — a crash never reissues), insert the
     * order + its test lines (name/price snapshotted), and pre-create EMPTY
     * result rows for every parameter of every test (worklist-ready).
     * Panels are expanded to their tests; duplicates collapse.
     */
    suspend fun createLabOrder(
        patientId: String,
        testIds: List<String> = emptyList(),
        panelIds: List<String> = emptyList(),
        referrerId: String? = null,
        invoiceId: String? = null,
        priority: String = "routine",
        notes: String? = null,
    ): Result<LabOrder> = withContext(Dispatchers.Default) {
        runCatching {
            pQ.byId(patientId).executeAsOneOrNull() ?: error("Patient not found: $patientId")
            val expanded = LinkedHashSet<String>()
            expanded += testIds
            for (pid in panelIds) {
                val panel = tQ.panelById(pid).executeAsOneOrNull()?.toModel() ?: error("Panel not found: $pid")
                expanded += panel.testIds
            }
            require(expanded.isNotEmpty()) { "Order needs at least one test" }
            val tests = expanded.map { tid ->
                tQ.testById(tid).executeAsOneOrNull()?.toModel() ?: error("Test not found: $tid")
            }
            val seat = prefs.accessionSeat
            val orderId = Uuid.random().toString()
            val now = nowIso()
            db.transactionWithResult {
                // Atomic allocate: seed the seat row if new, bump, then read.
                accQ.init(seat, prefs.accessionPrefix)
                accQ.bump(seat)
                val seq = accQ.highWater(seat).executeAsOne()
                val prefix = accQ.getSeries(seat).executeAsOne().prefix
                val accession = "$prefix-$seat-${seq.toString().padStart(5, '0')}"

                oQ.insertOrder(orderId, accession, patientId, referrerId, invoiceId,
                    LabStatus.REGISTERED, priority, notes, now, now)
                for (t in tests) {
                    oQ.insertOrderTest(Uuid.random().toString(), orderId, t.id, t.name, t.price, "pending")
                    for (param in t.parameters) {
                        resQ.insertEmpty(Uuid.random().toString(), orderId, t.id, param.key, param.unit)
                    }
                }
                LabOrder(
                    id = orderId, accessionNo = accession, patientId = patientId,
                    referrerId = referrerId, invoiceId = invoiceId, status = LabStatus.REGISTERED,
                    priority = priority, notes = notes, createdAt = now, updatedAt = now,
                )
            }
        }
    }

    suspend fun orderById(id: String): LabOrder? = withContext(Dispatchers.Default) {
        oQ.byId(id).executeAsOneOrNull()?.toModel()
    }

    suspend fun orderByAccession(accessionNo: String): LabOrder? = withContext(Dispatchers.Default) {
        oQ.byAccession(accessionNo).executeAsOneOrNull()?.toModel()
    }

    suspend fun ordersForPatient(patientId: String): List<LabOrder> = withContext(Dispatchers.Default) {
        oQ.ordersForPatient(patientId).executeAsList().map { it.toModel() }
    }

    suspend fun orderTests(orderId: String): List<LabOrderTest> = withContext(Dispatchers.Default) {
        oQ.testsForOrder(orderId).executeAsList().map { it.toModel() }
    }

    /** One pipeline stage's worklist, patient identity joined in. */
    suspend fun worklist(status: String): List<WorklistEntry> = withContext(Dispatchers.Default) {
        oQ.worklistByStatus(status).executeAsList().map { it.toEntry() }
    }

    /** Reactive worklist (results-entry screens live on this). */
    fun worklistFlow(status: String): Flow<List<WorklistEntry>> =
        oQ.worklistByStatus(status).asFlow().mapToList(Dispatchers.Default)
            .map { rows -> rows.map { it.toEntry() } }
            .catch { emit(emptyList()) }

    suspend fun countByStatusToday(status: String): Long = withContext(Dispatchers.Default) {
        oQ.countByStatusToday(status, todayLocalDate()).executeAsOne()
    }

    /** Dashboard: every order still moving through the pipeline (registered →
     *  approved; reported/delivered/cancelled are terminal), newest first. */
    fun openOrdersFlow(limit: Long): Flow<List<WorklistEntry>> =
        oQ.openOrders(limit).asFlow().mapToList(Dispatchers.Default)
            .map { rows -> rows.map { it.toEntry() } }
            .catch { emit(emptyList()) }

    /** Dashboard: today's critical results (CL/CH) with the patient's name and
     *  phone — the call-out list (labs must phone criticals). */
    fun criticalsTodayFlow(limit: Long): Flow<List<CriticalResult>> =
        resQ.criticalsToday(todayLocalDate(), limit).asFlow().mapToList(Dispatchers.Default)
            .map { rows ->
                rows.map {
                    CriticalResult(
                        orderId = it.order_id, testId = it.test_id, parameterKey = it.parameter_key,
                        value = it.value_, unit = it.unit, flag = it.flag,
                        accessionNo = it.accession_no, patientName = it.patient_name,
                        patientPhone = it.patient_phone,
                    )
                }
            }
            .catch { emit(emptyList()) }

    /** Attach the GST bill to an order (a lab bill IS a GST invoice). */
    suspend fun linkInvoice(orderId: String, invoiceId: String) = withContext(Dispatchers.Default) {
        oQ.linkInvoice(invoiceId, nowIso(), orderId)
    }

    /**
     * Generic guarded transition: only ONE step forward along [LabStatus.FLOW]
     * (or cancel from any non-terminal state). `verified`/`approved` must go
     * through [verifyOrder]/[approveOrder] — they carry the audit stamps.
     * Stamps collected_at / reported_at as those stages are reached.
     */
    suspend fun setOrderStatus(orderId: String, newStatus: String): Result<Unit> = withContext(Dispatchers.Default) {
        runCatching {
            val order = oQ.byId(orderId).executeAsOneOrNull()?.toModel() ?: error("Order not found: $orderId")
            when (newStatus) {
                LabStatus.VERIFIED -> error("Use verifyOrder() — verification stamps the audit trail")
                LabStatus.APPROVED -> error("Use approveOrder() — approval stamps the audit trail")
                LabStatus.CANCELLED -> require(
                    order.status != LabStatus.DELIVERED && order.status != LabStatus.CANCELLED
                ) { "Cannot cancel a ${order.status} order" }
                else -> {
                    val cur = LabStatus.FLOW.indexOf(order.status)
                    val next = LabStatus.FLOW.indexOf(newStatus)
                    require(next >= 0) { "Unknown status: $newStatus" }
                    require(cur >= 0) { "A ${order.status} order cannot move to $newStatus" }
                    require(next == cur + 1) { "Cannot move ${order.status} → $newStatus" }
                }
            }
            val now = nowIso()
            db.transaction {
                oQ.setStatus(newStatus, now, orderId)
                when (newStatus) {
                    LabStatus.COLLECTED -> oQ.stampCollected(now, orderId)
                    LabStatus.REPORTED -> oQ.stampReported(now, orderId)
                }
            }
        }
    }

    // ── Results ──────────────────────────────────────────────────────────────

    /**
     * Enter (or correct) one parameter's value. Computes the flag + printed
     * range by matching the PATIENT's age/sex against the parameter's ranges:
     * numeric values → N/L/H/CL/CH; qualitative ranges (with `text`) → 'N'
     * when equal ignoring case, else 'A'. Also walks the per-test and order
     * statuses (first entry → in_progress; all parameters filled → entered).
     * A blank value clears the cell. Locked once the order is verified+.
     */
    suspend fun enterResult(
        orderId: String,
        testId: String,
        paramKey: String,
        value: String,
        enteredBy: String? = null,
    ): Result<LabResult> = withContext(Dispatchers.Default) {
        runCatching {
            val order = oQ.byId(orderId).executeAsOneOrNull()?.toModel() ?: error("Order not found: $orderId")
            require(order.status in ENTRY_OPEN_STATUSES) {
                "Results are locked once an order is ${order.status}"
            }
            val patient = pQ.byId(order.patientId).executeAsOneOrNull()?.toModel()
                ?: error("Patient not found: ${order.patientId}")
            val test = tQ.testById(testId).executeAsOneOrNull()?.toModel() ?: error("Test not found: $testId")
            val param = test.parameters.firstOrNull { it.key == paramKey }
                ?: error("Unknown parameter '$paramKey' for ${test.code}")

            val cleanValue = value.trim()
            val age = resolveAgeYears(patient.dob, patient.ageYears)
            val range = pickRange(param.ranges, patient.sex, age)
            val flag = if (cleanValue.isEmpty()) null else computeFlag(cleanValue, range)
            val refDisp = refDisplay(range, param.decimals)
            val now = nowIso()

            db.transactionWithResult {
                val existing = resQ.byKey(orderId, testId, paramKey).executeAsOneOrNull()
                if (existing != null) {
                    resQ.updateValue(cleanValue, param.unit, flag, refDisp, enteredBy, now,
                        orderId, testId, paramKey)
                } else {
                    resQ.insertResult(Uuid.random().toString(), orderId, testId, paramKey,
                        cleanValue, param.unit, flag, refDisp, null, enteredBy, now)
                }
                // Per-test progress.
                val testEmpty = resQ.countEmptyForTest(orderId, testId).executeAsOne()
                oQ.setOrderTestStatus(if (testEmpty == 0L) "entered" else "in_progress", orderId, testId)
                // Order-level walk.
                val orderEmpty = resQ.countEmptyForOrder(orderId).executeAsOne()
                when {
                    orderEmpty == 0L -> oQ.setStatus(LabStatus.ENTERED, now, orderId)
                    order.status == LabStatus.REGISTERED || order.status == LabStatus.COLLECTED ||
                        order.status == LabStatus.ENTERED ->
                        oQ.setStatus(LabStatus.IN_PROGRESS, now, orderId)
                }
                resQ.byKey(orderId, testId, paramKey).executeAsOne()
            }.toModel()
        }
    }

    suspend fun resultsForOrder(orderId: String): List<LabResult> = withContext(Dispatchers.Default) {
        resQ.resultsForOrder(orderId).executeAsList().map { it.toModel() }
    }

    /** Technologist sign-off: requires the order to be fully `entered`. */
    suspend fun verifyOrder(orderId: String, by: String): Result<Unit> = withContext(Dispatchers.Default) {
        runCatching {
            val order = oQ.byId(orderId).executeAsOneOrNull()?.toModel() ?: error("Order not found: $orderId")
            require(order.status == LabStatus.ENTERED) { "Only a fully-entered order can be verified (is ${order.status})" }
            val now = nowIso()
            db.transaction {
                resQ.markVerified(by, now, orderId)
                oQ.setStatus(LabStatus.VERIFIED, now, orderId)
            }
        }
    }

    /** Pathologist sign-off: requires `verified` + every result entered. */
    suspend fun approveOrder(orderId: String, by: String): Result<Unit> = withContext(Dispatchers.Default) {
        runCatching {
            val order = oQ.byId(orderId).executeAsOneOrNull()?.toModel() ?: error("Order not found: $orderId")
            require(order.status == LabStatus.VERIFIED) { "Only a verified order can be approved (is ${order.status})" }
            require(resQ.countEmptyForOrder(orderId).executeAsOne() == 0L) {
                "Cannot approve: some results are still empty"
            }
            val now = nowIso()
            db.transaction {
                resQ.markApproved(by, now, orderId)
                oQ.setStatus(LabStatus.APPROVED, now, orderId)
                oQ.stampApproved(now, orderId)
            }
        }
    }

    // ── EMR inbox (P3 bridge; rows are written by LabSyncEngine) ─────────────

    /** Unregistered clinic orders — drives the LabHome badge. */
    fun emrPendingCountFlow(): Flow<Long> =
        emrQ.countPending().asFlow().mapToOne(Dispatchers.Default).catch { emit(0L) }

    /** All open inbox rows, unregistered first (the inbox screen's list). */
    suspend fun emrOpen(): List<EmrInboxItem> = withContext(Dispatchers.Default) {
        emrQ.open().executeAsList().map { it.toModel() }
    }

    suspend fun emrById(id: String): EmrInboxItem? = withContext(Dispatchers.Default) {
        emrQ.byId(id).executeAsOneOrNull()?.toModel()
    }

    // ── Row → model mappers ──────────────────────────────────────────────────

    private fun Patients.toModel() = Patient(id, name, sex, dob, age_years, phone, address,
        created_at, updated_at, deleted_at)

    private fun Referrers.toModel() = Referrer(id, name, kind, phone, commission_pct, created_at, deleted_at)

    private fun Lab_tests.toModel() = LabTest(
        id = id, code = code, name = name, category = category, price = price,
        sampleType = sample_type, method = method, active = active == 1L,
        sortOrder = sort_order.toInt(),
        parameters = runCatching { json.decodeFromString(paramsSerializer, parameters_json) }.getOrDefault(emptyList()),
    )

    private fun Lab_panels.toModel() = LabPanel(
        id = id, code = code, name = name, price = price,
        testIds = runCatching { json.decodeFromString(idsSerializer, test_ids_json) }.getOrDefault(emptyList()),
        active = active == 1L,
    )

    private fun Lab_orders.toModel() = LabOrder(id, accession_no, patient_id, referrer_id, invoice_id,
        status, priority, notes, created_at, updated_at, collected_at, approved_at, reported_at)

    private fun Lab_order_tests.toModel() = LabOrderTest(id, order_id, test_id, test_name, price, status)

    private fun Lab_results.toModel() = LabResult(id, order_id, test_id, parameter_key, value_, unit,
        flag, ref_display, notes, entered_by, entered_at, verified_by, verified_at, approved_by, approved_at)

    private fun Emr_inbox.toModel() = EmrInboxItem(id, visit_id, test_name, instructions, status,
        lab_status, accession_no, matched_order_id, done == 1L, created_at)

    private fun WorklistByStatus.toEntry() = WorklistEntry(
        order = LabOrder(id, accession_no, patient_id, referrer_id, invoice_id, status, priority,
            notes, created_at, updated_at, collected_at, approved_at, reported_at),
        patientName = patient_name,
        patientSex = patient_sex,
        patientDob = patient_dob,
        patientAgeYears = patient_age_years,
        testCount = test_count,
    )

    private fun OpenOrders.toEntry() = WorklistEntry(
        order = LabOrder(id, accession_no, patient_id, referrer_id, invoice_id, status, priority,
            notes, created_at, updated_at, collected_at, approved_at, reported_at),
        patientName = patient_name,
        patientSex = patient_sex,
        patientDob = patient_dob,
        patientAgeYears = patient_age_years,
        testCount = test_count,
    )

    companion object {
        private val ENTRY_OPEN_STATUSES = setOf(
            LabStatus.REGISTERED, LabStatus.COLLECTED, LabStatus.IN_PROGRESS, LabStatus.ENTERED,
        )

        /** Age in (fractional) years: dob preferred, age_years fallback, else
         *  null — and a null age matches only ranges with no age bounds. */
        fun resolveAgeYears(dob: String?, ageYears: Long?): Double? {
            if (!dob.isNullOrBlank()) {
                val birth = runCatching { LocalDate.parse(dob.take(10)) }.getOrNull()
                if (birth != null) {
                    val today = kotlin.time.Clock.System.now()
                        .toLocalDateTime(TimeZone.currentSystemDefault()).date
                    return birth.daysUntil(today) / 365.25
                }
            }
            return ageYears?.toDouble()
        }

        /** Most specific matching range wins (sex-split beats generic, age-banded
         *  beats open); no match → null (value stays unflagged). */
        fun pickRange(ranges: List<RefRange>, sex: String?, ageYears: Double?): RefRange? {
            val candidates = ranges.filter { r ->
                val sexOk = r.sex == null || (sex != null && r.sex.equals(sex, ignoreCase = true))
                val ageOk =
                    if (r.ageMinY == null && r.ageMaxY == null) true
                    else ageYears != null &&
                        (r.ageMinY == null || ageYears >= r.ageMinY) &&
                        (r.ageMaxY == null || ageYears <= r.ageMaxY)
                sexOk && ageOk
            }
            return candidates.maxByOrNull { r ->
                (if (r.sex != null) 2 else 0) + (if (r.ageMinY != null || r.ageMaxY != null) 1 else 0)
            }
        }

        /** N/L/H/CL/CH for numerics (criticals win), N/A for qualitative,
         *  null when there's no range to judge against. */
        fun computeFlag(value: String, range: RefRange?): String? {
            if (range == null) return null
            range.text?.let { expected ->
                return if (value.trim().equals(expected.trim(), ignoreCase = true)) "N" else "A"
            }
            val v = value.trim().toDoubleOrNull() ?: return null
            return when {
                range.criticalLow != null && v < range.criticalLow -> "CL"
                range.criticalHigh != null && v > range.criticalHigh -> "CH"
                range.low != null && v < range.low -> "L"
                range.high != null && v > range.high -> "H"
                else -> "N"
            }
        }

        /** The range exactly as the report prints it. */
        fun refDisplay(range: RefRange?, decimals: Int): String? = when {
            range == null -> null
            range.text != null -> range.text
            range.low != null && range.high != null -> "${fmt(range.low, decimals)} - ${fmt(range.high, decimals)}"
            range.high != null -> "< ${fmt(range.high, decimals)}"
            range.low != null -> "> ${fmt(range.low, decimals)}"
            else -> null
        }

        private fun fmt(v: Double, decimals: Int): String {
            if (decimals <= 0) return v.roundToLong().toString()
            var f = 1.0
            repeat(decimals) { f *= 10 }
            val r = round(v * f) / f
            return if (r == floor(r)) r.toLong().toString() else r.toString()
        }
    }
}

private fun nowIso(): String = kotlin.time.Clock.System.now().toString()

private fun todayLocalDate(): String = kotlin.time.Clock.System.now()
    .toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()
