package com.bnm.lab.license

/**
 * iOS stub — STRUCTURAL parse only, then returns true.
 *
 * TODO: replace with Security.framework (SecKeyCreateWithData +
 * SecKeyVerifySignature, kSecKeyAlgorithmECDSASignatureMessageX962SHA256,
 * converting the raw r||s JWS signature to X9.62/DER) when the iOS target
 * ships. Desktop + Android are the sale targets for BNM Lab P2, and
 * both verify the ES256 signature for real.
 */
actual fun verifyLicenseSignature(jwt: String): Boolean {
    val parts = jwt.split(".")
    if (parts.size != 3) return false
    return parts.all { it.isNotBlank() }
}
