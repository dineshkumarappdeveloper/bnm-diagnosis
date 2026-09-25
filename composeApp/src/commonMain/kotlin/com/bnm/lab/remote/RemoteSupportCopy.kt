package com.bnm.lab.remote

import com.bnm.lab.screens.lab.shortTimeLabel

/**
 * Every sentence the lab reads about remote support, in one place — plain
 * English for people who run a laboratory, not a network. The dialog, the
 * banner and the Settings row all read from here so they never disagree.
 */
object RemoteSupportCopy {

    /** Session lengths the owner can pick, in seconds, with their labels. */
    val DURATIONS: List<Pair<Long, String>> = listOf(
        3_600L to "1 hour",
        4 * 3_600L to "4 hours",
        24 * 3_600L to "24 hours",
    )

    /** The pre-selected length. */
    const val DEFAULT_DURATION_S: Long = 4 * 3_600L

    fun durationLabel(durationS: Long): String =
        DURATIONS.firstOrNull { it.first == durationS }?.second ?: when {
            durationS % 3_600L == 0L -> "${durationS / 3_600L} hours"
            durationS >= 60L -> "${durationS / 60L} minutes"
            else -> "$durationS seconds"
        }

    /** The consent paragraph shown above the three tick boxes. */
    fun consentText(durationS: Long): String =
        "Start a BNM support session? For the next ${durationLabel(durationS)}, BNM's engineer can see " +
            "this computer's diagnostics — app and analyzer status, analyzer settings, the activity log " +
            "and record counts — and can change analyzer settings, restart analyzer links and start an " +
            "app update. Every action is recorded in Support history. They cannot see patient records, " +
            "results or analyzer data unless you allow it below. You can end the session at any time."

    const val CONSENT_ANALYZER_DATA = "Share analyzer data"
    const val CONSENT_ANALYZER_DATA_DETAIL =
        "raw frames from the analyzer — these carry sample IDs and, on some analyzers, patient names"
    const val CONSENT_RECORDS = "Allow looking up records"
    const val CONSENT_RECORDS_DETAIL = "patient names and results"
    const val CONSENT_SCREEN = "Allow screen view"
    const val CONSENT_SCREEN_DETAIL = "pictures of what is on this screen"

    const val READ_CODE = "Read this code to the BNM engineer"

    /** Wall-clock end of the session, formatted like every other time in the app ("18:30", or "26 Sep 18:30"). */
    fun endsAtLabel(session: SupportSession): String =
        shortTimeLabel(kotlin.time.Instant.fromEpochMilliseconds(session.startedAtMs + session.durationS * 1_000L).toString())

    /** "3 h 59 min left", "12 min left", "under a minute left". */
    fun remainingLabel(remainingS: Long): String {
        val h = remainingS / 3_600L
        val m = (remainingS % 3_600L) / 60L
        return when {
            remainingS <= 0L -> "ending"
            h > 0L -> "$h h $m min left"
            m > 0L -> "$m min left"
            else -> "under a minute left"
        }
    }

    /** One line of status for the dialog. */
    fun phaseLabel(status: RemoteSupportStatus): String = when (status.phase) {
        RemoteSupportStatus.Phase.OFF -> "No support session is running."
        RemoteSupportStatus.Phase.CONNECTING -> "Connecting to BNM…"
        RemoteSupportStatus.Phase.WAITING_FOR_ENGINEER -> "Connected to BNM. Waiting for the engineer to join…"
        RemoteSupportStatus.Phase.ENGINEER_CONNECTED -> "The BNM engineer is connected."
        RemoteSupportStatus.Phase.RECONNECTING -> "Connection lost — reconnecting…"
        RemoteSupportStatus.Phase.ENDED_ERROR -> status.lastError ?: "The support session could not start."
    }

    /** What the owner allowed, for the running-session panel: "diagnostics only" or a list. */
    fun consentSummary(consent: SupportConsent): String {
        val extras = listOfNotNull(
            "analyzer data".takeIf { consent.analyzerData },
            "record lookup".takeIf { consent.records },
            "screen view".takeIf { consent.screen },
        )
        return if (extras.isEmpty()) "Diagnostics only — no patient records, results or analyzer data."
        else "Diagnostics, plus: ${extras.joinToString(", ")}."
    }

    /** The banner's one line: "BNM support session · code XXXX-XXXX · ends 18:30 · 3 actions". */
    fun bannerText(status: RemoteSupportStatus): String {
        val s = status.session ?: return "BNM support session"
        val actions = "${status.actions} ${if (status.actions == 1) "action" else "actions"}"
        val state = when (status.phase) {
            RemoteSupportStatus.Phase.CONNECTING -> " · connecting"
            RemoteSupportStatus.Phase.RECONNECTING -> " · reconnecting"
            else -> ""
        }
        return "BNM support session · code ${s.displayCode} · ends ${endsAtLabel(s)} · $actions$state"
    }

    /** Settings ▸ App row subtitle. */
    fun settingsSubtitle(status: RemoteSupportStatus): String = when {
        !status.isActive -> "Let a BNM engineer see this computer's diagnostics and fix analyzer links, for a few hours, with your consent."
        else -> "Running · code ${status.session?.displayCode} · ends ${status.session?.let(::endsAtLabel)} · ${status.actions} actions"
    }

    fun outcomeLabel(outcome: SupportAuditRow.Outcome): String = when (outcome) {
        SupportAuditRow.Outcome.OK -> "ok"
        SupportAuditRow.Outcome.REFUSED -> "refused"
        SupportAuditRow.Outcome.FAILED -> "failed"
    }
}
