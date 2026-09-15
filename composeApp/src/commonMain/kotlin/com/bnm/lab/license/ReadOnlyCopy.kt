package com.bnm.lab.license

/**
 * What a seat that may not start new work is told, per [ReadOnlyReason].
 *
 * Every variant says the same two things, because both are true and the lab
 * needs both: its records are all still there to read, print and export, and
 * here is what gets new orders going again. Pure so tests pin the promise.
 */
object ReadOnlyCopy {

    /** Headline for the banner and the refused-screen notice. */
    fun title(reason: ReadOnlyReason): String = when (reason) {
        ReadOnlyReason.EXPIRED -> "Subscription expired — this lab is read-only"
        ReadOnlyReason.DEACTIVATED -> "This device's license was deactivated — contact BNM"
        ReadOnlyReason.NOT_ACTIVATED -> "This computer has no active licence"
    }

    /** The sentence under the headline: what still works, and the way back. */
    fun detail(reason: ReadOnlyReason): String = when (reason) {
        ReadOnlyReason.EXPIRED ->
            "You can still sign in, view, print and export everything. Registering new orders and " +
                "raising new bills stay off until the subscription is renewed — then press " +
                "Check for renewal under License & devices (needs internet once)."
        ReadOnlyReason.DEACTIVATED ->
            "You can still view, print and export everything; creating new work is disabled."
        ReadOnlyReason.NOT_ACTIVATED ->
            "Existing records stay readable, printable and exportable. Activate a licence to register new orders."
    }

    /** One line beside the disabled "New order" button. */
    fun inline(reason: ReadOnlyReason): String = when (reason) {
        ReadOnlyReason.EXPIRED ->
            "Subscription expired — registering new orders is disabled until it is renewed. Everything else stays available."
        ReadOnlyReason.DEACTIVATED ->
            "License deactivated — registering new orders is disabled. Everything else stays available."
        ReadOnlyReason.NOT_ACTIVATED ->
            "No active licence — registering new orders is disabled. Everything else stays available."
    }
}
