package com.bnm.lab.billing

/**
 * Which business a bill is filed under, and which bills a screen reads.
 *
 * The billing store (`ecom_entity`, `billing_outbox`, `counter_series`) is keyed
 * by business id, inherited from BNM Billing where every counter belongs to a
 * BNM business. An OFFLINE-edition lab has no business — its licence carries
 * none and the licence routes refuse one — and until this existed the app
 * resolved its business id to "", on which the bill bootstrap gave up: every
 * order on a fresh offline install registered with "bill not created", the
 * payment the desk had just taken was thrown away, and there was no revenue to
 * report anywhere.
 *
 * So bills with no business are filed under [OFFLINE_BUSINESS_ID], a key that
 * exists only on this computer:
 *  • nothing keyed by it is ever sent anywhere — the sync engine, the outbox
 *    drain and the series bootstrap all refuse it (see [isOffline] call sites);
 *  • every balance read includes it ([readScopes]), so a lab that later moves
 *    to the connected edition keeps its offline bills in Bills, on order rows
 *    and in the revenue dashboard, instead of watching them vanish when its
 *    business id changes.
 */
object BillingScope {
    /** Not a UUID and not a Mongo-style id, so it can never collide with a real business. */
    const val OFFLINE_BUSINESS_ID = "offline-lab"

    /** Where an old install's 'null'-business series is parked: history only, never issued from. */
    const val PARKED_SERIES_KEY = "offline-lab-legacy-series"

    fun isOffline(businessId: String?): Boolean = businessId == OFFLINE_BUSINESS_ID

    /**
     * The business a NEW bill is filed under. Same precedence the app has always
     * used — the business picked at activation, then the licence's own — with the
     * offline key replacing the empty string that used to break billing.
     */
    fun issuingBusinessId(selected: String?, licensed: String?): String =
        selected?.takeIf { it.isNotBlank() }
            ?: licensed?.takeIf { it.isNotBlank() }
            ?: OFFLINE_BUSINESS_ID

    /**
     * The series an OFFLINE computer numbers its bills under.
     *
     * Connected seats get distinct codes from the server (L1, L2, L3). Offline
     * computers have nobody to ask, and a hard-coded L1 on every one of them gave
     * a two-seat offline lab two different bills numbered LAB-L1-0001 in the same
     * financial year — against GST Rule 46's unique serial. So:
     *  • the code starts "LO" (Lab, Offline) and can never equal a server-issued
     *    L<n>, so bills issued offline stay unique after a move to the connected
     *    edition too, on whichever seat the server numbers from;
     *  • the rest comes from this install's device id, minted once and never
     *    rotated — for single-seat licences too. An offline computer never checks
     *    in, so after a week its seat can be handed to a replacement PC while the
     *    old one keeps billing, and a plain hardware swap mid-year starts on an
     *    empty database: a shared "LO1" would restart at 0001 beside bills
     *    already given to patients.
     */
    fun offlineSeriesCode(deviceId: String): String {
        var h = 0L
        for (ch in deviceId) h = (h * 31 + ch.code) and 0xFFFFFFFFL
        // Four base-36 characters: 1.6 million codes, readable on a bill.
        return "LO" + h.rem(36L * 36 * 36 * 36).toString(36).uppercase().padStart(4, '0')
    }

    /** Every key a balance read covers for [businessId]: its own bills plus the offline archive. */
    fun readScopes(businessId: String): List<String> =
        listOf(businessId, OFFLINE_BUSINESS_ID).filter { it.isNotBlank() }.distinct()
}
