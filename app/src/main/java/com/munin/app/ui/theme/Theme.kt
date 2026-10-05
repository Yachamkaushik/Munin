package com.munin.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Munin's look: inspired by Apple's iOS design language (grouped lists on a quiet grey, white cards with generous rounding, hairline separators,
 * a single blue tint, large bold titles). It uses the phone's own sans-serif; Apple's San Francisco font is not licensed for other platforms.
 */
@Immutable
class MuninColors(
    val groupedBackground: Color,
    val card: Color,
    val label: Color,
    val secondaryLabel: Color,
    val tertiaryLabel: Color,
    val separator: Color,
    val fill: Color,
    val tint: Color,
    val tintSoft: Color,
    val green: Color,
    val orange: Color,
    val red: Color,
    val bar: Color,
    val indigo: Color,
    val purple: Color,
    val teal: Color,
    val pink: Color,
    val shadow: Color,
)

private val Light = MuninColors(
    groupedBackground = Color(0xFFF2F2F7), card = Color(0xFFFFFFFF), label = Color(0xFF000000), secondaryLabel = Color(0xFF6C6C70), tertiaryLabel = Color(0xFFA1A1A6),
    separator = Color(0xFFD8D8DC), fill = Color(0x1F767680), tint = Color(0xFF007AFF), tintSoft = Color(0x1A007AFF), green = Color(0xFF34C759), orange = Color(0xFFFF9500),
    red = Color(0xFFFF3B30), bar = Color(0xF2F9F9F9),
    indigo = Color(0xFF5856D6), purple = Color(0xFFAF52DE), teal = Color(0xFF30B0C7), pink = Color(0xFFFF2D55), shadow = Color(0x14000000),
)

private val Dark = MuninColors(
    groupedBackground = Color(0xFF000000), card = Color(0xFF1C1C1E), label = Color(0xFFFFFFFF), secondaryLabel = Color(0xFF98989F), tertiaryLabel = Color(0xFF636366),
    separator = Color(0xFF38383A), fill = Color(0x5C767680), tint = Color(0xFF0A84FF), tintSoft = Color(0x330A84FF), green = Color(0xFF30D158), orange = Color(0xFFFF9F0A),
    red = Color(0xFFFF453A), bar = Color(0xF21C1C1E),
    indigo = Color(0xFF5E5CE6), purple = Color(0xFFBF5AF2), teal = Color(0xFF40C8E0), pink = Color(0xFFFF375F), shadow = Color(0x00000000),
)

val LocalMuninColors = staticCompositionLocalOf { Light }

/** Where screens read the iOS-style tokens that Material's scheme has no slot for. */
object Munin { val colors: MuninColors @Composable get() = LocalMuninColors.current }

private fun scheme(c: MuninColors, dark: Boolean): ColorScheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
    primary = c.tint, onPrimary = Color.White, primaryContainer = c.tintSoft, onPrimaryContainer = c.tint,
    secondary = c.secondaryLabel, onSecondary = c.card, secondaryContainer = c.fill, onSecondaryContainer = c.label,
    tertiary = c.green, onTertiary = Color.White,
    background = c.groupedBackground, onBackground = c.label,
    surface = c.card, onSurface = c.label, surfaceVariant = c.fill, onSurfaceVariant = c.secondaryLabel,
    surfaceContainer = c.card, surfaceContainerHigh = c.card, surfaceContainerHighest = c.fill, surfaceContainerLow = c.card, surfaceContainerLowest = c.card,
    outline = c.separator, outlineVariant = c.separator, error = c.red, onError = Color.White, errorContainer = c.red.copy(alpha = 0.12f), onErrorContainer = c.red,
)

/** iOS text styles (Large Title, Title 1 to 3, Headline, Body, Callout, Subheadline, Footnote, Caption) mapped onto Material's slots. */
private val Sans = FontFamily.SansSerif
private fun t(size: Int, line: Int, weight: FontWeight, tracking: Double = 0.0) = TextStyle(fontFamily = Sans, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = tracking.sp)

private val MuninTypography = Typography(
    displaySmall = t(34, 41, FontWeight.Bold, 0.37),     // Large Title
    headlineLarge = t(34, 41, FontWeight.Bold, 0.37),
    headlineMedium = t(28, 34, FontWeight.Bold, 0.36),   // Title 1
    headlineSmall = t(22, 28, FontWeight.Bold, 0.35),    // Title 2
    titleLarge = t(22, 28, FontWeight.SemiBold, 0.35),
    titleMedium = t(17, 22, FontWeight.SemiBold, -0.41), // Headline
    titleSmall = t(15, 20, FontWeight.SemiBold, -0.24),
    bodyLarge = t(17, 22, FontWeight.Normal, -0.41),     // Body
    bodyMedium = t(15, 20, FontWeight.Normal, -0.24),    // Subheadline
    bodySmall = t(13, 18, FontWeight.Normal, -0.08),     // Footnote
    labelLarge = t(17, 22, FontWeight.Medium, -0.41),
    labelMedium = t(13, 18, FontWeight.Medium, -0.08),
    labelSmall = t(12, 16, FontWeight.Normal, 0.0),      // Caption
)

private val MuninShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp), extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun MuninTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (dark) Dark else Light
    CompositionLocalProvider(LocalMuninColors provides c) {
        MaterialTheme(colorScheme = scheme(c, dark), typography = MuninTypography, shapes = MuninShapes, content = content)
    }
}
