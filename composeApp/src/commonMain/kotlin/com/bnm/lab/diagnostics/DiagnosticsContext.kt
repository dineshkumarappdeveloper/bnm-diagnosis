package com.bnm.lab.diagnostics

/**
 * Named sections of state that go at the top of a support report — the things
 * support asks first ("which edition? is it licensed? when did it last sync?")
 * — so they are answered before anyone opens a log file.
 *
 * The app registers providers where the state lives (App.kt owns the licence
 * manager, repositories and sync engine); the report builder reads them without
 * needing to know about any of those types. Each provider must return counts,
 * flags and IDs only — the report is emailed.
 */
object DiagnosticsContext {

    @kotlin.concurrent.Volatile
    private var sections: Map<String, suspend () -> String> = linkedMapOf()

    @kotlin.concurrent.Volatile
    private var headlineProvider: () -> String = { "" }

    /**
     * One line identifying the install — lab, edition, device — for the email
     * subject and the top of the report, so a support inbox can be sorted by lab
     * without opening attachments.
     */
    fun setHeadline(provider: () -> String) { headlineProvider = provider }

    fun headline(): String = runCatching { LogRedactor.redact(headlineProvider()) }.getOrDefault("")

    /** Registering the same title again replaces it (recomposition-safe). */
    fun register(title: String, provider: suspend () -> String) {
        sections = LinkedHashMap(sections).apply { put(title, provider) }
    }

    suspend fun render(): String = buildString {
        for ((title, provider) in sections) {
            append("== ").append(title).append(" ==\n")
            val body = runCatching { provider() }
                .getOrElse { "(unavailable: ${it::class.simpleName}: ${it.message})" }
            append(LogRedactor.redact(body.trimEnd())).append("\n\n")
        }
    }
}
