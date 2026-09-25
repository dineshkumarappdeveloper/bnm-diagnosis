package com.bnm.analyzersim

import java.io.BufferedReader

/**
 * The no-flags path.
 *
 * The person who most needs this tool is standing in a lab with a phone in one
 * hand, and will not remember `--slow-chunks`. Run it with no arguments and it
 * asks four questions. Every answer has a default, so mashing Enter sends a
 * normal result to 127.0.0.1 — which is the right thing to do first anyway.
 */
object Menu {

    /** A named rehearsal, and what it changes about the run. */
    data class Scenario(val title: String, val note: String, val apply: (Options) -> Options)

    val SCENARIOS: List<Scenario> = listOf(
        Scenario("Normal result", "should land on the order with that accession") { it },
        Scenario("Critical result", "panic values — should reach the critical call-out list") {
            it.copy(profile = Profile.CRITICAL)
        },
        Scenario("Anaemia", "low HGB and MCV, wide RDW") { it.copy(profile = Profile.ANAEMIA) },
        Scenario("No specimen id keyed", "should land in the claim queue") { it.copy(noSpecimen = true) },
        Scenario("Truncated frame", "half a frame, then the connection closes") {
            it.copy(faults = it.faults.copy(truncated = true))
        },
        Scenario("Garbage bytes", "not a frame at all — bytes should count, frames should not") {
            it.copy(faults = it.faults.copy(garbage = true))
        },
        Scenario("Dribbled out slowly", "64-byte pieces 40ms apart — tests reassembly") {
            it.copy(faults = it.faults.copy(slowChunksMs = 40))
        },
        Scenario("QC run", "should be acknowledged and ignored, not filed as a patient") {
            it.copy(qc = true)
        },
        Scenario("Unknown parameter code", "a firmware the driver has not met") { it.copy(unknownCode = true) },
        Scenario("Unconvertible unit", "the app should store the value and log the mismatch") {
            it.copy(badUnits = true)
        },
        Scenario("Duplicate frame", "the same sample twice on one connection") {
            it.copy(faults = it.faults.copy(duplicate = true))
        },
        Scenario("Five samples, 2s apart", "a small run") {
            it.copy(count = 5, intervalSeconds = 2.0)
        },
        Scenario("Burst of five connections", "five analyzers at once") {
            it.copy(faults = it.faults.copy(burst = 5))
        },
        Scenario("Connect and say nothing", "the analyzer is wired up but nobody pressed Send") {
            it.copy(faults = it.faults.copy(hang = true))
        },
        Scenario("With the DIFF scattergram", "a ~40 KB message — the one that finds buffer bugs") {
            it.copy(image = true)
        },
        Scenario("Print the frame, send nothing", "read it before you trust it") {
            it.copy(dryRun = true, verbose = true)
        },
    )

    /**
     * The scenario list as it should be OFFERED for [analyzer].
     *
     * The QC rehearsal is Mindray-only — the Mispa format has no processing-id
     * field — and a menu that offers it anyway teaches the engineer that the
     * app files QC material as a patient, which is not what they just tested.
     * Relabelled rather than removed, so the numbering an engineer was read
     * over the phone means the same thing on both analyzers.
     */
    fun scenariosFor(analyzer: Analyzer): List<Scenario> =
        if (analyzer != Analyzer.MISPA) SCENARIOS else SCENARIOS.map { s ->
            if (s.title != "QC run") s else s.copy(
                title = "QC run (Mindray only)",
                note = "this format has no QC field — it would be filed as a patient",
            )
        }

    /**
     * [printerFor] rather than a printer: the scenario is chosen partway
     * through, and one of them (print the frame, send nothing) needs a verbose
     * printer that the prompts above it must not have.
     */
    fun run(reader: BufferedReader, printerFor: (verbose: Boolean) -> Printer): Int {
        val out = printerFor(false)
        out.info("BNM Analyzer Simulator")
        out.info("Pretends to be a lab analyzer so BNM Lab can be tested with no hardware.")
        out.info("Press Enter to take the [default] at every question. Ctrl+C to quit.")
        out.info("")

        val analyzer = askAnalyzer(reader, out) ?: return 1
        val scenario = askScenario(reader, out, analyzer) ?: return 1

        var options = Options(analyzer = analyzer, port = analyzer.defaultPort, verbose = false)

        val serial = if (analyzer == Analyzer.MISPA) askSerial(reader, out) else null
        options = if (serial != null) {
            options.copy(serialPort = serial)
        } else {
            val host = ask(reader, out, "PC running BNM Lab", options.host)
            if (!Cli.isLoopback(host) && !confirmLiveLab(reader, out, host)) return 1
            val port = ask(reader, out, "Port it listens on", options.port.toString()).toIntOrNull()
                ?: options.port
            options.copy(host = host, port = port, liveLab = !Cli.isLoopback(host))
        }

        val id = ask(reader, out, "Specimen id (the accession on the tube)", options.ids.first())
        options = options.copy(ids = Cli.expandIds(id).ifEmpty { options.ids })
        options = scenario.apply(options)

        // The same refusals the command line makes. A scenario that cannot be
        // run down the chosen cable has to say so here too, or the menu is a
        // way around the tool's own judgement.
        try {
            Cli.validate(options)
        } catch (e: CliError) {
            out.warn(e.message ?: "That combination cannot be sent.")
            return 2
        }

        out.info("")
        out.info("Scenario: ${scenario.title} — ${scenario.note}")
        out.info("")
        return Sender(options, printerFor(options.verbose || options.dryRun)).run()
    }

    /**
     * The one gate between "I typed the lab's address" and synthetic results on
     * a real patient's order. Typed in full, this run — an Enter must not do it.
     */
    private fun confirmLiveLab(reader: BufferedReader, out: Printer, host: String): Boolean {
        out.info("")
        out.warn("$host is not this machine.")
        out.warn("Everything the simulator sends is invented, and BNM Lab files it exactly as it")
        out.warn("would the analyzer's own numbers — on the order with that accession, attributed")
        out.warn("to the instrument, with nothing to mark it as generated.")
        val answer = ask(reader, out, "Type YES to send test results to $host", "no")
        if (answer.equals("YES", ignoreCase = true)) return true
        out.warn("Stopped. Nothing was sent.")
        return false
    }

    private fun askAnalyzer(reader: BufferedReader, out: Printer): Analyzer? {
        out.info("Which analyzer?")
        Analyzer.entries.forEachIndexed { i, a -> out.info("  ${i + 1}. ${a.label}") }
        val answer = ask(reader, out, "Choose", "1")
        // The NAME first. Resolving the number first with `?: 1` made a typed
        // name fall through to choice 1: "mispa" silently selected the Mindray,
        // dialled the wrong port with the wrong protocol, and the engineer went
        // looking for a fault in the app's Mispa driver.
        Analyzer.of(answer)?.let { return it }
        answer.toIntOrNull()?.let { n -> Analyzer.entries.getOrNull(n - 1)?.let { return it } }
        out.warn("'$answer' is not one of the choices — type the number, or 'mindray' / 'mispa'.")
        return null
    }

    private fun askScenario(reader: BufferedReader, out: Printer, analyzer: Analyzer): Scenario? {
        val scenarios = scenariosFor(analyzer)
        out.info("")
        out.info("What should it do?")
        scenarios.forEachIndexed { i, s ->
            out.info("  ${(i + 1).toString().padStart(2)}. ${s.title.padEnd(30)} ${s.note}")
        }
        val answer = ask(reader, out, "Choose", "1")
        // No `?: 1` here either: defaulting an unreadable answer to "Normal
        // result" TRANSMITS a frame to somebody's lab when the person was
        // trying to say something else.
        val choice = answer.toIntOrNull()
        return scenarios.getOrNull((choice ?: 0) - 1)
            ?: run { out.warn("'$answer' is not one of the numbers in that list."); null }
    }

    /**
     * Offer the serial ports that exist right now. A typed-in port name that is
     * not plugged in is the other half of the "it will not link" calls, so the
     * list is the answer rather than a free-text box.
     */
    private fun askSerial(reader: BufferedReader, out: Printer): String? {
        val ports = SerialTransport.available()
        out.info("")
        out.info("How is it wired?")
        out.info("  0. TCP (the app listens; the simulator dials in)")
        ports.forEachIndexed { i, p -> out.info("  ${i + 1}. serial $p") }
        if (ports.isEmpty()) out.info("     (no serial ports visible on this machine)")
        val answer = ask(reader, out, "Choose", "0").toIntOrNull() ?: 0
        return if (answer <= 0) null else ports.getOrNull(answer - 1)
    }

    private fun ask(reader: BufferedReader, out: Printer, question: String, default: String): String {
        out.info("$question [$default]: ")
        val line = reader.readLine()?.trim().orEmpty()
        return line.ifEmpty { default }
    }
}
