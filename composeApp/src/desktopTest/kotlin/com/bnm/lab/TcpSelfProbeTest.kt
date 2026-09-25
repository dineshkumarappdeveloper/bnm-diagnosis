package com.bnm.lab

import com.bnm.lab.instruments.DesktopLinkEnvironment
import java.net.ServerSocket
import java.net.SocketTimeoutException
import kotlin.test.Test
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
        env.localIpv4().forEach { ip -> assertTrue(ip.matches(Regex("""\d+\.\d+\.\d+\.\d+""")) && ip != "127.0.0.1", ip) }
    }
}
