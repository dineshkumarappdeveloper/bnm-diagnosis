package com.bnm.analyzersim

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger

/** Where the transcript goes. An interface so the tests can read it back. */
interface Printer {
    fun info(line: String)
    /** Only shown with --verbose. */
    fun detail(line: String)
    fun warn(line: String)
}

class ConsolePrinter(private val verbose: Boolean) : Printer {
    override fun info(line: String) = println(line)
    override fun detail(line: String) { if (verbose) println(line) }
    override fun warn(line: String) = System.err.println(line)
}

/** Keeps every line, for tests and for the menu's "what just happened" panel. */
class RecordingPrinter(private val verbose: Boolean = true) : Printer {
    val lines = mutableListOf<String>()
    override fun info(line: String) { lines += line }
    override fun detail(line: String) { if (verbose) lines += line }
    override fun warn(line: String) { lines += "! $line" }
    val text: String get() = lines.joinToString("\n")
}

/**
 * Runs one session: build the frames, put them on the wire, say exactly what
 * happened.
 *
 * "Say exactly what happened" is not a nicety. The engineer on the phone to
 * the lab needs to be able to tell "the bytes left this machine and nothing
 * came back" from "the connection was refused" from "the app ACKed but with
 * an error code", and those three look identical from the app's side of the
 * cable.
 *
 * What happened is reported as [SendListener] events, never by printing: the
 * CLI hands in a [PrintingSendListener] and the window hands in its own. Both
 * front ends therefore see the same run, and neither has to read the other's
 * sentences.
 */
class Sender(
    private val o: Options,
    private val listener: SendListener,
    /** yyyyMMddHHmmss; injected so a test gets a frame it can predict. */
    private val clock: () -> String = { LocalDateTime.now().format(STAMP) },
    /** Swaps the real socket out. Only the tests pass this — the CLI never does,
     *  so what the tests exercise is the same code the engineer runs. */
    private val transportOverride: TransportFactory? = null,
) {

    /** The CLI's way in: a printer is a listener that prints. */
    constructor(
        o: Options,
        out: Printer,
        clock: () -> String = { LocalDateTime.now().format(STAMP) },
        transportOverride: TransportFactory? = null,
    ) : this(o, PrintingSendListener(out), clock, transportOverride)

    /** What one shape of run ended up doing. */
    private data class Outcome(val kind: RunKind, val total: Int, val failed: Int, val exitCode: Int)

    /** Process exit code: 0 when every sample went out as intended. */
    fun run(): Int {
        listener.runStarted(runStart())
        val factory = factory()
        val outcome = try {
            when {
                o.faults.hang -> hang(factory)
                o.faults.burst > 0 -> burst(factory)
                else -> sequential(factory)
            }
        } catch (e: Exception) {
            listener.failed(Failure(null, explain(e)))
            Outcome(RunKind.ABORTED, o.samples, o.samples, 1)
        } finally {
            (factory as? AutoCloseable)?.let { runCatching { it.close() } }
        }
        // Always, including after a failure — a window that never hears this
        // would spin forever on a refused connection.
        listener.finished(RunFinished(outcome.kind, outcome.total, outcome.failed, outcome.exitCode))
        return outcome.exitCode
    }

    // ── the three shapes a run can take ──

    private fun sequential(factory: TransportFactory): Outcome {
        var failures = 0
        val shared = if (factory.perSample) null else factory.open()
        try {
            for (index in 0 until o.samples) {
                if (index > 0 && o.intervalSeconds > 0) Thread.sleep((o.intervalSeconds * 1000).toLong())
                val transport = shared ?: factory.open()
                try {
                    if (!sendOne(index, transport)) failures++
                } finally {
                    if (shared == null) transport.close()
                }
            }
        } finally {
            shared?.close()
        }
        return Outcome(RunKind.SEQUENTIAL, o.samples, failures, if (failures == 0) 0 else 1)
    }

    /**
     * Several analyzers (or one analyzer retrying) hitting the listener at the
     * same instant. The app accepts each connection on its own coroutine, and
     * this is the only way to prove that from outside.
     */
    private fun burst(factory: TransportFactory): Outcome {
        val n = o.faults.burst
        listener.note("Burst: opening $n connections at once.")
        val failures = AtomicInteger(0)
        val threads = (0 until n).map { index ->
            Thread {
                runCatching {
                    factory.open().use { t -> if (!sendOne(index, t)) failures.incrementAndGet() }
                }.onFailure {
                    failures.incrementAndGet()
                    listener.failed(Failure(index, explain(it)))
                }
            }.apply { name = "sim-burst-$index"; start() }
        }
        threads.forEach { it.join() }
        val bad = failures.get()
        return Outcome(RunKind.BURST, n, bad, if (bad == 0) 0 else 1)
    }

    /**
     * Connect and say nothing. The app should show the peer address and a
     * listening state with no frames — which is what a lab sees when the
     * analyzer's LIS setting points at BNM Lab but nobody has pressed Send.
     */
    private fun hang(factory: TransportFactory): Outcome {
        val holdSeconds = if (o.intervalSeconds > 0) o.intervalSeconds else 30.0
        factory.open().use { t ->
            listener.note("Connected: ${t.describe}")
            listener.note("Holding the link open for ${fmt(holdSeconds, 1)}s without sending anything.")
            listener.note("BNM Lab should show this address as the peer, bytes 0, frames 0.")
            Thread.sleep((holdSeconds * 1000).toLong())
        }
        listener.note("Closed. The app should log the connection closing with no frames.")
        return Outcome(RunKind.HANG, 0, 0, 0)
    }

    // ── one sample ──

    private fun sendOne(index: Int, transport: AnalyzerTransport): Boolean {
        val spec = specFor(index)
        val frame = frameBytes(spec)
        val wire = applyWireFaults(frame)

        listener.sampleStarted(
            SampleStarted(
                index = index,
                total = o.samples,
                specimenId = spec.specimenId,
                // Not spec.qc: --qc on a Mispa changes no byte of the frame, and
                // a transcript that says "QC" over a plain patient frame is read
                // as "the app's QC handling was exercised". runStarted's caveats
                // are where the engineer is told the flag did nothing.
                qc = spec.qc && o.analyzer == Analyzer.MINDRAY,
                cbc = spec.cbc,
                frame = render(wire),
            )
        )

        val started = System.nanoTime()
        writeWire(transport, wire)
        var sent = wire.size
        if (o.faults.duplicate) {
            // A retransmit: the analyzer did not believe the ACK. The app must
            // not double-apply it.
            writeWire(transport, wire)
            sent += wire.size
        }
        listener.bytesSent(BytesSent(index, sent, transport.describe, o.faults.duplicate))

        if (o.faults.truncated) {
            listener.note("  cut at ${percentOf(wire.size, frame.size)}% and closing — " +
                "the app should never see a complete frame")
            return true
        }
        if (o.faults.garbage) {
            listener.note("  that was not a frame: the app should count the bytes and frame nothing")
            return true
        }
        return awaitAck(index, transport, started)
    }

    /**
     * The ACK, or an honest statement that none came. Only the Mindray link has
     * one — saying "no ACK" about a Mispa cable would send an engineer hunting
     * a fault that does not exist.
     */
    private fun awaitAck(index: Int, transport: AnalyzerTransport, startedNanos: Long): Boolean {
        val outcome = when {
            o.dryRun -> AckOutcome.DryRun(index)                 // nothing was sent, so nothing can answer
            o.analyzer != Analyzer.MINDRAY -> AckOutcome.NotExpected(index)
            o.ackTimeoutMs <= 0 -> AckOutcome.NotWaited(index)
            else -> {
                val reply = transport.readReply(o.ackTimeoutMs)
                val elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000
                val ack = if (reply.isEmpty()) null else SimMllp.readAck(reply.decodeToString())
                when {
                    reply.isEmpty() -> AckOutcome.Missing(index, o.ackTimeoutMs / 1000.0)
                    ack == null -> AckOutcome.Unreadable(index, reply.size, render(reply).take(200))
                    else -> AckOutcome.Received(index, elapsedMs, ack)
                }
            }
        }
        listener.ackReceived(outcome)
        return outcome.ok
    }

    private fun writeWire(transport: AnalyzerTransport, wire: ByteArray) {
        val chunkDelay = o.faults.slowChunksMs
        if (chunkDelay == null) {
            transport.write(wire)
            return
        }
        // Small pieces with gaps: what a busy analyzer, a slow USB-serial
        // adapter or a fragmenting network actually does, and the thing the
        // app's frame assembler has to survive.
        val size = 64
        var at = 0
        var pieces = 0
        while (at < wire.size) {
            val end = minOf(wire.size, at + size)
            transport.write(wire.copyOfRange(at, end))
            pieces++
            at = end
            if (at < wire.size) Thread.sleep(chunkDelay)
        }
        listener.note("  dribbled out in $pieces pieces of $size bytes, ${chunkDelay}ms apart")
    }

    // ── building ──

    internal fun specFor(index: Int): SampleSpec = SampleSpec(
        specimenId = if (o.noSpecimen) null else o.ids[index % o.ids.size],
        patientId = o.patientId,
        patientName = o.patientName,
        profile = o.profile,
        // Every sample in a run is a different patient, reproducibly.
        seed = o.seed + index,
        sequence = index + 1,
        qc = o.qc,
        histograms = o.histograms,
        cbcOnly = o.cbcOnly,
        image = o.image,
        unknownCode = o.unknownCode,
        badUnits = o.badUnits,
        timestamp = clock(),
    )

    internal fun frameBytes(spec: SampleSpec): ByteArray = when (o.analyzer) {
        Analyzer.MINDRAY -> MindrayFrames.frame(spec)
        Analyzer.MISPA -> MispaFrames.build(spec).toByteArray(Charsets.UTF_8)
    }

    private fun applyWireFaults(frame: ByteArray): ByteArray = when {
        o.faults.garbage -> GARBAGE
        // Just past half: far enough in that the app has buffered a partial
        // frame, nowhere near an end-of-frame marker.
        o.faults.truncated -> frame.copyOf(maxOf(1, frame.size * 55 / 100))
        else -> frame
    }

    // ── what the run is ──

    private fun factory(): TransportFactory = when {
        transportOverride != null -> transportOverride
        o.dryRun -> NullTransport.factory()
        o.usesSerial -> SerialTransport.factory(o.serialPort!!, o.baud)
        else -> TcpClientTransport.factory(o.host, o.port)
    }

    private fun runStart() = RunStart(
        analyzer = o.analyzer,
        link = when {
            o.dryRun -> "dry run, nothing is sent"
            o.usesSerial -> "serial ${o.serialPort} @ ${o.baud} 8-N-1"
            else -> "TCP ${o.host}:${o.port} (the simulator dials out, as the analyzer does)"
        },
        samples = o.samples,
        profile = o.profile,
        seed = o.seed,
        cbcOnly = o.cbcOnly,
        faults = faultSummary(),
        specimenIds = if (o.noSpecimen) "none keyed (--no-specimen)" else summariseIds(),
        liveLabWarning = if (!o.dryRun && !o.usesSerial && !Cli.isLoopback(o.host)) liveLabWarning() else null,
        caveats = caveats(),
    )

    /**
     * What this run will NOT do, said before it does anything.
     *
     * A switch that is quietly a no-op is worse than a missing one: the
     * engineer ticks it off the commissioning list having tested nothing.
     */
    private fun caveats(): List<String> = buildList {
        if (o.qc && o.analyzer == Analyzer.MISPA) add(
            "--qc changes nothing on this link. The Mispa Count X format carries no processing-id field, " +
                "so this goes out as an ordinary patient frame and BNM Lab WILL file it as a patient " +
                "result. QC handling can only be rehearsed on the Mindray link.")
    }

    /** The banner shown before a live-lab run, which is the only warning between
     *  a shell-history re-run and synthetic results on a real patient's order. */
    private fun liveLabWarning(): String =
        "  " + "!".repeat(66) + "\n" +
            "  ! SENDING TO ANOTHER MACHINE: ${o.host}\n" +
            "  ! These results are invented. Once BNM Lab has filed them they are\n" +
            "  ! indistinguishable from the analyzer's own: on the order with that\n" +
            "  ! accession, attributed to the instrument, approvable, printable.\n" +
            "  ! Specimen id(s): " + summariseIds() + "\n" +
            "  " + "!".repeat(66)

    private fun summariseIds(): String =
        if (o.ids.size <= 3) o.ids.joinToString(", ")
        else "${o.ids.first()} … ${o.ids.last()} (${o.ids.size})"

    private fun faultSummary(): String = buildList {
        if (o.faults.truncated) add("truncated")
        if (o.faults.garbage) add("garbage")
        o.faults.slowChunksMs?.let { add("slow-chunks ${it}ms") }
        if (o.faults.duplicate) add("duplicate")
        if (o.faults.burst > 0) add("burst ${o.faults.burst}")
        if (o.faults.hang) add("hang")
        if (o.unknownCode) add("unknown-code")
        if (o.badUnits) add("bad-units")
        if (o.noSpecimen) add("no-specimen")
        // Only where it changes the frame: the Mispa has no QC field, and a
        // summary line saying "qc" over a plain patient frame is a lie the
        // engineer would take to mean the app's QC handling had been exercised.
        if (o.qc && o.analyzer == Analyzer.MINDRAY) add("qc")
    }.joinToString(", ")

    private fun percentOf(part: Int, whole: Int): Int = if (whole == 0) 0 else part * 100 / whole

    companion object {
        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

        /** Deliberate nonsense: no frame start for either driver, and a stray
         *  newline so it cannot accidentally look like an HL7 segment. */
        private val GARBAGE = " ÿ<<not a frame>>\nþQQQ 2f8a1c\n".toByteArray(Charsets.ISO_8859_1)

        /**
         * A frame as a human can read it: CR becomes a line break and any long
         * base64 run is abbreviated, because a 40 KB scattergram scrolled past
         * tells nobody anything.
         */
        fun render(bytes: ByteArray): String =
            abbreviate(bytes.toString(Charsets.UTF_8))
                .replace(SimMllp.SB, '␉')       // show the MLLP framing rather than eating it
                .replace(SimMllp.EB, '␜')
                .replace('\r', '\n')

        private val LONG_RUN = Regex("[A-Za-z0-9+/=]{200,}")

        private fun abbreviate(text: String): String = LONG_RUN.replace(text) { m ->
            val v = m.value
            v.take(48) + "…[" + (v.length - 48) + " more base64 chars]"
        }
    }
}

/** A network failure in the words a field engineer can act on. */
fun explain(e: Throwable): String = when (e) {
    is java.net.ConnectException ->
        "Connection refused. BNM Lab is not listening there — check the app is running, the analyzer " +
            "row is enabled, and the port matches the one on the Instruments screen."
    is java.net.SocketTimeoutException ->
        "Timed out. The address answered nothing — usually a firewall between this PC and the lab PC."
    is java.net.NoRouteToHostException, is java.net.UnknownHostException ->
        "Cannot reach that address. Check the IP and that both machines are on the same network."
    is java.net.BindException -> "Local port already in use: ${e.message}"
    else -> e.message ?: e::class.java.simpleName
}
