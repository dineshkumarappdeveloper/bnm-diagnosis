package com.bnm.diagnosis.license

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64

/**
 * ES256 (ECDSA P-256 / SHA-256) JWS verification via java.security.
 *
 * The JWS signature is RAW `r||s` (64 bytes) per RFC 7518 §3.4, but the JCA
 * `SHA256withECDSA` verifier expects a DER-encoded SEQUENCE(INTEGER r,
 * INTEGER s) — so we convert raw → DER (stripping redundant leading zeros and
 * re-adding a 0x00 pad byte when the high bit is set).
 */
actual fun verifyLicenseSignature(jwt: String): Boolean = try {
    val parts = jwt.split(".")
    if (parts.size != 3) false
    else {
        val signingInput = (parts[0] + "." + parts[1]).toByteArray(Charsets.US_ASCII)
        val rawSig = Base64.getUrlDecoder().decode(parts[2])
        if (rawSig.size != 64) false
        else {
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(licensePublicKey())
            verifier.update(signingInput)
            verifier.verify(rawEcdsaToDer(rawSig))
        }
    }
} catch (e: Exception) {
    false
}

/** Build the P-256 public key from the embedded JWK x/y coordinates. */
private fun licensePublicKey(): PublicKey {
    val dec = Base64.getUrlDecoder()
    val x = BigInteger(1, dec.decode(LicensePublicKey.X_B64URL))
    val y = BigInteger(1, dec.decode(LicensePublicKey.Y_B64URL))
    // Named-curve domain parameters without hardcoding the curve constants.
    val params = AlgorithmParameters.getInstance("EC").apply {
        init(ECGenParameterSpec("secp256r1"))
    }.getParameterSpec(ECParameterSpec::class.java)
    return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params))
}

/** RFC 7518 raw `r||s` (2×32 bytes) → DER SEQUENCE(INTEGER r, INTEGER s). */
private fun rawEcdsaToDer(raw: ByteArray): ByteArray {
    fun trimmed(bytes: ByteArray): ByteArray {
        var i = 0
        while (i < bytes.size - 1 && bytes[i].toInt() == 0) i++
        var out = bytes.copyOfRange(i, bytes.size)
        // INTEGER is signed — pad with 0x00 if the high bit would read negative.
        if (out[0].toInt() and 0x80 != 0) out = byteArrayOf(0) + out
        return out
    }
    val r = trimmed(raw.copyOfRange(0, 32))
    val s = trimmed(raw.copyOfRange(32, 64))
    val seqLen = 2 + r.size + 2 + s.size // always < 128, single-byte DER length
    val der = ByteArray(2 + seqLen)
    var i = 0
    der[i++] = 0x30; der[i++] = seqLen.toByte()
    der[i++] = 0x02; der[i++] = r.size.toByte()
    r.copyInto(der, i); i += r.size
    der[i++] = 0x02; der[i++] = s.size.toByte()
    s.copyInto(der, i)
    return der
}
