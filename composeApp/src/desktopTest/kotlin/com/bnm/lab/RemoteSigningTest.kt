package com.bnm.lab

import com.bnm.lab.remote.CanonicalJson
import com.bnm.lab.remote.Ed25519Verifier
import com.bnm.lab.remote.RemoteSigning
import com.bnm.lab.remote.RemoteSupportKeys
import com.bnm.lab.remote.SessionCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The bytes the app and the bridge must agree on: the app's verifier, given
 * the dev public key, accepts what the dev private key signs, canonical JSON
 * comes out identical to Node's, and the session code hashes the way the
 * relay expects. Whether the SHIPPED key may still be the dev key is
 * [RemoteSupportKeysReleaseTest]'s question, not this one's — the tests here
 * pin the dev CONSTANT to the committed file, so pasting the real key into
 * `SUPPORT_PUBLIC_KEY_SPKI_B64` changes nothing below.
 */
class RemoteSigningTest {

    private val verifier = Ed25519Verifier(RemoteSupportKeys.DEV_PUBLIC_KEY_SPKI_B64)

    @Test
    fun `the dev constant is the committed dev key, so the bridge's tests and the app's agree`() {
        assertEquals(DevSupportKey.publicSpkiB64, RemoteSupportKeys.DEV_PUBLIC_KEY_SPKI_B64,
            "RemoteSupportKeys.DEV_PUBLIC_KEY_SPKI_B64 must match tools/remote-mcp/test/dev-support.pub")
    }

    @Test
    fun `what the dev key signs, the dev public key verifies — and nothing else`() {
        val message = RemoteSigning.input("sess", "1", "tools/call", "1760000000000", "n-1", """{"name":"x"}""")
        val sig = DevSupportKey.sign(message)
        assertTrue(verifier.verify(message, sig))
        assertFalse(verifier.verify(message + 0x20.toByte(), sig), "a changed message")
        assertFalse(verifier.verify(message, sig.copyOf().also { it[3] = (it[3] + 1).toByte() }), "a changed signature")
        assertFalse(verifier.verify(message, ByteArray(3)), "a signature of the wrong size never throws")
        assertFalse(verifier.verify(message, ByteArray(0)))
    }

    @Test
    fun `the signing input is the seven lines of the contract`() {
        val input = RemoteSigning.input("S", "7", "tools/call", "123", "N", "{}").decodeToString()
        assertEquals("bnm-lab-support-v1\nS\n7\ntools/call\n123\nN\n{}", input)
        assertEquals("7", RemoteSigning.idAsString(JsonPrimitive(7)))
        assertEquals("abc", RemoteSigning.idAsString(JsonPrimitive("abc")))
        assertEquals("", RemoteSigning.idAsString(JsonNull))
        assertEquals("", RemoteSigning.idAsString(null))
    }

    @Test
    fun `canonical JSON sorts keys recursively and keeps numbers and text as sent`() {
        // Same vectors as tools/remote-mcp: input → what JSON.stringify(sortKeys(JSON.parse(input))) gives.
        val vectors = listOf(
            """{"b":1,"a":{"d":[3,{"z":1,"y":2}],"c":"x"}}""" to """{"a":{"c":"x","d":[3,{"y":2,"z":1}]},"b":1}""",
            """{}""" to """{}""",
            """{"name":"echo.text","arguments":{}}""" to """{"arguments":{},"name":"echo.text"}""",
            """{"n":1.50,"m":-0,"big":12345678901234567890}""" to """{"big":12345678901234567890,"m":-0,"n":1.50}""",
            """{"s":"a\"b\nc\td\\e"}""" to """{"s":"a\"b\nc\td\\e"}""",
            """{"u":"नमस्ते ✓","t":true,"z":null}""" to """{"t":true,"u":"नमस्ते ✓","z":null}""",
            """{"B":1,"a":2,"_":3,"1":4}""" to """{"1":4,"B":1,"_":3,"a":2}""",
            """{ "spaced" : [ 1 , 2 ] }""" to """{"spaced":[1,2]}""",
            """{"c":"\u0001x\u001f/"}""" to """{"c":"\u0001x\u001f/"}""",
            """{"e":"\ud83d\ude00 \ud800"}""" to """{"e":"😀 \ud800"}""",
        )
        for ((input, expected) in vectors) {
            assertEquals(expected, CanonicalJson.canonical(Json.parseToJsonElement(input)), input)
        }
    }

    @Test
    fun `the session code reads over a phone line and hashes with the relay's prefix`() {
        assertEquals(32, SessionCode.ALPHABET.length)
        for (c in "ILOU") assertFalse(c in SessionCode.ALPHABET, "$c is too easy to mishear or misread")
        repeat(20) {
            val code = SessionCode.random()
            assertEquals(8, code.length)
            assertTrue(code.all { it in SessionCode.ALPHABET }, code)
        }
        val expected = MessageDigest.getInstance("SHA-256").digest("bnm-lab-support|ABCDEFGH".toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, SessionCode.hash("ABCDEFGH"))
        assertEquals(64, SessionCode.hash("ABCDEFGH").length)
    }
}
