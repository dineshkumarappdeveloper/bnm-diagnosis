package com.bnm.lab.license

/**
 * Subscription lifecycle for the licence card + renewal nag (P4).
 *
 * Perpetual licences never enter any of the warning states — they are sold
 * outright and MUST keep working forever offline. Subscriptions get a soft
 * "renew soon" notice inside [EXPIRING_WINDOW_DAYS] of expiry, then the offline
 * GRACE the server signed into the licence (`gr` — 45 days for an annual
 * subscription, 0 for a trial key) in which everything still works, and only
 * past that does the lab go READ-ONLY ([LicenseStanding.LAPSED]): staff still
 * sign in and every record stays readable, printable and exportable; only
 * registering new orders and raising new bills is refused until renewal.
 *
 * EXPIRED here is exactly LAPSED there — both come from the same term end and
 * the same guarded clock — so the notice can never promise grace to a lab that
 * is already read-only.
 */
enum class SubscriptionState { PERPETUAL, ACTIVE, EXPIRING_SOON, IN_GRACE, EXPIRED }

data class SubscriptionStatus(
    val state: SubscriptionState,
    /** Whole days until expiry (negative once expired); null when unknown. */
    val daysToExpiry: Long? = null,
    /** Whole days left in the post-expiry grace window (only when IN_GRACE). */
    val graceDaysLeft: Long? = null,
    /** Expiry as an ISO instant string, for display. */
    val expiresAt: String? = null,
    /** The grace this licence grants past expiry, in seconds (0 = hard stop). */
    val graceSeconds: Long = LicenseManager.DEFAULT_GRACE_SECONDS,
) {
    val isSubscription: Boolean get() = state != SubscriptionState.PERPETUAL

    /** In grace or read-only — shown as danger rather than a warning. */
    val urgent: Boolean
        get() = state == SubscriptionState.IN_GRACE || state == SubscriptionState.EXPIRED

    /** One-line operator message; null when nothing needs saying. */
    val notice: String?
        get() = when (state) {
            SubscriptionState.PERPETUAL, SubscriptionState.ACTIVE -> null
            // No grace: expiry IS the moment new orders stop, so say so rather
            // than implying a runway the licence does not have.
            SubscriptionState.EXPIRING_SOON -> if (graceSeconds == 0L) {
                "Subscription ends ${inDays(daysToExpiry)} — new orders stop then. Renew to keep registering orders."
            } else {
                "Subscription renews ${inDays(daysToExpiry)}"
            }
            SubscriptionState.IN_GRACE ->
                "Subscription expired — ${graceLeftLabel()} of grace left. Renew to keep registering orders."
            SubscriptionState.EXPIRED ->
                "Subscription expired. Existing records stay readable and printable; renew to register new orders."
        }

    private fun inDays(days: Long?): String {
        val n = days ?: 0L
        return if (n < 1) "within a day" else "in $n day${plural(n)}"
    }

    private fun graceLeftLabel(): String {
        val n = graceDaysLeft ?: 0L
        return if (n < 1) "less than a day" else "$n day${plural(n)}"
    }

    private fun plural(n: Long): String = if (n == 1L) "" else "s"
}

/** Soft-notice window before expiry. */
const val EXPIRING_WINDOW_DAYS = 14L
private const val DAY_SECONDS = 24L * 60 * 60

/**
 * Evaluate the stored licence against the monotonic-guarded clock — the same
 * clock [LicenseManager.isLicensed] judges by, so winding the system clock back
 * revives neither the lock nor the grace countdown.
 */
fun LicenseManager.subscriptionStatus(): SubscriptionStatus = subscriptionStatusOf(
    claims = claims(),
    storedMode = state.value.mode,
    storedExpiresAt = state.value.expiresAt,
    nowSeconds = trustedNowSeconds(),
)

/**
 * Pure core of [subscriptionStatus].
 *
 * Expiry comes from the SIGNED token first — `lic_exp`, then the JWS `exp`
 * minus the signed grace (the server mints `exp` as expiry + grace) — and only
 * then from the persisted `expires_at` (tolerating a bare date), which is all
 * early builds had when they signed `lic_exp` as a timestamp string. Reading the
 * signed numbers first keeps the warning on the same arithmetic as the lock.
 */
internal fun subscriptionStatusOf(
    claims: LicenseClaims?,
    storedMode: String?,
    storedExpiresAt: String?,
    nowSeconds: Long,
): SubscriptionStatus {
    val mode = claims?.mode ?: storedMode
    if (mode != LicenseManager.MODE_SUBSCRIPTION) {
        return SubscriptionStatus(SubscriptionState.PERPETUAL)
    }
    val grace = LicenseManager.graceSecondsOf(claims)

    val expirySeconds = claims?.licExp
        ?: claims?.exp?.minus(grace)
        ?: parseIsoSeconds(storedExpiresAt)
        ?: return SubscriptionStatus(SubscriptionState.ACTIVE, expiresAt = storedExpiresAt, graceSeconds = grace)

    val secondsLeft = expirySeconds - nowSeconds
    // In term through the last second of grace, exactly as LicenseManager.standingOf.
    val graceSecondsLeft = secondsLeft + grace

    val state = when {
        graceSecondsLeft < 0 -> SubscriptionState.EXPIRED
        secondsLeft > EXPIRING_WINDOW_DAYS * DAY_SECONDS -> SubscriptionState.ACTIVE
        secondsLeft > 0 -> SubscriptionState.EXPIRING_SOON
        else -> SubscriptionState.IN_GRACE
    }

    return SubscriptionStatus(
        state = state,
        daysToExpiry = floorDivDays(secondsLeft),
        graceDaysLeft = if (state == SubscriptionState.IN_GRACE) floorDivDays(graceSecondsLeft) else null,
        expiresAt = storedExpiresAt,
        graceSeconds = grace,
    )
}

private fun floorDivDays(seconds: Long): Long =
    if (seconds >= 0) seconds / DAY_SECONDS else -((-seconds + DAY_SECONDS - 1) / DAY_SECONDS)

/** ISO instant ("…Z"/"+00:00") or a bare date ("2027-03-31") → epoch seconds. */
private fun parseIsoSeconds(iso: String?): Long? {
    if (iso.isNullOrBlank()) return null
    val text = if (iso.length == 10 && iso[4] == '-') "${iso}T00:00:00Z" else iso
    return runCatching { kotlin.time.Instant.parse(text).epochSeconds }.getOrNull()
}
