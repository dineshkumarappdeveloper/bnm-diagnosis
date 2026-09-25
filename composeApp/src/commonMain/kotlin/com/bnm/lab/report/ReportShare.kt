package com.bnm.lab.report

import com.bnm.lab.api.Constants
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The report link that the printed QR encodes.
 *
 * OFFLINE-FIRST, and that is the whole design constraint: the paper is printed
 * the moment the pathologist approves, often on a lab PC that has not seen the
 * internet in days. So the token is minted HERE, locally, written to
 * `lab_reports`, and the report DATA is published later by [ReportUploader]
 * whenever connectivity returns. Nothing on the printing path waits on a server.
 *
 * The token is the ONLY thing protecting a piece of PHI, so:
 *  - it is never derived from the accession number (`ACC-S1-00042` is per-seat
 *    sequential and trivially enumerable),
 *  - it is never derived from anything about the patient,
 *  - and it is minted from [Uuid.random], which is documented to use the
 *    platform's cryptographically secure RNG. `kotlin.random.Random` is NOT
 *    (it is a plain PRNG) and must not be used for this.
 */
object ReportShare {

    /** Printed under the code. A patient must be able to tell what it is. */
    const val CAPTION = "Scan to view this report"

    /** Second line, smaller: this is a private link, not a public page. */
    const val NOTE = "Private link - keep it to yourself"

    /**
     * A fresh 256-bit lowercase-hex capability token.
     *
     * Two UUIDs, dashes stripped: 64 hex characters carrying 244 bits of CSPRNG
     * entropy — comfortably past the 160 bits `admin-lab` demands before it will
     * register a token (it answers 400 weak_token below that).
     */
    @OptIn(ExperimentalUuidApi::class)
    fun newToken(): String =
        Uuid.random().toString().replace("-", "") + Uuid.random().toString().replace("-", "")

    /** Shape check for a token read back out of the DB or off the wire. */
    fun isWellFormed(token: String?): Boolean =
        token != null && token.length >= 40 && token.all { it in '0'..'9' || it in 'a'..'f' }

    /**
     * The link a QR printed NOW should encode. Two links exist, and which one
     * is correct is a question about the rollout, not a preference:
     *
     *  - [resolverUrl] — `admin-lab /reports/r/<token>`, the public resolver on
     *    the India ref. Every report BNM Lab has ever printed carries it, so it
     *    must live for as long as that paper does. It decides AT SCAN TIME what
     *    to do: 302 to the web page once that page is live, serve the report the
     *    old way until then. It therefore works in every state of the rollout.
     *  - [REPORT_PAGE_URL]`#<token>` — BNMClient's report page, which reads the
     *    token from the FRAGMENT, fetches the published snapshot from
     *    `admin-lab /reports/view` and draws the report (and its PDF) in the
     *    patient's browser. The token rides after `#` on purpose: a browser
     *    never sends the fragment, so the capability stays out of Cloudflare's
     *    logs and every Referer header. That is the better link, and the one to
     *    end up on.
     *
     * But paper cannot be recalled. A sheet printed with the page link on the
     * day before the page ships is dead forever — and worse than a 404, because
     * BNMClient's `_redirects` serves its SPA shell for any unknown path, so the
     * patient lands on a phone-OTP screen and the link merely LOOKS like it
     * worked. So the page link is printed only once the server has said the page
     * is live ([pageLive]: the heartbeat's `report_page_live`, persisted by
     * `LicenseManager` so an offline print still knows the last answer).
     *
     * Default false, deliberately: a caller that forgets prints the link that
     * works in every world.
     */
    fun resolveUrl(token: String, pageLive: Boolean = false): String =
        if (pageLive) "$REPORT_PAGE_URL#$token" else resolverUrl(token)

    /**
     * The permanent backend resolver. NEVER rename, retire, or move it off the
     * India ref: it is printed on paper that is already in patients' hands.
     */
    fun resolverUrl(token: String): String =
        "${Constants.EDGE_FUNCTIONS_BASE_URL}/admin-lab/reports/r/$token"

    /** Where [resolveUrl] points once the page is live. Printed paper is
     *  permanent: never change it to a page that is not live in production. */
    const val REPORT_PAGE_URL = "https://app.bnmapp.com/r/"

    /**
     * Build the printable QR block for [token], or null if the payload somehow
     * will not encode — in which case the report prints with no QR rather than
     * with a broken one.
     *
     * ECC level M (15% recovery): the code is printed at ~20 mm on paper that
     * gets folded into an envelope, and M is the level every consumer scanner is
     * tuned for. The page link (90 bytes) fits a 41-module symbol at M and the
     * resolver (138 bytes) a 49-module one; H would push either to the next size
     * up, i.e. smaller modules in the same 20 mm — worse, not better.
     */
    fun qrFor(token: String, pageLive: Boolean = false): ReportQr? {
        val url = resolveUrl(token, pageLive)
        val matrix = QrEncoder.encode(url, QrEncoder.ECC_M) ?: return null
        return ReportQr(url = url, matrix = matrix, caption = CAPTION, note = NOTE)
    }
}
