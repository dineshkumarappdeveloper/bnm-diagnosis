package com.bnm.lab.backup

import com.russhwolf.settings.Settings
import java.util.prefs.Preferences

/**
 * The engine's own preferences. Every key is prefixed `lab_backup_` because the
 * `java.util.prefs` root node is shared with other BNM desktop apps on the same
 * OS user, and [BackupAllowList] refuses the whole prefix so none of these ever
 * travel inside a backup (the vault key least of all).
 *
 * [settings] is injectable so tests use an in-memory store — on JVM the no-arg
 * store is the operator's REAL preferences. [flush] pushes writes to the OS
 * store (the Windows registry writes through; macOS and Linux sync on a timer),
 * called before the process exits after a restore.
 */
internal class BackupPrefs(
    private val settings: Settings = Settings(),
    private val flush: () -> Unit = { runCatching { Preferences.userRoot().flush() } },
) {
    /** The store itself, for the allow-list (carried prefs live beside these). */
    val store: Settings get() = settings

    // ── the bound pendrive / vault ──
    var dir: String? by str(K_DIR)
    var vaultId: String? by str(K_ID)
    /** Base64 of the 32-byte data key. Only ever on the lab PC (and a PC that restored from it). */
    var dek: String? by str(K_DEK)
    /** JSON of [VaultSlots] — the salts and wrapped keys copied into every container header. */
    var slots: String? by str(K_SLOTS)
    /** The normalised recovery code, so an owner can see or print it again. Absent on a PC that restored with the licence key. */
    var recoveryCode: String? by str(K_CODE)
    var seq: Long
        get() = settings.getLong(K_SEQ, 0L)
        set(v) = settings.putLong(K_SEQ, v)

    // ── state that must survive a relaunch ──
    var lastOkAt: Long? by long(K_LAST_OK)
    var lastVerifiedAt: Long? by long(K_LAST_VERIFIED)
    /** yyyy-MM-dd of the last read-back verification, so it runs at most once a day. */
    var lastVerifiedDay: String? by str(K_LAST_VERIFIED_DAY)
    /** Set on the clean→dirty transition only; a crash before the snapshot still shows "changes waiting". */
    var dirtySince: Long? by long(K_DIRTY_SINCE)
    /** JSON of the previous generation's row counts (the row-count-drop guard compares against it). */
    var lastCounts: String? by str(K_LAST_COUNTS)
    var retentionPaused: Boolean
        get() = settings.getBoolean(K_RETENTION_PAUSED, false)
        set(v) = settings.putBoolean(K_RETENTION_PAUSED, v)
    /** A tenant wipe or a restore is about to shrink the database on purpose. */
    var expectDrop: Boolean
        get() = settings.getBoolean(K_EXPECT_DROP, false)
        set(v) = settings.putBoolean(K_EXPECT_DROP, v)
    var writeErrors: Long
        get() = settings.getLong(K_WRITE_ERRORS, 0L)
        set(v) = settings.putLong(K_WRITE_ERRORS, v)

    // ── restore ──
    /** The old PC's device id this install was restored from; cleared by an online activation. */
    var restoredFrom: String? by str(K_RESTORED_FROM)
    var restoredAt: Long? by long(K_RESTORED_AT)
    /** The old PC's seat row under the licence — the seat to take over when BNM answers "all seats in use". */
    var restoredFromRowId: String? by str(K_RESTORED_FROM_ROW)
    /** After a restore the pendrive marker still names the old PC; rewrite it once the new device id exists. */
    var ownerRebindPending: Boolean
        get() = settings.getBoolean(K_OWNER_REBIND, false)
        set(v) = settings.putBoolean(K_OWNER_REBIND, v)

    val isBound: Boolean get() = !dir.isNullOrBlank() && !vaultId.isNullOrBlank() && !dek.isNullOrBlank()
    val hasVault: Boolean get() = !vaultId.isNullOrBlank() && !dek.isNullOrBlank()

    /** Unbind the pendrive but keep the vault key, so re-binding the same stick reads its old generations. */
    fun unbind() {
        settings.remove(K_DIR)
    }

    /** The vault belonged to the previous lab: forget all of it. */
    fun retireVault() {
        for (k in listOf(
            K_DIR, K_ID, K_DEK, K_SLOTS, K_CODE, K_SEQ, K_LAST_OK, K_LAST_VERIFIED, K_LAST_VERIFIED_DAY,
            K_DIRTY_SINCE, K_LAST_COUNTS, K_RETENTION_PAUSED, K_EXPECT_DROP, K_WRITE_ERRORS,
            K_RESTORED_FROM, K_RESTORED_AT, K_RESTORED_FROM_ROW, K_OWNER_REBIND,
        )) settings.remove(k)
    }

    fun flushNow() = flush()

    private fun str(key: String) = object : kotlin.properties.ReadWriteProperty<Any?, String?> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): String? =
            settings.getStringOrNull(key)?.takeIf { it.isNotBlank() }
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: String?) {
            if (value.isNullOrBlank()) settings.remove(key) else settings.putString(key, value)
        }
    }

    private fun long(key: String) = object : kotlin.properties.ReadWriteProperty<Any?, Long?> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): Long? =
            settings.getLongOrNull(key)?.takeIf { it > 0 }
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Long?) {
            if (value == null) settings.remove(key) else settings.putLong(key, value)
        }
    }

    companion object {
        const val K_DIR = "lab_backup_dir"
        const val K_ID = "lab_backup_id"
        const val K_DEK = "lab_backup_dek"
        const val K_SLOTS = "lab_backup_slots"
        const val K_CODE = "lab_backup_code"
        const val K_SEQ = "lab_backup_seq"
        const val K_LAST_OK = "lab_backup_last_ok"
        const val K_LAST_VERIFIED = "lab_backup_last_verified"
        const val K_LAST_VERIFIED_DAY = "lab_backup_last_verified_day"
        const val K_DIRTY_SINCE = "lab_backup_dirty_since"
        const val K_LAST_COUNTS = "lab_backup_last_counts"
        const val K_RETENTION_PAUSED = "lab_backup_retention_paused"
        const val K_EXPECT_DROP = "lab_backup_expect_drop"
        const val K_WRITE_ERRORS = "lab_backup_write_errors"
        const val K_RESTORED_FROM = "lab_backup_restored_from"
        const val K_RESTORED_AT = "lab_backup_restored_at"
        const val K_RESTORED_FROM_ROW = "lab_backup_restored_from_row"
        const val K_OWNER_REBIND = "lab_backup_owner_rebind"
    }
}
