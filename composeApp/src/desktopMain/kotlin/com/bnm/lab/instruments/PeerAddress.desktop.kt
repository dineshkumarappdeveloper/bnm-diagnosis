package com.bnm.lab.instruments

import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.SocketAddress
import io.ktor.network.sockets.toJavaAddress

/** `getAddress()` hands back the address the socket already carries; only `getHostName()` resolves. */
actual fun peerIpOf(address: SocketAddress?): String? {
    val inet = (address as? InetSocketAddress)?.toJavaAddress() as? java.net.InetSocketAddress ?: return null
    return inet.address?.hostAddress
}
