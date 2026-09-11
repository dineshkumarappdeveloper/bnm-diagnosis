package com.bnm.diagnosis.platform

/** Apple forbids an app terminating itself. */
actual val canExitApp: Boolean = false

actual fun exitApp() = Unit
