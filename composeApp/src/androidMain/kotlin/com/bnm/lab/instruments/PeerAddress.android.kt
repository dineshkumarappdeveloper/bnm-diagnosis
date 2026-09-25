package com.bnm.lab.instruments

import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.SocketAddress
import io.ktor.network.sockets.toJavaAddress

/** Same as desktop — Android is a JVM and an analyzer listener is desktop-only anyway. */
actual fun peerIpOf(address: SocketAddress?): String? {
    val inet = (address as? InetSocketAddress)?.toJavaAddress() as? java.net.InetSocketAddress ?: return null
    return inet.address?.hostAddress
}
