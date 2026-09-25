package com.bnm.lab

import com.bnm.lab.remote.CanonicalJson
import com.bnm.lab.remote.ConsentKind
import com.bnm.lab.remote.RemoteClock
import com.bnm.lab.remote.RemoteSigning
import com.bnm.lab.remote.RemoteToolHost
import com.bnm.lab.remote.RemoteTransport
import com.bnm.lab.remote.RemoteTransportFactory
import com.bnm.lab.remote.SupportAuditRow
import com.bnm.lab.remote.SupportAuditStore
import com.bnm.lab.remote.SupportSession
import com.bnm.lab.remote.ToolCall
import com.bnm.lab.remote.ToolResult
import com.bnm.lab.remote.ToolSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.UUID

/**
 * Shared pieces for the remote-support tests: the DEV support key (the one
 * committed for the bridge's tests — the app ships its public half on this
 * branch), a request signer that does what the bridge does, a tool host with
 * one tool per behaviour, an in-memory audit store, a clock the test advances
 * by hand, and a transport fed by script.
 */

/** tools/remote-mcp/test/dev-support.key, found by walking up from the test's working directory. */
internal object DevSupportKey {
    private fun repoFile(rel: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val f = File(dir, rel)
            if (f.exists()) return f
            dir = dir.parentFile
        }
        error("not found from ${File("").absolutePath}: $rel")
    }

    val privateKey: PrivateKey by lazy {
        val pem = repoFile("tools/remote-mcp/test/dev-support.key").readText()
        val der = Base64.getMimeDecoder().decode(pem.lineSequence().filterNot { it.startsWith("-----") }.joinToString(""))
        KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(der))
    }

    val publicSpkiB64: String by lazy { repoFile("tools/remote-mcp/test/dev-support.pub").readText().trim() }

    fun sign(message: ByteArray): ByteArray = Signature.getInstance("Ed25519").run {
        initSign(privateKey)
        update(message)
        sign()
    }
}

internal val testJson = Json

/** A JSON-RPC `tools/call` signed the way the bridge signs it (contract §2.4). */
internal fun signedCall(
    sessionId: String,
    id: JsonElement,
    tool: String,
    tsMs: Long,
    args: String = "{}",
    nonce: String = UUID.randomUUID().toString(),
    method: String = "tools/call",
    /** Applied AFTER signing — to produce a request whose signature no longer matches. */
    tamper: (JsonObject) -> JsonObject = { it },
): String {
    val params = buildJsonObject {
        put("name", tool)
        put("arguments", testJson.parseToJsonElement(args))
    }
    val input = RemoteSigning.input(sessionId, RemoteSigning.idAsString(id), method, tsMs.toString(), nonce, CanonicalJson.canonical(params))
    val sig = Base64.getEncoder().encodeToString(DevSupportKey.sign(input))
    val request = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("method", method)
        put("params", params)
        putJsonObject("bnm") {
            put("ts_ms", tsMs)
            put("nonce", nonce)
            put("sig", sig)
        }
    }
    return testJson.encodeToString(JsonObject.serializer(), tamper(request))
}

internal fun jsonRpc(id: JsonElement?, method: String, params: JsonObject? = null): String =
    testJson.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("jsonrpc", "2.0")
        if (id != null) put("id", id)
        put("method", method)
        if (params != null) put("params", params)
    })

internal fun String.asJson(): JsonObject = testJson.parseToJsonElement(this) as JsonObject
internal val JsonObject.result: JsonObject? get() = this["result"] as? JsonObject
internal val JsonObject.errorCode: Int? get() = ((this["error"] as? JsonObject)?.get("code") as? JsonPrimitive)?.content?.toInt()
internal val JsonObject.errorMessage: String? get() = ((this["error"] as? JsonObject)?.get("message") as? JsonPrimitive)?.content

/** One tool per behaviour the core must handle. */
internal class FakeToolHost : RemoteToolHost {
    val calls = mutableListOf<ToolCall>()

    override fun tools(): List<ToolSpec> = listOf(
        ToolSpec("echo.text", "Echoes the arguments.", SCHEMA, readOnly = true, destructive = false),
        ToolSpec("records.peek", "Looks at a record.", SCHEMA, readOnly = true, destructive = false, requires = ConsentKind.RECORDS),
        ToolSpec("analyzer.raw", "Raw frames.", SCHEMA, readOnly = true, destructive = false, requires = ConsentKind.ANALYZER_DATA),
        ToolSpec("boom", "Throws.", SCHEMA, readOnly = false, destructive = true),
        ToolSpec("refuse.me", "Refuses.", SCHEMA, readOnly = false, destructive = true),
        ToolSpec("fail.me", "Fails.", SCHEMA, readOnly = true, destructive = false),
        ToolSpec("big", "Too much.", SCHEMA, readOnly = true, destructive = false),
        ToolSpec("long.summary", "Over-long audit summary.", SCHEMA, readOnly = true, destructive = false),
    )

    override suspend fun call(call: ToolCall): ToolResult {
        calls += call
        return when (call.name) {
            "echo.text" -> ToolResult.Ok(text("echo:${call.argsJson}"), "echo ${call.argsJson.length} chars")
            "records.peek" -> ToolResult.Ok(text("a record"), "records.peek 1 row")
            "analyzer.raw" -> ToolResult.Ok(text("frames"), "analyzer.raw 1 frame")
            "boom" -> throw IllegalStateException("patient Jane Doe exploded") // must not reach the wire or the audit
            "refuse.me" -> ToolResult.Refused("Analyzer is busy — a frame arrived 3 s ago")
            "fail.me" -> ToolResult.Failed("Port COM3 could not be opened")
            "big" -> ToolResult.Ok(text("x".repeat(950_000)), "big")
            "long.summary" -> ToolResult.Ok(text("ok"), "s".repeat(500))
            else -> ToolResult.Failed("no such tool in the fake")
        }
    }

    private fun text(s: String) = testJson.encodeToString(JsonElement.serializer(), kotlinx.serialization.json.buildJsonArray {
        add(buildJsonObject { put("type", "text"); put("text", s) })
    })

    private companion object {
        const val SCHEMA = """{"type":"object","properties":{"q":{"type":"string"}}}"""
    }
}

internal class InMemoryAuditStore : SupportAuditStore {
    val rows = mutableListOf<SupportAuditRow>()
    val started = mutableListOf<SupportSession>()
    val ended = mutableListOf<Triple<String, Long, String>>()

    override suspend fun sessionStarted(session: SupportSession) { started += session }
    override suspend fun sessionEnded(sessionId: String, endedAtMs: Long, reason: String) { ended += Triple(sessionId, endedAtMs, reason) }
    override suspend fun append(row: SupportAuditRow) { synchronized(rows) { rows += row } }
    override suspend fun recent(limit: Int): List<SupportAuditRow> = synchronized(rows) { rows.asReversed().take(limit) }
}

/** Time moves only when the test says so; every sleep waits for [advance]. */
internal class FakeRemoteClock(
    @Volatile var mono: Long = 1_000_000L,
    @Volatile var wall: Long = 1_760_000_000_000L,
) : RemoteClock {
    val sleeps = mutableListOf<Long>()
    private val sleepers = mutableListOf<Pair<Long, CompletableDeferred<Unit>>>()

    override fun monotonicMs(): Long = mono
    override fun wallMs(): Long = wall

    override suspend fun sleep(ms: Long) {
        val d = CompletableDeferred<Unit>()
        synchronized(sleepers) {
            sleeps += ms
            sleepers += (mono + ms) to d
        }
        d.await()
    }

    fun advance(ms: Long) {
        val due: List<CompletableDeferred<Unit>>
        synchronized(sleepers) {
            mono += ms
            wall += ms
            due = sleepers.filter { it.first <= mono }.map { it.second }
            sleepers.removeAll { it.first <= mono }
        }
        due.forEach { it.complete(Unit) }
    }

    /** How many sleeps are pending right now (the ticker's, a backoff's). */
    fun pending(): Int = synchronized(sleepers) { sleepers.size }
}

/** A socket fed by the test: [push] frames in, read what the engine [sent]. */
internal class FakeTransport : RemoteTransport {
    private val inbox = Channel<String>(Channel.UNLIMITED)
    val sent = Channel<String>(Channel.UNLIMITED)
    val sentLog = mutableListOf<String>()
    @Volatile var closed = false

    fun push(frame: String) { inbox.trySend(frame) }
    /** The relay dropped us. */
    fun drop() { inbox.close() }

    override suspend fun send(text: String) {
        synchronized(sentLog) { sentLog += text }
        sent.trySend(text)
    }

    override suspend fun receive(): String? = inbox.receiveCatching().getOrNull()

    override suspend fun close() {
        closed = true
        inbox.close()
    }
}

/** Each connect takes the next step: a transport, or a failure. */
internal class ScriptedTransportFactory : RemoteTransportFactory {
    private val steps = Channel<Result<FakeTransport>>(Channel.UNLIMITED)
    val urls = mutableListOf<String>()

    fun thenConnect(t: FakeTransport = FakeTransport()): FakeTransport = t.also { steps.trySend(Result.success(it)) }
    fun thenFail(e: Throwable) { steps.trySend(Result.failure(e)) }

    override suspend fun connect(url: String): RemoteTransport {
        synchronized(urls) { urls += url }
        return steps.receive().getOrThrow()
    }
}

internal fun readyFrame(serverTimeMs: Long) = """{"t":"ready","server_time_ms":$serverTimeMs}"""
