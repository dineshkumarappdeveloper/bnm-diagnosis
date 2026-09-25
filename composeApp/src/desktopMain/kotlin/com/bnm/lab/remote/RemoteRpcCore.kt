package com.bnm.lab.remote

import com.bnm.lab.diagnostics.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.Base64
import java.util.UUID
import kotlin.math.abs

/**
 * The MCP-shaped JSON-RPC 2.0 server the engineer talks to, with no socket in
 * it: text in, text (or nothing, for a notification) out. `RemoteSupportService`
 * feeds it frames from the relay; tests feed it strings.
 *
 * Every `tools/call` is authenticated (Ed25519 over the request — contract
 * §2.4), checked against the session's clock window, nonce set, expiry and the
 * owner's consent, and then — whatever happened — written to the audit store
 * and reported through [onAction] so the banner's count moves. Refusals are
 * JSON-RPC error −32001 with a short reason; a tool that ran and failed comes
 * back as an MCP result with `isError: true`, which is what an MCP client
 * shows the engineer as the tool's own error.
 *
 * Never logs a message body, an argument, or the session code.
 */
class RemoteRpcCore(
    private val hosts: () -> List<RemoteToolHost>,
    private val audit: () -> SupportAuditStore?,
    private val verifier: RemoteVerifier,
    private val clock: RemoteClock,
    private val appVersion: String,
    /** Every tools/call — ok, refused or failed — after its audit row is written. */
    private val onAction: suspend (SupportAuditRow) -> Unit = {},
    /** The lab's name for `initialize`'s `_meta.bnm`, what the engineer's `lab_connect` shows. */
    private val labName: () -> String? = { null },
) {
    /** What the core must remember about one session between frames. */
    class Live(val session: SupportSession, val expiresAtMono: Long) {
        /** Client − server, learned from the relay's `ready`; corrects the timestamp window. */
        @Volatile var skewMs: Long = 0L

        /** One engineer at a time: `initialize` while set is refused; the service clears it when the peer drops. */
        @Volatile var initialized: Boolean = false

        internal val nonces = HashSet<String>()
    }

    private val json = Json

    /** Handle one text frame. Null = nothing to send (a notification). Never throws on bad input. */
    suspend fun handle(live: Live, text: String): String? {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: return error(JsonNull, ERR_INVALID_REQUEST, "Malformed request")
        val id = root["id"] ?: JsonNull
        val method = root["method"].asString()
            ?: return error(id, ERR_INVALID_REQUEST, "Malformed request: no method")
        val params = root["params"] as? JsonObject
        return when (method) {
            "initialize" -> initialize(live, id)
            "ping" -> result(id, JsonObject(emptyMap()))
            "tools/list" -> result(id, buildJsonObject { put("tools", toolsList(live)) })
            "tools/call" -> toolsCall(live, id, method, root, params)
            else -> if (method.startsWith("notifications/")) null
            else error(id, ERR_METHOD_NOT_FOUND, "Method not found: ${method.take(64)}")
        }
    }

    // ── methods ──

    private fun initialize(live: Live, id: JsonElement): String {
        if (live.initialized) return error(id, ERR_REFUSED, "Another engineer is already connected to this session")
        live.initialized = true
        return result(id, buildJsonObject {
            put("protocolVersion", PROTOCOL_VERSION)
            putJsonObject("capabilities") { putJsonObject("tools") {} }
            putJsonObject("serverInfo") {
                put("name", SERVER_NAME)
                put("version", appVersion)
            }
            // The bridge signs every tools/call over the session id, and the
            // relay — a dumb pipe — never tells it one. So the lab does, here,
            // with the facts `lab_connect` reports back to the engineer.
            putJsonObject("_meta") {
                putJsonObject("bnm") {
                    put("session_id", live.session.id)
                    put("lab", labName().orEmpty())
                    put("app_version", appVersion)
                    putJsonObject("consent") {
                        put("analyzer_data", live.session.consent.analyzerData)
                        put("records", live.session.consent.records)
                        put("screen", live.session.consent.screen)
                    }
                    put("expires_in_s", ((live.expiresAtMono - clock.monotonicMs()) / 1_000L).coerceAtLeast(0L))
                }
            }
        })
    }

    private fun toolsList(live: Live): JsonArray = buildJsonArray {
        for (host in hosts()) for (t in host.tools()) add(buildJsonObject {
            put("name", t.name)
            // Listed even without consent, so the engineer can ask the owner
            // for it instead of wondering why a tool is missing.
            val needs = t.requires?.takeIf { !live.session.consent.grants(it) }
            put("description", if (needs == null) t.description else "${t.description} (requires: ${consentLabel(needs)})")
            put("inputSchema", runCatching { json.parseToJsonElement(t.inputSchemaJson) }.getOrElse { EMPTY_SCHEMA })
            putJsonObject("annotations") {
                put("title", t.name)
                put("readOnlyHint", t.readOnly)
                put("destructiveHint", t.destructive)
            }
        })
    }

    private suspend fun toolsCall(live: Live, id: JsonElement, method: String, root: JsonObject, params: JsonObject?): String {
        val startedMono = clock.monotonicMs()
        val toolName = sanitizeToolName(params?.get("name").asString())

        suspend fun refuse(reason: String, code: Int = ERR_REFUSED): String {
            record(live, toolName, SupportAuditRow.Outcome.REFUSED, "refused: $reason", startedMono)
            return error(id, code, reason)
        }

        // 1. Authentication — signature first, then freshness, then replay, then expiry.
        val bnm = root["bnm"] as? JsonObject ?: return refuse("Unsigned request: tools/call must carry bnm.sig")
        val ts = bnm["ts_ms"] as? JsonPrimitive ?: return refuse("Unsigned request: bnm.ts_ms missing")
        val nonce = bnm["nonce"].asString()?.takeIf { it.isNotBlank() && it.length <= NONCE_MAX }
            ?: return refuse("Unsigned request: bnm.nonce missing")
        val sig = bnm["sig"].asString()?.let { s -> runCatching { Base64.getDecoder().decode(s) }.getOrNull() }
            ?: return refuse("Bad signature")
        val message = RemoteSigning.input(
            sessionId = live.session.id,
            requestId = RemoteSigning.idAsString(id),
            method = method,
            tsMs = ts.content,
            nonce = nonce,
            canonicalParams = CanonicalJson.canonical(params ?: JsonObject(emptyMap())),
        )
        if (!verifier.verify(message, sig)) return refuse("Bad signature")
        val tsMs = ts.content.toLongOrNull() ?: return refuse("Bad timestamp")
        val serverNow = clock.wallMs() - live.skewMs
        if (abs(tsMs - serverNow) > TS_WINDOW_MS) return refuse("Request timestamp is outside the 5-minute window")
        val fresh = synchronized(live.nonces) { live.nonces.add(nonce) }
        if (!fresh) return refuse("Nonce already used")
        if (clock.monotonicMs() >= live.expiresAtMono) return refuse("Support session has ended")

        // 2. The tool and the owner's consent.
        val (host, spec) = find(toolName) ?: return refuse("Unknown tool: $toolName", ERR_INVALID_PARAMS)
        spec.requires?.let { need ->
            if (!live.session.consent.grants(need)) {
                return refuse("Not allowed: the lab owner did not give ${consentLabel(need)}")
            }
        }

        // 3. Run it. A host that throws is a failed tool, never a dead socket.
        val args = params?.get("arguments") as? JsonObject ?: JsonObject(emptyMap())
        val call = ToolCall(toolName, json.encodeToString(JsonObject.serializer(), args), live.session, RemoteSigning.idAsString(id))
        val outcome = try {
            host.call(call)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            AppLog.w("RemoteSupport", "$toolName threw ${e::class.simpleName}", e)
            ToolResult.Failed("The tool failed (${e::class.simpleName})")
        }
        return when (outcome) {
            is ToolResult.Ok -> {
                val content = runCatching { json.parseToJsonElement(outcome.contentJson) as JsonArray }.getOrNull()
                if (content == null) {
                    record(live, toolName, SupportAuditRow.Outcome.FAILED, "failed: malformed tool content", startedMono)
                    result(id, errorContent("The tool returned malformed content"))
                } else {
                    val reply = result(id, buildJsonObject {
                        put("content", content)
                        put("isError", false)
                    })
                    if (reply.toByteArray(Charsets.UTF_8).size > MAX_MESSAGE_BYTES) {
                        record(live, toolName, SupportAuditRow.Outcome.FAILED, "failed: result over 900 KB", startedMono)
                        error(id, ERR_REFUSED, "Result too large (over 900 KB) — ask for less")
                    } else {
                        record(live, toolName, SupportAuditRow.Outcome.OK, outcome.summaryForAudit, startedMono)
                        reply
                    }
                }
            }
            is ToolResult.Refused -> {
                record(live, toolName, SupportAuditRow.Outcome.REFUSED, "refused: ${outcome.reason}", startedMono)
                error(id, ERR_REFUSED, outcome.reason)
            }
            is ToolResult.Failed -> {
                record(live, toolName, SupportAuditRow.Outcome.FAILED, "failed: ${outcome.summaryForAudit}", startedMono)
                result(id, errorContent(outcome.message))
            }
        }
    }

    // ── audit ──

    private suspend fun record(live: Live, tool: String, outcome: SupportAuditRow.Outcome, summary: String, startedMono: Long) {
        val row = SupportAuditRow(
            id = UUID.randomUUID().toString(),
            sessionId = live.session.id,
            atMs = clock.wallMs(),
            tool = tool,
            summary = summary.take(SUMMARY_MAX),
            outcome = outcome,
            ms = clock.monotonicMs() - startedMono,
            startedBy = live.session.startedBy.staffId,
        )
        runCatching { audit()?.append(row) }
            .onFailure { AppLog.w("RemoteSupport", "audit write failed: ${it::class.simpleName}") }
        AppLog.i("RemoteSupport", "$tool ${outcome.name.lowercase()} ${row.ms}ms")
        onAction(row)
    }

    // ── helpers ──

    private fun find(name: String): Pair<RemoteToolHost, ToolSpec>? {
        for (host in hosts()) host.tools().firstOrNull { it.name == name }?.let { return host to it }
        return null
    }

    private fun result(id: JsonElement, result: JsonObject): String = json.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    })

    private fun error(id: JsonElement, code: Int, message: String): String = json.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        putJsonObject("error") {
            put("code", code)
            put("message", message)
        }
    })

    private fun errorContent(text: String): JsonObject = buildJsonObject {
        put("content", buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", text)
            })
        })
        put("isError", true)
    }

    private fun JsonElement?.asString(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    companion object {
        const val PROTOCOL_VERSION = "2025-06-18"
        const val SERVER_NAME = "bnm-lab"

        const val ERR_INVALID_REQUEST = -32600
        const val ERR_METHOD_NOT_FOUND = -32601
        const val ERR_INVALID_PARAMS = -32602
        /** Refused by policy: signature, clock, replay, expiry, consent, or the tool itself. */
        const val ERR_REFUSED = -32001

        /** The relay closes frames over 1 MiB; base64 screenshots must fit under this. */
        const val MAX_MESSAGE_BYTES = 900_000
        const val TS_WINDOW_MS = 5 * 60_000L
        const val SUMMARY_MAX = 200
        private const val NONCE_MAX = 128
        private val EMPTY_SCHEMA: JsonElement = buildJsonObject { put("type", "object") }

        fun consentLabel(kind: ConsentKind): String = when (kind) {
            ConsentKind.ANALYZER_DATA -> "analyzer data consent"
            ConsentKind.RECORDS -> "records consent"
            ConsentKind.SCREEN -> "screen view consent"
        }

        /** Audit rows and log lines carry the tool name the engineer sent — bounded and printable. */
        internal fun sanitizeToolName(raw: String?): String =
            raw?.filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }?.take(64)?.ifBlank { null } ?: "?"
    }
}
