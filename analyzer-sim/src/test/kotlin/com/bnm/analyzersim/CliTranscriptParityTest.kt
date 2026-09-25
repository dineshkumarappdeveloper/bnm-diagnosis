package com.bnm.analyzersim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The CLI's transcript, pinned word for word.
 *
 * Reporting moved from `println` inside [Sender] to [SendListener] events so a
 * window could show the same run. The transcript is a user interface — the
 * README walks an engineer through a commissioning and tells them which line to
 * look for — so the refactor had to change nothing a person reads. A golden
 * dry run is the only honest way to say that, and it is cheap: no socket, a
 * fixed clock, a fixed seed.
 *
 * If this test fails because you deliberately reworded the CLI, update the
 * golden AND the README step that quotes it.
 */
class CliTranscriptParityTest {

    private fun transcript(vararg args: String): String {
        val out = RecordingPrinter(verbose = true)
        val options = Cli.parse(args.toList())
        Sender(options, out, clock = { "20260101090000" }).run()
        return out.text
    }

    /**
     * The golden writes ¤ where the wire carries a dollar. The Mispa format is
     * nothing but dollars, and a Kotlin string cannot hold a literal dollar
     * before a letter — escaping twenty of them would leave a golden no
     * reviewer could read against the real frame, which is its whole job.
     */
    private fun golden(text: String): String = text.trimIndent().trim('\n').replace("¤", "$")

    @Test
    fun `a mispa dry run prints exactly what it always has`() {
        val text = transcript("mispa", "--dry-run", "--id", "ACC-S1-00042", "--seed", "7", "--no-histograms")
        assertEquals(
            golden(
                """
                BNM Analyzer Simulator — Agappe Mispa Count X — 3-part, ¤-delimited frames over RS-232 (or raw TCP on the bench), one-way
                  driver the lab must have selected: mispa_count_x
                  link: dry run, nothing is sent
                  samples: 1 · profile normal · seed 7
                  specimen id(s): ACC-S1-00042

                [1/1] ACC-S1-00042 · WBC 6.68 · RBC 4.82 · HGB 14.0 g/dL · PLT 256
                ¤¤¤20260101090000¤1¤ACC-S1-00042¤0¤6.68¤4.82¤256¤14.0¤43.9¤91.1¤29.0¤31.9¤42.7¤13.6¤9.7¤32.1¤9.2¤58.7¤2.14¤0.61¤3.92¤0.25¤13.1¤25.9######N¤N¤N¤N¤N¤N¤N¤L¤N¤N¤N¤N¤N¤N¤N¤N¤N¤N¤N¤N###
                  179 bytes out over dry run — nothing is sent
                Done — 1 sample(s) sent.
                """
            ),
            text,
        )
    }

    @Test
    fun `a mindray dry run keeps its banner, its sample line and its byte count`() {
        val text = transcript("mindray", "--dry-run", "--id", "ACC-S1-00042", "--seed", "7", "--no-histograms")
        val lines = text.lines()
        assertEquals("BNM Analyzer Simulator — ${Analyzer.MINDRAY.label}", lines[0])
        assertEquals("  driver the lab must have selected: mindray_hl7", lines[1])
        assertEquals("  link: dry run, nothing is sent", lines[2])
        assertEquals("  samples: 1 · profile normal · seed 7", lines[3])
        assertEquals("  specimen id(s): ACC-S1-00042", lines[4])
        assertEquals("", lines[5])
        assertEquals("[1/1] ACC-S1-00042 · WBC 6.68 · RBC 4.82 · HGB 14.0 g/dL · PLT 256", lines[6])
        assertTrue(lines[7].startsWith("\u2409MSH|^~\\&|BC-5130|Mindray"), "the frame itself: ${lines[7]}")
        assertEquals("Done — 1 sample(s) sent.", lines.last())
        assertTrue(text.contains("bytes out over dry run — nothing is sent"), text)
    }

    @Test
    fun `the fault banner, the dribble note and the failure summary are unchanged`() {
        val text = transcript(
            "mindray", "--dry-run", "--id", "A,B", "--count", "3", "--qc", "--no-specimen",
            "--truncated", "--slow-chunks", "0", "--no-histograms",
        )
        assertTrue(text.contains("  FAULTS: truncated, slow-chunks 0ms, no-specimen, qc"), text)
        assertTrue(text.contains("  specimen id(s): none keyed (--no-specimen)"), text)
        assertTrue(Regex("""\n {2}dribbled out in \d+ pieces of 64 bytes, 0ms apart\n""").containsMatchIn(text), text)
        assertTrue(text.contains("% and closing — the app should never see a complete frame"), text)
        assertTrue(text.contains("[3/3] "), text)
        assertEquals("Done — 3 sample(s) sent.", text.lines().last())
    }

    @Test
    fun `an unreachable host still ends the run, and says why in the same words`() {
        val out = RecordingPrinter(verbose = false)
        // Port 1 on localhost: nothing has ever listened there, so this refuses
        // immediately rather than waiting out a connect timeout.
        val code = Sender(Cli.parse(listOf("mindray", "--port", "1")), out).run()
        assertEquals(1, code)
        assertTrue(out.text.contains("! Connection refused. BNM Lab is not listening there"), out.text)
        assertTrue(!out.text.contains("Done —"), "an aborted run must not claim it finished:\n${out.text}")
    }
}
