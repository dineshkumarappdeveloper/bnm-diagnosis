package com.bnm.lab.license

import com.bnm.lab.lab.TenantRowCounts

/**
 * A lab moving between editions, and the one thing it must be told.
 *
 * Going offline → connected is a real change to where a lab's records live:
 * everything recorded while offline uploads on the first sweep. The lab bought
 * an offline edition once, so the app says so plainly, once, rather than
 * letting months of history leave quietly.
 *
 * Pure so the words and the arithmetic are testable; the screen only shows it.
 */
enum class EditionSwitch { NONE, WENT_ONLINE, WENT_OFFLINE }

/** What changed between the edition last run ([lastSeen]) and the licence now. */
fun editionSwitch(lastSeen: String?, current: String): EditionSwitch {
    val was = lastSeen?.trim().orEmpty()
    if (was.isEmpty() || was == current) return EditionSwitch.NONE
    return when (current) {
        LicenseManager.EDITION_CONNECTED -> EditionSwitch.WENT_ONLINE
        LicenseManager.EDITION_STANDALONE -> EditionSwitch.WENT_OFFLINE
        else -> EditionSwitch.NONE
    }
}

/** Title for the notice, or null when there is nothing to say. */
fun editionNoticeTitle(switch: EditionSwitch): String? = when (switch) {
    EditionSwitch.WENT_ONLINE -> "This lab is now connected"
    EditionSwitch.WENT_OFFLINE -> "This lab is now offline only"
    EditionSwitch.NONE -> null
}

/**
 * The body of the notice: what is about to happen, in counts the lab
 * recognises. [counts] is null when they could not be read — the notice still
 * appears, without numbers, because the fact matters more than the figures.
 */
fun editionNoticeBody(switch: EditionSwitch, counts: TenantRowCounts?): String = when (switch) {
    EditionSwitch.WENT_ONLINE -> buildString {
        append("Everything recorded while this lab was offline will now upload to BNM")
        if (counts != null) {
            append(" — ")
            append(
                listOf(
                    plural(counts.patients, "patient", "patients"),
                    plural(counts.orders, "order", "orders"),
                    plural(counts.results, "result", "results"),
                ).joinToString(", ")
            )
        }
        append(". It happens in the background over the next few minutes; nothing is re-entered and nothing is deleted.")
        append("\n\nFrom now on reports carry a QR code the patient can scan. Reports printed before today keep no download link — reprint one to publish it.")
    }
    EditionSwitch.WENT_OFFLINE -> buildString {
        append("Nothing further will leave this computer: no sync, no report upload, no QR links, no licence or update checks.")
        append("\n\nWhatever already reached BNM stays there until it is removed on that side.")
    }
    EditionSwitch.NONE -> ""
}

private fun plural(n: Long, one: String, many: String): String = "$n " + if (n == 1L) one else many
