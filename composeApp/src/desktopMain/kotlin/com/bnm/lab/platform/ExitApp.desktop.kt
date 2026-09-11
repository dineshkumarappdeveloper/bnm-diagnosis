package com.bnm.lab.platform

actual val canExitApp: Boolean = true

actual fun exitApp() {
    // A hard exit, like the update path: every SQLDelight write is its own
    // committed transaction, and Ktor's engine and the printer pool are
    // non-daemon threads that would otherwise keep the JVM (and the window)
    // alive after the last frame.
    kotlin.system.exitProcess(0)
}
