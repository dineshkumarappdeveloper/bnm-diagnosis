package com.bnm.lab.lab

import com.russhwolf.settings.Settings

/** Persisted device-local LIMS preferences (same Settings store as BillingPrefs). */
class DiagnosisPrefs {
    private val s: Settings = Settings()

    /**
     * The edition this device last RAN as. Compared with the licence on every
     * start: when it moves from offline to connected the app says once, in
     * plain words, what is about to be uploaded — a lab that chose an offline
     * edition deserves to be told the moment that stops being true.
     * Empty until the first run after this was added.
     */
    var lastSeenEdition: String
        get() = s.getString(K_LAST_EDITION, "")
        set(v) = s.putString(K_LAST_EDITION, v)

    /** Accession number prefix (lab-configurable later; 'ACC' default). */
    var accessionPrefix: String
        get() = s.getString(K_PREFIX, "ACC").ifBlank { "ACC" }
        set(v) { s.putString(K_PREFIX, v.trim().ifBlank { "ACC" }) }

    private companion object {
        const val K_LAST_EDITION = "lims_last_seen_edition"
        const val K_PREFIX = "pref_accession_prefix"
    }
}
