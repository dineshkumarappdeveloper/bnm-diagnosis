package com.bnm.lab.backup

import java.security.SecureRandom

/**
 * The recovery code on the backup card: 125 random bits as 25 Crockford base-32
 * characters, `XXXXX-XXXXX-XXXXX-XXXXX-XXXXX`.
 *
 * Crockford's alphabet drops I, L, O and U so a code read off a printed card
 * cannot be misread; entry is forgiving the other way too — lower case, dashes,
 * spaces, and the letters I/L/O typed for 1/1/0 are all accepted.
 *
 * The code is the second unlock of the vault (the licence key is the first) for
 * the day the lab's licence key has been re-issued and the old one is nowhere:
 * BNM stores only the key's hash and cannot re-send it.
 */
internal object RecoveryCode {
    const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val LENGTH = 25
    const val GROUP = 5

    fun generate(random: SecureRandom = SecureRandom()): String {
        val bits = ByteArray(16).also { random.nextBytes(it) }
        return fromBits(bits)
    }

    /** The top 125 bits of [bits] (16 bytes), five per character. */
    fun fromBits(bits: ByteArray): String {
        require(bits.size == 16) { "16 bytes expected" }
        val sb = StringBuilder(LENGTH)
        for (i in 0 until LENGTH) {
            val bit = i * 5
            var v = 0
            for (k in 0 until 5) {
                val b = bit + k
                val set = (bits[b / 8].toInt() shr (7 - b % 8)) and 1
                v = (v shl 1) or set
            }
            sb.append(ALPHABET[v])
        }
        return sb.toString()
    }

    /** The inverse of [fromBits]: 16 bytes whose lowest three bits are always zero. */
    fun toBits(normalised: String): ByteArray {
        require(normalised.length == LENGTH) { "25 characters expected" }
        val out = ByteArray(16)
        for (i in 0 until LENGTH) {
            val v = ALPHABET.indexOf(normalised[i])
            require(v >= 0) { "not a recovery code character" }
            for (k in 0 until 5) {
                if ((v shr (4 - k)) and 1 == 1) {
                    val b = i * 5 + k
                    out[b / 8] = (out[b / 8].toInt() or (1 shl (7 - b % 8))).toByte()
                }
            }
        }
        return out
    }

    /**
     * What the operator typed, as the 25 canonical characters — or null when it
     * is not a recovery code at all (wrong length, a character outside the
     * alphabet). Never throws: the caller shows "That is not a recovery code".
     */
    fun normalise(typed: String): String? {
        val sb = StringBuilder(LENGTH)
        for (raw in typed.uppercase()) {
            val ch = when (raw) {
                'I', 'L' -> '1'
                'O' -> '0'
                '-', ' ', '\t', '\n', '\r', '.', '_' -> continue
                else -> raw
            }
            if (ch !in ALPHABET) return null
            sb.append(ch)
        }
        return sb.toString().takeIf { it.length == LENGTH }
    }

    /** `XXXXX-XXXXX-XXXXX-XXXXX-XXXXX` for the card and the "show code" dialog. */
    fun format(normalised: String): String = normalised.chunked(GROUP).joinToString("-")

    /** True when [typed] would be accepted as a code — cheap validation for a text field. */
    fun looksValid(typed: String): Boolean = normalise(typed) != null
}
