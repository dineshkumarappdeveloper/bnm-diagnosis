package com.bnm.analyzersim.ui

import com.bnm.analyzersim.AckOutcome
import com.bnm.analyzersim.Analyzer
import com.bnm.analyzersim.BytesSent
import com.bnm.analyzersim.Failure
import com.bnm.analyzersim.RunFinished
import com.bnm.analyzersim.RunKind
import com.bnm.analyzersim.RunStart
import com.bnm.analyzersim.SampleStarted
import com.bnm.analyzersim.SendListener
import com.bnm.analyzersim.fmt

/** A core [SendListener] callback, boxed so it can cross a thread as one value. */
sealed interface SimEvent {
    data class Started(val run: RunStart) : SimEvent
    data class Sample(val sample: SampleStarted) : SimEvent
    data class Bytes(val sent: BytesSent) : SimEvent
    data class Ack(val ack: AckOutcome) : SimEvent
    data class Failed(val failure: Failure) : SimEvent
    data class Note(val line: String) : SimEvent
    data class Detail(val line: String) : SimEvent
    data class Finished(val summary: RunFinished) : SimEvent
}

/**
 * Hands every event to [sink] and does nothing else.
 *
 * Called on the sending thread, so [sink] must be a handoff (a channel) and
 * never a Compose state write — a background thread mutating snapshot state is
 * the classic way to make a window flicker or tear.
 */
class ForwardingSendListener(private val sink: (SimEvent) -> Unit) : SendListener {
    override fun runStarted(run: RunStart) = sink(SimEvent.Started(run))
    override fun sampleStarted(sample: SampleStarted) = sink(SimEvent.Sample(sample))
    override fun bytesSent(sent: BytesSent) = sink(SimEvent.Bytes(sent))
    override fun ackReceived(ack: AckOutcome) = sink(SimEvent.Ack(ack))
    override fun failed(failure: Failure) = sink(SimEvent.Failed(failure))
    override fun note(line: String) = sink(SimEvent.Note(line.trim()))
    override fun detail(line: String) = sink(SimEvent.Detail(line))
    override fun finished(summary: RunFinished) = sink(SimEvent.Finished(summary))
}

/** How a block reads at a glance. */
enum class BlockTone { NEUTRAL, RUNNING, OK, FAILED }

/** One paragraph of the transcript: a sample, the header, or the summary. */
data class TranscriptBlock(
    val title: String,
    val lines: List<String> = emptyList(),
    val tone: BlockTone = BlockTone.NEUTRAL,
    /** Set on sample blocks so a later event knows which block to grow. */
    val sampleIndex: Int? = null,
)

/**
 * The transcript, as a pure fold over the events.
 *
 * Pure on purpose: the render tests build a transcript with one success and one
 * failure by replaying events, with no socket and no window, and get exactly
 * what a real run would draw.
 */
fun applyEvent(blocks: List<TranscriptBlock>, event: SimEvent): List<TranscriptBlock> = when (event) {

    is SimEvent.Started -> listOf(headerBlock(event.run))

    is SimEvent.Sample -> blocks + TranscriptBlock(
        title = "[${event.sample.index + 1}/${event.sample.total}] " +
            (event.sample.specimenId ?: "no specimen id keyed"),
        lines = listOf(event.sample.headline.substringAfter(" · ")) +
            if (event.sample.qc) listOf("QC material run") else emptyList(),
        tone = BlockTone.RUNNING,
        sampleIndex = event.sample.index,
    )

    is SimEvent.Bytes -> blocks.grow(event.sent.sampleIndex) { b ->
        val sent = "${event.sent.bytes} bytes out over ${event.sent.over}"
        b.copy(lines = b.lines + if (event.sent.duplicated) "$sent (the same frame twice)" else sent)
    }

    is SimEvent.Ack -> blocks.grow(event.ack.sampleIndex) { b ->
        b.copy(
            lines = b.lines + ackLine(event.ack),
            tone = if (event.ack.ok) BlockTone.OK else BlockTone.FAILED,
        )
    }

    is SimEvent.Failed -> {
        val target = event.failure.sampleIndex
        if (target != null && blocks.any { it.sampleIndex == target }) {
            blocks.grow(target) { it.copy(lines = it.lines + event.failure.message, tone = BlockTone.FAILED) }
        } else {
            blocks + TranscriptBlock("Could not send", listOf(event.failure.message), BlockTone.FAILED)
        }
    }

    // A note belongs to the sample being sent; before the first one it stands alone.
    is SimEvent.Note ->
        if (blocks.isEmpty() || blocks.last().sampleIndex == null)
            blocks + TranscriptBlock(event.line, tone = BlockTone.NEUTRAL)
        else blocks.dropLast(1) + blocks.last().let { it.copy(lines = it.lines + event.line) }

    // --verbose material: the whole frame. Too much for a window that has to
    // stay readable while five samples go past.
    is SimEvent.Detail -> blocks

    is SimEvent.Finished -> blocks + summaryBlock(event.summary)
}

private fun headerBlock(run: RunStart): TranscriptBlock = TranscriptBlock(
    title = run.analyzer.shortName,
    lines = buildList {
        add(run.link)
        add("driver the lab must have selected: ${run.analyzer.driverKey}")
        add("${run.samples} sample(s) · profile ${run.profile.cliName} · seed ${run.seed}")
        add("specimen id(s): ${run.specimenIds}")
        if (run.faults.isNotEmpty()) add("FAULTS: ${run.faults}")
    },
    tone = BlockTone.NEUTRAL,
)

private fun summaryBlock(s: RunFinished): TranscriptBlock = when (s.kind) {
    RunKind.SEQUENTIAL -> TranscriptBlock(
        title = if (s.failed == 0) "Done — ${s.total} sample(s) sent."
        else "Done — ${s.total - s.failed} of ${s.total} sent, ${s.failed} failed.",
        tone = if (s.ok) BlockTone.OK else BlockTone.FAILED,
    )
    RunKind.BURST -> TranscriptBlock(
        title = if (s.failed == 0) "Done — all ${s.total} connections completed."
        else "Done — ${s.failed} of ${s.total} connections failed.",
        tone = if (s.ok) BlockTone.OK else BlockTone.FAILED,
    )
    RunKind.HANG -> TranscriptBlock(
        title = "Closed. BNM Lab should log the connection closing with no frames.",
        tone = BlockTone.OK,
    )
    // The failure itself is already a block of its own; this just ends the run.
    RunKind.ABORTED -> TranscriptBlock(title = "Stopped — nothing further was sent.", tone = BlockTone.FAILED)
}

/** The ACK in one line, in the words the engineer needs to act on. */
fun ackLine(ack: AckOutcome): String = when (ack) {
    is AckOutcome.DryRun -> "dry run — nothing was sent, so nothing can answer"
    is AckOutcome.NotExpected -> "one-way protocol — no ACK is expected"
    is AckOutcome.NotWaited -> "not waiting for an ACK — the app still sends one; nobody reads it"
    is AckOutcome.Missing ->
        "no ACK within ${fmt(ack.waitedSeconds, 1)}s — a real analyzer would mark this " +
            "'transmission failed' and may retransmit"
    is AckOutcome.Unreadable -> "${ack.bytes} bytes came back but no MSA segment could be read"
    is AckOutcome.Received ->
        "ACK after ${ack.elapsedMs}ms: ${ack.ack}" + if (ack.ack.accepted) "" else "  <- NOT an accept"
}

/** The whole transcript as text, for the Copy button and for a bug report. */
fun transcriptText(blocks: List<TranscriptBlock>): String = blocks.joinToString("\n\n") { b ->
    (listOf(b.title) + b.lines.map { "  $it" }).joinToString("\n")
}

/** Grow the block for [sampleIndex]; the last block if none carries that index. */
private fun List<TranscriptBlock>.grow(
    sampleIndex: Int,
    change: (TranscriptBlock) -> TranscriptBlock,
): List<TranscriptBlock> {
    val at = indexOfLast { it.sampleIndex == sampleIndex }
    if (at < 0) return if (isEmpty()) this else dropLast(1) + change(last())
    return take(at) + change(this[at]) + drop(at + 1)
}

/** "Mindray BC-5130", not the whole sentence the CLI banner carries. */
val Analyzer.shortName: String
    get() = when (this) {
        Analyzer.MINDRAY -> "Mindray BC-5130"
        Analyzer.MISPA -> "Agappe Mispa Count X"
    }
