package com.bnm.analyzersim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Mispa frame's SHAPE. Whether the app's own parser agrees is asserted in
 * composeApp's AnalyzerSimFidelityTest — this file pins the wire format so a
 * change here shows up as a local failure first.
 */
class MispaFramesTest {

    private fun spec(
        id: String? = "ACC-S1-00042",
        histograms: Boolean = true,
        unknownCode: Boolean = false,
        profile: Profile = Profile.NORMAL,
        seed: Long = 5L,
    ) = SampleSpec(
        specimenId = id, patientId = "P-7", profile = profile, seed = seed,
        histograms = histograms, unknownCode = unknownCode, timestamp = "20260101090000",
    )

    /** Body between the markers, split into the vendor's '#' sections. */
    private fun sections(frame: String): List<String> {
        assertTrue(frame.startsWith("$$$"), "frame must start with three dollars")
        assertTrue(frame.endsWith("###"), "frame must end with three hashes")
        return frame.removePrefix("$$$").removeSuffix("###").split('#')
    }

    @Test
    fun `a full frame has seven sections in the vendor's order`() {
        val s = sections(MispaFrames.build(spec()))
        assertEquals(7, s.size, "head, 3 histograms, discriminators, disease flags, parameter flags")
    }

    @Test
    fun `the header carries date, sequence, specimen, patient and exactly twenty parameters`() {
        val head = sections(MispaFrames.build(spec()))[0].split('$')
        assertEquals(24, head.size)
        assertEquals("20260101090000", head[0])
        assertEquals("1", head[1])
        assertEquals("ACC-S1-00042", head[2])
        assertEquals("P-7", head[3])
        assertEquals(MispaFrames.MISPA_PARAM_ORDER.size, head.size - 4)
    }

    @Test
    fun `the header values are the parameters map, in PARAM_ORDER`() {
        val spec = spec()
        val head = sections(MispaFrames.build(spec))[0].split('$').drop(4)
        val expected = MispaFrames.parameters(spec)
        MispaFrames.MISPA_PARAM_ORDER.forEachIndexed { i, name ->
            assertEquals(expected.getValue(name), head[i], "field $i ($name)")
        }
    }

    @Test
    fun `each histogram is 128 dollar-separated channels inside a byte`() {
        val s = sections(MispaFrames.build(spec()))
        for ((index, kind) in listOf(1 to "WBC", 2 to "RBC", 3 to "PLT")) {
            val points = s[index].split('$').map { it.toInt() }
            assertEquals(Histograms.CHANNELS, points.size, "$kind channel count")
            assertTrue(points.all { it in 0..Histograms.MAX_COUNT }, "$kind channel out of 0..255")
            assertTrue(points.max() > 100, "$kind is a flat line, not a curve")
        }
    }

    @Test
    fun `discriminators ride in their own at-separated section`() {
        val lines = sections(MispaFrames.build(spec()))[4].split('@').map { it.toInt() }
        assertEquals(8, lines.size, "4 WBC lines + 2 RBC + 2 PLT")
        assertTrue(lines.all { it in 0 until Histograms.CHANNELS }, "a discriminator fell off the x axis: $lines")
    }

    @Test
    fun `no specimen id is sent as the literal zero the parser reads as 'not keyed'`() {
        val head = sections(MispaFrames.build(spec(id = null)))[0].split('$')
        assertEquals(MispaFrames.NO_SPECIMEN, head[2])
    }

    @Test
    fun `no-histograms still emits the section markers, carrying nothing`() {
        val s = sections(MispaFrames.build(spec(histograms = false)))
        assertEquals(7, s.size, "the frame keeps its shape")
        for (i in 1..4) {
            assertTrue(s[i].isBlank(), "section $i should carry nothing, got '${s[i]}'")
            // Blank, not EMPTY: two empty sections in a row would write "###"
            // and the app's extractor would end the frame there. See below.
            assertTrue(s[i].isNotEmpty(), "section $i is empty — the frame can self-truncate")
        }
    }

    /**
     * The frame must contain exactly ONE end-of-frame marker: its own.
     *
     * The app never parses a whole string off the wire — it runs
     * `MispaCountX.extractFrameText` over an accumulating buffer and stops at
     * the FIRST `###`. A frame carrying a second one arrives cut short and its
     * tail merges with the next sample. `--no-histograms` on a profile that
     * raises no disease flags used to emit six hashes in a row and do exactly
     * that, so this sweeps every combination rather than the one that broke.
     */
    @Test
    fun `a frame never carries a second end-of-frame marker`() {
        for (profile in Profile.entries) {
            for (histograms in listOf(true, false)) {
                for (unknownCode in listOf(true, false)) {
                    for (id in listOf("ACC-S1-00042", null)) {
                        val frame = MispaFrames.build(
                            spec(id = id, histograms = histograms, unknownCode = unknownCode, profile = profile))
                        val where = "$profile histograms=$histograms unknownCode=$unknownCode id=$id"
                        assertEquals(frame.length - 3, frame.indexOf("###", 3),
                            "$where emitted a frame the app would cut short:\n$frame")
                    }
                }
            }
        }
    }

    /**
     * The blanking must not move the sections: the parser reads discriminators
     * at index 4 and disease flags at index 5 BY POSITION, so a section dropped
     * rather than blanked would silently file the disease flags as a histogram.
     */
    @Test
    fun `the disease flags stay in section five even with no histograms`() {
        val spec = spec(histograms = false, profile = Profile.ANAEMIA)
        val s = sections(MispaFrames.build(spec))
        assertEquals(7, s.size)
        assertEquals(MispaFrames.diseaseFlags(spec), s[5].split('?').filter { it.isNotEmpty() })
    }

    @Test
    fun `unknown-code appends a twenty-first header field the parser must ignore`() {
        val plain = sections(MispaFrames.build(spec()))[0].split('$')
        val extra = sections(MispaFrames.build(spec(unknownCode = true)))[0].split('$')
        assertEquals(plain.size + 1, extra.size)
        assertEquals("99.9", extra.last())
        assertEquals(plain, extra.dropLast(1), "the 20 known fields must be untouched")
    }

    @Test
    fun `the disease flags match the picture and are question-mark separated`() {
        val anaemic = sections(MispaFrames.build(spec(profile = Profile.ANAEMIA)))[5].split('?')
        assertTrue("Anemia" in anaemic, "anaemia profile raised $anaemic")
        assertTrue("Microcytosis" in anaemic)
        val low = sections(MispaFrames.build(spec(profile = Profile.THROMBOCYTOPENIA)))[5].split('?')
        assertTrue("Thrombocytopenia" in low, "thrombocytopenia profile raised $low")
    }

    @Test
    fun `the same seed emits the same bytes, a different seed does not`() {
        assertEquals(MispaFrames.build(spec(seed = 11L)), MispaFrames.build(spec(seed = 11L)))
        assertTrue(MispaFrames.build(spec(seed = 11L)) != MispaFrames.build(spec(seed = 12L)))
    }

    @Test
    fun `the parameter order is the one the app's driver publishes`() {
        // Kept as a literal on purpose: if someone reorders PARAM_ORDER in the
        // app, the cross-check test fails loudly rather than both sides moving
        // together and the frame silently meaning something else.
        assertEquals(
            listOf("WBC", "RBC", "PLT", "HGB", "HCT", "MCV", "MCH", "MCHC",
                "RDW-SD", "RDW-CV", "MPV", "LYMP%", "MID%", "GRAN%",
                "LYMP#", "MID#", "GRAN#", "PCT", "PDW", "LPCR"),
            MispaFrames.MISPA_PARAM_ORDER,
        )
    }
}
