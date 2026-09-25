package com.bnm.lab.remote

import com.bnm.lab.staff.sha256Hex
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The one JSON form both ends of a support session sign over.
 *
 * The engineer's bridge signs `canonical(params)`; this app verifies the same
 * bytes. Any difference in whitespace, key order or number formatting between
 * the two would make every signature fail, so the rule is deliberately small:
 *
 * - object keys sorted recursively, by UTF-16 code unit (what JavaScript's
 *   `Object.keys(o).sort()` and Kotlin's `String.compareTo` both do);
 * - arrays keep their order;
 * - no whitespace;
 * - numbers exactly as they arrived on the wire — never re-formatted. The
 *   bridge writes them with `JSON.stringify`, this side parses and re-emits
 *   the literal untouched, so `1.0` never becomes `1` on one side only;
 * - strings escaped the JSON way (`"` `\` and control characters), non-ASCII
 *   emitted raw.
 *
 * The shared fixture `tools/remote-mcp/test/canonical-vectors.json` pins the
 * output for both implementations — see CanonicalJsonTest and the bridge's
 * node test, which load the same file.
 *
 * Written by hand rather than through `Json.encodeToString(JsonElement)`:
 * kotlinx re-parses numeric literals and prints them as doubles (`1e-7` →
 * `1.0E-7`, `1.50` → `1.5`), which is exactly the re-formatting the rule
 * forbids. String escaping IS kotlinx's — it matches JSON.stringify.
 */
object CanonicalJson {

    private val json = Json

    fun canonical(element: JsonElement): String = buildString { write(element, this) }

    private fun write(e: JsonElement, out: StringBuilder) {
        when (e) {
            is JsonObject -> {
                out.append('{')
                var first = true
                for (key in e.keys.sorted()) {
                    if (!first) out.append(',')
                    first = false
                    out.append(json.encodeToString(String.serializer(), key)).append(':')
                    write(e.getValue(key), out)
                }
                out.append('}')
            }
            is JsonArray -> {
                out.append('[')
                e.forEachIndexed { i, item -> if (i > 0) out.append(','); write(item, out) }
                out.append(']')
            }
            is JsonNull -> out.append("null")
            is JsonPrimitive -> if (e.isString) out.append(json.encodeToString(String.serializer(), e.content))
                else out.append(e.content)          // number or boolean, literal as it arrived
        }
    }

    /** Parse then canonicalise; throws on malformed JSON (the caller refuses the request). */
    fun canonical(text: String): String = canonical(json.parseToJsonElement(text))

    /** Hex sha256 of the canonical UTF-8 bytes — the fixture's `sha256` column. */
    fun sha256(canonical: String): String = sha256Hex(canonical)

    fun sortKeys(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.entries.sortedBy { it.key }.associate { (k, v) -> k to sortKeys(v) },
        )
        is JsonArray -> JsonArray(element.map { sortKeys(it) })
        else -> element
    }
}
