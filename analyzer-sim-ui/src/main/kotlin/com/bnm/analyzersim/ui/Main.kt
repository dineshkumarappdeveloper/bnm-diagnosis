package com.bnm.analyzersim.ui

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlin.system.exitProcess

/**
 * The window, or the self-test.
 *
 * `--selftest` exists because of a lesson BNM Lab paid for: jlink trims the
 * bundled runtime, `./gradlew run` uses the full JDK, and a module missing from
 * `nativeDistributions.modules` therefore only ever crashes the PACKAGED app,
 * on a client's laptop, on first launch. A flag that opens no window but draws
 * the real screen off-screen makes the installed binary testable from a
 * terminal — and from CI, one day, with no display attached.
 */
fun main(args: Array<String>) {
    if (args.any { it == "--selftest" }) exitProcess(selfTest())

    application {
        val scope = rememberCoroutineScope()
        val state = remember { SimState(PresetStore(), scope) }
        // NOT inside the remember above. Enumerating serial ports is
        // jSerialComm's first call: it unpacks a native library into the temp
        // directory and walks the OS device tree, which on a client's Windows
        // laptop takes seconds. Done in the composition it ran on the AWT thread
        // BEFORE the Window below existed, so a double-clicked installer icon
        // drew nothing at all and the engineer launched the app again. As an
        // effect it runs after this pass, and off the UI thread besides.
        LaunchedEffect(Unit) { state.refreshSerialPorts(announce = false) }
        Window(
            onCloseRequest = ::exitApplication,
            title = "BNM Analyzer Simulator",
            state = rememberWindowState(size = DpSize(900.dp, 700.dp)),
        ) {
            SimTheme { SimScreen(state) }
        }
    }
}
