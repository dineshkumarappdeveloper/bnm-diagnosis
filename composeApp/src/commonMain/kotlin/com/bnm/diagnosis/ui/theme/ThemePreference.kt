package com.bnm.diagnosis.ui.theme

import com.russhwolf.settings.Settings
import com.russhwolf.settings.set
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User theme choice. Persists across launches via [Settings] (Android shared
 * prefs, iOS NSUserDefaults, Desktop `java.util.prefs.Preferences`).
 *
 *   SYSTEM — follow OS dark-mode toggle (default)
 *   LIGHT  — force light
 *   DARK   — force dark
 */
enum class ThemeChoice {
    SYSTEM,
    LIGHT,
    DARK;

    val label: String
        get() = when (this) {
            SYSTEM -> "System"
            LIGHT  -> "Light"
            DARK   -> "Dark"
        }
}

/**
 * Single source of truth for the user's theme preference. Hold one instance
 * at the app root and pass it into [AppTheme] (which collects the StateFlow
 * and resolves SYSTEM via `isSystemInDarkTheme()`).
 *
 * Reads + writes are synchronous against [Settings] — fast enough that we
 * don't bother with a backing scope/dispatcher; UI calls happen on main
 * thread and the I/O is a single key-value write.
 */
class ThemeManager {
    private val settings: Settings = Settings()
    private val _choice = MutableStateFlow(load())

    val choice: StateFlow<ThemeChoice> = _choice.asStateFlow()

    fun setChoice(choice: ThemeChoice) {
        _choice.value = choice
        settings.set(KEY, choice.name)
    }

    private fun load(): ThemeChoice {
        val raw = settings.getStringOrNull(KEY) ?: return ThemeChoice.SYSTEM
        return runCatching { ThemeChoice.valueOf(raw) }.getOrDefault(ThemeChoice.SYSTEM)
    }

    companion object {
        private const val KEY = "ui_theme_choice"
    }
}
