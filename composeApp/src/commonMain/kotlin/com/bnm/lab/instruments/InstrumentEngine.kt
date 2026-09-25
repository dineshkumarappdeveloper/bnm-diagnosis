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
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
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
 */
@OptIn(ExperimentalUuidApi::class)
class InstrumentEngine(
    private val db: AppDatabase,
    private val labRepo: LabRepository,
    private val json: Json,
    /** How often an enabled instrument in `error` is retried. Tests shorten it. */
    private val healIntervalMs: Long = 10_000L,
) {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            // Last line of defence for a failure no call site caught: log it, keep
            // the other listeners running, and never let it reach the JVM's
            // uncaught handler (which would treat it as a crash).
            CoroutineExceptionHandler { _, e -> AppLog.e("Analyzer", "background task failed", e) },
    )
    private val q get() = db.instrumentsQueries
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
            val next = mutableMapOf<String, InstrumentStatus>()
            for (cfg in configs) next[cfg.id] = relaunch(cfg, _status.value[cfg.id])
            _status.value = next
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
            if (cfg == null) {
                _status.value = _status.value - id
            } else {
                _status.value = _status.value + (id to relaunch(cfg, _status.value[id]))
            }
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
                    val fresh = launchListener(cfg)
                    // A TCP launch reports "listening" before the bind has happened.
                    // Keep showing the error until the accept loop proves otherwise
                    // — otherwise every retry would flip error → listening → error
                    // and log two transitions a pass.
                    val merged = if (cfg.transport == InstrumentTransport.TCP && fresh.state == "listening") current
                        else carry(current, fresh)
                    if (merged.state != current.state || merged.detail != current.detail) {
                        if (merged.state == "error") AppLog.w("Analyzer", "${cfg.id} -> ${merged.state}: ${merged.detail.orEmpty()}")
                        else AppLog.i("Analyzer", "${cfg.id} -> ${merged.state}: ${merged.detail.orEmpty()}")
                    }
                    _status.value = _status.value + (id to merged)
                }
            }
        }
    }

    /** Launch (or park) one instrument and carry its counters over. Must run under [restartMutex]. */
    private fun relaunch(cfg: InstrumentConfig, prev: InstrumentStatus?): InstrumentStatus {
        val fresh = if (!cfg.enabled) InstrumentStatus("off", "Disabled") else launchListener(cfg)
        val merged = carry(prev?.copy(boundAt = null), fresh)
        if (prev?.state != merged.state || prev.detail != merged.detail) {
            if (merged.state == "error") AppLog.w("Analyzer", "${cfg.id} -> ${merged.state}: ${merged.detail.orEmpty()}")
            else AppLog.i("Analyzer", "${cfg.id} -> ${merged.state}: ${merged.detail.orEmpty()}")
        }
        return merged
    }

    /** Stop every listener without touching config. Used by the tenant-switch
     *  wipe: no analyzer byte may land between "old lab erased" and "new lab
     *  activated". [restartAll] brings listeners back. */
    suspend fun stopAll() = restartMutex.withLock {
        listeners.values.toList().forEach { it.close() }
        listeners.clear()
        _status.value = _status.value.mapValues { (_, st) -> carry(st, InstrumentStatus("off", "Stopped")) }
    }

    private fun launchListener(cfg: InstrumentConfig): InstrumentStatus = when (cfg.transport) {
        InstrumentTransport.TCP -> {
            val port = cfg.tcpPort
            if (port == null || port !in 1..65535) {
                InstrumentStatus("error", "No TCP port set")
            } else {
                val job = scope.launch { tcpListenLoop(cfg, port) }
                listeners[cfg.id] = ListenerHandle(job, serial = null, assembler = null)
                InstrumentStatus("listening", "TCP port $port")
            }
        }
        InstrumentTransport.SERIAL -> {
            val portName = cfg.serialPort
            when {
                !serialSupported() ->
                    InstrumentStatus("error", "Serial isn't available on this device — use the lab PC")
                portName.isNullOrBlank() ->
                    InstrumentStatus("error", "No serial port chosen")
                else -> {
                    val assembler = FrameAssembler(cfg)
                    // submit() (not launch-per-chunk): jSerialComm delivers
                    // chunks sequentially on its listener thread, and the
                    // assembler's single consumer preserves that order —
                    // separate coroutine launches would not.
                    val handle = openSerialPort(
                        portName, cfg.baud,
                        onData = { bytes -> assembler.submit(bytes) },
                        onClosed = { reason ->
                            setStatus(cfg.id, InstrumentStatus("error", reason ?: "Serial port closed"))
                            scope.launch { logRow(cfg, "error", reason ?: "Serial port closed", null) }
                        },
                    )
                    if (handle == null) {
                        assembler.abandon()
                        InstrumentStatus("error", "Couldn't open $portName — in use, or unplugged?")
                    } else {
                        listeners[cfg.id] = ListenerHandle(job = null, serial = handle, assembler = assembler)
                        InstrumentStatus("listening", "$portName @ ${cfg.baud}", boundAt = nowIso())
                    }
                }
            }
        }
        else -> InstrumentStatus("error", "Unknown transport '${cfg.transport}'")
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

    /** A state transition. Counters, last-frame and last-error stamps carry over from the previous status. */
    private fun setStatus(id: String, s: InstrumentStatus) {
        val before = _status.value[id]
        val merged = carry(before, s)
        _status.value = _status.value + (id to merged)
        // Transitions only — lastFrameAt moves on every frame and would drown the log.
        if (before?.state != merged.state || before.detail != merged.detail) {
            if (merged.state == "error") AppLog.w("Analyzer", "$id -> ${merged.state}: ${merged.detail.orEmpty()}")
            else AppLog.i("Analyzer", "$id -> ${merged.state}: ${merged.detail.orEmpty()}")
        }
    }

    /** A counter bump or stamp on an instrument whose state does not change. No-op for an unknown id. */
    private fun update(id: String, f: (InstrumentStatus) -> InstrumentStatus) {
        val cur = _status.value[id] ?: return
        _status.value = _status.value + (id to f(cur))
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
        update(cfg.id) { it.copy(framesParsed = it.framesParsed + 1) }
        setStatus(cfg.id, InstrumentStatus("listening",
            _status.value[cfg.id]?.detail, lastFrameAt = nowIso()))
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
        update(cfg.id) { it.copy(framesParsed = it.framesParsed + 1) }
        setStatus(cfg.id, InstrumentStatus("listening",
            _status.value[cfg.id]?.detail, lastFrameAt = nowIso()))
        val stored = StoredInstrumentFrame(
            driver = cfg.driver,
            specimenId = frame.specimenId,
            patientId = frame.patientId,
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

    /** A complete frame the driver returned null for. Masked excerpt: sample ids stay shapes. */
    private suspend fun frameNotUnderstood(cfg: InstrumentConfig, text: String) {
        update(cfg.id) { it.copy(framesIgnored = it.framesIgnored + 1) }
        val excerpt = FrameScrubber.maskIds(text.take(120)).replace('\r', ' ').replace('\n', ' ')
        logRow(cfg, "error", "Frame not understood by ${cfg.driver}: $excerpt", null)
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

    /** Claim-queue apply: operator typed/scanned an accession for a stored
     *  frame. The row is marked applied ONLY when results actually landed. */
    suspend fun claimUnmatched(resultId: String, accessionNo: String): Result<String> = runCatching {
        val row = withContext(Dispatchers.Default) { q.unmatchedById(resultId).executeAsOneOrNull() }
            ?: error("That result is gone")
        require(row.status == "unmatched") { "Already ${row.status} — nothing to apply" }
        val stored = json.decodeFromString(StoredInstrumentFrame.serializer(), row.payload_json)
        val typed = accessionNo.trim()
        val order = labRepo.orderByAccession(typed)
            ?: labRepo.orderByAccession(typed.uppercase())
            ?: labRepo.orderByAccession(typed.lowercase())
            ?: error("No order with accession $typed")
        require(order.status in ENTRY_OPEN) { "Order ${order.accessionNo} is ${order.status} — results are locked" }
        val cfg = row.instrument_id?.let { id ->
            withContext(Dispatchers.Default) { q.instrumentById(id).executeAsOneOrNull()?.toModel() }
        } ?: InstrumentConfig(id = "", name = "Analyzer", driver = stored.driver, transport = InstrumentTransport.TCP)
        val outcome = applyFrameToOrder(cfg, stored, order)
        if (!outcome.matched) error("No test on ${order.accessionNo} takes these parameters — check the ordered tests")
        withContext(Dispatchers.Default) { q.markResultApplied(order.id, nowIso(), resultId) }
        outcome.summary
    }

    suspend fun discardUnmatched(resultId: String) = withContext(Dispatchers.Default) {
        q.markResultDiscarded(nowIso(), resultId)
    }

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
    )

    companion object {
        /** One HL7 frame may grow this far before it is abandoned (a BC-5130
         *  result with every graph on is a few hundred KB). */
        private const val MAX_HL7_BYTES = 8 * 1024 * 1024
        private val SB_STR = Mllp.SB.toString()
        private val EB_STR = Mllp.EB.toString()

        /** Why a frame sits in the claim queue while `verify_pending` is set — shown on the Instruments screen. */
        const val VERIFY_PENDING_REASON =
            "Analyzer settings changed by support — check one known sample, then press Verified"

        private val ENTRY_OPEN = setOf(
            LabStatus.REGISTERED, LabStatus.COLLECTED, LabStatus.IN_PROGRESS, LabStatus.ENTERED,
        )

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
                        driver = driver, specimenId = f.specimenId, patientId = f.patientId, date = f.date,
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
