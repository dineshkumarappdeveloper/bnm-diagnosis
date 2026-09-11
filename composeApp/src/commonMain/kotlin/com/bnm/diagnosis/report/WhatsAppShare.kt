package com.bnm.diagnosis.report

/**
 * Sending a finished report to the patient (or the referring doctor) on
 * WhatsApp — the two ways a lab can do it, and the text that goes with them.
 *
 * Pure functions only: phone shapes, the message, the deep link. The API call
 * lives in `LabApi`, the button in the order screen, and the choice between
 * them in `ReportPrefs`.
 *
 * WHY TWO MODES. The Business API sends the PDF itself, from the lab's own
 * WhatsApp number, with no human in the loop — but Meta only allows a
 * business-initiated message inside a 24-hour window after the patient wrote
 * to the lab (otherwise an approved template is needed). The deep link has no
 * such rule: it opens WhatsApp with the message typed out and the operator
 * presses send, from whatever WhatsApp that PC or phone is signed into. Most
 * labs will want the link; labs already running the BNM WhatsApp number will
 * want the API.
 *
 * WHAT IS NEVER SENT: a result value, a flag, or anything else clinical. The
 * message carries the patient's name, the accession, and the private download
 * link — the link IS the capability, so the report itself never rides in the
 * chat as text.
 */
enum class WaShareMode(val slug: String, val label: String, val blurb: String) {
    OFF(
        "off", "Off",
        "No WhatsApp button on the report.",
    ),
    LINK(
        "link", "Open WhatsApp (link)",
        "Opens WhatsApp (or WhatsApp Web) with the message ready — the operator presses send. " +
            "Works from any device, needs no WhatsApp Business setup.",
    ),
    API(
        "api", "Send automatically (Business API)",
        "The lab's own WhatsApp Business number sends the report PDF itself, with no one in the loop. " +
            "Needs WhatsApp connected in BNM, and the patient must have messaged the lab in the last 24 hours.",
    );

    companion object {
        fun fromSlug(slug: String?): WaShareMode =
            entries.firstOrNull { it.slug.equals(slug?.trim(), ignoreCase = true) } ?: OFF
    }
}

/**
 * A phone number as WhatsApp wants it: digits only, country code first, no `+`.
 *
 * Indian labs type ten digits and nothing else, so a bare 10-digit number gets
 * [defaultCc]. A leading 0 (STD habit) is dropped, a leading + or 00 is
 * honoured, and a number that already starts with the country code is left
 * alone. Null when there is nothing usable — the caller then has no recipient
 * rather than a wrong one.
 */
fun waPhone(raw: String?, defaultCc: String = "91"): String? {
    val cc = defaultCc.filter { it.isDigit() }.ifEmpty { "91" }
    var d = raw?.filter { it.isDigit() }.orEmpty()
    if (d.isEmpty()) return null
    if (d.startsWith("00")) d = d.drop(2)                       // 0091… international prefix
    if (d.length > 10 && d.startsWith("0")) d = d.trimStart('0') // 0 98765 43210
    return when {
        d.length == 10 -> cc + d                                 // the common case
        d.length == 11 && d.startsWith("0") -> cc + d.drop(1)
        d.length in 11..15 -> d                                  // already carries a country code
        else -> null                                             // too short to dial
    }
}

/** True when [raw] can be dialled on WhatsApp at all. */
fun hasWaPhone(raw: String?, defaultCc: String = "91"): Boolean = waPhone(raw, defaultCc) != null

/**
 * The message that goes with the link. [toDoctor] addresses the referring
 * doctor instead of the patient (same link, different first line).
 *
 * Deliberately free of results: see the file KDoc.
 */
fun waReportMessage(
    patientName: String,
    labName: String,
    accession: String,
    url: String?,
    toDoctor: Boolean = false,
): String = buildString {
    if (toDoctor) {
        append("Lab report for ").append(patientName.trim()).append(" is ready.")
    } else {
        val first = patientName.trim().substringBefore(' ').ifBlank { "there" }
        append("Dear ").append(first).append(", your lab report from ").append(labName.trim()).append(" is ready.")
    }
    append("\n\nAccession: ").append(accession.trim())
    if (!url.isNullOrBlank()) {
        append("\nDownload: ").append(url.trim())
        append("\n\nThis link is private — please do not forward it.")
    }
    append("\n\n").append(labName.trim())
}

/**
 * The message that goes with a BILL. Same rule as the report: identity and
 * amounts, never anything clinical — a bill names the tests, so [url] (the
 * hosted invoice, when the server has one) carries the detail instead.
 */
fun waBillMessage(
    customerName: String?,
    businessName: String,
    invoiceNumber: String,
    total: Double,
    balance: Double,
    url: String? = null,
    money: (Double) -> String,
): String = buildString {
    val first = customerName?.trim()?.substringBefore(' ')?.takeIf { it.isNotEmpty() } ?: "there"
    append("Dear ").append(first).append(", thank you for visiting ").append(businessName.trim()).append(".")
    append("\n\nBill: ").append(invoiceNumber.trim())
    append("\nAmount: ").append(money(total))
    if (balance > 0.005) append("\nBalance due: ").append(money(balance))
    else append("\nPaid in full — thank you.")
    if (!url.isNullOrBlank()) append("\n\n").append(url.trim())
    append("\n\n").append(businessName.trim())
}

/** Caption for the PDF the Business API sends (the document carries the file). */
fun waReportCaption(patientName: String, labName: String, accession: String, toDoctor: Boolean = false): String =
    waReportMessage(patientName, labName, accession, url = null, toDoctor = toDoctor)

/** Filename the patient sees in the chat: `ACC-S1-00042 report.pdf`. */
fun waReportFilename(accession: String): String =
    (accession.trim().ifEmpty { "Lab" } + " report.pdf").replace('/', '-')

/**
 * `https://wa.me/<number>?text=…` — the one link that opens the WhatsApp app on
 * a phone, the desktop app when it is installed, and WhatsApp Web otherwise.
 */
fun waDeepLink(phone: String, text: String): String =
    "https://wa.me/$phone?text=" + percentEncode(text)

/** RFC 3986 percent-encoding of [s] (UTF-8), unreserved characters kept. */
internal fun percentEncode(s: String): String = buildString {
    for (b in s.encodeToByteArray()) {
        val c = b.toInt().toChar()
        if (c.isLetterOrDigit() && b.toInt() in 0..127 || c == '-' || c == '_' || c == '.' || c == '~') {
            append(c)
        } else {
            append('%').append(HEX[(b.toInt() shr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
        }
    }
}

private const val HEX = "0123456789ABCDEF"
