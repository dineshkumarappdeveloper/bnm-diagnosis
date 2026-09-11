package com.bnm.lab.instruments

// Analyzers plug into the lab PC — Android seats read the synced results.
// (USB-OTG serial could land later; until then this is an honest stub.)
actual fun serialSupported(): Boolean = false

actual fun listSerialPorts(): List<String> = emptyList()

actual fun openSerialPort(
    portName: String,
    baud: Int,
    onData: (ByteArray) -> Unit,
    onClosed: (String?) -> Unit,
): SerialHandle? = null
