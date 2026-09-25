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

    /** Whatever `--id` defaults to, so a change there does not silently rewrite these fixtures. */
    private val defaultId = Options(analyzer = Analyzer.MINDRAY).ids.single()

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
        val whole = MindrayFrames.frame(SampleSpec(specimenId = defaultId, timestamp = "20260101090000")).size
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
            SampleSpec(specimenId = defaultId, image = true, timestamp = "20260101090000")).size
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

    /**
     * `--qc` has nothing to set in the Mispa format. The transcript used to
     * print "FAULTS: qc" and "· QC" over an ordinary patient frame, so an
     * engineer ticked QC off the commissioning list having tested nothing —
     * or filed a bug against the app for filing a patient result.
     */
    @Test
    fun `qc on the Mispa says it changes nothing, and the transcript stops claiming otherwise`() {
        val capture = Capture()
        val (code, out) = run(listOf("mispa", "--qc", "--id", "ACC-S1-00042"), capture)
        assertEquals(0, code)
        assertTrue(out.text.contains("carries no processing-id field"), out.text)
        assertTrue(out.text.contains("WILL file it as a patient result"), out.text)
        assertTrue(!out.text.contains("· QC"), "the sample line still claims a QC run:\n${out.text}")
        assertTrue(!out.text.contains("FAULTS: qc"), "the banner still claims a QC run:\n${out.text}")
        // …and it really is byte-identical to the patient frame, which is why.
        val plain = Capture()
        run(listOf("mispa", "--id", "ACC-S1-00042"), plain)
        assertTrue(capture.allBytes.contentEquals(plain.allBytes))
    }

    /**
     * `--bad-units` spoils the HGB unit in [MindrayFrames] and is read nowhere
     * in [MispaFrames] — that format has no unit field. The banner used to print
     * "FAULTS: bad-units" over a byte-for-byte clean frame, so an engineer saw
     * the fault named, saw no unit-mismatch row in BNM Lab, and ticked unit
     * handling off the commissioning list having tested nothing.
     */
    @Test
    fun `bad units on the Mispa says it changes nothing, and the banner stops claiming it`() {
        val capture = Capture()
        val (code, out) = run(listOf("mispa", "--bad-units", "--id", "ACC-S1-00042"), capture)
        assertEquals(0, code)
        assertTrue(out.text.contains("carries no unit field at all"), out.text)
        // The caveat names the flag on purpose; the FAULTS line must not, because
        // that line is read as "this run exercised these".
        assertTrue(out.text.lines().none { it.startsWith("  FAULTS:") },
            "the banner still names the fault:\n${out.text}")
        val plain = Capture()
        run(listOf("mispa", "--id", "ACC-S1-00042"), plain)
        assertTrue(capture.allBytes.contentEquals(plain.allBytes),
            "if the frame differs at all, the caveat is the thing that is wrong")
    }

    @Test
    fun `bad units on the Mindray really does spoil the unit, and is still announced`() {
        val capture = Capture(goodAck)
        val (_, out) = run(listOf("mindray", "--bad-units", "--id", "ACC-S1-00042"), capture)
        assertTrue(out.text.contains("FAULTS: bad-units"), out.text)
        assertTrue(capture.allBytes.decodeToString().contains("bogus/L"), "the HGB unit was not spoiled")
    }

    @Test
    fun `qc on the Mindray is a real QC run and is still announced as one`() {
        val capture = Capture(goodAck)
        val (_, out) = run(listOf("mindray", "--qc", "--id", "ACC-S1-00042"), capture)
        assertTrue(out.text.contains("· QC"), out.text)
        assertTrue(out.text.contains("FAULTS: qc"), out.text)
        assertTrue(capture.allBytes.decodeToString().contains("|Q|2.3.1"), "MSH-11 was not Q")
    }

    /** Pointing the tool at somebody else's machine has to be impossible to miss. */
    @Test
    fun `a live-lab run opens with a banner naming the machine and the accessions`() {
        val capture = Capture(goodAck)
        val (_, out) = run(
            listOf("mindray", "--host", "192.168.1.50", "--live-lab", "--id", "ACC-S1-00042"), capture)
        assertTrue(out.text.contains("SENDING TO ANOTHER MACHINE: 192.168.1.50"), out.text)
        assertTrue(out.text.contains("These results are invented"), out.text)
        assertTrue(out.text.contains("ACC-S1-00042"), "the banner must name what it will overwrite")
    }

    @Test
    fun `a run against this machine keeps its banner short`() {
        val capture = Capture(goodAck)
        val (_, out) = run(listOf("mindray"), capture)
        assertTrue(!out.text.contains("SENDING TO ANOTHER MACHINE"), out.text)
    }

    @Test
    fun `a long base64 blob is abbreviated in the transcript rather than scrolled past`() {
        val frame = MindrayFrames.frame(SampleSpec(specimenId = "X", image = true, timestamp = "20260101090000"))
        val rendered = Sender.render(frame)
        assertTrue(rendered.contains("more base64 chars"), "the bitmap was dumped verbatim")
        assertTrue(rendered.length < frame.size / 2, "abbreviation did not actually shorten anything")
    }
}
