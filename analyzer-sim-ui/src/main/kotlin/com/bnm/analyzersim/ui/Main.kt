package com.bnm.analyzersim.ui

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
        val state = remember { SimState(PresetStore(), scope).also { it.refreshSerialPorts() } }
        Window(
            onCloseRequest = ::exitApplication,
            title = "BNM Analyzer Simulator",
            state = rememberWindowState(size = DpSize(900.dp, 700.dp)),
        ) {
            SimTheme { SimScreen(state) }
        }
    }
}
