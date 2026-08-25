package com.bnm.diagnosis.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 4pt grid. Use these everywhere instead of magic dp values.
 * Tablet layouts use the higher rungs (xl, 2xl) for content padding;
 * phone uses md/lg.
 */
@Immutable
data class AppSpacing(
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
    val xxxl: Dp = 48.dp,
    val huge: Dp = 64.dp,
)

internal val DefaultSpacing = AppSpacing()

val LocalAppSpacing = staticCompositionLocalOf<AppSpacing> {
    error("AppSpacing not provided. Wrap your composables in AppTheme { … }")
}
