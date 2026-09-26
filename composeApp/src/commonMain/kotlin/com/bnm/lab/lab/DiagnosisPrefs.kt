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

    /**
     * What this PC is FOR — Settings ▸ View mode.
     *
     * [ViewMode.FULL] is BNM Lab as it has always been. [ViewMode.MACHINE_ONLY]
     * is the bench PC of a lab that already runs its reporting elsewhere and
     * wants this one job only: watch the analyzer, print what it sends. It
     * registers no patient and raises no order, so nothing is keyed twice.
     *
     * DEVICE-local on purpose, exactly like the printer and the analyzer's own
     * connection: one lab can have a machine-only bench PC beside a full BNM
     * Lab at the front desk, and a licence change must not silently retune
     * either. It is not a licence flag and never syncs.
     */
    var viewMode: ViewMode
        get() = ViewMode.fromSlug(s.getString(K_VIEW_MODE, ViewMode.FULL.slug))
        set(v) = s.putString(K_VIEW_MODE, v.slug)

    /**
     * Referring doctor printed in machine-only mode when the operator types
     * none. The analyzer never sends one — HL7 carries an operator (OBR-32)
     * and a vet (OBR-10), neither of which is the referrer a CBC sheet names.
     */
    var machineReferrer: String
        get() = s.getString(K_MACHINE_REFERRER, "")
        set(v) = s.putString(K_MACHINE_REFERRER, v.trim())

    private companion object {
        const val K_LAST_EDITION = "lims_last_seen_edition"
        const val K_PREFIX = "pref_accession_prefix"
        const val K_VIEW_MODE = "pref_view_mode"
        const val K_MACHINE_REFERRER = "pref_machine_referrer"
    }
}

/**
 * What this PC shows when it opens — Settings ▸ View mode.
 *
 * Stored by [slug], never by ordinal: an enum reordered in a later build must
 * not silently turn a bench PC back into a full lab. An unknown slug (a
 * downgrade, a hand-edited settings file) reads as [FULL], because the safe
 * default is the app that can do everything rather than the one that cannot.
 */
enum class ViewMode(val slug: String, val label: String, val detail: String) {
    FULL(
        "full",
        "Full lab",
        "Patients, orders, results, billing and reports — everything BNM Lab does.",
    ),
    MACHINE_ONLY(
        "machine_only",
        "Machine only",
        "Home shows the analyzer's incoming results and nothing else. No patient " +
            "registration, no orders — the machine already has the details, you just print.",
    ),
    ;

    companion object {
        fun fromSlug(slug: String?): ViewMode =
            entries.firstOrNull { it.slug == slug?.trim() } ?: FULL
    }
}
