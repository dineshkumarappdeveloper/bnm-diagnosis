package com.bnm.diagnosis.report

/**
 * Minimal PNG encoder — 1-bit greyscale, no dependencies, commonMain only.
 * Used by the approver signature pad: the pad hands us an ink mask, we hand
 * back bytes that `staff.signature_png` can carry to every seat and that both
 * PDF renderers' native decoders (ImageIO on desktop, BitmapFactory on Android)
 * already understand.
 *
 * WHY WE ENCODE IT OURSELVES rather than reaching for a platform bitmap:
 * a signature captured on the front desk has to render on the pathologist's
 * laptop, so it lives in a synced text column — which means the encoder has to
 * exist in commonMain. Writing ~80 lines here beats an expect/actual over three
 * unrelated platform imaging stacks, and keeps the pad testable off-device.
 *
 * WHY 1-BIT AND WHY "STORED" DEFLATE: ink is black-on-white, so one bit per
 * pixel loses nothing a signature needs, and it is what keeps the row small —
 * a 960x300 pad is ~36 KB of pixels (~48 KB base64), against ~290 KB for 8-bit
 * grey. Small enough that an uncompressed (type-0 "stored") deflate stream is
 * fine, and a stored stream is a dozen lines instead of a Huffman coder. The
 * caller supersamples, so the printed edge still looks smooth.
 */
object PngWriter {

    /** Hard ceiling on what the pad may produce, so a stray value cannot mint a
     *  multi-megabyte staff row that then has to sync. */
    const val MAX_SIDE = 4096

    /**
     * Encode a [width] x [height] 1-bit image. [dark] returns true where ink is.
     * Returns an empty array for a nonsense size rather than throwing — a
     * signature pad must never be able to crash the app that hosts it.
     */
    fun grayscale1Bit(width: Int, height: Int, dark: (x: Int, y: Int) -> Boolean): ByteArray {
        if (width !in 1..MAX_SIDE || height !in 1..MAX_SIDE) return ByteArray(0)
        val rowBytes = (width + 7) / 8

        // Raw scanlines: one filter byte (0 = None) then MSB-first pixels,
        // 0 = black. Filtering would only pay off with a real compressor.
        val raw = ByteArray((rowBytes + 1) * height)
        var p = 0
        for (y in 0 until height) {
            raw[p++] = 0
            for (xByte in 0 until rowBytes) {
                var b = 0
                for (bit in 0 until 8) {
                    val x = xByte * 8 + bit
                    val white = x >= width || !dark(x, y) // padding bits read as white
                    if (white) b = b or (0x80 ushr bit)
                }
                raw[p++] = b.toByte()
            }
        }

        val out = ByteSink(raw.size + raw.size / 65535 * 5 + 128)
        out.bytes(PNG_MAGIC)
        out.chunk("IHDR") {
            it.int32(width); it.int32(height)
            it.byte(1)      // bit depth
            it.byte(0)      // colour type 0 = greyscale
            it.byte(0); it.byte(0); it.byte(0) // deflate / adaptive filtering / no interlace
        }
        out.chunk("IDAT") { it.bytes(zlibStored(raw)) }
        out.chunk("IEND") { }
        return out.toByteArray()
    }

    // ── zlib / deflate, "stored" blocks only ─────────────────────────────────

    private fun zlibStored(data: ByteArray): ByteArray {
        val out = ByteSink(data.size + data.size / 65535 * 5 + 16)
        out.byte(0x78); out.byte(0x01)          // CMF/FLG: deflate, 32K window, no dict
        var off = 0
        do {
            val len = minOf(65535, data.size - off)
            val last = off + len >= data.size
            out.byte(if (last) 1 else 0)        // BFINAL, BTYPE = 00 (stored)
            out.byte(len and 0xFF); out.byte((len ushr 8) and 0xFF)
            val n = len.inv() and 0xFFFF
            out.byte(n and 0xFF); out.byte((n ushr 8) and 0xFF)
            out.bytes(data, off, len)
            off += len
        } while (off < data.size)
        out.int32(adler32(data))
        return out.toByteArray()
    }

    private fun adler32(data: ByteArray): Int {
        var a = 1
        var b = 0
        for (byte in data) {
            a = (a + (byte.toInt() and 0xFF)) % 65521
            b = (b + a) % 65521
        }
        return (b shl 16) or a
    }

    // ── CRC-32 (PNG chunk checksum) ──────────────────────────────────────────

    private val CRC_TABLE = IntArray(256) {
        var c = it
        repeat(8) { c = if (c and 1 != 0) (0xEDB88320.toInt() xor (c ushr 1)) else (c ushr 1) }
        c
    }

    private fun crc32(data: ByteArray, from: Int, len: Int): Int {
        var c = -1
        for (i in from until from + len) {
            c = CRC_TABLE[(c xor data[i].toInt()) and 0xFF] xor (c ushr 8)
        }
        return c.inv()
    }

    private val PNG_MAGIC = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    /** Growable byte buffer — ByteArrayOutputStream isn't commonMain. */
    private class ByteSink(initial: Int = 64) {
        private var buf = ByteArray(maxOf(16, initial))
        private var size = 0

        fun byte(v: Int) {
            ensure(1)
            buf[size++] = v.toByte()
        }

        fun int32(v: Int) {
            byte((v ushr 24) and 0xFF); byte((v ushr 16) and 0xFF)
            byte((v ushr 8) and 0xFF); byte(v and 0xFF)
        }

        fun bytes(src: ByteArray, from: Int = 0, len: Int = src.size) {
            ensure(len)
            src.copyInto(buf, size, from, from + len)
            size += len
        }

        /** length + type + payload + CRC(type..payload). */
        fun chunk(type: String, body: (ByteSink) -> Unit) {
            val payload = ByteSink(64).also(body).toByteArray()
            int32(payload.size)
            val start = size
            for (ch in type) byte(ch.code)
            bytes(payload)
            int32(crc32(buf, start, size - start))
        }

        fun toByteArray(): ByteArray = buf.copyOf(size)

        private fun ensure(extra: Int) {
            if (size + extra <= buf.size) return
            var cap = buf.size * 2
            while (cap < size + extra) cap *= 2
            buf = buf.copyOf(cap)
        }
    }
}
