package com.bnm.analyzersim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One accession per sample.
 *
 * A real analyzer carries its own sequence: five tubes go down the wire as five
 * accessions. The simulator used to send `--count 5` as the SAME id five times,
 * which BNM Lab files onto one order with each result overwriting the last — so
 * the run that is supposed to prove "five results, none lost" could not, and the
 * engineer signed it off as a pass because the transcript showed five sends.
 */
class SampleSequenceTest {

    private fun idsOf(vararg args: String): List<String> {
        val o = Cli.parse(args.toList())
        val sender = Sender(o, RecordingPrinter())
        return (0 until o.samples).map { sender.specFor(it).specimenId!! }
    }

    @Test
    fun `count with no --id advances the default sequence, as an analyzer's does`() {
        assertEquals(
            listOf("BNMTEST-0001", "BNMTEST-0002", "BNMTEST-0003", "BNMTEST-0004", "BNMTEST-0005"),
            idsOf("mindray", "--count", "5"),
        )
    }

    @Test
    fun `one sample with no --id is still the plain default id`() {
        assertEquals(listOf("BNMTEST-0001"), idsOf("mindray"))
    }

    /** The documented behaviour, and the reason the rule is per-front-end and
     *  not guessed inside the core: an id the engineer typed is theirs. */
    @Test
    fun `an id given by hand is pinned and repeats`() {
        assertEquals(List(3) { "ACC-S1-00042" }, idsOf("mindray", "--id", "ACC-S1-00042", "--count", "3"))
    }

    @Test
    fun `a list or a pattern still cycles rather than growing new ids`() {
        assertEquals(listOf("A", "B", "A", "B", "A"), idsOf("mindray", "--id", "A,B", "--count", "5"))
        assertEquals(
            listOf("L1-0008", "L1-0009", "L1-0010"),
            idsOf("mindray", "--id", "L1-{0008..0010}"),
        )
    }

    @Test
    fun `a burst gives every connection its own accession`() {
        // n identical accessions would prove nothing about whether a connection
        // was dropped: the app files them onto one order and the last wins.
        val ids = idsOf("mindray", "--burst", "4")
        assertEquals(4, ids.size)
        assertEquals(ids.size, ids.distinct().size, "a burst reused an accession: $ids")
    }

    @Test
    fun `the banner names the ids the run will really send, not the one it was given`() {
        val out = RecordingPrinter()
        Sender(Cli.parse(listOf("mindray", "--count", "5", "--dry-run")), out).run()
        assertTrue(out.text.contains("specimen id(s): BNMTEST-0001 … BNMTEST-0005 (5)"),
            "the banner must show the whole sequence:\n${out.text}")
    }

    /**
     * A pinned id repeated across a run is not a fault the engineer asked for
     * and is invisible in the transcript — every sample looks like it went out.
     * Only the app knows the fifth result overwrote the first.
     */
    @Test
    fun `a pinned id repeated across a run is called out before the run starts`() {
        val out = RecordingPrinter()
        Sender(Cli.parse(listOf("mindray", "--id", "ACC-S1-00042", "--count", "3", "--dry-run")), out).run()
        assertTrue(out.text.contains("All 3 samples carry the SAME specimen id"), out.text)
        assertTrue(out.text.contains("overwrites the last"), out.text)
    }

    @Test
    fun `a run that really does advance carries no such warning`() {
        val out = RecordingPrinter()
        Sender(Cli.parse(listOf("mindray", "--count", "3", "--dry-run")), out).run()
        assertTrue(!out.text.contains("SAME specimen id"), out.text)
    }

    @Test
    fun `nothing keyed at all is not reported as a repeated accession`() {
        val out = RecordingPrinter()
        Sender(Cli.parse(listOf("mindray", "--no-specimen", "--count", "3", "--dry-run")), out).run()
        assertTrue(!out.text.contains("SAME specimen id"), out.text)
    }

    @Test
    fun `the id width survives, because an accession that lost a leading zero matches nothing`() {
        assertEquals("SIM-0002", nextSpecimenId("SIM-0001"))
        assertEquals("ACC-S1-00043", nextSpecimenId("ACC-S1-00042"))
        assertEquals("L1-0100", nextSpecimenId("L1-0099"))
        // Overflowing the width has to grow it; 0099 -> 0100 does not.
        assertEquals("100", nextSpecimenId("99"))
        // No trailing number at all: a counter, rather than repeating forever.
        assertEquals("TUBE-2", nextSpecimenId("TUBE"))
    }
}
