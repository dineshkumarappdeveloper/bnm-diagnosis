package com.bnm.lab.remote

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * The wire agreement with the bridge (`tools/remote-mcp/bnmlab-remote.mjs`) —
 * the parts the app must compute IDENTICALLY to Node or every signature fails:
 * the signing input, the session code and its hash. Canonical JSON — the
 * other half of the agreement — lives in commonMain (`CanonicalJson`), shared
 * with the tool host and pinned by the fixture the bridge's tests load too.
 */

/** What the engineer signs for every `tools/call`, contract §2.4. */
internal object RemoteSigning {
    const val PREFIX = "bnm-lab-support-v1"

    fun input(sessionId: String, requestId: String, method: String, tsMs: String, nonce: String, canonicalParams: String): ByteArray =
        "$PREFIX\n$sessionId\n$requestId\n$method\n$tsMs\n$nonce\n$canonicalParams".toByteArray(Charsets.UTF_8)

    /** The JSON-RPC id "as string": `7` → "7", `"abc"` → "abc", absent → "". */
    fun idAsString(id: JsonElement?): String = when (id) {
        null, JsonNull -> ""
        is JsonPrimitive -> id.content
        else -> id.toString()
    }
}

/** Ed25519 over raw bytes; the real one holds the embedded support key, tests may stub it. */
fun interface RemoteVerifier {
    fun verify(message: ByteArray, signature: ByteArray): Boolean
}

/**
 * Verifies with a public key given as X.509 SubjectPublicKeyInfo, base64 —
 * [RemoteSupportKeys.SUPPORT_PUBLIC_KEY_SPKI_B64]. Ed25519 ships in the JDK
 * (SunEC), which the jlink image already carries for licence verification.
 */
class Ed25519Verifier(spkiB64: String) : RemoteVerifier {
    private val key: PublicKey =
        KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(spkiB64)))

    /** False on any failure — a malformed signature must never throw into the socket loop. */
    override fun verify(message: ByteArray, signature: ByteArray): Boolean = runCatching {
        Signature.getInstance("Ed25519").run {
            initVerify(key)
            update(message)
            verify(signature)
        }
    }.getOrDefault(false)
}

/**
 * The session code the owner reads out: eight characters from Crockford's
 * base32 alphabet — no I, L, O or U, so it survives a phone line — displayed
 * `XXXX-XXXX`. The relay only ever sees its hash.
 */
internal object SessionCode {
    const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val LENGTH = 8
    private const val HASH_PREFIX = "bnm-lab-support|"

    fun random(random: SecureRandom = SecureRandom()): String =
        buildString(LENGTH) { repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }

    fun hash(code: String): String =
        MessageDigest.getInstance("SHA-256").digest((HASH_PREFIX + code).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
