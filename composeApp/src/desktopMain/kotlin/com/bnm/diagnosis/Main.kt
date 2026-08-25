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
package com.bnm.diagnosis

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    val state = rememberWindowState(size = DpSize(1280.dp, 800.dp))
    Window(
        onCloseRequest = ::exitApplication,
        state = state,
        title = "BNM Diagnosis",
    ) {
        App()
    }
}
