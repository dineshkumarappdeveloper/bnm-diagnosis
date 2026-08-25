package com.bnm.diagnosis.staff

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Who is signed in on THIS seat, right now (P4). Purely in-memory and
 * deliberately NOT persisted: a shared lab PC should come back to the sign-in
 * grid after a restart, and the signed-in identity is only ever used to stamp
 * attribution (`entered_by` / `verified_by` / `approved_by`) and to gate the
 * approve action.
 *
 * ### Auto-lock
 * [lastActiveAt] is a plain epoch-millis stamp bumped by [touch] on navigation
 * and on explicit user actions — cheap on purpose (no listeners, no timers per
 * screen). A single low-frequency poll in App.kt asks [isIdle] and calls
 * [signOut] once [IDLE_TIMEOUT_MS] has passed; the app returns to sign-in and
 * every row of lab data stays exactly where it was.
 */
class StaffSession {
    private val _current = MutableStateFlow<Staff?>(null)
    val current: StateFlow<Staff?> = _current.asStateFlow()

    /** Epoch millis of the last interaction (nav change or explicit action). */
    var lastActiveAt: Long = nowMs()
        private set

    val signedIn: Staff? get() = _current.value

    /** Name to stamp on results; [fallback] covers a somehow-null session. */
    fun actorName(fallback: String): String =
        _current.value?.name?.takeIf { it.isNotBlank() } ?: fallback

    fun signIn(staff: Staff) {
        _current.value = staff
        lastActiveAt = nowMs()
    }

    /** "Sign out" and "Switch user" are the same thing: drop back to the grid. */
    fun signOut() {
        _current.value = null
        lastActiveAt = nowMs()
    }

    /** Somebody did something — restart the idle clock. */
    fun touch() {
        lastActiveAt = nowMs()
    }

    /** Keep an edited row (role/name change) reflected in the live session. */
    fun refresh(staff: Staff) {
        if (_current.value?.id == staff.id) {
            _current.value = if (staff.active) staff else null
        }
    }

    /** True once nothing has happened for [IDLE_TIMEOUT_MS] while signed in. */
    fun isIdle(now: Long = nowMs()): Boolean =
        _current.value != null && now - lastActiveAt >= IDLE_TIMEOUT_MS

    private fun nowMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

    companion object {
        /** 15 minutes — a lab PC left unattended at the bench locks itself. */
        const val IDLE_TIMEOUT_MS = 15 * 60_000L
    }
}

val LocalStaffSession = staticCompositionLocalOf<StaffSession> {
    error("LocalStaffSession not provided — wrap content in CompositionLocalProvider in App.kt")
}

val LocalStaffRepository = staticCompositionLocalOf<StaffRepository> {
    error("LocalStaffRepository not provided — wrap content in CompositionLocalProvider in App.kt")
}
