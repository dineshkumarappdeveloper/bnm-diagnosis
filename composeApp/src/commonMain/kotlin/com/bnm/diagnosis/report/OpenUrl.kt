package com.bnm.diagnosis.report

/**
 * Hand a URL to the operating system — the browser, or whichever app claims it
 * (`https://wa.me/…` opens WhatsApp itself where it is installed).
 *
 * Returns a short human status like the other platform bridges here: "Opened
 * WhatsApp" or a reason. Never throws; a lab PC with no browser must not crash
 * the screen that called it.
 */
expect fun openUrl(url: String): String
