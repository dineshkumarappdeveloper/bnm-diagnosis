package com.bnm.diagnosis.sync

import com.bnm.diagnosis.api.LabApi
import com.bnm.diagnosis.api.LabSyncDisabledException
import com.bnm.diagnosis.api.LabSyncPullRow
import com.bnm.diagnosis.api.LabSyncPushRow
import com.bnm.diagnosis.db.AppDatabase
import com.bnm.diagnosis.db.Lab_orders
import com.bnm.diagnosis.db.Lab_results
import com.bnm.diagnosis.lab.LabOrder
import com.bnm.diagnosis.lab.LabOrderTest
import com.bnm.diagnosis.lab.LabPanel
import com.bnm.diagnosis.lab.LabResult
import com.bnm.diagnosis.lab.LabStatus
import com.bnm.diagnosis.lab.LabTest
import com.bnm.diagnosis.lab.Patient
import com.bnm.diagnosis.lab.Referrer
import com.bnm.diagnosis.lab.TestParameter
import com.bnm.diagnosis.license.LicenseManager
import com.bnm.diagnosis.staff.Staff
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** An order doc as pushed to `lab_entities` (entity='order'): the order row
 *  plus its embedded test lines (they always travel together). */
@Serializable
data class LabOrderDoc(val order: LabOrder, val tests: List<LabOrderTest> = emptyList())

/** UI snapshot of the sync spine (Settings card + LabHome note). */
data class LabSyncState(
    val syncing: Boolean = false,
    val lastSyncAt: String? = null,
    /** Standalone license — server said 409 no_business; sync is off, silently. */
    val disabled: Boolean = false,
    val lastError: String? = null,
)

/**
 * P3 sync engine against the live `admin-lab` sync endpoints. ADDITIVE by
 * design: the app is the system of record and never requires network —
 * every phase fails silent (logged) and simply retries on the next trigger
 * (app start / reconnect / periodic / manual "Sync now").
 *
 * UPSTREAM  — watermark sweeps: push every row whose newest local stamp is
 *             past the persisted per-entity `last_push_at` (idempotent
 *             upserts; a 2s slack window absorbs ISO-precision compare skew).
 *             Tests/panels carry no stamps → pushed when a whole-catalog
 *             fingerprint changes.
 * DOWNSTREAM — `sync/pull?sinceSeq=` cursor; apply per-entity LAST-WRITER-WINS
 *             on the same stamps; a local row with verified/approved stamps is
 *             NEVER overwritten by one without them.
 * EMR INBOX  — `emr-orders?sinceSeq=` cursor into the local `emr_inbox` table;
 *             registration acknowledges in_progress+accession; an APPROVED
 *             matched order pushes its result back and marks the row done.
 *
 * A standalone license (no BNM business) gets 409 no_business → persisted
 * "disabled" flag (one-line LabHome note), no retries within the run; each
 * new trigger re-probes once so a later business link self-heals.
 */
class LabSyncEngine(
    private val db: AppDatabase,
    private val json: Json,
    private val api: LabApi,
    private val license: LicenseManager,
    private val prefs: SyncPrefs = SyncPrefs(),
) {
    private val mutex = Mutex()

    private val _state = MutableStateFlow(
        LabSyncState(lastSyncAt = prefs.lastSyncAt, disabled = prefs.syncDisabled)
    )
    val state: StateFlow<LabSyncState> = _state.asStateFlow()

    private val pQ get() = db.patientsQueries
    private val rQ get() = db.referrersQueries
    private val tQ get() = db.testCatalogQueries
    private val oQ get() = db.labOrdersQueries
    private val resQ get() = db.resultsQueries
    private val emrQ get() = db.emrInboxQueries
    private val stQ get() = db.staffQueries

    private val paramsSerializer = ListSerializer(TestParameter.serializer())
    private val idsSerializer = ListSerializer(String.serializer())
    private val resultsSerializer = ListSerializer(LabResult.serializer())

    /**
     * One full sweep: push → pull → EMR. Never throws; never blocks UI (call
     * from a coroutine). Serialized behind a mutex so overlapping triggers
     * (reconnect + periodic) queue instead of stampeding.
     */
    suspend fun syncNow() {
        if (license.deviceToken() == null) return // not activated — nothing to talk to
        mutex.withLock {
            _state.value = _state.value.copy(syncing = true, lastError = null)
            try {
                withContext(Dispatchers.Default) {
                    pushAll()
                    pullAll()
                    syncEmr()
                }
                if (prefs.syncDisabled) prefs.syncDisabled = false
                prefs.lastSyncAt = nowIso()
                _state.value = LabSyncState(syncing = false, lastSyncAt = prefs.lastSyncAt)
            } catch (e: LabSyncDisabledException) {
                // Standalone license: silent, no retries this run, note-only UI.
                prefs.syncDisabled = true
                _state.value = _state.value.copy(syncing = false, disabled = true)
            } catch (e: Throwable) {
                println("[LabSync] sweep failed (will retry next trigger): ${e.message}")
                _state.value = _state.value.copy(syncing = false, lastError = e.message)
            }
        }
    }

    // ── UPSTREAM ─────────────────────────────────────────────────────────────

    private class PushCandidate(val id: String, val doc: JsonElement, val deletedAt: String?, val stampMs: Long)

    private suspend fun pushAll() {
        pushEntity(E_PATIENT) { since ->
            pQ.changedSince(since).executeAsList().map { row ->
                val m = Patient(row.id, row.name, row.sex, row.dob, row.age_years, row.phone,
                    row.address, row.created_at, row.updated_at, row.deleted_at)
                PushCandidate(m.id, json.encodeToJsonElement(Patient.serializer(), m), m.deletedAt,
                    maxOf(ms(m.updatedAt), ms(m.createdAt), ms(m.deletedAt)))
            }
        }
        pushEntity(E_REFERRER) { since ->
            rQ.changedSince(since).executeAsList().map { row ->
                val m = Referrer(row.id, row.name, row.kind, row.phone, row.commission_pct,
                    row.created_at, row.deleted_at)
                PushCandidate(m.id, json.encodeToJsonElement(Referrer.serializer(), m), m.deletedAt,
                    maxOf(ms(m.createdAt), ms(m.deletedAt)))
            }
        }
        // Staff (P4): roles + PINs converge across the lab's seats. pin_hash IS
        // pushed — deliberately. Same lab, same people: a PIN set at the front
        // desk has to work on the pathologist's laptop, and the salt is embedded
        // in the hash for exactly that reason (StaffRepository KDoc). It is also
        // no meaningful disclosure: a PIN here is convenience access control on a
        // shared lab PC, not a secret, and the local DB it already sits in is
        // plainly readable. Nothing here is reused as a credential anywhere else.
        pushEntity(E_STAFF) { since ->
            stQ.changedSince(since).executeAsList().map { row ->
                val m = row.toStaff()
                PushCandidate(m.id, json.encodeToJsonElement(Staff.serializer(), m), m.deletedAt,
                    maxOf(ms(m.updatedAt), ms(m.createdAt), ms(m.deletedAt)))
            }
        }
        pushCatalog()
        pushEntity(E_ORDER) { since ->
            oQ.changedSince(since).executeAsList().map { row ->
                val order = row.toOrder()
                val tests = oQ.testsForOrder(order.id).executeAsList().map {
                    LabOrderTest(it.id, it.order_id, it.test_id, it.test_name, it.price, it.status)
                }
                PushCandidate(order.id,
                    json.encodeToJsonElement(LabOrderDoc.serializer(), LabOrderDoc(order, tests)),
                    null, maxOf(ms(order.updatedAt), ms(order.createdAt)))
            }
        }
        pushEntity(E_RESULT_BUNDLE) { since ->
            resQ.changedResultOrders(since).executeAsList().map { orderId ->
                val results = resQ.resultsForOrder(orderId).executeAsList().map { it.toResult() }
                PushCandidate(orderId,
                    json.encodeToJsonElement(resultsSerializer, results),
                    null, results.maxOfOrNull { it.stampMs() } ?: 0L)
            }
        }
    }

    /** Watermark sweep for one entity; advances the watermark only after every
     *  batch of that entity landed (batches ≤[PUSH_BATCH], server cap 500). */
    private suspend fun pushEntity(entity: String, collect: (sinceIso: String) -> List<PushCandidate>) {
        val watermark = prefs.lastPushAt(entity)
        // 2s slack: ISO strings from Instant.toString() vary in sub-second
        // precision, so the SQL string compare can mis-order within a second —
        // re-pushing a couple of rows is free (idempotent upserts), missing one
        // is not.
        val rows = collect(isoOf(watermark - STAMP_SLACK_MS))
        if (rows.isEmpty()) return
        rows.chunked(PUSH_BATCH).forEach { chunk ->
            api.syncPush(chunk.map { LabSyncPushRow(entity, it.id, it.doc, it.deletedAt) }).getOrThrow()
        }
        prefs.setLastPushAt(entity, rows.maxOf { it.stampMs }.coerceAtLeast(watermark))
    }

    /** Tests/panels have NO timestamps: push the whole (small) catalog whenever
     *  its content fingerprint changes — otherwise every sweep would churn seq
     *  numbers and ping-pong the catalog between seats forever. */
    private suspend fun pushCatalog() {
        val tests = tQ.listAllTests().executeAsList().map { it.toTestModel() }
        val panels = tQ.listAllPanels().executeAsList().map {
            LabPanel(it.id, it.code, it.name, it.price,
                runCatching { json.decodeFromString(idsSerializer, it.test_ids_json) }.getOrDefault(emptyList()),
                it.active == 1L)
        }
        val fp = catalogFingerprint(tests, panels)
        if (fp == prefs.catalogFingerprint) return
        val rows =
            tests.map { LabSyncPushRow(E_TEST, it.id, json.encodeToJsonElement(LabTest.serializer(), it)) } +
            panels.map { LabSyncPushRow(E_PANEL, it.id, json.encodeToJsonElement(LabPanel.serializer(), it)) }
        rows.chunked(PUSH_BATCH).forEach { chunk -> api.syncPush(chunk).getOrThrow() }
        prefs.catalogFingerprint = fp
    }

    // ── DOWNSTREAM ───────────────────────────────────────────────────────────

    private suspend fun pullAll() {
        var cursor = prefs.pullCursor
        var appliedCatalog = false
        while (true) {
            val rows = api.syncPull(cursor, PULL_PAGE).getOrThrow()
            if (rows.isEmpty()) break
            for (row in rows) {
                // Malformed rows are skipped, never fatal — the cursor still advances.
                runCatching { applyRow(row) }
                    .onFailure { println("[LabSync] skip ${row.entity}/${row.id}: ${it.message}") }
                if (row.entity == E_TEST || row.entity == E_PANEL) appliedCatalog = true
                if (row.seq > cursor) cursor = row.seq
            }
            prefs.pullCursor = cursor
            if (rows.size < PULL_PAGE) break
        }
        // Applying remote catalog rows changes the local fingerprint — refresh
        // it so the next push sweep doesn't bounce the same content back.
        if (appliedCatalog) {
            val tests = tQ.listAllTests().executeAsList().map { it.toTestModel() }
            val panels = tQ.listAllPanels().executeAsList().map {
                LabPanel(it.id, it.code, it.name, it.price,
                    runCatching { json.decodeFromString(idsSerializer, it.test_ids_json) }.getOrDefault(emptyList()),
                    it.active == 1L)
            }
            prefs.catalogFingerprint = catalogFingerprint(tests, panels)
        }
    }

    private fun applyRow(row: LabSyncPullRow) {
        val doc = row.json ?: return
        when (row.entity) {
            E_PATIENT -> {
                val p = json.decodeFromJsonElement(Patient.serializer(), doc)
                val local = pQ.byId(p.id).executeAsOneOrNull()
                val incoming = maxOf(ms(p.updatedAt), ms(p.deletedAt ?: row.deletedAt))
                if (local != null && maxOf(ms(local.updated_at), ms(local.deleted_at)) >= incoming) return
                pQ.upsert(p.id, p.name, p.sex, p.dob, p.ageYears, p.phone, p.address,
                    p.createdAt, p.updatedAt, p.deletedAt ?: row.deletedAt)
            }
            E_REFERRER -> {
                val r = json.decodeFromJsonElement(Referrer.serializer(), doc)
                val local = rQ.byId(r.id).executeAsOneOrNull()
                val incoming = maxOf(ms(r.createdAt), ms(r.deletedAt ?: row.deletedAt))
                if (local != null && maxOf(ms(local.created_at), ms(local.deleted_at)) >= incoming) return
                rQ.upsert(r.id, r.name, r.kind, r.phone, r.commissionPct,
                    r.createdAt, r.deletedAt ?: row.deletedAt)
            }
            E_STAFF -> {
                val st = json.decodeFromJsonElement(Staff.serializer(), doc)
                val local = stQ.byId(st.id).executeAsOneOrNull()
                val incoming = maxOf(ms(st.updatedAt), ms(st.deletedAt ?: row.deletedAt))
                if (local != null && maxOf(ms(local.updated_at), ms(local.deleted_at)) >= incoming) return
                stQ.upsert(st.id, st.name, st.role, st.pinHash, if (st.active) 1L else 0L,
                    st.createdAt, st.updatedAt, st.deletedAt ?: row.deletedAt)
            }
            // Catalog rows carry no stamps — server seq order IS the LWW order.
            E_TEST -> {
                val t = json.decodeFromJsonElement(LabTest.serializer(), doc)
                tQ.upsertTest(t.id, t.code, t.name, t.category, t.price, t.sampleType, t.method,
                    if (t.active) 1L else 0L, t.sortOrder.toLong(),
                    json.encodeToString(paramsSerializer, t.parameters))
            }
            E_PANEL -> {
                val p = json.decodeFromJsonElement(LabPanel.serializer(), doc)
                tQ.upsertPanel(p.id, p.code, p.name, p.price,
                    json.encodeToString(idsSerializer, p.testIds), if (p.active) 1L else 0L)
            }
            E_ORDER -> {
                val d = json.decodeFromJsonElement(LabOrderDoc.serializer(), doc)
                val o = d.order
                val local = oQ.byId(o.id).executeAsOneOrNull()
                if (local != null) {
                    if (ms(local.updated_at) >= ms(o.updatedAt)) return
                    // Never let a not-yet-approved copy roll back a local approval.
                    if (local.approved_at != null && o.approvedAt == null) return
                }
                db.transaction {
                    oQ.upsertOrder(o.id, o.accessionNo, o.patientId, o.referrerId, o.invoiceId,
                        o.status, o.priority, o.notes, o.createdAt, o.updatedAt,
                        o.collectedAt, o.approvedAt, o.reportedAt)
                    oQ.deleteTestsForOrder(o.id)
                    for (t in d.tests) {
                        oQ.insertOrderTest(t.id, o.id, t.testId, t.testName, t.price, t.status)
                        // Pre-create the empty entry grid from the local catalog
                        // (INSERT OR IGNORE — never clobbers entered values).
                        val params = tQ.testById(t.testId).executeAsOneOrNull()?.toTestModel()?.parameters
                        params?.forEach { p ->
                            resQ.insertEmpty(uuid4(), o.id, t.testId, p.key, p.unit)
                        }
                    }
                }
            }
            E_RESULT_BUNDLE -> {
                val incoming = json.decodeFromJsonElement(resultsSerializer, doc)
                db.transaction {
                    for (res in incoming) {
                        val local = resQ.byKey(res.orderId, res.testId, res.parameterKey)
                            .executeAsOneOrNull()
                        if (local != null) {
                            val localRes = local.toResult()
                            if (localRes.stampMs() >= res.stampMs()) continue
                            // NEVER downgrade a verified/approved local row to an
                            // incoming copy that lacks those stamps.
                            if (localRes.verifiedAt != null && res.verifiedAt == null) continue
                            if (localRes.approvedAt != null && res.approvedAt == null) continue
                        }
                        resQ.upsertFull(res.id, res.orderId, res.testId, res.parameterKey,
                            res.value, res.unit, res.flag, res.refDisplay, res.notes,
                            res.enteredBy, res.enteredAt, res.verifiedBy, res.verifiedAt,
                            res.approvedBy, res.approvedAt)
                    }
                }
            }
        }
    }

    // ── EMR INBOX ────────────────────────────────────────────────────────────

    private suspend fun syncEmr() {
        // 1) Pull the clinic-order delta into the local inbox (server fields
        //    only — matched_order_id / status_pushed / done stay local).
        var cursor = prefs.emrCursor
        val orders = api.emrOrders(cursor).getOrThrow()
        for (o in orders) {
            val name = o.testName?.takeIf { it.isNotBlank() } ?: "Lab test"
            val doneNow = o.resultedAt != null || o.labStatus == "reported" || o.status == "cancelled"
            val existing = emrQ.byId(o.id).executeAsOneOrNull()
            // Identity block (P3b) is additive and every field is nullable: a
            // server that hasn't shipped it sends nothing, and updateFromServer
            // COALESCEs so null never wipes what an earlier sweep landed.
            if (existing == null) {
                emrQ.insert(o.id, o.visitId, name, o.instructions, o.status, o.labStatus,
                    o.accessionNo, o.seq, if (doneNow) 1L else 0L, o.createdAt,
                    o.testCode, o.visitNumber, o.patientName, o.patientPhone,
                    o.patientSex, o.patientDob)
            } else {
                emrQ.updateFromServer(o.visitId, name, o.instructions, o.status, o.labStatus,
                    o.accessionNo ?: existing.accession_no, o.seq,
                    if (doneNow) 1L else existing.done,
                    o.testCode, o.visitNumber, o.patientName, o.patientPhone,
                    o.patientSex, o.patientDob, o.id)
            }
            // Multi-seat: ANOTHER seat may have registered this row (its ack put
            // the accession on the server copy). Re-match it against the local
            // order (which converges via pull) so it doesn't show as pending here.
            if (!doneNow && existing?.matched_order_id == null) {
                val acc = o.accessionNo ?: existing?.accession_no
                if (acc != null && o.labStatus == "in_progress") {
                    oQ.byAccession(acc).executeAsOneOrNull()?.let { local ->
                        emrQ.setMatched(local.id, acc, o.id)
                        emrQ.markStatusPushed(o.id) // the clinic already knows
                    }
                }
            }
            if (o.seq > cursor) cursor = o.seq
        }
        prefs.emrCursor = cursor

        // 2) Acknowledge registrations the clinic hasn't heard about yet
        //    (registration itself fires this too — this is the offline catch-up).
        for (row in emrQ.matchedUnpushed().executeAsList()) {
            api.emrOrderStatus(row.id, "in_progress", row.accession_no)
                .onSuccess { emrQ.markStatusPushed(row.id) }
        }

        // 3) Push results for matched rows whose local order is APPROVED+.
        for (row in emrQ.matchedNotDone().executeAsList()) {
            val orderId = row.matched_order_id // non-null: query filters IS NOT NULL
            val order = oQ.byId(orderId).executeAsOneOrNull() ?: continue
            if (order.status !in APPROVED_PLUS) continue
            val entered = resQ.resultsForOrder(orderId).executeAsList()
                .map { it.toResult() }.filter { it.isEntered }
            if (entered.isEmpty()) continue
            val ok = if (entered.size == 1) {
                // Single-analyte convention: the one parameter travels verbatim.
                val r = entered.first()
                api.emrOrderResult(row.id,
                    resultValue = r.value.orEmpty(),
                    resultUnit = r.unit,
                    referenceRange = r.refDisplay,
                    resultFlag = r.flag, // app code — server translates
                    resultNotes = r.notes,
                ).isSuccess
            } else {
                // Multi-parameter: value = "see report", per-line breakdown in notes.
                api.emrOrderResult(row.id,
                    resultValue = "see report",
                    resultFlag = null,
                    resultNotes = entered.joinToString("\n") { r ->
                        buildString {
                            append(paramName(r.testId, r.parameterKey)).append(": ").append(r.value)
                            r.unit?.takeIf { it.isNotBlank() }?.let { append(' ').append(it) }
                            r.flag?.takeIf { it != "N" }?.let { append(" (").append(it).append(')') }
                        }
                    },
                ).isSuccess
            }
            if (ok) emrQ.markDone(row.id)
        }
    }

    /**
     * Called right after NewOrderScreen registers a local order for an EMR
     * inbox row: store the match, then best-effort acknowledge the clinic
     * (offline → the next sweep's catch-up sends it).
     */
    suspend fun onEmrOrderRegistered(emrId: String, order: LabOrder) =
        // NonCancellable: the caller pops the screen right after registering —
        // the local match must land (the sweep's catch-up depends on it).
        withContext(Dispatchers.Default + NonCancellable) {
            runCatching {
                emrQ.setMatched(order.id, order.accessionNo, emrId)
                api.emrOrderStatus(emrId, "in_progress", order.accessionNo)
                    .onSuccess { emrQ.markStatusPushed(emrId) }
            }
            Unit
        }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private val paramNameCache = HashMap<String, Map<String, String>>()

    private fun paramName(testId: String, key: String): String {
        val names = paramNameCache.getOrPut(testId) {
            tQ.testById(testId).executeAsOneOrNull()?.toTestModel()
                ?.parameters?.associate { it.key to it.name } ?: emptyMap()
        }
        return names[key] ?: key
    }

    private fun com.bnm.diagnosis.db.Lab_tests.toTestModel() = LabTest(
        id = id, code = code, name = name, category = category, price = price,
        sampleType = sample_type, method = method, active = active == 1L,
        sortOrder = sort_order.toInt(),
        parameters = runCatching { json.decodeFromString(paramsSerializer, parameters_json) }
            .getOrDefault(emptyList()),
    )

    private fun com.bnm.diagnosis.db.Staff.toStaff() = Staff(
        id = id, name = name, role = role, pinHash = pin_hash, active = active == 1L,
        createdAt = created_at, updatedAt = updated_at, deletedAt = deleted_at,
    )

    private fun Lab_orders.toOrder() = LabOrder(id, accession_no, patient_id, referrer_id,
        invoice_id, status, priority, notes, created_at, updated_at, collected_at,
        approved_at, reported_at)

    private fun Lab_results.toResult() = LabResult(id, order_id, test_id, parameter_key,
        value_, unit, flag, ref_display, notes, entered_by, entered_at, verified_by,
        verified_at, approved_by, approved_at)

    private fun LabResult.stampMs(): Long = maxOf(ms(enteredAt), ms(verifiedAt), ms(approvedAt))

    private fun catalogFingerprint(tests: List<LabTest>, panels: List<LabPanel>): Long {
        val s = json.encodeToString(ListSerializer(LabTest.serializer()), tests.sortedBy { it.id }) +
            json.encodeToString(ListSerializer(LabPanel.serializer()), panels.sortedBy { it.id })
        var h = 1125899906842597L // FNV-ish
        for (c in s) h = 31 * h + c.code
        return h
    }

    private fun uuid4(): String {
        val b = ByteArray(16).also { kotlin.random.Random.nextBytes(it) }
        b[6] = ((b[6].toInt() and 0x0F) or 0x40).toByte()
        b[8] = ((b[8].toInt() and 0x3F) or 0x80).toByte()
        val hex = b.joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
            "${hex.substring(16, 20)}-${hex.substring(20)}"
    }

    private companion object {
        const val E_PATIENT = "patient"
        const val E_REFERRER = "referrer"
        const val E_STAFF = "staff"
        const val E_TEST = "test"
        const val E_PANEL = "panel"
        const val E_ORDER = "order"
        const val E_RESULT_BUNDLE = "result_bundle"

        const val PUSH_BATCH = 400   // server cap 500
        const val PULL_PAGE = 500
        const val STAMP_SLACK_MS = 2_000L

        val APPROVED_PLUS = setOf(LabStatus.APPROVED, LabStatus.REPORTED, LabStatus.DELIVERED)

        fun ms(iso: String?): Long =
            if (iso.isNullOrBlank()) 0L
            else runCatching { Instant.parse(iso).toEpochMilliseconds() }.getOrDefault(0L)

        fun isoOf(epochMs: Long): String =
            Instant.fromEpochMilliseconds(epochMs.coerceAtLeast(0L)).toString()

        fun nowIso(): String = kotlin.time.Clock.System.now().toString()
    }
}
