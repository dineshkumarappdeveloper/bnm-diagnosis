package com.bnm.diagnosis.print

import android.content.Context
import android.content.Intent

private var printContext: Context? = null

/** Call once from MainActivity (mirrors initDbContext). */
fun initPrintContext(context: Context) { printContext = context.applicationContext }

/** Baseline: open the print/share chooser with the receipt text so it can go to a
 *  printer or PDF app. Direct ESC-POS thermal printing is a follow-up (configured
 *  from Settings → Printer). */
actual fun printReceipt(title: String, body: String): String {
    val ctx = printContext ?: return "Printer not ready"
    return try {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        ctx.startActivity(Intent.createChooser(send, "Print / share receipt").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        "Opened print / share"
    } catch (e: Exception) {
        "Print failed: ${e.message}"
    }
}
