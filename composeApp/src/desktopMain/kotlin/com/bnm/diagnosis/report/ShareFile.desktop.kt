package com.bnm.diagnosis.report

import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.File

/** A file on the system clipboard, in the one flavour every desktop app reads
 *  (macOS NSFilenamesPboardType, Windows CF_HDROP, Linux text/uri-list). */
private class FileTransferable(private val files: List<File>) : Transferable {
    override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.javaFileListFlavor)
    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.javaFileListFlavor
    override fun getTransferData(flavor: DataFlavor): Any =
        if (flavor == DataFlavor.javaFileListFlavor) files else throw UnsupportedFlavorException(flavor)
}

actual fun shareFile(path: String, mimeType: String, text: String, waPhone: String?): String {
    val f = File(path)
    if (!f.exists()) return "The report file could not be found"
    val copied = runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(FileTransferable(listOf(f)), null)
    }.isSuccess
    val opened = waPhone?.let { openUrl(waDeepLink(it, text)) }.orEmpty()
    val mac = System.getProperty("os.name").orEmpty().lowercase().contains("mac")
    val paste = if (mac) "Cmd+V" else "Ctrl+V"
    return when {
        copied && opened.startsWith("Opened") -> "WhatsApp opened — press $paste to attach the report, then send"
        copied -> "Report copied — open the chat and press $paste to attach it"
        opened.startsWith("Opened") -> "WhatsApp opened — attach ${f.name} from ${f.parent}"
        else -> "Could not open WhatsApp — the report is saved at $path"
    }
}
