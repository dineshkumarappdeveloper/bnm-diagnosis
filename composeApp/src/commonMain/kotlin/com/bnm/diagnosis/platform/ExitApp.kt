package com.bnm.diagnosis.platform

/**
 * Can this platform close the app from inside it?
 *
 * True on the desktop, where a lab PC's operator expects a way out of a
 * full-screen app that has no visible window chrome on some setups. False on
 * Android and iOS, where the system owns the back stack and a self-quit button
 * is both unnecessary and against the platform's conventions — the caller
 * hides the control rather than showing one that does nothing.
 */
expect val canExitApp: Boolean

/** Close the app. No-op where [canExitApp] is false. */
expect fun exitApp()
