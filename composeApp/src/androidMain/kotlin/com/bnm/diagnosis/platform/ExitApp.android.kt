package com.bnm.diagnosis.platform

/** Android owns the back stack; an in-app quit button is not the convention. */
actual val canExitApp: Boolean = false

actual fun exitApp() = Unit
