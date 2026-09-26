package com.bnm.lab.instruments

import com.bnm.lab.diagnostics.AppLog
import com.bnm.lab.remote.FrameScrubber

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.bnm.lab.db.AppDatabase
import com.bnm.lab.db.Instrument_log
import com.bnm.lab.db.Instrument_results
import com.bnm.lab.db.Instruments
import com.bnm.lab.lab.LabOrder
import com.bnm.lab.lab.LabRepository
import com.bnm.lab.lab.LabStatus
import com.bnm.lab.lab.LabTest
import com.bnm.lab.lab.Patient
import com.bnm.lab.license.LicenseState
import com.bnm.lab.staff.Staff
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Analyzer interfacing engine (I0/I1) — the always-on listener service.
 *
 * One listener per enabled [InstrumentConfig]: a TCP accept loop (ktor-network,
 * works on every target — also the simulator/test path) or a serial data
 * listener (desktop only, jSerialComm). Received bytes flow through a
 * per-connection [FrameAssembler] — a single-consumer channel, so chunks are
 * processed strictly in arrival order — until a complete protocol frame
 * appears; the frame is parsed by the instrument's driver and applied to the
 * matching lab order **through [LabRepository.enterResult]** — the single
 * result write path — so instrument values get flags/ref-ranges frozen,
 * per-test + order status walking, and `entered_by = <instrument name>` in
 * the audit trail exactly like a human entry. Measured histograms land in
 * `lab_result_graphs`.
 *
 * Frames that can't be matched to an order (specimen id not keyed on the
 * analyzer, unknown accession) are stored in `instrument_results` — the claim
 * queue — for one-tap assignment from the Instruments screen.
 *
 * Fully offline: nothing here touches the network beyond LISTENING on a local
 * port; licence/sync state is irrelevant (results still reach the platform
 * later via the normal sync spine). Lifecycle follows BillingOutboxSender:
 * app-lifetime scope, constructed once in App(), started from a
 * LaunchedEffect. A tenant switch calls [stopAll] BEFORE the wipe so a frame
 * arriving mid-switch can't seed the new tenant with the old lab's data.
 *
 * Remote-support hardening (2026-09-25): per-instrument counters in the
 * [status] map, [restart] of ONE listener (config edits no longer bounce every
 * analyzer), a self-heal loop that retries an errored listener every
 * [healIntervalMs] (the re-plugged USB-serial cable is the commonest "not
 * linking" ticket), error rows for frames the driver could not read, a log row
 * per ACK/NAK sent, the `verify_pending` gate (settings changed by support →
 * claim queue until the bench confirms one sample) and [dryRun], which parses
 * and maps a pasted frame without routing or writing anything.
 *
 * The [status] map is written from many threads at once — every connection's
 * assembler bumps counters, the accept loop stamps the peer, jSerialComm's
 * thread reports a disconnect, the restart and heal paths relaunch — so every
 * write is a compare-and-set ([MutableStateFlow.update]), never a
 * read-modify-write of `.value`; and a launcher publishes its status BEFORE it
 * starts the job, so the job's real verdict (bound, or bind failed) is never
 * erased by a stale optimistic one. Result ingest only stamps `lastFrameAt`;
 * state changes belong to the transports.
 */
@OptIn(ExperimentalUuidApi::class)
class InstrumentEngine(
    private val db: AppDatabase,
    private val labRepo: LabRepository,
    private val json: Json,
    /** How often an enabled instrument in `error` is retried. Tests shorten it. */
    private val healIntervalMs: Long = 10_000L,
    /** Where the engine's own coroutines run. A test passes an immediate
     *  dispatcher so a listener job reaches its bind verdict INSIDE
     *  `scope.launch` — that is what makes the launch ordering below
     *  reproducible instead of a race nobody can schedule. */
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** How a serial port is opened. A test substitutes one that can fail, or
     *  report the device gone, on cue — [openSerialPort] wants real hardware. */
    private val openSerial: (String, Int, (ByteArray) -> Unit, (String?) -> Unit) -> SerialHandle? = ::openSerialPort,
) {
    private val scope = CoroutineScope(
        SupervisorJob() + dispatcher +
            // Last line of defence for a failure no call site caught: log it, keep
            // the other listeners running, and never let it reach the JVM's
            // uncaught handler (which would treat it as a crash).
            CoroutineExceptionHandler { _, e -> AppLog.e("Analyzer", "background task failed", e) },
    )
    private val q get() = db.instrumentsQueries
    private val oQ get() = db.labOrdersQueries
    private val restartMutex = Mutex()
    private val listeners = mutableMapOf<String, ListenerHandle>()
    /** The bind failure already written to the log per instrument — the self-heal loop retries every few seconds and must not write a row per attempt. */
    private val loggedBindFailure = mutableMapOf<String, String>()

    private val _status = MutableStateFlow<Map<String, InstrumentStatus>>(emptyMap())
    val status: StateFlow<Map<String, InstrumentStatus>> = _status

    /** What one apply attempt did — the live path queues on !matched, the
     *  claim path surfaces it as an error instead (never re-queue, never
     *  mark applied). */
    data class ApplyOutcome(val matched: Boolean, val applied: Int, val summary: String)

    /**
     * What [dryRun] found out about a pasted frame — the parse and the mapping
     * an incoming frame WOULD get, with nothing written. Ids are masked: this
     * travels to a support engineer.
     */
    data class DryRun(
        val driver: String,
        /** The driver produced a result frame. */
        val parsed: Boolean,
        /** Why not, or a caveat (QC run, test frame) — plain words. */
        val note: String? = null,
        val specimenIdMasked: String? = null,
        val paramKeys: List<String> = emptyList(),
        val units: Map<String, String> = emptyMap(),
        val histograms: List<String> = emptyList(),
        /** An open order with that accession exists (lookup only). */
        val wouldMatch: Boolean = false,
        val accessionMasked: String? = null,
        val orderStatus: String? = null,
        /** "ordered_tests" when an order matched, else "catalog" (best overlap
         *  among active tests — helps fix a param map before any order exists). */
        val mappingBasis: String? = null,
        val testName: String? = null,
        /** analyzerKey → catalog parameter key. */
        val mapped: Map<String, String> = emptyMap(),
        val unmapped: List<String> = emptyList(),
        /** "HGB bogus → g/dL" — pairs the unit converter cannot bridge. */
        val unitMismatches: List<String> = emptyList(),
    )

    /**
     * What [prepareListener] hands back: the status to publish and, for a
     * transport whose real work happens in a coroutine (TCP), the step that
     * starts it — run only AFTER the status is in the map.
     */
    private class Launch(val status: InstrumentStatus, val start: (() -> Unit)? = null)

    private class ListenerHandle(
        val job: Job?,
        val serial: SerialHandle?,
        val assembler: FrameAssembler?,
    ) {
        /** Waits for the accept loop to release its socket: the relaunch that
         *  follows binds the SAME port, and would lose the race to the old one. */
        suspend fun close() {
            runCatching { serial?.close() }
            assembler?.abandon()
            job?.let { runCatching { it.cancel(); it.join() } }
        }
    }

    // ── lifecycle ──

    fun start() {
        scope.launch { restartAll() }
        scope.launch {
            while (currentCoroutineContext().isActive) {
                delay(healIntervalMs)
                runCatching { healOnce() }
            }
        }
    }

    /** Tear down and relaunch every listener from current config. Called on
     *  start and when the licence identity changes (tenant switch). Config
     *  edits use [restart] for the one row they touched. Counters survive. */
    suspend fun restartAll() = restartMutex.withLock {
        withContext(Dispatchers.Default) {
            listeners.values.toList().forEach { it.close() }
            listeners.clear()
            val configs = runCatching { q.listInstruments().executeAsList().map { it.toModel() } }
                .getOrDefault(emptyList())
            // Rows that no longer exist leave the map; the rest are relaunched
            // one at a time, each published before its job starts.
            val ids = configs.map { it.id }.toSet()
            _status.update { m -> m.filterKeys { it in ids } }
            for (cfg in configs) relaunch(cfg)
        }
    }

    /**
     * Restart exactly ONE listener from its current config, leaving every
     * other analyzer's socket/port untouched. A row that no longer exists is
     * dropped from the status map. Busy-guarding ("a frame arrived 3 s ago")
     * is the caller's decision — the remote tool refuses, the engine obeys.
     */
    suspend fun restart(id: String) = restartMutex.withLock {
        withContext(Dispatchers.Default) {
            listeners.remove(id)?.close()
            val cfg = runCatching { q.instrumentById(id).executeAsOneOrNull()?.toModel() }.getOrNull()
            if (cfg == null) _status.update { m -> m - id } else relaunch(cfg)
        }
    }

    /**
     * The self-heal pass: every ENABLED instrument whose listener sits in
     * `error` (serial open failed, cable unplugged, TCP bind refused) is
     * re-opened. Silent by design — [setStatus] logs the transition when the
     * state or its detail actually changes, never each attempt, and the bind
     * failure row in [tcpListenLoop] is written once per distinct error too.
     * Public so a test (and the remote tool) can run one pass on demand.
     */
    suspend fun healOnce() {
        val errored = _status.value.filterValues { it.state == "error" }.keys.toList()
        if (errored.isEmpty()) return
        restartMutex.withLock {
            withContext(Dispatchers.Default) {
                for (id in errored) {
                    val current = _status.value[id] ?: continue
                    if (current.state != "error") continue
                    val cfg = runCatching { q.instrumentById(id).executeAsOneOrNull()?.toModel() }.getOrNull()
                        ?: continue
                    if (!cfg.enabled) continue
                    listeners.remove(id)?.close()
                    val snapshot = _status.value[id]
                    val launch = prepareListener(cfg)
                    if (cfg.transport == InstrumentTransport.TCP && launch.status.state == "listening") {
                        // The accept loop reports the real verdict (bound, or the
                        // same bind failure). Keep showing the error until then —
                        // otherwise every retry would flip error → listening →
                        // error and log two transitions a pass.
                        launch.start?.invoke()
                        continue
                    }
                    publishLaunch(id, snapshot, launch.status)
                    launch.start?.invoke()
                }
            }
        }
    }

    /**
     * Launch (or park) one instrument. Its status goes into the map FIRST —
     * counters carried over, the old bind time dropped — and only then does
     * the job start, and the launcher never writes that entry again: a TCP
     * accept loop reaches its bind verdict within microseconds, and an
     * optimistic "listening" landing on top of it would leave an instrument
     * that reads `listening` with no listener behind it and no `error` for
     * [healOnce] to retry. Must run under [restartMutex].
     */
    private fun relaunch(cfg: InstrumentConfig) {
        val snapshot = _status.value[cfg.id]
        val launch = if (!cfg.enabled) Launch(InstrumentStatus("off", "Disabled")) else prepareListener(cfg)
        publishLaunch(cfg.id, snapshot, launch.status)
        launch.start?.invoke()
    }

    /**
     * Publish a launcher's verdict — unless the transport has already given a
     * better one. TCP defers its work to [Launch.start], but SERIAL opens its
     * port inside [prepareListener], and a device that vanishes while the port
     * is opening reports itself through `onClosed` BEFORE this line: an
     * optimistic "listening" written over that would leave a dead port reading
     * `listening`, which [healOnce] never retries because it only looks at
     * `error`. The comparison rides inside the same compare-and-set as the
     * write, so nothing can slip between the two. [snapshot] is what the entry
     * was before the transport was touched.
     */
    private fun publishLaunch(id: String, snapshot: InstrumentStatus?, fresh: InstrumentStatus) {
        var before: InstrumentStatus? = null
        var after: InstrumentStatus? = null
        _status.update { m ->
            before = m[id]
            if (before !== snapshot) {
                after = null
                return@update m
            }
            // This listener is new until its own accept loop says otherwise.
            val next = carry(before?.copy(boundAt = null), fresh)
            after = next
            m + (id to next)
        }
        after?.let { logTransition(id, before, it) }
    }

    /** Stop every listener without touching config. Used by the tenant-switch
     *  wipe: no analyzer byte may land between "old lab erased" and "new lab
     *  activated". [restartAll] brings listeners back. */
    suspend fun stopAll() = restartMutex.withLock {
        listeners.values.toList().forEach { it.close() }
        listeners.clear()
        _status.update { m -> m.mapValues { (_, st) -> carry(st, InstrumentStatus("off", "Stopped")) } }
    }

    /**
     * Open the transport for one enabled instrument. TCP hands back the
     * optimistic status and a [Launch.start] that starts the accept loop —
     * the caller publishes first, then starts. Serial opens the port right
     * here (jSerialComm is synchronous) and needs no second step.
     */
    private fun prepareListener(cfg: InstrumentConfig): Launch = when (cfg.transport) {
        InstrumentTransport.TCP -> {
            val port = cfg.tcpPort
            if (port == null || port !in 1..65535) {
                Launch(InstrumentStatus("error", "No TCP port set"))
            } else {
                if (InstrumentTcpRole.normalise(cfg.tcpRole) == InstrumentTcpRole.CONNECT) {
                    val host = cfg.analyzerHost?.trim().orEmpty()
                    if (host.isEmpty()) {
                        // Naming the field is the whole value of this message:
                        // "connection failed" would send a bench hunting cables
                        // for an hour over an empty text box.
                        Launch(InstrumentStatus("error", "This analyzer must be dialled — set its address"))
                    } else {
                        Launch(InstrumentStatus("connecting", "Dialling $host:$port")) {
                            val job = scope.launch { tcpDialLoop(cfg, host, port) }
                            listeners[cfg.id] = ListenerHandle(job, serial = null, assembler = null)
                        }
                    }
                } else {
                    Launch(InstrumentStatus("listening", "TCP port $port")) {
                        val job = scope.launch { tcpListenLoop(cfg, port) }
                        listeners[cfg.id] = ListenerHandle(job, serial = null, assembler = null)
                    }
                }
            }
        }
        InstrumentTransport.SERIAL -> {
            val portName = cfg.serialPort
            when {
                !serialSupported() ->
                    Launch(InstrumentStatus("error", "Serial isn't available on this device — use the lab PC"))
                portName.isNullOrBlank() ->
                    Launch(InstrumentStatus("error", "No serial port chosen"))
                else -> {
                    val assembler = FrameAssembler(cfg)
                    // submit() (not launch-per-chunk): jSerialComm delivers
                    // chunks sequentially on its listener thread, and the
                    // assembler's single consumer preserves that order —
                    // separate coroutine launches would not.
                    val handle = openSerial(
                        portName, cfg.baud,
                        { bytes -> assembler.submit(bytes) },                     // onData
                        { reason ->                                               // onClosed
                            setStatus(cfg.id, InstrumentStatus("error", reason ?: "Serial port closed"))
                            scope.launch { logRow(cfg, "error", reason ?: "Serial port closed", null) }
                        },
                    )
                    if (handle == null) {
                        assembler.abandon()
                        Launch(InstrumentStatus("error", "Couldn't open $portName — in use, or unplugged?"))
                    } else {
                        listeners[cfg.id] = ListenerHandle(job = null, serial = handle, assembler = assembler)
                        Launch(InstrumentStatus("listening", "$portName @ ${cfg.baud}", boundAt = nowIso()))
                    }
                }
            }
        }
        else -> Launch(InstrumentStatus("error", "Unknown transport '${cfg.transport}'"))
    }

    /**
     * The OTHER direction: this app dials the analyzer and reads results off
     * the socket it opened.
     *
     * WHY THIS EXISTS. A listener and a server both wait, so pointing the wrong
     * one at the other produces no data, no error and no clue — the single
     * worst failure shape in this whole subsystem. The Mindray BC-5x is a TCP
     * server ("Port is fixed as 5100", manual 5.2) whose Communication Setup
     * screen has no host-address field at all: it cannot dial out. So for that
     * analyzer the app must be the client.
     *
     * The connection is held OPEN and results stream down it; when the analyzer
     * drops it (a reboot, a cable, an idle timeout) we redial with a capped
     * backoff. Every frame is acked on this same socket, exactly as in the
     * listening path, because the HL7 ack is a property of the protocol and not
     * of who dialled.
     */
    private suspend fun tcpDialLoop(cfg: InstrumentConfig, host: String, port: Int) {
        val selector = SelectorManager(Dispatchers.Default)
        var backoffMs = 1_000L
        var lastError: String? = null
        try {
            while (currentCoroutineContext().isActive) {
                try {
                    val socket = aSocket(selector).tcp().connect(host, port)
                    backoffMs = 1_000L
                    lastError = null
                    loggedBindFailure.remove(cfg.id)
                    setStatus(
                        cfg.id,
                        InstrumentStatus("listening", "Connected to $host:$port", boundAt = nowIso(), peerIp = host),
                    )
                    logRow(cfg, "info", "Connected to $host:$port", null)
                    val out = socket.openWriteChannel(autoFlush = true)
                    val assembler = FrameAssembler(cfg) { bytes -> out.writeFully(bytes, 0, bytes.size) }
                    try {
                        val channel = socket.openReadChannel()
                        val buf = ByteArray(8 * 1024)
                        while (true) {
                            val n = channel.readAvailable(buf, 0, buf.size)
                            if (n < 0) break
                            if (n > 0) assembler.submit(buf.copyOf(n))
                        }
                    } finally {
                        // Drain BEFORE closing: the last chunk may complete a
                        // message whose ACK still has to go out on this socket.
                        try { assembler.finish() } finally { runCatching { socket.close() } }
                    }
                    logRow(cfg, "info", "Analyzer closed the connection", null)
                    setStatus(cfg.id, InstrumentStatus("connecting", "Reconnecting to $host:$port"))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // One log row per DISTINCT failure: a lab that leaves the
                    // analyzer off overnight would otherwise wake up to
                    // thousands of identical rows and a log with no history in
                    // it, which is how the useful evidence gets trimmed away.
                    val reason = e.message?.take(120) ?: e::class.simpleName.orEmpty()
                    if (reason != lastError) {
                        lastError = reason
                        logRow(cfg, "error", "Could not reach $host:$port — $reason", null)
                    }
                    setStatus(cfg.id, InstrumentStatus("error", "Can't reach $host:$port — is the analyzer on?"))
                }
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
            }
        } finally {
            runCatching { selector.close() }
        }
    }

    private suspend fun tcpListenLoop(cfg: InstrumentConfig, port: Int) {
        val selector = SelectorManager(Dispatchers.Default)
        try {
            val server = aSocket(selector).tcp().bind("0.0.0.0", port)
            try {
                // The real "listening" moment (launchListener reported it optimistically).
                loggedBindFailure.remove(cfg.id)
                setStatus(cfg.id, InstrumentStatus("listening", "TCP port $port", boundAt = nowIso()))
                logRow(cfg, "info", "Listening on TCP port $port", null)
                while (currentCoroutineContext().isActive) {
                    val socket = server.accept()
                    // "Test connection" dials 127.0.0.1 and is accepted like any
                    // client; letting it stamp peerIp would erase the one fact
                    // that says WHICH box is talking — and put "peer=127.0.0.1"
                    // in the support report the lab pastes to BNM.
                    peerIpOf(socket.remoteAddress)
                        ?.takeIf { !LinkCheck.isLoopback(it) }
                        ?.let { ip -> update(cfg.id) { it.copy(peerIp = ip) } }
                    scope.launch {
                        // One assembler per connection: analyzers open, send, close.
                        // HL7 analyzers wait for an ACK on the same socket, so the
                        // assembler gets a way to write back.
                        val out = socket.openWriteChannel(autoFlush = true)
                        val assembler = FrameAssembler(cfg) { bytes -> out.writeFully(bytes, 0, bytes.size) }
                        try {
                            val channel = socket.openReadChannel()
                            val buf = ByteArray(8 * 1024)
                            while (true) {
                                val n = channel.readAvailable(buf, 0, buf.size)
                                if (n < 0) break
                                if (n > 0) assembler.submit(buf.copyOf(n))
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            // An analyzer dropping its connection mid-send is routine,
                            // not a crash. Record it against the instrument and let the
                            // listener keep serving — escaping here would reach the
                            // JVM's uncaught handler.
                            logRow(cfg, "info", "connection closed: ${e::class.simpleName}", null)
                        } finally {
                            // Drain BEFORE closing: the last chunk may complete a
                            // message whose ACK still has to go out on this socket.
                            try { assembler.finish() } finally { runCatching { socket.close() } }
                        }
                    }
                }
            } finally {
                runCatching { server.close() }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            val detail = e.message ?: "TCP listen failed on port $port"
            // The self-heal loop retries every few seconds: one row per
            // distinct failure, not one per attempt.
            val repeat = loggedBindFailure[cfg.id] == detail
            loggedBindFailure[cfg.id] = detail
            setStatus(cfg.id, InstrumentStatus("error", detail))
            if (!repeat) logRow(cfg, "error", "TCP listen failed: ${e.message}", null)
        } finally {
            runCatching { selector.close() }
        }
    }

    /**
     * A state transition, atomic against the counter bumps landing from other
     * threads. Counters, last-frame and last-error stamps carry over from the
     * previous status.
     */
    private fun setStatus(id: String, s: InstrumentStatus) {
        var before: InstrumentStatus? = null
        var merged: InstrumentStatus = s
        _status.update { m ->
            before = m[id]
            merged = carry(before, s)
            m + (id to merged)
        }
        logTransition(id, before, merged)
    }

    /** Transitions only — lastFrameAt moves on every frame and would drown the log. */
    private fun logTransition(id: String, before: InstrumentStatus?, after: InstrumentStatus) {
        if (before?.state != after.state || before.detail != after.detail) {
            if (after.state == "error") AppLog.w("Analyzer", "$id -> ${after.state}: ${after.detail.orEmpty()}")
            else AppLog.i("Analyzer", "$id -> ${after.state}: ${after.detail.orEmpty()}")
        }
    }

    /**
     * A counter bump or stamp on an instrument whose state does not change —
     * a compare-and-set, so two connections counting at once never lose an
     * increment. No-op for an unknown id.
     */
    private fun update(id: String, f: (InstrumentStatus) -> InstrumentStatus) {
        _status.update { m -> m[id]?.let { m + (id to f(it)) } ?: m }
    }

    /**
     * [fresh] decides state/detail; everything the listener accumulated
     * ([prev]) rides along: counters, lastFrameAt, peer, the last error. A
     * new error stamps lastError/lastErrorAt; leaving `listening` clears boundAt.
     */
    private fun carry(prev: InstrumentStatus?, fresh: InstrumentStatus): InstrumentStatus {
        if (prev == null) return fresh.copy(
            lastError = fresh.detail.takeIf { fresh.state == "error" },
            lastErrorAt = if (fresh.state == "error") nowIso() else null,
        )
        val newError = fresh.state == "error" && (prev.state != "error" || prev.detail != fresh.detail)
        return prev.copy(
            state = fresh.state,
            detail = fresh.detail,
            lastFrameAt = fresh.lastFrameAt ?: prev.lastFrameAt,
            peerIp = fresh.peerIp ?: prev.peerIp,
            boundAt = if (fresh.state == "listening") (fresh.boundAt ?: prev.boundAt) else null,
            lastError = if (newError) fresh.detail else prev.lastError,
            lastErrorAt = if (newError) nowIso() else prev.lastErrorAt,
        )
    }

    /**
     * Byte accumulator → complete frames → ingest. All producers hand bytes to
     * [submit]; a single consumer coroutine processes them strictly in arrival
     * order (an unbounded channel, closed on [finish]/[abandon]).
     */
    private inner class FrameAssembler(
        private val cfg: InstrumentConfig,
        /** Writes bytes back to the analyzer (the ACK an HL7 device waits for);
         *  null on one-way transports (serial has no write path today). */
        private val reply: (suspend (ByteArray) -> Unit)? = null,
    ) {
        private val buffer = StringBuilder()
        private val intake = Channel<ByteArray>(Channel.UNLIMITED)
        private val job: Job = scope.launch {
            for (bytes in intake) {
                update(cfg.id) { it.copy(bytesIn = it.bytesIn + bytes.size) }
                buffer.append(bytes.decodeToString())
                trimOverflow()
                drainFrames()
            }
            if (buffer.isNotBlank()) {
                logRow(cfg, "info",
                    "Connection closed with ${buffer.length} unframed bytes (ignored)",
                    buffer.toString())
            }
            buffer.setLength(0)
        }

        /** Thread-safe, non-suspending — callable straight from the serial
         *  listener thread. Order of calls = order of processing. */
        fun submit(bytes: ByteArray) {
            intake.trySend(bytes)
        }

        /** Close the intake and wait until every queued byte was processed. */
        suspend fun finish() {
            intake.close()
            job.join()
        }

        fun abandon() {
            intake.close()
            job.cancel()
        }

        /**
         * Bound the buffer WITHOUT losing the frame in flight. The original cap
         * (512 KB, oldest half dropped) suited 2 KB Mispa frames and would have
         * been fatal for an HL7 result carrying three histograms and a BMP: a
         * few hundred KB arriving in 1.5 KB chunks, so the cut took the <VT>
         * with it — the message never framed and the analyzer never got its
         * ACK. HL7 keeps everything from the last frame start and lets one
         * frame grow to [MAX_HL7_BYTES]; past that it is abandoned with an
         * MSA|AE (so the analyzer stops waiting) and logged.
         */
        private suspend fun trimOverflow() {
            if (cfg.driver != "mindray_hl7") {
                if (buffer.length > 512 * 1024) buffer.deleteRange(0, buffer.length - 256 * 1024)
                return
            }
            if (buffer.length <= MAX_HL7_BYTES) return
            val start = buffer.lastIndexOf(SB_STR)
            if (start < 0) { buffer.setLength(0); return }        // no frame start anywhere: junk
            if (start > 0) buffer.deleteRange(0, start)           // junk before the open frame
            if (buffer.length <= MAX_HL7_BYTES) return
            val head = buffer.substring(1, minOf(buffer.length, 4000))
            logRow(cfg, "error",
                "HL7 message passed ${MAX_HL7_BYTES / (1024 * 1024)} MB with no end-of-block — dropped, MSA|AE sent", head)
            if (reply != null) {
                val nak = Hl7Ack.forMessage(Hl7Message.parse(head), "AE", ackControlId = ackControlId(), timestamp = hl7Timestamp())
                runCatching { reply(nak) }
                    .onSuccess { ackSent(cfg, "NAK sent (MSA|AE — message abandoned)") }
                    .onFailure { logRow(cfg, "error", "MSA|AE could not be sent: ${it.message}", null) }
            }
            update(cfg.id) { it.copy(framesIgnored = it.framesIgnored + 1) }
            buffer.setLength(0)
        }

        private suspend fun drainFrames() {
            while (true) {
                when (cfg.driver) {
                    "mispa_count_x" -> {
                        val (text, rest) = MispaCountX.extractFrameText(buffer.toString())
                        buffer.setLength(0); buffer.append(rest)
                        if (text == null) return
                        update(cfg.id) { it.copy(framesIn = it.framesIn + 1) }
                        val frame = MispaCountX.parse(text)
                        if (frame == null) {
                            // It framed ($$$…###) but the driver found nothing in it.
                            // Silence here was the worst kind of "not linking": bytes
                            // arriving, nothing on screen, nothing in the log.
                            frameNotUnderstood(cfg, text)
                            continue
                        }
                        ingestMispa(cfg, frame)
                    }
                    "mindray_hl7" -> {
                        // A long message arrives in hundreds of chunks; don't copy
                        // the whole buffer for each until an end-of-block is in.
                        if (buffer.indexOf(EB_STR) < 0) return
                        val (message, rest) = Mllp.extract(buffer.toString())
                        buffer.setLength(0); buffer.append(rest)
                        if (message == null) return
                        update(cfg.id) { it.copy(framesIn = it.framesIn + 1) }
                        ingestHl7(cfg, message, reply)
                    }
                    else -> {
                        logRow(cfg, "error", "No parser for driver '${cfg.driver}'", null)
                        update(cfg.id) { it.copy(framesIgnored = it.framesIgnored + 1) }
                        buffer.setLength(0)
                        return
                    }
                }
            }
        }
    }

    // ── ingestion (Mispa Count X) ──

    private suspend fun ingestMispa(cfg: InstrumentConfig, frame: MispaCountX.Frame) {
        // A stamp, never a transition: the last chunk of a frame can drain
        // AFTER jSerialComm reported the cable gone, and flipping `error` back
        // to `listening` here would hide that from the self-heal loop.
        update(cfg.id) { it.copy(framesParsed = it.framesParsed + 1, lastFrameAt = nowIso()) }
        val stored = StoredInstrumentFrame(
            driver = cfg.driver,
            specimenId = frame.specimenId,
            patientId = frame.patientId,
            date = frame.date,
            sequenceId = frame.sequenceId,
            params = frame.params,
            histograms = frame.histograms,
            meta = buildMap {
                frame.discriminators?.let { put("discriminators", it) }
                if (frame.diseaseFlags.isNotEmpty()) put("disease_flags", frame.diseaseFlags.joinToString("; "))
            },
        )
        logRow(cfg, "rx",
            "Result frame · specimen ${frame.specimenId ?: "—"} · ${frame.params.size} params · " +
                "${frame.histograms.size} histograms", frame.raw)
        routeFrame(cfg, stored)
    }

    // ── ingestion (Mindray BC-5130 / 5000 / 5150 — HL7 v2.3.1 over MLLP) ──

    /**
     * One MLLP-framed HL7 message. The ACK goes FIRST and unconditionally:
     * the analyzer blocks on it and marks the sample "transmission failed"
     * (and may retransmit) without one — even for messages we then ignore.
     */
    private suspend fun ingestHl7(cfg: InstrumentConfig, text: String, reply: (suspend (ByteArray) -> Unit)?) {
        val msg = Hl7Message.parse(text)
        if (reply != null) {
            val ack = Hl7Ack.forMessage(msg, "AA", ackControlId = ackControlId(), timestamp = hl7Timestamp())
            runCatching { reply(ack) }
                .onSuccess { ackSent(cfg, "ACK sent (MSA|AA for ${msg.controlId.ifBlank { "message" }})") }
                .onFailure { logRow(cfg, "error", "ACK could not be sent: ${it.message}", null) }
        } else {
            logRow(cfg, "info", "No reply path on this transport — analyzer will not get an ACK", null)
        }
        // Blobs (histograms, BMPs) are the bulk of a message; the log keeps the
        // head, which is where every human-readable field lives.
        val excerpt = text.take(4000)
        val frame = MindrayBc5x.parse(msg, text)
        if (frame == null) {
            update(cfg.id) { it.copy(framesIgnored = it.framesIgnored + 1) }
            logRow(cfg, "rx", "${msg.messageType.ifBlank { "message" }} acknowledged and ignored (not a result)", excerpt)
            return
        }
        if (frame.isQc) {
            update(cfg.id) { it.copy(framesIgnored = it.framesIgnored + 1) }
            logRow(cfg, "rx", "QC result acknowledged and ignored (${frame.specimenId ?: "no id"})", excerpt)
            return
        }
        update(cfg.id) { it.copy(framesParsed = it.framesParsed + 1, lastFrameAt = nowIso()) }   // a stamp, never a transition (see ingestMispa)
        val stored = StoredInstrumentFrame(
            driver = cfg.driver,
            specimenId = frame.specimenId,
            patientId = frame.patientId,
            // Carried, not dropped: the whole premise of "Create order from this
            // result" is that the bench DID key the patient in. Throwing the name
            // away here made the operator read it off the analyzer's screen and
            // retype it, which is how "Aasha Menon" becomes a second patient.
            patientName = frame.patientName,
            patientSex = frame.patientSex,
            date = frame.date,
            sequenceId = frame.sequenceId,
            params = frame.params,
            histograms = frame.histograms,
            meta = frame.meta,
            units = frame.units,
            images = frame.images,
        )
        logRow(cfg, "rx",
            "Result · specimen ${frame.specimenId ?: "—"} · ${frame.params.size} params · " +
                "${frame.histograms.size} histograms · ${frame.images.size} images" +
                (frame.meta["test_mode"]?.let { " · $it" } ?: ""), excerpt)
        routeFrame(cfg, stored)
    }

    /**
     * Raw transport bytes through the same assembler the listeners use, then
     * wait until every frame in them was routed. What the TCP loop does per
     * connection minus the socket — the engine test drives the whole
     * MLLP → ACK → route → apply → graphs path through it. [chunk] splits the
     * delivery the way a TCP stack fragments a long message.
     */
    internal suspend fun ingestBytes(
        cfg: InstrumentConfig,
        bytes: ByteArray,
        chunk: Int = bytes.size,
        reply: (suspend (ByteArray) -> Unit)? = null,
    ) {
        val assembler = FrameAssembler(cfg, reply)
        var at = 0
        while (at < bytes.size) {
            val end = minOf(bytes.size, at + chunk.coerceAtLeast(1))
            assembler.submit(bytes.copyOfRange(at, end))
            at = end
        }
        assembler.finish()
    }

    /** Match a parsed frame to an open order, else the claim queue. */
    private suspend fun routeFrame(cfg: InstrumentConfig, stored: StoredInstrumentFrame) {
        // Read the flag fresh: the running listener's cfg was captured at launch,
        // and "Verified" clears the flag without restarting the listener.
        if (isVerifyPending(cfg.id)) {
            queueUnmatched(cfg, stored, VERIFY_PENDING_REASON)
            return
        }
        val order = stored.specimenId?.let { findOrder(it) }
        if (order == null) {
            queueUnmatched(cfg, stored,
                if (stored.specimenId == null) "no specimen id keyed on the analyzer"
                else "no order matches '${stored.specimenId}'")
            return
        }
        if (order.status !in ENTRY_OPEN) {
            queueUnmatched(cfg, stored, "order ${order.accessionNo} is ${order.status} — results locked")
            return
        }
        val outcome = applyFrameToOrder(cfg, stored, order)
        if (!outcome.matched) queueUnmatched(cfg, stored, outcome.summary)
        else update(cfg.id) { it.copy(framesApplied = it.framesApplied + 1) }
    }

    private suspend fun isVerifyPending(instrumentId: String): Boolean =
        instrumentId.isNotBlank() && withContext(Dispatchers.Default) {
            runCatching { q.instrumentById(instrumentId).executeAsOneOrNull()?.verify_pending == 1L }.getOrDefault(false)
        }

    /** An ACK/NAK went out: a row so the log shows both directions, and a count. */
    private suspend fun ackSent(cfg: InstrumentConfig, summary: String) {
        update(cfg.id) { it.copy(acksSent = it.acksSent + 1) }
        logRow(cfg, "info", summary, null)
    }

    /**
     * A complete frame the driver returned null for. The excerpt is scrubbed
     * the way a shared raw frame is (names and demographics out — a Mispa
     * PatientID is a typed name on some analyzers, and [FrameScrubber.maskIds]
     * only masks tokens with a digit in them), then masked; this summary goes
     * to the activity log, which is emailed and served by `logs.tail`. A driver
     * without a scrubber gets no excerpt at all.
     */
    private suspend fun frameNotUnderstood(cfg: InstrumentConfig, text: String) {
        update(cfg.id) { it.copy(framesIgnored = it.framesIgnored + 1) }
        val cleaned = FrameScrubber.scrubRaw(cfg.driver, text)
        val excerpt = cleaned?.let { FrameScrubber.maskIds(it.take(120)).replace('\r', ' ').replace('\n', ' ') }
        logRow(cfg, "error", "Frame not understood by ${cfg.driver}" + (excerpt?.let { ": $it" } ?: ""), null)
    }

    private var ackSeq = 0
    private fun ackControlId(): String = "${hl7Timestamp()}${(++ackSeq % 1000).toString().padStart(3, '0')}"

    /** yyyyMMddHHmmss in the device timezone — what MSH-7 wants. */
    private fun hl7Timestamp(): String {
        val dt = kotlin.time.Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        val d = dt.date.toString().filter { it.isDigit() }
        return d + dt.hour.toString().padStart(2, '0') + dt.minute.toString().padStart(2, '0') + dt.second.toString().padStart(2, '0')
    }

    /**
     * Exact accession first, then numeric-tail resolution ('42' → ACC-S1-00042).
     * The tail shortcut only auto-applies to the series this seat issues from
     * NOW: on a multi-seat lab another seat's ACC-S2-00042 may not have synced
     * here yet, so a bare '42' meant for it would silently land on the wrong
     * patient. Other seats' samples take the claim queue (one tap) instead, and
     * so do bare numbers from the ACC-S1 series every computer used up to 1.2.0:
     * a bare number there cannot say whose tube it is. A full barcode still
     * matches exactly.
     */
    private suspend fun findOrder(specimenId: String): LabOrder? {
        labRepo.orderByAccession(specimenId.trim())?.let { return it }
        val digits = specimenId.trim().takeIf { it.isNotEmpty() && it.all { c -> c.isDigit() } }
            ?: return null
        val tail = digits.trimStart('0').ifEmpty { "0" }.padStart(5, '0')
        val hits = withContext(Dispatchers.Default) {
            q.ordersByAccessionTail(tail).executeAsList()
        }
        if (hits.size != 1) return null       // ambiguous or none → claim queue
        val ownSeries = labRepo.ownAccessionSeries()
        if (!hits[0].accession_no.startsWith(ownSeries, ignoreCase = true)) return null
        return labRepo.orderById(hits[0].id)
    }

    /**
     * Write a stored frame into [order]: pick the ordered test whose catalog
     * parameters best overlap the analyzer's params, then enterResult() each
     * mapped value and persist histograms against that test. Pure apply — the
     * CALLER decides what a non-match means (live path queues it, claim path
     * reports it).
     */
    suspend fun applyFrameToOrder(cfg: InstrumentConfig, stored: StoredInstrumentFrame, order: LabOrder): ApplyOutcome {
        val overrides = parseOverrides(cfg.paramMapJson)
        val tests = labRepo.orderTests(order.id).mapNotNull { labRepo.testById(it.testId) }
        val (test, mapping) = selectMapping(stored.params.keys, tests, overrides)
            ?: return ApplyOutcome(false, 0,
                "no ordered test on ${order.accessionNo} takes these parameters")

        var applied = 0
        val failed = mutableListOf<String>()
        val unconverted = mutableListOf<String>()
        for ((analyzerKey, paramKey) in mapping) {
            val raw = stored.params[analyzerKey] ?: continue
            // The analyzer's unit → the catalog's (10*9/L → cells/cumm, g/L → g/dL).
            // A pair the app cannot bridge passes through unchanged — the value
            // still lands (a number on the bench beats nothing) but the log
            // says so in red: 10*4/uL printed as /cumm is off by ten thousand.
            val target = test.parameters.firstOrNull { it.key == paramKey }?.unit
            val from = stored.units[analyzerKey]
            val value = AnalyzerUnits.convert(raw, from, target)
            if (value == raw && AnalyzerUnits.needsConversion(from, target)) unconverted += "$analyzerKey $from → $target"
            labRepo.enterResult(order.id, test.id, paramKey, value, enteredBy = cfg.name)
                .onSuccess { applied++ }
                .onFailure { failed += "$analyzerKey: ${it.message}" }
        }
        val unmappedCount = stored.params.size - mapping.size
        if (unconverted.isNotEmpty()) {
            logRow(cfg, "error",
                "Unit not converted — stored as the analyzer sent it: ${unconverted.joinToString(", ")}. " +
                    "Give the catalog parameter a unit the app can convert to, or correct the value by hand.", null)
        }

        // One graph row per kind: measured points, the analyzer's bitmap, or both.
        val graphKinds = stored.histograms.keys + stored.images.keys
        if (graphKinds.isNotEmpty()) {
            val now = nowIso()
            val meta = stored.meta.takeIf { it.isNotEmpty() }
                ?.let { m -> json.encodeToString(STRING_MAP, m) }
            withContext(Dispatchers.Default) {
                for (kind in graphKinds) {
                    q.upsertGraph(order.id, test.id, kind,
                        json.encodeToString(DOUBLE_LIST, stored.histograms[kind].orEmpty()), meta, now,
                        stored.images[kind])
                }
            }
        }

        val summary = buildString {
            append("Applied $applied/${stored.params.size} params to ${order.accessionNo} · ${test.name}")
            if (stored.histograms.isNotEmpty()) append(" · ${stored.histograms.size} histograms")
            if (stored.images.isNotEmpty()) append(" · ${stored.images.size} images")
            if (unmappedCount > 0) append(" · $unmappedCount unmapped")
            if (unconverted.isNotEmpty()) append(" · ${unconverted.size} unit mismatch")
            if (failed.isNotEmpty()) append(" · ${failed.size} failed (${failed.first()})")
        }
        logRow(cfg, "info", summary, null)
        return ApplyOutcome(true, applied, summary)
    }

    // ── claim queue ──

    private suspend fun queueUnmatched(cfg: InstrumentConfig, stored: StoredInstrumentFrame, reason: String) {
        update(cfg.id) { it.copy(framesUnmatched = it.framesUnmatched + 1) }
        withContext(Dispatchers.Default) {
            q.insertUnmatched(
                Uuid.random().toString(), cfg.id.ifBlank { null }, stored.specimenId,
                json.encodeToString(StoredInstrumentFrame.serializer(), stored), nowIso(),
            )
        }
        logRow(cfg, "info", "Queued for manual claim — $reason", null)
    }

    /**
     * The queue as the screen shows it: every waiting row with its frame
     * already decoded.
     *
     * Decoding here rather than in the composable is not tidiness — a
     * recomposition would otherwise re-parse every payload (histograms and
     * base64 scattergrams included) on the UI thread, several times a second
     * while a dialog animates. A row whose payload will not parse is dropped
     * rather than crashing the screen; it is still in the table, and the
     * traffic log has the frame that produced it.
     *
     * `mapToList(Dispatchers.Default)` only moves the `executeAsList()`; the
     * decode below runs wherever the COLLECTOR runs, which for `collectAsState`
     * is the composition's Main dispatcher. Hence the trailing `flowOn` — without
     * it the paragraph above is a wish, not a description. The screen must also
     * `remember` this flow: a new instance per recomposition restarts the
     * subscription and re-decodes the whole queue on every keystroke.
     */
    fun queueFlow(): Flow<List<QueuedFrame>> =
        q.listUnmatched().asFlow().mapToList(Dispatchers.Default).map { rows ->
            rows.mapNotNull { row ->
                val frame = runCatching {
                    json.decodeFromString(StoredInstrumentFrame.serializer(), row.payload_json)
                }.getOrNull() ?: return@mapNotNull null
                QueuedFrame(
                    id = row.id,
                    instrumentId = row.instrument_id,
                    specimenId = row.specimen_id,
                    receivedAt = row.received_at,
                    frame = frame,
                )
            }
        }.flowOn(Dispatchers.Default)

    /**
     * The orders a queued result could be assigned to, best guess first.
     *
     * The ranking is the same [selectMapping] the apply path runs — computed
     * against the same explicit overrides from the instrument's param map — so
     * "matches 18 of 22" is a promise the assignment keeps rather than a second
     * opinion that can drift from it. The whole catalog is read once and shared
     * across candidates: a lab with 223 tests and 80 recent orders would
     * otherwise do hundreds of single-row lookups to draw one dialog.
     *
     * [query] is pushed into SQL, not applied to the result, so an order older
     * than [limit] is still reachable by name; the screen re-calls this as the
     * operator types. When the read fills the window, [ClaimCandidates.windowFull]
     * says so, because "nothing matches" must never be a guess.
     *
     * The whole body runs on [Dispatchers.Default]. The caller is a
     * `LaunchedEffect`, i.e. Main, and the work here is a payload decode (128-point
     * histograms and base64 scattergrams) plus one [selectMapping] per candidate
     * — the cost `queueFlow` is at pains to keep off the UI thread. Leaving it
     * here would freeze the very frame that draws "Reading the orders…".
     */
    suspend fun claimCandidates(
        resultId: String,
        query: String = "",
        limit: Long = 80,
    ): Result<ClaimCandidates> = withContext(Dispatchers.Default) {
        runCatching {
            val row = q.unmatchedById(resultId).executeAsOneOrNull() ?: error("That result is gone")
            val stored = json.decodeFromString(StoredInstrumentFrame.serializer(), row.payload_json)
            val overrides = parseOverrides(
                row.instrument_id?.let { id -> q.instrumentById(id).executeAsOneOrNull()?.param_map_json }
            )
            val catalog = labRepo.listTests(includeInactive = true).associateBy { it.id }
            val q0 = query.trim()
            // Under three digits is not a phone number, it is a fragment of every
            // phone number — the same threshold filterForClaim uses on screen.
            val digits = q0.filter { it.isDigit() }.takeIf { it.length >= 3 }.orEmpty()
            val rows = oQ.claimCandidates(q0, digits, limit).executeAsList()
            val all = rows.map { r ->
                val tests = r.test_ids.orEmpty().split(',').mapNotNull { catalog[it.trim()] }
                ClaimCandidate(
                    orderId = r.id,
                    accessionNo = r.accession_no,
                    status = r.status,
                    registeredAt = r.created_at,
                    patientName = r.patient_name,
                    ageSex = ageSexLabel(r.patient_dob, r.patient_age_years, r.patient_sex),
                    phone = r.patient_phone?.takeIf { it.isNotBlank() },
                    tests = r.test_names.orEmpty(),
                    matched = selectMapping(stored.params.keys, tests, overrides)?.second?.size ?: 0,
                    total = stored.params.size,
                    canTakeResults = r.status in LabStatus.ENTRY_OPEN,
                )
            }
            ClaimCandidates(
                frame = stored,
                open = all.filter { it.canTakeResults }.rankedForClaim(),
                // Held back, but only the ones that could plausibly have been the
                // target: the operator's own search, or — with no search — an order
                // whose tests actually take this frame. A steady lab signs off
                // everything it registers, so counting the window's finished rows
                // wholesale would print "70 more orders" under every dialog and
                // teach the bench to stop reading the line.
                locked = all.filter { !it.canTakeResults && (q0.isNotEmpty() || it.matched > 0) },
                query = q0,
                windowFull = rows.size.toLong() >= limit,
            )
        }
    }

    /**
     * Claim-queue apply: operator typed/scanned an accession for a stored
     * frame. The row is marked applied ONLY when results actually landed.
     *
     * [by] is the signed-in staff member, and it is not decoration. The result
     * rows keep the ANALYZER as `entered_by` — the instrument produced the
     * numbers and that attribution is correct — so without a second line saying
     * a human chose this order, a wrong-patient claim surfacing a week later
     * looks exactly like a frame that matched on its own. The log line below is
     * the only place the difference is written down.
     *
     * On [Dispatchers.Default] as a WHOLE, not just around the row read: the
     * caller is a button handler on Main, and `payload_json` for a BC-5130 run
     * carries base64 scattergrams and 128-point histograms. Decoding that on the
     * UI thread freezes the very spinner the handler just switched on.
     */
    suspend fun claimUnmatched(resultId: String, accessionNo: String, by: Staff? = null): Result<String> =
        withContext(Dispatchers.Default) {
            runCatching {
                val row = q.unmatchedById(resultId).executeAsOneOrNull() ?: error("That result is gone")
                require(row.status == "unmatched") { "Already ${row.status} — nothing to apply" }
                val stored = json.decodeFromString(StoredInstrumentFrame.serializer(), row.payload_json)
                val typed = accessionNo.trim()
                val order = labRepo.orderByAccession(typed)
                    ?: labRepo.orderByAccession(typed.uppercase())
                    ?: labRepo.orderByAccession(typed.lowercase())
                    ?: error("No order with accession $typed")
                require(order.status in ENTRY_OPEN) { "Order ${order.accessionNo} is ${order.status} — results are locked" }
                val cfg = row.instrument_id?.let { id -> q.instrumentById(id).executeAsOneOrNull()?.toModel() }
                    ?: InstrumentConfig(id = "", name = FALLBACK_INSTRUMENT_NAME, driver = stored.driver,
                        transport = InstrumentTransport.TCP)
                val outcome = applyFrameToOrder(cfg, stored, order)
                if (!outcome.matched) error("No test on ${order.accessionNo} takes these parameters — check the ordered tests")
                q.markResultApplied(order.id, nowIso(), by?.name, by?.id, resultId)
                logRow(cfg, "info", "Claimed by ${actorName(by)} onto ${order.accessionNo} (manual) — ${outcome.summary}", null)
                outcome.summary
            }
        }

    // ── the third way out: register the order this run was meant for ──

    /**
     * What the "Create order" dialog opens with: the run, the ACTIVE catalog
     * ranked against it, and the lab's referrers.
     *
     * Only active tests, unlike [claimCandidates] (which reads the whole catalog
     * because it is DESCRIBING orders that already exist, retired tests and
     * all). This one is registering new work, and a test the lab has retired is
     * not something to start.
     *
     * On [Dispatchers.Default] for the reason `claimCandidates` is: the caller
     * is a `LaunchedEffect` on Main, and this decodes a payload (128-point
     * histograms, base64 scattergrams) and runs one [mapParams] per catalog test
     * — 223 of them in a stocked lab.
     */
    suspend fun createOrderContext(resultId: String): Result<CreateOrderContext> =
        withContext(Dispatchers.Default) {
            runCatching {
                val row = q.unmatchedById(resultId).executeAsOneOrNull() ?: error("That result is gone")
                val stored = json.decodeFromString(StoredInstrumentFrame.serializer(), row.payload_json)
                val inst = row.instrument_id?.let { q.instrumentById(it).executeAsOneOrNull() }
                CreateOrderContext(
                    frame = stored,
                    instrumentName = inst?.name ?: FALLBACK_INSTRUMENT_NAME,
                    fits = rankTestsForFrame(
                        stored.params.keys, labRepo.listTests(), parseOverrides(inst?.param_map_json),
                    ),
                    referrers = labRepo.listReferrers(),
                )
            }
        }

    /**
     * Existing patients a new registration would duplicate — same phone digits
     * AND the same name ([duplicatesOf]) — each with what they have been to this
     * lab before.
     *
     * Asked BEFORE anything is written. A patient list that fills with
     * near-duplicates is not an untidiness: it is how the wrong result reaches
     * the wrong person a month later, when two "Ravi Kumar" rows both look
     * plausible and neither is obviously the one holding the phone.
     */
    suspend fun duplicatePatients(name: String, phone: String?, recent: Int = 3): List<PatientMatch> =
        withContext(Dispatchers.Default) {
            if (name.isBlank() || phone.isNullOrBlank()) return@withContext emptyList()
            labRepo.patientsByPhone(phone).duplicatesOf(name).map { p ->
                val orders = labRepo.ordersForPatient(p.id)      // newest first
                PatientMatch(p, orders.take(recent), orders.size)
            }
        }

    /**
     * Register the order this run was meant for, then put the run on it.
     *
     * The whole point of the feature, and deliberately NOT a new way to make an
     * order: a new patient and their order are written together by
     * [LabRepository.createPatientAndOrder] (an existing patient's order by
     * [LabRepository.createLabOrder] alone) — which allocates the accession
     * from THIS computer's seat series, snapshots prices and pre-creates the
     * empty result rows — and the numbers land through [claimUnmatched], the
     * single result write path, so flags, frozen ranges, status walking and
     * `entered_by = <instrument name>` behave exactly as a bench entry.
     *
     * On [Dispatchers.Default] as a whole, like [createOrderContext] above: the
     * caller is a button handler on Main and this decodes a payload carrying
     * base64 scattergrams and 128-point histograms.
     *
     * 🔴 The accession is always the app's. `draft` carries no accession field
     * and the analyzer's specimen text becomes the order's NOTE
     * ([sampleReferenceNote]) — see the note there for why adopting it would
     * break the barcode's one promise.
     *
     * The gate is checked HERE and not only on the button: this is new work on a
     * licence that may have lapsed, and a screen is not a gate.
     *
     * Failure is split. Anything before the order exists throws, and nothing is
     * written. Once the order exists the result is a SUCCESS carrying
     * [CreatedFromResult.claimError]: the order is real either way, and an
     * operator told only "failed" registers a second one.
     */
    suspend fun createOrderForResult(
        resultId: String,
        draft: CreateOrderDraft,
        by: Staff? = null,
        licence: LicenseState,
    ): Result<CreatedFromResult> = withContext(Dispatchers.Default) {
        runCatching {
            CreateOrderGate.refusal(by, licence)?.let { error(it.detail) }

            val row = q.unmatchedById(resultId).executeAsOneOrNull() ?: error("That result is gone")
            require(row.status == "unmatched") { "Already ${row.status} — nothing to register" }
            val stored = json.decodeFromString(StoredInstrumentFrame.serializer(), row.payload_json)
            val cfg = cfgFor(row)

            // The same mapping the claim will use, one test wide: validate against
            // THAT rather than against the ranked list the dialog happened to show,
            // which may have been read before someone edited the catalog.
            val test = draft.testId?.let { labRepo.testById(it) }
                ?: error("Pick the test this run belongs to")
            val fit = TestFit(test, mapParams(stored.params.keys, test, parseOverrides(cfg.paramMapJson)))
            validateCreateOrder(draft, listOf(fit), stored.params.size)?.let { error(it) }

            val reused = draft.reusingPatient
            val notes = sampleReferenceNote(stored.specimenId ?: row.specimen_id, cfg.name)
            val referrerId = draft.referrerId?.takeIf { it.isNotBlank() }
            // 🔴 ONE unit of work for a new patient. Written as two, a failing
            // order (SQLITE_BUSY under a backup snapshot is the realistic one)
            // left the patient row committed behind a registration that never
            // happened — and the retry, with no phone to match on, wrote a
            // second copy of the same person past the duplicate guard.
            val (patient, order) = if (reused) {
                val onFile = labRepo.patientById(draft.reusePatientId!!)
                    ?: error("That patient is no longer on file")
                onFile to labRepo.createLabOrder(
                    patientId = onFile.id,
                    testIds = listOf(test.id),
                    referrerId = referrerId,
                    priority = draft.priority,
                    notes = notes,
                ).getOrElse { error(it.message ?: "Could not register the order") }
            } else {
                labRepo.createPatientAndOrder(
                    patient = Patient(
                        id = Uuid.random().toString(),
                        name = draft.name,
                        sex = draft.sex ?: "O",
                        dob = draft.dobClean,
                        // Exactly as the desk's form stores it: a DOB is the better age
                        // source, so the typed years are kept only when there is none.
                        ageYears = if (draft.dobClean == null) draft.ageYears else null,
                        phone = draft.phoneClean,
                    ),
                    testIds = listOf(test.id),
                    referrerId = referrerId,
                    priority = draft.priority,
                    notes = notes,
                ).getOrElse { error(it.message ?: "Could not register the order") }
            }

            // The one line that says where this order came from. Written before the
            // claim so the trail reads in the order it happened, and counts only —
            // this log travels to BNM support, so the patient's name stays out of it
            // exactly as it does in `describe`.
            logRow(
                cfg, "info",
                "Order ${order.accessionNo} created from a queued analyzer result by ${actorName(by)} — " +
                    "${if (reused) "existing patient" else "new patient"}, ${test.name}, ${describe(row)}",
                null,
            )

            val claim = claimUnmatched(resultId, order.accessionNo, by)
            CreatedFromResult(
                order = order,
                patient = patient,
                reusedPatient = reused,
                testName = test.name,
                claimSummary = claim.getOrNull(),
                claimError = claim.exceptionOrNull()?.message,
            )
        }
    }

    /**
     * Destroy a queued run. Logged for the same reason a claim is: this throws
     * away clinical numbers that the analyzer will not send again.
     */
    suspend fun discardUnmatched(resultId: String, by: Staff? = null) {
        val row = withContext(Dispatchers.Default) { q.unmatchedById(resultId).executeAsOneOrNull() }
        withContext(Dispatchers.Default) { q.markResultDiscarded(nowIso(), by?.name, by?.id, resultId) }
        row?.let { logRow(cfgFor(it), "info", "Discarded by ${actorName(by)} — ${describe(it)}", null) }
    }

    /**
     * The worksheet left the building. A page of a patient's numbers walking out
     * on paper is an event; the trail should not have to infer it from silence.
     *
     * [outcome] is `printA4`'s own verdict, and the row is written only when it
     * says the job went out — a cancelled dialog is not a print, and a log that
     * claims one is worse than no log. The verdict rides along in the line so
     * the trail says which printer path it took.
     */
    suspend fun logWorksheetPrinted(resultId: String, outcome: String, by: Staff? = null) {
        if (!printOutcomeSucceeded(outcome)) return
        val row = withContext(Dispatchers.Default) { q.unmatchedById(resultId).executeAsOneOrNull() } ?: return
        logRow(cfgFor(row), "info", "Worksheet printed by ${actorName(by)} — ${describe(row)} — $outcome", null)
    }

    /**
     * Did `printA4` actually hand the page to a printer?
     *
     * The platforms word it differently ("Sent to printer" on desktop, "Opening
     * print preview…" on Android, which is as far as that API's answer goes) and
     * every failure they report is prefixed. So: refuse the known failures rather
     * than whitelist the successes — a new platform's success string should not
     * silently drop out of the audit trail, while "Print cancelled" must never
     * enter it.
     */
    internal fun printOutcomeSucceeded(outcome: String): Boolean {
        val o = outcome.trim()
        return o.isNotEmpty() &&
            !o.startsWith("Print cancelled", true) &&
            !o.startsWith("Print failed", true) &&
            !o.startsWith("Printer not ready", true) &&
            !o.startsWith("A4 printing arrives", true)   // the iOS stub
    }

    /** "Ravi (technician)" / "an unidentified operator" — never a blank in the trail. */
    private fun actorName(by: Staff?): String =
        by?.name?.takeIf { it.isNotBlank() }?.let { "$it (${by.role})" } ?: "an unidentified operator"

    /** A queued row in one line, counts only — the values stay out of the log. */
    private fun describe(row: Instrument_results): String {
        val params = runCatching {
            json.decodeFromString(StoredInstrumentFrame.serializer(), row.payload_json).params.size
        }.getOrDefault(0)
        return "specimen ${row.specimen_id ?: "—"}, $params parameters, received ${row.received_at}"
    }

    /** The row's analyzer, or the engine's stand-in when that config is gone. */
    private suspend fun cfgFor(row: Instrument_results): InstrumentConfig =
        row.instrument_id?.let { id ->
            withContext(Dispatchers.Default) { q.instrumentById(id).executeAsOneOrNull()?.toModel() }
        } ?: InstrumentConfig(id = "", name = FALLBACK_INSTRUMENT_NAME, driver = "",
            transport = InstrumentTransport.TCP)

    // ── config CRUD + observation (Instruments screen) ──

    fun instrumentsFlow(): Flow<List<InstrumentConfig>> =
        q.listInstruments().asFlow().mapToList(Dispatchers.Default)
            .map { rows -> rows.map { it.toModel() } }

    fun logFlow(limit: Long = 100): Flow<List<Instrument_log>> =
        q.recentLog(limit).asFlow().mapToList(Dispatchers.Default)

    fun unmatchedFlow(): Flow<List<Instrument_results>> =
        q.listUnmatched().asFlow().mapToList(Dispatchers.Default)

    /** Upsert one analyzer and restart ONLY its listener. Returns the row id (minted for a new row). */
    suspend fun saveInstrument(cfg: InstrumentConfig): String {
        val id = cfg.id.ifBlank { Uuid.random().toString() }
        withContext(Dispatchers.Default) {
            val now = nowIso()
            q.upsertInstrument(
                id, cfg.name.trim().ifBlank { "Analyzer" },
                cfg.driver, cfg.transport, cfg.serialPort?.trim()?.ifBlank { null },
                cfg.baud.toLong(), cfg.tcpPort?.toLong(),
                if (cfg.enabled) 1L else 0L, cfg.paramMapJson,
                cfg.createdAt.ifBlank { now }, now,
                if (cfg.verifyPending) 1L else 0L,
                cfg.analyzerHost?.trim()?.ifBlank { null },
                InstrumentTcpRole.normalise(cfg.tcpRole),
            )
        }
        restart(id)
        return id
    }

    suspend fun deleteInstrument(id: String) {
        withContext(Dispatchers.Default) { q.deleteInstrument(id) }
        restart(id)
    }

    suspend fun instrumentById(id: String): InstrumentConfig? = withContext(Dispatchers.Default) {
        q.instrumentById(id).executeAsOneOrNull()?.toModel()
    }

    suspend fun listInstruments(): List<InstrumentConfig> = withContext(Dispatchers.Default) {
        q.listInstruments().executeAsList().map { it.toModel() }
    }

    /**
     * The "check one known sample first" gate. Set by remote support when it
     * changed the driver or the param map; cleared by "Verified" on the
     * Instruments screen. No listener restart — [routeFrame] reads it per frame.
     */
    suspend fun setVerifyPending(id: String, pending: Boolean) = withContext(Dispatchers.Default) {
        q.setVerifyPending(if (pending) 1L else 0L, nowIso(), id)
    }

    /**
     * Parse [frameText] with [cfg]'s driver and work out what the live path
     * WOULD do — the driver's parse, the order lookup (never a claim, never a
     * write) and the same [selectMapping] the apply path uses. Specimen ids
     * beginning `BNMTEST-` are always reported as non-matching so an engineer
     * can paste a fabricated frame without ever hitting a real order.
     */
    suspend fun dryRun(cfg: InstrumentConfig, frameText: String): DryRun {
        val parsed = parseFrameText(cfg.driver, frameText)
            ?: return DryRun(driver = cfg.driver, parsed = false,
                note = "The ${driverFor(cfg.driver)?.label ?: cfg.driver} driver found no result in that text")
        val (stored, note) = parsed
        val specimen = stored.specimenId
        val isTest = specimen?.uppercase()?.startsWith("BNMTEST-") == true
        val order = if (specimen == null || isTest) null else findOrder(specimen)
        val overrides = parseOverrides(cfg.paramMapJson)
        val ordered = order?.let { o -> labRepo.orderTests(o.id).mapNotNull { labRepo.testById(it.testId) } }
        val basisTests = ordered?.takeIf { it.isNotEmpty() } ?: labRepo.listTests()
        val basis = if (ordered != null && ordered.isNotEmpty()) "ordered_tests" else "catalog"
        val pick = selectMapping(stored.params.keys, basisTests, overrides)
        val mapping = pick?.second.orEmpty()
        return DryRun(
            driver = cfg.driver,
            parsed = true,
            note = listOfNotNull(note, if (isTest) "BNMTEST- specimen — never matched to an order" else null)
                .joinToString("; ").ifBlank { null },
            specimenIdMasked = specimen?.let { FrameScrubber.maskId(it) },
            paramKeys = stored.params.keys.toList(),
            units = stored.units,
            histograms = (stored.histograms.keys + stored.images.keys).toList(),
            wouldMatch = order != null && order.status in ENTRY_OPEN,
            accessionMasked = order?.let { FrameScrubber.maskId(it.accessionNo) },
            orderStatus = order?.status,
            mappingBasis = basis,
            testName = pick?.first?.name,
            mapped = mapping,
            unmapped = stored.params.keys.filter { it !in mapping },
            unitMismatches = pick?.let { (test, m) -> unitMismatches(stored, test, m) }.orEmpty(),
        )
    }

    suspend fun clearLog() = withContext(Dispatchers.Default) { q.clearLog() }

    // ── internals ──

    private suspend fun logRow(cfg: InstrumentConfig, direction: String, summary: String, raw: String?) {
        // Onto the activity log as well — the SUMMARY only, which every call site
        // writes as specimen id + counts. `raw` is the analyzer frame itself
        // (patient names, values) and stays in the local instrument_log table;
        // it must never reach a file that is emailed.
        if (direction == "error") AppLog.w("Analyzer", "${cfg.name}: $summary")
        else AppLog.i("Analyzer", "${cfg.name} [$direction]: $summary")
        runCatching {
            withContext(Dispatchers.Default) {
                q.insertLog(Uuid.random().toString(), cfg.id.ifBlank { null }, cfg.name,
                    direction, summary.take(500), raw?.take(4000), nowIso())
                q.trimLog(500)
            }
        }
    }

    private fun parseOverrides(paramMapJson: String?): Map<String, String> =
        paramMapJson?.let { raw ->
            runCatching { json.decodeFromString(STRING_MAP, raw) }.getOrNull()
        } ?: emptyMap()

    private fun Instruments.toModel() = InstrumentConfig(
        id = id, name = name, driver = driver_key, transport = transport,
        serialPort = serial_port, baud = baud.toInt(), tcpPort = tcp_port?.toInt(),
        enabled = enabled == 1L, paramMapJson = param_map_json,
        createdAt = created_at, updatedAt = updated_at,
        verifyPending = verify_pending == 1L,
        analyzerHost = analyzer_host,
        tcpRole = InstrumentTcpRole.normalise(tcp_role),
    )

    companion object {
        /** One HL7 frame may grow this far before it is abandoned (a BC-5130
         *  result with every graph on is a few hundred KB). */
        private const val MAX_HL7_BYTES = 8 * 1024 * 1024
        private val SB_STR = Mllp.SB.toString()
        private val EB_STR = Mllp.EB.toString()

        /** The name results carry when the analyzer that sent them has since been
         *  removed from the app. Never a blank: `entered_by` prints on a report. */
        internal const val FALLBACK_INSTRUMENT_NAME = "Analyzer"

        /** Why a frame sits in the claim queue while `verify_pending` is set — shown on the Instruments screen. */
        const val VERIFY_PENDING_REASON =
            "Analyzer settings changed by support — check one known sample, then press Verified"

        /** @see LabStatus.ENTRY_OPEN — one set, shared with enterResult and the picker. */
        private val ENTRY_OPEN = LabStatus.ENTRY_OPEN

        private val STRING_MAP = MapSerializer(String.serializer(), String.serializer())
        private val DOUBLE_LIST = ListSerializer(Double.serializer())

        /**
         * The test whose catalog parameters best overlap the analyzer's params,
         * with the mapping — pure, shared by the live apply path and [dryRun]
         * so the two can never disagree about what a frame maps to.
         */
        internal fun selectMapping(
            analyzerKeys: Collection<String>,
            tests: List<LabTest>,
            overrides: Map<String, String>,
        ): Pair<LabTest, Map<String, String>>? {
            var best: Pair<LabTest, Map<String, String>>? = null   // test → analyzerKey→paramKey
            for (test in tests) {
                val mapping = mapParams(analyzerKeys, test, overrides)
                if (mapping.isNotEmpty() && mapping.size > (best?.second?.size ?: 0)) {
                    best = test to mapping
                }
            }
            return best
        }

        /** "HGB bogus → g/dL" for every mapped param whose analyzer unit the converter cannot bridge. Pure. */
        internal fun unitMismatches(stored: StoredInstrumentFrame, test: LabTest, mapping: Map<String, String>): List<String> =
            mapping.mapNotNull { (analyzerKey, paramKey) ->
                val raw = stored.params[analyzerKey] ?: return@mapNotNull null
                val target = test.parameters.firstOrNull { it.key == paramKey }?.unit
                val from = stored.units[analyzerKey]
                if (AnalyzerUnits.convert(raw, from, target) == raw && AnalyzerUnits.needsConversion(from, target))
                    "$analyzerKey $from → $target" else null
            }

        /**
         * A frame as pasted by a person — with or without its transport framing
         * — through the driver, to storage form. Second value: a caveat worth
         * repeating (a QC run). Null when the driver finds no result.
         */
        internal fun parseFrameText(driver: String, text: String): Pair<StoredInstrumentFrame, String?>? = when (driver) {
            "mispa_count_x" -> {
                val t = text.trim()
                val (extracted, _) = MispaCountX.extractFrameText(t)
                val frameText = extracted
                    ?: (if (t.startsWith("$$$")) t else "$$$$t").let { if (it.endsWith("###")) it else "$it###" }
                MispaCountX.parse(frameText)?.let { f ->
                    StoredInstrumentFrame(
                        driver = driver, specimenId = f.specimenId, patientId = f.patientId, date = f.date,
                        sequenceId = f.sequenceId, params = f.params, histograms = f.histograms,
                    ) to null
                }
            }
            "mindray_hl7" -> {
                val body = if (text.indexOf(Mllp.SB) >= 0) Mllp.extract(text).first ?: text.trim(Mllp.SB, Mllp.EB, Mllp.CR)
                    else text.trim()
                MindrayBc5x.parse(body)?.let { f ->
                    StoredInstrumentFrame(
                        driver = driver, specimenId = f.specimenId, patientId = f.patientId,
                        patientName = f.patientName, patientSex = f.patientSex, date = f.date,
                        sequenceId = f.sequenceId, params = f.params, histograms = f.histograms, meta = f.meta,
                        units = f.units, images = f.images,
                    ) to (if (f.isQc) "QC run — the live path acknowledges and ignores these" else null)
                }
            }
            else -> null
        }

        /** "LYMP%" → "lymppct", "MID#" → "midabs", "RDW-SD" → "rdwsd". */
        internal fun norm(s: String): String =
            s.replace("%", "pct").replace("#", "abs")
                .lowercase().filter { it.isLetterOrDigit() }

        /**
         * Conservative built-in synonyms per Mispa param. Anything not
         * matched stays unmapped (visible in the log) — the config's explicit
         * param map is the intended fix, never a fuzzy guess. Notably GRAN%
         * is NOT mapped to "neutrophils": a 3-part granulocyte count isn't a
         * neutrophil count; that substitution is the lab's call to configure.
         */
        internal val MISPA_ALIASES: Map<String, List<String>> = mapOf(
            "WBC" to listOf("wbc", "twbc", "tlc", "totalwbccount", "totalleucocytecount", "totalleukocytecount", "wbccount"),
            "RBC" to listOf("rbc", "rbccount", "totalrbc", "totalrbccount"),
            "PLT" to listOf("plt", "platelet", "platelets", "plateletcount", "pltcount"),
            "HGB" to listOf("hgb", "hb", "haemoglobin", "hemoglobin"),
            "HCT" to listOf("hct", "pcv", "haematocrit", "hematocrit", "packedcellvolume"),
            "MCV" to listOf("mcv"),
            "MCH" to listOf("mch"),
            "MCHC" to listOf("mchc"),
            "RDW-SD" to listOf("rdwsd"),
            "RDW-CV" to listOf("rdwcv", "rdw"),
            "MPV" to listOf("mpv"),
            "LYMP%" to listOf("lymppct", "lympct", "lymphpct", "lymphocytespct", "lymphocytepct", "lym", "lymp", "lymph", "lymphocytes", "lymphocyte"),
            "MID%" to listOf("midpct", "mid", "mixedpct", "mxdpct", "mixed", "mxd"),
            "GRAN%" to listOf("granpct", "gran", "granulocytespct", "granulocytepct", "granulocytes", "grapct"),
            "LYMP#" to listOf("lympabs", "lymabs", "lymphabs", "abslymphocytes", "lymphocytesabs",
                "absolutelymphocytes", "lymphocytesabsolute", "abslymph", "abslymp"),
            "MID#" to listOf("midabs", "mxdabs", "mixedabs"),
            "GRAN#" to listOf("granabs", "granulocytesabs", "absgranulocytes", "graabs"),
            "PCT" to listOf("plateletcrit"),
            "PDW" to listOf("pdw"),
            "LPCR" to listOf("lpcr", "plcr"),
        )

        /** Mindray BC-5x names (5-part differential) — same conservatism. */
        internal val MINDRAY_ALIASES: Map<String, List<String>> = mapOf(
            "LYM%" to MISPA_ALIASES.getValue("LYMP%"),
            "LYM#" to MISPA_ALIASES.getValue("LYMP#"),
            "NEU%" to listOf("neupct", "neutpct", "neutrophilpct", "neutrophilspct", "neu", "neut", "neutrophil", "neutrophils"),
            "NEU#" to listOf("neuabs", "neutabs", "neutrophilsabs", "absneutrophils", "absoluteneutrophils"),
            "EOS%" to listOf("eospct", "eosinophilpct", "eosinophilspct", "eos", "eosinophil", "eosinophils"),
            "EOS#" to listOf("eosabs", "eosinophilsabs", "abseosinophils", "absoluteeosinophils"),
            "BAS%" to listOf("baspct", "basopct", "basophilpct", "basophilspct", "bas", "baso", "basophil", "basophils"),
            "BAS#" to listOf("basabs", "basoabs", "basophilsabs", "absbasophils", "absolutebasophils"),
            "MON%" to listOf("monpct", "monopct", "monocytepct", "monocytespct", "mon", "mono", "monocyte", "monocytes"),
            "MON#" to listOf("monabs", "monoabs", "monocytesabs", "absmonocytes", "absolutemonocytes"),
            "PLCC" to listOf("plcc", "plateletlargercellcount"),
            "PLCR" to listOf("plcr", "lpcr", "plateletlargercellratio"),
        )

        private val ANALYZER_ALIASES: Map<String, List<String>> = MISPA_ALIASES + MINDRAY_ALIASES

        /**
         * analyzerKey → catalog parameter_key for one test. Explicit overrides
         * win. Then TWO passes: exact normalised key/name equality first, and
         * only afterwards whole-word name-token matches — a token match must be
         * UNIQUE among the test's still-unclaimed parameters or it is skipped.
         * A percentage key never binds a parameter whose name says absolute
         * (and vice versa): "Lymphocytes" takes LYMP%, "Absolute Lymphocyte
         * Count" doesn't. One catalog parameter never takes two analyzer
         * values.
         */
        internal fun mapParams(
            analyzerKeys: Collection<String>,
            test: LabTest,
            overrides: Map<String, String>,
        ): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            val catalogKeys = test.parameters.map { it.key }.toSet()

            fun taken(paramKey: String) = out.values.any { it == paramKey }
            // A percentage never binds an absolute-count parameter and vice
            // versa. The NAME says so sometimes; the UNIT always does — and
            // Mindray sends "LYM#" before "LYM%", so without the unit check the
            // absolute count would grab a bare "Lymphocytes" (%) first.
            fun crossKindClash(analyzerKey: String, pKeyN: String, pNameN: String, unit: String?): Boolean {
                val n = pKeyN + pNameN
                val u = unit?.let { AnalyzerUnits.canonical(it) }.orEmpty()
                val unitIsPercent = u == "%"
                val unitIsCount = u.isNotEmpty() && !unitIsPercent &&
                    ("cumm" in u || u.endsWith("/l") || u.endsWith("/ul") || u.startsWith("10^"))
                return when {
                    analyzerKey.endsWith("%") -> "abs" in n || "absolute" in n || unitIsCount
                    analyzerKey.endsWith("#") -> "pct" in n || "percent" in n || unitIsPercent
                    else -> false
                }
            }

            val pending = mutableListOf<String>()
            for (ak in analyzerKeys) {
                val override = overrides[ak]
                if (override != null) {
                    if (override in catalogKeys && !taken(override)) out[ak] = override
                    continue
                }
                pending += ak
            }

            // Pass 1: exact normalised key/name equality.
            val stillPending = mutableListOf<String>()
            for (ak in pending) {
                val aliases = (ANALYZER_ALIASES[ak] ?: listOf(norm(ak))).toSet()
                val hit = test.parameters.firstOrNull { p ->
                    val keyN = norm(p.key); val nameN = norm(p.name)
                    (keyN in aliases || nameN in aliases) &&
                        !crossKindClash(ak, keyN, nameN, p.unit) && !taken(p.key)
                }
                if (hit != null) out[ak] = hit.key else stillPending += ak
            }

            // Pass 2: whole-word name tokens — only when exactly ONE parameter
            // qualifies, so "Lymphocytes" vs "Lymphocytes (manual)" maps neither.
            for (ak in stillPending) {
                val aliases = (ANALYZER_ALIASES[ak] ?: listOf(norm(ak))).toSet()
                val candidates = test.parameters.filter { p ->
                    val keyN = norm(p.key); val nameN = norm(p.name)
                    val nameTokens = p.name.split(' ', '(', ')', '/', ',', '-')
                        .map { norm(it) }.filter { it.isNotEmpty() }.toSet()
                    nameTokens.any { it in aliases } &&
                        !crossKindClash(ak, keyN, nameN, p.unit) && !taken(p.key)
                }
                if (candidates.size == 1) out[ak] = candidates[0].key
            }
            return out
        }
    }
}

private fun nowIso(): String = kotlin.time.Clock.System.now().toString()
