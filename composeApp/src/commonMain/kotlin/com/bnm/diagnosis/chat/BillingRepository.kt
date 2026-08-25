package com.bnm.diagnosis.chat

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.bnm.diagnosis.api.ApiClient
import com.bnm.diagnosis.api.BillingApi
import com.bnm.diagnosis.api.models.BillingCounter
import com.bnm.diagnosis.api.models.Invoice
import com.bnm.diagnosis.api.models.InvoiceCreateRequest
import com.bnm.diagnosis.api.models.InvoiceSettings
import com.bnm.diagnosis.api.models.Product
import com.bnm.diagnosis.api.models.TaxRate
import com.bnm.diagnosis.billing.GstLine
import com.bnm.diagnosis.billing.GstTaxEngine
import com.bnm.diagnosis.db.AppDatabase
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
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
        payment: com.bnm.diagnosis.billing.PaymentChoice? = null,
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
     * Wipe the synced offline cache (`ecom_entity`) + all delta cursors
     * (`sync_state`) so the next sync re-pulls everything fresh. Preserves the
     * login, the device counter series, and any un-synced bills in the outbox.
     * Fixes stale rows that linger after a server-side hard-delete / re-import
     * (delta sync only removes soft-deleted rows).
     */
    suspend fun clearLocalData() = withContext(Dispatchers.Default) {
        db.transaction {
            q.clearAllEntities()
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
        com.bnm.diagnosis.billing.BillingPrefs().counterUpiVpa = row.upiVpa?.trim().orEmpty()
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
        val key = cursorKey(entity, businessId)
        // fromZero re-pulls the whole entity (heals stale rows whose seq is below
        // the saved cursor); otherwise resume from the saved delta cursor.
        val cursor = if (fromZero) 0L else withContext(Dispatchers.Default) {
            syncQ.getState(key).executeAsOneOrNull()?.cursor?.toLongOrNull() ?: 0L
        }
        return fetch(cursor).map { page ->
            var maxSeq = cursor
            if (page.isNotEmpty()) {
                withContext(Dispatchers.Default) {
                    db.transaction {
                        for (el in page) {
                            val o = el.jsonObject
                            val id = o["id"]?.jsonPrimitive?.contentOrNull ?: continue
                            val seq = o["seq"]?.jsonPrimitive?.longOrNull ?: 0L
                            val deleted = (o["deleted_at"] ?: o["deletedAt"])
                                ?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull
                            val createdAt = (o["created_at"] ?: o["createdAt"])
                                ?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull
                            if (deleted != null) q.deleteEntity(entity, id)
                            else q.upsertEntity(entity, id, businessId, seq, createdAt, el.toString())
                            if (seq > maxSeq) maxSeq = seq
                        }
                    }
                }
            }
            withContext(Dispatchers.Default) { syncQ.upsertState(key, maxSeq.toString(), nowIso()) }
        }
    }

    companion object {
        const val INVOICE = "invoice"
        const val INVOICE_SETTING = "invoice_setting"
        const val TAX_RATE = "tax_rate"
        const val COUNTER = "billing_counter"
        const val PRODUCT = "product"
        const val CUSTOMER = "customer"
        fun cursorKey(entity: String, businessId: String) = "ecom-$entity-seq:$businessId"
    }
}

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
