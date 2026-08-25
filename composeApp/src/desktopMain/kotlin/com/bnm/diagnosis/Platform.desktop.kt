package com.bnm.diagnosis

class JvmDesktopPlatform : Platform {
    override val name: String =
        "JVM ${System.getProperty("java.version")} (${System.getProperty("os.name")})"
}

actual fun getPlatform(): Platform = JvmDesktopPlatform()
