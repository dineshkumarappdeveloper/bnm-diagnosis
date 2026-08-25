package com.bnm.diagnosis.license

/**
 * BNM Diagnosis license public key — P-256 (secp256r1) coordinates embedded
 * verbatim from `docs/license-public-key.jwk.json`. The matching private key
 * lives ONLY on the server (admin-lab edge fn); this app can verify license
 * JWTs but never mint them.
 */
object LicensePublicKey {
    const val CRV = "P-256"
    const val X_B64URL = "yQbC7YPLyrelDs8Rd79n8drr-ZgI0U0GBQ7qHlROEHE"
    const val Y_B64URL = "By-3yfBRmaz4VkDYjTGS3CovFel00UfhfvVXkSnT5Wg"
}

/**
 * Verify the ES256 (ECDSA P-256 / SHA-256) signature of a license JWS against
 * the embedded [LicensePublicKey]. Returns false on ANY structural or
 * cryptographic failure — never throws.
 *
 * NOTE: the JWS ES256 signature is RAW `r||s` (64 bytes, base64url) per
 * RFC 7518 — JVM actuals must convert raw → DER before `Signature.verify`.
 */
expect fun verifyLicenseSignature(jwt: String): Boolean
