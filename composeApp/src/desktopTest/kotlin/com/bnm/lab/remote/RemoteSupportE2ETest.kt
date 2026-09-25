package com.bnm.lab.remote

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.bnm.lab.BuildInfo
import com.bnm.lab.DevSupportKey
import com.bnm.lab.api.ApiClient
import com.bnm.lab.db.AppDatabase
import com.bnm.lab.instruments.InstrumentEngine
import com.bnm.lab.lab.LabRepository
import com.bnm.lab.license.LicenseManager
import com.bnm.lab.update.ReleaseInfo
import com.bnm.lab.update.UpdateCheck
import com.bnm.lab.update.UpdateInstall
import com.russhwolf.settings.PropertiesSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.util.Properties
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The whole feature, end to end, with nothing faked in the middle: THIS app's
 * session engine (real Ktor WebSocket) → a relay running locally (`wrangler
 * dev`, started with the TEST licence key) → the engineer's bridge
 * (`tools/remote-mcp/bnmlab-remote.mjs`, a child process driven over stdio
 * exactly as Claude Code drives it).
 *
 * Opt-in — it needs the relay up and Node on the PATH:
 *
 * ```
 * cd relay && npx wrangler dev --local --var BNM_SUPPORT_TOKEN:dev-token \
 *     --var "LAB_LICENSE_PUBLIC_JWK:$(cat test/license-test.public.jwk.json)"
 * BNM_RELAY_URL=ws://127.0.0.1:8787 ./gradlew :composeApp:desktopTest -Dbnm.e2e=true \
 *     --tests 'com.bnm.lab.remote.RemoteSupportE2ETest'
 * ```
 *
 * Without `-Dbnm.e2e=true` the test skips itself. The licence JWT is minted
 * with `relay/scripts/mint-test-jwt.mjs` unless `BNM_E2E_LICENSE_JWT` is set,
 * and reaches the engine through its constructor seam — the real prefs, data
 * dir and log folder are never touched: temp Settings, a temp SQLite file with
 * the real schema, a temp folder for the platform's disk/log answers.
 *
 * The transcript is printed as `[e2e] …` lines (Gradle keeps them in the test
 * report) and, when `BNM_E2E_TRANSCRIPT` names a file, written there too.
 * It never contains the code, the token or the JWT.
 */
class RemoteSupportE2ETest {

    private val json = Json
    private val transcript = StringBuilder()

    private fun say(line: String) {
        println("[e2e] $line")
        transcript.append(line).append('\n')
    }

    @Test
    fun `owner starts a session, the engineer's bridge connects through the relay, reads, is refused unsigned, and ends it`() {
        assumeTrue("opt-in: run with -Dbnm.e2e=true", System.getProperty("bnm.e2e") == "true")
        val relayUrl = (System.getenv("BNM_RELAY_URL")?.takeIf { it.isNotBlank() } ?: "ws://127.0.0.1:8787").trimEnd('/')
        val token = System.getenv("BNM_SUPPORT_TOKEN")?.takeIf { it.isNotBlank() } ?: "dev-token"
        val root = repoRoot()
        val keyFile = File(root, "tools/remote-mcp/test/dev-support.key")
        assertTrue(keyFile.isFile, "dev support key missing: $keyFile")

        try {
            runBlocking { run(root, relayUrl, token, keyFile) }
        } finally {
            System.getenv("BNM_E2E_TRANSCRIPT")?.takeIf { it.isNotBlank() }?.let { path ->
                runCatching { File(path).apply { parentFile?.mkdirs() }.writeText(transcript.toString()) }
            }
        }
    }

    private suspend fun run(root: File, relayUrl: String, token: String, keyFile: File) {
        // ── 0. the relay must be up, or this is a failure, not a skip ──
        val httpBase = relayUrl.replaceFirst("ws://", "http://").replaceFirst("wss://", "https://")
        HttpClient(OkHttp).use { c ->
            val health = c.get("$httpBase/v1/health")
            say("GET $httpBase/v1/health → ${health.status.value} ${health.bodyAsText().trim()}")
            assertEquals(200, health.status.value, "relay not reachable at $relayUrl — start `wrangler dev` first")
        }

        // ── 1. a licence the relay accepts (test keypair), minted by the relay's own script ──
        val jwt = System.getenv("BNM_E2E_LICENSE_JWT")?.takeIf { it.isNotBlank() } ?: mintJwt(root)

        // ── 2. THIS app: temp prefs, temp data dir, temp SQLite with the real schema ──
        val dataDir = Files.createTempDirectory("bnmlab-e2e").toFile().apply { deleteOnExit() }
        val dbFile = File(dataDir, "e2e.db")
        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        val db = AppDatabase(driver).also { AppDatabase.Schema.create(driver) }
        val repo = LabRepository(db, ApiClient.json)
        val engine = InstrumentEngine(db, repo, ApiClient.json, healIntervalMs = 3_600_000L)
        val licence = LicenseManager(PropertiesSettings(Properties()), { true }, { System.currentTimeMillis() / 1_000L }).apply {
            saveActivation(jwt, "e2e-device-token", "e2e-row", LAB_NAME, "standalone", 1, null, null)
        }
        val audit = SqlSupportAuditStore(db)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val service = RemoteSupportService(
            relayUrl = { relayUrl },
            licenseJwt = { jwt },                       // the test seam: never the real prefs
            labName = { LAB_NAME },
            appVersion = "${BuildInfo.VERSION}-e2e",
            transports = KtorWebSocketTransportFactory(), // the real socket
            verifier = Ed25519Verifier(DevSupportKey.publicSpkiB64),
            clock = SystemRemoteClock,
            scope = scope,
        )
        service.install()
        service.attachAuditStore(audit)
        service.attachToolHost(remoteToolHost(
            db, engine, repo, licence, controller = service,
            platform = DesktopRemoteToolPlatform(logDir = dataDir, dataDir = dataDir),
            readOnlySql = JdbcReadOnlySql(dbFile),
            updates = NoUpdates,
            supportKeyLabel = { "dev" },
        ))
        say("app: temp data dir, temp prefs, temp SQLite (real schema); tool host + audit store attached; version ${BuildInfo.VERSION}-e2e")

        var bridge: Bridge? = null
        try {
            // ── 3. the owner starts a session ──
            val session = service.start(SupportConsent(), 3_600L, SupportStarter("owner-e2e", "E2E Owner")).getOrThrow()
            say("owner started a 1 h session, diagnostics-only consent → code has ${session.code.length} chars, id ${session.id.take(8)}…")
            val waiting = withTimeout(20_000L) { service.status.first { it.phase == RemoteSupportStatus.Phase.WAITING_FOR_ENGINEER } }
            say("app: phase ${waiting.phase} (hello accepted, clock skew ${waiting.clockSkewMs} ms)")

            // ── 4. the engineer's bridge, as Claude Code runs it: a child process over stdio ──
            bridge = Bridge(root, relayUrl, token, keyFile, ::say)
            val init = bridge.request("initialize", buildJsonObject {
                put("protocolVersion", "2025-06-18")
                putJsonObject("capabilities") {}
                putJsonObject("clientInfo") { put("name", "e2e"); put("version", "0") }
            })
            assertEquals("2025-06-18", init.result()["protocolVersion"]!!.jsonPrimitive.content)
            assertEquals("bnmlab-remote", init.result()["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
            bridge.notify("notifications/initialized")
            say("bridge: initialize → protocolVersion 2025-06-18, serverInfo.name bnmlab-remote")

            val connect = bridge.tool("lab_connect", buildJsonObject { put("code", session.displayCode) })
            assertFalse(connect.isError, "lab_connect failed: ${connect.text}")
            assertTrue(LAB_NAME in connect.text, "lab_connect should report the lab's name (from initialize._meta.bnm): ${connect.text}")
            say("bridge: lab_connect(code) → ok: ${connect.text.oneLine()}")
            val connected = withTimeout(20_000L) { service.status.first { it.peerConnected } }
            // lab_connect ends with one signed session.info of its own — the first action on the banner.
            withTimeout(10_000L) { service.status.first { it.actions == 1 } }
            say("app: phase ${connected.phase}, engineer connected; banner: 1 action (the bridge's own session.info)")

            val tools = bridge.tool("lab_tools")
            assertFalse(tools.isError, tools.text)
            for (name in listOf("lab.overview", "instruments.list", "instruments.set_config", "app.screenshot", "session.end")) {
                assertTrue(name in tools.text, "lab_tools should list $name: ${tools.text.take(400)}")
            }
            say("bridge: lab_tools → ${TOOL_NAME_REGEX.findAll(tools.text).map { it.value }.toSet().size} distinct tool names, incl. lab.overview / instruments.list / session.end")

            val overview = bridge.tool("lab_call", buildJsonObject { put("tool", "lab.overview"); putJsonObject("args") {} })
            assertFalse(overview.isError, "lab.overview failed: ${overview.text}")
            val overviewJson = json.parseToJsonElement(overview.text).jsonObject
            assertEquals("dev", overviewJson["support_key"]!!.jsonPrimitive.content)
            assertEquals(LAB_NAME, overviewJson["lab"]!!.jsonPrimitive.content)
            say("bridge: lab_call lab.overview → ok: lab=${overviewJson["lab"]!!.jsonPrimitive.content}, support_key=dev, " +
                "keys ${overviewJson.keys.sorted().joinToString(",")}")
            assertEquals(2, service.status.value.actions, "banner counts the overview as the second action")

            // ── 5. an UNSIGNED tools/call sent straight to the relay must be refused ──
            val bye = bridge.tool("lab_disconnect")
            assertFalse(bye.isError, bye.text)
            withTimeout(20_000L) { service.status.first { !it.peerConnected } }
            say("bridge: lab_disconnect → ok; app: engineer disconnected")

            val refusal = unsignedCallDirect(relayUrl, token, session.displayCode)
            val err = assertNotNull(refusal["error"]?.jsonObject, "an unsigned call must come back as a JSON-RPC error: $refusal")
            assertEquals(RemoteRpcCore.ERR_REFUSED, err["code"]!!.jsonPrimitive.content.toInt())
            assertTrue("Unsigned" in err["message"]!!.jsonPrimitive.content, err.toString())
            say("direct socket: unsigned tools/call lab.overview → error ${err["code"]!!.jsonPrimitive.content} \"${err["message"]!!.jsonPrimitive.content}\"")
            withTimeout(20_000L) { service.status.first { !it.peerConnected } }
            assertEquals(3, service.status.value.actions, "the refusal counts as an action too")

            // ── 6. the bridge again: instruments.list, then the engineer ends the session ──
            val again = bridge.tool("lab_connect", buildJsonObject { put("code", session.code.lowercase()) })
            assertFalse(again.isError, "second lab_connect failed: ${again.text}")
            withTimeout(20_000L) { service.status.first { it.peerConnected } }
            say("bridge: lab_connect(code, lowercase this time) → ok; app: engineer connected again")

            val instruments = bridge.tool("lab_call", buildJsonObject { put("tool", "instruments.list"); putJsonObject("args") {} })
            assertFalse(instruments.isError, instruments.text)
            val instrumentsList = json.parseToJsonElement(instruments.text).let { it as? kotlinx.serialization.json.JsonArray ?: it.jsonObject["instruments"]!!.jsonArray }
            assertEquals(0, instrumentsList.size, instruments.text)
            say("bridge: lab_call instruments.list → ok: ${instrumentsList.size} instruments (fresh database)")

            val end = bridge.tool("lab_call", buildJsonObject { put("tool", "session.end"); putJsonObject("args") {} })
            assertFalse(end.isError, end.text)
            val off = withTimeout(20_000L) { service.status.first { it.phase == RemoteSupportStatus.Phase.OFF } }
            say("bridge: lab_call session.end → ok; app: phase ${off.phase}")
            // The lab's `end` frame and close travel relay → bridge a few ms after the reply did.
            var status = bridge.tool("lab_status")
            val until = System.currentTimeMillis() + 10_000L
            while ("\"disconnected\"" !in status.text && System.currentTimeMillis() < until) {
                Thread.sleep(200)
                status = bridge.tool("lab_status")
            }
            say("bridge: lab_status → ${status.text.oneLine()}")
            assertTrue("\"disconnected\"" in status.text, "after the lab ends, the bridge must be disconnected: ${status.text}")

            // ── 7. Support history on the lab PC ──
            val rows = audit.recent(50)
            fun row(tool: String, outcome: SupportAuditRow.Outcome) = rows.firstOrNull { it.tool == tool && it.outcome == outcome }
            assertNotNull(row("lab.overview", SupportAuditRow.Outcome.OK), rows.toString())
            assertNotNull(row("instruments.list", SupportAuditRow.Outcome.OK), rows.toString())
            assertNotNull(row("session.end", SupportAuditRow.Outcome.OK), rows.toString())
            val refused = assertNotNull(row("lab.overview", SupportAuditRow.Outcome.REFUSED), rows.toString())
            assertTrue("Unsigned" in refused.summary, refused.summary)
            assertTrue(rows.all { it.sessionId == session.id })
            say("audit: ${rows.size} rows — " + rows.asReversed().joinToString(" · ") { "${it.tool} ${it.outcome.name.lowercase()}" })
        } finally {
            bridge?.close()
            runCatching { service.end("e2e finished") }
            scope.cancel()
            runCatching { driver.close() }
        }
    }

    // ── pieces ──

    /** `node relay/scripts/mint-test-jwt.mjs --lab …` — prints only the JWT; the command is logged, the token is not. */
    private fun mintJwt(root: File): String {
        val cmd = listOf("node", "scripts/mint-test-jwt.mjs", "--lab", LAB_NAME)
        val p = ProcessBuilder(cmd).directory(File(root, "relay")).start()
        val jwt = p.inputStream.bufferedReader().readText().trim()
        val err = p.errorStream.bufferedReader().readText()
        check(p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0 && jwt.count { it == '.' } == 2) { "mint failed: $err" }
        say("minted a TEST licence JWT: (cd relay && ${cmd.joinToString(" ")}) → ${jwt.length} chars")
        return jwt
    }

    /**
     * The engineer's side without the bridge: join with the code, `initialize`,
     * then a `tools/call` with no `bnm` block. The lab must answer −32001.
     */
    private suspend fun unsignedCallDirect(relayUrl: String, token: String, code: String): JsonObject {
        var refusal: JsonObject? = null
        HttpClient(OkHttp) { install(WebSockets) }.use { c ->
            c.webSocket(urlString = "$relayUrl/v1/support", request = { header("Authorization", "Bearer $token") }) {
                suspend fun rpc(id: Int): JsonObject = withTimeout(20_000L) {
                    var found: JsonObject? = null
                    while (found == null) {
                        val frame = incoming.receive() as? Frame.Text ?: continue
                        val obj = json.parseToJsonElement(frame.readText()).jsonObject
                        if (obj["t"] != null) continue // relay notices (peer, error)
                        if (obj["id"]?.jsonPrimitive?.content == id.toString()) found = obj
                    }
                    checkNotNull(found)
                }
                send(Frame.Text("""{"t":"join","code":"$code"}"""))
                send(Frame.Text("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"e2e-direct","version":"0"}}}"""))
                val init = rpc(1)
                assertNotNull(init["result"], "direct initialize should succeed: $init")
                send(Frame.Text("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"lab.overview","arguments":{}}}"""))
                refusal = rpc(2)
            }
        }
        return refusal!!
    }

    /** The bridge as a child process: newline-delimited JSON-RPC on stdin/stdout, stderr echoed into the transcript. */
    private class Bridge(root: File, relayUrl: String, token: String, keyFile: File, private val say: (String) -> Unit) : AutoCloseable {
        private val json = Json
        private val proc: Process = ProcessBuilder("node", File(root, "tools/remote-mcp/bnmlab-remote.mjs").path)
            .directory(root)
            .apply {
                environment()["BNM_RELAY_URL"] = relayUrl
                environment()["BNM_SUPPORT_TOKEN"] = token
                environment()["BNM_SUPPORT_KEY_FILE"] = keyFile.path
            }
            .start()
        private val stdin = proc.outputStream.bufferedWriter()
        private val lines = LinkedBlockingQueue<String>()
        private var nextId = 1

        init {
            Thread({ proc.inputStream.bufferedReader().forEachLine { lines.put(it) } }, "e2e-bridge-stdout").apply { isDaemon = true }.start()
            Thread({ proc.errorStream.bufferedReader().forEachLine { say("bridge stderr: $it") } }, "e2e-bridge-stderr").apply { isDaemon = true }.start()
            say("bridge: spawned `node tools/remote-mcp/bnmlab-remote.mjs` with BNM_RELAY_URL=$relayUrl BNM_SUPPORT_TOKEN=… BNM_SUPPORT_KEY_FILE=…/dev-support.key")
        }

        fun request(method: String, params: JsonObject? = null): JsonObject {
            val id = nextId++
            write(buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                if (params != null) put("params", params)
            })
            while (true) {
                val line = lines.poll(45, TimeUnit.SECONDS) ?: fail("bridge did not answer $method (id $id) in 45 s")
                val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: fail("bridge wrote a non-JSON line to stdout: ${line.take(200)}")
                if (obj["id"]?.jsonPrimitive?.content == id.toString()) return obj
            }
        }

        fun notify(method: String) = write(buildJsonObject { put("jsonrpc", "2.0"); put("method", method) })

        /** `tools/call` on the bridge; returns the first text content and the isError flag. */
        fun tool(name: String, args: JsonObject = JsonObject(emptyMap())): ToolReply {
            val reply = request("tools/call", buildJsonObject { put("name", name); put("arguments", args) })
            reply["error"]?.let { fail("bridge returned a JSON-RPC error for $name: $it") }
            val result = reply.result()
            val text = result["content"]?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content ?: ""
            return ToolReply(result["isError"]?.jsonPrimitive?.booleanOrNull == true, text)
        }

        private fun write(obj: JsonObject) {
            stdin.write(json.encodeToString(JsonObject.serializer(), obj))
            stdin.newLine()
            stdin.flush()
        }

        override fun close() {
            runCatching { stdin.close() }
            if (!proc.waitFor(3, TimeUnit.SECONDS)) proc.destroyForcibly()
            say("bridge: exited with ${proc.exitValue()}")
        }
    }

    private data class ToolReply(val isError: Boolean, val text: String)

    private object NoUpdates : UpdateFlow {
        override suspend fun check(): UpdateCheck = UpdateCheck.UpToDate(BuildInfo.VERSION)
        override suspend fun checksum(release: ReleaseInfo, assetName: String): String? = null
        override suspend fun download(url: String, fileName: String, sha256: String?): UpdateInstall =
            UpdateInstall.LaunchedQuitNow("/dev/null")
    }

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "tools/remote-mcp/bnmlab-remote.mjs").isFile) return dir
            dir = dir.parentFile
        }
        fail("repo root not found above ${File("").absolutePath}")
    }

    private companion object {
        const val LAB_NAME = "E2E Lab"
        val TOOL_NAME_REGEX = Regex("\\b(lab|instruments|catalog|db|logs|app|session)\\.[a-z_.]+")
    }
}

private fun JsonObject.result(): JsonObject = this["result"]?.jsonObject ?: fail("no result in $this")
private fun String.oneLine(): String = replace('\n', ' ').replace(Regex("\\s+"), " ").take(220)
