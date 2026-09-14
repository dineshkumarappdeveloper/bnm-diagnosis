package com.bnm.lab.chat

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.bnm.lab.api.ApiClient
import com.bnm.lab.api.BillingApi
import com.bnm.lab.api.models.BillingCounter
import com.bnm.lab.api.models.Invoice
import com.bnm.lab.api.models.InvoiceCreateRequest
import com.bnm.lab.api.models.InvoiceSettings
import com.bnm.lab.api.models.Product
import com.bnm.lab.api.models.TaxRate
import com.bnm.lab.billing.BillingScope
import com.bnm.lab.billing.GstLine
import com.bnm.lab.billing.GstTaxEngine
import com.bnm.lab.db.AppDatabase
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Offline-first store for the Billing app. Same engine as BNMAdmin's commerce
 * repo: a server `seq` cursor delta into a generic SQLDelight document table
 * (`ecom_entity`) + reactive Flow reads. Invoices/settings/tax-rates/counters
 * come from `admin-billing`; customer names from `admin-orders`.
 */
class BillingRepository(
    private val db: AppDatabase,
    private val api: BillingApi,
) {
    private val json = ApiClient.json
    private val q get() = db.ecommerceQueries
    private val syncQ get() = db.syncStateQueries
    private val seriesQ get() = db.counterSeriesQueries
    private val outboxQ get() = db.billingOutboxQueries

    // ── Reactive reads (instant, offline) ───────────────────────────────────
    fun invoicesFlow(businessId: String): Flow<List<Invoice>> = entityFlow(INVOICE, businessId)
    fun taxRatesFlow(businessId: String): Flow<List<TaxRate>> = entityFlow(TAX_RATE, businessId)
    fun countersFlow(businessId: String): Flow<List<BillingCounter>> = entityFlow(COUNTER, businessId)
    fun productsFlow(businessId: String): Flow<List<Product>> = entityFlow(PRODUCT, businessId)

    /** The single invoice-settings row (offline; stored keyed by businessId). */
    fun invoiceSettingsFlow(businessId: String): Flow<InvoiceSettings?> =
        q.selectById(INVOICE_SETTING, businessId).asFlow().mapToOneOrNull(Dispatchers.Default)
            .map { raw -> raw?.let { runCatching { json.decodeFromString<InvoiceSettings>(it) }.getOrNull() } }
            .catch { emit(null) }

    /**
     * Invoices with the money actually collected against each of them.
     *
     * Two sources, deliberately kept apart:
     *  • `paid_amount` inside the synced server row — the authority, maintained
     *    by the invoice-payment rollup trigger.
     *  • tenders still sitting in the outbox — money taken on this device that
     *    the server hasn't seen yet.
     * Summing them is safe in both directions: a queued tender leaves the outbox
     * only when the server has counted it, and the drain writes the server row
     * back verbatim in the same breath. Nothing is ever written into the local
     * invoice doc, because sync replaces that doc wholesale.
     */
    fun invoiceBalancesFlow(businessId: String): Flow<List<InvoiceBalance>> =
        combine(
            // The lab's own bills plus the offline archive: a lab that moved to
            // the connected edition keeps seeing the bills it issued before.
            q.selectEntityIn(INVOICE, BillingScope.readScopes(businessId)).asFlow().mapToList(Dispatchers.Default),
            outboxQ.pendingPayments().asFlow().mapToList(Dispatchers.Default),
        ) { rows, queued ->
            // Each queued tender keeps its own method: a ₹300 cash advance and a
            // ₹700 UPI balance are two different drawers, not one bill-level mode.
            val queuedByInvoice = HashMap<String, MutableMap<String?, Double>>()
            for (p in queued) {
                val req = runCatching {
                    json.decodeFromString(InvoicePaymentRequest.serializer(), p.payload)
                }.getOrNull() ?: continue
                val byMethod = queuedByInvoice.getOrPut(p.aggregate_id) { LinkedHashMap() }
                byMethod[req.paymentMethod] = (byMethod[req.paymentMethod] ?: 0.0) + req.amount
            }
            rows.mapNotNull { row -> parseBalance(row.json, row.business_id, queuedByInvoice) }
        }.catch { emit(emptyList()) }

    /** One invoice's balance (bill detail). Same brain as the list. */
    fun invoiceBalanceFlow(businessId: String, invoiceId: String): Flow<InvoiceBalance?> =
        invoiceBalancesFlow(businessId).map { list -> list.firstOrNull { it.invoice.id == invoiceId } }

    private fun parseBalance(raw: String, filedUnder: String, queued: Map<String, Map<String?, Double>>): InvoiceBalance? {
        val o = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val inv = runCatching { json.decodeFromJsonElement(Invoice.serializer(), o) }.getOrNull() ?: return null
        val byMethod = queued[inv.id].orEmpty()
        return InvoiceBalance(
            invoice = inv,
            paidAmount = paidAmountOf(inv.status, inv.total, o["paid_amount"]),
            queuedAmount = byMethod.values.sum(),
            queuedByMethod = byMethod,
            offlineArchive = BillingScope.isOffline(filedUnder),
        )
    }

    /**
     * One-time repair for offline installs that filed bills under the business id
     * "null" (see LicenseManager.claimString). Bills and their queued tenders move
     * to the offline archive, keeping their numbers; the old series is parked so
     * new bills get a per-device series. Idempotent — nothing matches once done.
     * @return how many bills moved.
     */
    suspend fun adoptNullBusinessBills(): Long = withContext(Dispatchers.Default) {
        var moved = 0L
        db.transaction {
            moved = q.countEntity(INVOICE, "null").executeAsOne()
            q.rekeyNullBusinessEntities(BillingScope.OFFLINE_BUSINESS_ID)
            outboxQ.rekeyNullBusinessOutbox(BillingScope.OFFLINE_BUSINESS_ID)
            seriesQ.parkNullBusinessSeries(BillingScope.PARKED_SERIES_KEY)
        }
        moved
    }

    /** The highest number this device printed under [series] in [fy] while it had no business. */
    suspend fun maxHighWater(series: String, fy: String): Long = withContext(Dispatchers.Default) {
        seriesQ.maxHighWaterFor(series, fy, listOf(BillingScope.OFFLINE_BUSINESS_ID, BillingScope.PARKED_SERIES_KEY, "null"))
            .executeAsOne()
    }

    suspend fun invoiceById(id: String): Invoice? = withContext(Dispatchers.Default) {
        q.selectById(INVOICE, id).executeAsOneOrNull()
            ?.let { runCatching { json.decodeFromString<Invoice>(it) }.getOrNull() }
    }

    /** Dashboard: invoices created today — a cheap count over the extracted
     *  created_at column of the local doc store (no JSON parsing). */
    suspend fun countInvoicesToday(businessId: String): Long = withContext(Dispatchers.Default) {
        val today = kotlin.time.Clock.System.now()
            .toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()
        q.countEntityToday(INVOICE, businessId, today).executeAsOne()
    }

    /** productId → category NAME for the given ids (offline; one pass over the
     *  synced catalog). Used to bucket a bill's lines into collection stations. */
    suspend fun categoriesFor(businessId: String, productIds: Collection<String>): Map<String, String> =
        withContext(Dispatchers.Default) {
            val wanted = productIds.filterTo(HashSet()) { it.isNotBlank() }
            if (wanted.isEmpty()) return@withContext emptyMap()
            val out = HashMap<String, String>()
            for (raw in q.selectEntity(PRODUCT, businessId).executeAsList()) {
                val p = runCatching { json.decodeFromString<Product>(raw) }.getOrNull() ?: continue
                if (p.id in wanted) p.category?.takeIf { it.isNotBlank() }?.let { out[p.id] = it }
                if (out.size == wanted.size) break
            }
            out
        }

    fun customerNamesFlow(businessId: String): Flow<Map<String, String>> =
        q.selectEntity(CUSTOMER, businessId).asFlow().mapToList(Dispatchers.Default)
            .map { rows ->
                buildMap {
                    for (raw in rows) {
                        val e = runCatching { json.decodeFromString<CustomerDirectoryEntry>(raw) }.getOrNull() ?: continue
                        val name = e.displayName?.takeIf { it.isNotBlank() } ?: continue
                        e.customerId?.takeIf { it.isNotBlank() }?.let { put("uuid:$it", name) }
                        normalizePhone(e.phone)?.let { put("phone:$it", name) }
                    }
                }
            }
            .catch { emit(emptyMap()) }

    private inline fun <reified T> entityFlow(entity: String, businessId: String): Flow<List<T>> =
        q.selectEntity(entity, businessId).asFlow().mapToList(Dispatchers.Default)
            .map { rows -> rows.mapNotNull { runCatching { json.decodeFromString<T>(it) }.getOrNull() } }
            .catch { emit(emptyList()) }

    fun lastSyncedFlow(entity: String, businessId: String): Flow<String?> =
        syncQ.getState(cursorKey(entity, businessId)).asFlow().mapToOneOrNull(Dispatchers.Default)
            .map { it?.last_synced_at }

    // ── Local optimistic write (offline create — Phase 5 outbox uses this) ───
    /** Upsert a single entity row into the local store, e.g. an optimistic invoice. */
    suspend fun upsertLocal(entity: String, id: String, businessId: String, jsonStr: String, createdAt: String? = null) =
        withContext(Dispatchers.Default) {
            q.upsertEntity(entity, id, businessId, 0L, createdAt ?: nowIso(), jsonStr)
        }

    // ── Numbering series (per-device, offline) ──────────────────────────────
    /** Bind this device to a numbering series (after the server register succeeds). */
    suspend fun registerSeriesLocal(businessId: String, series: String, fy: String, prefix: String, numberFormat: String, highWater: Long) =
        withContext(Dispatchers.Default) {
            // Never rewind: keep the higher of the existing local high_water and the
            // incoming (server) one, so a re-pair can't restart numbering at 0 and
            // reissue an already-used invoice number.
            val existing = seriesQ.getSeries(businessId, series, fy).executeAsOneOrNull()?.high_water ?: 0L
            seriesQ.upsertSeries(businessId, series, fy, maxOf(existing, highWater), prefix, numberFormat)
            // Bind this device to the just-paired series. If it had previously issued
            // under another counter (e.g. re-paired C1 → C2), drop the stale series so
            // deviceSeries can't keep numbering under the old code.
            seriesQ.deleteOtherSeries(businessId, series)
        }

    /** The series this device issues under (its single bound counter), for the current FY. */
    suspend fun deviceSeries(businessId: String): SeriesInfo? = withContext(Dispatchers.Default) {
        val rows = seriesQ.allSeries(businessId).executeAsList()
        val fy = currentFy()
        (rows.firstOrNull { it.fy == fy } ?: rows.firstOrNull())
            ?.let { SeriesInfo(it.series, it.fy, it.prefix, it.number_format, it.high_water) }
    }

    /** Outbox pending count (drives a "pending sync" indicator). */
    fun pendingOutboxFlow(): Flow<Long> =
        outboxQ.pendingCount().asFlow().mapToOneOrNull(Dispatchers.Default).map { it ?: 0L }

    // ── Optimistic offline create (the parallel-offline numbering path) ─────
    @OptIn(ExperimentalUuidApi::class)
    suspend fun createInvoiceLocal(
        businessId: String,
        supplierStateCode: String?,
        placeOfSupply: String?,
        customerName: String?,
        customerPhone: String?,
        customerGstin: String?,
        lines: List<GstLine>,
        discount: Double = 0.0,
        dueDays: Int = 7,
        notes: String? = null,
        fulfillment: String? = null,
        payment: com.bnm.lab.billing.PaymentChoice? = null,
    ): Result<Invoice> = withContext(Dispatchers.Default) {
        runCatching {
            val series = deviceSeries(businessId)
                ?: error("No billing series registered on this device. Register a counter series first.")
            val gst = GstTaxEngine.compute(lines, supplierStateCode, placeOfSupply, discount)
            val clientId = Uuid.random().toString()
            val idem = Uuid.random().toString()
            val today = nowDate()
            val dueAt = addDays(today, dueDays)
            // Payment recorded at save time: paid now (cash/upi/card) → status 'paid'
            // + paid_at stamped; else stays 'pending' with the method (if any) noted.
            val status = if (payment?.markPaid == true) "paid" else "pending"
            val paidAt = if (payment?.markPaid == true) nowIso() else null

            var invoiceNumber = ""
            db.transaction {
                // Atomic allocate: bump then read inside the same transaction.
                seriesQ.bumpSeries(businessId, series.series, series.fy)
                val localSeq = seriesQ.highWater(businessId, series.series, series.fy).executeAsOne()
                invoiceNumber = formatNumber(series.numberFormat, series.prefix, series.series, localSeq)

                val invoice = Invoice(
                    id = clientId, invoiceNumber = invoiceNumber, customerName = customerName,
                    customerPhone = customerPhone, customerGstin = customerGstin, placeOfSupply = placeOfSupply,
                    issuedAt = today, dueAt = dueAt, subtotal = gst.subtotal, discount = gst.discount,
                    tax = gst.tax, total = gst.total, status = status, paidAt = paidAt,
                    paymentMethod = payment?.method, paymentReference = payment?.reference,
                    amountTendered = payment?.tendered,
                    changeDue = payment?.change, seriesCode = series.series,
                    localSeq = localSeq, lineItems = gst.lineItems, taxBreakup = gst.taxBreakup,
                    notes = notes, clientId = clientId, syncStatus = "pending_sync", createdAt = nowIso(),
                )
                q.upsertEntity(INVOICE, clientId, businessId, 0L, nowIso(), json.encodeToString(Invoice.serializer(), invoice))

                val req = InvoiceCreateRequest(
                    clientId = clientId, invoiceNumber = invoiceNumber, seriesCode = series.series,
                    counterCode = series.series, fy = series.fy, localSeq = localSeq,
                    customerName = customerName, customerPhone = customerPhone, customerGstin = customerGstin,
                    placeOfSupply = placeOfSupply, lineItems = gst.lineItems, subtotal = gst.subtotal,
                    discount = gst.discount, tax = gst.tax, total = gst.total, taxBreakup = gst.taxBreakup,
                    issuedAt = today, dueAt = dueAt, status = status, notes = notes,
                    fulfillment = fulfillment,
                    paidAt = paidAt, paymentMethod = payment?.method, paymentReference = payment?.reference,
                    amountTendered = payment?.tendered, changeDue = payment?.change,
                )
                val seqLocal = outboxQ.maxSeqLocal().executeAsOne() + 1
                outboxQ.enqueue(
                    Uuid.random().toString(), businessId, "create_invoice", clientId, idem,
                    json.encodeToString(InvoiceCreateRequest.serializer(), req), nowIso(), seqLocal, null,
                )
            }
            invoiceById(clientId) ?: error("Local invoice insert failed")
        }
    }

    /**
     * Take a payment against an EXISTING bill — the advance at registration and
     * the balance collected days later are the same operation.
     *
     * Queued, never applied locally: invoices are pulled server-authoritative
     * (see [syncDelta]), so anything written into the local doc is erased by the
     * next sync. The tender goes out through the outbox and comes back inside
     * the server's row, where the rollup trigger derives pending → partial →
     * paid. Until it drains, [invoiceBalancesFlow] shows the money from the
     * queue, so the operator sees the right balance offline.
     *
     * Numbering is untouched — a payment must never bump the series.
     */
    @OptIn(ExperimentalUuidApi::class)
    suspend fun recordPaymentLocal(
        businessId: String,
        invoiceId: String,
        amount: Double,
        method: String?,
        reference: String? = null,
        note: String? = null,
    ): Result<Unit> = withContext(Dispatchers.Default) {
        runCatching {
            val amt = round2(amount)
            require(amt > 0.0) { "Enter an amount greater than zero" }
            // ONE client_id per tender, minted here and frozen into the payload:
            // the server's unique index turns a retry into a no-op. Minting a
            // fresh id per attempt would take the customer's money twice.
            val clientId = Uuid.random().toString()
            val req = InvoicePaymentRequest(
                clientId = clientId,
                amount = amt,
                paymentMethod = method?.trim()?.ifBlank { null },
                paymentReference = reference?.trim()?.ifBlank { null },
                note = note?.trim()?.ifBlank { null },
                paidAt = nowIso(),
            )
            db.transaction {
                val dep = outboxQ.pendingCreateKeyFor(invoiceId).executeAsOneOrNull()
                val seqLocal = outboxQ.maxSeqLocal().executeAsOne() + 1
                outboxQ.enqueue(
                    Uuid.random().toString(), businessId, OP_RECORD_PAYMENT, invoiceId, clientId,
                    json.encodeToString(InvoicePaymentRequest.serializer(), req), nowIso(), seqLocal, dep,
                )
            }
        }
    }

    /**
     * Wipe the synced offline cache (`ecom_entity`) + all delta cursors
     * (`sync_state`) so the next sync re-pulls everything fresh. Preserves the
     * login, the device counter series, and any un-synced bills in the outbox.
     * Fixes stale rows that linger after a server-side hard-delete / re-import
     * (delta sync only removes soft-deleted rows).
     *
     * Offline-edition bills are NOT cache: no server holds a copy, so they are
     * kept. Wiping them would erase a lab's billing history with no way back.
     */
    suspend fun clearLocalData() = withContext(Dispatchers.Default) {
        db.transaction {
            q.clearEntitiesExcept(BillingScope.OFFLINE_BUSINESS_ID)
            syncQ.clearAllState()
        }
    }

    // ── Delta sync (only what's new) ─────────────────────────────────────────
    suspend fun syncInvoices(businessId: String) = syncDelta(INVOICE, businessId) { api.getInvoicesDelta(businessId, it) }
    suspend fun syncInvoiceSettings(businessId: String) = syncDelta(INVOICE_SETTING, businessId) { api.getInvoiceSettingsDelta(businessId, it) }
    suspend fun syncTaxRates(businessId: String) = syncDelta(TAX_RATE, businessId) { api.getTaxRatesDelta(businessId, it) }
    suspend fun syncCounters(businessId: String) =
        syncDelta(COUNTER, businessId) { api.getCountersDelta(businessId, it) }
            .also { if (it.isSuccess) runCatching { refreshCounterVpa(businessId) } }

    /** After a counter sync, refresh the persisted device VPA from the counter row
     *  matching this device's bound series — so a VPA changed (or cleared) in BNM
     *  Admin reaches the payment sheet on normal sync, not just at pair time. */
    private suspend fun refreshCounterVpa(businessId: String) = withContext(Dispatchers.Default) {
        val series = deviceSeries(businessId)?.series ?: return@withContext
        val row = q.selectEntity(COUNTER, businessId).executeAsList()
            .mapNotNull { runCatching { json.decodeFromString<BillingCounter>(it) }.getOrNull() }
            .firstOrNull { it.seriesCode == series } ?: return@withContext
        com.bnm.lab.billing.BillingPrefs().counterUpiVpa = row.upiVpa?.trim().orEmpty()
    }

    /** Mint a Razorpay payment link for a saved invoice (online only). */
    suspend fun createPaymentLink(businessId: String, invoiceId: String) =
        api.createPaymentLink(businessId, invoiceId)

    /** Fetch one invoice from the server (payment-link status polling). */
    suspend fun getInvoice(businessId: String, invoiceId: String) = api.getInvoice(businessId, invoiceId)

    /** Store a server invoice row locally so lists show its state (e.g. paid via
     *  payment link) immediately, without waiting for the next delta sync. */
    suspend fun applyServerInvoice(businessId: String, inv: Invoice) =
        upsertLocal(INVOICE, inv.id, businessId, json.encodeToString(Invoice.serializer(), inv), inv.createdAt)
    // Products always FULL-sync (fromZero): the catalog is small, and prices/names
    // change in place. A delta keyed on seq leaves rows synced before a server-side
    // field was added (e.g. price/mrp) stuck with stale values forever — a full
    // pull each time self-heals them. (This is what fixed the "₹0 price" rows.)
    suspend fun syncProducts(businessId: String) = syncDelta(PRODUCT, businessId, fromZero = true) { api.getProductsDelta(businessId, it) }
    suspend fun syncCustomerDirectory(businessId: String) = syncDelta(CUSTOMER, businessId) { api.getCustomerDirectoryDelta(businessId, it) }

    private suspend fun syncDelta(
        entity: String,
        businessId: String,
        fromZero: Boolean = false,
        fetch: suspend (Long) -> Result<List<JsonElement>>,
    ): Result<Unit> {
        // The offline archive has no server side. Refusing here covers every
        // caller — the engine, a screen's "refresh", a WhatsApp send's re-pull.
        if (BillingScope.isOffline(businessId) || businessId.isBlank()) return Result.success(Unit)
        val key = cursorKey(entity, businessId)
        // fromZero re-pulls the whole entity (heals stale rows whose seq is below
        // the saved cursor); otherwise resume from the saved delta cursor.
        val cursor = if (fromZero) 0L else withContext(Dispatchers.Default) {
            syncQ.getState(key).executeAsOneOrNull()?.cursor?.toLongOrNull() ?: 0L
        }
        return fetch(cursor).map { page ->
            // The cursor moves past a held-back bill: the drain stores that bill's
            // current server row itself when its tender is acknowledged, so there
            // is nothing to come back for — and pinning the cursor re-downloaded
            // the whole business every sweep behind a tender that never landed.
            val written = storeDeltaPage(entity, businessId, page)
            withContext(Dispatchers.Default) { syncQ.upsertState(key, maxOf(cursor, written.maxSeq).toString(), nowIso()) }
        }
    }

    /** What [storeDeltaPage] wrote: the page's highest seq, and the lowest seq it held back (if any). */
    internal data class DeltaWrite(val maxSeq: Long, val heldBackFromSeq: Long?)

    /**
     * Write one pulled page; returns the highest seq in it (0 when empty).
     *
     * Never replaces a NEWER row. A pull's page can be fetched before a tender
     * lands and written after the drain has already stored the post-tender row
     * (the drain keeps the server's seq), and the payment would vanish from the
     * balance until the next pull. Optimistic local rows carry seq 0, so any
     * server row still replaces them.
     */
    internal suspend fun storeDeltaPage(entity: String, businessId: String, page: List<JsonElement>): DeltaWrite {
        if (page.isEmpty()) return DeltaWrite(0L, null)
        var maxSeq = 0L
        var heldBack: Long? = null
        withContext(Dispatchers.Default) {
            db.transaction {
                // Bills with a queued tender keep their local row (see
                // pendingTenderInvoiceIds) — unless there IS no local row, e.g.
                // after Clear & re-sync: an invisible bill is worse than one that
                // briefly counts a tender twice.
                val tenderQueued = if (entity == INVOICE) outboxQ.pendingTenderInvoiceIds().executeAsList().toHashSet() else emptySet()
                for (el in page) {
                    val o = el.jsonObject
                    val id = o["id"]?.jsonPrimitive?.contentOrNull ?: continue
                    val seq = o["seq"]?.jsonPrimitive?.longOrNull ?: 0L
                    val deleted = (o["deleted_at"] ?: o["deletedAt"])
                        ?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull
                    val createdAt = (o["created_at"] ?: o["createdAt"])
                        ?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull
                    val stored = q.seqOf(entity, id).executeAsOneOrNull()
                    when {
                        deleted != null -> q.deleteEntity(entity, id)
                        id in tenderQueued && stored != null -> {
                            if (seq > 0) heldBack = minOf(heldBack ?: seq, seq)
                        }
                        seq > 0 && seq < (stored ?: -1L) -> Unit
                        else -> q.upsertEntity(entity, id, businessId, seq, createdAt, el.toString())
                    }
                    if (seq > maxSeq) maxSeq = seq
                }
            }
        }
        return DeltaWrite(maxSeq, heldBack)
    }

    companion object {
        const val INVOICE = "invoice"
        const val INVOICE_SETTING = "invoice_setting"
        const val TAX_RATE = "tax_rate"
        const val COUNTER = "billing_counter"
        const val PRODUCT = "product"
        const val CUSTOMER = "customer"
        /** Outbox op for one tender against an existing bill (see [recordPaymentLocal]). */
        const val OP_RECORD_PAYMENT = "record_payment"
        fun cursorKey(entity: String, businessId: String) = "ecom-$entity-seq:$businessId"
    }
}

/**
 * One tender against an existing invoice — `admin-billing POST
 * /invoices/:businessId/:invoiceId/record-payment`. The endpoint APPENDS to the
 * bill's payment history (it does not overwrite), and dedupes on [clientId], so
 * an outbox retry can never double-count. Status is derived server-side from the
 * running total; nothing here says "paid".
 */
@kotlinx.serialization.Serializable
data class InvoicePaymentRequest(
    @kotlinx.serialization.SerialName("client_id") val clientId: String,
    @kotlinx.serialization.SerialName("amount") val amount: Double,
    @kotlinx.serialization.SerialName("payment_method") val paymentMethod: String? = null,
    @kotlinx.serialization.SerialName("payment_reference") val paymentReference: String? = null,
    @kotlinx.serialization.SerialName("note") val note: String? = null,
    @kotlinx.serialization.SerialName("paid_at") val paidAt: String? = null,
)

/**
 * A bill plus what has actually been collected on it. [paidAmount] is the
 * server's running total; [queuedAmount] is money taken on this device that is
 * still in the outbox. Every balance the operator sees comes from here — one
 * place decides whether a bill is settled, part paid or untouched.
 */
data class InvoiceBalance(
    val invoice: Invoice,
    val paidAmount: Double,
    val queuedAmount: Double,
    /** [queuedAmount] split by each queued tender's method (null = not recorded). */
    val queuedByMethod: Map<String?, Double> = emptyMap(),
    /**
     * Filed under the offline archive key: an offline-edition bill that exists
     * only on this computer. It never syncs by design, so "pending sync" and
     * "payment queued" are not states it can be in — they are its permanent record.
     */
    val offlineArchive: Boolean = false,
) {
    val collected: Double get() = paidAmount + queuedAmount
    /** Never negative: an over-tender is change in the drawer, not a credit. */
    val balance: Double get() = (invoice.total - collected).coerceAtLeast(0.0)
    val isCancelled: Boolean get() = invoice.status == "cancelled"
    /** Half a paisa is settled: a summed float total never lands exactly on 0. */
    val isSettled: Boolean get() = isCancelled || balance <= 0.005
    val isPartPaid: Boolean get() = !isSettled && collected > 0.005
    /** True while some of [collected] hasn't reached the server yet. Never for an
     *  offline-archive bill, whose tenders are not waiting for anything. */
    val hasQueuedPayment: Boolean get() = !offlineArchive && queuedAmount > 0.005
    /** The bill's own "not uploaded yet" flag, false where there is nothing to upload to. */
    val isPendingSync: Boolean get() = !offlineArchive && invoice.isPendingSync

    /** Operator-facing state. Server writes 'partial' (there is no 'unpaid'). */
    val label: String get() = when {
        isCancelled -> "Cancelled"
        isSettled -> "Paid"
        isPartPaid -> "Part paid"
        else -> "Unpaid"
    }
}

/** Money is compared and sent in paise-accurate units, never raw float noise. */
internal fun round2(v: Double): Double = kotlin.math.round(v * 100.0) / 100.0

/** One row of the offline customer-name directory. */
@kotlinx.serialization.Serializable
data class CustomerDirectoryEntry(
    @kotlinx.serialization.SerialName("id") val id: String,
    @kotlinx.serialization.SerialName("customerId") val customerId: String? = null,
    @kotlinx.serialization.SerialName("phone") val phone: String? = null,
    @kotlinx.serialization.SerialName("displayName") val displayName: String? = null,
)

private fun normalizePhone(p: String?): String? {
    val t = p?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return if (t.startsWith("+")) t.substring(1) else t
}

fun Map<String, String>.resolveCustomerName(customerId: String?, phone: String?): String? {
    if (!customerId.isNullOrBlank()) this["uuid:$customerId"]?.let { return it }
    val np = normalizePhone(phone)
    if (np != null) this["phone:$np"]?.let { return it }
    return null
}

private fun nowIso(): String = kotlin.time.Clock.System.now().toString()

/**
 * Money already collected on a bill, as the synced row states it.
 *
 * `paid_amount` is maintained ONLY by the server's invoice-payment rollup, and a
 * bill paid in full at the counter never gets a payment row: the create path
 * writes `status = 'paid'` and leaves `paid_amount` at its default 0. Trusting
 * the column whenever it was present turned every such bill that came back
 * through a pull (a new seat, "Clear & re-sync", a WhatsApp send) into "Unpaid,
 * full amount due" — blocking its report and inviting the desk to collect twice.
 * A paid bill is therefore never worth less than its total. Other statuses keep
 * trusting the column, and a local row that has no column at all has collected
 * nothing yet.
 */
internal fun paidAmountOf(status: String, total: Double, paidAmount: JsonElement?): Double {
    val column = runCatching { paidAmount?.takeIf { it !is JsonNull }?.jsonPrimitive?.doubleOrNull }.getOrNull()
    return if (status == "paid") maxOf(total, column ?: 0.0) else column ?: 0.0
}

/** This device's numbering series (from counter_series). */
data class SeriesInfo(val series: String, val fy: String, val prefix: String, val numberFormat: String, val highWater: Long)

private val IST = TimeZone.of("Asia/Kolkata")
internal fun nowDate(): String = kotlin.time.Clock.System.now().toLocalDateTime(IST).date.toString()
internal fun addDays(date: String, days: Int): String = LocalDate.parse(date).plus(DatePeriod(days = days)).toString()

/** Indian financial year for a date, e.g. 2026-06-21 → "2026-27" (Apr–Mar). */
internal fun currentFy(date: String = nowDate()): String {
    val d = LocalDate.parse(date)
    val startYear = if (d.month.ordinal + 1 >= 4) d.year else d.year - 1
    val endYy = (startYear + 1) % 100
    return "$startYear-${endYy.toString().padStart(2, '0')}"
}

internal fun formatNumber(format: String, prefix: String, series: String, seq: Long): String =
    format.replace("{prefix}", prefix).replace("{series}", series)
        .replace("{seq}", seq.toString().padStart(4, '0'))
