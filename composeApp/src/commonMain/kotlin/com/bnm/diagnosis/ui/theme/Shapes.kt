package com.bnm.diagnosis.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

@Immutable
data class AppShapes(
    val none: Shape  = RoundedCornerShape(0.dp),
    val xs: Shape    = RoundedCornerShape(4.dp),
    val sm: Shape    = RoundedCornerShape(6.dp),
    val md: Shape    = RoundedCornerShape(8.dp),
    val lg: Shape    = RoundedCornerShape(12.dp),
    val xl: Shape    = RoundedCornerShape(16.dp),
    val xxl: Shape   = RoundedCornerShape(20.dp),
    val pill: Shape  = RoundedCornerShape(999.dp),
)

internal val DefaultShapes = AppShapes()

val LocalAppShapes = staticCompositionLocalOf<AppShapes> {
    error("AppShapes not provided. Wrap your composables in AppTheme { … }")
}
