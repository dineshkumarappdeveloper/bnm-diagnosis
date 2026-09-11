package com.bnm.lab.billing

import com.russhwolf.settings.Settings

/**
 * Printer configuration PER DOCUMENT KIND.
 *
 * A lab genuinely prints its three things on different hardware: the invoice on
 * an 80mm thermal roll at the counter, the test report on an A4 laser in the
 * back office, and the sample-tube STICKERS on a small thermal label printer at
 * the phlebotomy desk. Until now the first two read one shared [BillingPrefs]
 * block, so configuring one silently reconfigured the other.
 *
 * MIGRATION MATTERS HERE. The old shared keys are still the INVOICE profile's
 * keys, so a counter that was already set up keeps working untouched. The REPORT
 * profile is seeded ONCE from those same values ([PrintProfiles.migrateOnce]) —
 * not defaulted to "system" — because reports were previously printed on exactly
 * that shared thermal config, and silently repointing them at the OS print dialog
 * would break a working lab on upgrade. After the seed the two diverge freely.
 */
class PrintProfile(private val kind: PrintKind) {
    private val s: Settings = Settings()

    /** Invoice keeps the LEGACY key names, so existing setups migrate by doing nothing. */
    private fun key(base: String): String =
        if (kind == PrintKind.INVOICE) base else "${base}_${kind.slug}"

    var enabled: Boolean
        get() = s.getBoolean(key(BillingPrefs.K_PRINTER_ON), true)
        set(v) = s.putBoolean(key(BillingPrefs.K_PRINTER_ON), v)

    /** "system" = OS print dialog / AirPrint · "network" = raw ESC/POS over TCP ·
     *  "bluetooth" = ESC/POS over BT. A4 reports normally want "system". */
    var connection: String
        get() = s.getString(key(BillingPrefs.K_CONN), "system")
        set(v) = s.putString(key(BillingPrefs.K_CONN), v)

    var ip: String
        get() = s.getString(key(BillingPrefs.K_IP), "")
        set(v) = s.putString(key(BillingPrefs.K_IP), v.trim())

    var port: Int
        get() = s.getInt(key(BillingPrefs.K_PORT), 9100)
        set(v) = s.putInt(key(BillingPrefs.K_PORT), v)

    var btAddress: String
        get() = s.getString(key(BillingPrefs.K_BT_ADDR), "")
        set(v) = s.putString(key(BillingPrefs.K_BT_ADDR), v.trim())

    var btName: String
        get() = s.getString(key(BillingPrefs.K_BT_NAME), "")
        set(v) = s.putString(key(BillingPrefs.K_BT_NAME), v)

    /**
     * INVOICE only — which receipt design this counter issues:
     *  • "thermal" — narrow monospace docket for 58/80mm rolls.
     *  • "a4"      — full-page laid-out tax invoice (system print dialog; the
     *    transport settings below then only matter for collection tokens).
     * Shares the legacy base key with BillingPrefs.receiptFormat, and migrates
     * the old "A4 = 64-char width" choice the same way.
     */
    var receiptFormat: String
        get() = s.getStringOrNull(key(BillingPrefs.K_FORMAT))
            ?: if (s.getInt(key(BillingPrefs.K_PAPER), 32) >= 64) "a4" else "thermal"
        set(v) = s.putString(key(BillingPrefs.K_FORMAT), v)

    /** Character width: 32 ≈ 58mm roll, 48 ≈ 80mm roll, 64 ≈ A4. */
    var paperWidth: Int
        get() = s.getInt(key(BillingPrefs.K_PAPER), if (kind == PrintKind.REPORT) 64 else 32)
        set(v) = s.putInt(key(BillingPrefs.K_PAPER), v)

    /** Print without asking when a printer is configured and reachable.
     *  Stickers default to OFF: the user asked for an OPTION after registration,
     *  and a phlebotomy desk that is not this counter should not start a label
     *  printer unasked. */
    var autoPrint: Boolean
        get() = s.getBoolean(key(BillingPrefs.K_AUTOPRINT), kind == PrintKind.INVOICE)
        set(v) = s.putBoolean(key(BillingPrefs.K_AUTOPRINT), v)

    // ── STICKER-only settings (harmless on the other kinds; never read there) ──

    /** Printer command language — see [com.bnm.lab.print.LabelLanguage]. */
    var labelLanguage: String
        get() = s.getString(key("pref_label_language"), "tspl")
        set(v) = s.putString(key("pref_label_language"), v)

    /** Label stock, mm. 50 x 25 with a 2 mm gap is the common Indian lab roll. */
    var stickerWidthMm: Int
        get() = s.getInt(key("pref_sticker_w_mm"), 50).coerceIn(20, 120)
        set(v) = s.putInt(key("pref_sticker_w_mm"), v.coerceIn(20, 120))

    var stickerHeightMm: Int
        get() = s.getInt(key("pref_sticker_h_mm"), 25).coerceIn(10, 120)
        set(v) = s.putInt(key("pref_sticker_h_mm"), v.coerceIn(10, 120))

    var stickerGapMm: Int
        get() = s.getInt(key("pref_sticker_gap_mm"), 2).coerceIn(0, 10)
        set(v) = s.putInt(key("pref_sticker_gap_mm"), v.coerceIn(0, 10))

    /** 203 or 300 — the head's resolution; bitmap fonts and bars are in dots. */
    var stickerDpi: Int
        get() = if (s.getInt(key("pref_sticker_dpi"), 203) >= 300) 300 else 203
        set(v) = s.putInt(key("pref_sticker_dpi"), if (v >= 300) 300 else 203)

    /** Roll loaded the other way round → print the label upside down. */
    var stickerRotate: Boolean
        get() = s.getBoolean(key("pref_sticker_rotate"), false)
        set(v) = s.putBoolean(key("pref_sticker_rotate"), v)

    /** Print-position nudge, mm (±30, 0.5 steps): +x right, +y down. Wide
     *  enough to reach any phase of a label pitch on a sensorless receipt
     *  printer, where the vertical shift IS the alignment. */
    var stickerShiftXmm: Float
        get() = s.getFloat(key("pref_sticker_shift_x"), 0f).coerceIn(-30f, 30f)
        set(v) = s.putFloat(key("pref_sticker_shift_x"), v.coerceIn(-30f, 30f))

    var stickerShiftYmm: Float
        get() = s.getFloat(key("pref_sticker_shift_y"), 0f).coerceIn(-30f, 30f)
        set(v) = s.putFloat(key("pref_sticker_shift_y"), v.coerceIn(-30f, 30f))

    /** For [connection] == "system" on the STICKER profile: the OS printer that
     *  receives RAW label bytes through the spooler (a USB label printer). The
     *  print DIALOG is useless for labels — it would rasterise a page. */
    var printerName: String
        get() = s.getString(key("pref_printer_name"), "")
        set(v) = s.putString(key("pref_printer_name"), v.trim())

    /** How many copies to send. Labs often want two invoice copies (lab + patient). */
    var copies: Int
        get() = s.getInt(key("pref_print_copies"), 1).coerceIn(1, 5)
        set(v) = s.putInt(key("pref_print_copies"), v.coerceIn(1, 5))

    /** True when this profile can actually reach a printer without a dialog.
     *  For stickers a named OS printer counts: raw bytes go straight to it. */
    val isDirectlyConnected: Boolean
        get() = when (connection) {
            "network" -> ip.isNotBlank()
            "bluetooth" -> btAddress.isNotBlank()
            "system" -> kind == PrintKind.BARCODE && printerName.isNotBlank()
            else -> false
        }

    /** One-line summary for the settings row, e.g. "LAN 192.168.1.50:9100 · 80mm". */
    val summary: String
        get() {
            if (!enabled) return "Printing off"
            if (kind == PrintKind.INVOICE && receiptFormat == "a4") return "A4 sheet · system dialog"
            val where = when (connection) {
                "network" -> if (ip.isBlank()) "LAN (no IP set)" else "LAN $ip:$port"
                "bluetooth" -> btName.ifBlank { btAddress }.ifBlank { "Bluetooth (none selected)" }
                else -> if (kind == PrintKind.BARCODE) printerName.ifBlank { "USB (no printer chosen)" }
                        else "System print dialog"
            }
            if (kind == PrintKind.BARCODE) {
                return "$where · ${labelLanguage.uppercase()} · ${stickerWidthMm}x${stickerHeightMm} mm"
            }
            val paper = when (paperWidth) {
                32 -> "58mm"
                48 -> "80mm"
                else -> "A4"
            }
            return "$where · $paper"
        }
}

enum class PrintKind(val slug: String, val title: String, val blurb: String) {
    INVOICE("invoice", "Invoice / bill", "Receipts and GST bills printed at the counter"),
    REPORT("report", "Test report", "Patient reports handed over or posted"),
    /** Sample-tube labels: accession barcode + patient, one per tube. Always a
     *  thermal LABEL printer; what varies is the sticker stock. */
    BARCODE("barcode", "Barcode sticker", "Accession barcodes stuck on every sample tube"),
}

object PrintProfiles {
    val invoice get() = PrintProfile(PrintKind.INVOICE)
    val report get() = PrintProfile(PrintKind.REPORT)
    val barcode get() = PrintProfile(PrintKind.BARCODE)

    fun of(kind: PrintKind) = PrintProfile(kind)

    private const val K_MIGRATED = "pref_print_profiles_migrated_v1"

    /**
     * Seed the REPORT profile from the previously-shared printer settings, once.
     *
     * Before profiles existed, `printThermalSlip` for reports read the very same
     * keys the invoice printer used. Defaulting reports to "system" on upgrade
     * would therefore stop a working thermal report dead. Copying the old values
     * across makes the upgrade a no-op, which is what an upgrade should be.
     *
     * Idempotent, and skipped entirely on a fresh install (nothing to inherit) so
     * a new lab still gets the sensible A4 default for reports.
     */
    fun migrateOnce() {
        val s = Settings()
        if (s.getBoolean(K_MIGRATED, false)) return
        val hadLegacySetup = s.hasKey(BillingPrefs.K_CONN)
        if (hadLegacySetup) {
            val legacy = BillingPrefs()
            report.apply {
                connection = legacy.printerConnection
                ip = legacy.printerIp
                port = legacy.printerPort
                btAddress = legacy.printerBtAddress
                btName = legacy.printerBtName
                paperWidth = legacy.paperWidth
                enabled = legacy.printerEnabled
                // autoPrint deliberately NOT copied: auto-printing a patient
                // report the instant it is approved is not the same decision as
                // auto-printing a receipt at the counter, and the release gate
                // now stands between approval and printing anyway.
            }
        }
        s.putBoolean(K_MIGRATED, true)
    }
}
