package com.bnm.lab.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
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
 * canonical JSON, the signing input, the session code and its hash.
 */

/**
 * Canonical JSON: object keys sorted (recursively, by UTF-16 code units, which
 * is what `Object.keys().sort()` does in Node), no whitespace, numbers exactly
 * as they arrived, strings escaped the way `JSON.stringify` escapes them.
 *
 * Written by hand rather than through kotlinx's encoder, which re-encodes a
 * parsed number through Long/Double (`1.50` → `1.5`, `-0` → `0`) — the bridge
 * canonicalises what it SENDS, the app what it RECEIVED, and those must be
 * the same bytes.
 */
internal object CanonicalJson {

    fun canonical(element: JsonElement): String = buildString { write(element, this) }

    private fun write(e: JsonElement, sb: StringBuilder) {
        when (e) {
            is JsonNull -> sb.append("null")
            is JsonPrimitive -> if (e.isString) quote(e.content, sb) else sb.append(e.content)
            is JsonArray -> {
                sb.append('[')
                e.forEachIndexed { i, v -> if (i > 0) sb.append(','); write(v, sb) }
                sb.append(']')
            }
            is JsonObject -> {
                sb.append('{')
                e.entries.sortedBy { it.key }.forEachIndexed { i, (k, v) ->
                    if (i > 0) sb.append(',')
                    quote(k, sb)
                    sb.append(':')
                    write(v, sb)
                }
                sb.append('}')
            }
        }
    }

    /** `JSON.stringify` string escaping: the two-character escapes, `\u00xx` for other controls, lone surrogates escaped. */
    private fun quote(s: String, sb: StringBuilder) {
        sb.append('"')
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\b' -> sb.append("\\b")
                c == '\u000C' -> sb.append("\\f")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate() -> {
                    sb.append(c).append(s[i + 1])
                    i++
                }
                c.isSurrogate() -> sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> sb.append(c)
            }
            i++
        }
        sb.append('"')
    }
}

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
