package com.bnm.analyzersim.ui

import com.bnm.analyzersim.Analyzer
import com.bnm.analyzersim.Profile

/**
 * "What should happen in BNM Lab" — the line under the transcript.
 *
 * This is the part that makes the tool teach rather than merely send. An
 * engineer who does not already know what a truncated frame is supposed to look
 * like on the Instruments screen cannot tell a pass from a failure, and will
 * report "it worked" because nothing crashed. Saying the expected outcome
 * BEFORE the Send button is pressed turns every run into a check.
 *
 * Kept as a pure function of the form so the wording is testable — every
 * sentence here is a claim about the app's behaviour, and a claim that quietly
 * goes stale is worse than no hint at all.
 */
fun labExpectation(form: SimForm): String {
    val f = form.faults

    // The faults that stop a result existing at all: nothing else matters, so
    // each of these is the whole hint.
    if (f.hang) return "Expect: Instruments shows this PC as the peer, bytes 0, frames 0 — " +
        "and nothing filed. If it shows nothing at all, it is the port or the firewall."
    if (f.garbage) return "Expect: the bytes counted and frames 0 — those bytes are not a frame, " +
        "so no result and no claim-queue row."
    if (f.truncated) return "Expect: the traffic log to say \"Connection closed with N unframed bytes " +
        "(ignored)\", and the frame counter NOT to move. Nothing should be filed."
    if (f.duplicate) return "Expect: two frames and ONE result — a retransmit the app double-applies " +
        "is a doubled patient record."
    if (f.burst) {
        val n = f.burstCount.trim().toIntOrNull() ?: 5
        return "Expect: $n results and none lost — each connection is accepted on its own."
    }

    val extras = buildList {
        if (f.slowChunks) add("The pieces must arrive as ONE result, not several.")
        if (f.badUnits && form.analyzer == Analyzer.MINDRAY)
            add("Plus a red log row: \"Unit not converted — stored as the analyzer sent it\".")
        if (f.unknownCode) add(
            if (form.analyzer == Analyzer.MINDRAY)
                "The unknown parameter should be kept under the analyzer's own label, never mapped onto a real one."
            else "The 21st field should be dropped, never mapped onto a real parameter."
        )
        if (form.profile == Profile.CRITICAL)
            add("Panic values: it must also reach the critical call-out list.")
        if (form.image && form.analyzer == Analyzer.MINDRAY)
            add("~40 KB with the scattergram — the message big enough to find a buffer bug.")
    }

    return (deliveryExpectation(form) + " " + extras.joinToString(" ")).trim()
}

/** Where the result itself should land. */
private fun deliveryExpectation(form: SimForm): String {
    val id = form.sampleId.trim()

    if (form.faults.noSpecimen) return "Expect: Queued for manual claim — " +
        "reason \"no specimen id keyed on the analyzer\"."

    if (form.qc) return when (form.analyzer) {
        Analyzer.MINDRAY -> "Expect: acknowledged and then ignored — MSH-11 = Q marks control material, " +
            "which must never be filed as a patient."
        // Honest rather than tidy: the format simply has no field for it.
        Analyzer.MISPA -> "Expect: an ordinary result — the Mispa format carries no QC field, " +
            "so nothing tells BNM Lab this was control material."
    }

    // SIM-xxxx is this tool's own placeholder, not an accession anyone
    // registered, so the honest expectation is the claim queue.
    if (id.isEmpty()) return "Expect: Queued for manual claim — there is no accession on this frame."
    if (id.startsWith("SIM-", ignoreCase = true))
        return "Expect: Queued for manual claim — no order matches '$id'."

    return "Expect: the result on the order with accession '$id' — " +
        "or Queued for manual claim if BNM Lab has no order under it."
}

/** The one-line reminder above the transport picker. */
fun driverReminder(form: SimForm): String = when (form.analyzer) {
    Analyzer.MINDRAY ->
        "BNM Lab must have an instrument row with driver \"${form.driverKey}\", transport TCP, " +
            "port ${form.port.ifBlank { Analyzer.MINDRAY.defaultPort.toString() }}, enabled and listening."
    Analyzer.MISPA -> when (form.transport) {
        TransportKind.SERIAL ->
            "BNM Lab must have an instrument row with driver \"${form.driverKey}\", transport SERIAL, " +
                "on the port at the OTHER end of this cable."
        TransportKind.TCP ->
            "BNM Lab must have an instrument row with driver \"${form.driverKey}\", transport TCP, " +
                "port ${form.port.ifBlank { Analyzer.MISPA.defaultPort.toString() }} — a real Mispa is on a " +
                "cable, so this is the bench rehearsal, not the install."
    }
}
