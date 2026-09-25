package com.bnm.lab

import com.bnm.lab.remote.Ed25519Verifier
import com.bnm.lab.remote.RemoteRpcCore
import com.bnm.lab.remote.RemoteSupportKeys
import com.bnm.lab.remote.SupportAuditRow
import com.bnm.lab.remote.SupportConsent
import com.bnm.lab.remote.SupportSession
import com.bnm.lab.remote.SupportStarter
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The JSON-RPC core over an in-memory "transport" (strings in, strings out):
 * every method, every refusal path, and the audit rows each leaves behind.
 * Requests are signed with the committed DEV key and verified with the key
 * the app embeds — the same pair the bridge's tests use.
 */
class RemoteRpcTest {

    private val clock = FakeRemoteClock()
    private val audit = InMemoryAuditStore()
    private val host = FakeToolHost()
    private val actions = mutableListOf<SupportAuditRow>()
    private val core = RemoteRpcCore(
        hosts = { listOf(host) },
        audit = { audit },
        verifier = Ed25519Verifier(RemoteSupportKeys.SUPPORT_PUBLIC_KEY_SPKI_B64),
        clock = clock,
        appVersion = "9.9.9-test",
        onAction = { actions += it },
    )

    private fun session(consent: SupportConsent = SupportConsent()) = SupportSession(
        id = "3f1c2a8e-6d0b-4c7e-9a1f-000000000001",
        code = "ABCDEFGH",
        startedAtMs = clock.wallMs(),
        durationS = 3_600L,
        consent = consent,
        startedBy = SupportStarter("owner-1", "Lab Owner"),
    )

    private fun live(consent: SupportConsent = SupportConsent(), durationS: Long = 3_600L) =
        RemoteRpcCore.Live(session(consent).copy(durationS = durationS), clock.monotonicMs() + durationS * 1_000L)

    private fun id(n: Int) = JsonPrimitive(n)

    private fun call(live: RemoteRpcCore.Live, tool: String, args: String = "{}", id: Int = 1, tsMs: Long = clock.wallMs(),
                     nonce: String = java.util.UUID.randomUUID().toString(), tamper: (JsonObject) -> JsonObject = { it }) =
        runBlocking { core.handle(live, signedCall(live.session.id, id(id), tool, tsMs, args, nonce, tamper = tamper))!!.asJson() }

    // ── methods ──

    @Test
    fun `initialize answers with the protocol and the app, and refuses a second engineer`() = runBlocking<Unit> {
        val live = live()
        val first = core.handle(live, jsonRpc(id(1), "initialize", buildJsonObject { put("protocolVersion", "2025-06-18") }))!!.asJson()
        val result = assertNotNull(first.result)
        assertEquals("2025-06-18", result["protocolVersion"]!!.jsonPrimitive.content)
        assertEquals("bnm-lab", (result["serverInfo"] as JsonObject)["name"]!!.jsonPrimitive.content)
        assertEquals("9.9.9-test", (result["serverInfo"] as JsonObject)["version"]!!.jsonPrimitive.content)
        assertNotNull((result["capabilities"] as JsonObject)["tools"])
        assertEquals(1, first["id"]!!.jsonPrimitive.content.toInt())

        val second = core.handle(live, jsonRpc(id(2), "initialize"))!!.asJson()
        assertEquals(RemoteRpcCore.ERR_REFUSED, second.errorCode)
        assertTrue("already connected" in second.errorMessage!!, second.errorMessage)

        // The peer dropped and came back: the service clears the flag and the next initialize is welcome.
        live.initialized = false
        assertNotNull(core.handle(live, jsonRpc(id(3), "initialize"))!!.asJson().result)
    }

    @Test
    fun `notifications get no reply, ping gets an empty result`() = runBlocking<Unit> {
        val live = live()
        assertNull(core.handle(live, jsonRpc(null, "notifications/initialized")))
        assertNull(core.handle(live, jsonRpc(null, "notifications/cancelled")))
        val pong = core.handle(live, jsonRpc(id(9), "ping"))!!.asJson()
        assertEquals(JsonObject(emptyMap()), pong.result)
        assertEquals("9", pong["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tools list carries MCP annotations and marks tools the owner did not allow`() = runBlocking<Unit> {
        val live = live(SupportConsent(records = true))
        val reply = core.handle(live, jsonRpc(id(1), "tools/list"))!!.asJson()
        val tools = (reply.result!!["tools"] as JsonArray).map { it as JsonObject }
        assertEquals(host.tools().map { it.name }, tools.map { it["name"]!!.jsonPrimitive.content })
        val byName = tools.associateBy { it["name"]!!.jsonPrimitive.content }
        // Allowed (records) → plain description; not allowed (analyzer data) → flagged, but still listed.
        assertEquals("Looks at a record.", byName["records.peek"]!!["description"]!!.jsonPrimitive.content)
        assertEquals("Raw frames. (requires: analyzer data consent)", byName["analyzer.raw"]!!["description"]!!.jsonPrimitive.content)
        val ann = byName["boom"]!!["annotations"] as JsonObject
        assertEquals("true", ann["destructiveHint"]!!.jsonPrimitive.content)
        assertEquals("false", ann["readOnlyHint"]!!.jsonPrimitive.content)
        assertEquals("boom", ann["title"]!!.jsonPrimitive.content)
        assertEquals("object", (byName["echo.text"]!!["inputSchema"] as JsonObject)["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `bad JSON and unknown methods are errors, never exceptions`() = runBlocking<Unit> {
        val live = live()
        val bad = core.handle(live, "{not json")!!.asJson()
        assertEquals(RemoteRpcCore.ERR_INVALID_REQUEST, bad.errorCode)
        assertEquals(JsonNull, bad["id"])
        val noMethod = core.handle(live, """{"jsonrpc":"2.0","id":4}""")!!.asJson()
        assertEquals(RemoteRpcCore.ERR_INVALID_REQUEST, noMethod.errorCode)
        val array = core.handle(live, "[1,2,3]")!!.asJson()
        assertEquals(RemoteRpcCore.ERR_INVALID_REQUEST, array.errorCode)
        val unknown = core.handle(live, jsonRpc(id(5), "resources/list"))!!.asJson()
        assertEquals(RemoteRpcCore.ERR_METHOD_NOT_FOUND, unknown.errorCode)
        assertEquals("5", unknown["id"]!!.jsonPrimitive.content)
        assertTrue(audit.rows.isEmpty(), "nothing to audit — no tool was named")
    }

    // ── a good call ──

    @Test
    fun `a signed call runs the tool, wraps MCP content, and is audited with the host's summary`() {
        val live = live()
        val reply = call(live, "echo.text", args = """{"q":"hello"}""", id = 7)
        assertEquals("7", reply["id"]!!.jsonPrimitive.content)
        val result = assertNotNull(reply.result)
        assertEquals("false", result["isError"]!!.jsonPrimitive.content)
        val content = (result["content"] as JsonArray)[0] as JsonObject
        assertEquals("text", content["type"]!!.jsonPrimitive.content)
        assertEquals("""echo:{"q":"hello"}""", content["text"]!!.jsonPrimitive.content)
        assertEquals("""{"q":"hello"}""", host.calls.single().argsJson)
        assertEquals("7", host.calls.single().requestId)

        val row = audit.rows.single()
        assertEquals(SupportAuditRow.Outcome.OK, row.outcome)
        assertEquals("echo.text", row.tool)
        assertEquals("echo 13 chars", row.summary)
        assertEquals(live.session.id, row.sessionId)
        assertEquals("owner-1", row.startedBy)
        assertEquals(listOf(row), actions)
    }

    @Test
    fun `a string id is echoed as a string and signed as its text`() {
        val live = live()
        val text = signedCall(live.session.id, JsonPrimitive("req-abc"), "echo.text", clock.wallMs())
        val reply = runBlocking { core.handle(live, text)!!.asJson() }
        assertNotNull(reply.result)
        assertEquals("req-abc", reply["id"]!!.jsonPrimitive.content)
        assertTrue(reply["id"]!!.jsonPrimitive.isString)
    }

    // ── refusals ──

    @Test
    fun `an unsigned call is refused and audited`() = runBlocking<Unit> {
        val live = live()
        val reply = core.handle(live, jsonRpc(id(1), "tools/call", buildJsonObject { put("name", "echo.text") }))!!.asJson()
        assertEquals(RemoteRpcCore.ERR_REFUSED, reply.errorCode)
        assertTrue("Unsigned" in reply.errorMessage!!, reply.errorMessage)
        assertTrue(host.calls.isEmpty())
        val row = audit.rows.single()
        assertEquals(SupportAuditRow.Outcome.REFUSED, row.outcome)
        assertEquals("echo.text", row.tool)
        assertTrue(row.summary.startsWith("refused: Unsigned"), row.summary)
        assertEquals(1, actions.size, "refusals count as actions on the banner")
    }

    @Test
    fun `a tampered request fails the signature`() {
        val live = live()
        // Change the arguments after signing — what a relay in the middle could try.
        val reply = call(live, "echo.text", args = """{"q":"hello"}""") { req ->
            JsonObject(req + ("params" to buildJsonObject {
                put("name", "echo.text")
                put("arguments", buildJsonObject { put("q", "HELLO") })
            }))
        }
        assertEquals(RemoteRpcCore.ERR_REFUSED, reply.errorCode)
        assertEquals("Bad signature", reply.errorMessage)
        assertTrue(host.calls.isEmpty())
        assertEquals(SupportAuditRow.Outcome.REFUSED, audit.rows.single().outcome)

        // And a signature that is not even base64 does not throw.
        val garbage = call(live, "echo.text") { req ->
            JsonObject(req + ("bnm" to buildJsonObject { put("ts_ms", clock.wallMs()); put("nonce", "n1"); put("sig", "***") }))
        }
        assertEquals("Bad signature", garbage.errorMessage)
    }

    @Test
    fun `a request older than five minutes is refused, and the relay's clock skew is honoured`() {
        val live = live()
        val stale = call(live, "echo.text", tsMs = clock.wallMs() - 6 * 60_000L)
        assertEquals(RemoteRpcCore.ERR_REFUSED, stale.errorCode)
        assertTrue("5-minute" in stale.errorMessage!!, stale.errorMessage)
        val future = call(live, "echo.text", tsMs = clock.wallMs() + 6 * 60_000L)
        assertEquals(RemoteRpcCore.ERR_REFUSED, future.errorCode)
        assertNotNull(call(live, "echo.text", tsMs = clock.wallMs() - 4 * 60_000L).result, "inside the window")

        // This PC runs 10 minutes fast: the engineer's (correct) timestamps only
        // pass because the window is measured in the relay's time.
        live.skewMs = 10 * 60_000L
        assertEquals(RemoteRpcCore.ERR_REFUSED, call(live, "echo.text", tsMs = clock.wallMs()).errorCode)
        assertNotNull(call(live, "echo.text", tsMs = clock.wallMs() - 10 * 60_000L).result)
        assertEquals(3, audit.rows.count { it.outcome == SupportAuditRow.Outcome.REFUSED })
    }

    @Test
    fun `a nonce is good once`() {
        val live = live()
        assertNotNull(call(live, "echo.text", nonce = "same-nonce", id = 1).result)
        val replay = call(live, "echo.text", nonce = "same-nonce", id = 2)
        assertEquals(RemoteRpcCore.ERR_REFUSED, replay.errorCode)
        assertEquals("Nonce already used", replay.errorMessage)
        assertEquals(1, host.calls.size)
    }

    @Test
    fun `an expired session refuses every call`() {
        val live = live(durationS = 60L)
        assertNotNull(call(live, "echo.text").result)
        clock.mono += 61_000L
        val late = call(live, "echo.text")
        assertEquals(RemoteRpcCore.ERR_REFUSED, late.errorCode)
        assertEquals("Support session has ended", late.errorMessage)
        assertEquals(1, host.calls.size)
    }

    @Test
    fun `consent gates the tool, and the refusal names what is missing`() {
        val without = live(SupportConsent())
        val refused = call(without, "records.peek")
        assertEquals(RemoteRpcCore.ERR_REFUSED, refused.errorCode)
        assertTrue("records consent" in refused.errorMessage!!, refused.errorMessage)
        assertTrue(host.calls.isEmpty())
        val row = audit.rows.single()
        assertEquals(SupportAuditRow.Outcome.REFUSED, row.outcome)
        assertEquals("records.peek", row.tool)

        val with = live(SupportConsent(records = true))
        assertNotNull(call(with, "records.peek").result)
        assertEquals(1, host.calls.size)

        val analyzer = call(with, "analyzer.raw")
        assertTrue("analyzer data consent" in analyzer.errorMessage!!, analyzer.errorMessage)
    }

    @Test
    fun `an unknown tool is an invalid-params error`() {
        val reply = call(live(), "instruments.nuke")
        assertEquals(RemoteRpcCore.ERR_INVALID_PARAMS, reply.errorCode)
        assertTrue("Unknown tool" in reply.errorMessage!!)
        assertEquals("instruments.nuke", audit.rows.single().tool)
    }

    // ── what the tool itself says ──

    @Test
    fun `a tool's own refusal is an error with its reason`() {
        val reply = call(live(), "refuse.me")
        assertEquals(RemoteRpcCore.ERR_REFUSED, reply.errorCode)
        assertEquals("Analyzer is busy — a frame arrived 3 s ago", reply.errorMessage)
        val row = audit.rows.single()
        assertEquals(SupportAuditRow.Outcome.REFUSED, row.outcome)
        assertEquals("refused: Analyzer is busy — a frame arrived 3 s ago", row.summary)
    }

    @Test
    fun `a tool that fails is an MCP error result, not a dead socket`() {
        val reply = call(live(), "fail.me")
        val result = assertNotNull(reply.result)
        assertEquals("true", result["isError"]!!.jsonPrimitive.content)
        assertEquals("Port COM3 could not be opened", ((result["content"] as JsonArray)[0] as JsonObject)["text"]!!.jsonPrimitive.content)
        assertEquals(SupportAuditRow.Outcome.FAILED, audit.rows.single().outcome)
    }

    @Test
    fun `a tool that throws is reported by class only — the message never leaves the PC`() {
        val reply = call(live(), "boom")
        val result = assertNotNull(reply.result)
        assertEquals("true", result["isError"]!!.jsonPrimitive.content)
        val text = ((result["content"] as JsonArray)[0] as JsonObject)["text"]!!.jsonPrimitive.content
        assertTrue("IllegalStateException" in text, text)
        assertFalse("Jane" in text, text)
        val row = audit.rows.single()
        assertEquals(SupportAuditRow.Outcome.FAILED, row.outcome)
        assertFalse("Jane" in row.summary, row.summary)
    }

    @Test
    fun `a result over 900 KB is refused instead of sent`() {
        val reply = call(live(), "big")
        assertEquals(RemoteRpcCore.ERR_REFUSED, reply.errorCode)
        assertTrue("too large" in reply.errorMessage!!.lowercase(), reply.errorMessage)
        assertEquals(SupportAuditRow.Outcome.FAILED, audit.rows.single().outcome)
    }

    @Test
    fun `audit summaries are cut at 200 characters`() {
        call(live(), "long.summary")
        assertEquals(200, audit.rows.single().summary.length)
    }

    @Test
    fun `every call counts, however it ended`() {
        val live = live()
        call(live, "echo.text")
        call(live, "refuse.me")
        call(live, "fail.me")
        call(live, "records.peek")
        assertEquals(4, actions.size)
        assertEquals(
            listOf(SupportAuditRow.Outcome.OK, SupportAuditRow.Outcome.REFUSED, SupportAuditRow.Outcome.FAILED, SupportAuditRow.Outcome.REFUSED),
            actions.map { it.outcome },
        )
    }
}
