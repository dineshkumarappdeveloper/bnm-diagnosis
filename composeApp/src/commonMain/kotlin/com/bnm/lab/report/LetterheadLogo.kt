package com.bnm.lab.report

import kotlin.io.encoding.Base64

/**
 * Letterhead logos — the two marks either side of the lab's name.
 *
 * A printed lab report is the lab's public face, and every reference-lab sheet
 * carries artwork: an emblem on one side, the practice's wordmark on the other.
 * Those are pictures, not text, so [ReportPrefs.letterheadLines] cannot express
 * them and the lab should not need a new build to change one.
 *
 * WHERE THEY LIVE. In `lab_settings` (the database), as base64 PNG — NOT in the
 * Settings store. On desktop that store is `java.util.prefs`, whose values are
 * capped at 8 KB; a logo is tens of kilobytes, so it would fail to save with an
 * error nobody would connect to the picture they just chose.
 */
object LetterheadLogo {

    /** `lab_settings` keys. Left and right are independent: a lab may set one. */
    const val KEY_LEFT = "report_logo_left_png"
    const val KEY_RIGHT = "report_logo_right_png"

    /**
     * Longest edge, pixels, after the picker re-encodes. A letterhead logo
     * prints about 25 mm tall at 300 dpi ≈ 300 px, so 600 is already generous —
     * and it keeps a phone-camera JPEG from becoming a multi-megabyte database
     * row that is copied into every backup and every sync.
     */
    const val MAX_EDGE_PX = 600

    /** Refuse anything that would bloat the row even after downscaling. */
    const val MAX_BYTES = 512 * 1024

    enum class Side { LEFT, RIGHT }

    fun keyFor(side: Side): String = when (side) {
        Side.LEFT -> KEY_LEFT
        Side.RIGHT -> KEY_RIGHT
    }

    /**
     * Stored string → PNG bytes. Tolerates a `data:image/png;base64,` prefix
     * and stray whitespace, and answers null rather than throwing: a letterhead
     * that will not decode must cost the lab its logo, never the report.
     */
    fun decode(stored: String?): ByteArray? {
        val raw = stored?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val payload = raw.substringAfter("base64,", raw).filterNot { it.isWhitespace() }
        return runCatching { Base64.Default.decode(payload) }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /** PNG bytes → the stored string. */
    fun encode(png: ByteArray): String = Base64.Default.encode(png)
}

/**
 * Ask the platform for an image file and return it as PNG bytes, downscaled to
 * [LetterheadLogo.MAX_EDGE_PX]. Null when the person cancelled, and null (never
 * a throw) when the file could not be read as an image — a picker that crashes
 * the app because someone chose a PDF is worse than one that does nothing.
 *
 * Implemented on desktop, where labs actually run this. The mobile targets
 * return null and their Settings screen hides the row rather than offering a
 * button that does nothing.
 */
expect suspend fun pickLetterheadLogoPng(): ByteArray?

/** True where [pickLetterheadLogoPng] can do anything at all. */
expect fun letterheadLogoPickerSupported(): Boolean
