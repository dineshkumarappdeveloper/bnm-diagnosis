package com.bnm.analyzersim

/** Which analyzer the tool is pretending to be. */
enum class Analyzer(
    val cliName: String,
    /** The driver key the lab must have picked on the Instruments screen. */
    val driverKey: String,
    val defaultPort: Int,
    val label: String,
) {
    MINDRAY("mindray", "mindray_hl7", 5500,
        "Mindray BC-5130 / BC-5000 / BC-5150 — 5-part, HL7 v2.3.1 over MLLP/TCP, waits for an ACK"),
    MISPA("mispa", "mispa_count_x", 5501,
        "Agappe Mispa Count X — 3-part, \$-delimited frames over RS-232 (or raw TCP on the bench), one-way");

    companion object {
        fun of(name: String): Analyzer? = entries.firstOrNull { it.cliName.equals(name, ignoreCase = true) }
    }
}

/**
 * The deliberate misbehaviours.
 *
 * These are the reason this tool exists. A frame that parses proves the happy
 * path once; a lab that will not link is almost always one of these, and
 * until now they could only be reproduced by unplugging something at the
 * client's bench.
 */
data class Faults(
    /** Cut the frame in half and close the connection. */
    val truncated: Boolean = false,
    /** Send bytes that are not a frame at all. */
    val garbage: Boolean = false,
    /** Send in small pieces this many ms apart — exercises reassembly. */
    val slowChunksMs: Long? = null,
    /** Send the same frame twice on the same link. */
    val duplicate: Boolean = false,
    /** Open this many connections at once. */
    val burst: Int = 0,
    /** Connect, send nothing, hold the socket open. */
    val hang: Boolean = false,
) {
    val any: Boolean get() = truncated || garbage || slowChunksMs != null || duplicate || burst > 0 || hang
}

/** A parsed command line. Everything the run needs, and nothing it reads from the environment. */
data class Options(
    val analyzer: Analyzer,
    val host: String = "127.0.0.1",
    val port: Int = 0,
    val serialPort: String? = null,
    val baud: Int = 115200,
    val ids: List<String> = listOf("SIM-0001"),
    val count: Int = 0,
    val intervalSeconds: Double = 0.0,
    val profile: Profile = Profile.NORMAL,
    val patientName: String? = null,
    val patientId: String? = null,
    val qc: Boolean = false,
    val histograms: Boolean = true,
    val image: Boolean = false,
    val seed: Long = 1L,
    val ackTimeoutMs: Long = 5_000,
    val unknownCode: Boolean = false,
    val badUnits: Boolean = false,
    val noSpecimen: Boolean = false,
    val faults: Faults = Faults(),
    val dryRun: Boolean = false,
    val verbose: Boolean = false,
) {
    /** How many samples this run sends. Defaults to "one per id". */
    val samples: Int get() = if (count > 0) count else ids.size

    val usesSerial: Boolean get() = serialPort != null
}

/** A command line that could not be honoured, with a message meant for a human. */
class CliError(message: String) : Exception(message)

object Cli {

    /** `--help` / `analyzer-sim` with a bad verb. */
    val HELP: String = """
        BNM Analyzer Simulator — pretend to be a lab analyzer so BNM Lab can be
        commissioned and debugged with no hardware on the bench.

        USAGE
          analyzer-sim                          interactive menu (no flags to remember)
          analyzer-sim <mindray|mispa> [options]
          analyzer-sim --list-profiles
          analyzer-sim --help

        ANALYZERS
          mindray   ${Analyzer.MINDRAY.label}
          mispa     ${Analyzer.MISPA.label}

        WHERE TO SEND
          --host <addr>        the PC running BNM Lab (default 127.0.0.1)
          --port <n>           its listening port (default ${Analyzer.MINDRAY.defaultPort} mindray, ${Analyzer.MISPA.defaultPort} mispa)
          --serial <port>      send down a serial cable instead (mispa; e.g. COM3, /dev/tty.usbserial-110)
          --baud <n>           serial speed (default 115200, 8-N-1)

        WHAT TO SEND
          --id <ids>           specimen id(s): one, a comma list, or a pattern
                               such as ACC-S1-000{1..5}
          --count <n>          how many samples (default: one per id; ids cycle)
          --interval <s>       seconds between samples (default 0)
          --profile <name>     ${Profile.names}
          --patient "<name>"   patient name (mindray PID-5; mispa carries none)
          --patient-id <id>    patient id
          --qc                 a QC material run (mindray MSH-11 = Q)
          --no-histograms      numbers only, no curves
          --image              attach the DIFF scattergram bitmap (mindray, ~40 KB)
          --seed <n>           reproducible values and curves (default 1)

        THE ACK (mindray)
          --ack-timeout <s>    how long to wait for the app's ACK (default 5)
          --no-ack-wait        send and hang up without waiting — what a badly
                               configured analyzer does

        FAULTS — the point of the tool
          --truncated          cut the frame mid-way and close the connection
          --garbage            send bytes that are not a frame at all
          --slow-chunks <ms>   dribble the frame out in small pieces
          --unknown-code       a parameter the driver has no code for
          --bad-units          a unit the app's converter cannot bridge (mindray)
          --no-specimen        no sample id keyed on the analyzer -> claim queue
          --duplicate          send the same frame twice
          --burst <n>          n connections at once
          --hang               connect, send nothing, hold the socket open

        SEEING WHAT HAPPENS
          --dry-run            print the frame, send nothing
          --verbose            print the frame as it goes out (long base64 blobs
                               abbreviated, or a scattergram fills the screen)
          --list-profiles      describe the value profiles and exit

        EXAMPLES
          analyzer-sim mindray --host 192.168.1.50
          analyzer-sim mindray --id ACC-S1-00042 --profile critical --image
          analyzer-sim mispa --serial COM3 --id ACC-S1-000{1..5} --interval 2
          analyzer-sim mindray --no-specimen            # lands in the claim queue
          analyzer-sim mindray --truncated              # half a frame, then silence
    """.trimIndent()

    val PROFILES: String = buildString {
        appendLine("Value profiles:")
        for (p in Profile.entries) appendLine("  ${p.cliName.padEnd(18)}${p.summary}")
        appendLine()
        appendLine("Every profile is internally consistent: the differential sums to 100,")
        appendLine("absolutes are WBC x percent, HCT is RBC x MCV, MCHC is HGB over HCT.")
        append("--seed fixes the patient; the same seed always emits the same frame.")
    }

    /**
     * Parse [args]. Throws [CliError] with a human sentence for anything it
     * cannot honour — a field engineer reading a stack trace is a bug in this
     * tool, not in their typing.
     */
    fun parse(args: List<String>): Options {
        val analyzer = Analyzer.of(args.first())
            ?: throw CliError("Unknown analyzer '${args.first()}'. Expected 'mindray' or 'mispa'.")

        var o = Options(analyzer = analyzer, port = analyzer.defaultPort)
        var faults = Faults()
        var i = 1
        fun next(flag: String): String =
            args.getOrNull(++i) ?: throw CliError("$flag needs a value.")
        fun int(flag: String): Int =
            next(flag).let { it.toIntOrNull() ?: throw CliError("$flag needs a whole number, not '$it'.") }
        fun long(flag: String): Long =
            next(flag).let { it.toLongOrNull() ?: throw CliError("$flag needs a whole number, not '$it'.") }
        fun double(flag: String): Double =
            next(flag).let { it.toDoubleOrNull() ?: throw CliError("$flag needs a number, not '$it'.") }

        while (i < args.size) {
            when (val arg = args[i]) {
                "--host" -> o = o.copy(host = next(arg))
                "--port" -> o = o.copy(port = int(arg))
                "--serial" -> o = o.copy(serialPort = next(arg))
                "--baud" -> o = o.copy(baud = int(arg))
                "--id" -> o = o.copy(ids = expandIds(next(arg)))
                "--count" -> o = o.copy(count = int(arg))
                "--interval" -> o = o.copy(intervalSeconds = double(arg))
                "--profile" -> {
                    val name = next(arg)
                    o = o.copy(profile = Profile.of(name)
                        ?: throw CliError("Unknown profile '$name'. Try: ${Profile.names}"))
                }
                "--patient" -> o = o.copy(patientName = next(arg))
                "--patient-id" -> o = o.copy(patientId = next(arg))
                "--qc" -> o = o.copy(qc = true)
                "--no-histograms" -> o = o.copy(histograms = false)
                "--image" -> o = o.copy(image = true)
                "--seed" -> o = o.copy(seed = long(arg))
                "--ack-timeout" -> o = o.copy(ackTimeoutMs = (double(arg) * 1000).toLong())
                "--no-ack-wait" -> o = o.copy(ackTimeoutMs = 0)
                "--truncated" -> faults = faults.copy(truncated = true)
                "--garbage" -> faults = faults.copy(garbage = true)
                "--slow-chunks" -> faults = faults.copy(slowChunksMs = long(arg))
                "--unknown-code" -> o = o.copy(unknownCode = true)
                "--bad-units" -> o = o.copy(badUnits = true)
                "--no-specimen" -> o = o.copy(noSpecimen = true)
                "--duplicate" -> faults = faults.copy(duplicate = true)
                "--burst" -> faults = faults.copy(burst = int(arg))
                "--hang" -> faults = faults.copy(hang = true)
                "--dry-run" -> o = o.copy(dryRun = true)
                "--verbose" -> o = o.copy(verbose = true)
                else -> throw CliError("Unknown option '$arg'. Run with --help for the list.")
            }
            i++
        }

        val opts = o.copy(faults = faults)
        validate(opts)
        return opts
    }

    private fun validate(o: Options) {
        if (o.usesSerial) {
            if (o.analyzer == Analyzer.MINDRAY) throw CliError(
                "The Mindray driver is TCP-only: it waits for an ACK on the same socket, and a " +
                    "serial cable has no way to carry one. Drop --serial and give --host/--port.")
            if (o.baud !in 1..4_000_000) throw CliError("--baud ${o.baud} is not a serial speed.")
        } else if (o.port !in 1..65535) {
            throw CliError("--port ${o.port} is outside 1-65535.")
        }
        if (o.count < 0) throw CliError("--count cannot be negative.")
        if (o.faults.burst < 0) throw CliError("--burst cannot be negative.")
        if (o.ids.isEmpty()) throw CliError("--id was given but expanded to nothing.")
        if (o.ackTimeoutMs < 0) throw CliError("--ack-timeout cannot be negative.")
    }

    /**
     * "A", "A,B", or a pattern with one `{start..end}` range: `ACC-S1-000{1..5}`
     * gives ACC-S1-0001 … ACC-S1-0005. The width comes from the digits written
     * in the pattern, so leading zeros survive — an accession that lost them
     * would match nothing.
     */
    fun expandIds(spec: String): List<String> =
        spec.split(',').map { it.trim() }.filter { it.isNotEmpty() }.flatMap { expandOne(it) }

    private val RANGE = Regex("""\{(\d+)\.\.(\d+)}""")

    private fun expandOne(id: String): List<String> {
        val m = RANGE.find(id) ?: return listOf(id)
        val from = m.groupValues[1]
        val to = m.groupValues[2]
        val start = from.toLongOrNull() ?: return listOf(id)
        val end = to.toLongOrNull() ?: return listOf(id)
        if (end < start) throw CliError("--id range {$from..$to} counts backwards.")
        if (end - start > 10_000) throw CliError("--id range {$from..$to} is more than 10 000 samples.")
        val width = from.length
        return (start..end).map { n ->
            id.replaceRange(m.range, n.toString().padStart(width, '0'))
        }
    }
}
