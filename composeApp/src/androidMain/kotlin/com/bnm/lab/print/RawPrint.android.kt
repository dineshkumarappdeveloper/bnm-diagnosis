package com.bnm.lab.print

// Android's print framework rasterises documents; it has no raw pass-through
// to a USB label printer. Stickers on Android go over LAN or Bluetooth.
actual val rawPrintSupported: Boolean = false
actual fun listRawPrinters(): List<String> = emptyList()
actual fun printRaw(printerName: String, payload: ByteArray): String =
    "USB sticker printing isn't available on Android — use LAN or Bluetooth."
