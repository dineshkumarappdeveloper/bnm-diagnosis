package com.bnm.diagnosis.instruments

/**
 * RS-232 access for analyzer interfacing (I0).
 *
 * Desktop is the only real implementation (labs wire analyzers to the lab PC
 * over USB-to-DB9); Android/iOS actuals are honest stubs, same convention as
 * BtPrinter (desktop stub) but mirrored. TCP transport needs none of this —
 * it's ktor-network in commonMain.
 */
interface SerialHandle {
    fun close()
}

/** Whether this platform can open serial ports at all. */
expect fun serialSupported(): Boolean

/** Names of the serial ports visible right now (e.g. COM3, /dev/tty.usbserial-110). */
expect fun listSerialPorts(): List<String>

/**
 * Open [portName] at [baud] (8-N-1, no flow control — what every analyzer in
 * the vendor docs uses) and stream received bytes to [onData] from a
 * background thread. Returns null when the port can't be opened. [onClosed]
 * fires once if the device disappears (USB unplugged).
 */
expect fun openSerialPort(
    portName: String,
    baud: Int,
    onData: (ByteArray) -> Unit,
    onClosed: (String?) -> Unit,
): SerialHandle?
