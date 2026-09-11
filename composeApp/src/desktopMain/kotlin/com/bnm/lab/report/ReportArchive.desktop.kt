package com.bnm.lab.report

import java.io.File
import javax.swing.JFileChooser
import javax.swing.UIManager

actual fun defaultReportsDir(): String {
    val home = System.getProperty("user.home").orEmpty()
    if (home.isBlank()) return ""
    // Documents exists on a stock Windows, macOS and most Linux desktops; a
    // machine without it gets the home folder rather than a surprise path.
    val docs = File(home, "Documents").takeIf { it.isDirectory } ?: File(home)
    return File(docs, "BNM Lab Reports").absolutePath
}

actual fun archiveReportFile(sourcePath: String, dir: String, relativePath: String): String = try {
    val src = File(sourcePath)
    if (!src.isFile || dir.isBlank()) "" else {
        val target = File(dir, relativePath)
        target.parentFile?.mkdirs()
        src.copyTo(target, overwrite = true)
        target.absolutePath
    }
} catch (e: Exception) {
    ""   // a full disk or an unplugged drive must never fail a print
}

actual fun revealInFileManager(path: String): String = try {
    val f = File(path)
    val dir = if (f.isDirectory) f else f.parentFile ?: f
    if (!dir.exists()) dir.mkdirs()
    val os = System.getProperty("os.name").orEmpty().lowercase()
    val cmd = when {
        os.contains("mac") -> arrayOf("open", dir.absolutePath)
        os.contains("win") -> arrayOf("explorer", dir.absolutePath)
        else -> arrayOf("xdg-open", dir.absolutePath)
    }
    ProcessBuilder(*cmd).start()
    "Opened ${dir.name}"
} catch (e: Exception) {
    "Could not open the folder: ${e.message}"
}

actual fun pickFolder(title: String): String? = try {
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    val chooser = JFileChooser().apply {
        dialogTitle = title
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        isAcceptAllFileFilterUsed = false
    }
    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile?.absolutePath
    } else null
} catch (e: Exception) {
    null
}
