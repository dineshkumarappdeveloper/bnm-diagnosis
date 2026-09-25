package com.bnm.lab.license

/**
 * What an OFFLINE (standalone) licence is allowed to do on the network.
 *
 * The edition is decided when the licence is minted and signed into every
 * licence JWT — the lab cannot flip it, which is exactly what a customer who
 * says "nothing leaves this building" is buying. This object is the one place
 * that answers "may this run?", so a new background loop has to come here to
 * get permission rather than quietly starting up.
 *
 * ACTIVATION IS THE ONE EXCEPTION and it is not listed here: it is how the
 * licence arrives in the first place, it happens once, and the operator starts
 * it by typing a key. Everything that would happen on its own afterwards is
 * refused below.
 *
 * These are the CLIENT's manners. The server refuses the same calls anyway
 * (`admin-lab` answers 409 standalone_edition), so a tampered build gains
 * nothing — but a lab must be able to watch its own firewall and see silence,
 * and that is what these gates give.
 */
object OfflinePolicy {

    /** Lab data push/pull, EMR inbox, platform catalog — the sync sweeps. */
    fun allowsSync(standalone: Boolean): Boolean = !standalone

    /** Uploading the report PDF so a printed QR resolves. */
    fun allowsReportUpload(standalone: Boolean): Boolean = !standalone

    /**
     * The periodic licence heartbeat (refreshes the JWT, learns of revocation).
     * A perpetual offline licence never locks, so nothing is lost by silence;
     * the operator can still check on demand from Settings.
     */
    fun allowsHeartbeat(standalone: Boolean): Boolean = !standalone

    /** Draining the billing outbox and pulling the billing directories. */
    fun allowsBillingSync(standalone: Boolean): Boolean = !standalone

    /** Asking GitHub whether a newer build exists. */
    fun allowsUpdateCheck(standalone: Boolean): Boolean = !standalone

    /** Sending a report through the WhatsApp Business API (the server refuses it too). */
    fun allowsWhatsappApi(standalone: Boolean): Boolean = !standalone

    /**
     * Pulling the GLOBAL master test catalog (`lab_test_catalog`) — public
     * clinical reference data. No tenant data travels in either direction.
     *
     * This is the one call an offline licence may make AFTER activation, and it
     * belongs to the same category as activation: the operator starts it by
     * pressing a button. It never runs on a timer, at startup, or as part of a
     * sync sweep — hence [userInitiated], which is the whole gate.
     *
     * Why an offline lab needs it: with no business there are no `products` to
     * sync, so without this its catalog is the bundled ~40-test starter set
     * forever, with the national set permanently out of reach.
     *
     * What a standalone customer is promised is that their patients' data never
     * leaves the building and that nothing happens behind their back — not that
     * the machine may never fetch a public list of tests when asked to.
     */
    fun allowsMasterCatalogPull(userInitiated: Boolean): Boolean = userInitiated

    /**
     * A remote support session: the app connects OUT to BNM's relay so an
     * engineer can read this computer's diagnostics and fix analyzer links.
     *
     * Same category as the catalog pull — the OWNER starts it, in person, for a
     * fixed number of hours, after reading what it allows. It never starts on
     * its own, and it ends the moment the owner presses End or the time is up.
     * Patient records, results and analyzer data stay out of it unless the
     * owner ticks them on the consent screen.
     */
    fun allowsRemoteSupport(userInitiated: Boolean): Boolean = userInitiated

    /** One line for the operator: what this licence does with the network. */
    fun summary(standalone: Boolean): String = if (standalone) {
        "Offline edition — after activation, nothing leaves this computer: no sync, " +
            "no report upload, no QR link, no licence check, no update check. The only " +
            "network calls are fetching the master test catalog, only when you ask for it, " +
            "and a remote support session, only while you have started one."
    } else {
        "Connected edition — results sync to BNM, report QR links resolve, and updates are checked."
    }
}
