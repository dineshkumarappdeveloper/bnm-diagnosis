package com.bnm.lab.instruments

import io.ktor.network.sockets.SocketAddress

/** iOS runs no analyzer listener; nothing accepts a connection to report a peer for. */
actual fun peerIpOf(address: SocketAddress?): String? = null
