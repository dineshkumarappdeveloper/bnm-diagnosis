package com.bnm.lab.instruments

/**
 * The facts about the PC that [LinkCheck] cannot compute — read by the
 * platform, never guessed. Desktop is the real implementation (java.base
 * only: NetworkInterface, ProcessBuilder for netsh/ping, a plain Socket for
 * the self-probe; jSerialComm for the ports). Android and iOS answer
 * [UnknownLinkEnvironment]: every row that needs the PC says "unknown"
 * instead of "missing".
 *
 * Calls block (a ping is up to a second, netsh up to five) — run them off
 * the UI thread.
 */
interface LinkEnvironment {
    /** "Windows 11", "Mac OS X 15.5", "Android" — for the report header. */
    val osName: String
    val isWindows: Boolean
    /** False when nothing below can be answered on this device. */
    val environmentKnown: Boolean

    /** Non-loopback IPv4 addresses of interfaces that are up. */
    fun localIpv4(): List<String>
    /** System names of the serial ports present right now (COM3, /dev/tty.usbserial-110). */
    fun serialPorts(): List<String>
    /** Windows: firewall state + whether an inbound allow rule covers [port] or this program. Elsewhere NOT_APPLICABLE. */
    fun firewall(port: Int): FirewallFacts
    /** Same for several ports at once — desktop reads netsh ONCE for all of them. */
    fun firewallFor(ports: Collection<Int>): Map<Int, FirewallFacts> = ports.associateWith { firewall(it) }
    /** One ping with a ~1 s wait; exit code decides. */
    fun ping(host: String): PingResult
    /** Connect to 127.0.0.1:[port] with a 2 s timeout, send nothing, close. True when the connection was accepted. */
    fun tcpSelfProbe(port: Int): TcpProbeResult
    /** Open then close [portName] — zero reads. Null on success, else a short reason. */
    fun probeSerial(portName: String): String?
    /** Windows: launch netsh elevated (UAC prompt) to add the inbound allow rule for [port]. */
    fun addFirewallRule(port: Int): FirewallRuleOutcome
    /** The exact command for an Administrator Command Prompt, for the Copy button. */
    fun firewallRuleCommand(port: Int): String = NetshParser.addRuleCommand(port)
}

data class TcpProbeResult(val accepted: Boolean, val detail: String)

data class FirewallRuleOutcome(val launched: Boolean, val detail: String)

/** Android / iOS: the link check runs, but every PC fact is "unknown". */
object UnknownLinkEnvironment : LinkEnvironment {
    override val osName: String = "this device"
    override val isWindows: Boolean = false
    override val environmentKnown: Boolean = false
    override fun localIpv4(): List<String> = emptyList()
    override fun serialPorts(): List<String> = emptyList()
    override fun firewall(port: Int): FirewallFacts = FirewallFacts.NOT_APPLICABLE
    override fun ping(host: String): PingResult = PingResult(false, "not available on this device")
    override fun tcpSelfProbe(port: Int): TcpProbeResult = TcpProbeResult(false, "not available on this device")
    override fun probeSerial(portName: String): String? = "Serial ports are only available on the lab PC"
    override fun addFirewallRule(port: Int): FirewallRuleOutcome = FirewallRuleOutcome(false, "Only on the Windows lab PC")
}

expect fun platformLinkEnvironment(): LinkEnvironment
