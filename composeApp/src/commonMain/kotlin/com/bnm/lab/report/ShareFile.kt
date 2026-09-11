package com.bnm.lab.report

/**
 * Hand a FILE to WhatsApp (or whatever the operator picks) with [text] as the
 * accompanying message — the real document, not a link to one.
 *
 * WhatsApp's `wa.me` link can only carry text; no URL scheme on any platform
 * attaches a file. So each platform does the nearest true thing:
 *  - **Android / iOS**: the system share sheet, aimed at WhatsApp when it is
 *    installed. The PDF is attached; the operator picks the chat.
 *  - **Desktop**: the PDF goes on the clipboard and WhatsApp opens at the right
 *    chat with the message typed — one paste attaches it. A lab PC runs
 *    WhatsApp Web or the desktop app all day, and pasting is how a file gets
 *    into either of them.
 *
 * [waPhone] is digits with the country code (see [waPhone]) or null when the
 * platform picks the recipient itself. Returns a short human status; never
 * throws.
 */
expect fun shareFile(path: String, mimeType: String, text: String, waPhone: String?): String

/**
 * Did [status] mean the report actually left the lab?
 *
 * The platform bridges answer in words, and the caller has to decide whether to
 * stamp the report as reported. Anything that reads like a failure ("could
 * not", "failed", "not found", "no app") is not a hand-over; an opened app or a
 * copied file is.
 */
fun waHandedOver(status: String?): Boolean {
    val s = status?.trim()?.lowercase() ?: return false
    if (s.isEmpty()) return false
    val failed = listOf("could not", "failed", "not found", "no app", "not ready", "later", "not valid")
    if (failed.any { it in s }) return false
    return "open" in s || "copied" in s || "sent" in s
}
