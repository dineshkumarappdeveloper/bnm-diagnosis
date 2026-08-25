package com.bnm.diagnosis.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Motion tokens. Reference: Linear's "fast and quiet" feel.
 * Standard (in/out) for most UI; emphasised for entrances; decelerate for incoming.
 */
@Immutable
data class AppMotion(
    val durationFast: Int = 120,
    val durationStandard: Int = 200,
    val durationSlow: Int = 300,
    val durationDeliberate: Int = 450,
    val easeStandard: Easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f),
    val easeEmphasised: Easing = CubicBezierEasing(0.3f, 0.0f, 0.0f, 1.0f),
    val easeDecelerate: Easing = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f),
    val easeAccelerate: Easing = CubicBezierEasing(0.4f, 0.0f, 1.0f, 1.0f),
    val easeLinear: Easing = LinearEasing,
)

internal val DefaultMotion = AppMotion()

val LocalAppMotion = staticCompositionLocalOf<AppMotion> {
    error("AppMotion not provided. Wrap your composables in AppTheme { … }")
}
