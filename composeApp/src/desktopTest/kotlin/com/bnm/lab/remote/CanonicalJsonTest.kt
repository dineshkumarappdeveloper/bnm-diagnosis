package com.bnm.lab.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The canonical form both ends sign over. The vectors are the SAME file the
 * bridge's node test loads (`tools/remote-mcp/test/canonical-vectors.json`):
 * a mismatch here means a signature that verifies on one side and not the
 * other, so the file is the contract and this test is the app's half of it.
 */
class CanonicalJsonTest {

    private fun fixture(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val f = File(dir, "tools/remote-mcp/test/canonical-vectors.json")
            if (f.isFile) return f
            dir = dir.parentFile
        }
        fail("canonical-vectors.json not found above ${File("").absolutePath}")
    }

    @Test
    fun `every shared vector canonicalises and hashes exactly as the fixture says`() {
        val doc = Json.parseToJsonElement(fixture().readText()).jsonObject
        val vectors = doc.getValue("vectors").jsonArray
        assertTrue(vectors.size >= 8, "the contract asks for at least 8 vectors, found ${vectors.size}")
        for (v in vectors) {
            val o = v.jsonObject
            val name = o.getValue("name").jsonPrimitive.content
            val expected = o.getValue("canonical").jsonPrimitive.content
            val canonical = CanonicalJson.canonical(o.getValue("input"))
            assertEquals(expected, canonical, name)
            assertEquals(o.getValue("sha256").jsonPrimitive.content, CanonicalJson.sha256(canonical), "$name · sha256")
            // Canonical text is a fixed point: parse → canonicalise → same bytes.
            assertEquals(canonical, CanonicalJson.canonical(canonical), "$name · idempotent")
        }
    }

    @Test
    fun `the fixture covers the shapes the contract names`() {
        val names = Json.parseToJsonElement(fixture().readText()).jsonObject.getValue("vectors").jsonArray
            .map { it.jsonObject.getValue("name").jsonPrimitive.content.lowercase() }
        for (needle in listOf("nested", "array", "unicode", "number", "empty")) {
            assertTrue(names.any { needle in it }, "no vector about '$needle' in $names")
        }
    }

    @Test
    fun `keys sort at every level, arrays keep order, whitespace goes, numbers stay as sent`() {
        assertEquals("""{"a":[{"a":2,"b":1},[3,1]],"z":0}""",
            CanonicalJson.canonical(""" { "z" : 0 , "a" : [ { "b" : 1 , "a" : 2 } , [ 3 , 1 ] ] } """))
        // The literal is re-emitted, never re-formatted — the bridge signed "1.50".
        assertEquals("""{"x":1.50,"y":1e-7,"z":-0.001}""", CanonicalJson.canonical("""{"z":-0.001,"y":1e-7,"x":1.50}"""))
        // UTF-16 code unit order: digits < upper < '_' < lower; "10" < "9".
        assertEquals("""{"10":1,"9":2,"A":3,"_":4,"a":5}""", CanonicalJson.canonical("""{"a":5,"_":4,"A":3,"9":2,"10":1}"""))
        assertEquals("""{"s":"say \"hi\"\n"}""", CanonicalJson.canonical("""{"s":"say \"hi\"\n"}"""))
    }
}
