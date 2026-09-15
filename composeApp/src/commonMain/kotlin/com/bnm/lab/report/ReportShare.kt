package com.bnm.lab.report

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
     * The report page on BNMClient's site. It reads the token from the
     * FRAGMENT, fetches the published snapshot from `admin-lab /reports/view`
     * and draws the report (and its PDF) in the patient's browser — the server
     * keeps the data, never a PDF.
     *
     * The token rides after `#` on purpose: a browser never sends the fragment,
     * so it stays out of Cloudflare's logs and every Referer header.
     *
     * Paper printed before this link existed encodes the backend resolver
     * (`…/functions/v1/admin-lab/reports/r/<token>`). That route redirects here
     * and has to live for as long as those sheets do.
     */
    fun resolveUrl(token: String): String = "$REPORT_PAGE_URL#$token"

    /** Where [resolveUrl] points. Printed paper is permanent: never change it
     *  to a page that is not live in production. */
    const val REPORT_PAGE_URL = "https://app.bnmapp.com/r/"

    /**
     * Build the printable QR block for [token], or null if the payload somehow
     * will not encode — in which case the report prints with no QR rather than
     * with a broken one.
     *
     * ECC level M (15% recovery): the code is printed at ~20 mm on paper that
     * gets folded into an envelope, and M is the level every consumer scanner is
     * tuned for. The 90-byte link fits a 41-module symbol at M; H would push it
     * to a 53-module one, i.e. smaller modules in the same 20 mm — worse, not
     * better.
     */
    fun qrFor(token: String): ReportQr? {
        val url = resolveUrl(token)
        val matrix = QrEncoder.encode(url, QrEncoder.ECC_M) ?: return null
        return ReportQr(url = url, matrix = matrix, caption = CAPTION, note = NOTE)
    }
}
