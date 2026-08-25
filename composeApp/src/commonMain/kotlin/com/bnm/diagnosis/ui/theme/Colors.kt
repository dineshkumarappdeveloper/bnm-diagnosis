package com.bnm.diagnosis.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Linear / Stripe-inspired palette.
 * - Dark: deep neutrals (#08090A → #1C1D1F) with a single emerald accent.
 * - Light: paper-white (#FCFCFD) on warm grey, same emerald accent.
 * - Borders are explicit hairlines, not shadows — pulled forward for tablet density.
 */

// ── Brand accent ──────────────────────────────────────────────────────────────
private val EmeraldDark    = Color(0xFF16A34A) // active, hover
private val EmeraldDefault = Color(0xFF22C55E) // primary
private val EmeraldSoft    = Color(0xFFDCFCE7) // tinted bg (light)
private val EmeraldDim     = Color(0xFF052E16) // tinted bg (dark)

// ── Status palette (consistent across themes) ─────────────────────────────────
private val Info    = Color(0xFF3B82F6)
private val Warning = Color(0xFFF59E0B)
private val Danger  = Color(0xFFEF4444)
private val Success = EmeraldDefault

@Immutable
data class AppColors(
    val canvas: Color,            // page background
    val surface: Color,           // cards, panels
    val surfaceElevated: Color,   // dialogs, hovered rows, popups
    val surfaceMuted: Color,      // inset wells, code blocks, table headers
    val border: Color,            // hairline dividers, card outlines
    val borderStrong: Color,      // input outlines, focus rings
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textOnAccent: Color,
    val accent: Color,
    val accentHover: Color,
    val accentSoft: Color,        // tinted backgrounds for accent surfaces
    val accentTextOnSoft: Color,  // text colour for accent-soft surfaces
    val info: Color,
    val warning: Color,
    val danger: Color,
    val success: Color,
    val infoSoft: Color,
    val warningSoft: Color,
    val dangerSoft: Color,
    val successSoft: Color,
    val isDark: Boolean,
)

internal val LightColors = AppColors(
    canvas          = Color(0xFFF7F8F9),
    surface         = Color(0xFFFFFFFF),
    surfaceElevated = Color(0xFFFFFFFF),
    surfaceMuted    = Color(0xFFF1F2F4),
    border          = Color(0xFFE6E7EA),
    borderStrong    = Color(0xFFD1D3D7),
    textPrimary     = Color(0xFF0B0D0F),
    textSecondary   = Color(0xFF5C6370),
    textTertiary    = Color(0xFF8B919A),
    textOnAccent    = Color.White,
    accent          = EmeraldDefault,
    accentHover     = EmeraldDark,
    accentSoft      = EmeraldSoft,
    accentTextOnSoft= Color(0xFF14532D),
    info            = Info,
    warning         = Warning,
    danger          = Danger,
    success         = Success,
    infoSoft        = Color(0xFFDBEAFE),
    warningSoft     = Color(0xFFFEF3C7),
    dangerSoft      = Color(0xFFFEE2E2),
    successSoft     = EmeraldSoft,
    isDark          = false,
)

internal val DarkColors = AppColors(
    canvas          = Color(0xFF08090A),
    surface         = Color(0xFF0F1011),
    surfaceElevated = Color(0xFF18191B),
    surfaceMuted    = Color(0xFF131416),
    border          = Color(0xFF1F2124),
    borderStrong    = Color(0xFF2A2C30),
    textPrimary     = Color(0xFFF7F8F8),
    textSecondary   = Color(0xFFA1A4AB),
    textTertiary    = Color(0xFF6B7079),
    textOnAccent    = Color(0xFF052E16),
    accent          = EmeraldDefault,
    accentHover     = Color(0xFF34D399),
    accentSoft      = EmeraldDim,
    accentTextOnSoft= Color(0xFF86EFAC),
    info            = Info,
    warning         = Warning,
    danger          = Danger,
    success         = Success,
    infoSoft        = Color(0xFF1E3A8A).copy(alpha = 0.35f),
    warningSoft     = Color(0xFF78350F).copy(alpha = 0.4f),
    dangerSoft      = Color(0xFF7F1D1D).copy(alpha = 0.35f),
    successSoft     = EmeraldDim,
    isDark          = true,
)

/** Mirror to Material3's ColorScheme so legacy MaterialTheme components stay aligned. */
internal fun AppColors.toMaterialScheme(): ColorScheme = if (isDark) {
    darkColorScheme(
        primary             = accent,
        onPrimary           = textOnAccent,
        primaryContainer    = accentSoft,
        onPrimaryContainer  = accentTextOnSoft,
        secondary           = info,
        onSecondary          = Color.White,
        background          = canvas,
        onBackground        = textPrimary,
        surface             = surface,
        onSurface           = textPrimary,
        surfaceVariant      = surfaceMuted,
        onSurfaceVariant    = textSecondary,
        outline             = border,
        outlineVariant      = borderStrong,
        error               = danger,
        onError             = Color.White,
    )
} else {
    lightColorScheme(
        primary             = accent,
        onPrimary           = textOnAccent,
        primaryContainer    = accentSoft,
        onPrimaryContainer  = accentTextOnSoft,
        secondary           = info,
        onSecondary         = Color.White,
        background          = canvas,
        onBackground        = textPrimary,
        surface             = surface,
        onSurface           = textPrimary,
        surfaceVariant      = surfaceMuted,
        onSurfaceVariant    = textSecondary,
        outline             = border,
        outlineVariant      = borderStrong,
        error               = danger,
        onError             = Color.White,
    )
}

val LocalAppColors = staticCompositionLocalOf<AppColors> {
    error("AppColors not provided. Wrap your composables in AppTheme { … }")
}
