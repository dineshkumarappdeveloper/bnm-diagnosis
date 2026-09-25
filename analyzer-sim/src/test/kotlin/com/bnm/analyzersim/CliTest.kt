package com.bnm.analyzersim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The command line, including the refusals — a bad flag must produce a sentence, never a stack trace. */
class CliTest {

    private fun parse(vararg args: String) = Cli.parse(args.toList())

    @Test
    fun `the defaults are the ones the app ships with`() {
        val mindray = parse("mindray")
        assertEquals("127.0.0.1", mindray.host)
        assertEquals(5500, mindray.port)
        assertEquals("mindray_hl7", mindray.analyzer.driverKey)
        assertEquals(listOf("BNMTEST-0001"), mindray.ids,
            "the default id must be unmistakably a test, never something an accession search could match")
        assertEquals(5501, parse("mispa").port)
        assertEquals("mispa_count_x", parse("mispa").analyzer.driverKey)
        assertEquals(115200, parse("mispa").baud, "8-N-1 at 115200 is what the vendor doc specifies")
    }

    @Test
    fun `an id can be one, a comma list, or a padded range`() {
        assertEquals(listOf("ACC-S1-00042"), Cli.expandIds("ACC-S1-00042"))
        assertEquals(listOf("A", "B", "C"), Cli.expandIds("A, B ,C"))
        assertEquals(
            listOf("ACC-S1-0001", "ACC-S1-0002", "ACC-S1-0003", "ACC-S1-0004", "ACC-S1-0005"),
            Cli.expandIds("ACC-S1-000{1..5}"),
        )
    }

    @Test
    fun `a range keeps its leading zeros — an accession that lost them matches nothing`() {
        assertEquals(listOf("L1-0008", "L1-0009", "L1-0010"), Cli.expandIds("L1-{0008..0010}"))
    }

    @Test
    fun `count defaults to one per id and ids cycle when count is larger`() {
        assertEquals(3, parse("mindray", "--id", "A,B,C").samples)
        val five = parse("mindray", "--id", "A,B", "--count", "5")
        assertEquals(5, five.samples)
        val sender = Sender(five, RecordingPrinter())
        assertEquals(listOf("A", "B", "A", "B", "A"), (0 until 5).map { sender.specFor(it).specimenId })
    }

    @Test
    fun `every sample in a run is its own patient`() {
        val sender = Sender(parse("mindray", "--count", "3", "--seed", "10"), RecordingPrinter())
        val seeds = (0 until 3).map { sender.specFor(it).seed }
        assertEquals(listOf(10L, 11L, 12L), seeds)
    }

    @Test
    fun `no-ack-wait is the same as a zero timeout`() {
        assertEquals(0L, parse("mindray", "--no-ack-wait").ackTimeoutMs)
        assertEquals(2_500L, parse("mindray", "--ack-timeout", "2.5").ackTimeoutMs)
    }

    @Test
    fun `faults arrive on the options where the sender looks for them`() {
        val o = parse("mispa", "--truncated", "--slow-chunks", "25", "--burst", "4", "--no-specimen", "--unknown-code")
        assertTrue(o.faults.truncated)
        assertEquals(25L, o.faults.slowChunksMs)
        assertEquals(4, o.faults.burst)
        assertTrue(o.noSpecimen)
        assertTrue(o.unknownCode)
    }

    @Test
    fun `serial is refused for the Mindray, which needs a socket to hear its ACK on`() {
        val e = assertFailsWith<CliError> { parse("mindray", "--serial", "COM3") }
        assertTrue(e.message!!.contains("TCP-only"), "unhelpful message: ${e.message}")
    }

    /**
     * The one that can hurt somebody. The simulator writes invented results
     * onto whatever accession it names, and BNM Lab files them exactly as it
     * would the analyzer's own — so pointing it at a lab in production, from
     * shell history or a mistyped octet, is the failure worth a gate.
     */
    @Test
    fun `another machine's address is refused until it is asked for in as many words`() {
        val refused = assertFailsWith<CliError> { parse("mindray", "--host", "192.168.1.50") }
        assertTrue(refused.message!!.contains("--live-lab"), "unhelpful refusal: ${refused.message}")
        assertTrue(refused.message!!.contains("INVENTED"), "it must say what is at stake: ${refused.message}")

        assertEquals("192.168.1.50", parse("mindray", "--host", "192.168.1.50", "--live-lab").host)
        // Nothing leaves the machine on a dry run, so nothing to gate.
        assertEquals("192.168.1.50", parse("mindray", "--host", "192.168.1.50", "--dry-run").host)
    }

    @Test
    fun `this machine is recognised however it is spelled, and nothing else is`() {
        for (here in listOf("127.0.0.1", "localhost", "LOCALHOST", "127.1.2.3", "::1", "[::1]")) {
            assertTrue(Cli.isLoopback(here), "$here is this machine")
        }
        for (elsewhere in listOf("192.168.1.50", "10.0.0.2", "lab-pc", "127.0.0", "0.0.0.0", "128.0.0.1")) {
            assertTrue(!Cli.isLoopback(elsewhere), "$elsewhere was treated as this machine")
        }
    }

    /**
     * A serial cable has no connections, so the three faults that are ABOUT a
     * connection cannot be rehearsed down one. `--truncated` is the dangerous
     * one: the app keeps a single frame assembler for the whole serial listener,
     * so the half frame never gets reported and merges with the next sample.
     */
    @Test
    fun `the faults that need a connection are refused on a cable`() {
        val truncated = assertFailsWith<CliError> { parse("mispa", "--serial", "COM3", "--truncated") }
        assertTrue(truncated.message!!.contains("merge with your next sample"), truncated.message!!)
        assertTrue(assertFailsWith<CliError> { parse("mispa", "--serial", "COM3", "--burst", "3") }
            .message!!.contains("interleave"))
        assertTrue(assertFailsWith<CliError> { parse("mispa", "--serial", "COM3", "--hang") }
            .message!!.contains("nothing to rehearse"))
        // Over TCP all three are exactly what the tool is for.
        parse("mispa", "--truncated")
        parse("mispa", "--burst", "3")
        parse("mispa", "--hang")
    }

    @Test
    fun `cbc-only is a Mindray run mode and is refused on the three-part analyzer`() {
        assertTrue(parse("mindray", "--cbc-only").cbcOnly)
        assertTrue(assertFailsWith<CliError> { parse("mispa", "--cbc-only") }
            .message!!.contains("3-part"))
    }

    /**
     * bash, zsh and Git Bash expand `{1..5}` before the JVM sees it, so the
     * pattern the help text prints arrives as five loose words. Saying
     * "Unknown option 'ACC-S1-0002'" to somebody who typed what we told them
     * to type is the worst answer available.
     */
    @Test
    fun `an argument the shell already expanded says so instead of 'unknown option'`() {
        val e = assertFailsWith<CliError> {
            parse("mispa", "--id", "ACC-S1-0001", "ACC-S1-0002", "ACC-S1-0003")
        }
        assertTrue(e.message!!.contains("expanded the braces"), e.message!!)
        assertTrue(e.message!!.contains("quote it"), e.message!!)
    }

    @Test
    fun `every id pattern the help text prints is quoted against the shell`() {
        for (line in Cli.HELP.lines().filter { it.contains("{1..5}") }) {
            assertTrue(line.contains("'ACC-S1-000{1..5}'"),
                "an unquoted brace pattern a reader would copy verbatim: $line")
        }
    }

    @Test
    fun `bad input is refused in words`() {
        assertTrue(assertFailsWith<CliError> { parse("sysmex") }.message!!.contains("Unknown analyzer"))
        assertTrue(assertFailsWith<CliError> { parse("mispa", "--profile", "sick") }.message!!.contains("Unknown profile"))
        assertTrue(assertFailsWith<CliError> { parse("mispa", "--port", "99999") }.message!!.contains("1-65535"))
        assertTrue(assertFailsWith<CliError> { parse("mispa", "--wat") }.message!!.contains("Unknown option"))
        assertTrue(assertFailsWith<CliError> { parse("mispa", "--port") }.message!!.contains("needs a value"))
        assertTrue(assertFailsWith<CliError> { parse("mispa", "--count", "many") }.message!!.contains("whole number"))
        assertTrue(assertFailsWith<CliError> { Cli.expandIds("A{9..1}") }.message!!.contains("backwards"))
    }

    @Test
    fun `help and the profile list are printable without touching the network`() {
        val out = RecordingPrinter()
        assertEquals(0, runCli(listOf("--help"), printerFor = { out }))
        assertTrue(out.text.contains("--no-specimen") && out.text.contains("analyzer-sim mindray --host"))
        val profiles = RecordingPrinter()
        assertEquals(0, runCli(listOf("--list-profiles"), printerFor = { profiles }))
        for (p in Profile.entries) assertTrue(profiles.text.contains(p.cliName), "${p.cliName} is undocumented")
    }

    @Test
    fun `a bad command line exits 2 and points at help`() {
        val out = RecordingPrinter()
        assertEquals(2, runCli(listOf("mindray", "--nonsense"), printerFor = { out }))
        assertTrue(out.text.contains("--help"))
    }

    @Test
    fun `every fault flag in the help text is a flag the parser accepts`() {
        val documented = Regex("--[a-z-]+").findAll(Cli.HELP).map { it.value }.toSet()
        for (flag in documented - setOf("--help", "--list-profiles")) {
            val args = when (flag) {
                // Mindray-only switches, checked against the analyzer that has them.
                "--cbc-only" -> listOf("mindray", flag)
                "--host", "--serial", "--patient", "--patient-id", "--id", "--profile" ->
                    listOf("mispa", flag, defaultFor(flag))
                "--port", "--baud", "--count", "--interval", "--seed", "--ack-timeout", "--slow-chunks", "--burst" ->
                    listOf("mispa", flag, "1")
                else -> listOf("mispa", flag)
            }
            Cli.parse(args)   // throws CliError if the help promises something that does not exist
        }
    }

    private fun defaultFor(flag: String) = when (flag) {
        "--profile" -> "normal"
        "--serial" -> "COM3"
        "--host" -> "127.0.0.1"     // anything else needs --live-lab, which is its own test
        else -> "X"
    }
}
