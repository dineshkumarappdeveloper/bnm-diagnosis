package com.bnm.analyzersim.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * BNM Lab's palette, rebuilt small.
 *
 * The same emerald-on-paper look an engineer sees all day in BNM Lab, so the
 * two windows on one bench read as one product — but its own file, not a
 * dependency on `:composeApp`. Pulling the app's theme in would drag SQLDelight,
 * Ktor and the whole LIMS into a tool whose job is to be a single small
 * installer that runs on a client's laptop.
 *
 * Light only, deliberately: this is a five-minute tool, and a theme switch is a
 * setting nobody would use.
 */
private val Emerald = Color(0xFF22C55E)
private val EmeraldDark = Color(0xFF16A34A)
private val EmeraldSoft = Color(0xFFDCFCE7)
private val Ink = Color(0xFF0B0D0F)
private val InkSoft = Color(0xFF5C6370)
private val Paper = Color(0xFFF7F8F9)
private val Card = Color(0xFFFFFFFF)
private val Hairline = Color(0xFFE6E7EA)

/** Status colours, shared with the transcript's tones. */
val SimDanger = Color(0xFFEF4444)
val SimDangerSoft = Color(0xFFFEE2E2)
val SimWarning = Color(0xFFF59E0B)
val SimInfo = Color(0xFF3B82F6)
val SimSuccessSoft = EmeraldSoft
val SimMuted = InkSoft
val SimWell = Color(0xFFF1F2F4)

private val Scheme = lightColorScheme(
    primary = EmeraldDark,
    onPrimary = Color.White,
    primaryContainer = EmeraldSoft,
    onPrimaryContainer = Color(0xFF14532D),
    secondary = InkSoft,
    background = Paper,
    onBackground = Ink,
    surface = Card,
    onSurface = Ink,
    surfaceVariant = SimWell,
    onSurfaceVariant = InkSoft,
    outline = Color(0xFFD1D3D7),
    outlineVariant = Hairline,
    error = SimDanger,
    errorContainer = SimDangerSoft,
    onErrorContainer = Color(0xFF7F1D1D),
)

/** The transcript is the one place a proportional font would lie: byte counts
 *  and addresses have to line up between one sample and the next. */
val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp)

private val SimTypography = Typography().let { base ->
    base.copy(
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Medium),
    )
}

@Composable
fun SimTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = SimTypography, content = content)
}
