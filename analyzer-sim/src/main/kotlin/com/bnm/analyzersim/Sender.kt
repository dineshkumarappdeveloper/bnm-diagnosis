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
 */
class Sender(
    private val o: Options,
    private val out: Printer,
    /** yyyyMMddHHmmss; injected so a test gets a frame it can predict. */
    private val clock: () -> String = { LocalDateTime.now().format(STAMP) },
    /** Swaps the real socket out. Only the tests pass this — the CLI never does,
     *  so what the tests exercise is the same code the engineer runs. */
    private val transportOverride: TransportFactory? = null,
) {

    /** Process exit code: 0 when every sample went out as intended. */
    fun run(): Int {
        out.info(banner())
        val factory = factory()
        return try {
            when {
                o.faults.hang -> hang(factory)
                o.faults.burst > 0 -> burst(factory)
                else -> sequential(factory)
            }
        } catch (e: Exception) {
            out.warn(explain(e))
            1
        } finally {
            (factory as? AutoCloseable)?.let { runCatching { it.close() } }
        }
    }

    // ── the three shapes a run can take ──

    private fun sequential(factory: TransportFactory): Int {
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
        out.info(if (failures == 0) "Done — ${o.samples} sample(s) sent."
        else "Done — ${o.samples - failures} of ${o.samples} sent, $failures failed.")
        return if (failures == 0) 0 else 1
    }

    /**
     * Several analyzers (or one analyzer retrying) hitting the listener at the
     * same instant. The app accepts each connection on its own coroutine, and
     * this is the only way to prove that from outside.
     */
    private fun burst(factory: TransportFactory): Int {
        val n = o.faults.burst
        out.info("Burst: opening $n connections at once.")
        val failures = AtomicInteger(0)
        val threads = (0 until n).map { index ->
            Thread {
                runCatching {
                    factory.open().use { t -> if (!sendOne(index, t)) failures.incrementAndGet() }
                }.onFailure {
                    failures.incrementAndGet()
                    out.warn("connection $index: ${explain(it)}")
                }
            }.apply { name = "sim-burst-$index"; start() }
        }
        threads.forEach { it.join() }
        val bad = failures.get()
        out.info(if (bad == 0) "Done — all $n connections completed." else "Done — $bad of $n failed.")
        return if (bad == 0) 0 else 1
    }

    /**
     * Connect and say nothing. The app should show the peer address and a
     * listening state with no frames — which is what a lab sees when the
     * analyzer's LIS setting points at BNM Lab but nobody has pressed Send.
     */
    private fun hang(factory: TransportFactory): Int {
        val holdSeconds = if (o.intervalSeconds > 0) o.intervalSeconds else 30.0
        factory.open().use { t ->
            out.info("Connected: ${t.describe}")
            out.info("Holding the link open for ${fmt(holdSeconds, 1)}s without sending anything.")
            out.info("BNM Lab should show this address as the peer, bytes 0, frames 0.")
            Thread.sleep((holdSeconds * 1000).toLong())
        }
        out.info("Closed. The app should log the connection closing with no frames.")
        return 0
    }

    // ── one sample ──

    private fun sendOne(index: Int, transport: AnalyzerTransport): Boolean {
        val spec = specFor(index)
        val frame = frameBytes(spec)
        val wire = applyWireFaults(frame)

        out.info("")
        out.info("[${index + 1}/${o.samples}] ${describe(spec)}")
        out.detail(render(wire))

        val started = System.nanoTime()
        writeWire(transport, wire)
        var sent = wire.size
        if (o.faults.duplicate) {
            // A retransmit: the analyzer did not believe the ACK. The app must
            // not double-apply it.
            writeWire(transport, wire)
            sent += wire.size
            out.info("  sent the same frame twice (--duplicate)")
        }
        out.info("  $sent bytes out over ${transport.describe}")

        if (o.faults.truncated) {
            out.info("  cut at ${percentOf(wire.size, frame.size)}% and closing — the app should never see a complete frame")
            return true
        }
        if (o.faults.garbage) {
            out.info("  that was not a frame: the app should count the bytes and frame nothing")
            return true
        }
        return awaitAck(transport, started)
    }

    /**
     * The ACK, or an honest statement that none came. Only the Mindray link has
     * one — saying "no ACK" about a Mispa cable would send an engineer hunting
     * a fault that does not exist.
     */
    private fun awaitAck(transport: AnalyzerTransport, startedNanos: Long): Boolean {
        if (o.dryRun) return true                      // nothing was sent, so nothing can answer
        if (o.analyzer != Analyzer.MINDRAY) {
            out.info("  one-way protocol — no ACK is expected")
            return true
        }
        if (o.ackTimeoutMs <= 0) {
            out.info("  not waiting for an ACK (--no-ack-wait) — the app still sends one; nobody reads it")
            return true
        }
        val reply = transport.readReply(o.ackTimeoutMs)
        val elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000
        if (reply.isEmpty()) {
            out.warn("  no ACK within ${fmt(o.ackTimeoutMs / 1000.0, 1)}s — a real BC-5130 would mark this " +
                "sample 'transmission failed' and may retransmit")
            return false
        }
        val ack = SimMllp.readAck(reply.decodeToString())
        if (ack == null) {
            out.warn("  ${reply.size} bytes came back but no MSA segment could be read: " +
                render(reply).take(200))
            return false
        }
        out.info("  ACK after ${elapsedMs}ms: $ack${if (ack.accepted) "" else "  <- NOT an accept"}")
        out.detail("  " + ack.raw.replace('\r', '\n').trim().replace("\n", "\n  "))
        return ack.accepted
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
        out.info("  dribbled out in $pieces pieces of $size bytes, ${chunkDelay}ms apart")
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

    // ── transcript ──

    private fun factory(): TransportFactory = when {
        transportOverride != null -> transportOverride
        o.dryRun -> NullTransport.factory()
        o.usesSerial -> SerialTransport.factory(o.serialPort!!, o.baud)
        else -> TcpClientTransport.factory(o.host, o.port)
    }

    private fun banner(): String = buildString {
        appendLine("BNM Analyzer Simulator — ${o.analyzer.label}")
        appendLine("  driver the lab must have selected: ${o.analyzer.driverKey}")
        appendLine("  link: " + when {
            o.dryRun -> "dry run, nothing is sent"
            o.usesSerial -> "serial ${o.serialPort} @ ${o.baud} 8-N-1"
            else -> "TCP ${o.host}:${o.port} (the simulator dials out, as the analyzer does)"
        })
        appendLine("  samples: ${o.samples} · profile ${o.profile.cliName} · seed ${o.seed}")
        val faults = faultSummary()
        if (faults.isNotEmpty()) appendLine("  FAULTS: $faults")
        append("  specimen id(s): " + if (o.noSpecimen) "none keyed (--no-specimen)" else summariseIds())
    }

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
        if (o.qc) add("qc")
    }.joinToString(", ")

    private fun describe(spec: SampleSpec): String {
        val c = spec.cbc
        val id = spec.specimenId ?: "(no specimen id)"
        return "$id · WBC ${fmt(c.wbc, 2)} · RBC ${fmt(c.rbc, 2)} · HGB ${fmt(c.hgb, 1)} g/dL · " +
            "PLT ${fmt(c.plt, 0)}" + if (spec.qc) " · QC" else ""
    }

    private fun percentOf(part: Int, whole: Int): Int = if (whole == 0) 0 else part * 100 / whole

    companion object {
        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

        /** Deliberate nonsense: no frame start for either driver, and a stray
         *  newline so it cannot accidentally look like an HL7 segment. */
        private val GARBAGE = " ÿ<<not a frame>>\nþQQQ 2f8a1c\n".toByteArray(Charsets.ISO_8859_1)

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
