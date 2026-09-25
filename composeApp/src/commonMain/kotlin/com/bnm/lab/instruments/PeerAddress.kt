package com.bnm.lab.instruments

import io.ktor.network.sockets.SocketAddress

/**
 * The connecting analyzer's numeric address — never its NAME.
 *
 * Ktor's `InetSocketAddress.hostname` is `java.net.InetSocketAddress.getHostName()`,
 * which asks the resolver to turn the peer's IP back into a host name (ktor's own
 * source warns about it). A lab LAN usually has no DNS server, or has one that is
 * unreachable because the PC is offline, and the lookup then blocks for as long as
 * the resolver takes. It runs on the accept loop, so every analyzer connection
 * waits behind it: the analyzer gives up before its ACK arrives and the result is
 * lost — the exact fault the Link check exists to explain.
 *
 * The address is already resolved by the time a connection is accepted, so reading
 * it numerically costs nothing and cannot block.
 */
expect fun peerIpOf(address: SocketAddress?): String?
