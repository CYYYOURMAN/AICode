package com.aicode.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val LocalIsDark = staticCompositionLocalOf { true }

object AppColors {
    val Blue = Color(0xFF3D6BFF)
    val BlueBright = Color(0xFF8FAFFF)
    val Teal = Color(0xFF2BC4A8)
    val Amber = Color(0xFFFFB25C)
    val Red = Color(0xFFFF6B6B)
    val DarkBg = Color(0xFF151517)
    val DarkCard = Color(0xFF1E1E20)
    val DarkLine = Color(0xFF2A2A2C)
    val LightBg = Color(0xFFFFFFFF)
    val LightCard = Color(0xFFF7F7F8)
    val LightLine = Color(0xFFE8E8EA)
}

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FAFFF),
    onPrimary = Color(0xFF071431),
    primaryContainer = Color(0xFF22376B),
    onPrimaryContainer = Color(0xFFDCE6FF),
    secondary = Color(0xFF7FD9C9),
    onSecondary = Color(0xFF042420),
    secondaryContainer = Color(0xFF143A34),
    onSecondaryContainer = Color(0xFFD2F4EC),
    tertiary = Color(0xFFFFC98B),
    onTertiary = Color(0xFF2A1A05),
    tertiaryContainer = Color(0xFF3D2A12),
    onTertiaryContainer = Color(0xFFFFE7C7),
    background = AppColors.DarkBg,
    onBackground = Color(0xFFE9EDF5),
    surface = AppColors.DarkBg,
    onSurface = Color(0xFFE9EDF5),
    surfaceVariant = Color(0xFF1A2331),
    onSurfaceVariant = Color(0xFF98A5BB),
    outline = Color(0xFF2B3547),
    outlineVariant = AppColors.DarkLine,
    error = Color(0xFFFF7A7A),
    onError = Color(0xFF2C0A0A),
    errorContainer = Color(0xFF47140F),
    onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFFE9EDF5),
    inverseOnSurface = Color(0xFF14181F),
    inversePrimary = Color(0xFF2A4EA8),
    scrim = Color(0xCC000000),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF2A4FCC),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE5FF),
    onPrimaryContainer = Color(0xFF0B1C4A),
    secondary = Color(0xFF0E7C6B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD2F4EC),
    onSecondaryContainer = Color(0xFF042420),
    tertiary = Color(0xFF9A5B00),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFE7C7),
    onTertiaryContainer = Color(0xFF2A1A05),
    background = AppColors.LightBg,
    onBackground = Color(0xFF111722),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111722),
    surfaceVariant = Color(0xFFEAEEF6),
    onSurfaceVariant = Color(0xFF5A6478),
    outline = Color(0xFFC7CEDC),
    outlineVariant = AppColors.LightLine,
    error = Color(0xFFC62828),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    inverseSurface = Color(0xFF111722),
    inverseOnSurface = Color(0xFFF3F5FA),
    inversePrimary = Color(0xFFB4C5FF),
    scrim = Color(0x99000000),
)

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(26.dp),
)

@Composable
fun AicodeTheme(dark: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalIsDark provides dark) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            shapes = AppShapes,
            content = content,
        )
    }
}

@Composable
fun appCardColor(): Color = if (LocalIsDark.current) AppColors.DarkCard else AppColors.LightCard

@Composable
fun appLineColor(): Color = if (LocalIsDark.current) AppColors.DarkLine else AppColors.LightLine
