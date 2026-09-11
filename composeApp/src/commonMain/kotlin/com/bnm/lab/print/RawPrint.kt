package com.bnm.lab.print

/**
 * RAW bytes to an OS-installed printer, bypassing any page rendering.
 *
 * Label printers on USB (TSC, Xprinter, Zebra…) install as ordinary printers,
 * but what they want is their own command language — TSPL/ZPL — not a
 * rasterised page from the print dialog. The spooler can pass bytes through
 * untouched; that is what this does. Desktop only in practice: Android has no
 * spooler API for it and iOS none at all — those return a clear message and
 * the profile offers LAN/Bluetooth instead.
 */
expect fun listRawPrinters(): List<String>

/** Send [payload] verbatim to the printer named [printerName]. Returns a short
 *  operator-facing status; success starts with "Sent to". */
expect fun printRaw(printerName: String, payload: ByteArray): String

/** True where [printRaw] can work at all (desktop). */
expect val rawPrintSupported: Boolean
