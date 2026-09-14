package com.bnm.lab.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One switch for "show the Report-a-problem dialog", flipped from anywhere: the
 * desktop Help menu (reachable even when the app is stuck on activation or
 * sign-in), the Settings ▸ App panel, and the crash-on-last-run prompt. App.kt
 * renders the dialog inside the app theme, above whatever screen is showing.
 */
object SupportUi {
    data class Request(
        /** Set when the dialog opens because the previous session crashed. */
        val crashNotice: String? = null,
    )

    private val _request = MutableStateFlow<Request?>(null)
    val request: StateFlow<Request?> = _request.asStateFlow()

    fun open(crashNotice: String? = null) {
        // Help > Report a problem while the crash prompt is up must not downgrade
        // it to a plain report: the crash would then never be acknowledged, and
        // the lab asked about it again on every launch after already sending it.
        if (crashNotice == null && _request.value?.crashNotice != null) return
        AppLog.i("Support", if (crashNotice != null) "crash-report prompt shown" else "report dialog opened")
        _request.value = Request(crashNotice)
    }

    fun close() { _request.value = null }
}
