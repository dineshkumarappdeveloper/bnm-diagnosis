package com.bnm.analyzersim.ui

import com.bnm.analyzersim.SerialTransport
import com.bnm.analyzersim.TcpClientTransport
import com.bnm.analyzersim.explain
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Whether the link came up, said in one sentence a lab technician can act on. */
data class LinkTestResult(val ok: Boolean, val message: String)

/**
 * Open the link, close it, say what happened.
 *
 * Deliberately sends nothing. Half the "it will not link" calls are answered
 * before a single frame is built — the app is not running, the instrument row
 * is disabled, the port is a digit out, or Windows Firewall is eating the
 * connection — and an engineer should be able to settle that in two seconds
 * without filing a test result into a live lab's records.
 *
 * Blocking: call it off the UI thread.
 */
object LinkTest {

    fun check(form: SimForm): LinkTestResult {
        val serial = form.transport == TransportKind.SERIAL && form.serialAllowed
        return if (serial) checkSerial(form) else checkTcp(form)
    }

    private fun checkTcp(form: SimForm): LinkTestResult {
        val host = form.host.trim()
        val port = form.port.trim().toIntOrNull() ?: form.analyzer.defaultPort
        return try {
            TcpClientTransport(host, port, connectTimeoutMs = 3_000).close()
            LinkTestResult(true, "Reached BNM Lab at $host:$port. Nothing was sent.")
        } catch (e: ConnectException) {
            LinkTestResult(false, "Nothing is listening on $port — is BNM Lab running, is the " +
                "instrument row enabled, and is the Windows firewall allowing it?")
        } catch (e: SocketTimeoutException) {
            LinkTestResult(false, "No answer from $host:$port within 3s. That is usually a firewall " +
                "between this PC and the lab PC, not a wrong port.")
        } catch (e: UnknownHostException) {
            LinkTestResult(false, "Cannot find '$host'. Check the address — an IP is safer than a " +
                "machine name on a lab network.")
        } catch (e: NoRouteToHostException) {
            LinkTestResult(false, "No route to $host. The two machines are not on the same network.")
        } catch (e: Exception) {
            LinkTestResult(false, explain(e))
        }
    }

    private fun checkSerial(form: SimForm): LinkTestResult {
        val name = form.serialPort.trim()
        val baud = form.baud.trim().toIntOrNull() ?: 115200
        return try {
            SerialTransport(name, baud).close()
            // Worth spelling out: a one-way cable cannot tell you anything about
            // the other end, and an engineer who reads "OK" as "BNM Lab is
            // listening" will chase the wrong fault next.
            LinkTestResult(true, "Opened $name at $baud 8-N-1. A cable is one-way, so this proves the " +
                "adapter on THIS machine — not that BNM Lab is listening on the other end.")
        } catch (e: Exception) {
            LinkTestResult(false, "Could not open $name — it is already in use (a terminal program still " +
                "holding it?), or the adapter is unplugged.")
        }
    }

    /** The ports visible right now, for the picker's Refresh. */
    fun serialPorts(): List<String> = SerialTransport.available()
}
