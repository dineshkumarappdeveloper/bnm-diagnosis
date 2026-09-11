package com.bnm.lab.report

import android.content.Intent
import android.os.Environment
import androidx.core.content.FileProvider
import java.io.File

/**
 * Android's scoped storage: an app may write freely only inside its own
 * external directory. That directory IS visible in Files (Android/data/…), so
 * the archive still exists off the app — it just cannot live in the phone's
 * top-level Documents without the user picking it through the system picker,
 * which a bench tablet should not be asked to do on every report.
 */
actual fun defaultReportsDir(): String {
    val ctx = reportContext ?: return ""
    val base = ctx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: ctx.filesDir
    return File(base, "Reports").absolutePath
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
    ""
}

/** No file manager to command; open the folder's newest file instead. */
actual fun revealInFileManager(path: String): String {
    val ctx = reportContext ?: return "Not ready yet"
    val f = File(path)
    val target = if (f.isDirectory) f.listFiles()?.maxByOrNull { it.lastModified() } else f
    target ?: return "No reports filed yet"
    return try {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", target)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        "Opened ${target.name}"
    } catch (e: Exception) {
        "Could not open it: ${e.message}"
    }
}

/** The folder is fixed on Android (scoped storage) — nothing to pick. */
actual fun pickFolder(title: String): String? = null
