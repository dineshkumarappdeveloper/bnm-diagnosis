package com.bnm.diagnosis.instruments

import com.fazecast.jSerialComm.SerialPort
import com.fazecast.jSerialComm.SerialPortDataListener
import com.fazecast.jSerialComm.SerialPortEvent

actual fun serialSupported(): Boolean = true

actual fun listSerialPorts(): List<String> =
    runCatching { SerialPort.getCommPorts().map { it.systemPortName } }
        .getOrDefault(emptyList())

actual fun openSerialPort(
    portName: String,
    baud: Int,
    onData: (ByteArray) -> Unit,
    onClosed: (String?) -> Unit,
): SerialHandle? {
    val port = runCatching { SerialPort.getCommPort(portName) }.getOrNull() ?: return null
    port.setComPortParameters(baud, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY)
    port.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED)
    if (!port.openPort()) return null
    port.addDataListener(object : SerialPortDataListener {
        override fun getListeningEvents(): Int =
            SerialPort.LISTENING_EVENT_DATA_AVAILABLE or SerialPort.LISTENING_EVENT_PORT_DISCONNECTED

        override fun serialEvent(event: SerialPortEvent) {
            when (event.eventType) {
                SerialPort.LISTENING_EVENT_DATA_AVAILABLE -> {
                    val available = port.bytesAvailable()
                    if (available <= 0) return
                    val buf = ByteArray(available)
                    val read = port.readBytes(buf, available)
                    if (read > 0) onData(if (read == buf.size) buf else buf.copyOf(read))
                }
                SerialPort.LISTENING_EVENT_PORT_DISCONNECTED -> {
                    runCatching { port.removeDataListener(); port.closePort() }
                    onClosed("Serial device disconnected")
                }
            }
        }
    })
    return object : SerialHandle {
        override fun close() {
            runCatching { port.removeDataListener() }
            runCatching { port.closePort() }
        }
    }
}
