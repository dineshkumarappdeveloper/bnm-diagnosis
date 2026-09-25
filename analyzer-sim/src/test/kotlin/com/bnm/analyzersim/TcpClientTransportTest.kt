package com.bnm.analyzersim

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A real loopback round trip.
 *
 * Every other test in this module swaps the socket out, which is right for
 * testing fault logic — and is exactly how the transport once shipped dialling
 * port 0 (`java.net.Socket` has its own `port` property, which shadowed the
 * constructor argument inside an `apply` block). Nothing but an actual
 * connection catches that, so this test makes one.
 */
class TcpClientTransportTest {

    /** A listener that answers with [reply] and hands back what it was sent. */
    private class Host(private val reply: ByteArray? = null) : AutoCloseable {
        private val server = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress("127.0.0.1", 0)) }
        val port: Int get() = server.localPort
        val received = ArrayBlockingQueue<ByteArray>(8)

        init {
            thread(isDaemon = true, name = "test-host") {
                runCatching {
                    while (true) {
                        val socket = server.accept()
                        val buffer = java.io.ByteArrayOutputStream()
                        val chunk = ByteArray(4096)
                        while (true) {
                            val n = socket.getInputStream().read(chunk)
                            if (n < 0) break
                            buffer.write(chunk, 0, n)
                            if (buffer.toByteArray().decodeToString().indexOf(SimMllp.EB) >= 0) break
                        }
                        received.offer(buffer.toByteArray())
                        reply?.let { socket.getOutputStream().write(it); socket.getOutputStream().flush() }
                        Thread.sleep(50)
                        socket.close()
                    }
                }
            }
        }

        fun await(): ByteArray = assertNotNull(received.poll(10, TimeUnit.SECONDS), "nothing arrived at the host")
        override fun close() { runCatching { server.close() } }
    }

    private val ack = SimMllp.wrap(
        "MSH|^~\\&|BNM Lab||BC-5130|Mindray|20260101090001||ACK^R01|A1|P|2.3.1\rMSA|AA|SIM001\r")

    @Test
    fun `it dials the port it was given and the bytes arrive intact`() {
        Host(ack).use { host ->
            val frame = MindrayFrames.frame(SampleSpec(specimenId = "ACC-S1-00042", timestamp = "20260101090000"))
            TcpClientTransport("127.0.0.1", host.port).use { transport ->
                assertTrue(transport.describe.contains("-> 127.0.0.1:${host.port}"), transport.describe)
                transport.write(frame)
                val reply = transport.readReply(10_000)
                val parsed = assertNotNull(SimMllp.readAck(reply.decodeToString()), "no ACK came back")
                assertEquals("AA", parsed.code)
                assertTrue(parsed.accepted)
            }
            assertTrue(host.await().contentEquals(frame), "the host did not get the frame byte for byte")
        }
    }

    @Test
    fun `a whole run goes through the real socket and reports the ACK`() {
        Host(ack).use { host ->
            val out = RecordingPrinter()
            val code = runCli(
                listOf("mindray", "--host", "127.0.0.1", "--port", host.port.toString(), "--id", "ACC-S1-00042"),
                printerFor = { out },
            )
            assertEquals(0, code, out.text)
            assertTrue(out.text.contains("MSA|AA"), out.text)
            assertTrue(host.await().isNotEmpty())
        }
    }

    @Test
    fun `a port nobody is listening on is explained, not stack-traced`() {
        val dead = ServerSocket(0).use { it.localPort }     // taken and released: nothing is there now
        val out = RecordingPrinter()
        val code = runCli(listOf("mindray", "--host", "127.0.0.1", "--port", dead.toString()), printerFor = { out })
        assertEquals(1, code)
        assertTrue(out.text.contains("Connection refused"), out.text)
        assertTrue(out.text.contains("Instruments screen"), "the message should say where to look:\n${out.text}")
    }

    @Test
    fun `hang holds the connection open without sending a byte`() {
        Host().use { host ->
            val out = RecordingPrinter()
            val code = runCli(
                listOf("mindray", "--host", "127.0.0.1", "--port", host.port.toString(), "--hang", "--interval", "0.3"),
                printerFor = { out },
            )
            assertEquals(0, code)
            assertTrue(out.text.contains("without sending anything"), out.text)
            // The host records the connection (it saw an accept and then an EOF);
            // what matters is that not one byte came with it.
            assertEquals(0, host.received.poll(5, TimeUnit.SECONDS)?.size ?: 0, "--hang sent something")
        }
    }

    @Test
    fun `a burst really does open several connections at once`() {
        Host(ack).use { host ->
            val out = RecordingPrinter()
            val code = runCli(
                listOf("mindray", "--host", "127.0.0.1", "--port", host.port.toString(), "--burst", "3",
                    "--ack-timeout", "10"),
                printerFor = { out },
            )
            assertEquals(0, code, out.text)
            repeat(3) { assertTrue(host.await().isNotEmpty(), "connection ${it + 1} sent nothing") }
        }
    }

    @Test
    fun `an unconnected socket is never left behind when the host hangs up early`() {
        // The host closes without answering; the transport must return what it
        // has rather than throw, so a run reports "no ACK" instead of crashing.
        val server = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
        thread(isDaemon = true) { runCatching { server.accept().close() } }
        TcpClientTransport("127.0.0.1", server.localPort).use { transport ->
            runCatching { transport.write("x".toByteArray()) }      // may or may not fail; either is fine
            assertEquals(0, transport.readReply(500).size)
        }
        server.close()
    }
}
