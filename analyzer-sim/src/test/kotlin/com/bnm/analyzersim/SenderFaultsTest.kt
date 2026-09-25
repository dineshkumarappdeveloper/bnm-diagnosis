package com.bnm.analyzersim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What actually reaches the wire under each fault flag, and what the transcript
 * says about it. The socket is swapped for a recorder — the fault logic is the
 * thing under test, not TCP.
 */
class SenderFaultsTest {

    /** Records every write, and answers with a canned ACK. */
    private class Capture(private val ack: ByteArray? = null) : AnalyzerTransport {
        val writes = mutableListOf<ByteArray>()
        var opened = 0
        var closed = 0
        override val describe = "capture"
        override fun write(bytes: ByteArray) { writes += bytes }
        override fun readReply(timeoutMs: Long): ByteArray = ack ?: ByteArray(0)
        override fun close() { closed++ }
        val allBytes: ByteArray get() = writes.fold(ByteArray(0)) { a, b -> a + b }

        fun factory(perSample: Boolean = true) = object : TransportFactory {
            override val perSample = perSample
            override val describe = "capture"
            override fun open(): AnalyzerTransport { opened++; return this@Capture }
        }
    }

    private val goodAck = SimMllp.wrap("MSH|^~\\&|BNM Lab||BC-5130|Mindray|20260101090001||ACK^R01|A1|P|2.3.1\rMSA|AA|SIM20260101090000001\r")

    private fun run(args: List<String>, capture: Capture, out: RecordingPrinter = RecordingPrinter()): Pair<Int, RecordingPrinter> {
        val options = Cli.parse(args)
        val code = Sender(options, out, clock = { "20260101090000" }, transportOverride = capture.factory()).run()
        return code to out
    }

    @Test
    fun `a clean Mindray run sends one MLLP frame and reports the ACK`() {
        val capture = Capture(goodAck)
        val (code, out) = run(listOf("mindray", "--id", "ACC-S1-00042"), capture)
        assertEquals(0, code)
        assertEquals(1, capture.writes.size)
        assertEquals(SimMllp.SB, capture.allBytes.decodeToString().first())
        assertTrue(out.text.contains("MSA|AA"), "the transcript must name the ACK code:\n${out.text}")
    }

    @Test
    fun `no ACK is reported as a failure, in the words the analyzer would use`() {
        val capture = Capture(ack = null)
        val (code, out) = run(listOf("mindray", "--ack-timeout", "0.1"), capture)
        assertEquals(1, code, "a sample the analyzer would mark 'transmission failed' is not a success")
        assertTrue(out.text.contains("no ACK within"), out.text)
        assertTrue(out.text.contains("transmission failed"), out.text)
    }

    @Test
    fun `no-ack-wait sends and hangs up without reading`() {
        val capture = Capture(ack = null)
        val (code, out) = run(listOf("mindray", "--no-ack-wait"), capture)
        assertEquals(0, code)
        assertTrue(out.text.contains("not waiting for an ACK"), out.text)
    }

    @Test
    fun `truncated puts out roughly half a frame and never a frame end`() {
        val capture = Capture(goodAck)
        val (_, out) = run(listOf("mindray", "--truncated"), capture)
        val sent = capture.allBytes.decodeToString()
        val whole = MindrayFrames.frame(SampleSpec(specimenId = "SIM-0001", timestamp = "20260101090000")).size
        assertTrue(sent.length < whole, "nothing was cut")
        assertTrue(sent.length > whole / 4, "cut so early the app would not even buffer a partial frame")
        assertTrue(SimMllp.EB !in sent, "a truncated frame must not carry the end-of-block byte")
        assertTrue(out.text.contains("never see a complete frame"), out.text)
    }

    @Test
    fun `truncated mispa carries no frame terminator either`() {
        val capture = Capture()
        run(listOf("mispa", "--truncated"), capture)
        assertTrue(!capture.allBytes.decodeToString().endsWith("###"))
    }

    @Test
    fun `garbage is not a frame for either driver`() {
        for (analyzer in listOf("mindray", "mispa")) {
            val capture = Capture()
            run(listOf(analyzer, "--garbage"), capture)
            val sent = capture.allBytes.decodeToString()
            assertTrue(sent.isNotEmpty(), "$analyzer sent nothing at all")
            assertTrue(SimMllp.SB !in sent, "$analyzer garbage contained an MLLP start")
            assertTrue(!sent.contains("$$$"), "$analyzer garbage contained a Mispa frame start")
        }
    }

    @Test
    fun `duplicate sends the identical bytes twice on one link`() {
        val capture = Capture(goodAck)
        run(listOf("mindray", "--duplicate"), capture)
        assertEquals(2, capture.writes.size)
        assertTrue(capture.writes[0].contentEquals(capture.writes[1]), "a retransmit must be byte-identical")
    }

    @Test
    fun `slow-chunks dribbles the frame out in many small writes`() {
        val capture = Capture(goodAck)
        val (_, out) = run(listOf("mindray", "--slow-chunks", "0", "--image"), capture)
        assertTrue(capture.writes.size > 100, "only ${capture.writes.size} writes — that is not a dribble")
        assertTrue(capture.writes.dropLast(1).all { it.size == 64 }, "pieces should be a fixed small size")
        val whole = MindrayFrames.frame(
            SampleSpec(specimenId = "SIM-0001", image = true, timestamp = "20260101090000")).size
        assertEquals(whole, capture.allBytes.size, "reassembling the pieces must give the whole frame back")
        assertTrue(out.text.contains("pieces"), out.text)
    }

    @Test
    fun `a run of several samples opens a connection each time, as a TCP analyzer does`() {
        val capture = Capture(goodAck)
        run(listOf("mindray", "--id", "A,B,C"), capture)
        assertEquals(3, capture.opened)
        assertEquals(3, capture.closed)
    }

    @Test
    fun `no-specimen builds a frame with nothing keyed`() {
        val options = Cli.parse(listOf("mispa", "--no-specimen", "--id", "IGNORED"))
        val spec = Sender(options, RecordingPrinter()).specFor(0)
        assertEquals(null, spec.specimenId, "--no-specimen must win over --id")
    }

    @Test
    fun `dry-run prints the frame and opens no link at all`() {
        val out = RecordingPrinter()
        val code = runCli(listOf("mispa", "--dry-run", "--id", "ACC-S1-00042"), printerFor = { out })
        assertEquals(0, code)
        assertTrue(out.text.contains("$$$"), "the frame itself should be on screen")
        assertTrue(out.text.contains("dry run"), out.text)
    }

    @Test
    fun `the banner states the driver the lab must have selected`() {
        val capture = Capture(goodAck)
        val (_, out) = run(listOf("mindray"), capture)
        assertTrue(out.text.contains("mindray_hl7"),
            "an engineer who picked the wrong driver has to be able to see it:\n${out.text}")
    }

    @Test
    fun `a long base64 blob is abbreviated in the transcript rather than scrolled past`() {
        val frame = MindrayFrames.frame(SampleSpec(specimenId = "X", image = true, timestamp = "20260101090000"))
        val rendered = Sender.render(frame)
        assertTrue(rendered.contains("more base64 chars"), "the bitmap was dumped verbatim")
        assertTrue(rendered.length < frame.size / 2, "abbreviation did not actually shorten anything")
    }
}
