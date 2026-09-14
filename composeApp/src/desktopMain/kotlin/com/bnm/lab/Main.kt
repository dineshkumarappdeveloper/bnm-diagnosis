/**
 * Desktop (JVM) entry point for BNMAdmin.
 *
 * Run:        ./gradlew :composeApp:run
 * Package:    ./gradlew :composeApp:packageDmg                       # macOS .dmg
 *             ./gradlew :composeApp:packageDeb                       # Linux .deb
 *             ./gradlew :composeApp:packageMsi                       # Windows .msi (build on Windows)
 *             ./gradlew :composeApp:packageDistributionForCurrentOS
 *
 * Auth: full Google Sign-In via OAuth 2.0 + PKCE in a system browser.
 * See `auth/FirebaseAuthManager.desktop.kt` for flow + one-time Google
 * Cloud Console setup (Authorized redirect URI: http://127.0.0.1).
 *
 * Override the OAuth client (e.g. when using a "Desktop application" client
 * instead of the Web one) via env var:
 *   GOOGLE_OAUTH_CLIENT_ID='<your-client-id>' ./gradlew :composeApp:run
 */
package com.bnm.lab

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.LocalWindowExceptionHandlerFactory
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.bnm.lab.api.ApiClient
import com.bnm.lab.diagnostics.AppLog
import com.bnm.lab.diagnostics.DesktopDiagnostics
import com.bnm.lab.diagnostics.FatalWindowError
import com.bnm.lab.diagnostics.SupportReporter
import com.bnm.lab.diagnostics.SupportUi

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    // FIRST, before any other code runs: a failure in startup or in the first
    // frame of the UI is exactly the kind a lab cannot describe over the phone.
    DesktopDiagnostics.install()
    // Full HTTP bodies only for a developer who asks for them — and only ever
    // to the console, never into a log file that gets emailed.
    ApiClient.consoleHttpBodies = System.getenv("BNM_HTTP_DEBUG") != null

    application {
        val state = rememberWindowState(size = DpSize(1280.dp, 800.dp))
        // Replaces Compose's default "exception in window" handling: record the
        // crash and offer the support report before closing — see FatalWindowError.
        CompositionLocalProvider(
            LocalWindowExceptionHandlerFactory provides WindowExceptionHandlerFactory { window ->
                WindowExceptionHandler { error -> FatalWindowError.handle(window, error) { exitApplication() } }
            },
        ) {
        Window(
            onCloseRequest = {
                AppLog.i("Lifecycle", "window closed by user")
                exitApplication()
            },
            state = state,
            title = "BNM Lab",
        ) {
            // The Help menu works from every screen — including activation and
            // staff sign-in, where Settings cannot be reached. On macOS it lives
            // in the system menu bar; on Windows and Linux, in the window.
            MenuBar {
                Menu("Help") {
                    Item("Report a problem…", onClick = { SupportUi.open() })
                    Item("Open logs folder", onClick = {
                        if (!SupportReporter.openLogsFolder()) SupportUi.open()
                    })
                }
            }
            LaunchedEffect(Unit) {
                SupportReporter.pendingCrash()?.let { SupportUi.open(crashNotice = it) }
            }
            App()
        }
        }
    }
}
