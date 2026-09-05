package com.bnm.diagnosis.instruments

// iOS has no serial ports; analyzers talk to the lab PC.
actual fun serialSupported(): Boolean = false

actual fun listSerialPorts(): List<String> = emptyList()

actual fun openSerialPort(
    portName: String,
    baud: Int,
    onData: (ByteArray) -> Unit,
    onClosed: (String?) -> Unit,
): SerialHandle? = null
