package com.bnm.analyzersim

/**
 * MLLP — `<VT 0x0B> message <FS 0x1C> <CR 0x0D>` — as an analyzer speaks it.
 *
 * A deliberate second implementation of what `com.bnm.lab.instruments.Mllp`
 * does inside the app: the simulator must be able to be WRONG independently,
 * or a framing bug shared by both sides would test as a pass.
 */
object SimMllp {
    const val SB = ''
    const val EB = ''
    const val CR = '\r'

    fun wrap(message: String): ByteArray = (SB + message + EB + CR).toByteArray(Charsets.UTF_8)

    /**
     * What the app sent back. The analyzer only cares about two things — did
     * the ACK arrive, and does MSA-1 say "accepted" — so that is all we read.
     */
    data class Ack(val code: String, val controlId: String, val raw: String) {
        val accepted: Boolean get() = code.equals("AA", ignoreCase = true) || code.equals("CA", ignoreCase = true)
        override fun toString(): String = "MSA|$code" + if (controlId.isNotEmpty()) "|$controlId" else ""
    }

    /** The first complete MLLP block in [buffer] → the ACK it carries, or null. */
    fun readAck(buffer: String): Ack? {
        val start = buffer.indexOf(SB)
        if (start < 0) return null
        val end = buffer.indexOf(EB, start + 1)
        if (end < 0) return null
        val message = buffer.substring(start + 1, end)
        val msa = message.split('\r', '\n').firstOrNull { it.startsWith("MSA") } ?: return null
        val fields = msa.split('|')
        return Ack(
            code = fields.getOrNull(1)?.trim().orEmpty(),
            controlId = fields.getOrNull(2)?.trim().orEmpty(),
            raw = message,
        )
    }
}
