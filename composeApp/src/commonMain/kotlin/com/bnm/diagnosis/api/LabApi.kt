package com.bnm.diagnosis.api

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
    ) : LabHeartbeatResult

    /** 403 license_inactive | device_revoked — block creating new work. */
    data class Blocked(val code: String, val message: String) : LabHeartbeatResult

    /** 401 — invalid device session; offline session semantics unchanged. */
    data object InvalidSession : LabHeartbeatResult
}

/** GET /devices response: all seats + which row is THIS device. */
data class LabDevicesInfo(val devices: List<LabSeatDevice>, val selfId: String?)

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

    private fun JsonObject?.strField(key: String): String? =
        runCatching { this?.get(key)?.jsonPrimitive?.content }.getOrNull()

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
