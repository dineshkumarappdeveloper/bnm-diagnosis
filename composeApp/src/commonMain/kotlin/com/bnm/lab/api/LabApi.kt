package com.bnm.lab.api

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import com.bnm.lab.lab.TestParameter
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

// ── Models (admin-lab contract, snake_case → @SerialName) ────────────────────

/** 200 response of `POST admin-lab/activate`. */
@Serializable
data class LicenseActivation(
    @SerialName("license_jwt") val licenseJwt: String,
    @SerialName("device_token") val deviceToken: String,
    @SerialName("device_row_id") val deviceRowId: String,
    @SerialName("lab_name") val labName: String,
    val mode: String, // "perpetual" | "subscription"
    val seats: Int = 1,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("business_id") val businessId: String? = null,
    /** Stable per-device seat number (never reused). Absent from servers that assign none. */
    @SerialName("seat_no") val seatNo: Int? = null,
)

/** A seat row — from the 409 seats_full payload and GET /devices. */
@Serializable
data class LabSeatDevice(
    val id: String,
    @SerialName("device_name") val deviceName: String? = null,
    val platform: String? = null,
    @SerialName("last_seen") val lastSeen: String? = null,
    /** seats_full only: silent >7d → can be taken over via replace_device_id. */
    val replaceable: Boolean = false,
    /** GET /devices only. */
    val status: String? = null,
    @SerialName("activated_at") val activatedAt: String? = null,
)

/** Typed outcome of `activate` — non-fatal branches the UI must handle. */
sealed interface LabActivateResult {
    data class Activated(val license: LicenseActivation) : LabActivateResult

    /** 409 seats_full — retry with `replaceDeviceId` of a replaceable seat. */
    data class SeatsFull(val seats: Int, val devices: List<LabSeatDevice>) : LabActivateResult

    /** 409 replace_cooldown — the chosen seat was active too recently. */
    data class ReplaceCooldown(val message: String) : LabActivateResult
}

/** Typed outcome of `heartbeat`. */
sealed interface LabHeartbeatResult {
    data class Ok(
        val licenseJwt: String?,
        val mode: String?,
        val seats: Int?,
        val expiresAt: String?,
        val labName: String?,
        val seatNo: Int? = null,
        /**
         * `report_page_live` — has the app.bnmapp.com/r/ report page shipped?
         * It decides which link a printed QR encodes (see `ReportShare`), and
         * paper is permanent, so null ("this server said nothing") must leave
         * the stored answer alone rather than read as false.
         */
        val reportPageLive: Boolean? = null,
    ) : LabHeartbeatResult

    /** 403 license_inactive | device_revoked — block creating new work. */
    data class Blocked(val code: String, val message: String) : LabHeartbeatResult

    /** 401 — invalid device session; offline session semantics unchanged. */
    data object InvalidSession : LabHeartbeatResult
}

/** GET /devices response: all seats + which row is THIS device. */
data class LabDevicesInfo(val devices: List<LabSeatDevice>, val selfId: String?)

// ── P3 sync models ────────────────────────────────────────────────────────────

/**
 * Every sync endpoint returns 409 `{code:'no_business'}` for a STANDALONE
 * license (no BNM business linked). The engine treats it as "sync disabled":
 * silent, no retries within a run, a one-line note on the home screen only.
 */
class LabSyncDisabledException(message: String) : Exception(message)

/** One row of `POST admin-lab/sync/push` — the app's doc mirrored to `lab_entities`. */
@Serializable
data class LabSyncPushRow(
    val entity: String,
    val id: String,
    val json: kotlinx.serialization.json.JsonElement,
    @SerialName("deleted_at") val deletedAt: String? = null,
)

/** One row of `GET admin-lab/sync/pull` (ordered by server seq). */
@Serializable
data class LabSyncPullRow(
    val entity: String,
    val id: String,
    val seq: Long = 0,
    @SerialName("deleted_at") val deletedAt: String? = null,
    val json: kotlinx.serialization.json.JsonElement? = null,
)

/**
 * One clinic order from `GET admin-lab/emr-orders` (clinical_lab_orders projection).
 *
 * The identity block ([testCode] … [patientDob]) is an ADDITIVE server change:
 * every field defaults to null, so this parses unchanged against a server that
 * hasn't shipped it yet — the desk simply falls back to name matching and a
 * hand-typed patient, exactly as before.
 *  - [testCode]  the LAB'S OWN catalog code the doctor picked; null = the
 *                doctor free-texted the test and only [testName] exists.
 *  - [patientDob] ISO date — the desk derives whole years from it.
 */
@Serializable
data class LabEmrOrder(
    val id: String,
    @SerialName("visit_id") val visitId: String? = null,
    @SerialName("test_name") val testName: String? = null,
    val instructions: String? = null,
    val status: String? = null,          // clinic side: ordered | completed | cancelled…
    @SerialName("lab_status") val labStatus: String? = null,
    @SerialName("accession_no") val accessionNo: String? = null,
    val seq: Long = 0,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("result_value") val resultValue: String? = null,
    @SerialName("resulted_at") val resultedAt: String? = null,
    // ── identity block (additive; all nullable) ──
    @SerialName("test_code") val testCode: String? = null,
    @SerialName("visit_number") val visitNumber: String? = null,
    @SerialName("patient_name") val patientName: String? = null,
    @SerialName("patient_phone") val patientPhone: String? = null,
    @SerialName("patient_sex") val patientSex: String? = null,   // M | F | O
    @SerialName("patient_dob") val patientDob: String? = null,   // ISO date
)

/**
 * One lab-test SERVICE product from `GET admin-lab/platform-tests` (L3):
 * the LINKED business's products where item_type='service' and lab_config is
 * set. [labConfig] arrives as the raw jsonb object — the importer both decodes
 * the fields it maps AND stores the object verbatim (lossless) on the local
 * test row, so nothing the platform authored is ever dropped.
 */
@Serializable
data class PlatformLabTest(
    val id: String,
    val name: String = "Lab test",
    @SerialName("selling_price") val sellingPrice: Double? = null,
    val currency: String? = null,
    /** Discipline from the product's first category ("Haematology", …).
     *  Nullable and defaulted: a server that predates this field still parses. */
    val category: String? = null,
    @SerialName("lab_config") val labConfig: JsonObject? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val seq: Long = 0,
)

/**
 * One row of the GLOBAL master test catalog (`lab_test_catalog`), served by
 * `admin-lab/master-catalog`.
 *
 * Note what is NOT here: a price. The master catalog is reference data about
 * MEDICINE — every lab prices its own menu, so an imported test lands at 0 and
 * the lab sets its own price locally.
 *
 * `parameters` is stored on the platform in the LIMS shape ALREADY, with the
 * same field names as [TestParameter]/[RefRange], so it deserialises straight
 * into the local model. That is deliberate: the connected-edition importer has
 * to flatten lab_config into reference_ranges and regroup them BY ANALYTE NAME,
 * which loses a rangeless analyte unless it emits an empty row. Reading the
 * canonical shape directly skips that hazard entirely.
 */
@Serializable
data class MasterCatalogTest(
    val code: String,
    val name: String,
    val category: String? = null,
    @SerialName("sample_type") val sampleType: String = "blood",
    val method: String? = null,
    @SerialName("tat_hours") val tatHours: Double? = null,
    val parameters: List<TestParameter> = emptyList(),
    @SerialName("sort_order") val sortOrder: Int = 0,
)

/**
 * This device's invoice numbering series, minted by `admin-lab/billing-series`
 * (one series per seat — L1/L2/L3 — so parallel-offline devices never collide
 * on invoice numbers). Registered locally via BillingRepository so bills work
 * exactly as on a paired BNMBilling counter.
 */
@Serializable
data class LabBillingSeries(
    val seriesCode: String,
    val prefix: String = "LAB",
    val numberFormat: String = "{prefix}-{series}-{seq}",
    val highWater: Long = 0,
    val businessId: String? = null,
)

/**
 * Client for the `admin-lab` edge fn — license activation + device management.
 * Auth is the license `device_token` (NOT the BusinessStudio session token);
 * `activate` is the only unauthenticated call.
 */
class LabApi(
    private val httpClient: HttpClient,
    private val deviceTokenProvider: () -> String?,
) {
    private val json = ApiClient.json

    private fun edgeUrl(path: String) = "${Constants.EDGE_FUNCTIONS_BASE_URL}/admin-lab$path"

    private fun deviceAuth(): Pair<String, String> {
        val token = deviceTokenProvider()
        if (token.isNullOrEmpty()) error("This device isn't activated yet")
        return "Authorization" to "Bearer $token"
    }

    /** JSON null is absent, not the string "null" (a perpetual licence's
     *  `expires_at` is null — see license.claimString for the same trap). */
    private fun JsonObject?.strField(key: String): String? =
        runCatching { (this?.get(key) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content }.getOrNull()

    /**
     * `POST admin-lab/activate` (no auth). Returns a typed outcome for the
     * success / seats_full / replace_cooldown branches; hard rejections
     * (invalid_key, license_revoked/suspended/expired) become failures whose
     * message is the server's error text verbatim when present.
     */
    suspend fun activate(
        key: String,
        deviceId: String,
        deviceName: String,
        platform: String,
        replaceDeviceId: String? = null,
    ): Result<LabActivateResult> = withContext(Dispatchers.Default) {
        runCatching {
            val resp = httpClient.post(edgeUrl("/activate")) {
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("key", key.trim())
                        put("device_id", deviceId)
                        put("device_name", deviceName)
                        put("platform", platform)
                        replaceDeviceId?.let { put("replace_device_id", it) }
                    }.toString()
                )
            }
            val text = resp.bodyAsText()
            val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            when {
                resp.status.isSuccess() ->
                    LabActivateResult.Activated(json.decodeFromString(LicenseActivation.serializer(), text))

                resp.status == HttpStatusCode.Conflict -> when (obj.strField("code")) {
                    "seats_full" -> LabActivateResult.SeatsFull(
                        seats = obj?.get("seats")?.jsonPrimitive?.intOrNull ?: 0,
                        devices = obj?.get("devices")?.let {
                            json.decodeFromJsonElement(ListSerializer(LabSeatDevice.serializer()), it)
                        } ?: emptyList(),
                    )
                    "replace_cooldown" -> LabActivateResult.ReplaceCooldown(
                        obj.strField("error") ?: "That device was active recently and can't be replaced yet"
                    )
                    else -> error(obj.strField("error") ?: "HTTP 409: ${text.take(200)}")
                }

                else -> error(
                    obj.strField("error") ?: when (obj.strField("code")) {
                        "invalid_key" -> "License key not found — check it and try again"
                        "license_revoked" -> "This license has been revoked"
                        "license_suspended" -> "This license is suspended"
                        "license_expired" -> "This license has expired"
                        else -> "HTTP ${resp.status.value}: ${text.take(200)}"
                    }
                )
            }
        }
    }

    /**
     * `POST admin-lab/heartbeat` (device auth). 200 refreshes the license JWT;
     * 403 blocks (license_inactive / device_revoked); 401 = invalid session
     * (caller ignores — offline semantics unchanged).
     */
    suspend fun heartbeat(): Result<LabHeartbeatResult> = withContext(Dispatchers.Default) {
        runCatching {
            val auth = deviceAuth()
            val resp = httpClient.post(edgeUrl("/heartbeat")) {
                header(auth.first, auth.second)
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
            val text = resp.bodyAsText()
            val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            when {
                resp.status.isSuccess() -> LabHeartbeatResult.Ok(
                    licenseJwt = obj.strField("license_jwt"),
                    mode = obj.strField("mode"),
                    seats = obj?.get("seats")?.jsonPrimitive?.intOrNull,
                    expiresAt = obj.strField("expires_at"),
                    labName = obj.strField("lab_name"),
                    seatNo = obj?.get("seat_no")?.jsonPrimitive?.intOrNull,
                    reportPageLive = obj?.get("report_page_live")?.jsonPrimitive?.booleanOrNull,
                )
                resp.status == HttpStatusCode.Unauthorized -> LabHeartbeatResult.InvalidSession
                resp.status == HttpStatusCode.Forbidden -> LabHeartbeatResult.Blocked(
                    code = obj.strField("code") ?: "license_inactive",
                    message = obj.strField("error") ?: "This device's license is no longer active",
                )
                else -> error("HTTP ${resp.status.value}: ${text.take(200)}")
            }
        }
    }

    /** `GET admin-lab/devices` (device auth): seat list + this device's row id. */
    suspend fun listDevices(): Result<LabDevicesInfo> = withContext(Dispatchers.Default) {
        runCatching {
            val auth = deviceAuth()
            val resp = httpClient.get(edgeUrl("/devices")) { header(auth.first, auth.second) }
            val text = resp.bodyAsText()
            if (!resp.status.isSuccess()) {
                val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                error(obj.strField("error") ?: "HTTP ${resp.status.value}: ${text.take(200)}")
            }
            val obj = json.parseToJsonElement(text).jsonObject
            val devices = obj["devices"]?.let {
                json.decodeFromJsonElement(ListSerializer(LabSeatDevice.serializer()), it)
            } ?: emptyList()
            // `self` may be the row id or an object carrying it — parse both.
            val selfId = runCatching {
                obj["self"]?.let { self ->
                    if (self is JsonObject) self.strField("id") else self.jsonPrimitive.content
                }
            }.getOrNull()
            LabDevicesInfo(devices, selfId)
        }
    }

    // ── P3 sync endpoints ────────────────────────────────────────────────────

    /** Throw the typed sync-disabled error on 409 no_business, else a plain error. */
    private fun syncFail(status: HttpStatusCode, text: String): Nothing {
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
        val conflictCode = obj.strField("code")
        if (status == HttpStatusCode.Conflict &&
            (conflictCode == "no_business" || conflictCode == "standalone_edition")
        ) {
            throw LabSyncDisabledException(
                obj.strField("error") ?: "This license is standalone — sync is disabled"
            )
        }
        error(obj.strField("error") ?: "HTTP ${status.value}: ${text.take(200)}")
    }

    /** `POST admin-lab/sync/push` {rows:[…]} (≤500/batch) → upserted count. */
    suspend fun syncPush(rows: List<LabSyncPushRow>): Result<Int> = withContext(Dispatchers.Default) {
        runCatching {
            val auth = deviceAuth()
            val resp = httpClient.post(edgeUrl("/sync/push")) {
                header(auth.first, auth.second)
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("rows", json.encodeToJsonElement(ListSerializer(LabSyncPushRow.serializer()), rows))
                    }.toString()
                )
            }
            val text = resp.bodyAsText()
            if (!resp.status.isSuccess()) syncFail(resp.status, text)
            runCatching {
                json.parseToJsonElement(text).jsonObject["upserted"]?.jsonPrimitive?.intOrNull
            }.getOrNull() ?: rows.size
        }
    }

    /** `GET admin-lab/sync/pull?sinceSeq=N&limit=…` → rows ordered by seq. */
    suspend fun syncPull(sinceSeq: Long, limit: Int = 500): Result<List<LabSyncPullRow>> =
        withContext(Dispatchers.Default) {
            runCatching {
                val auth = deviceAuth()
                val resp = httpClient.get(edgeUrl("/sync/pull?sinceSeq=$sinceSeq&limit=$limit")) {
                    header(auth.first, auth.second)
                }
                val text = resp.bodyAsText()
                if (!resp.status.isSuccess()) syncFail(resp.status, text)
                json.parseToJsonElement(text).jsonObject["rows"]?.let {
                    json.decodeFromJsonElement(ListSerializer(LabSyncPullRow.serializer()), it)
                } ?: emptyList()
            }
        }

    /**
     * `GET admin-lab/platform-tests` (L3) — the linked business's lab-test
     * products, WHOLE list every time (the catalog is small; the sync engine
     * fingerprints it and applies only on change — the same whole-fingerprint
     * idiom the local test/panel push uses). Standalone licences 409 → the
     * shared [LabSyncDisabledException] path.
     */
    suspend fun platformTests(): Result<List<PlatformLabTest>> = withContext(Dispatchers.Default) {
        runCatching {
            val auth = deviceAuth()
            val resp = httpClient.get(edgeUrl("/platform-tests")) {
                header(auth.first, auth.second)
            }
            val text = resp.bodyAsText()
            if (!resp.status.isSuccess()) syncFail(resp.status, text)
            json.parseToJsonElement(text).jsonObject["tests"]?.let {
                json.decodeFromJsonElement(ListSerializer(PlatformLabTest.serializer()), it)
            } ?: emptyList()
        }
    }

    /**
     * `GET admin-lab/master-catalog` — the global master test catalog.
     *
     * Unlike every other sync call this one is allowed on a STANDALONE licence:
     * it returns no tenant data, only public clinical reference data. It is
     * invoked ONLY from an explicit user action, never from the sync loop, so
     * an offline lab still makes no background requests.
     */
    suspend fun masterCatalog(): Result<List<MasterCatalogTest>> = withContext(Dispatchers.Default) {
        runCatching {
            val auth = deviceAuth()
            val resp = httpClient.get(edgeUrl("/master-catalog")) {
                header(auth.first, auth.second)
            }
            val text = resp.bodyAsText()
            if (!resp.status.isSuccess()) syncFail(resp.status, text)
            json.parseToJsonElement(text).jsonObject["tests"]?.let {
                json.decodeFromJsonElement(ListSerializer(MasterCatalogTest.serializer()), it)
            } ?: emptyList()
        }
    }

    /**
     * `POST admin-lab/billing-series` {fy} — find-or-create this device's
     * invoice numbering series on the platform (per-seat L1/L2/L3, prefix LAB)
     * and return its anchor. Replaces BNMBilling's counter-pairing flow for lab
     * devices. Standalone licences 409 → [LabSyncDisabledException].
     */
    suspend fun billingSeries(fy: String): Result<LabBillingSeries> = withContext(Dispatchers.Default) {
        runCatching {
            val auth = deviceAuth()
            val resp = httpClient.post(edgeUrl("/billing-series")) {
                header(auth.first, auth.second)
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("fy", fy) })
            }
            val text = resp.bodyAsText()
            if (!resp.status.isSuccess()) syncFail(resp.status, text)
            json.decodeFromString(LabBillingSeries.serializer(), text)
        }
    }

    /** `GET admin-lab/emr-orders?sinceSeq=N` — clinic orders routed to this lab. */
    suspend fun emrOrders(sinceSeq: Long): Result<List<LabEmrOrder>> = withContext(Dispatchers.Default) {
        runCatching {
            val auth = deviceAuth()
            val resp = httpClient.get(edgeUrl("/emr-orders?sinceSeq=$sinceSeq")) {
                header(auth.first, auth.second)
            }
            val text = resp.bodyAsText()
            if (!resp.status.isSuccess()) syncFail(resp.status, text)
            json.parseToJsonElement(text).jsonObject["orders"]?.let {
                json.decodeFromJsonElement(ListSerializer(LabEmrOrder.serializer()), it)
            } ?: emptyList()
        }
    }

    /** `POST admin-lab/emr-orders/{id}/status` — acknowledge {lab_status, accession_no?}. */
    suspend fun emrOrderStatus(id: String, labStatus: String, accessionNo: String? = null): Result<Unit> =
        withContext(Dispatchers.Default) {
            runCatching {
                val auth = deviceAuth()
                val resp = httpClient.post(edgeUrl("/emr-orders/$id/status")) {
                    header(auth.first, auth.second)
                    contentType(ContentType.Application.Json)
                    setBody(
                        buildJsonObject {
                            put("lab_status", labStatus)
                            accessionNo?.let { put("accession_no", it) }
                        }.toString()
                    )
                }
                if (!resp.status.isSuccess()) syncFail(resp.status, resp.bodyAsText())
            }
        }

    /**
     * `POST admin-lab/emr-orders/{id}/result` — write the result back onto the
     * clinic's row. `resultFlag` is the APP code (N/L/H/A/CL/CH) — the server
     * translates to the EMR's word vocabulary at the bridge.
     */
    suspend fun emrOrderResult(
        id: String,
        resultValue: String,
        resultUnit: String? = null,
        referenceRange: String? = null,
        resultFlag: String? = null,
        resultNotes: String? = null,
    ): Result<Unit> = withContext(Dispatchers.Default) {
        runCatching {
            val auth = deviceAuth()
            val resp = httpClient.post(edgeUrl("/emr-orders/$id/result")) {
                header(auth.first, auth.second)
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("result_value", resultValue)
                        resultUnit?.let { put("result_unit", it) }
                        referenceRange?.let { put("reference_range", it) }
                        resultFlag?.let { put("result_flag", it) }
                        resultNotes?.let { put("result_notes", it) }
                    }.toString()
                )
            }
            if (!resp.status.isSuccess()) syncFail(resp.status, resp.bodyAsText())
        }
    }

    // ── Report publishing (the printed QR resolves to this) ──────────────────

    /**
     * `POST admin-lab/reports/publish` (device auth) — register [token] and
     * store what it opens: [report], the report snapshot v1 (see
     * `report/ReportSnapshot`) that the app.bnmapp.com page draws. No PDF is
     * kept on the server.
     *
     * [pdfBase64] is the PDF-era body, still accepted by the server for older
     * builds; this build sends the snapshot. At least one of the two is needed.
     *
     * NEVER on the printing path. The QR is printed from a locally-minted token
     * (see `ReportShare`) and this call drains later, so a lab with no
     * connectivity still hands the patient a correct sheet of paper.
     *
     * A standalone licence gets 409 standalone_edition, which surfaces as
     * [LabSyncDisabledException] — the caller stops queueing rather than
     * retrying forever, and those labs print no QR in the first place.
     */
    suspend fun publishReport(
        token: String,
        orderId: String,
        accessionNo: String,
        report: JsonObject? = null,
        pdfBase64: String? = null,
    ): Result<Unit> = withContext(Dispatchers.Default) {
        runCatching {
            require(report != null || pdfBase64 != null) { "Nothing to publish" }
            val auth = deviceAuth()
            val resp = httpClient.post(edgeUrl("/reports/publish")) {
                header(auth.first, auth.second)
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("token", token)
                        put("orderId", orderId)
                        put("accessionNo", accessionNo)
                        report?.let { put("report", it) }
                        pdfBase64?.let { put("pdfBase64", it) }
                    }.toString()
                )
            }
            if (!resp.status.isSuccess()) syncFail(resp.status, resp.bodyAsText())
        }
    }

    /**
     * Send a report to [to] (digits, country code first) as a WhatsApp
     * document, from the lab's own WhatsApp Business number.
     *
     * [pdfBase64] is the PDF this PC just rendered: the server hands the bytes
     * to Meta's media upload and sends that, keeping no copy — the server no
     * longer stores report PDFs, so without them only a report published by an
     * older PDF-era build can be sent (409 not_published otherwise).
     *
     * The failure modes are the interesting part and they come back as words
     * the screen can show: WhatsApp not connected for this business, the
     * 24-hour window closed (Meta refuses business-initiated messages outside
     * it), the link revoked. [idempotencyKey] makes a retry a no-op instead of a
     * second copy in the patient's chat.
     */
    suspend fun sendReportWhatsapp(
        token: String,
        to: String,
        filename: String,
        caption: String,
        idempotencyKey: String,
        pdfBase64: String? = null,
    ): Result<Unit> = withContext(Dispatchers.Default) {
        runCatching {
            val auth = deviceAuth()
            val resp = httpClient.post(edgeUrl("/reports/$token/whatsapp")) {
                header(auth.first, auth.second)
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("to", to)
                        put("filename", filename)
                        put("caption", caption)
                        put("idempotencyKey", idempotencyKey)
                        pdfBase64?.let { put("pdfBase64", it) }
                    }.toString()
                )
            }
            if (!resp.status.isSuccess()) syncFail(resp.status, resp.bodyAsText())
        }
    }

    /** `POST admin-lab/reports/{token}/revoke` — kill a link (wrong patient,
     *  corrected report). The local row is marked revoked first, so a dropped
     *  connection can never leave the lab thinking a live link is dead. */
    suspend fun revokeReport(token: String): Result<Unit> = withContext(Dispatchers.Default) {
        runCatching {
            val auth = deviceAuth()
            val resp = httpClient.post(edgeUrl("/reports/$token/revoke")) {
                header(auth.first, auth.second)
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
            if (!resp.status.isSuccess()) syncFail(resp.status, resp.bodyAsText())
        }
    }

    /** `POST admin-lab/devices/{id}/deactivate` (device auth; 422 if self). */
    suspend fun deactivateDevice(deviceRowId: String): Result<Unit> = withContext(Dispatchers.Default) {
        runCatching {
            val auth = deviceAuth()
            val resp = httpClient.post(edgeUrl("/devices/$deviceRowId/deactivate")) {
                header(auth.first, auth.second)
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
            if (!resp.status.isSuccess()) {
                val text = resp.bodyAsText()
                val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                error(
                    obj.strField("error") ?: if (resp.status == HttpStatusCode.UnprocessableEntity) {
                        "Use \"Deactivate this device\" to deactivate the device you're on"
                    } else {
                        "HTTP ${resp.status.value}: ${text.take(200)}"
                    }
                )
            }
        }
    }
}
