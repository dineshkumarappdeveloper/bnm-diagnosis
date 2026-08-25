package com.bnm.diagnosis.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Single entry point for our design system. Wraps Material3's MaterialTheme so legacy
 * `MaterialTheme.colorScheme.primary`-style call sites still work, while new code reads
 * the richer tokens from `AppTheme.colors`, `AppTheme.typography`, `AppTheme.spacing` etc.
 *
 * Theming honours the user's [ThemeChoice]:
 *   - SYSTEM  → follow `isSystemInDarkTheme()`
 *   - LIGHT   → force light
 *   - DARK    → force dark
 *
 * Pass [themeChoice] from the app root (App.kt collects it from [ThemeManager]).
 * Settings UI can call [LocalThemeManager.current.setChoice] to flip it.
 */
@Composable
fun AppTheme(
    themeChoice: ThemeChoice = ThemeChoice.SYSTEM,
    themeManager: ThemeManager? = null,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (themeChoice) {
        ThemeChoice.SYSTEM -> systemDark
        ThemeChoice.LIGHT  -> false
        ThemeChoice.DARK   -> true
    }
    val colors    = if (darkTheme) DarkColors else LightColors
    val typography = DefaultTypography
    val spacing    = DefaultSpacing
    val shapes     = DefaultShapes
    val motion     = DefaultMotion

    CompositionLocalProvider(
        LocalAppColors      provides colors,
        LocalAppTypography  provides typography,
        LocalAppSpacing     provides spacing,
        LocalAppShapes      provides shapes,
        LocalAppMotion      provides motion,
        LocalThemeManager   provides themeManager,
    ) {
        MaterialTheme(
            colorScheme = colors.toMaterialScheme(),
            typography  = typography.toMaterial(),
        ) {
            content()
        }
    }
}

object AppTheme {
    val colors: AppColors
        @Composable @ReadOnlyComposable get() = LocalAppColors.current
    val typography: AppTypography
        @Composable @ReadOnlyComposable get() = LocalAppTypography.current
    val spacing: AppSpacing
        @Composable @ReadOnlyComposable get() = LocalAppSpacing.current
    val shapes: AppShapes
        @Composable @ReadOnlyComposable get() = LocalAppShapes.current
    val motion: AppMotion
        @Composable @ReadOnlyComposable get() = LocalAppMotion.current
}

/**
 * Provided by [AppTheme] so any screen can read or change the theme without
 * threading a manager through every constructor. Null when no manager was
 * supplied — UIs that flip the theme should null-check and disable the
 * affordance.
 */
val LocalThemeManager = staticCompositionLocalOf<ThemeManager?> { null }
