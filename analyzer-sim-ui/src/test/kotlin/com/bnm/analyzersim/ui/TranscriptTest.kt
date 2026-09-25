package com.bnm.analyzersim.ui

import com.bnm.analyzersim.AckOutcome
import com.bnm.analyzersim.Analyzer
import com.bnm.analyzersim.BytesSent
import com.bnm.analyzersim.Failure
import com.bnm.analyzersim.Profile
import com.bnm.analyzersim.RunFinished
import com.bnm.analyzersim.RunKind
import com.bnm.analyzersim.RunStart
import com.bnm.analyzersim.SampleStarted
import com.bnm.analyzersim.SimMllp
import com.bnm.analyzersim.cbcFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The fold from core events to what the window draws. Pure, so a whole run can
 *  be replayed with no socket — which is also how the render tests build one. */
class TranscriptTest {

    @Test
    fun `a successful sample ends green, a failed one ends red`() {
        var blocks = applyEvent(emptyList(), SimEvent.Started(runStart()))
        blocks = applyEvent(blocks, SimEvent.Sample(sample(0, 2, "ACC-S1-00042")))
        blocks = applyEvent(blocks, SimEvent.Bytes(BytesSent(0, 3519, "TCP -> 127.0.0.1:5500", false)))
        blocks = applyEvent(blocks, SimEvent.Ack(AckOutcome.Received(0, 14, ack("AA"))))
        blocks = applyEvent(blocks, SimEvent.Sample(sample(1, 2, "ACC-S1-00043")))
        blocks = applyEvent(blocks, SimEvent.Bytes(BytesSent(1, 3519, "TCP -> 127.0.0.1:5500", false)))
        blocks = applyEvent(blocks, SimEvent.Ack(AckOutcome.Missing(1, 5.0)))
        blocks = applyEvent(blocks, SimEvent.Finished(RunFinished(RunKind.SEQUENTIAL, 2, 1, 1)))

        assertEquals(4, blocks.size, "header, two samples, summary")
        assertEquals(BlockTone.OK, blocks[1].tone)
        assertEquals(BlockTone.FAILED, blocks[2].tone)
        assertEquals(BlockTone.FAILED, blocks.last().tone)
        assertEquals("Done — 1 of 2 sent, 1 failed.", blocks.last().title)
        assertTrue(blocks[1].lines.any { it.contains("ACK after 14ms") }, "${blocks[1].lines}")
        assertTrue(blocks[2].lines.any { it.contains("no ACK within 5.0s") }, "${blocks[2].lines}")
    }

    @Test
    fun `a note lands on the sample it belongs to, not in a block of its own`() {
        var blocks = applyEvent(emptyList(), SimEvent.Started(runStart()))
        blocks = applyEvent(blocks, SimEvent.Sample(sample(0, 1, "ACC-S1-00042")))
        blocks = applyEvent(blocks, SimEvent.Note("dribbled out in 55 pieces of 64 bytes, 40ms apart"))
        assertEquals(2, blocks.size)
        assertTrue(blocks.last().lines.last().startsWith("dribbled out"))
    }

    @Test
    fun `a burst failure reaches the connection it came from`() {
        var blocks = applyEvent(emptyList(), SimEvent.Started(runStart()))
        blocks = applyEvent(blocks, SimEvent.Sample(sample(0, 3, "A")))
        blocks = applyEvent(blocks, SimEvent.Sample(sample(1, 3, "B")))
        blocks = applyEvent(blocks, SimEvent.Ack(AckOutcome.Received(1, 9, ack("AA"))))
        blocks = applyEvent(blocks, SimEvent.Failed(Failure(0, "Connection refused.")))
        assertEquals(BlockTone.FAILED, blocks[1].tone, "the failure must colour connection 0, not the latest block")
        assertEquals(BlockTone.OK, blocks[2].tone)
    }

    @Test
    fun `a refused connection before any sample is its own block`() {
        var blocks = applyEvent(emptyList(), SimEvent.Started(runStart()))
        blocks = applyEvent(blocks, SimEvent.Failed(Failure(null, "Connection refused. BNM Lab is not listening")))
        blocks = applyEvent(blocks, SimEvent.Finished(RunFinished(RunKind.ABORTED, 1, 1, 1)))
        assertEquals("Could not send", blocks[1].title)
        assertEquals(BlockTone.FAILED, blocks[1].tone)
        assertEquals("Stopped — nothing further was sent.", blocks.last().title)
    }

    @Test
    fun `starting a run clears whatever the last one left behind`() {
        val stale = listOf(TranscriptBlock("old", listOf("old line")))
        assertEquals(1, applyEvent(stale, SimEvent.Started(runStart())).size)
    }

    @Test
    fun `the whole frame is never dumped into the window`() {
        val blocks = applyEvent(listOf(TranscriptBlock("x")), SimEvent.Detail("MSH|^~\\&|BC-5130|…"))
        assertEquals(listOf(TranscriptBlock("x")), blocks, "--verbose material belongs in the CLI, not here")
    }

    @Test
    fun `the copied transcript is the same text a bug report needs`() {
        var blocks = applyEvent(emptyList(), SimEvent.Started(runStart()))
        blocks = applyEvent(blocks, SimEvent.Sample(sample(0, 1, "ACC-S1-00042")))
        blocks = applyEvent(blocks, SimEvent.Bytes(BytesSent(0, 3519, "TCP -> 127.0.0.1:5500", true)))
        val text = transcriptText(blocks)
        assertTrue(text.contains("Mindray BC-5130"), text)
        assertTrue(text.contains("driver the lab must have selected: mindray_hl7"), text)
        assertTrue(text.contains("3519 bytes out over TCP -> 127.0.0.1:5500 (the same frame twice)"), text)
    }

    @Test
    fun `a hang says what BNM Lab should have logged`() {
        val blocks = applyEvent(emptyList(), SimEvent.Finished(RunFinished(RunKind.HANG, 0, 0, 0)))
        assertTrue(blocks.single().title.contains("no frames"))
        assertEquals(BlockTone.OK, blocks.single().tone)
    }

    @Test
    fun `a live-lab run opens with the warning, above everything`() {
        val blocks = applyEvent(emptyList(), SimEvent.Started(runStart().copy(
            liveLabWarning = "  " + "!".repeat(66) + "\n" +
                "  ! SENDING TO ANOTHER MACHINE: 192.168.1.50\n" +
                "  ! These results are invented.\n" +
                "  " + "!".repeat(66),
        )))
        assertEquals(2, blocks.size)
        assertEquals(BlockTone.FAILED, blocks[1].tone)
        assertTrue(blocks[1].title.contains("another machine"))
        assertTrue(blocks[1].lines.any { it.contains("192.168.1.50") }, "${blocks[1].lines}")
        // The terminal's rows of "!" are framing for a monospace console; a
        // window has colour, and empty bars would push the sentences down.
        assertTrue(blocks[1].lines.none { it.isBlank() || it.all { c -> c == '!' } }, "${blocks[1].lines}")
    }

    @Test
    fun `a switch that does nothing says so before the first sample`() {
        val caveat = "--qc changes nothing on this link. The Mispa Count X format carries no processing-id field."
        val blocks = applyEvent(emptyList(), SimEvent.Started(runStart().copy(caveats = listOf(caveat))))
        assertEquals(BlockTone.FAILED, blocks[1].tone)
        assertEquals(listOf(caveat), blocks[1].lines)
    }

    @Test
    fun `a Mispa sample never claims to be a QC run`() {
        // The core sets qc=false on a link whose format cannot carry it; the
        // transcript must then not print a QC line the engineer would tick off.
        val blocks = applyEvent(
            applyEvent(emptyList(), SimEvent.Started(runStart())),
            SimEvent.Sample(sample(0, 1, "ACC-S1-00042")),
        )
        assertTrue(blocks.last().lines.none { it.contains("QC") }, "${blocks.last().lines}")
    }

    // ── fixtures ──

    private fun runStart() = RunStart(
        analyzer = Analyzer.MINDRAY,
        link = "TCP 127.0.0.1:5500 (the simulator dials out, as the analyzer does)",
        samples = 2, profile = Profile.NORMAL, seed = 7L, faults = "",
        specimenIds = "ACC-S1-00042, ACC-S1-00043",
    )

    private fun sample(index: Int, total: Int, id: String?) = SampleStarted(
        index = index, total = total, specimenId = id, qc = false,
        cbc = cbcFor(Profile.NORMAL, 7L + index), frame = "MSH|…",
    )

    private fun ack(code: String) = SimMllp.Ack(code, "SIM20260101090000001", "MSA|$code|SIM20260101090000001")
}