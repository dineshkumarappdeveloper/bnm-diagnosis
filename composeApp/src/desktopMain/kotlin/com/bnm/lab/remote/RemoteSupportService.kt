package com.bnm.lab.remote

import com.bnm.lab.BuildInfo
import com.bnm.lab.api.Constants
import com.bnm.lab.diagnostics.AppLog
import com.bnm.lab.diagnostics.logFailure
import com.bnm.lab.license.LicenseManager
import com.bnm.lab.license.OfflinePolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException
import kotlin.math.min

/**
 * The desktop remote-support engine (contract §2) — the only
 * [RemoteSupportController]. One session at a time: the owner starts it, the
 * app dials OUT to BNM's relay over WebSocket, says hello, and from then on
 * answers the engineer's JSON-RPC through [RemoteRpcCore] until End, sign-out,
 * the relay ending it, or the time being up.
 *
 * - Expiry is measured on the monotonic clock; the wall clock only ever
 *   formats "ends 18:30".
 * - A dropped socket reconnects with backoff (2 s → 30 s) until expiry; the
 *   banner reads "reconnecting" meanwhile. Only the FIRST connect failing
 *   fails [start] — the owner sees a short reason and can try again.
 * - The audit store is attached once the database exists; until then (and
 *   for the dialog's live list) the last [RECENT_MAX] rows are kept in memory.
 * - Logs carry the session id prefix and counts — never the code, never a
 *   frame, never an argument.
 *
 * Constructor is the test seam (fake transport, fake clock, stub verifier);
 * production uses [instance].
 */
class RemoteSupportService internal constructor(
    private val relayUrl: () -> String,
    private val licenseJwt: () -> String?,
    private val labName: () -> String?,
    private val appVersion: String,
    private val transports: RemoteTransportFactory,
    verifier: RemoteVerifier,
    private val clock: RemoteClock,
    private val scope: CoroutineScope,
    private val newCode: () -> String = { SessionCode.random() },
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : RemoteSupportController {

    private val hosts = CopyOnWriteArrayList<RemoteToolHost>()
    @Volatile private var auditStore: SupportAuditStore? = null
    /** Newest first; the dialog's history when no store is attached yet. Guarded by itself. */
    private val recent = ArrayDeque<SupportAuditRow>()
    private val _status = MutableStateFlow(RemoteSupportStatus())
    override val status: StateFlow<RemoteSupportStatus> = _status.asStateFlow()
    private val installed = AtomicBoolean(false)
    @Volatile private var run: Run? = null
    @Volatile private var window: java.awt.Window? = null
    private val json = Json

    private val core = RemoteRpcCore(
        hosts = { hosts.toList() },
        audit = { auditStore },
        verifier = verifier,
        clock = clock,
        appVersion = appVersion,
        onAction = ::recordAction,
    )

    /** One started session: its state for the core, its socket, its job. */
    private class Run(val live: RemoteRpcCore.Live) {
        @Volatile var transport: RemoteTransport? = null
        val ended = AtomicBoolean(false)
        @Volatile var job: Job? = null
        /** Completed by the first connect attempt — success, or the reason [start] returns. */
        val firstConnect = CompletableDeferred<Result<Unit>>()
        val session: SupportSession get() = live.session
    }

    // ── desktop wiring (Main.kt) ──

    /** Called once from `main()` after diagnostics: registers the window-bound tool. Idle until a session starts. */
    fun install() {
        if (!installed.compareAndSet(false, true)) return
        attachToolHost(ScreenshotToolHost { window })
        AppLog.i("RemoteSupport", "engine ready — relay ${relayHost()} · support key ${if (RemoteSupportKeys.isDevKey) "DEV (replace before release)" else "prod"}")
    }

    /** The Compose window the screenshot tool captures (consent-gated). */
    fun registerWindow(w: java.awt.Window) { window = w }

    /** For the window's close handler: end now, but never hold the exit for more than a moment. */
    fun endBlocking(reason: String) {
        if (run == null) return
        runCatching { runBlocking { withTimeoutOrNull(1_500L) { end(reason) } } }
    }

    // ── RemoteSupportController ──

    override suspend fun start(consent: SupportConsent, durationS: Long, startedBy: SupportStarter): Result<SupportSession> {
        val r = synchronized(this) {
            if (_status.value.isActive) {
                return Result.failure(IllegalStateException("A support session is already running. End it first."))
            }
            // Always true — the owner pressed it — but the policy is the one place that decides.
            if (!OfflinePolicy.allowsRemoteSupport(userInitiated = true)) {
                return Result.failure(IllegalStateException("Remote support is not allowed on this licence."))
            }
            if (licenseJwt().isNullOrBlank()) {
                return Result.failure(IllegalStateException("This computer has no BNM Lab licence yet. Activate it first, then start remote support."))
            }
            val duration = durationS.coerceIn(MIN_DURATION_S, MAX_DURATION_S)
            val session = SupportSession(
                id = newId(),
                code = newCode(),
                startedAtMs = clock.wallMs(),
                durationS = duration,
                consent = consent,
                startedBy = startedBy,
            )
            Run(RemoteRpcCore.Live(session, clock.monotonicMs() + duration * 1_000L)).also { r ->
                run = r
                _status.value = RemoteSupportStatus(phase = RemoteSupportStatus.Phase.CONNECTING, session = session, remainingS = duration)
                AppLog.i("RemoteSupport", "session ${session.id.take(8)} started by ${startedBy.staffId.take(8)} " +
                    "for ${duration}s consent=${consentFlags(consent)}")
            }
        }
        runCatching { auditStore?.sessionStarted(r.session) }.logFailure("RemoteSupport", "audit session start")
        r.job = scope.launch { sessionLoop(r) }
        return r.firstConnect.await().map { r.session }
    }

    override suspend fun end(reason: String) {
        val r = run ?: return
        finish(r, reason, RemoteSupportStatus.Phase.OFF)
        r.job?.cancel()
    }

    override suspend fun history(limit: Int): List<SupportAuditRow> {
        auditStore?.let { store ->
            runCatching { store.recent(limit) }
                .onSuccess { return it }
                .logFailure("RemoteSupport", "read support history")
        }
        return synchronized(recent) { recent.take(limit) }
    }

    override fun attachToolHost(host: RemoteToolHost) {
        val names = host.tools().map { it.name }
        val taken = hosts.flatMap { h -> h.tools().map { it.name } }
        require(names.none { it in taken }) { "tool name already attached: ${names.filter { it in taken }}" }
        hosts.add(host)
    }

    override fun attachAuditStore(store: SupportAuditStore) { auditStore = store }

    // ── the session ──

    private suspend fun sessionLoop(r: Run) = coroutineScope {
        val ticker = launch { tick(r) }
        var backoffMs = BACKOFF_MIN_MS
        var attempt = 0
        try {
            while (!r.ended.get() && !expired(r)) {
                attempt++
                var reason: String
                try {
                    val t = transports.connect(labUrl())
                    r.transport = t
                    try {
                        t.send(helloFrame(r))
                        val serverTimeMs = awaitReady(t)
                        r.live.skewMs = clock.wallMs() - serverTimeMs
                        backoffMs = BACKOFF_MIN_MS
                        update(r) { copy(phase = RemoteSupportStatus.Phase.WAITING_FOR_ENGINEER, lastError = null, clockSkewMs = r.live.skewMs) }
                        if (!r.firstConnect.isCompleted) r.firstConnect.complete(Result.success(Unit))
                        AppLog.i("RemoteSupport", "session ${r.session.id.take(8)} connected to the relay (skew ${r.live.skewMs} ms)")
                        readLoop(r, t)
                        reason = "the connection closed"
                    } finally {
                        runCatching { t.close() }
                        r.transport = null
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    reason = describe(e)
                }
                if (r.ended.get() || expired(r)) break
                if (!r.firstConnect.isCompleted) {
                    // The very first connect failed: the owner is watching the
                    // dialog, so say why instead of retrying behind their back.
                    finish(r, "could not connect: $reason", RemoteSupportStatus.Phase.ENDED_ERROR, error = reason)
                    return@coroutineScope
                }
                r.live.initialized = false
                update(r) { copy(phase = RemoteSupportStatus.Phase.RECONNECTING, peerConnected = false, lastError = reason) }
                AppLog.w("RemoteSupport", "session ${r.session.id.take(8)}: $reason — reconnecting in ${backoffMs / 1_000}s")
                clock.sleep(backoffMs)
                backoffMs = min(backoffMs * 2, BACKOFF_MAX_MS)
            }
            if (!r.ended.get()) finish(r, if (expired(r)) "time is up" else "stopped", RemoteSupportStatus.Phase.OFF)
        } finally {
            ticker.cancel()
        }
    }

    /** Once a second: the banner's remaining time, and the expiry that ends the session. */
    private suspend fun tick(r: Run) {
        while (!r.ended.get()) {
            clock.sleep(TICK_MS)
            if (r.ended.get()) return
            val rem = remaining(r)
            if (rem <= 0L) {
                finish(r, "time is up", RemoteSupportStatus.Phase.OFF)
                return
            }
            update(r) { if (remainingS == rem) this else copy(remainingS = rem) }
        }
    }

    private suspend fun readLoop(r: Run, t: RemoteTransport) {
        while (!r.ended.get()) {
            val text = t.receive() ?: return
            if (r.ended.get() || expired(r)) return
            val notice = relayNotice(text)
            if (notice != null) {
                handleNotice(r, notice)
                continue
            }
            val reply = core.handle(r.live, text) ?: continue
            t.send(reply)
        }
    }

    /** Relay frames carry a `t`; the engineer's JSON-RPC never does. */
    private fun relayNotice(text: String): JsonObject? {
        val obj = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        val t = obj["t"] as? JsonPrimitive ?: return null
        return if (t.isString) obj else null
    }

    private suspend fun handleNotice(r: Run, notice: JsonObject) {
        when ((notice["t"] as? JsonPrimitive)?.content) {
            "peer" -> {
                val connected = (notice["state"] as? JsonPrimitive)?.content == "connected"
                if (!connected) r.live.initialized = false
                update(r) {
                    copy(
                        peerConnected = connected,
                        phase = if (connected) RemoteSupportStatus.Phase.ENGINEER_CONNECTED
                        else RemoteSupportStatus.Phase.WAITING_FOR_ENGINEER,
                    )
                }
                AppLog.i("RemoteSupport", "session ${r.session.id.take(8)}: engineer ${if (connected) "connected" else "disconnected"}")
            }
            "end" -> finish(r, "closed by the relay", RemoteSupportStatus.Phase.OFF)
            "error" -> {
                val code = (notice["code"] as? JsonPrimitive)?.content
                update(r) { copy(lastError = relayError(code)) }
                AppLog.w("RemoteSupport", "session ${r.session.id.take(8)}: relay error ${code ?: "?"}")
            }
            else -> Unit // "ready" again, or something newer than this build — ignore
        }
    }

    /** Reads until the relay's `ready`; returns its `server_time_ms`. */
    private suspend fun awaitReady(t: RemoteTransport): Long {
        val serverTime: Long? = withTimeoutOrNull(READY_TIMEOUT_MS) {
            var found: Long? = null
            while (found == null) {
                val text = t.receive() ?: throw IOException("the connection closed before BNM's relay answered")
                val obj = relayNotice(text) ?: continue
                when ((obj["t"] as? JsonPrimitive)?.content) {
                    "ready" -> found = (obj["server_time_ms"] as? JsonPrimitive)?.content?.toLongOrNull() ?: clock.wallMs()
                    "error" -> throw IOException(relayError((obj["code"] as? JsonPrimitive)?.content))
                    else -> Unit // a peer notice can precede ready when the engineer was already waiting
                }
            }
            found
        }
        return serverTime ?: throw IOException("BNM's relay did not answer")
    }

    private suspend fun finish(r: Run, reason: String, phase: RemoteSupportStatus.Phase, error: String? = null) {
        if (!r.ended.compareAndSet(false, true)) return
        r.transport?.let { t ->
            runCatching { withTimeoutOrNull(1_000L) { t.send(END_FRAME) } }
            runCatching { t.close() }
        }
        val actions = _status.value.actions
        runCatching { auditStore?.sessionEnded(r.session.id, clock.wallMs(), reason) }
            .logFailure("RemoteSupport", "audit session end")
        AppLog.i("RemoteSupport", "session ${r.session.id.take(8)} ended: $reason ($actions actions)")
        if (run === r) _status.value = RemoteSupportStatus(phase = phase, lastError = error)
        // Ended before the first connect came back (End, or the app closing):
        // the dialog waiting in start() must hear about it, not hang.
        r.firstConnect.complete(Result.failure(IOException(error ?: reason)))
    }

    private suspend fun recordAction(row: SupportAuditRow) {
        synchronized(recent) {
            recent.addFirst(row)
            while (recent.size > RECENT_MAX) recent.removeLast()
        }
        run?.let { r -> if (r.session.id == row.sessionId) update(r) { copy(actions = actions + 1) } }
    }

    // ── helpers ──

    private fun helloFrame(r: Run): String = json.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("t", "hello")
        put("v", 1)
        put("jwt", licenseJwt().orEmpty())
        put("session_id", r.session.id)
        put("code_hash", SessionCode.hash(r.session.code))
        put("lab", labName().orEmpty())
        put("app_version", appVersion)
        put("expires_in_s", remaining(r))
        putJsonObject("consent") {
            put("analyzer_data", r.session.consent.analyzerData)
            put("records", r.session.consent.records)
            put("screen", r.session.consent.screen)
        }
    })

    private fun remaining(r: Run): Long = ((r.live.expiresAtMono - clock.monotonicMs()) / 1_000L).coerceAtLeast(0L)
    private fun expired(r: Run): Boolean = clock.monotonicMs() >= r.live.expiresAtMono

    /** Status changes only for the run that is current and not yet finished. */
    private fun update(r: Run, block: RemoteSupportStatus.() -> RemoteSupportStatus) {
        _status.update { if (run === r && !r.ended.get()) it.block() else it }
    }

    private fun labUrl(): String = relayUrl().trimEnd('/') + "/v1/lab"
    private fun relayHost(): String = relayUrl().substringAfter("://").substringBefore('/')

    /** Plain words for the dialog and the log; never the URL, never a stack. */
    private fun describe(e: Throwable): String = when (e) {
        is IOException -> when (e) {
            is UnknownHostException, is ConnectException, is SocketTimeoutException ->
                "no internet connection, or BNM's relay is not reachable"
            is SSLException -> "the secure connection to BNM's relay failed"
            else -> e.message?.takeIf { it.isNotBlank() && !it.contains("://") } ?: "the connection failed"
        }
        else -> "the connection failed (${e::class.simpleName})"
    }

    private fun relayError(code: String?): String = when (code) {
        "no_session" -> "BNM's relay does not know this session"
        "bad_licence", "bad_license", "bad_jwt" -> "BNM's relay did not accept this computer's licence"
        "busy" -> "another engineer is already connected"
        null -> "BNM's relay reported an error"
        else -> "BNM's relay reported an error ($code)"
    }

    private fun consentFlags(c: SupportConsent): String =
        listOfNotNull("analyzer".takeIf { c.analyzerData }, "records".takeIf { c.records }, "screen".takeIf { c.screen })
            .ifEmpty { listOf("diagnostics-only") }.joinToString(",")

    companion object {
        const val MIN_DURATION_S = 60L
        const val MAX_DURATION_S = 24 * 3_600L
        const val BACKOFF_MIN_MS = 2_000L
        const val BACKOFF_MAX_MS = 30_000L
        const val TICK_MS = 1_000L
        const val READY_TIMEOUT_MS = 20_000L
        const val RECENT_MAX = 200
        const val END_FRAME = """{"t":"end"}"""

        /** The one engine, built on first use; idle until an owner starts a session. */
        val instance: RemoteSupportService by lazy {
            RemoteSupportService(
                // A developer points a build at `wrangler dev` with BNM_RELAY_URL.
                relayUrl = { System.getenv("BNM_RELAY_URL")?.takeIf { it.isNotBlank() } ?: Constants.REMOTE_RELAY_URL },
                // Read fresh from prefs at session start — a lab that activated
                // after launch must not present a stale (or no) licence.
                licenseJwt = { LicenseManager().licenseJwt() },
                labName = { LicenseManager().state.value.labName },
                appVersion = BuildInfo.VERSION,
                transports = KtorWebSocketTransportFactory(),
                verifier = Ed25519Verifier(RemoteSupportKeys.SUPPORT_PUBLIC_KEY_SPKI_B64),
                clock = SystemRemoteClock,
                scope = CoroutineScope(
                    SupervisorJob() + Dispatchers.IO +
                        CoroutineExceptionHandler { _, e -> AppLog.e("RemoteSupport", "background task failed", e) },
                ),
            )
        }
    }
}
