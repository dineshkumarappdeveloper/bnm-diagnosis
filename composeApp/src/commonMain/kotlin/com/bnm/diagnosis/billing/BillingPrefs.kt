package com.bnm.diagnosis.billing

import com.russhwolf.settings.Settings
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** Persisted device-local billing preferences (printer + barcode scanner). */
class BillingPrefs {
    private val s: Settings = Settings()

    // ── Printer ──
    var printerEnabled: Boolean
        get() = s.getBoolean(K_PRINTER_ON, true)
        set(v) = s.putBoolean(K_PRINTER_ON, v)

    /** Receipt character width: 32 ≈ 58mm roll, 48 ≈ 80mm roll, 64 ≈ A4. */
    var paperWidth: Int
        get() = s.getInt(K_PAPER, 32)
        set(v) = s.putInt(K_PAPER, v)

    var autoPrint: Boolean
        get() = s.getBoolean(K_AUTOPRINT, true)
        set(v) = s.putBoolean(K_AUTOPRINT, v)

    /** Print-once guard: invoice ids already printed on THIS device (rolling 100).
     *  Device-local by design — a receipt belongs to the counter that printed it. */
    fun isInvoicePrinted(id: String): Boolean =
        s.getString(K_PRINTED_IDS, "").split(',').contains(id)

    fun markInvoicePrinted(id: String) {
        val cur = s.getString(K_PRINTED_IDS, "").split(',').filter { it.isNotBlank() && it != id }
        s.putString(K_PRINTED_IDS, (cur + id).takeLast(100).joinToString(","))
    }

    /** "system" = OS print dialog / AirPrint / share; "network" = raw ESC/POS over TCP to a LAN
     *  thermal printer; "bluetooth" = ESC/POS over Bluetooth (Android SPP / iOS BLE). */
    var printerConnection: String
        get() = s.getString(K_CONN, "system")
        set(v) = s.putString(K_CONN, v)

    /** LAN IP of the ESC/POS printer (network mode), e.g. "192.168.1.50". */
    var printerIp: String
        get() = s.getString(K_IP, "")
        set(v) = s.putString(K_IP, v.trim())

    /** Raw-print TCP port — 9100 is the JetDirect / ESC-POS standard. */
    var printerPort: Int
        get() = s.getInt(K_PORT, 9100)
        set(v) = s.putInt(K_PORT, v)

    /** MAC (Android) / CBPeripheral UUID (iOS) of the selected Bluetooth printer. */
    var printerBtAddress: String
        get() = s.getString(K_BT_ADDR, "")
        set(v) = s.putString(K_BT_ADDR, v.trim())

    /** Friendly name of the selected Bluetooth printer, for the Settings label. */
    var printerBtName: String
        get() = s.getString(K_BT_NAME, "")
        set(v) = s.putString(K_BT_NAME, v)

    // ── Collection tokens (self-service stations) ──
    /** Next per-device DAILY token number. Stored as "yyyy-MM-dd:n"; resets to 1
     *  when the local date changes. Device-local by design — one token roll per
     *  counter, like the print-once guard. */
    fun nextTokenNumber(): Int {
        val today = kotlin.time.Clock.System.now()
            .toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()
        val cur = s.getString(K_TOKEN_COUNTER, "")
        val last = cur.substringAfterLast(':', "").toIntOrNull()
            ?.takeIf { cur.substringBeforeLast(':') == today } ?: 0
        val next = last + 1
        s.putString(K_TOKEN_COUNTER, "$today:$next")
        return next
    }

    /** Token number assigned to a bill — allocated once via [nextTokenNumber],
     *  then reused on reprints (same number on ALL that bill's station tokens).
     *  Rolling ~100 map, same vein as pref_printed_invoice_ids. */
    fun tokenNumberFor(invoiceId: String): Int {
        val entries = s.getString(K_TOKEN_NUMBERS, "").split(',').filter { it.isNotBlank() }
        entries.firstOrNull { it.substringBefore('=') == invoiceId }
            ?.substringAfter('=')?.toIntOrNull()?.let { return it }
        val n = nextTokenNumber()
        s.putString(K_TOKEN_NUMBERS, (entries + "$invoiceId=$n").takeLast(100).joinToString(","))
        return n
    }

    // ── Counter payment ──
    /** This counter's UPI ID (from /pair, kept fresh by counter sync). Empty = unset. */
    var counterUpiVpa: String
        get() = s.getString(K_UPI_VPA, "")
        set(v) = s.putString(K_UPI_VPA, v.trim())

    // ── Barcode scanner ──
    var barcodeEnabled: Boolean
        get() = s.getBoolean(K_BARCODE_ON, false)
        set(v) = s.putBoolean(K_BARCODE_ON, v)

    /** "wedge" = USB/Bluetooth keyboard scanner, "camera" = device camera. */
    var barcodeMode: String
        get() = s.getString(K_BARCODE_MODE, "wedge")
        set(v) = s.putString(K_BARCODE_MODE, v)

    private companion object {
        const val K_PRINTER_ON = "pref_printer_enabled"
        const val K_PAPER = "pref_paper_width"
        const val K_AUTOPRINT = "pref_auto_print"
        const val K_CONN = "pref_printer_connection"
        const val K_IP = "pref_printer_ip"
        const val K_PORT = "pref_printer_port"
        const val K_BT_ADDR = "pref_printer_bt_address"
        const val K_BT_NAME = "pref_printer_bt_name"
        const val K_UPI_VPA = "counter_upi_vpa"
        const val K_BARCODE_ON = "pref_barcode_enabled"
        const val K_BARCODE_MODE = "pref_barcode_mode"
        const val K_PRINTED_IDS = "pref_printed_invoice_ids"
        const val K_TOKEN_COUNTER = "pref_token_day_counter"
        const val K_TOKEN_NUMBERS = "pref_invoice_token_numbers"
    }
}
