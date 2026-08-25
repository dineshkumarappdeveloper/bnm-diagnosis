package com.bnm.diagnosis.api

import com.bnm.diagnosis.api.models.BillingCounter
import com.bnm.diagnosis.api.models.Business
import com.bnm.diagnosis.api.models.BusinessListResponse
import com.bnm.diagnosis.api.models.Invoice
import com.bnm.diagnosis.api.models.InvoiceCreateRequest
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Thrown when an invoice POST is rejected because its number already exists
 * (HTTP 409 `series_collision`) — e.g. a re-paired device restarted its local
 * numbering. Carries the server's next free sequence so the outbox can renumber
 * the bill and retry instead of looping on a permanent failure.
 */
class SeriesCollisionException(val nextSeq: Long?) : Exception("series_collision")

/** Razorpay payment link minted for a saved invoice. */
data class PaymentLinkInfo(val linkId: String, val shortUrl: String, val amount: Double)

/** Backend client for the Billing app. Delta reads + idempotent writes against
 *  admin-billing; orders/customers reuse admin-orders; business list via admin-ai. */
class BillingApi(
    private val httpClient: HttpClient,
    private val tokenProvider: suspend () -> String?,
    private val onUnauthorized: (suspend () -> Unit)? = null,
) {
    private val json = ApiClient.json

    private fun edgeUrl(module: String, path: String) = "${Constants.EDGE_FUNCTIONS_BASE_URL}/$module$path"

    private suspend fun authHeader(): Pair<String, String>? {
        val token = tokenProvider()
        if (token.isNullOrEmpty()) { onUnauthorized?.invoke(); return null }
        return "Authorization" to "Bearer $token"
    }

    private suspend fun checkStatus(resp: HttpResponse) {
        if (resp.status == HttpStatusCode.Unauthorized) { onUnauthorized?.invoke(); error("Unauthorized") }
        if (!resp.status.isSuccess()) error("HTTP ${resp.status.value}: ${resp.bodyAsText().take(300)}")
    }

    // ── Business selection ──
    suspend fun getBusinesses(): Result<List<Business>> = withContext(Dispatchers.Default) {
        runCatching {
            val auth = authHeader() ?: error("Not authenticated")
            val resp = httpClient.get(edgeUrl("admin-ai", "/business-accounts")) { header(auth.first, auth.second) }
            checkStatus(resp)
            val text = resp.bodyAsText()
            runCatching { json.decodeFromString<BusinessListResponse>(text).businesses }
                .getOrElse { json.decodeFromString<List<Business>>(text) }
        }
    }

    // ── Delta reads ──
    private suspend fun delta(module: String, path: String, key: String, sinceSeq: Long): Result<List<JsonElement>> =
        withContext(Dispatchers.Default) {
            runCatching {
                val auth = authHeader() ?: error("Not authenticated")
                val resp = httpClient.get(edgeUrl(module, "$path?sinceSeq=$sinceSeq")) { header(auth.first, auth.second) }
                checkStatus(resp)
                (json.parseToJsonElement(resp.bodyAsText()).jsonObject[key]?.jsonArray ?: JsonArray(emptyList())).toList()
            }
        }

    suspend fun getInvoicesDelta(bid: String, sinceSeq: Long) = delta("admin-billing", "/invoices/$bid", "invoices", sinceSeq)
    suspend fun getInvoiceSettingsDelta(bid: String, sinceSeq: Long) = delta("admin-billing", "/invoice-settings/$bid", "settings", sinceSeq)
    suspend fun getTaxRatesDelta(bid: String, sinceSeq: Long) = delta("admin-billing", "/tax-rates/$bid", "rates", sinceSeq)
    suspend fun getCountersDelta(bid: String, sinceSeq: Long) = delta("admin-billing", "/billing-counters/$bid", "counters", sinceSeq)
    suspend fun getProductsDelta(bid: String, sinceSeq: Long) = delta("admin-billing", "/products/$bid", "products", sinceSeq)

    suspend fun getCustomerDirectoryDelta(bid: String, sinceSeq: Long): Result<List<JsonElement>> =
        withContext(Dispatchers.Default) {
            runCatching {
                val auth = authHeader() ?: error("Not authenticated")
                val resp = httpClient.get(edgeUrl("admin-orders", "/customer-directory/$bid?sinceSeq=$sinceSeq")) { header(auth.first, auth.second) }
                checkStatus(resp)
                json.parseToJsonElement(resp.bodyAsText()).jsonArray.toList()
            }
        }

    // ── Writes ──
    suspend fun createInvoice(bid: String, req: InvoiceCreateRequest): Result<Invoice> = withContext(Dispatchers.Default) {
        runCatching {
            val auth = authHeader() ?: error("Not authenticated")
            val resp = httpClient.post(edgeUrl("admin-billing", "/invoices/$bid")) {
                header(auth.first, auth.second); contentType(ContentType.Application.Json)
                setBody(json.encodeToString(InvoiceCreateRequest.serializer(), req))
            }
            val text = resp.bodyAsText()
            // Duplicate invoice number (e.g. a re-paired device restarted its local
            // numbering) → surface a typed error carrying the server's next free seq
            // so the outbox can renumber + retry instead of looping forever.
            if (resp.status == HttpStatusCode.Conflict) {
                val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                if (o?.get("error")?.jsonPrimitive?.content == "series_collision") {
                    throw SeriesCollisionException(o["next_seq"]?.jsonPrimitive?.content?.toLongOrNull())
                }
            }
            if (resp.status == HttpStatusCode.Unauthorized) { onUnauthorized?.invoke(); error("Unauthorized") }
            if (!resp.status.isSuccess()) error("HTTP ${resp.status.value}: ${text.take(300)}")
            json.decodeFromString<Invoice>(json.parseToJsonElement(text).jsonObject["invoice"].toString())
        }
    }

    suspend fun markPaid(bid: String, invoiceId: String, method: String?, reference: String?): Result<Invoice> =
        withContext(Dispatchers.Default) {
            runCatching {
                val auth = authHeader() ?: error("Not authenticated")
                val resp = httpClient.patch(edgeUrl("admin-billing", "/invoices/$bid/$invoiceId/mark-paid")) {
                    header(auth.first, auth.second); contentType(ContentType.Application.Json)
                    setBody(buildString { append("{"); append("\"payment_method\":"); append(method?.let { "\"$it\"" } ?: "null")
                        append(",\"payment_reference\":"); append(reference?.let { "\"$it\"" } ?: "null"); append("}") })
                }
                checkStatus(resp)
                json.decodeFromString<Invoice>(json.parseToJsonElement(resp.bodyAsText()).jsonObject["invoice"].toString())
            }
        }

    /** Mint a Razorpay payment link for a saved (synced) invoice. The Razorpay
     *  webhook flips the invoice paid later — the device sees it on sync. Errors
     *  surface the server's friendly text (409 already_paid, 502 unconfigured). */
    suspend fun createPaymentLink(bid: String, invoiceId: String): Result<PaymentLinkInfo> =
        withContext(Dispatchers.Default) {
            runCatching {
                val auth = authHeader() ?: error("Not authenticated")
                val resp = httpClient.post(edgeUrl("admin-billing", "/invoices/$bid/$invoiceId/payment-link")) {
                    header(auth.first, auth.second); contentType(ContentType.Application.Json); setBody("{}")
                }
                if (resp.status == HttpStatusCode.Unauthorized) { onUnauthorized?.invoke(); error("Unauthorized") }
                val text = resp.bodyAsText()
                val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                if (!resp.status.isSuccess()) {
                    val err = obj?.get("error")?.jsonPrimitive?.content
                    error(
                        when {
                            err == "already_paid" -> "This bill is already paid"
                            !err.isNullOrBlank() -> err
                            else -> "HTTP ${resp.status.value}: ${text.take(200)}"
                        }
                    )
                }
                val o = obj ?: error("Empty response")
                PaymentLinkInfo(
                    linkId = o["link_id"]?.jsonPrimitive?.content ?: "",
                    shortUrl = o["short_url"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                        ?: error("No payment link returned"),
                    amount = o["amount"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                )
            }
        }

    /** Fetch ONE invoice — the payment-link dialog polls this so the counter
     *  sees "Paid" the moment the Razorpay webhook flips the invoice. */
    suspend fun getInvoice(bid: String, invoiceId: String): Result<Invoice> =
        withContext(Dispatchers.Default) {
            runCatching {
                val auth = authHeader() ?: error("Not authenticated")
                val resp = httpClient.get(edgeUrl("admin-billing", "/invoices/$bid/$invoiceId")) {
                    header(auth.first, auth.second)
                }
                checkStatus(resp)
                json.decodeFromString<Invoice>(json.parseToJsonElement(resp.bodyAsText()).jsonObject["invoice"].toString())
            }
        }

    suspend fun sendInvoice(bid: String, invoiceId: String, via: String): Result<Invoice> = withContext(Dispatchers.Default) {
        runCatching {
            val auth = authHeader() ?: error("Not authenticated")
            val resp = httpClient.post(edgeUrl("admin-billing", "/invoices/$bid/$invoiceId/send")) {
                header(auth.first, auth.second); contentType(ContentType.Application.Json); setBody("{\"via\":\"$via\"}")
            }
            checkStatus(resp)
            json.decodeFromString<Invoice>(json.parseToJsonElement(resp.bodyAsText()).jsonObject["invoice"].toString())
        }
    }

    suspend fun registerCounter(bid: String, seriesCode: String, prefix: String, numberFormat: String, fy: String, start: Long): Result<BillingCounter> =
        withContext(Dispatchers.Default) {
            runCatching {
                val auth = authHeader() ?: error("Not authenticated")
                val resp = httpClient.post(edgeUrl("admin-billing", "/billing-counters/$bid/register")) {
                    header(auth.first, auth.second); contentType(ContentType.Application.Json)
                    setBody("""{"series_code":"$seriesCode","prefix":"$prefix","number_format":"$numberFormat","fy":"$fy","start":$start}""")
                }
                checkStatus(resp)
                json.decodeFromString<BillingCounter>(json.parseToJsonElement(resp.bodyAsText()).jsonObject["counter"].toString())
            }
        }

    suspend fun updateSettings(bid: String, bodyJson: String): Result<Unit> = withContext(Dispatchers.Default) {
        runCatching {
            val auth = authHeader() ?: error("Not authenticated")
            val resp = httpClient.put(edgeUrl("admin-billing", "/invoice-settings/$bid")) {
                header(auth.first, auth.second); contentType(ContentType.Application.Json); setBody(bodyJson)
            }
            checkStatus(resp)
        }
    }
}
