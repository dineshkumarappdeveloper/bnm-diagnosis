package com.bnm.diagnosis.lab

import com.russhwolf.settings.Settings

/** Persisted device-local LIMS preferences (same Settings store as BillingPrefs). */
class DiagnosisPrefs {
    private val s: Settings = Settings()

    /** This device's accession seat code — its own never-rewind number series
     *  (`ACC-S1-00042`), so multi-seat offline devices can never collide.
     *  Mirrors the billing counter-series concept. */
    var accessionSeat: String
        get() = s.getString(K_SEAT, "S1").ifBlank { "S1" }
        set(v) { s.putString(K_SEAT, v.trim().ifBlank { "S1" }) }

    /** Accession number prefix (lab-configurable later; 'ACC' default). */
    var accessionPrefix: String
        get() = s.getString(K_PREFIX, "ACC").ifBlank { "ACC" }
        set(v) { s.putString(K_PREFIX, v.trim().ifBlank { "ACC" }) }

    private companion object {
        const val K_SEAT = "pref_accession_seat"
        const val K_PREFIX = "pref_accession_prefix"
    }
}
