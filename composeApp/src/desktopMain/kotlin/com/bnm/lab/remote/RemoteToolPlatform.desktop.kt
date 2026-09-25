package com.bnm.lab.remote

import com.bnm.lab.db.CHAT_DB_NAME
import com.bnm.lab.db.appDataDir
import com.bnm.lab.diagnostics.DesktopDiagnostics
import com.fazecast.jSerialComm.SerialPort
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Desktop answers for [RemoteToolPlatform] — java.base for disk, network and
 * time, the activity-log folder [DesktopDiagnostics] writes to, and jSerialComm
 * for the ports. Nothing here needs a runtime module the jlink image lacks
 * (no java.lang.management: uptime comes from ProcessHandle).
 */
class DesktopRemoteToolPlatform(
    private val logDir: File = DesktopDiagnostics.logDir,
    private val dataDir: File = appDataDir(),
) : RemoteToolPlatform {

    override fun environmentLine(): String = DesktopDiagnostics.environmentLine()

    override fun localIpv4(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nic -> nic.inetAddresses.toList().filterIsInstance<Inet4Address>().map { it.hostAddress } }
            .distinct()
    }.getOrDefault(emptyList())

    override fun diskFreeBytes(): Long? = runCatching { dataDir.usableSpace }.getOrNull()

    override fun dbSizeBytes(): Long? = runCatching {
        val main = File(dataDir, CHAT_DB_NAME)
        if (!main.exists()) null
        else main.length() + File(dataDir, "$CHAT_DB_NAME-wal").let { if (it.exists()) it.length() else 0L }
    }.getOrNull()

    override fun uptimeSeconds(): Long? = runCatching {
        ProcessHandle.current().info().startInstant().map { Duration.between(it, Instant.now()).seconds }.orElse(null)
    }.getOrNull()

    override fun timezoneId(): String = ZoneId.systemDefault().id

    override fun localTimeIso(): String = ZonedDateTime.now().toOffsetDateTime().toString()

    /**
     * `bnmlab-yyyy-MM-dd.log` for [day], else the newest log file. The files
     * are redacted line by line as they are written (LogRedactor), so nothing
     * further is stripped here. Null when the folder has no log at all.
     */
    override fun logTail(lines: Int, day: String?): String? {
        val files = (logDir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.startsWith("$LOG_PREFIX-") && it.name.endsWith(".log") }
        val file = when {
            day != null -> files.firstOrNull { it.name == "$LOG_PREFIX-$day.log" }
            else -> files.maxByOrNull { it.name }
        } ?: return null
        val all = runCatching { file.readLines() }.getOrDefault(emptyList())
        return all.takeLast(lines.coerceIn(1, MAX_LINES)).joinToString("\n")
    }

    override fun serialPorts(): List<SerialPortInfo> = runCatching {
        SerialPort.getCommPorts().map { SerialPortInfo(it.systemPortName, it.descriptivePortName) }
    }.getOrDefault(emptyList())

    /** Open, close, nothing read — enough to tell "in use / missing" from "free". */
    override fun probeSerial(portName: String): String? {
        val port = runCatching { SerialPort.getCommPort(portName) }.getOrNull()
            ?: return "No such port"
        if (!port.openPort()) return "Could not open $portName — in use by another program, or unplugged"
        runCatching { port.closePort() }
        return null
    }

    private companion object {
        /** Same prefix DesktopDiagnostics uses for its rolling files. */
        const val LOG_PREFIX = "bnmlab"
        const val MAX_LINES = 500
    }
}

actual fun platformRemoteToolPlatform(): RemoteToolPlatform = DesktopRemoteToolPlatform()
