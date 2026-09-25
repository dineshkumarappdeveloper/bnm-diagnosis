package com.bnm.lab.license

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
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
import com.bnm.lab.staff.sha256Hex

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
    val graceSeconds: Long?, // gr — offline grace past licExp; absent = 45d default
    val issuedAt: Long?,     // iat
    val exp: Long?,          // JWS exp (subscription only: expiry + the signed grace)
)

/**
 * Where the licence stored on this computer stands. The ONE answer behind the
 * entry screen, [LicenseManager.isLicensed] and the subscription warning, so
 * the three can never disagree about whether a lab has lapsed.
 */
enum class LicenseStanding {
    /** No genuine licence here: never activated, deactivated, or the stored
     *  token fails verification. The only standing that shows Activation. */
    NONE,
    /** Genuine and in term: perpetual, or a subscription within lic_exp + gr. */
    CURRENT,
    /** A genuine subscription past lic_exp + gr. The lab keeps its records —
     *  sign-in, reading, printing and export all work — but cannot start new
     *  work until a renewed licence arrives. */
    LAPSED,
}

/** Why this seat may not start new work; null when it may. */
enum class ReadOnlyReason { NOT_ACTIVATED, EXPIRED, DEACTIVATED }

/** Snapshot of the persisted license, exposed as a StateFlow for the UI. */
data class LicenseState(
    /** Genuine and in term ([LicenseStanding.CURRENT]). */
    val licensed: Boolean = false,
    /** Genuine but past lic_exp + gr ([LicenseStanding.LAPSED]). */
    val lapsed: Boolean = false,
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

    /** A genuine licence is on this computer — in term, lapsed, or blocked by
     *  BNM. Decides the entry screen: only a computer WITHOUT one activates. */
    val activated: Boolean get() = licensed || lapsed

    /**
     * Why new work (registering orders, raising bills) is refused; null when it
     * is allowed. A block from BNM outranks a lapse: renewing does not undo a
     * revoked device, so "contact BNM" is the message that gets the lab moving.
     */
    val readOnlyReason: ReadOnlyReason?
        get() = when {
            blocked -> ReadOnlyReason.DEACTIVATED
            licensed -> null
            lapsed -> ReadOnlyReason.EXPIRED
            else -> ReadOnlyReason.NOT_ACTIVATED
        }

    val canStartNewWork: Boolean get() = readOnlyReason == null
}

/**
 * Persists + evaluates the device's BNM Lab license (P2).
 *
 * - Storage: multiplatform-settings (license_jwt, device_token, device_row_id,
 *   lab name/mode/seats/expiry, a once-minted stable device_id, and the
 *   `license_blocked` heartbeat flag).
 * - [standing] = stored JWT present + ES256 signature valid (embedded public
 *   key) + issuer check → [LicenseStanding.NONE] otherwise; then perpetual is
 *   always CURRENT, and a subscription is CURRENT while now <= lic_exp + the
 *   SIGNED grace (`gr`; 45 days only for tokens minted before the claim) and
 *   LAPSED after. "Now" is the monotonic-guarded clock — we persist
 *   max(now, lastSeenNow) so winding the system clock back can't extend a
 *   subscription. `isLicensed()` = CURRENT; `isActivated()` = not NONE.
 * - Perpetual licenses NEVER lock. A LAPSED subscription is READ-ONLY, not
 *   locked out: it still reaches staff sign-in and every record stays
 *   readable/printable/exportable — only starting new work is refused.
 *   Clearing the license never touches lab data.
 *
 * The internal constructor is the test seam: an in-memory settings store (on
 * JVM the no-arg store is the operator's REAL preferences), a signature
 * verdict, and a wall clock. Production always uses the no-arg constructor.
 */
class LicenseManager internal constructor(
    private val settings: Settings,
    private val verifySignature: (String) -> Boolean,
    private val wallClockSeconds: () -> Long,
) {
    constructor() : this(
        Settings(),
        ::verifyLicenseSignature,
        { kotlin.time.Clock.System.now().epochSeconds },
    )

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
private const val KEY_LICENSE_FP = "lab_license_fp"
        private const val KEY_DEVICE_ID = "license_device_id"
        private const val KEY_SEAT_NO = "license_seat_no"
        private const val KEY_BLOCKED = "license_blocked"
        private const val KEY_LAST_SEEN_NOW = "license_last_seen_now"

        const val ISSUER = "bnm-lab-license"
        const val MODE_PERPETUAL = "perpetual"
        const val MODE_SUBSCRIPTION = "subscription"
        const val EDITION_CONNECTED = "connected"
        const val EDITION_STANDALONE = "standalone"

        /**
         * Pure decision core behind [isDifferentTenant], split out so it can be
         * tested without a Settings store — on JVM that is the REAL user
         * preferences, and a test that wrote to it would clobber the operator's
         * actual licence.
         */
        internal fun differentTenant(
            storedFingerprint: String?,
            keyFingerprint: String,
            hasLocalData: Boolean,
        ): Boolean {
            val current = storedFingerprint ?: return hasLocalData
            return current != keyFingerprint
        }

        /**
         * After a restore from a backup pendrive: is the token `/activate` just
         * returned the licence the restored records came with? The fingerprint
         * cannot say — a RE-ISSUED key hashes differently for the very same
         * licence, and that is the day the backup's recovery code exists for —
         * but the licence id inside both tokens can. Unknown on either side is
         * never "same".
         */
        internal fun sameLicenceId(restoredLid: String?, activatedLid: String?): Boolean =
            !restoredLid.isNullOrBlank() && restoredLid == activatedLid

        /**
         * Default offline grace past lic_exp. Only a fallback now: the server
         * signs the actual allowance into the `gr` claim, because a fixed 45
         * days is right for a lapsed annual subscription and absurd for a
         * 24-hour trial key (it would outlive the term 45x).
         */
        internal const val DEFAULT_GRACE_SECONDS = 45L * 24 * 60 * 60

        /** The grace a token grants: its signed `gr`, or the default for tokens
         *  minted before the claim existed (a negative value is malformed). */
        internal fun graceSecondsOf(claims: LicenseClaims?): Long =
            claims?.graceSeconds?.takeIf { it >= 0 } ?: DEFAULT_GRACE_SECONDS

        /**
         * The last second a subscription token is in term: lic_exp + its grace,
         * else the JWS exp (which the server mints as that same sum). Null when
         * the token carries no expiry at all — such a licence cannot lapse.
         */
        internal fun termEndSeconds(claims: LicenseClaims): Long? =
            claims.licExp?.plus(graceSecondsOf(claims)) ?: claims.exp

        /**
         * Pure decision core behind [standing] — no Settings, no crypto, no
         * clock, so every branch is testable.
         *
         * [storedMode] backs up a token without a `mode` claim; [nowSeconds]
         * must already be the monotonic-guarded clock.
         */
        internal fun standingOf(
            claims: LicenseClaims?,
            signatureValid: Boolean,
            storedMode: String?,
            nowSeconds: Long,
        ): LicenseStanding {
            if (claims == null || !signatureValid) return LicenseStanding.NONE
            if (claims.issuer != null && claims.issuer != ISSUER) return LicenseStanding.NONE
            val mode = claims.mode ?: storedMode ?: MODE_PERPETUAL
            if (mode != MODE_SUBSCRIPTION) return LicenseStanding.CURRENT // perpetual never locks
            val end = termEndSeconds(claims) ?: return LicenseStanding.CURRENT
            return if (nowSeconds <= end) LicenseStanding.CURRENT else LicenseStanding.LAPSED
        }
    }

    private val _state = MutableStateFlow(snapshot())
    val state: StateFlow<LicenseState> = _state.asStateFlow()

    /** Stable per-install device id — random UUID minted once, never rotated. */
    val deviceId: String
        get() = settings.getStringOrNull(KEY_DEVICE_ID) ?: uuid4().also {
            settings.putString(KEY_DEVICE_ID, it)
        }

    /**
     * This device's seat number under its licence, as admin-lab assigned it:
     * once per device row, never reused. Null until a server that assigns one
     * answers /activate or /heartbeat. Connected seats number accessions
     * `S<n>` from it ([com.bnm.lab.lab.AccessionSeat]).
     */
    val seatNo: Int? get() = settings.getIntOrNull(KEY_SEAT_NO)?.takeIf { it > 0 }

    fun deviceToken(): String? = settings.getStringOrNull(KEY_DEVICE_TOKEN)
    fun licenseJwt(): String? = settings.getStringOrNull(KEY_JWT)

    /** Persist a successful `admin-lab/activate` response. Clears any block. */
    /**
     * Fingerprint of the licence this install belongs to — `sha256(KEY)`, the same
     * identity the server stores. Null on a never-activated device.
     *
     * Keyed on the LICENCE, not the business or the lab name: a standalone licence
     * has no business id, and two labs can share a name. Device-row id is wrong
     * too — it changes on a seat replacement, which is the same lab.
     */
    val licenseFingerprint: String? get() = settings.getStringOrNull(KEY_LICENSE_FP)

    /**
     * True when [key] belongs to a DIFFERENT licence than the one this install is
     * already carrying data for — i.e. activating it would put another lab's
     * records under a new name, and sync them into the new tenant.
     *
     * False for re-activating the same licence (refresh, seat replacement,
     * re-install against the same lab), so the common case never prompts.
     *
     * [hasLocalData] settles the case with NO recorded fingerprint. Absent is
     * not the same as "fresh device": an install activated before fingerprints
     * were recorded — or one whose database arrived through the BNMDiagnosis
     * data-dir migration — holds a full lab yet has nothing to compare against.
     * Reading that as "fresh" let a different licence take over another lab's
     * records silently, which is the very leak this guard exists to stop. With
     * no fingerprint we therefore defer to whether there is anything to lose.
     */
    fun isDifferentTenant(key: String, hasLocalData: Boolean = false): Boolean =
        differentTenant(licenseFingerprint, fingerprintOf(key), hasLocalData)

    /** sha256 of the normalised key — matches how the server hashes it. */
    fun fingerprintOf(key: String): String = sha256Hex(key.trim().uppercase())

    fun saveActivation(
        licenseJwt: String,
        deviceToken: String,
        deviceRowId: String,
        labName: String,
        mode: String,
        seats: Int,
        expiresAt: String?,
        businessId: String?,
        /** sha256 of the key just activated — see [licenseFingerprint]. Null keeps
         *  the previous value, so a heartbeat-style refresh cannot erase it. */
        licenseFingerprint: String? = null,
        /** admin-lab's `seat_no` for this device row; absent = the server assigns none. */
        seatNo: Int? = null,
    ) {
        if (licenseFingerprint != null) settings.putString(KEY_LICENSE_FP, licenseFingerprint)
        // Belongs to the device row this activation returned: never carry a
        // previous licence's number over to a new one.
        if (seatNo != null) settings.putInt(KEY_SEAT_NO, seatNo) else settings.remove(KEY_SEAT_NO)
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
    fun applyHeartbeat(licenseJwt: String?, mode: String?, seats: Int?, expiresAt: String?, labName: String?, seatNo: Int? = null) {
        if (seatNo != null) settings.putInt(KEY_SEAT_NO, seatNo)
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
        settings.remove(KEY_SEAT_NO)
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
            graceSeconds = payload["gr"]?.jsonPrimitive?.longOrNull,
            issuedAt = payload["iat"]?.jsonPrimitive?.longOrNull,
            exp = payload["exp"]?.jsonPrimitive?.longOrNull,
        )
    }

    /**
     * Full local license check: JWT present + signature valid + issuer ok, then
     * the term (perpetual: always; subscription: lic_exp + the signed grace,
     * judged against the monotonic-guarded clock). See [standingOf].
     */
    fun standing(): LicenseStanding {
        val jwt = licenseJwt() ?: return LicenseStanding.NONE
        return standingOf(
            claims = claims(jwt),
            signatureValid = verifySignature(jwt),
            storedMode = settings.getStringOrNull(KEY_MODE),
            nowSeconds = trustedNowSeconds(),
        )
    }

    /** In term: may start new work (unless BNM has also [isBlocked] it). */
    fun isLicensed(): Boolean = standing() == LicenseStanding.CURRENT

    /** A genuine licence is on this computer, lapsed or not — decides whether
     *  the app opens on Activation (false) or on staff sign-in (true). */
    fun isActivated(): Boolean = standing() != LicenseStanding.NONE

    /** Refresh the exposed state (e.g. after external settings changes). */
    fun refresh() {
        _state.value = snapshot()
    }

    private fun snapshot(): LicenseState {
        val standing = standing()
        return LicenseState(
            licensed = standing == LicenseStanding.CURRENT,
            lapsed = standing == LicenseStanding.LAPSED,
            blocked = isBlocked(),
            labName = settings.getStringOrNull(KEY_LAB_NAME),
            mode = settings.getStringOrNull(KEY_MODE),
            seats = settings.getInt(KEY_SEATS, 0),
            expiresAt = settings.getStringOrNull(KEY_EXPIRES_AT),
            // Both of these ride in the SIGNED licence token, so a licence check
            // is all a lab needs to move from the offline edition to the connected
            // one: the new token carries the new edition AND the business it now
            // syncs with. The stored value is the fallback for tokens minted
            // before `biz` was a claim.
            businessId = claims()?.businessId?.takeIf { it.isNotBlank() }
                ?: settings.getStringOrNull(KEY_BUSINESS_ID),
            edition = claims()?.edition?.takeIf { it.isNotBlank() } ?: EDITION_CONNECTED,
            deviceRowId = settings.getStringOrNull(KEY_DEVICE_ROW_ID),
        )
    }

    // ── Clock guard ──────────────────────────────────────────────────────────
    // Persist the highest wall-clock we've ever seen and judge expiry against
    // max(now, lastSeenNow) — turning the system clock back can't revive an
    // expired subscription.

    /** Also what the subscription warning reads, so its day counts cannot be
     *  revived by the same clock trick the lock refuses. */
    internal fun trustedNowSeconds(): Long {
        val wall = wallClockSeconds()
        val guarded = maxOf(wall, settings.getLong(KEY_LAST_SEEN_NOW, 0L))
        settings.putLong(KEY_LAST_SEEN_NOW, guarded)
        return guarded
    }

    private fun touchClock() {
        trustedNowSeconds()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun JsonObject.str(key: String): String? = claimString(this, key)

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

/**
 * A string claim, or null when it is absent OR JSON `null`.
 *
 * `jsonPrimitive.content` of JSON null is the four-letter string "null". The
 * licence server writes `biz: null` into every offline-edition licence, so every
 * offline install read its business id as "null": bills were filed under a
 * phantom business called "null", the invoice-series bootstrap asked the server
 * about it, and the first-visit bill sync called the server with it — from an
 * edition whose promise is that nothing leaves the computer.
 */
internal fun claimString(payload: JsonObject, key: String): String? =
    runCatching { (payload[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content }.getOrNull()
