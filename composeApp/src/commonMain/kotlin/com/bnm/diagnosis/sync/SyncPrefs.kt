package com.bnm.diagnosis.sync

import com.russhwolf.settings.Settings

/**
 * P3 sync watermarks & cursors (multiplatform-settings, same store as the
 * other prefs holders). Watermarks are epoch millis of the newest LOCAL stamp
 * pushed per entity; cursors are the server `seq` high-water per stream.
 */
class SyncPrefs {
    private val s: Settings = Settings()

    /** Newest local stamp already pushed for [entity] (epoch ms; 0 = never). */
    fun lastPushAt(entity: String): Long = s.getLong("$K_PUSH_AT$entity", 0L)
    fun setLastPushAt(entity: String, epochMs: Long) = s.putLong("$K_PUSH_AT$entity", epochMs)

    /** Fingerprint of the last pushed test+panel catalog (they carry no stamps). */
    var catalogFingerprint: Long
        get() = s.getLong(K_CATALOG_FP, 0L)
        set(v) = s.putLong(K_CATALOG_FP, v)

    /** `lab_entities` pull cursor (server seq). */
    var pullCursor: Long
        get() = s.getLong(K_PULL_CURSOR, 0L)
        set(v) = s.putLong(K_PULL_CURSOR, v)

    /** `clinical_lab_orders` EMR-inbox cursor (server seq). */
    var emrCursor: Long
        get() = s.getLong(K_EMR_CURSOR, 0L)
        set(v) = s.putLong(K_EMR_CURSOR, v)

    /** ISO stamp of the last fully-successful sync (display only). */
    var lastSyncAt: String?
        get() = s.getStringOrNull(K_LAST_SYNC)
        set(v) = if (v == null) s.remove(K_LAST_SYNC) else s.putString(K_LAST_SYNC, v)

    /** Standalone license (server said 409 no_business) → home-screen note. */
    var syncDisabled: Boolean
        get() = s.getBoolean(K_DISABLED, false)
        set(v) = s.putBoolean(K_DISABLED, v)

    private companion object {
        const val K_PUSH_AT = "sync_push_at_"
        const val K_CATALOG_FP = "sync_catalog_fp"
        const val K_PULL_CURSOR = "sync_pull_cursor"
        const val K_EMR_CURSOR = "sync_emr_cursor"
        const val K_LAST_SYNC = "sync_last_at"
        const val K_DISABLED = "sync_disabled_no_business"
    }
}
