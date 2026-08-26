package com.bnm.diagnosis.license

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random

/** Decoded claims of a `bnm-lab-license` ES256 JWS. */
data class LicenseClaims(
    val issuer: String?,     // iss — must be "bnm-lab-license"
    val licenseId: String?,  // lid
    val labName: String?,    // lab
    val mode: String?,       // "perpetual" | "subscription"
    val seats: Int?,
    val businessId: String?, // biz
    val edition: String?,    // ed — "connected" | "standalone" (absent = connected)
    val licExp: Long?,       // subscription license expiry (epoch seconds)
    val issuedAt: Long?,     // iat
    val exp: Long?,          // JWS exp (subscription only: expiry + 45d grace)
)

/** Snapshot of the persisted license, exposed as a StateFlow for the UI. */
data class LicenseState(
    val licensed: Boolean = false,
    val blocked: Boolean = false,
    val labName: String? = null,
    val mode: String? = null,
    val seats: Int = 0,
    val expiresAt: String? = null,
    val businessId: String? = null,
    val deviceRowId: String? = null,
    /** "connected" (ecosystem sync) or "standalone" (offline-only after the
     *  one-time online activation). Absent in legacy licences = connected. */
    val edition: String = LicenseManager.EDITION_CONNECTED,
) {
    /** Sold as offline-only: never sync, never nag about being offline. */
    val isStandalone: Boolean get() = edition == LicenseManager.EDITION_STANDALONE
}

/**
 * Persists + evaluates the device's BNM Diagnosis license (P2).
 *
 * - Storage: multiplatform-settings (license_jwt, device_token, device_row_id,
 *   lab name/mode/seats/expiry, a once-minted stable device_id, and the
 *   `license_blocked` heartbeat flag).
 * - `isLicensed()` = stored JWT present + ES256 signature valid (embedded
 *   public key) + issuer check + (perpetual: always; subscription:
 *   now <= lic_exp + 45d grace) using a monotonic clock guard — we persist
 *   max(now, lastSeenNow) so winding the system clock back can't extend a
 *   subscription.
 * - Perpetual licenses NEVER lock; lab data stays readable/exportable
 *   regardless of license state (clearing the license never touches lab data).
 */
class LicenseManager {
    private val settings: Settings = Settings()
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val KEY_JWT = "license_jwt"
        private const val KEY_DEVICE_TOKEN = "license_device_token"
        private const val KEY_DEVICE_ROW_ID = "license_device_row_id"
        private const val KEY_LAB_NAME = "license_lab_name"
        private const val KEY_MODE = "license_mode"
        private const val KEY_SEATS = "license_seats"
        private const val KEY_EXPIRES_AT = "license_expires_at"
        private const val KEY_BUSINESS_ID = "license_business_id"
        private const val KEY_DEVICE_ID = "license_device_id"
        private const val KEY_BLOCKED = "license_blocked"
        private const val KEY_LAST_SEEN_NOW = "license_last_seen_now"

        const val ISSUER = "bnm-lab-license"
        const val MODE_PERPETUAL = "perpetual"
        const val MODE_SUBSCRIPTION = "subscription"
        const val EDITION_CONNECTED = "connected"
        const val EDITION_STANDALONE = "standalone"

        /** Subscriptions keep working 45 days past lic_exp (offline grace). */
        private const val GRACE_SECONDS = 45L * 24 * 60 * 60
    }

    private val _state = MutableStateFlow(snapshot())
    val state: StateFlow<LicenseState> = _state.asStateFlow()

    /** Stable per-install device id — random UUID minted once, never rotated. */
    val deviceId: String
        get() = settings.getStringOrNull(KEY_DEVICE_ID) ?: uuid4().also {
            settings.putString(KEY_DEVICE_ID, it)
        }

    fun deviceToken(): String? = settings.getStringOrNull(KEY_DEVICE_TOKEN)
    fun licenseJwt(): String? = settings.getStringOrNull(KEY_JWT)

    /** Persist a successful `admin-lab/activate` response. Clears any block. */
    fun saveActivation(
        licenseJwt: String,
        deviceToken: String,
        deviceRowId: String,
        labName: String,
        mode: String,
        seats: Int,
        expiresAt: String?,
        businessId: String?,
    ) {
        settings.putString(KEY_JWT, licenseJwt)
        settings.putString(KEY_DEVICE_TOKEN, deviceToken)
        settings.putString(KEY_DEVICE_ROW_ID, deviceRowId)
        settings.putString(KEY_LAB_NAME, labName)
        settings.putString(KEY_MODE, mode)
        settings.putInt(KEY_SEATS, seats)
        if (expiresAt != null) settings.putString(KEY_EXPIRES_AT, expiresAt) else settings.remove(KEY_EXPIRES_AT)
        if (businessId != null) settings.putString(KEY_BUSINESS_ID, businessId) else settings.remove(KEY_BUSINESS_ID)
        settings.putBoolean(KEY_BLOCKED, false)
        touchClock()
        refresh()
    }

    /** Persist a fresh heartbeat: new JWT + latest license metadata; unblocks. */
    fun applyHeartbeat(licenseJwt: String?, mode: String?, seats: Int?, expiresAt: String?, labName: String?) {
        if (!licenseJwt.isNullOrBlank()) settings.putString(KEY_JWT, licenseJwt)
        if (!mode.isNullOrBlank()) settings.putString(KEY_MODE, mode)
        if (seats != null) settings.putInt(KEY_SEATS, seats)
        if (!expiresAt.isNullOrBlank()) settings.putString(KEY_EXPIRES_AT, expiresAt)
        if (!labName.isNullOrBlank()) settings.putString(KEY_LAB_NAME, labName)
        settings.putBoolean(KEY_BLOCKED, false)
        touchClock()
        refresh()
    }

    /** Heartbeat said 403 device_revoked / license_inactive → block new work. */
    fun setBlocked(blocked: Boolean) {
        settings.putBoolean(KEY_BLOCKED, blocked)
        refresh()
    }

    fun isBlocked(): Boolean = settings.getBoolean(KEY_BLOCKED, false)

    /**
     * Clear the local license (Deactivate this device). The stable device_id
     * is KEPT so re-activating reuses the same seat row; local lab data is
     * NEVER deleted here.
     */
    fun clearLicense() {
        settings.remove(KEY_JWT)
        settings.remove(KEY_DEVICE_TOKEN)
        settings.remove(KEY_DEVICE_ROW_ID)
        settings.remove(KEY_LAB_NAME)
        settings.remove(KEY_MODE)
        settings.remove(KEY_SEATS)
        settings.remove(KEY_EXPIRES_AT)
        settings.remove(KEY_BUSINESS_ID)
        settings.remove(KEY_BLOCKED)
        refresh()
    }

    /** Parse the stored (or given) JWT's payload claims — no verification. */
    fun claims(jwt: String? = licenseJwt()): LicenseClaims? {
        if (jwt.isNullOrBlank()) return null
        val payload = decodeJwtPayload(jwt) ?: return null
        return LicenseClaims(
            issuer = payload.str("iss"),
            licenseId = payload.str("lid"),
            labName = payload.str("lab"),
            mode = payload.str("mode"),
            seats = payload["seats"]?.jsonPrimitive?.intOrNull,
            businessId = payload.str("biz"),
            edition = payload.str("ed"),
            licExp = payload["lic_exp"]?.jsonPrimitive?.longOrNull,
            issuedAt = payload["iat"]?.jsonPrimitive?.longOrNull,
            exp = payload["exp"]?.jsonPrimitive?.longOrNull,
        )
    }

    /**
     * Full local license check: JWT present + signature valid + issuer ok +
     * (perpetual: always; subscription: within lic_exp + 45d grace, judged
     * against the monotonic-guarded clock).
     */
    fun isLicensed(): Boolean {
        val jwt = licenseJwt() ?: return false
        if (!verifyLicenseSignature(jwt)) return false
        val c = claims(jwt) ?: return false
        if (c.issuer != null && c.issuer != ISSUER) return false
        val mode = c.mode ?: settings.getStringOrNull(KEY_MODE) ?: MODE_PERPETUAL
        if (mode != MODE_SUBSCRIPTION) return true // perpetual never locks
        // Subscription: lic_exp + grace; fall back to the JWS exp (which the
        // server already mints as expiry + 45d grace).
        val gate = c.licExp?.plus(GRACE_SECONDS) ?: c.exp ?: return true
        return trustedNowSeconds() <= gate
    }

    /** Refresh the exposed state (e.g. after external settings changes). */
    fun refresh() {
        _state.value = snapshot()
    }

    private fun snapshot(): LicenseState = LicenseState(
        licensed = isLicensed(),
        blocked = isBlocked(),
        labName = settings.getStringOrNull(KEY_LAB_NAME),
        mode = settings.getStringOrNull(KEY_MODE),
        seats = settings.getInt(KEY_SEATS, 0),
        expiresAt = settings.getStringOrNull(KEY_EXPIRES_AT),
        businessId = settings.getStringOrNull(KEY_BUSINESS_ID),
        edition = claims()?.edition?.takeIf { it.isNotBlank() } ?: EDITION_CONNECTED,
        deviceRowId = settings.getStringOrNull(KEY_DEVICE_ROW_ID),
    )

    // ── Clock guard ──────────────────────────────────────────────────────────
    // Persist the highest wall-clock we've ever seen and judge expiry against
    // max(now, lastSeenNow) — turning the system clock back can't revive an
    // expired subscription.

    private fun trustedNowSeconds(): Long {
        val wall = kotlin.time.Clock.System.now().epochSeconds
        val guarded = maxOf(wall, settings.getLong(KEY_LAST_SEEN_NOW, 0L))
        settings.putLong(KEY_LAST_SEEN_NOW, guarded)
        return guarded
    }

    private fun touchClock() {
        trustedNowSeconds()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun JsonObject.str(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()

    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeJwtPayload(jwt: String): JsonObject? = try {
        val parts = jwt.split(".")
        if (parts.size < 2) null
        else {
            var p = parts[1].replace('-', '+').replace('_', '/')
            while (p.length % 4 != 0) p += "="
            json.parseToJsonElement(Base64.Default.decode(p).decodeToString()).jsonObject
        }
    } catch (e: Exception) {
        null
    }

    /** Random v4 UUID without platform APIs (stable device id mint). */
    private fun uuid4(): String {
        val b = ByteArray(16).also { Random.nextBytes(it) }
        b[6] = ((b[6].toInt() and 0x0F) or 0x40).toByte()
        b[8] = ((b[8].toInt() and 0x3F) or 0x80).toByte()
        val hex = b.joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
            "${hex.substring(16, 20)}-${hex.substring(20)}"
    }
}
