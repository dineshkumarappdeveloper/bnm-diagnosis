package com.bnm.lab.remote

/**
 * The bits of `lab.overview`, `logs.tail`, `instruments.ports` and
 * `instruments.probe` that need the operating system: disk, network
 * interfaces, the rolling log files, serial ports. Desktop implements it with
 * java.base / java.desktop / jSerialComm; Android and iOS answer with empty
 * or "not here" values so [RemoteTools] stays in commonMain and compiles
 * everywhere. Tests pass a fake.
 */
interface RemoteToolPlatform {
    /** OS, Java, heap, CPUs, process start — [com.bnm.lab.diagnostics.DesktopDiagnostics.environmentLine] on desktop. */
    fun environmentLine(): String
    /** Non-loopback IPv4 addresses of this PC — what the analyzer must be pointed at. */
    fun localIpv4(): List<String>
    fun diskFreeBytes(): Long?
    /** Size of the SQLite file (+ its WAL sidecar). */
    fun dbSizeBytes(): Long?
    fun uptimeSeconds(): Long?
    fun timezoneId(): String
    /** Local wall-clock time as the lab sees it (ISO with offset). */
    fun localTimeIso(): String
    /** The last [lines] lines of the activity log (already redacted at write); null when there are no log files here. */
    fun logTail(lines: Int, day: String?): String?
    /** Serial ports visible right now: system name → human description. */
    fun serialPorts(): List<SerialPortInfo>
    /** Open then close [portName] — zero reads. Null on success, else a short reason. */
    fun probeSerial(portName: String): String?
}

data class SerialPortInfo(val name: String, val description: String)

expect fun platformRemoteToolPlatform(): RemoteToolPlatform
