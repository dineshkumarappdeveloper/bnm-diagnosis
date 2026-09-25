package com.bnm.analyzersim

import com.fazecast.jSerialComm.SerialPort
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The two ways an analyzer is wired to the lab PC.
 *
 * Both directions matter and they are not symmetric: over TCP the ANALYZER
 * dials the app (the app listens on 5500), so the simulator is a client, not a
 * server. Over RS-232 it just pushes bytes down the cable and never hears
 * anything back. Getting that backwards is the single commonest reason a
 * bench rehearsal "works" and the real install does not.
 */
interface AnalyzerTransport : AutoCloseable {
    /** One line for the transcript: what link this is, in the engineer's words. */
    val describe: String

    fun write(bytes: ByteArray)

    /**
     * Bytes the host wrote back within [timeoutMs]; empty when it said nothing
     * (or when this transport has no read path at all).
     */
    fun readReply(timeoutMs: Long): ByteArray
}

/** How transports are made for a run, and whether one sample gets one link. */
interface TransportFactory {
    /** TCP analyzers open a fresh connection per sample; a serial cable is opened once. */
    val perSample: Boolean
    val describe: String
    fun open(): AnalyzerTransport
}

/**
 * Dials the app's listener, exactly as the analyzer does: connect, send one
 * message, wait for the ACK on the same socket, hang up.
 */
class TcpClientTransport(
    private val host: String,
    private val port: Int,
    connectTimeoutMs: Int = 5_000,
) : AnalyzerTransport {

    private val socket = Socket()

    init {
        // Spelled out rather than configured inside an `apply` block: java.net.Socket
        // has its own `port` property, which would shadow the constructor argument and
        // quietly dial port 0.
        socket.tcpNoDelay = true               // a 200-byte Mispa frame must not wait for Nagle
        socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
    }

    override val describe: String =
        "TCP ${socket.localAddress.hostAddress}:${socket.localPort} -> $host:$port"

    override fun write(bytes: ByteArray) {
        socket.getOutputStream().write(bytes)
        socket.getOutputStream().flush()
    }

    override fun readReply(timeoutMs: Long): ByteArray {
        if (timeoutMs <= 0) return ByteArray(0)
        socket.soTimeout = timeoutMs.toInt()
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(4096)
        return try {
            while (true) {
                val n = socket.getInputStream().read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                // An MLLP block is complete at the end-of-block byte; stop there
                // rather than sitting out the whole timeout on every sample.
                if (out.toByteArray().decodeToString().indexOf(SimMllp.EB) >= 0) break
            }
            out.toByteArray()
        } catch (_: java.net.SocketTimeoutException) {
            out.toByteArray()
        } catch (_: IOException) {
            out.toByteArray()
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }

    companion object : Any() {
        fun factory(host: String, port: Int, connectTimeoutMs: Int = 5_000) = object : TransportFactory {
            override val perSample = true
            override val describe = "TCP client -> $host:$port"
            override fun open(): AnalyzerTransport = TcpClientTransport(host, port, connectTimeoutMs)
        }
    }
}

/**
 * RS-232 at 8-N-1, no flow control — the settings every analyzer LIS document
 * in this repo specifies, and the same jSerialComm the app itself uses, so
 * "the cable works with the simulator" means the same thing on both sides.
 *
 * One-way by nature: [readReply] returns whatever happens to be in the receive
 * buffer, which for the Mispa protocol is nothing at all.
 */
class SerialTransport(portName: String, private val baud: Int) : AnalyzerTransport {

    private val port: SerialPort = SerialPort.getCommPort(portName).apply {
        setComPortParameters(baud, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY)
        setFlowControl(SerialPort.FLOW_CONTROL_DISABLED)
        setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 1_000, 2_000)
    }

    init {
        if (!port.openPort()) {
            error("Could not open $portName — already in use, or the adapter is unplugged")
        }
    }

    override val describe: String = "serial ${port.systemPortName} @ $baud 8-N-1 (${port.descriptivePortName})"

    override fun write(bytes: ByteArray) {
        port.outputStream.write(bytes)
        port.outputStream.flush()
    }

    override fun readReply(timeoutMs: Long): ByteArray {
        if (timeoutMs <= 0) return ByteArray(0)
        val deadline = System.currentTimeMillis() + timeoutMs
        val out = java.io.ByteArrayOutputStream()
        while (System.currentTimeMillis() < deadline) {
            val available = port.bytesAvailable()
            if (available > 0) {
                val buf = ByteArray(available)
                val read = port.readBytes(buf, available)
                if (read > 0) out.write(buf, 0, read)
            } else {
                Thread.sleep(50)
            }
        }
        return out.toByteArray()
    }

    override fun close() {
        runCatching { port.closePort() }
    }

    companion object {
        /** Port names visible right now, for the CLI's error message and the menu. */
        fun available(): List<String> =
            runCatching { SerialPort.getCommPorts().map { it.systemPortName } }.getOrDefault(emptyList())

        fun factory(portName: String, baud: Int) = object : TransportFactory {
            override val perSample = false          // a cable is opened once and kept
            override val describe = "serial $portName @ $baud"
            private val shared by lazy { SerialTransport(portName, baud) }
            override fun open(): AnalyzerTransport = shared
        }
    }
}

/** Swallows the frame instead of sending it (`--dry-run`); the Sender prints it. */
class NullTransport : AnalyzerTransport {
    override val describe = "dry run — nothing is sent"
    override fun write(bytes: ByteArray) = Unit
    override fun readReply(timeoutMs: Long): ByteArray = ByteArray(0)
    override fun close() {}

    companion object {
        fun factory() = object : TransportFactory {
            override val perSample = true
            override val describe = "dry run"
            override fun open(): AnalyzerTransport = NullTransport()
        }
    }
}
