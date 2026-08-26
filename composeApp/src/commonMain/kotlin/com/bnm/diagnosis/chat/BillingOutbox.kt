package com.bnm.diagnosis.chat

import androidx.compose.runtime.staticCompositionLocalOf
import com.bnm.diagnosis.api.ApiClient
import com.bnm.diagnosis.api.BillingApi
import com.bnm.diagnosis.api.Constants
import com.bnm.diagnosis.api.SeriesCollisionException
import com.bnm.diagnosis.api.models.Invoice
import com.bnm.diagnosis.api.models.InvoiceCreateRequest
import com.bnm.diagnosis.auth.SessionManager
import com.bnm.diagnosis.connectivity.ConnectivityMonitor
import com.bnm.diagnosis.db.AppDatabase
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Drains the billing outbox: each queued mutation is POSTed (idempotently) to
 * admin-billing, then the optimistic local row is reconciled with the server's
 * canonical row (same id, so no remap) and the outbox entry deleted. Failures
 * are marked for retry on the next reconnect.
 */
class BillingOutboxSender(
    private val db: AppDatabase,
    private val api: BillingApi,
) {
    private val json = ApiClient.json
    private val outboxQ get() = db.billingOutboxQueries
    private val q get() = db.ecommerceQueries
    private val mutex = Mutex()

    /** Built on first use — a device that never takes a part payment never
     *  creates a second http client. */
    private val payments by lazy { InvoicePaymentClient() }

    /** App-lifetime scope so a drain triggered right before navigation isn't
     *  cancelled when the originating screen leaves the composition. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun requeueStuck() {
        runCatching { outboxQ.requeueStuck() }
    }

    /** Fire-and-forget drain on the app-lifetime scope. Screens call this after a
     *  create/mutation so the POST completes even as the UI navigates away. */
    fun kick() {
        scope.launch { runCatching { drain() } }
    }

    suspend fun drain() = mutex.withLock {
        withContext(Dispatchers.Default) {
            val pending = outboxQ.selectPending().executeAsList()
            for (row in pending) {
                // Respect dependencies: skip until the prerequisite mutation has drained.
                val dep = row.depends_on
                if (dep != null && outboxQ.countByIdempotency(dep).executeAsOne() > 0) continue

                outboxQ.markSending(row.id)
                val result = runCatching {
                    when (row.op) {
                        "create_invoice" -> {
                            var req = json.decodeFromString(InvoiceCreateRequest.serializer(), row.payload)
                            val inv = api.createInvoice(row.business_id, req).getOrElse { ex ->
                                // Duplicate number (a re-paired device reissued an existing
                                // number) → renumber to the next free seq and retry once.
                                if (ex is SeriesCollisionException) {
                                    req = renumberInvoice(row.id, row.business_id, req, ex.nextSeq)
                                    api.createInvoice(row.business_id, req).getOrThrow()
                                } else throw ex
                            }
                            // Replace the optimistic (pending_sync) row with the server row.
                            // Same id (client UUID) → no remap; sync_status absent → no longer pending.
                            q.upsertEntity(
                                BillingRepository.INVOICE, inv.id, row.business_id, 0L, inv.createdAt,
                                json.encodeToString(Invoice.serializer(), inv),
                            )
                        }
                        BillingRepository.OP_RECORD_PAYMENT -> {
                            // The payload is sent BYTE-FOR-BYTE as enqueued, so the
                            // client_id inside it is identical on every retry — the
                            // server dedupes on it, turning a lost ACK into a no-op
                            // instead of a second debit.
                            val server = payments.recordPayment(row.business_id, row.aggregate_id, row.payload)
                                ?: payments.invoiceRaw(row.business_id, row.aggregate_id)
                            // Store the server row VERBATIM. Round-tripping it through
                            // the Invoice model would silently drop `paid_amount` (an
                            // unknown key) — the one field every balance is read from.
                            if (server != null) {
                                val id = server["id"]?.jsonPrimitive?.contentOrNull ?: row.aggregate_id
                                val createdAt = (server["created_at"] ?: server["createdAt"])
                                    ?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull
                                // Hand the money over in ONE step: for the instant
                                // between dropping the queued tender and adopting the
                                // server row, a balance would read it twice (or not
                                // at all). The outer deleteById then finds nothing.
                                db.transaction {
                                    outboxQ.deleteById(row.id)
                                    q.upsertEntity(
                                        BillingRepository.INVOICE, id, row.business_id, 0L, createdAt, server.toString(),
                                    )
                                }
                            }
                        }
                        else -> Unit // unknown op — drop
                    }
                }
                if (result.isSuccess) {
                    outboxQ.deleteById(row.id)
                } else {
                    outboxQ.markFailed(nowIso(), result.exceptionOrNull()?.message?.take(300), row.id)
                }
            }
        }
    }

    /** Renumber a colliding invoice to the next free sequence (server hint, else
     *  local high_water+1), raise the local high_water so future bills continue
     *  past it, and rewrite the outbox payload so this retry — and any later
     *  retry — uses the new number. */
    private fun renumberInvoice(
        outboxId: String,
        businessId: String,
        req: InvoiceCreateRequest,
        serverNextSeq: Long?,
    ): InvoiceCreateRequest {
        val seriesQ = db.counterSeriesQueries
        val series = seriesQ.allSeries(businessId).executeAsList()
            .firstOrNull { it.series == req.seriesCode && it.fy == req.fy }
        val localHw = series?.high_water ?: 0L
        val newSeq = maxOf(serverNextSeq ?: 0L, localHw + 1)
        val fmt = series?.number_format ?: "{prefix}-{series}-{seq}"
        val prefix = series?.prefix ?: "INV"
        val newNumber = formatNumber(fmt, prefix, req.seriesCode, newSeq)
        val newReq = req.copy(invoiceNumber = newNumber, localSeq = newSeq)
        db.transaction {
            seriesQ.raiseHighWater(newSeq, businessId, req.seriesCode, req.fy)
            outboxQ.updatePayload(json.encodeToString(InvoiceCreateRequest.serializer(), newReq), outboxId)
        }
        return newReq
    }

    private fun nowIso(): String = kotlin.time.Clock.System.now().toString()
}

/**
 * The two calls a queued tender needs: append it to a bill, and read that bill
 * back. Both hand over the invoice's RAW server JSON — decoding through
 * [Invoice] would drop `paid_amount`, which is exactly the field a part payment
 * exists to change.
 *
 * It lives beside the outbox (its only caller) rather than on BillingApi
 * because it must never be reachable from a screen: a payment always goes
 * through the outbox, never straight to the network.
 */
private class InvoicePaymentClient(
    private val http: HttpClient = ApiClient.create(),
    private val session: SessionManager = SessionManager(),
) {
    private val json = ApiClient.json

    /** Throwing (rather than posting anonymously) leaves the row queued for the
     *  next drain — a signed-out seat must not lose the tender. */
    private fun bearer(): String =
        session.getSessionToken()?.takeIf { it.isNotEmpty() }?.let { "Bearer $it" }
            ?: error("Not signed in")

    private suspend fun body(resp: HttpResponse): JsonObject {
        val text = resp.bodyAsText()
        if (!resp.status.isSuccess()) error("HTTP ${resp.status.value}: ${text.take(300)}")
        return json.parseToJsonElement(text).jsonObject
    }

    /** POST the tender verbatim. Returns the updated invoice when the server
     *  echoes one. */
    suspend fun recordPayment(businessId: String, invoiceId: String, payload: String): JsonObject? {
        val resp = http.post(url(businessId, invoiceId, "/record-payment")) {
            header("Authorization", bearer())
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        return body(resp)["invoice"] as? JsonObject
    }

    /** Fallback re-read, so the counter sees the new balance immediately even if
     *  the endpoint answered with a bare ack. */
    suspend fun invoiceRaw(businessId: String, invoiceId: String): JsonObject? = runCatching {
        val resp = http.get(url(businessId, invoiceId, "")) { header("Authorization", bearer()) }
        body(resp)["invoice"] as? JsonObject
    }.getOrNull()

    private fun url(businessId: String, invoiceId: String, suffix: String) =
        "${Constants.EDGE_FUNCTIONS_BASE_URL}/admin-billing/invoices/$businessId/$invoiceId$suffix"
}

/** Recovers stuck sends + drains the outbox on every reconnect. */
class BillingSyncManager(
    private val sender: BillingOutboxSender,
    private val connectivity: ConnectivityMonitor,
) {
    fun start(scope: CoroutineScope) {
        scope.launch {
            sender.requeueStuck()
            runCatching { sender.drain() }
        }
        scope.launch {
            var wasOnline = true
            connectivity.isOnline.collect { online ->
                if (online && !wasOnline) runCatching { sender.drain() }
                wasOnline = online
            }
        }
    }
}

/** App-wide outbox sender, provided in App.kt — screens call drain() after a create. */
val LocalOutboxSender = staticCompositionLocalOf<BillingOutboxSender> {
    error("LocalOutboxSender not provided")
}
