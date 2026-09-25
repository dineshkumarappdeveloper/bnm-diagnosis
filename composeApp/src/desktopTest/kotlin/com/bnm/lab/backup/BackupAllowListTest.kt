package com.bnm.lab.backup

import com.russhwolf.settings.PropertiesSettings
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What travels in a backup is an allow-list, never a dump of the shared
 * preferences node — and the device identity is refused even when a backup
 * carries it, because a restored PC must mint its own.
 */
class BackupAllowListTest {

    private val forbidden = listOf(
        "license_device_id", "license_device_token", "license_device_row_id", "license_seat_no", "license_blocked",
        "session_token", "user_id", "user_email", "selected_business_id", "sync_pull_cursor", "sync_push_at_order",
        "staff_pin_salt", "lab_backup_dek", "lab_backup_dir", "lab_backup_seq", "lab_backup_code",
    )
    private val carried = listOf(
        "report_letterhead_mode", "report_lh_address", "report_archive_dir", "pref_accession_prefix",
        "pref_printer_connection_report", "pref_printed_invoice_ids", "lims_device_name", "counter_upi_vpa",
        "ui_theme_choice", "lab_license_fp", "license_jwt", "license_lab_name", "license_mode", "license_seats",
        "license_expires_at", "license_business_id", "license_last_seen_now",
    )

    @Test
    fun `every forbidden key is refused and every carried key travels`() {
        for (k in forbidden) assertFalse(BackupAllowList.carries(k), "$k must never travel")
        for (k in carried) assertTrue(BackupAllowList.carries(k), "$k must travel")
        assertFalse(BackupAllowList.carries("notif_device_id"), "another BNM app's key is not ours to carry")
        assertFalse(BackupAllowList.carries("lab_backup_anything_future"), "the whole lab_backup_ prefix is refused")
    }

    @Test
    fun `collect reads only the carried keys from a store, sorted`() {
        val s = PropertiesSettings(Properties())
        s.putString("report_lh_address", "12 Main Rd")
        s.putString("license_jwt", "a.b.c")
        s.putString("license_device_id", "dev-1")
        s.putString("session_token", "tok")
        s.putString("lab_backup_dek", "secret")
        s.putBoolean("report_archive_on", false)
        s.putInt("license_seats", 2)
        val out = BackupAllowList.collect(s)
        assertEquals(listOf("license_jwt", "license_seats", "report_archive_on", "report_lh_address"), out.keys.toList())
        assertEquals("false", out["report_archive_on"], "raw string values, typed reads still work on the other side")
        assertEquals("2", out["license_seats"])
    }

    @Test
    fun `apply filters again on the way in - a planted device identity never lands`() {
        val s = PropertiesSettings(Properties())
        val written = BackupAllowList.apply(
            mapOf(
                "report_lh_address" to "12 Main Rd",
                "license_device_id" to "cloned-device",
                "sync_pull_cursor" to "99",
                "lab_backup_dek" to "planted",
                "staff_pin_salt" to "salt",
            ),
            s,
        )
        assertEquals(listOf("report_lh_address"), written)
        assertNull(s.getStringOrNull("license_device_id"))
        assertNull(s.getStringOrNull("sync_pull_cursor"))
        assertNull(s.getStringOrNull("lab_backup_dek"))
        assertNull(s.getStringOrNull("staff_pin_salt"))
    }

    @Test
    fun `the fingerprint changes with a carried key and ignores the rest`() {
        val s = PropertiesSettings(Properties())
        s.putString("report_lh_address", "12 Main Rd")
        val a = BackupAllowList.fingerprint(s)
        s.putString("session_token", "changed")
        s.putString("lab_backup_seq", "5")
        assertEquals(a, BackupAllowList.fingerprint(s), "a session or engine key is not a backed-up change")
        s.putString("report_lh_address", "14 Main Rd")
        assertNotEquals(a, BackupAllowList.fingerprint(s))
    }
}
