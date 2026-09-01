package com.bnm.diagnosis.print

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.print.PageFormat
import java.awt.print.Printable
import java.awt.print.PrinterJob

/** Replay the shared A4 ops with Graphics2D, scaled to the printable area. */
actual fun printA4(doc: A4Doc): String {
    val job = PrinterJob.getPrinterJob()
    job.jobName = doc.jobName
    job.setPrintable { graphics: Graphics, pf: PageFormat, pageIndex: Int ->
        if (pageIndex >= doc.pages.size) return@setPrintable Printable.NO_SUCH_PAGE
        val g = graphics as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.translate(pf.imageableX, pf.imageableY)
        val scale = minOf(pf.imageableWidth / A4_W, pf.imageableHeight / A4_H).toFloat()
        g.scale(scale.toDouble(), scale.toDouble())
        for (op in doc.pages[pageIndex]) when (op) {
            is A4Op.Text -> {
                g.font = Font(Font.SANS_SERIF, if (op.bold) Font.BOLD else Font.PLAIN, 100)
                    .deriveFont(op.size)
                g.color = Color(op.gray, op.gray, op.gray)
                val w = g.fontMetrics.stringWidth(op.text)
                val x = when (op.align) {
                    A4Align.LEFT -> op.x
                    A4Align.CENTER -> op.x - w / 2f
                    A4Align.RIGHT -> op.x - w
                }
                g.drawString(op.text, x, op.y)
            }
            is A4Op.Line -> {
                g.color = Color.BLACK
                g.stroke = BasicStroke(op.width)
                g.drawLine(op.x1.toInt(), op.y1.toInt(), op.x2.toInt(), op.y2.toInt())
            }
            is A4Op.Rect -> {
                g.color = Color(op.gray, op.gray, op.gray)
                g.fillRect(op.x.toInt(), op.y.toInt(), op.w.toInt(), op.h.toInt())
            }
        }
        Printable.PAGE_EXISTS
    }
    return try {
        if (job.printDialog()) { job.print(); "Sent to printer" } else "Print cancelled"
    } catch (e: Exception) {
        "Print failed: ${e.message}"
    }
}
