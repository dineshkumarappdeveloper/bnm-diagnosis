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
        val scenario = askScenario(reader, out) ?: return 1

        var options = Options(analyzer = analyzer, port = analyzer.defaultPort, verbose = false)

        val serial = if (analyzer == Analyzer.MISPA) askSerial(reader, out) else null
        options = if (serial != null) {
            options.copy(serialPort = serial)
        } else {
            val host = ask(reader, out, "PC running BNM Lab", options.host)
            val port = ask(reader, out, "Port it listens on", options.port.toString()).toIntOrNull()
                ?: options.port
            options.copy(host = host, port = port)
        }

        val id = ask(reader, out, "Specimen id (the accession on the tube)", options.ids.first())
        options = options.copy(ids = Cli.expandIds(id).ifEmpty { options.ids })
        options = scenario.apply(options)

        out.info("")
        out.info("Scenario: ${scenario.title} — ${scenario.note}")
        out.info("")
        return Sender(options, printerFor(options.verbose || options.dryRun)).run()
    }

    private fun askAnalyzer(reader: BufferedReader, out: Printer): Analyzer? {
        out.info("Which analyzer?")
        Analyzer.entries.forEachIndexed { i, a -> out.info("  ${i + 1}. ${a.label}") }
        val answer = ask(reader, out, "Choose", "1")
        return Analyzer.entries.getOrNull((answer.toIntOrNull() ?: 1) - 1)
            ?: Analyzer.of(answer)
            ?: run { out.warn("Not one of the choices."); null }
    }

    private fun askScenario(reader: BufferedReader, out: Printer): Scenario? {
        out.info("")
        out.info("What should it do?")
        SCENARIOS.forEachIndexed { i, s ->
            out.info("  ${(i + 1).toString().padStart(2)}. ${s.title.padEnd(30)} ${s.note}")
        }
        val answer = ask(reader, out, "Choose", "1").toIntOrNull() ?: 1
        return SCENARIOS.getOrNull(answer - 1) ?: run { out.warn("Not one of the choices."); null }
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
