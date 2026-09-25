package com.bnm.analyzersim

/**
 * What a run does, as events rather than as printed lines.
 *
 * The CLI needs a transcript on stdout; a window needs the same facts as data
 * it can colour, count and lay out. Printing was the only report [Sender] made,
 * so a second front end would have had to scrape its own output — and a
 * transcript parsed for meaning is a transcript nobody may reword.
 *
 * So the events are the contract and printing is one implementation of it
 * ([PrintingSendListener], which the CLI uses and which owns every sentence the
 * CLI has always printed).
 *
 * Every call arrives on the thread that is running the send, which is not the
 * caller's thread in a UI — implementations must be cheap and must not assume
 * a UI toolkit.
 */
interface SendListener {

    /** Once, before anything is opened. */
    fun runStarted(run: RunStart)

    /** A sample has been built and is about to go out. */
    fun sampleStarted(sample: SampleStarted)

    /** The bytes for that sample have been handed to the transport. */
    fun bytesSent(sent: BytesSent)

    /** What the app said back — or why nothing was expected. */
    fun ackReceived(ack: AckOutcome)

    /** The run could not do what it was asked. [Failure.sampleIndex] is null for the run itself. */
    fun failed(failure: Failure)

    /** A line worth showing that carries no other event: chunking, the hang notice. */
    fun note(line: String)

    /** The same, but only worth showing when the engineer asked for detail (`--verbose`). */
    fun detail(line: String)

    /** Once, always — including after a failure, so a UI can stop spinning. */
    fun finished(summary: RunFinished)
}

/** The link and the plan, settled before the first byte. */
data class RunStart(
    val analyzer: Analyzer,
    /** e.g. "TCP 192.168.1.50:5500 (the simulator dials out, as the analyzer does)". */
    val link: String,
    val samples: Int,
    val profile: Profile,
    val seed: Long,
    /** Mindray run mode CBC — no differential at all, not merely no curves. */
    val cbcOnly: Boolean = false,
    /** "" when the run is clean; otherwise "truncated, burst 5". */
    val faults: String,
    /** "BNMTEST-0001 … BNMTEST-0005 (5)", or "none keyed (--no-specimen)". */
    val specimenIds: String,
    /**
     * Set only when this run is aimed at another machine: the last thing
     * between a re-run out of shell history and invented results filed onto a
     * real patient's order. Show it where it cannot be missed.
     */
    val liveLabWarning: String? = null,
    /**
     * What this run will NOT do, however it was asked. A switch that is quietly
     * a no-op is worse than a missing one — the engineer ticks it off the
     * commissioning list having tested nothing.
     */
    val caveats: List<String> = emptyList(),
)

/** One sample, with the numbers it claims to have measured. */
data class SampleStarted(
    /** 0-based. */
    val index: Int,
    val total: Int,
    /** Null = nothing was keyed on the analyzer. */
    val specimenId: String?,
    /** The frame really does mark this as control material. False on a Mispa
     *  even when QC was asked for: that format has no field to carry it. */
    val qc: Boolean,
    val cbc: Cbc,
    /** The frame as a human can read it — see [Sender.render]. */
    val frame: String,
) {
    /** The one line worth showing when there is room for one line. */
    val headline: String =
        (specimenId ?: "(no specimen id)") +
            " · WBC ${fmt(cbc.wbc, 2)} · RBC ${fmt(cbc.rbc, 2)} · HGB ${fmt(cbc.hgb, 1)} g/dL · " +
            "PLT ${fmt(cbc.plt, 0)}" + if (qc) " · QC" else ""
}

data class BytesSent(
    val sampleIndex: Int,
    val bytes: Int,
    /** The transport's own words: "TCP 127.0.0.1:52344 -> 127.0.0.1:5500". */
    val over: String,
    /** The frame went out twice — a retransmit the app must not double-apply. */
    val duplicated: Boolean,
)

/**
 * The ACK, or the honest reason there is none. Only the Mindray link has one;
 * reporting "no ACK" about a Mispa cable would send an engineer hunting a fault
 * that does not exist.
 */
sealed interface AckOutcome {
    val sampleIndex: Int
    /** Whether this counts the sample as delivered. */
    val ok: Boolean

    /** Nothing was sent, so nothing can answer. */
    data class DryRun(override val sampleIndex: Int) : AckOutcome {
        override val ok get() = true
    }

    /** A one-way protocol: the analyzer never hears anything back. */
    data class NotExpected(override val sampleIndex: Int) : AckOutcome {
        override val ok get() = true
    }

    /** `--no-ack-wait`: the app still sends one; nobody reads it. */
    data class NotWaited(override val sampleIndex: Int) : AckOutcome {
        override val ok get() = true
    }

    /** Silence — what makes a real analyzer mark the sample "transmission failed". */
    data class Missing(override val sampleIndex: Int, val waitedSeconds: Double) : AckOutcome {
        override val ok get() = false
    }

    /** Bytes came back, but no MSA segment in them. */
    data class Unreadable(
        override val sampleIndex: Int,
        val bytes: Int,
        val preview: String,
    ) : AckOutcome {
        override val ok get() = false
    }

    data class Received(
        override val sampleIndex: Int,
        val elapsedMs: Long,
        val ack: SimMllp.Ack,
    ) : AckOutcome {
        override val ok get() = ack.accepted
    }
}

/** Something went wrong, in words a field engineer can act on. */
data class Failure(
    /** Null when the run itself failed rather than one sample. */
    val sampleIndex: Int?,
    val message: String,
)

/** Which shape the run took — the summary reads differently for each. */
enum class RunKind {
    /** Samples one after another. */
    SEQUENTIAL,

    /** N connections at once. */
    BURST,

    /** Connect, send nothing, hold the socket. */
    HANG,

    /** The run threw before it could finish; [Failure] already said why. */
    ABORTED,
}

data class RunFinished(
    val kind: RunKind,
    val total: Int,
    val failed: Int,
    /** What the process exits with: 0 only when everything went out as intended. */
    val exitCode: Int,
) {
    val ok: Boolean get() = exitCode == 0
}

/**
 * The CLI's transcript — and the only place its sentences live.
 *
 * Every string here is one the tool has printed since it shipped;
 * `CliTranscriptParityTest` pins a whole dry run against them, because an
 * engineer following the README step by step is reading for these exact words.
 */
class PrintingSendListener(private val out: Printer) : SendListener {

    override fun runStarted(run: RunStart) {
        out.info(buildString {
            appendLine("BNM Analyzer Simulator — ${run.analyzer.label}")
            appendLine("  driver the lab must have selected: ${run.analyzer.driverKey}")
            appendLine("  link: ${run.link}")
            appendLine("  samples: ${run.samples} · profile ${run.profile.cliName} · seed ${run.seed}" +
                if (run.cbcOnly) " · CBC-only run (no differential)" else "")
            if (run.faults.isNotEmpty()) appendLine("  FAULTS: ${run.faults}")
            // No trailing newline unless the warning follows: a banner that
            // always ended in one put a second blank line before every sample.
            append("  specimen id(s): ${run.specimenIds}")
            run.liveLabWarning?.let { appendLine(); append(it) }
        })
        for (caveat in run.caveats) out.warn(caveat)
    }

    override fun sampleStarted(sample: SampleStarted) {
        out.info("")
        out.info("[${sample.index + 1}/${sample.total}] ${sample.headline}")
        out.detail(sample.frame)
    }

    override fun bytesSent(sent: BytesSent) {
        if (sent.duplicated) out.info("  sent the same frame twice (--duplicate)")
        out.info("  ${sent.bytes} bytes out over ${sent.over}")
    }

    override fun ackReceived(ack: AckOutcome) {
        when (ack) {
            is AckOutcome.DryRun -> Unit                    // nothing was sent; saying so twice helps nobody
            is AckOutcome.NotExpected -> out.info("  one-way protocol — no ACK is expected")
            is AckOutcome.NotWaited ->
                out.info("  not waiting for an ACK (--no-ack-wait) — the app still sends one; nobody reads it")
            is AckOutcome.Missing ->
                out.warn("  no ACK within ${fmt(ack.waitedSeconds, 1)}s — a real BC-5130 would mark this " +
                    "sample 'transmission failed' and may retransmit")
            is AckOutcome.Unreadable ->
                out.warn("  ${ack.bytes} bytes came back but no MSA segment could be read: ${ack.preview}")
            is AckOutcome.Received -> {
                out.info("  ACK after ${ack.elapsedMs}ms: ${ack.ack}" +
                    if (ack.ack.accepted) "" else "  <- NOT an accept")
                out.detail("  " + ack.ack.raw.replace('\r', '\n').trim().replace("\n", "\n  "))
            }
        }
    }

    override fun failed(failure: Failure) {
        val where = failure.sampleIndex?.let { "connection $it: " } ?: ""
        out.warn(where + failure.message)
    }

    override fun note(line: String) = out.info(line)

    override fun detail(line: String) = out.detail(line)

    override fun finished(summary: RunFinished) {
        when (summary.kind) {
            RunKind.SEQUENTIAL -> out.info(
                if (summary.failed == 0) "Done — ${summary.total} sample(s) sent."
                else "Done — ${summary.total - summary.failed} of ${summary.total} sent, ${summary.failed} failed."
            )
            RunKind.BURST -> out.info(
                if (summary.failed == 0) "Done — all ${summary.total} connections completed."
                else "Done — ${summary.failed} of ${summary.total} failed."
            )
            // A hang says its own last word; an aborted run already printed the failure.
            RunKind.HANG, RunKind.ABORTED -> Unit
        }
    }
}
