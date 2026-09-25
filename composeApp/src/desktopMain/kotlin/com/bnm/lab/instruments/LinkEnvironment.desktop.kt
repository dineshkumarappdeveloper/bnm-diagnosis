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

    override fun localIpv4(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nic -> nic.inetAddresses.toList().filterIsInstance<Inet4Address>().map { it.hostAddress } }
            .distinct()
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

    override fun ping(host: String): PingResult {
        val h = host.trim()
        if (h.isEmpty() || h.any { it.isWhitespace() }) return PingResult(false, "not a valid address")
        // -W is seconds on Linux but MILLISECONDS on macOS.
        val cmd = when {
            isWindows -> listOf("ping", "-n", "1", "-w", "1000", h)
            System.getProperty("os.name").orEmpty().lowercase().contains("mac") -> listOf("ping", "-c", "1", "-W", "1000", h)
            else -> listOf("ping", "-c", "1", "-W", "1", h)
        }
        val t0 = System.currentTimeMillis()
        val code = runExit(cmd) ?: return PingResult(false, "ping could not be run")
        val ms = System.currentTimeMillis() - t0
        return if (code == 0) PingResult(true, "$ms ms") else PingResult(false, "no reply in ${ms} ms")
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

    private fun run(cmd: List<String>, timeoutMs: Long = PROCESS_TIMEOUT_MS): String? = runCatching {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val out = ByteArrayOutputStream()
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
        out.toString(Charsets.UTF_8.name())
    }.getOrElse { e ->
        AppLog.w("LinkCheck", "${cmd.take(3).joinToString(" ")} failed: ${e.message}")
        null
    }

    private fun runExit(cmd: List<String>, timeoutMs: Long = PROCESS_TIMEOUT_MS): Int? = runCatching {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        // Drain so a chatty tool can't block on a full pipe.
        Thread { runCatching { p.inputStream.use { it.readAllBytes() } } }.apply { isDaemon = true; start() }
        if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            p.destroyForcibly()
            return@runCatching null
        }
        p.exitValue()
    }.getOrNull()

    private companion object {
        const val PROCESS_TIMEOUT_MS = 5_000L
        /** The UAC prompt itself is not waited for — Start-Process returns once it is launched. */
        const val ELEVATION_TIMEOUT_MS = 20_000L
        const val TCP_PROBE_TIMEOUT_MS = 2_000
        const val MAX_OUTPUT_BYTES = 4 * 1024 * 1024
    }
}

actual fun platformLinkEnvironment(): LinkEnvironment = DesktopLinkEnvironment()
