package com.bnm.diagnosis.connectivity

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

actual class ConnectivityMonitor actual constructor() {
    // Desktop is typically wired; assume online. If actually offline, sends fail
    // and stay queued for retry. (A periodic reachability probe can be added later.)
    actual val isOnline: Flow<Boolean> = flowOf(true)
}
