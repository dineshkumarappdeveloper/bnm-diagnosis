package com.bnm.diagnosis.print

import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.print.PageFormat
import java.awt.print.Printable
import java.awt.print.PrinterJob

/** Real desktop printing: opens the OS print dialog and prints the text receipt
 *  to whatever printer the user picks (USB / network / PDF). */
actual fun printReceipt(title: String, body: String): String {
    val job = PrinterJob.getPrinterJob()
    job.jobName = title
    job.setPrintable { graphics: Graphics, pageFormat: PageFormat, pageIndex: Int ->
        if (pageIndex > 0) return@setPrintable Printable.NO_SUCH_PAGE
        val g = graphics as Graphics2D
        g.translate(pageFormat.imageableX.toInt(), pageFormat.imageableY.toInt())
        g.font = Font(Font.MONOSPACED, Font.PLAIN, 10)
        var y = 14
        for (line in body.split("\n")) {
            g.drawString(line, 0, y)
            y += 14
        }
        Printable.PAGE_EXISTS
    }
    return try {
        if (job.printDialog()) { job.print(); "Sent to printer" } else "Print cancelled"
    } catch (e: Exception) {
        "Print failed: ${e.message}"
    }
}
