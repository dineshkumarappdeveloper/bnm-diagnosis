package com.bnm.lab.print

import java.io.File
import javax.print.DocFlavor
import javax.print.PrintServiceLookup
import javax.print.SimpleDoc
import javax.print.attribute.HashPrintRequestAttributeSet
import javax.print.attribute.standard.JobName

actual val rawPrintSupported: Boolean = true

/** javax.print's names ARE the OS queue names on every platform (CUPS printer
 *  name on macOS/Linux, the spooler name on Windows), so one list serves both
 *  transports below. */
actual fun listRawPrinters(): List<String> =
    runCatching { PrintServiceLookup.lookupPrintServices(null, null).map { it.name } }
        .getOrDefault(emptyList())

/**
 * Two genuinely different spoolers behind one call:
 *
 *  • WINDOWS — javax.print with the AUTOSENSE flavour hands the bytes to the
 *    spooler as a RAW datatype job (Win32PrintJob → StartDocPrinter "RAW"), so a
 *    TSPL/ZPL stream reaches the label printer untouched. Works with the vendor
 *    driver or "Generic / Text Only".
 *  • macOS / LINUX (CUPS) — javax.print is NOT raw there: the JDK just runs
 *    `lpr -P<queue> <file>` and CUPS auto-types the job. A TSPL or ZPL stream
 *    is printable ASCII, so CUPS calls it text/plain and rasterises the COMMAND
 *    WORDS onto the labels through the queue's filter chain. The only thing that
 *    bypasses the filters is an explicit raw job, so this path spools with
 *    `lp -d <queue> -o raw` itself and reads lp's exit code. (Found by review —
 *    the first draft trusted javax.print on both.)
 */
actual fun printRaw(printerName: String, payload: ByteArray): String {
    if (printerName.isBlank()) return "No sticker printer chosen — pick one in Settings ▸ Printing"
    val known = listRawPrinters()
    if (known.isNotEmpty() && printerName !in known) {
        return "Printer \"$printerName\" not found — is it installed and switched on?"
    }
    return if (isWindows) printRawViaSpooler(printerName, payload) else printRawViaCups(printerName, payload)
}

private val isWindows: Boolean
    get() = System.getProperty("os.name").orEmpty().lowercase().contains("win")

private fun printRawViaSpooler(printerName: String, payload: ByteArray): String {
    val service = runCatching {
        PrintServiceLookup.lookupPrintServices(null, null).firstOrNull { it.name == printerName }
    }.getOrNull() ?: return "Printer \"$printerName\" not found — is it installed and switched on?"
    return try {
        val job = service.createPrintJob()
        val attrs = HashPrintRequestAttributeSet().apply { add(JobName("BNM Lab stickers", null)) }
        job.print(SimpleDoc(payload, DocFlavor.BYTE_ARRAY.AUTOSENSE, null), attrs)
        "Sent to $printerName"
    } catch (e: Exception) {
        "Print failed: ${e.message}"
    }
}

private fun printRawViaCups(queue: String, payload: ByteArray): String {
    val tmp = runCatching { File.createTempFile("bnm-sticker-", ".prn") }
        .getOrElse { return "Print failed: ${it.message}" }
    return try {
        tmp.writeBytes(payload)
        val proc = ProcessBuilder("lp", "-d", queue, "-o", "raw", "-t", "BNM Lab stickers", tmp.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.bufferedReader().readText().trim()
        val code = proc.waitFor()
        if (code == 0) "Sent to $queue" else "Print failed: ${output.ifBlank { "lp exited with $code" }}"
    } catch (e: Exception) {
        "Print failed: ${e.message}"
    } finally {
        tmp.delete()
    }
}
