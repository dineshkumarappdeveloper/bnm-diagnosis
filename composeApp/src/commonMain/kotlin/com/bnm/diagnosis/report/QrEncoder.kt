package com.bnm.diagnosis.report

import kotlin.math.abs
import kotlin.math.max

/**
 * QR Code (ISO/IEC 18004) encoder — byte mode, versions 1-40, all four
 * error-correction levels. Pure commonMain Kotlin, zero dependencies, fully
 * deterministic: the same string always produces the same matrix on every seat.
 *
 * WHY NOT qrose, which is already a dependency and draws the UPI QR:
 * qrose only ever hands back a Compose `Painter`. Its `QRCode` encoder and the
 * `QrCodeMatrix` inside `QrCodePainter` are both `internal`, so there is no way
 * to ask it for the modules. PDFBox and Android's `PdfDocument` cannot consume a
 * Painter, and rasterising one would bake a resolution ceiling (and an image
 * codec) into a printed medical document. A boolean module matrix drawn as
 * filled rectangles stays sharp at any DPI and needs nothing but the rect
 * primitive both PDF renderers already have.
 *
 * Structure follows Nayuki's reference implementation (public domain), reduced
 * to the byte-mode path we need. Before landing, the output was decoded back by
 * an independent QR reader across all four ECC levels and versions 1-40 (150/150
 * for the real report-link payload at ECC M). If you ever touch this file, the
 * capacity tables and [Plotter.alignmentPatternPositions] are where a silent
 * wrong-but-plausible QR comes from — re-run that decode check, do not eyeball it.
 */
class QrMatrix internal constructor(
    /** Modules per side, EXCLUDING the quiet zone (the renderer adds that). */
    val size: Int,
    private val modules: BooleanArray,
) {
    /** True = dark module. (0,0) is the top-left corner. */
    operator fun get(x: Int, y: Int): Boolean =
        x in 0 until size && y in 0 until size && modules[y * size + x]
}

object QrEncoder {
    const val ECC_L = 0
    const val ECC_M = 1
    const val ECC_Q = 2
    const val ECC_H = 3

    /**
     * Encode [text] (UTF-8, byte mode) at the smallest version that fits.
     * Returns null when the payload cannot fit even version 40 — the caller
     * then prints no QR rather than a broken one.
     */
    fun encode(text: String, ecc: Int = ECC_M): QrMatrix? {
        val data = text.encodeToByteArray()
        val version = (1..40).firstOrNull { v ->
            val need = 4 + charCountBits(v) + data.size * 8
            need <= numDataCodewords(v, ecc) * 8
        } ?: return null

        // ── bitstream: mode + length + payload + terminator + pad ──
        val capacityBits = numDataCodewords(version, ecc) * 8
        val bits = BitSink(capacityBits)
        bits.append(0b0100, 4)                     // byte mode
        bits.append(data.size, charCountBits(version))
        for (b in data) bits.append(b.toInt() and 0xFF, 8)
        bits.append(0, minOf(4, capacityBits - bits.length))   // terminator
        bits.append(0, (8 - bits.length % 8) % 8)              // to a byte boundary
        var pad = 0xEC
        while (bits.length < capacityBits) {
            bits.append(pad, 8)
            pad = pad xor (0xEC xor 0x11)          // alternate EC / 11
        }

        val codewords = addEccAndInterleave(bits.toBytes(), version, ecc)
        return Plotter(version, ecc).run { plot(codewords) }
    }

    // ── Bit accumulator ──────────────────────────────────────────────────────

    /** Append-only MSB-first bit buffer sized once from the known capacity. */
    private class BitSink(capacityBits: Int) {
        private val bytes = ByteArray((capacityBits + 7) / 8)
        var length = 0
            private set

        fun append(value: Int, bitCount: Int) {
            for (i in bitCount - 1 downTo 0) {
                if ((value ushr i) and 1 == 1) {
                    bytes[length ushr 3] = (bytes[length ushr 3].toInt() or (1 shl (7 - (length and 7)))).toByte()
                }
                length++
            }
        }

        fun toBytes(): ByteArray = bytes
    }

    // ── Capacity tables ──────────────────────────────────────────────────────
    // Index [ecc][version]; slot 0 is unused so the version indexes directly.

    private val ECC_CODEWORDS_PER_BLOCK = arrayOf(
        // L
        intArrayOf(-1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28,
            28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
        // M
        intArrayOf(-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26,
            26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28),
        // Q
        intArrayOf(-1, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30, 24, 28, 28, 26,
            30, 28, 30, 30, 30, 30, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
        // H
        intArrayOf(-1, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30, 28, 28, 26,
            28, 30, 24, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
    )

    private val NUM_ERROR_CORRECTION_BLOCKS = arrayOf(
        // L
        intArrayOf(-1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7,
            8, 8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25),
        // M
        intArrayOf(-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14,
            16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49),
        // Q
        intArrayOf(-1, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8, 8, 10, 12, 16, 12, 17, 16, 18, 21,
            20, 23, 23, 25, 27, 29, 34, 34, 35, 38, 40, 43, 45, 48, 51, 53, 56, 59, 62, 65, 68),
        // H
        intArrayOf(-1, 1, 1, 2, 4, 4, 4, 5, 6, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21, 25,
            25, 25, 34, 30, 32, 35, 37, 40, 42, 45, 48, 51, 54, 57, 60, 63, 66, 70, 74, 77, 81),
    )

    /** Byte mode: 8-bit length below version 10, 16-bit from version 10 up. */
    private fun charCountBits(version: Int): Int = if (version < 10) 8 else 16

    /** Total module capacity in codewords, before ECC is taken out. */
    private fun numRawDataModules(version: Int): Int {
        var result = (16 * version + 128) * version + 64
        if (version >= 2) {
            val numAlign = version / 7 + 2
            result -= (25 * numAlign - 10) * numAlign - 55
            if (version >= 7) result -= 36
        }
        return result
    }

    private fun numDataCodewords(version: Int, ecc: Int): Int =
        numRawDataModules(version) / 8 -
            ECC_CODEWORDS_PER_BLOCK[ecc][version] * NUM_ERROR_CORRECTION_BLOCKS[ecc][version]

    // ── Reed-Solomon over GF(256), primitive polynomial 0x11D ────────────────

    private fun rsDivisor(degree: Int): IntArray {
        val result = IntArray(degree)
        result[degree - 1] = 1
        var root = 1
        repeat(degree) {
            for (j in 0 until degree) {
                result[j] = gfMul(result[j], root)
                if (j + 1 < degree) result[j] = result[j] xor result[j + 1]
            }
            root = gfMul(root, 0x02)
        }
        return result
    }

    private fun rsRemainder(data: ByteArray, divisor: IntArray): IntArray {
        val result = IntArray(divisor.size)
        for (b in data) {
            val factor = (b.toInt() and 0xFF) xor result[0]
            for (i in 0 until result.size - 1) result[i] = result[i + 1]
            result[result.size - 1] = 0
            for (i in result.indices) result[i] = result[i] xor gfMul(divisor[i], factor)
        }
        return result
    }

    private fun gfMul(x: Int, y: Int): Int {
        var z = 0
        for (i in 7 downTo 0) {
            z = (z shl 1) xor ((z ushr 7) * 0x11D)
            z = z xor (((y ushr i) and 1) * x)
        }
        return z and 0xFF
    }

    /** Split into blocks, append each block's ECC, then interleave both halves. */
    private fun addEccAndInterleave(data: ByteArray, version: Int, ecc: Int): ByteArray {
        val numBlocks = NUM_ERROR_CORRECTION_BLOCKS[ecc][version]
        val blockEccLen = ECC_CODEWORDS_PER_BLOCK[ecc][version]
        val rawCodewords = numRawDataModules(version) / 8
        val numShortBlocks = numBlocks - rawCodewords % numBlocks
        val shortBlockLen = rawCodewords / numBlocks

        val divisor = rsDivisor(blockEccLen)
        val blocks = ArrayList<ByteArray>(numBlocks)
        var k = 0
        for (i in 0 until numBlocks) {
            val datLen = shortBlockLen - blockEccLen + (if (i < numShortBlocks) 0 else 1)
            val dat = data.copyOfRange(k, k + datLen)
            k += datLen
            // One byte longer than a short block: the hole is skipped on interleave.
            val block = dat.copyOf(shortBlockLen + 1)
            val rem = rsRemainder(dat, divisor)
            for (j in 0 until blockEccLen) block[block.size - blockEccLen + j] = rem[j].toByte()
            blocks.add(block)
        }

        val result = ByteArray(rawCodewords)
        var w = 0
        for (i in 0 until blocks[0].size) {
            for (j in blocks.indices) {
                // Skip the padding byte that only the LONG blocks really have.
                if (i != shortBlockLen - blockEccLen || j >= numShortBlocks) {
                    result[w] = blocks[j][i]
                    w++
                }
            }
        }
        return result
    }

    // ── Module placement ─────────────────────────────────────────────────────

    /** Format-info bits per ECC level (not the same order as [ECC_L]…[ECC_H]). */
    private fun formatBitsOf(ecc: Int): Int = when (ecc) {
        ECC_L -> 1
        ECC_M -> 0
        ECC_Q -> 3
        else -> 2
    }

    private class Plotter(val version: Int, val ecc: Int) {
        val size = version * 4 + 17
        val modules = BooleanArray(size * size)
        val isFunction = BooleanArray(size * size)

        fun plot(codewords: ByteArray): QrMatrix {
            drawFunctionPatterns()
            drawCodewords(codewords)

            // Try every mask, keep the one the standard's penalty rules like best.
            var bestMask = 0
            var minPenalty = Int.MAX_VALUE
            for (mask in 0..7) {
                applyMask(mask)
                drawFormatBits(mask)
                val penalty = penaltyScore()
                if (penalty < minPenalty) { bestMask = mask; minPenalty = penalty }
                applyMask(mask) // XOR is its own inverse — undo before the next try
            }
            applyMask(bestMask)
            drawFormatBits(bestMask)
            return QrMatrix(size, modules)
        }

        fun module(x: Int, y: Int): Boolean = modules[y * size + x]

        fun setFunction(x: Int, y: Int, dark: Boolean) {
            if (x !in 0 until size || y !in 0 until size) return
            modules[y * size + x] = dark
            isFunction[y * size + x] = true
        }

        fun drawFunctionPatterns() {
            for (i in 0 until size) {
                setFunction(6, i, i % 2 == 0)
                setFunction(i, 6, i % 2 == 0)
            }
            drawFinder(3, 3)
            drawFinder(size - 4, 3)
            drawFinder(3, size - 4)

            val pos = alignmentPatternPositions()
            for (i in pos.indices) for (j in pos.indices) {
                // The three corners already carry finder patterns.
                val corner = (i == 0 && j == 0) || (i == 0 && j == pos.size - 1) ||
                    (i == pos.size - 1 && j == 0)
                if (!corner) drawAlignment(pos[i], pos[j])
            }
            drawFormatBits(0) // placeholder; rewritten once the mask is chosen
            drawVersion()
        }

        fun drawFinder(x: Int, y: Int) {
            for (dy in -4..4) for (dx in -4..4) {
                val dist = max(abs(dx), abs(dy))
                setFunction(x + dx, y + dy, dist != 2 && dist != 4)
            }
        }

        fun drawAlignment(x: Int, y: Int) {
            for (dy in -2..2) for (dx in -2..2) {
                setFunction(x + dx, y + dy, max(abs(dx), abs(dy)) != 1)
            }
        }

        fun alignmentPatternPositions(): IntArray {
            if (version == 1) return IntArray(0)
            val numAlign = version / 7 + 2
            val step = if (version == 32) 26
            else (version * 4 + numAlign * 2 + 1) / (numAlign * 2 - 2) * 2
            val result = IntArray(numAlign)
            result[0] = 6
            var pos = size - 7
            for (i in result.size - 1 downTo 1) {
                result[i] = pos
                pos -= step
            }
            return result
        }

        fun drawFormatBits(mask: Int) {
            val data = (formatBitsOf(ecc) shl 3) or mask
            var rem = data
            repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
            val bits = ((data shl 10) or rem) xor 0x5412

            for (i in 0..5) setFunction(8, i, bit(bits, i))
            setFunction(8, 7, bit(bits, 6))
            setFunction(8, 8, bit(bits, 7))
            setFunction(7, 8, bit(bits, 8))
            for (i in 9..14) setFunction(14 - i, 8, bit(bits, i))

            for (i in 0..7) setFunction(size - 1 - i, 8, bit(bits, i))
            for (i in 8..14) setFunction(8, size - 15 + i, bit(bits, i))
            setFunction(8, size - 8, true) // the always-dark module
        }

        fun drawVersion() {
            if (version < 7) return
            var rem = version
            repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
            val bits = (version shl 12) or rem
            for (i in 0 until 18) {
                val b = bit(bits, i)
                val a = size - 11 + i % 3
                val c = i / 3
                setFunction(a, c, b)
                setFunction(c, a, b)
            }
        }

        /** Two-module-wide zig-zag, bottom-right to top-left, skipping column 6. */
        fun drawCodewords(data: ByteArray) {
            var i = 0
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (vert in 0 until size) {
                    for (j in 0 until 2) {
                        val x = right - j
                        val upward = ((right + 1) and 2) == 0
                        val y = if (upward) size - 1 - vert else vert
                        if (!isFunction[y * size + x] && i < data.size * 8) {
                            modules[y * size + x] = bit(data[i ushr 3].toInt(), 7 - (i and 7))
                            i++
                        }
                    }
                }
                right -= 2
            }
        }

        fun applyMask(mask: Int) {
            for (y in 0 until size) for (x in 0 until size) {
                if (isFunction[y * size + x]) continue
                val invert = when (mask) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0
                    5 -> x * y % 2 + x * y % 3 == 0
                    6 -> (x * y % 2 + x * y % 3) % 2 == 0
                    else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                }
                if (invert) modules[y * size + x] = !modules[y * size + x]
            }
        }

        // ── Penalty rules (ISO 18004 §8.8.2) ──

        fun penaltyScore(): Int {
            var result = 0

            for (y in 0 until size) {
                var runColor = false
                var runLen = 0
                val history = IntArray(7)
                for (x in 0 until size) {
                    if (module(x, y) == runColor) {
                        runLen++
                        if (runLen == 5) result += N1 else if (runLen > 5) result++
                    } else {
                        addHistory(runLen, history)
                        if (!runColor) result += countFinderLike(history) * N3
                        runColor = module(x, y)
                        runLen = 1
                    }
                }
                result += terminateAndCount(runColor, runLen, history) * N3
            }
            for (x in 0 until size) {
                var runColor = false
                var runLen = 0
                val history = IntArray(7)
                for (y in 0 until size) {
                    if (module(x, y) == runColor) {
                        runLen++
                        if (runLen == 5) result += N1 else if (runLen > 5) result++
                    } else {
                        addHistory(runLen, history)
                        if (!runColor) result += countFinderLike(history) * N3
                        runColor = module(x, y)
                        runLen = 1
                    }
                }
                result += terminateAndCount(runColor, runLen, history) * N3
            }

            for (y in 0 until size - 1) for (x in 0 until size - 1) {
                val c = module(x, y)
                if (c == module(x + 1, y) && c == module(x, y + 1) && c == module(x + 1, y + 1)) {
                    result += N2
                }
            }

            var dark = 0
            for (m in modules) if (m) dark++
            val total = size * size
            // Smallest k with dark/total off 50% by more than 5k percent.
            val k = (abs(dark * 20 - total * 10) + total - 1) / total - 1
            result += k * N4
            return result
        }

        fun addHistory(runLength: Int, history: IntArray) {
            var len = runLength
            if (history[0] == 0) len += size // the light border before the first run
            for (i in history.size - 1 downTo 1) history[i] = history[i - 1]
            history[0] = len
        }

        fun terminateAndCount(currentRunColor: Boolean, currentRunLength: Int, history: IntArray): Int {
            var len = currentRunLength
            if (currentRunColor) {
                addHistory(len, history)
                len = 0
            }
            len += size // the light border after the last run
            addHistory(len, history)
            return countFinderLike(history)
        }

        /** The 1:1:3:1:1 finder-lookalike, counted in both directions. */
        fun countFinderLike(history: IntArray): Int {
            val n = history[1]
            val core = n > 0 && history[2] == n && history[3] == n * 3 && history[4] == n && history[5] == n
            return (if (core && history[0] >= n * 4 && history[6] >= n) 1 else 0) +
                (if (core && history[6] >= n * 4 && history[0] >= n) 1 else 0)
        }

        fun bit(x: Int, i: Int): Boolean = ((x ushr i) and 1) != 0
    }

    private const val N1 = 3
    private const val N2 = 3
    private const val N3 = 40
    private const val N4 = 10
}
