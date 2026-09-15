package com.bnm.lab.report

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Read back a PDF that [writeLabReportPdf] just produced.
 *
 * Needed where the bytes have to go on the wire: the WhatsApp Business send
 * hands the rendered PDF to the server, which passes it straight to Meta.
 * Returns null when the file is missing or unreadable — the caller then sends
 * nothing rather than an empty document.
 */
expect fun readReportBytes(path: String): ByteArray?

/** [readReportBytes] as base64 for a JSON body, or null. */
@OptIn(ExperimentalEncodingApi::class)
fun readReportBase64(path: String): String? =
    readReportBytes(path)?.takeIf { it.isNotEmpty() }?.let { Base64.Default.encode(it) }
