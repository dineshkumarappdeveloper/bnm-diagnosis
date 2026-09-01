package com.bnm.diagnosis.print

import android.app.Activity
import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.lang.ref.WeakReference

/** PrintManager.print() needs an ACTIVITY context — registered by MainActivity
 *  (weak: a finished activity must not leak through a static). */
private var a4Activity: WeakReference<Activity>? = null

fun initA4Print(activity: Activity) { a4Activity = WeakReference(activity) }

/** Render the shared ops into a PdfDocument, then hand the PDF to the native
 *  Android print preview (printer / Save as PDF). */
actual fun printA4(doc: A4Doc): String {
    val activity = a4Activity?.get() ?: return "Printer not ready — reopen the app"
    val file = try { renderPdf(activity, doc) } catch (e: Exception) { return "Print failed: ${e.message}" }
    Handler(Looper.getMainLooper()).post {
        try {
            val pm = activity.getSystemService(Context.PRINT_SERVICE) as PrintManager
            pm.print(doc.jobName, object : PrintDocumentAdapter() {
                override fun onLayout(
                    oldAttributes: PrintAttributes?, newAttributes: PrintAttributes?,
                    cancellationSignal: android.os.CancellationSignal?,
                    callback: LayoutResultCallback, extras: android.os.Bundle?,
                ) {
                    callback.onLayoutFinished(
                        PrintDocumentInfo.Builder(doc.jobName)
                            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                            .setPageCount(doc.pages.size).build(),
                        true,
                    )
                }
                override fun onWrite(
                    pages: Array<out PageRange>?, destination: ParcelFileDescriptor,
                    cancellationSignal: android.os.CancellationSignal?, callback: WriteResultCallback,
                ) {
                    try {
                        FileInputStream(file).use { i -> FileOutputStream(destination.fileDescriptor).use { o -> i.copyTo(o) } }
                        callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                    } catch (e: Exception) {
                        callback.onWriteFailed(e.message)
                    }
                }
            }, null)
        } catch (_: Exception) { /* the status below was already returned */ }
    }
    return "Opening print preview…"
}

private fun renderPdf(ctx: Context, doc: A4Doc): File {
    val pdf = PdfDocument()
    doc.pages.forEachIndexed { i, ops ->
        val page = pdf.startPage(PdfDocument.PageInfo.Builder(A4_W.toInt(), A4_H.toInt(), i + 1).create())
        val c = page.canvas
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        for (op in ops) when (op) {
            is A4Op.Text -> {
                paint.style = Paint.Style.FILL
                paint.typeface = if (op.bold) Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) else Typeface.SANS_SERIF
                paint.textSize = op.size
                val g = (op.gray * 255).toInt().coerceIn(0, 255)
                paint.color = android.graphics.Color.rgb(g, g, g)
                val w = paint.measureText(op.text)
                val x = when (op.align) {
                    A4Align.LEFT -> op.x
                    A4Align.CENTER -> op.x - w / 2f
                    A4Align.RIGHT -> op.x - w
                }
                c.drawText(op.text, x, op.y, paint)
            }
            is A4Op.Line -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = op.width
                paint.color = android.graphics.Color.BLACK
                c.drawLine(op.x1, op.y1, op.x2, op.y2, paint)
            }
            is A4Op.Rect -> {
                paint.style = Paint.Style.FILL
                val g = (op.gray * 255).toInt().coerceIn(0, 255)
                paint.color = android.graphics.Color.rgb(g, g, g)
                c.drawRect(op.x, op.y, op.x + op.w, op.y + op.h, paint)
            }
        }
        pdf.finishPage(page)
    }
    val dir = File(ctx.cacheDir, "a4-invoices").apply { mkdirs() }
    val safe = doc.jobName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "invoice" }
    val file = File(dir, "$safe.pdf")
    FileOutputStream(file).use { pdf.writeTo(it) }
    pdf.close()
    return file
}
