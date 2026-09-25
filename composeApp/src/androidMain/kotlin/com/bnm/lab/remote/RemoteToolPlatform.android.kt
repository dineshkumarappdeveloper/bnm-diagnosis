package com.bnm.lab.remote

import com.bnm.lab.getPlatform

/** Android never hosts a support session; these answers only keep RemoteTools compiling in commonMain. */
actual fun platformRemoteToolPlatform(): RemoteToolPlatform = object : RemoteToolPlatform {
    override fun environmentLine(): String = getPlatform().name
    override fun localIpv4(): List<String> = emptyList()
    override fun diskFreeBytes(): Long? = null
    override fun dbSizeBytes(): Long? = null
    override fun uptimeSeconds(): Long? = null
    override fun timezoneId(): String = java.util.TimeZone.getDefault().id
    override fun localTimeIso(): String = kotlin.time.Clock.System.now().toString()
    override fun logTail(lines: Int, day: String?): String? = null
    override fun serialPorts(): List<SerialPortInfo> = emptyList()
    override fun probeSerial(portName: String): String? = "Serial ports are only available on the lab PC"
}
