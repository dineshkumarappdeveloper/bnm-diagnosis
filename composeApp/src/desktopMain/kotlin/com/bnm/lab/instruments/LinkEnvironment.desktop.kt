package com.bnm.lab.instruments

import com.bnm.lab.diagnostics.AppLog
import com.fazecast.jSerialComm.SerialPort
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Desktop answers for [LinkEnvironment] — java.base only (the packaged app
 * runs on a jlink-trimmed runtime), plus jSerialComm for the ports.
 *
 * Reads never use PowerShell: `netsh advfirewall` prints in the console code
 * page and its English keywords are ASCII, so [NetshParser] can read them;
 * every process is capped at [PROCESS_TIMEOUT_MS] and [MAX_OUTPUT_BYTES] so a
 * hung tool cannot hang the panel. The ONE elevation ([addFirewallRule]) goes
 * through `Start-Process -Verb RunAs` because that is what raises the UAC
 * prompt; it returns as soon as the prompt is on screen and the caller
 * re-checks a few seconds later.
 */
class DesktopLinkEnvironment(
    private val exePath: String? = runCatching { ProcessHandle.current().info().command().orElse(null) }.getOrNull(),
) : LinkEnvironment {

    override val osName: String = "${System.getProperty("os.name")} ${System.getProperty("os.version")}".trim()
    override val isWindows: Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("win")
    override val environmentKnown: Boolean = true

    // The interface name rides along: Windows lists a Hyper-V switch or a VPN
    // tunnel here exactly like the real LAN card, and the Link check can only
    // rank them apart by name (LocalAddress.looksVirtual).
    override fun localIpv4(): List<LocalAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nic ->
                val label = nic.displayName?.takeIf { it.isNotBlank() } ?: nic.name.orEmpty()
                nic.inetAddresses.toList().filterIsInstance<Inet4Address>()
                    .mapNotNull { it.hostAddress }
                    .map { LocalAddress(it, label) }
            }
            .distinctBy { it.ip }
    }.getOrDefault(emptyList())

    override fun serialPorts(): List<String> =
        runCatching { SerialPort.getCommPorts().map { it.systemPortName } }.getOrDefault(emptyList())

    override fun firewall(port: Int): FirewallFacts = firewallFor(listOf(port)).getValue(port)

    override fun firewallFor(ports: Collection<Int>): Map<Int, FirewallFacts> {
        if (ports.isEmpty()) return emptyMap()
        if (!isWindows) return ports.associateWith { FirewallFacts.NOT_APPLICABLE }
        val profile = run(listOf("netsh", "advfirewall", "show", "currentprofile"))
        // `verbose` is what adds the Program: line — without it a per-program allow can't be seen.
        val rules = run(listOf("netsh", "advfirewall", "firewall", "show", "rule", "name=all", "dir=in", "verbose"))
        return ports.associateWith { NetshParser.facts(it, exePath, profile, rules) }
    }

    /**
     * The OUTPUT decides, not the exit code alone. Windows `ping -n 1` exits 0
     * when a router answers "Destination host unreachable", so the code says
     * "reachable" for a host that plainly is not; and a filtered analyzer, a
     * powered-off one and an unroutable address all share a non-zero code. Only
     * the tool's own unreachable/unknown-host wording is treated as evidence —
     * everything else is [PingOutcome.NO_ANSWER], which the checklist reports
     * as inconclusive rather than blaming the cable.
     */
    override fun ping(host: String): PingResult {
        val h = host.trim()
        if (h.isEmpty() || h.any { it.isWhitespace() }) return PingResult.unreachable("not a valid address")
        // -W is seconds on Linux but MILLISECONDS on macOS.
        val cmd = when {
            isWindows -> listOf("ping", "-n", "1", "-w", "1000", h)
            System.getProperty("os.name").orEmpty().lowercase().contains("mac") -> listOf("ping", "-c", "1", "-W", "1000", h)
            else -> listOf("ping", "-c", "1", "-W", "1", h)
        }
        val t0 = System.currentTimeMillis()
        val out = exec(cmd) ?: return PingResult.noAnswer("ping could not be run")
        val ms = System.currentTimeMillis() - t0
        return classifyPing(out.code, out.text, ms)
    }

    /** The engine logs a harmless closed connection; nothing is sent, so the byte counter stays at zero. */
    override fun tcpSelfProbe(port: Int): TcpProbeResult = runCatching {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port), TCP_PROBE_TIMEOUT_MS)
            TcpProbeResult(true, "this PC accepted a connection on port $port")
        }
    }.getOrElse { e ->
        TcpProbeResult(false, "port $port did not accept a connection (${e.message?.take(80) ?: e::class.simpleName})")
    }

    override fun probeSerial(portName: String): String? {
        val port = runCatching { SerialPort.getCommPort(portName) }.getOrNull() ?: return "No such port"
        if (!port.openPort()) return "Could not open $portName — in use by another program, or unplugged"
        runCatching { port.closePort() }
        return null
    }

    override fun addFirewallRule(port: Int): FirewallRuleOutcome {
        if (!isWindows) return FirewallRuleOutcome(false, "Only on Windows")
        // No double quote may appear in a ProcessBuilder argument on Windows —
        // NetshParser.elevateRuleCommand explains why and builds the quotes in
        // PowerShell instead.
        val code = runExit(listOf("powershell", "-NoProfile", "-Command", NetshParser.elevateRuleCommand(port)),
            timeoutMs = ELEVATION_TIMEOUT_MS)
        return when (code) {
            null -> FirewallRuleOutcome(false, "PowerShell did not answer")
            0 -> FirewallRuleOutcome(true, "Windows asked for permission — allow it, then the rule is added")
            else -> FirewallRuleOutcome(false, "cancelled or refused (exit $code)")
        }
    }

    // ── process helpers: capped output, hard timeout, never throw ──

    private data class ProcOut(val code: Int, val text: String)

    /** Exit code AND output; null when the tool could not be run or timed out. */
    private fun exec(cmd: List<String>, timeoutMs: Long = PROCESS_TIMEOUT_MS): ProcOut? = runCatching {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val out = ByteArrayOutputStream()
        // Drain on a thread so a chatty tool can't block on a full pipe.
        val reader = Thread {
            runCatching {
                p.inputStream.use { ins ->
                    val buf = ByteArray(8 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        if (out.size() < MAX_OUTPUT_BYTES) out.write(buf, 0, minOf(n, MAX_OUTPUT_BYTES - out.size()))
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        val finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            p.destroyForcibly()
            AppLog.w("LinkCheck", "${cmd.take(3).joinToString(" ")} timed out after $timeoutMs ms")
            return@runCatching null
        }
        reader.join(1_000)
        ProcOut(p.exitValue(), out.toString(Charsets.UTF_8.name()))
    }.getOrElse { e ->
        AppLog.w("LinkCheck", "${cmd.take(3).joinToString(" ")} failed: ${e.message}")
        null
    }

    private fun run(cmd: List<String>, timeoutMs: Long = PROCESS_TIMEOUT_MS): String? = exec(cmd, timeoutMs)?.text

    private fun runExit(cmd: List<String>, timeoutMs: Long = PROCESS_TIMEOUT_MS): Int? = exec(cmd, timeoutMs)?.code

    internal companion object {
        const val PROCESS_TIMEOUT_MS = 5_000L
        /** The UAC prompt itself is not waited for — Start-Process returns once it is launched. */
        const val ELEVATION_TIMEOUT_MS = 20_000L
        const val TCP_PROBE_TIMEOUT_MS = 2_000
        const val MAX_OUTPUT_BYTES = 4 * 1024 * 1024

        /**
         * The network itself saying the host cannot be reached or resolved —
         * the only ping outcomes that justify a red row. Lower-case; matched as
         * substrings of the tool's output.
         */
        private val UNREACHABLE_MARKS = listOf(
            "destination host unreachable", "destination net unreachable", "destination unreachable",
            "no route to host", "network is unreachable",
            "could not find host", "unknown host", "name or service not known",
            "name does not resolve", "cannot resolve", "temporary failure in name resolution",
        )

        /** An echo really came back. "ttl=" survives localisation on Windows; the rest cover *nix. */
        private val REPLY_MARKS = listOf("ttl=", "bytes from", "1 received", "1 packets received")

        /** Shared with the test: the classification is the whole point of reading the output. */
        internal fun classifyPing(code: Int, output: String, ms: Long): PingResult {
            val text = output.lowercase()
            UNREACHABLE_MARKS.firstOrNull { text.contains(it) }?.let { return PingResult.unreachable(it) }
            if (code == 0 || REPLY_MARKS.any { text.contains(it) }) return PingResult.replied("$ms ms")
            return PingResult.noAnswer("no reply in $ms ms")
        }
    }
}

actual fun platformLinkEnvironment(): LinkEnvironment = DesktopLinkEnvironment()
