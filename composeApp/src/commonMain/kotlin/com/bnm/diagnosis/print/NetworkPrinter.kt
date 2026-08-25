package com.bnm.diagnosis.print

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout

/**
 * Raw **ESC/POS over TCP** printing for LAN thermal printers (TVS RP3200 LITE,
 * Epson TM-*, most 58/80mm receipt printers). The printer is plugged into the
 * network via Ethernet and listens on the JetDirect/raw port (9100 by default).
 *
 * This is the only printing transport that works uniformly on iOS, Android,
 * macOS and Desktop — Ktor's `ktor-network` sockets are multiplatform, so this
 * is a single shared implementation (no AirPrint, no per-OS driver, no USB).
 */
object EscPos {
    private const val ESC = 0x1B
    private const val GS = 0x1D
    private const val LF = 0x0A

    /**
     * Turn rendered monospace receipt text into an ESC/POS byte stream:
     * initialise → body → feed → auto-cut.
     *
     * The `₹` glyph isn't in the default thermal codepage (it would print
     * garbage), so it's stripped — amounts print as plain numbers, which keeps
     * the monospace columns aligned. Non-Latin glyphs fall back to '?'.
     */
    fun encode(text: String, cut: Boolean = true): ByteArray {
        val out = ArrayList<Byte>(text.length + 16)
        fun b(v: Int) { out.add(v.toByte()) }

        b(ESC); b(0x40) // ESC @  → reset/initialise

        // `·` (station-token separator) is ≤255 but lands on a CP437 box glyph —
        // map it to a plain dot so thermal output stays readable.
        val body = text.replace("₹", "").replace('·', '.')
        for (ch in body) {
            val code = ch.code
            b(if (code in 0..255) code else '?'.code)
        }

        // Feed a few lines so content clears the cutter, then full-cut.
        repeat(4) { b(LF) }
        if (cut) { b(GS); b(0x56); b(0x00) } // GS V 0 → full cut
        return out.toByteArray()
    }
}

/**
 * Send an ESC/POS payload to a LAN printer at [host]:[port]. Returns a short
 * human status for the UI. Never throws — connection/timeout errors come back
 * as a message so the caller can show them (and reset its spinner).
 */
suspend fun printToNetworkPrinter(host: String, port: Int, payload: ByteArray): String {
    if (host.isBlank()) return "No printer IP set — add it in Settings ▸ Printer"
    return try {
        withTimeout(8_000) {
            val selector = SelectorManager(Dispatchers.Default)
            try {
                val socket = aSocket(selector).tcp().connect(host, port)
                try {
                    val channel = socket.openWriteChannel(autoFlush = false)
                    channel.writeFully(payload)
                    channel.flush()
                } finally {
                    socket.close()
                }
            } finally {
                selector.close()
            }
            "Sent to printer ($host:$port)"
        }
    } catch (e: Throwable) {
        "Network print failed: ${e.message ?: "could not reach $host:$port"}"
    }
}
