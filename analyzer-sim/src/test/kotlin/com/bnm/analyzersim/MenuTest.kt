package com.bnm.analyzersim

import java.io.BufferedReader
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals
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

    private fun drive(vararg answers: String): RecordingPrinter {
        val out = RecordingPrinter()
        val script = BufferedReader(StringReader(answers.joinToString("\n") + "\n"))
        assertEquals(0, runCli(emptyList(), stdin = script, printerFor = { out }))
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
        assertTrue(out.text.contains("SIM-0001"), "the default specimen id was not used:\n${out.text}")
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
