package com.bnm.lab.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import java.util.concurrent.TimeUnit

/**
 * The real transport: one WebSocket to the relay per connect, over the same
 * OkHttp engine the API client uses. Its own client on purpose — the API
 * client carries JSON content negotiation and request timeouts that do not
 * belong on a long-lived socket. OkHttp keeps the socket alive with its own
 * pings, so a NAT that drops idle connections cannot silently strand a session.
 */
internal class KtorWebSocketTransportFactory : RemoteTransportFactory {

    private val client: HttpClient by lazy {
        HttpClient(OkHttp) {
            engine {
                config {
                    connectTimeout(15, TimeUnit.SECONDS)
                    pingInterval(20, TimeUnit.SECONDS)
                }
            }
            // No `maxFrameSize` here: the OkHttp engine refuses the switch outright
            // ("Max frame size switch is not supported in OkHttp engine") and the
            // socket never opens. The relay closes anything over 1 MiB (1009) and
            // RemoteRpcCore caps its own replies at 900 KB, so nothing is lost.
            install(WebSockets)
        }
    }

    override suspend fun connect(url: String): RemoteTransport = Session(client.webSocketSession(url))

    private class Session(private val ws: DefaultClientWebSocketSession) : RemoteTransport {
        override suspend fun send(text: String) = ws.send(Frame.Text(text))

        override suspend fun receive(): String? {
            while (true) {
                val frame = try {
                    ws.incoming.receive()
                } catch (_: ClosedReceiveChannelException) {
                    return null
                }
                when (frame) {
                    is Frame.Text -> return frame.readText()
                    is Frame.Close -> return null
                    else -> continue // ping/pong/binary — not part of this protocol
                }
            }
        }

        override suspend fun close() {
            runCatching { ws.close(CloseReason(CloseReason.Codes.NORMAL, "end")) }
        }
    }
}
