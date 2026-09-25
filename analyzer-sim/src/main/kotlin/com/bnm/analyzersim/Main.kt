package com.bnm.analyzersim

import java.io.BufferedReader
import kotlin.system.exitProcess

/**
 * Entry point. `java -jar analyzer-sim.jar mindray --host 192.168.1.50`, or
 * no arguments at all for the menu.
 *
 * Everything real lives in [runCli], which takes its input and output as
 * parameters — so the tests drive the same code path the engineer does,
 * instead of a parallel one that can quietly disagree with it.
 */
fun main(args: Array<String>) {
    exitProcess(runCli(args.toList()))
}

internal fun runCli(
    args: List<String>,
    stdin: BufferedReader = System.`in`.bufferedReader(),
    printerFor: (verbose: Boolean) -> Printer = { ConsolePrinter(it) },
): Int {
    if (args.isEmpty()) return Menu.run(stdin, printerFor)

    when (args.first()) {
        "--help", "-h", "help" -> { printerFor(false).info(Cli.HELP); return 0 }
        "--list-profiles" -> { printerFor(false).info(Cli.PROFILES); return 0 }
    }

    val options = try {
        Cli.parse(args)
    } catch (e: CliError) {
        val out = printerFor(false)
        out.warn(e.message ?: "Bad command line.")
        out.warn("Run 'analyzer-sim --help' for the options.")
        return 2
    }
    return Sender(options, printerFor(options.verbose || options.dryRun)).run()
}
