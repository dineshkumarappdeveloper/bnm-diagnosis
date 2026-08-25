package com.bnm.diagnosis.connectivity

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.Flow

/**
 * Emits the device's online/offline state. Used to auto-drain the chat outbox
 * when connectivity is (re)gained.
 *
 * - Android: ConnectivityManager default-network callback.
 * - iOS: NWPathMonitor (Network framework).
 * - Desktop: assumed online (wired); sends fail-and-retry if not.
 */
expect class ConnectivityMonitor() {
    val isOnline: Flow<Boolean>
}

/** App-wide connectivity, provided in App.kt — screens gate online-only actions
 *  (e.g. payment links) on `LocalConnectivity.current.isOnline`. */
val LocalConnectivity = staticCompositionLocalOf<ConnectivityMonitor> {
    error("LocalConnectivity not provided")
}
