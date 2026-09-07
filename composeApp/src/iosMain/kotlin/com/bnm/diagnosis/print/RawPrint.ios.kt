package com.bnm.diagnosis.print

actual val rawPrintSupported: Boolean = false
actual fun listRawPrinters(): List<String> = emptyList()
actual fun printRaw(printerName: String, payload: ByteArray): String =
    "USB sticker printing isn't available on iOS — use LAN or Bluetooth."
