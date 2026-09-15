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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
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
import com.bnm.lab.backup.BackupCli
import com.bnm.lab.backup.BackupService
import com.bnm.lab.backup.SingleInstance
import com.bnm.lab.db.appDataDir
import com.bnm.lab.diagnostics.AppLog
import com.bnm.lab.diagnostics.DesktopDiagnostics
import com.bnm.lab.diagnostics.FatalWindowError
import com.bnm.lab.diagnostics.SupportReporter
import com.bnm.lab.diagnostics.SupportUi
import com.bnm.lab.screens.backup.BackupExitFlowUi
import com.bnm.lab.ui.theme.AppTheme
import com.bnm.lab.ui.theme.ThemeManager

@OptIn(ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    // Headless: `--export-backup <file.bnmlab> <out.db> [--key …|--code …]`
    // decrypts a Backup pendrive generation to a plain SQLite file for support,
    // with no window, no diagnostics and no engine. Exits with its own code.
    BackupCli.run(args)?.let { kotlin.system.exitProcess(it) }
    // FIRST, before any other code runs: a failure in startup or in the first
    // frame of the UI is exactly the kind a lab cannot describe over the phone.
    DesktopDiagnostics.install()
    // One BNM Lab per data directory: a second copy would run a second backup
    // engine against the same pendrive, and a staged restore could not swap the
    // database while the first copy still holds it open.
    if (!SingleInstance.acquire(appDataDir())) SingleInstance.refuseAndExit()
    // The Backup pendrive engine ticks from here on — before the window exists —
    // but does nothing until DriverFactory has opened the database. Its
    // shutdown hook only persists what is unsaved and removes a half-written
    // file; the long close-window flush is the window's job, not the JVM's.
    BackupService.shared.start()
    BackupService.shared.installShutdownHook()
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
        // Closing the window is the ONE place a last backup generation is
        // written (the JVM shutdown hook never snapshots): unsaved changes with
        // the pendrive present are flushed behind a small overlay; unsaved
        // changes with no pendrive get a "Close anyway / Cancel" question. The
        // decision itself is BackupExitFlow's, from the engine's status alone.
        var closing by remember { mutableStateOf(false) }
        Window(
            onCloseRequest = {
                AppLog.i("Lifecycle", "window close requested")
                // A letterhead or prefix edit in the last minute is a preference,
                // not a database write — hash it now so it counts as unsaved.
                BackupService.shared.checkPrefsBeforeClose()
                closing = true
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
            Box(Modifier.fillMaxSize()) {
                App()
                if (closing) {
                    // Same theme preference App() reads, so the overlay and the
                    // question match the window they sit on.
                    val themeManager = remember { ThemeManager() }
                    val themeChoice by themeManager.choice.collectAsState()
                    AppTheme(themeChoice = themeChoice) {
                        BackupExitFlowUi(
                            controller = BackupService.shared,
                            onExit = {
                                AppLog.i("Lifecycle", "window closed by user")
                                exitApplication()
                            },
                            onCancel = { closing = false },
                        )
                    }
                }
            }
        }
        }
    }
}
