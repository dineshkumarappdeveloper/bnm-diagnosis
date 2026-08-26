package com.bnm.diagnosis.report

import com.bnm.diagnosis.api.Constants
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The report-download link that the printed QR encodes.
 *
 * OFFLINE-FIRST, and that is the whole design constraint: the paper is printed
 * the moment the pathologist approves, often on a lab PC that has not seen the
 * internet in days. So the token is minted HERE, locally, written to
 * `lab_reports`, and the PDF is uploaded later by [ReportUploader] whenever
 * connectivity returns. Nothing on the printing path waits on a server.
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
    const val CAPTION = "Scan to download this report"

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
     * The PUBLIC resolver `admin-lab` serves: it 302s to a 60-second signed URL
     * for the private `lab-reports` object, and answers an identical 404 for
     * unknown / malformed / revoked / expired so the endpoint is not an
     * enumeration oracle.
     */
    fun resolveUrl(token: String): String =
        "${Constants.EDGE_FUNCTIONS_BASE_URL}/admin-lab/reports/r/$token"

    /**
     * Build the printable QR block for [token], or null if the payload somehow
     * will not encode — in which case the report prints with no QR rather than
     * with a broken one.
     *
     * ECC level M (15% recovery): the code is printed at ~20 mm on paper that
     * gets folded into an envelope, and M is the level every consumer scanner is
     * tuned for. H would push the payload from a 49-module symbol to a 65-module
     * one, i.e. smaller modules in the same 20 mm — worse, not better.
     */
    fun qrFor(token: String): ReportQr? {
        val url = resolveUrl(token)
        val matrix = QrEncoder.encode(url, QrEncoder.ECC_M) ?: return null
        return ReportQr(url = url, matrix = matrix, caption = CAPTION, note = NOTE)
    }
}
