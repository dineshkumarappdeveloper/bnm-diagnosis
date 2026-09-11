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

    /** One line for the operator: what this licence does with the network. */
    fun summary(standalone: Boolean): String = if (standalone) {
        "Offline edition — after activation, nothing leaves this computer: no sync, " +
            "no report upload, no QR link, no licence check, no update check."
    } else {
        "Connected edition — results sync to BNM, report QR links resolve, and updates are checked."
    }
}
