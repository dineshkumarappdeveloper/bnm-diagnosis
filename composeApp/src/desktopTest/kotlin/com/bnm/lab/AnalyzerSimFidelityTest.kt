package com.bnm.lab

import com.bnm.analyzersim.AnalyzerTransport
import com.bnm.analyzersim.Cli
import com.bnm.analyzersim.Histograms
import com.bnm.analyzersim.MindrayFrames
import com.bnm.analyzersim.MispaFrames
import com.bnm.analyzersim.Profile
import com.bnm.analyzersim.RecordingPrinter
import com.bnm.analyzersim.SampleSpec
import com.bnm.analyzersim.Sender
import com.bnm.analyzersim.TransportFactory
import com.bnm.lab.instruments.AnalyzerUnits
import com.bnm.lab.instruments.MindrayBc5x
import com.bnm.lab.instruments.MispaCountX
import com.bnm.lab.instruments.Mllp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The contract between the BNM Analyzer Simulator and the drivers it imitates.
 *
 * A simulator nobody checks is worse than none: it drifts, an engineer
 * commissions a lab against a frame no analyzer would send, and the fault only
 * shows up at the client's bench. So every profile, on both analyzers, is
 * built by the tool and read back by the REAL parser — and what the parser
 * returns has to be what the simulator says it sent. Not "parses": equals.
 *
 * The fault scenarios are pinned the same way. `--truncated` claiming to
 * truncate while actually sending a whole frame would be the most expensive
 * kind of wrong.
 */
class AnalyzerSimFidelityTest {

    private val profiles = Profile.entries
    private val stamp = "20260101090000"

    private fun spec(
        profile: Profile,
        id: String? = "ACC-S1-00042",
        seed: Long = 17L,
        qc: Boolean = false,
        histograms: Boolean = true,
        cbcOnly: Boolean = false,
        image: Boolean = false,
        unknownCode: Boolean = false,
        badUnits: Boolean = false,
    ) = SampleSpec(
        specimenId = id, patientId = "PAT-9001", patientName = "Asha Menon", sex = "F", ageYears = 34,
        profile = profile, seed = seed, sequence = 7, qc = qc, histograms = histograms,
        cbcOnly = cbcOnly, image = image,
        unknownCode = unknownCode, badUnits = badUnits, timestamp = stamp,
    )

    // ── Mispa Count X ──

    @Test
    fun `MispaCountX reads back every value, id and curve the simulator meant to send`() {
        for (profile in profiles) {
            val spec = spec(profile)
            val text = MispaFrames.build(spec)
            val frame = assertNotNull(MispaCountX.parse(text), "$profile did not parse")

            assertEquals(MispaFrames.parameters(spec), frame.params, "$profile parameters")
            assertEquals(spec.specimenId, frame.specimenId, "$profile specimen id")
            assertEquals(spec.patientId, frame.patientId, "$profile patient id")
            assertEquals(stamp, frame.date, "$profile date")
            assertEquals("7", frame.sequenceId, "$profile sequence")
            assertEquals(MispaFrames.diseaseFlags(spec), frame.diseaseFlags, "$profile disease flags")

            assertEquals(setOf("wbc", "rbc", "plt"), frame.histograms.keys, "$profile histogram kinds")
            assertEquals(
                Histograms.wbc(spec.cbc, spec.seed).points.map { it.toDouble() },
                frame.histograms.getValue("wbc"), "$profile WBC curve")
            assertEquals(
                Histograms.rbc(spec.cbc, spec.seed).points.map { it.toDouble() },
                frame.histograms.getValue("rbc"), "$profile RBC curve")
            assertEquals(
                Histograms.plt(spec.cbc, spec.seed).points.map { it.toDouble() },
                frame.histograms.getValue("plt"), "$profile PLT curve")
        }
    }

    @Test
    fun `a Mispa frame extracts cleanly from a byte stream, leaving nothing behind`() {
        val one = MispaFrames.build(spec(Profile.NORMAL))
        val two = MispaFrames.build(spec(Profile.ANAEMIA, id = "ACC-S1-00043"))
        var buffer = "noise before the frame" + one + two
        val (first, rest) = MispaCountX.extractFrameText(buffer)
        assertEquals(one, first, "the leading junk was not discarded")
        buffer = rest
        val (second, tail) = MispaCountX.extractFrameText(buffer)
        assertEquals(two, second, "back-to-back frames must drain one at a time")
        assertEquals("", tail)
    }

    /**
     * Through the STREAMING extractor, not `parse` on a whole string.
     *
     * The engine never has the frame as a string: `InstrumentEngine` runs
     * `extractFrameText` over an accumulating buffer and stops at the FIRST
     * `###`. A no-histogram frame used to write four empty sections in a row —
     * six hashes — so the extractor cut it at the first three: the disease
     * flags never reached the driver and the tail stayed in the buffer to
     * merge with the next sample. Calling `parse` on the whole string, as this
     * test used to, is exactly the check that could not see it.
     */
    @Test
    fun `a Mispa frame with no histograms survives the streaming extractor, just without curves`() {
        for (profile in profiles) {
            val spec = spec(profile, histograms = false)
            val text = MispaFrames.build(spec)
            val (extracted, rest) = MispaCountX.extractFrameText(text)
            assertEquals(text, extracted, "$profile: the app would read a shorter frame than was sent")
            assertEquals("", rest, "$profile left bytes behind to poison the next sample")

            val frame = assertNotNull(MispaCountX.parse(assertNotNull(extracted)), "$profile did not parse")
            assertEquals(MispaFrames.parameters(spec), frame.params, "$profile parameters")
            assertEquals(MispaFrames.diseaseFlags(spec), frame.diseaseFlags, "$profile disease flags")
            assertTrue(frame.histograms.isEmpty(), "$profile: curves appeared from nowhere")
            assertNull(frame.discriminators, "$profile: discriminators with no curves to read them off")
        }
    }

    /**
     * Two no-histogram frames back to back on one link. A frame that ends early
     * does not merely lose itself — its tail is still in the buffer when the
     * next sample arrives, and the two are read as one.
     */
    @Test
    fun `two no-histogram frames on one stream stay two samples`() {
        val one = MispaFrames.build(spec(Profile.NORMAL, histograms = false))
        val two = MispaFrames.build(spec(Profile.ANAEMIA, id = "ACC-S1-00043", histograms = false))
        val (first, rest) = MispaCountX.extractFrameText(one + two)
        assertEquals(one, first)
        val (second, tail) = MispaCountX.extractFrameText(rest)
        assertEquals(two, second, "the second sample was swallowed by the first")
        assertEquals("", tail)
        assertEquals("ACC-S1-00043", assertNotNull(MispaCountX.parse(assertNotNull(second))).specimenId)
    }

    // ── Mindray BC-5x ──

    @Test
    fun `MindrayBc5x reads back every value, unit, id and curve the simulator meant to send`() {
        for (profile in profiles) {
            val spec = spec(profile, image = true)
            val frame = assertNotNull(MindrayBc5x.parse(MindrayFrames.build(spec)), "$profile did not parse")

            for (p in MindrayFrames.parameters(spec)) {
                assertEquals(p.value, frame.params[p.name], "$profile ${p.name} value")
                assertEquals(p.unit, frame.units[p.name], "$profile ${p.name} unit")
            }
            assertEquals(spec.specimenId, frame.specimenId, "$profile specimen id (OBR-3)")
            assertEquals(spec.patientId, frame.patientId, "$profile patient id (PID-3.1)")
            assertEquals("Asha Menon", frame.patientName, "$profile patient name (PID-5)")
            assertEquals(stamp, frame.date, "$profile date (OBR-7)")
            assertEquals("SIM${stamp}007", frame.sequenceId, "$profile control id (MSH-10)")
            assertTrue(!frame.isQc, "$profile was read as a QC run")

            assertEquals(setOf("wbc", "rbc", "plt"), frame.histograms.keys, "$profile histogram kinds")
            for ((kind, curve) in listOf(
                "wbc" to Histograms.wbc(spec.cbc, spec.seed),
                "rbc" to Histograms.rbc(spec.cbc, spec.seed),
                "plt" to Histograms.plt(spec.cbc, spec.seed),
            )) {
                assertEquals(curve.points.map { it.toDouble() }, frame.histograms[kind],
                    "$profile $kind curve did not survive the base64 round trip")
            }
            assertTrue("diff" in frame.images, "$profile lost the scattergram")
            assertTrue(frame.meta["${"wbc"}_hist_error"] == null, "$profile: ${frame.meta}")
        }
    }

    /**
     * The histogram layout is the one piece of this the app can accept and
     * still get wrong — a 256-channel UInt16 payload decodes to a different
     * curve, silently. Pin the channel count the decoder hands back.
     */
    @Test
    fun `histograms decode to exactly 128 channels, not 256 misread bytes`() {
        val spec = spec(Profile.NORMAL)
        val frame = assertNotNull(MindrayBc5x.parse(MindrayFrames.build(spec)))
        for ((kind, points) in frame.histograms) {
            assertEquals(Histograms.CHANNELS, points.size, "$kind decoded to ${points.size} channels")
        }
    }

    @Test
    fun `the run settings, age and alerts reach the driver's meta map`() {
        val spec = spec(Profile.ANAEMIA)
        val frame = assertNotNull(MindrayBc5x.parse(MindrayFrames.build(spec)))
        assertEquals("Open Vial", frame.meta["take_mode"])
        assertEquals("Whole Blood", frame.meta["blood_mode"])
        assertEquals("CBC+5DIFF", frame.meta["test_mode"])
        assertEquals("General", frame.meta["ref_group"])
        assertEquals("34 Year", frame.meta["age"])
        assertEquals(MindrayFrames.alerts(spec).joinToString(";") { it.second }, frame.meta["alerts"])
        val lines = MindrayFrames.let { _ ->
            Histograms.wbc(spec.cbc, spec.seed).lines.joinToString(",")
        }
        assertEquals(lines, frame.meta["wbc_lines"], "the WBC discriminators did not come back")
    }

    /**
     * `--no-histograms` drops the curves; `--cbc-only` is the RUN mode. The two
     * used to be the same switch, which put "Test Mode: CBC" on a message still
     * carrying the full five-part differential — a frame no BC-5130 emits, and
     * a lab rehearsing a CBC-only run never saw what the app does when the
     * differential parameters are genuinely absent.
     */
    @Test
    fun `a CBC-only run reaches the driver with no differential at all`() {
        val full = spec(Profile.NORMAL)
        val cbc = spec(Profile.NORMAL, cbcOnly = true)

        val fullFrame = assertNotNull(MindrayBc5x.parse(MindrayFrames.build(full)))
        assertEquals("CBC+5DIFF", fullFrame.meta["test_mode"], "dropping the curves is not a run mode")
        assertNotNull(fullFrame.params["NEU%"], "a 5-part run must carry its differential")

        val cbcFrame = assertNotNull(MindrayBc5x.parse(MindrayFrames.build(cbc)))
        assertEquals("CBC", cbcFrame.meta["test_mode"])
        for (name in listOf("NEU%", "NEU#", "LYM%", "LYM#", "MON%", "MON#", "EOS%", "EOS#", "BAS%", "BAS#")) {
            assertNull(cbcFrame.params[name], "a CBC-only run still reported $name")
        }
        for (name in listOf("WBC", "RBC", "HGB", "HCT", "PLT")) {
            assertNotNull(cbcFrame.params[name], "a CBC-only run lost $name")
        }
        // Still every value the simulator says it sent, for the rows it sent.
        for (p in MindrayFrames.parameters(cbc)) assertEquals(p.value, cbcFrame.params[p.name], p.name)
        assertEquals(setOf("wbc", "rbc", "plt"), cbcFrame.histograms.keys, "the curves are a separate question")
    }

    @Test
    fun `a QC run is marked as one, so the engine ignores it instead of filing a patient result`() {
        val frame = assertNotNull(MindrayBc5x.parse(MindrayFrames.build(spec(Profile.NORMAL, qc = true))))
        assertTrue(frame.isQc, "MSH-11 = Q was not read as QC")
    }

    @Test
    fun `the MLLP wrapper the simulator writes is the one the app unwraps`() {
        val bytes = MindrayFrames.frame(spec(Profile.NORMAL))
        val (message, rest) = Mllp.extract(bytes.decodeToString())
        assertEquals(MindrayFrames.build(spec(Profile.NORMAL)), message)
        assertEquals("", rest)
    }

    // ── fault scenarios: each must fail exactly the way it claims ──

    @Test
    fun `truncated produces no frame at all, for either driver`() {
        val mispa = onWire("mispa", "--truncated")
        assertNull(MispaCountX.extractFrameText(mispa).first, "a truncated Mispa frame framed anyway")

        val mindray = onWire("mindray", "--truncated")
        assertNull(Mllp.extract(mindray).first, "a truncated MLLP block framed anyway")
    }

    @Test
    fun `garbage parses to null, for either driver`() {
        assertNull(MispaCountX.parse(onWire("mispa", "--garbage")))
        assertNull(MindrayBc5x.parse(onWire("mindray", "--garbage")))
    }

    @Test
    fun `slow chunks reassemble into exactly the frame that was meant`() {
        val pieces = writes("mindray", "--slow-chunks", "0", "--image")
        assertTrue(pieces.size > 100, "not actually chunked: ${pieces.size} writes")
        val joined = pieces.fold(ByteArray(0)) { a, b -> a + b }.decodeToString()
        val (message, rest) = Mllp.extract(joined)
        assertNotNull(message, "the pieces did not add up to a frame")
        assertEquals("", rest)
        assertNotNull(MindrayBc5x.parse(message), "the reassembled frame did not parse")
    }

    @Test
    fun `an unknown Mindray code is kept under the analyzer's own label, never mapped to a real parameter`() {
        val spec = spec(Profile.NORMAL, unknownCode = true)
        val frame = assertNotNull(MindrayBc5x.parse(MindrayFrames.build(spec)),
            "a code the driver has never seen must not cost the lab the whole sample")
        assertNull(frame.params[MindrayFrames.UNKNOWN_CODE],
            "the raw code leaked in as a parameter key")
        val known = MindrayBc5x.PARAM_CODES.values.toSet()
        val landedOn = frame.params.entries.filter { it.value == "1.23" }.map { it.key }
        assertEquals(listOf(MindrayFrames.UNKNOWN_NAME), landedOn,
            "the unknown value should sit under its vendor label only, not on a known parameter")
        assertTrue(MindrayFrames.UNKNOWN_NAME !in known, "the fault picked a name the driver DOES know")
        // Every real parameter is still there — the surprise row costs nothing.
        for (p in MindrayFrames.parameters(spec)) assertEquals(p.value, frame.params[p.name], p.name)
    }

    @Test
    fun `an unknown Mispa field past the twentieth is dropped, and the twenty survive`() {
        val spec = spec(Profile.NORMAL, unknownCode = true)
        val frame = assertNotNull(MispaCountX.parse(MispaFrames.build(spec)))
        assertEquals(MispaFrames.parameters(spec), frame.params)
        assertEquals(MispaCountX.PARAM_ORDER.size, frame.params.size)
        assertTrue("99.9" !in frame.params.values, "the 21st field was read as a parameter")
    }

    @Test
    fun `bad-units sends a unit the app's converter genuinely cannot bridge`() {
        val frame = assertNotNull(MindrayBc5x.parse(MindrayFrames.build(spec(Profile.NORMAL, badUnits = true))))
        val sent = assertNotNull(frame.units["HGB"])
        assertTrue(AnalyzerUnits.needsConversion(sent, "g/dL"),
            "'$sent' is convertible after all — the fault proves nothing")
        // The good unit must still convert, or the test above would pass for the wrong reason.
        assertTrue(!AnalyzerUnits.needsConversion("g/L", "g/dL"))
    }

    @Test
    fun `no-specimen leaves the drivers with nothing to match on`() {
        assertNull(MispaCountX.parse(MispaFrames.build(spec(Profile.NORMAL, id = null)))!!.specimenId,
            "Mispa sends '0' and the driver must read it as 'not keyed'")
        assertNull(MindrayBc5x.parse(MindrayFrames.build(spec(Profile.NORMAL, id = null)))!!.specimenId,
            "an empty OBR-3 must read as no specimen id")
    }

    @Test
    fun `a duplicate is two whole frames on one stream, both readable`() {
        val bytes = writes("mindray", "--duplicate").fold(ByteArray(0)) { a, b -> a + b }
        val (first, rest) = Mllp.extract(bytes.decodeToString())
        val (second, tail) = Mllp.extract(rest)
        assertEquals(first, second, "a retransmit must be the identical message")
        assertEquals("", tail)
        assertNotNull(MindrayBc5x.parse(assertNotNull(first)))
    }

    // ── driving the real CLI, so these are the bytes an engineer sends ──

    /** Every byte the simulator writes for [args], concatenated. */
    private fun onWire(vararg args: String): String =
        writes(*args).fold(ByteArray(0)) { a, b -> a + b }.decodeToString()

    /** The individual writes the simulator makes for [args], through the real Sender. */
    private fun writes(vararg args: String): List<ByteArray> {
        val capture = Capture()
        val options = Cli.parse(args.toList() + listOf("--id", "ACC-S1-00042", "--ack-timeout", "0"))
        Sender(options, RecordingPrinter(verbose = false), clock = { stamp },
            transportOverride = capture.factory()).run()
        return capture.writes
    }

    private class Capture : AnalyzerTransport {
        val writes = mutableListOf<ByteArray>()
        override val describe = "capture"
        override fun write(bytes: ByteArray) { writes += bytes }
        override fun readReply(timeoutMs: Long): ByteArray = ByteArray(0)
        override fun close() {}
        fun factory() = object : TransportFactory {
            override val perSample = true
            override val describe = "capture"
            override fun open(): AnalyzerTransport = this@Capture
        }
    }
}
