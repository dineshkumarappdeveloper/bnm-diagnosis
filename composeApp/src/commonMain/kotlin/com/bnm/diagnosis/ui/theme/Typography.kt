package com.bnm.diagnosis.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Typography scale tuned for information density.
 * Reference: Inter on Linear / Stripe Dashboard — but we use system default
 * to keep the app font-asset-free across Android + iOS.
 *
 * Densities:
 * - displayMd: hero numbers (e.g. "$45,231")
 * - titleLg:   section headers
 * - titleMd:   card titles
 * - bodyMd:    primary body
 * - bodySm:    secondary body / metadata
 * - labelSm:   chips, table column headers
 * - mono:      tabular figures (timestamps, ids, currencies in tables)
 */
@Immutable
data class AppTypography(
    val displayLg: TextStyle,
    val displayMd: TextStyle,
    val titleLg: TextStyle,
    val titleMd: TextStyle,
    val titleSm: TextStyle,
    val bodyLg: TextStyle,
    val bodyMd: TextStyle,
    val bodySm: TextStyle,
    val labelLg: TextStyle,
    val labelMd: TextStyle,
    val labelSm: TextStyle,
    val mono: TextStyle,
)

private val sans = FontFamily.Default
// FontFamily.Monospace is reliable across all KMP targets.
private val mono = FontFamily.Monospace

internal val DefaultTypography = AppTypography(
    displayLg = TextStyle(fontFamily = sans, fontWeight = FontWeight.Bold,     fontSize = 36.sp, lineHeight = 40.sp, letterSpacing = (-0.5).sp),
    displayMd = TextStyle(fontFamily = sans, fontWeight = FontWeight.Bold,     fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.4).sp),
    titleLg   = TextStyle(fontFamily = sans, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.2).sp),
    titleMd   = TextStyle(fontFamily = sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSm   = TextStyle(fontFamily = sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLg    = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal,   fontSize = 15.sp, lineHeight = 22.sp),
    bodyMd    = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal,   fontSize = 13.sp, lineHeight = 18.sp),
    bodySm    = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal,   fontSize = 12.sp, lineHeight = 16.sp),
    labelLg   = TextStyle(fontFamily = sans, fontWeight = FontWeight.Medium,   fontSize = 13.sp, lineHeight = 16.sp),
    labelMd   = TextStyle(fontFamily = sans, fontWeight = FontWeight.Medium,   fontSize = 12.sp, lineHeight = 14.sp, letterSpacing = 0.2.sp),
    labelSm   = TextStyle(fontFamily = sans, fontWeight = FontWeight.Medium,   fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.4.sp),
    mono      = TextStyle(fontFamily = mono, fontWeight = FontWeight.Normal,   fontSize = 12.sp, lineHeight = 16.sp),
)

/** Map our typography onto Material3's slots so existing screens still inherit something sensible. */
internal fun AppTypography.toMaterial(): Typography = Typography(
    displayLarge   = displayLg,
    displayMedium  = displayMd,
    displaySmall   = titleLg,
    headlineLarge  = displayMd,
    headlineMedium = titleLg,
    headlineSmall  = titleLg,
    titleLarge     = titleLg,
    titleMedium    = titleMd,
    titleSmall     = titleSm,
    bodyLarge      = bodyLg,
    bodyMedium     = bodyMd,
    bodySmall      = bodySm,
    labelLarge     = labelLg,
    labelMedium    = labelMd,
    labelSmall     = labelSm,
)

val LocalAppTypography = staticCompositionLocalOf<AppTypography> {
    error("AppTypography not provided. Wrap your composables in AppTheme { … }")
}
