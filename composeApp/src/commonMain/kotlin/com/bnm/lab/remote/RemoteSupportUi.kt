package com.bnm.lab.remote

import com.bnm.lab.diagnostics.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One switch for "show the Remote support dialog", flipped from anywhere — the
 * desktop Help menu (reachable on activation and sign-in, where Settings is
 * not) and the Settings ▸ App panel. App.kt renders the dialog inside the app
 * theme, above whatever screen is showing. Same shape as [com.bnm.lab.diagnostics.SupportUi].
 */
object RemoteSupportUi {
    private val _open = MutableStateFlow(false)
    val open: StateFlow<Boolean> = _open.asStateFlow()

    fun open() {
        if (!_open.value) AppLog.i("RemoteSupport", "dialog opened")
        _open.value = true
    }

    fun close() { _open.value = false }
}
