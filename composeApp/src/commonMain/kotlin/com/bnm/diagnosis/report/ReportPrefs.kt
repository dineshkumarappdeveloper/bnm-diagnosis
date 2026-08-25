package com.bnm.diagnosis.report

import com.russhwolf.settings.Settings

/**
 * Persisted device-local report/letterhead preferences (same Settings store as
 * BillingPrefs/DiagnosisPrefs/LimsPrefs — new keys, no overlap). The lab NAME
 * is deliberately NOT here: it always comes from the LicenseManager (read-only
 * in-app); these prefs only carry the editable letterhead lines around it.
 */
class ReportPrefs {
    private val s: Settings = Settings()

    /** "printed" = app draws the letterhead; "preprinted" = leave the space blank. */
    var letterheadMode: String
        get() = s.getString(K_MODE, "printed")
        set(v) = s.putString(K_MODE, if (v == "preprinted") "preprinted" else "printed")

    fun mode(): LetterheadMode =
        if (letterheadMode == "preprinted") LetterheadMode.PREPRINTED else LetterheadMode.PRINTED

    /** Reserved header space per page, mm (both modes honour it). */
    var headerMm: Int
        get() = s.getInt(K_HEADER_MM, 40)
        set(v) = s.putInt(K_HEADER_MM, v.coerceIn(0, 120))

    /** Reserved footer space per page, mm (both modes honour it). */
    var footerMm: Int
        get() = s.getInt(K_FOOTER_MM, 20)
        set(v) = s.putInt(K_FOOTER_MM, v.coerceIn(0, 80))

    // ── Editable letterhead lines (drawn in 'printed' mode only) ──
    var addressLine: String
        get() = s.getString(K_ADDRESS, "")
        set(v) = s.putString(K_ADDRESS, v.trim())

    var phoneLine: String
        get() = s.getString(K_PHONE, "")
        set(v) = s.putString(K_PHONE, v.trim())

    var emailLine: String
        get() = s.getString(K_EMAIL, "")
        set(v) = s.putString(K_EMAIL, v.trim())

    /** Free extra line, e.g. "NABL accredited · GSTIN …". */
    var extraLine: String
        get() = s.getString(K_EXTRA, "")
        set(v) = s.putString(K_EXTRA, v.trim())

    /** Accent colour 0xRRGGBB; one of [ReportPalette.presets]. */
    var accentRgb: Int
        get() = s.getInt(K_ACCENT, ReportPalette.TEAL)
        set(v) = s.putInt(K_ACCENT, v)

    /** The letterhead lines as printed: address, then phone/email combined, then extra. */
    fun letterheadLines(): List<String> {
        val contact = listOf(
            phoneLine.takeIf { it.isNotBlank() }?.let { "Ph: $it" },
            emailLine.takeIf { it.isNotBlank() },
        ).filterNotNull().joinToString("  ·  ")
        return listOf(addressLine, contact, extraLine).filter { it.isNotBlank() }
    }

    private companion object {
        const val K_MODE = "report_letterhead_mode"
        const val K_HEADER_MM = "report_header_mm"
        const val K_FOOTER_MM = "report_footer_mm"
        const val K_ADDRESS = "report_lh_address"
        const val K_PHONE = "report_lh_phone"
        const val K_EMAIL = "report_lh_email"
        const val K_EXTRA = "report_lh_extra"
        const val K_ACCENT = "report_accent_rgb"
    }
}
