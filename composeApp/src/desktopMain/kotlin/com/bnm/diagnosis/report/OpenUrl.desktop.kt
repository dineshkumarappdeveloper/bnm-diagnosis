package com.bnm.diagnosis.report

import java.awt.Desktop
import java.net.URI

actual fun openUrl(url: String): String = try {
    when {
        Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE) -> {
            Desktop.getDesktop().browse(URI(url)); "Opened WhatsApp"
        }
        // Headless or a stripped desktop: hand it to the shell instead of failing.
        else -> {
            val os = System.getProperty("os.name").orEmpty().lowercase()
            val cmd = when {
                os.contains("mac") -> arrayOf("open", url)
                os.contains("win") -> arrayOf("rundll32", "url.dll,FileProtocolHandler", url)
                else -> arrayOf("xdg-open", url)
            }
            ProcessBuilder(*cmd).start(); "Opened WhatsApp"
        }
    }
} catch (e: Exception) {
    "Could not open WhatsApp: ${e.message}"
}
