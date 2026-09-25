package com.bnm.lab.backup

import com.russhwolf.settings.Settings
import java.security.MessageDigest

/**
 * Which preferences travel inside a backup — and which never do.
 *
 * Preferences live in `java.util.prefs`, NOT in the data directory, and the
 * node is shared with other BNM desktop apps on the same OS user. So a backup
 * never dumps the node: it carries an allow-list (letterhead, print profiles,
 * accession prefix, theme, and the signed licence token so a restored PC works
 * offline at once) and REFUSES the device identity, sessions and sync
 * watermarks even when a backup made by a future build carries them — a
 * restore must mint a fresh device id (fresh accession seat, fresh bill series)
 * and a connected edition must start its sync from zero (out of scope in v1).
 */
internal object BackupAllowList {
    private val CARRIED_PREFIXES = listOf("report_", "pref_", "lims_")
    private val CARRIED_KEYS = setOf(
        "counter_upi_vpa", "ui_theme_choice", "lab_license_fp",
        "license_jwt", "license_lab_name", "license_mode", "license_seats",
        "license_expires_at", "license_business_id", "license_last_seen_now",
    )

    /** The device's identity under its licence: a restore MINTS a new one. */
    val DEVICE_KEYS = listOf(
        "license_device_id", "license_device_token", "license_device_row_id",
        "license_seat_no", "license_blocked",
    )
    private val REFUSED_PREFIXES = listOf("session_", "user_", "selected_", "sync_", "lab_backup_")
    private val REFUSED_KEYS = DEVICE_KEYS.toSet() + "staff_pin_salt"

    /** Would this key travel? Refusals win over prefixes, always. */
    fun carries(key: String): Boolean {
        if (key in REFUSED_KEYS) return false
        if (REFUSED_PREFIXES.any { key.startsWith(it) }) return false
        if (key in CARRIED_KEYS) return true
        return CARRIED_PREFIXES.any { key.startsWith(it) }
    }

    /** The carried keys and their raw string values, sorted, from [settings]. */
    fun collect(settings: Settings): Map<String, String> {
        val out = sortedMapOf<String, String>()
        for (key in settings.keys) {
            if (!carries(key)) continue
            val v = settings.getStringOrNull(key) ?: continue
            out[key] = v
        }
        return out
    }

    /**
     * Write the carried entries of [prefs] into [settings] — filtered AGAIN on
     * the way in, so a tampered or future-format prefs.json cannot plant a
     * device identity or a sync watermark. Returns the keys written.
     */
    fun apply(prefs: Map<String, String>, settings: Settings): List<String> {
        val written = ArrayList<String>()
        for ((key, value) in prefs) {
            if (!carries(key)) continue
            settings.putString(key, value)
            written += key
        }
        return written
    }

    /** SHA-256 over the carried entries: a change means the operator edited a setting worth backing up. */
    fun fingerprint(settings: Settings): String {
        val md = MessageDigest.getInstance("SHA-256")
        for ((k, v) in collect(settings)) {
            md.update(k.toByteArray(Charsets.UTF_8)); md.update(0)
            md.update(v.toByteArray(Charsets.UTF_8)); md.update(10)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
