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

    /**
     * How the A4 report splits across sheets — a [ReportPagination] slug.
     * Defaults to ONE TEST PER PAGE: that is how the labs this ships to file
     * and hand over reports (user rule, 2026-09-07). An unknown slug is
     * coerced back to that default rather than stored.
     */
    var paginationSlug: String
        get() = s.getString(K_PAGINATION, ReportPagination.PER_TEST.slug)
        set(v) = s.putString(K_PAGINATION, ReportPagination.fromSlug(v).slug)

    fun pagination(): ReportPagination = ReportPagination.fromSlug(paginationSlug)

    /**
     * Per-test release: each test can be verified, approved and printed on its
     * own, so an outsourced test no longer holds back the rest of its order.
     * Off by default — the whole order is signed and printed at once.
     */
    var releasePerTest: Boolean
        get() = s.getBoolean(K_RELEASE_PER_TEST, false)
        set(v) = s.putBoolean(K_RELEASE_PER_TEST, v)

    /**
     * How a released report reaches the patient on WhatsApp: not at all, by
     * opening WhatsApp with the message ready ([WaShareMode.LINK]), or sent by
     * the lab's own WhatsApp Business number ([WaShareMode.API]). Off by
     * default — a lab turns it on once it knows which one it wants.
     */
    var waShareSlug: String
        get() = s.getString(K_WA_MODE, WaShareMode.OFF.slug)
        set(v) = s.putString(K_WA_MODE, WaShareMode.fromSlug(v).slug)

    fun waShareMode(): WaShareMode = WaShareMode.fromSlug(waShareSlug)

    /** Country code prefixed to a bare local number ("91" for India). */
    var waCountryCode: String
        get() = s.getString(K_WA_CC, "91").filter { it.isDigit() }.ifEmpty { "91" }
        set(v) = s.putString(K_WA_CC, v.filter { it.isDigit() }.take(4).ifEmpty { "91" })

    /**
     * Send the report PDF itself rather than a download link, when WhatsApp
     * opens from this device ([WaShareMode.LINK]). On/by default: a patient
     * would rather have the file than a link, and the file needs no server —
     * which is what makes this work on an offline licence too.
     */
    var waSendPdf: Boolean
        get() = s.getBoolean(K_WA_PDF, true)
        set(v) = s.putBoolean(K_WA_PDF, v)

    /** Offer the referring doctor as a recipient too (their number is on the referrer row). */
    var waSendToReferrer: Boolean
        get() = s.getBoolean(K_WA_REFERRER, false)
        set(v) = s.putBoolean(K_WA_REFERRER, v)

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
        const val K_PAGINATION = "report_pagination"
        const val K_RELEASE_PER_TEST = "report_release_per_test"
        const val K_WA_MODE = "report_wa_mode"
        const val K_WA_CC = "report_wa_country_code"
        const val K_WA_REFERRER = "report_wa_to_referrer"
        const val K_WA_PDF = "report_wa_send_pdf"
    }
}
