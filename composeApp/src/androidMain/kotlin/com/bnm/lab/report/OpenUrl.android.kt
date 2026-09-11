package com.bnm.lab.report

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri

actual fun openUrl(url: String): String {
    val ctx = reportContext ?: return "Not ready yet — try again"
    return try {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        )
        "Opened WhatsApp"
    } catch (e: ActivityNotFoundException) {
        "No app on this device can open WhatsApp links"
    } catch (e: Exception) {
        "Could not open WhatsApp: ${e.message}"
    }
}
