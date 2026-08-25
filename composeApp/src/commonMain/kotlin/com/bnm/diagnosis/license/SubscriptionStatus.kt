package com.bnm.diagnosis.license

/**
 * Subscription lifecycle for the licence card + renewal nag (P4).
 *
 * Perpetual licences never enter any of the warning states — they are sold
 * outright and MUST keep working forever offline. Subscriptions get a long
 * runway: a soft "renew soon" notice inside [EXPIRING_WINDOW_DAYS] of expiry,
 * then a 45-day offline GRACE in which everything still works (the lab is
 * never stopped mid-day by a billing date), and only past that does
 * [LicenseManager.isLicensed] fail — and even then lab data stays readable
 * and exportable.
 */
enum class SubscriptionState { PERPETUAL, ACTIVE, EXPIRING_SOON, IN_GRACE, EXPIRED }

data class SubscriptionStatus(
    val state: SubscriptionState,
    /** Whole days until expiry (negative once expired); null when unknown. */
    val daysToExpiry: Long? = null,
    /** Days left in the post-expiry grace window (only when IN_GRACE). */
    val graceDaysLeft: Long? = null,
    /** Expiry as an ISO instant string, for display. */
    val expiresAt: String? = null,
) {
    val isSubscription: Boolean get() = state != SubscriptionState.PERPETUAL

    /** One-line operator message; null when nothing needs saying. */
    val notice: String?
        get() = when (state) {
            SubscriptionState.PERPETUAL, SubscriptionState.ACTIVE -> null
            SubscriptionState.EXPIRING_SOON ->
                "Subscription renews in ${daysToExpiry ?: 0} day${plural(daysToExpiry)}"
            SubscriptionState.IN_GRACE ->
                "Subscription expired — ${graceDaysLeft ?: 0} day${plural(graceDaysLeft)} of grace left. Renew to keep registering orders."
            SubscriptionState.EXPIRED ->
                "Subscription expired. Existing records stay readable and printable; renew to register new orders."
        }

    private fun plural(n: Long?): String = if (n == 1L) "" else "s"
}

/** Soft-notice window before expiry. */
const val EXPIRING_WINDOW_DAYS = 14L
private const val GRACE_DAYS = 45L
private const val DAY_SECONDS = 24L * 60 * 60

/**
 * Evaluate the stored licence. Reads the expiry from (in order) the signed
 * `lic_exp` claim — tolerating BOTH epoch-seconds and ISO-string forms, since
 * early builds signed it as a timestamp string — then the persisted
 * `expires_at`, then the JWS `exp` (which the server mints as expiry + grace).
 */
fun LicenseManager.subscriptionStatus(): SubscriptionStatus {
    val s = state.value
    val c = claims()
    val mode = c?.mode ?: s.mode
    if (mode != LicenseManager.MODE_SUBSCRIPTION) {
        return SubscriptionStatus(SubscriptionState.PERPETUAL)
    }

    val expirySeconds = c?.licExp
        ?: parseIsoSeconds(s.expiresAt)
        ?: c?.exp?.minus(GRACE_DAYS * DAY_SECONDS)
        ?: return SubscriptionStatus(SubscriptionState.ACTIVE, expiresAt = s.expiresAt)

    val now = kotlin.time.Clock.System.now().epochSeconds
    val secondsLeft = expirySeconds - now
    val days = floorDivDays(secondsLeft)

    val state = when {
        secondsLeft > EXPIRING_WINDOW_DAYS * DAY_SECONDS -> SubscriptionState.ACTIVE
        secondsLeft > 0 -> SubscriptionState.EXPIRING_SOON
        secondsLeft > -(GRACE_DAYS * DAY_SECONDS) -> SubscriptionState.IN_GRACE
        else -> SubscriptionState.EXPIRED
    }
    val graceLeft = if (state == SubscriptionState.IN_GRACE) {
        floorDivDays(secondsLeft + GRACE_DAYS * DAY_SECONDS)
    } else null

    return SubscriptionStatus(
        state = state,
        daysToExpiry = days,
        graceDaysLeft = graceLeft,
        expiresAt = s.expiresAt,
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
