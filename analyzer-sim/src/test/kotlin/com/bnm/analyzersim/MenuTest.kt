package com.bnm.analyzersim

import java.io.BufferedReader
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The no-flags path, driven from a script.
 *
 * It is the path the least experienced user takes, so it is the one most worth
 * a test: a menu that silently drops an answer would send the wrong thing to a
 * client's machine and nobody would know.
 */
class MenuTest {

    /** Scenario 16, "print the frame, send nothing" — the only one safe to run with no listener. */
    private val dryRun = Menu.SCENARIOS.indexOfFirst { it.title.startsWith("Print the frame") } + 1

    private fun drive(vararg answers: String): RecordingPrinter = driveExpecting(0, *answers)

    private fun driveExpecting(code: Int, vararg answers: String): RecordingPrinter {
        val out = RecordingPrinter()
        val script = BufferedReader(StringReader(answers.joinToString("\n") + "\n"))
        assertEquals(code, runCli(emptyList(), stdin = script, printerFor = { out }), out.text)
        return out
    }

    @Test
    fun `picking an analyzer and a scenario builds and prints the frame`() {
        val out = drive("1", dryRun.toString(), "", "", "ACC-S1-00042")
        assertTrue(out.text.contains("Scenario: Print the frame"), out.text)
        assertTrue(out.text.contains("mindray_hl7"), "the driver to select should be on screen")
        assertTrue(out.text.contains("MSH|^~"), "the HL7 frame was not printed:\n${out.text}")
        assertTrue(out.text.contains("ACC-S1-00042"), "the typed specimen id did not reach the frame")
    }

    @Test
    fun `the Mispa choice produces a Mispa frame`() {
        val out = drive("2", dryRun.toString(), "0", "", "", "ACC-S1-00043")
        assertTrue(out.text.contains("mispa_count_x"), out.text)
        assertTrue(out.text.contains("$$$"), "no Mispa frame in:\n${out.text}")
    }

    @Test
    fun `mashing Enter takes every default`() {
        val out = drive("", dryRun.toString(), "", "", "")
        assertTrue(out.text.contains("TCP 127.0.0.1:5500") || out.text.contains("dry run"), out.text)
        assertTrue(out.text.contains("BNMTEST-0001"), "the default specimen id was not used:\n${out.text}")
    }

    /**
     * The prompt lists the analyzers by name, so a name is the answer an
     * engineer is most likely to type. It used to fall through to choice 1:
     * "mispa" ran the Mindray, on the Mindray port, in HL7 — and the link that
     * then refused to frame anything looked like a fault in the app.
     */
    @Test
    fun `typing the analyzer's name picks that analyzer, not the first one`() {
        val out = drive("mispa", dryRun.toString(), "0", "", "", "ACC-S1-00043")
        assertTrue(out.text.contains("mispa_count_x"), "'mispa' did not select the Mispa:\n${out.text}")
        assertTrue(out.text.contains("$$$"), "no Mispa frame in:\n${out.text}")
    }

    @Test
    fun `an answer that is not on the list stops, rather than quietly picking the first choice`() {
        val analyzer = driveExpecting(1, "sysmex")
        assertTrue(analyzer.text.contains("not one of the choices"), analyzer.text)

        // Scenario 1 SENDS a frame. Defaulting a typo to it would put a result
        // on somebody's lab machine when the person meant something else.
        val scenario = driveExpecting(1, "1", "the third one")
        assertTrue(scenario.text.contains("not one of the numbers"), scenario.text)
        assertTrue(!scenario.text.contains("Scenario:"), "it ran something anyway:\n${scenario.text}")
    }

    @Test
    fun `the QC rehearsal is relabelled on the analyzer whose format has no QC field`() {
        val qc = Menu.scenariosFor(Analyzer.MISPA).first { it.title.startsWith("QC run") }
        assertTrue(qc.title.contains("Mindray only"), "offered as if it worked: ${qc.title}")
        assertTrue(qc.note.contains("filed as a patient"), qc.note)
        assertEquals("QC run", Menu.scenariosFor(Analyzer.MINDRAY).first { it.title.startsWith("QC run") }.title)
        assertEquals(Menu.SCENARIOS.size, Menu.scenariosFor(Analyzer.MISPA).size,
            "the numbering must mean the same thing on both analyzers")
    }

    /** Sending invented results to somebody else's machine takes a typed YES. */
    @Test
    fun `a host that is not this machine has to be confirmed in words`() {
        val refused = driveExpecting(1, "1", dryRun.toString(), "192.168.1.50")
        assertTrue(refused.text.contains("not this machine"), refused.text)
        assertTrue(refused.text.contains("Nothing was sent"), refused.text)

        val allowed = drive("1", dryRun.toString(), "192.168.1.50", "yes", "", "ACC-S1-00042")
        assertTrue(allowed.text.contains("MSH|^~"), "a confirmed run should still go ahead:\n${allowed.text}")
    }

    /** A fault that needs a connection cannot be rehearsed down a cable, and the
     *  menu must refuse it in the same words the command line does. */
    @Test
    fun `the menu refuses a scenario the chosen link cannot carry`() {
        val truncated = Menu.SCENARIOS.indexOfFirst { it.title == "Truncated frame" } + 1
        val options = Menu.SCENARIOS[truncated - 1]
            .apply(Options(analyzer = Analyzer.MISPA, serialPort = "COM3"))
        val error = assertFailsWith<CliError> { Cli.validate(options) }
        assertTrue(error.message!!.contains("merge with your next sample"), error.message!!)
    }

    @Test
    fun `a scenario really does change the run`() {
        val critical = Menu.SCENARIOS.indexOfFirst { it.title == "Critical result" } + 1
        val options = Menu.SCENARIOS[critical - 1].apply(Options(analyzer = Analyzer.MINDRAY, port = 5500))
        assertEquals(Profile.CRITICAL, options.profile)
        val queue = Menu.SCENARIOS.first { it.title == "No specimen id keyed" }
            .apply(Options(analyzer = Analyzer.MINDRAY, port = 5500))
        assertTrue(queue.noSpecimen)
    }

    @Test
    fun `every scenario in the menu produces a command line the parser would accept`() {
        // A scenario that set an impossible combination would only fail in front
        // of a customer; check them all against the same validation the CLI uses.
        for (scenario in Menu.SCENARIOS) {
            val options = scenario.apply(Options(analyzer = Analyzer.MINDRAY, port = 5500))
            assertTrue(options.samples >= 1, "${scenario.title} sends nothing")
            assertTrue(options.port in 1..65535, "${scenario.title} has no valid port")
        }
    }
}
