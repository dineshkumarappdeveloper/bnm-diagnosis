package com.bnm.diagnosis.report

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

actual fun shareFile(path: String, mimeType: String, text: String, waPhone: String?): String {
    val ctx = reportContext ?: return "Not ready yet — try again"
    val f = File(path)
    if (!f.exists()) return "The report file could not be found"
    val uri = runCatching { FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f) }
        .getOrElse { return "Could not share this file: ${it.message}" }
    fun intent() = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TEXT, text)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    // Straight to WhatsApp when it is installed; the chooser otherwise. The
    // recipient is picked in WhatsApp itself — no supported intent extra
    // targets a phone number with an attachment.
    return try {
        ctx.startActivity(intent().setPackage("com.whatsapp"))
        "Opened WhatsApp — choose the chat and send"
    } catch (e: ActivityNotFoundException) {
        try {
            ctx.startActivity(Intent.createChooser(intent(), "Send report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "Opened the share sheet"
        } catch (e2: Exception) {
            "No app on this device can share the report"
        }
    } catch (e: Exception) {
        "Could not open WhatsApp: ${e.message}"
    }
}
