package com.bnm.lab.remote

import kotlinx.coroutines.delay

/**
 * The engine's two clocks and its sleep, behind one seam so a test can drive
 * time: expiry is measured on the MONOTONIC clock (winding the PC's clock
 * back must not stretch a session), the wall clock is for display, the hello
 * frame and the ±5-minute request window.
 */
interface RemoteClock {
    fun monotonicMs(): Long
    fun wallMs(): Long
    suspend fun sleep(ms: Long)
}

object SystemRemoteClock : RemoteClock {
    override fun monotonicMs(): Long = System.nanoTime() / 1_000_000L
    override fun wallMs(): Long = System.currentTimeMillis()
    override suspend fun sleep(ms: Long) = delay(ms)
}

/** One open connection to the relay: text frames in, text frames out. */
interface RemoteTransport {
    suspend fun send(text: String)
    /** The next text frame, or null once the socket is closed (by either side). */
    suspend fun receive(): String?
    suspend fun close()
}

/** Opens connections; the real one speaks WebSocket over Ktor, tests script frames. */
fun interface RemoteTransportFactory {
    /** Throws when the relay cannot be reached — the engine turns it into a short reason. */
    suspend fun connect(url: String): RemoteTransport
}
