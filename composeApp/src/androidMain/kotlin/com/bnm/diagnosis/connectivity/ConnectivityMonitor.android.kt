package com.bnm.diagnosis.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

private var connContext: Context? = null

/** Called once from MainActivity. Mirrors the other init*Context holders. */
fun initConnectivityContext(context: Context) {
    connContext = context.applicationContext
}

actual class ConnectivityMonitor actual constructor() {
    actual val isOnline: Flow<Boolean> = callbackFlow {
        val cm = connContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) {
            // No context/service — assume online so sends still attempt.
            trySend(true)
            awaitClose { }
            return@callbackFlow
        }

        fun hasInternet(): Boolean {
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

        trySend(hasInternet())

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(true) }
            override fun onLost(network: Network) { trySend(hasInternet()) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
            }
        }
        // registerDefaultNetworkCallback requires API 24+ — minSdk is 24.
        cm.registerDefaultNetworkCallback(callback)
        awaitClose { cm.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}
