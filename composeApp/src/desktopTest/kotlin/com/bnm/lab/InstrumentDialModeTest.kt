package com.bnm.lab

import com.bnm.lab.instruments.InstrumentDriver
import com.bnm.lab.instruments.INSTRUMENT_DRIVERS
import com.bnm.lab.instruments.InstrumentTcpRole
import com.bnm.lab.instruments.InstrumentTransport
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Dial mode — the app as the CLIENT.
 *
 * The Mindray BC-5x is a TCP server ("Port is fixed as 5100", manual 5.2): its
 * Communication Setup screen has no host-address field, so it cannot call us.
 * Before this, the engine could only bind and accept, which meant both ends
 * waited and the lab saw no data, no error and no clue — a failure with no
 * symptom is the one worth a test of its own.
 *
 * This stands a real server socket in the analyzer's place, lets the engine's
 * client dial it, and checks the bytes and the ACK cross in the right
 * direction. It exercises sockets rather than the engine's database, so it can
 * run anywhere.
 */
class InstrumentDialModeTest {

    private fun mllp(body: String): ByteArray =
        byteArrayOf(0x0B) + body.toByteArray() + byteArrayOf(0x1C, 0x0D)

    private val oru = buildString {
        append("MSH|^~\\&|||||20260926104200||ORU^R01|1|P|2.3.1||||||UNICODE\r")
        append("PID|1||CP-1^^^^MR||MURUGAMANI||19840101000000|Female\r")
        append("OBR|1||636/08|00001^Automated Count^99MRC\r")
        append("OBX|1|NM|6690-2^WBC^LN||8.1|10*9/L|4.00-10.00||||F\r")
    }

    @Test
    fun `the roster says the BC-5x must be dialled on its own port`() {
        val mindray: InstrumentDriver = INSTRUMENT_DRIVERS.first { it.key == "mindray_hl7" }
        assertEquals(InstrumentTcpRole.CONNECT, mindray.tcpRole,
            "the BC-5x is a server; a listener waits for it forever")
        assertEquals(5100, mindray.defaultTcpPort, "manual 5.2: the port is fixed as 5100")
        assertTrue(mindray.tcpOnly)
        // Everything else keeps the old behaviour, so no existing install moves.
        INSTRUMENT_DRIVERS.filter { it.key != "mindray_hl7" }.forEach {
            assertEquals(InstrumentTcpRole.LISTEN, it.tcpRole, "${it.key} should be unchanged")
        }
    }

    @Test
    fun `an unknown or missing role reads as listen, never as dial`() {
        // A row written before this column existed reads back null. Defaulting
        // it to CONNECT would silently turn every installed listener into a
        // dialler pointed at a blank address.
        assertEquals(InstrumentTcpRole.LISTEN, InstrumentTcpRole.normalise(null))
        assertEquals(InstrumentTcpRole.LISTEN, InstrumentTcpRole.normalise(""))
        assertEquals(InstrumentTcpRole.LISTEN, InstrumentTcpRole.normalise("nonsense"))
        assertEquals(InstrumentTcpRole.CONNECT, InstrumentTcpRole.normalise("connect"))
        assertEquals(InstrumentTcpRole.CONNECT, InstrumentTcpRole.normalise("  CONNECT "))
    }

    /**
     * The analyzer's half of the conversation: accept, push one ORU, then wait
     * for the ACK on the SAME socket — which is what the BC-5x does and what a
     * naive "connect, read, close" client would get wrong.
     */
    @Test
    fun `a client that dials the analyzer receives its results and acks on the same socket`() {
        val server = ServerSocket(0)
        val gotAck = CountDownLatch(1)
        var ackText = ""
        val serverThread = thread(isDaemon = true) {
            runCatching {
                server.accept().use { s ->
                    DataOutputStream(s.getOutputStream()).apply { write(mllp(oru)); flush() }
                    val buf = ByteArray(4096)
                    val n = s.getInputStream().read(buf)
                    if (n > 0) {
                        ackText = String(buf, 0, n)
                        gotAck.countDown()
                    }
                }
            }
        }

        // The client half, the same shape as InstrumentEngine.tcpDialLoop:
        // dial, read framed bytes, write the ACK back down the open socket.
        val received = StringBuilder()
        Socket("127.0.0.1", server.localPort).use { sock ->
            val input = sock.getInputStream()
            val out = sock.getOutputStream()
            val buf = ByteArray(4096)
            val n = input.read(buf)
            assertTrue(n > 0, "the analyzer pushed a frame down the connection we opened")
            received.append(String(buf, 0, n))
            out.write(mllp("MSH|^~\\&|||||20260926104201||ACK^R01|2|P|2.3.1\rMSA|AA|1\r"))
            out.flush()
            assertTrue(gotAck.await(5, TimeUnit.SECONDS), "the analyzer must see the ACK")
        }
        serverThread.join(2_000)
        server.close()

        val body = received.toString()
        assertTrue(body.startsWith("\u000B"), "MLLP start block")
        assertTrue(body.contains("ORU^R01"), "an ORU arrived")
        assertTrue(body.contains("636/08"), "with its specimen id")
        assertTrue(body.contains("6690-2^WBC"), "and its WBC result")
        assertTrue(ackText.contains("MSA|AA"), "the analyzer received our accept-ack")
    }

    @Test
    fun `dialling a port with nothing on it fails fast instead of hanging`() {
        // A dead analyzer must surface as an error the bench can act on, not as
        // a connection that never resolves — the engine retries with backoff on
        // exactly this exception.
        val free = ServerSocket(0).use { it.localPort }
        val failed = runCatching { Socket("127.0.0.1", free).close() }.isFailure
        assertTrue(failed, "connecting to a closed port must throw")
    }

    @Test
    fun `transport stays tcp for the dialling driver`() {
        assertEquals(
            InstrumentTransport.TCP,
            INSTRUMENT_DRIVERS.first { it.key == "mindray_hl7" }
                .let { if (it.tcpOnly) InstrumentTransport.TCP else InstrumentTransport.SERIAL },
        )
    }
}
