package com.bnm.diagnosis.chat

import androidx.compose.runtime.staticCompositionLocalOf
import com.bnm.diagnosis.api.ApiClient
import com.bnm.diagnosis.api.BillingApi
import com.bnm.diagnosis.api.SeriesCollisionException
import com.bnm.diagnosis.api.models.Invoice
import com.bnm.diagnosis.api.models.InvoiceCreateRequest
import com.bnm.diagnosis.connectivity.ConnectivityMonitor
import com.bnm.diagnosis.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
