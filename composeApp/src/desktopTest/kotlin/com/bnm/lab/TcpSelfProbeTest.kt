package com.bnm.lab

import com.bnm.lab.instruments.DesktopLinkEnvironment
import com.bnm.lab.instruments.PingOutcome
import java.net.ServerSocket
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The "Test connection" self-probe against a real local ServerSocket: accepted while something listens, refused once it is gone. */
class TcpSelfProbeTest {

    private val env = DesktopLinkEnvironment(exePath = null)

    @Test
    fun `a listening port accepts the probe, which sends nothing`() {
        ServerSocket(0).use { server ->
            server.soTimeout = 3_000
            val result = env.tcpSelfProbe(server.localPort)
            assertTrue(result.accepted, result.detail)
            assertTrue(result.detail.contains("accepted a connection on port ${server.localPort}"), result.detail)
            // The connection reached the listener and carried zero bytes.
            val accepted = server.accept()
            accepted.soTimeout = 1_000
            val first = runCatching { accepted.getInputStream().read() }
            assertTrue(first.getOrNull() == -1 || first.exceptionOrNull() is SocketTimeoutException,
                "nothing was sent: $first")
            accepted.close()
        }
    }

    @Test
    fun `a closed port refuses the probe`() {
        val port = ServerSocket(0).use { it.localPort }
        val result = env.tcpSelfProbe(port)
        assertFalse(result.accepted, result.detail)
        assertTrue(result.detail.contains("did not accept"), result.detail)
    }

    @Test
    fun `facts off Windows - firewall not applicable, addresses readable`() {
        if (env.isWindows) return
        val fw = env.firewallFor(listOf(5500, 5501))
        assertTrue(fw.values.all { !it.applicable && it.known }, fw.toString())
        // Loopback is never listed; whatever else this machine has is a dotted quad.
        env.localIpv4().forEach { a ->
            assertTrue(a.ip.matches(Regex("""\d+\.\d+\.\d+\.\d+""")) && a.ip != "127.0.0.1", a.toString())
            // The interface name is what lets the Link check rank a real LAN
            // card ahead of a Hyper-V switch or a VPN tunnel.
            assertTrue(a.iface.isNotBlank(), a.toString())
        }
    }

    @Test
    fun `ping is classified from the output, not the exit code alone`() {
        // Windows answers a router's "Destination host unreachable" with exit 0,
        // so the code alone reports a dead host as reachable.
        val winUnreachable = DesktopLinkEnvironment.classifyPing(0,
            "Pinging 192.168.1.77 with 32 bytes of data:\r\nReply from 192.168.1.1: Destination host unreachable.\r\n", 4)
        assertEquals(PingOutcome.UNREACHABLE, winUnreachable.outcome)

        assertEquals(PingOutcome.UNREACHABLE, DesktopLinkEnvironment.classifyPing(1,
            "Ping request could not find host analyzer1. Please check the name and try again.", 2).outcome)
        assertEquals(PingOutcome.UNREACHABLE, DesktopLinkEnvironment.classifyPing(2,
            "ping: cannot resolve analyzer1: Unknown host", 2).outcome)

        // A silent host: inconclusive, never BLOCKED — analyzers routinely drop ICMP.
        val silent = DesktopLinkEnvironment.classifyPing(1,
            "Pinging 192.168.1.77 with 32 bytes of data:\r\nRequest timed out.\r\n" +
                "Ping statistics for 192.168.1.77:\r\n    Packets: Sent = 1, Received = 0, Lost = 1 (100% loss),\r\n", 1004)
        assertEquals(PingOutcome.NO_ANSWER, silent.outcome)
        assertEquals("no reply in 1004 ms", silent.detail)

        val replied = DesktopLinkEnvironment.classifyPing(0,
            "Reply from 192.168.1.77: bytes=32 time=1ms TTL=128", 3)
        assertEquals(PingOutcome.REPLIED, replied.outcome)
        assertEquals("3 ms", replied.detail)
        // A localised Windows still prints TTL=, so a reply is recognised even
        // when the exit code is lost in translation.
        assertEquals(PingOutcome.REPLIED,
            DesktopLinkEnvironment.classifyPing(1, "Antwort von 192.168.1.77: Bytes=32 Zeit<1ms TTL=128", 3).outcome)
    }

    @Test
    fun `a host that answers ping is REPLIED, one that cannot exist is never BLOCKED by accident`() {
        assertEquals(PingOutcome.REPLIED, env.ping("127.0.0.1").outcome)
        // A syntactically impossible address is the one input we can call unreachable outright.
        assertEquals(PingOutcome.UNREACHABLE, env.ping("not a host").outcome)
    }
}
