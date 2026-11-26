package com.aicode.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val LocalIsDark = staticCompositionLocalOf { true }

/**
 * 全局色板：2026-11-26 起跟「枳生天气」同一套语言——
 * 浅色是雾蓝底 + 半透明白卡 + 深青绿强调；深色是同结构的墨绿版本。
 * 网页端（DSH 前端）注入的 CSS 是同一套色值，两边看起来是一件东西。
 */
object AppColors {
    // 强调色：浅色下用深青绿，深色下用亮薄荷绿（见两套 scheme 的 primary）
    val TealDeep = Color(0xFF2F4F48)
    val TealMint = Color(0xFF4FC3A9)
    val Teal = TealMint
    val Amber = Color(0xFFE0A458)
    val Red = Color(0xFFD95555)
    val Blue = TealDeep
    val BlueBright = TealMint

    // 深色层：底 → 卡 → 高亮卡（中性灰黑，跟网页 #151517 那套一致，不带绿）
    val DarkBg = Color(0xFF151517)
    val DarkCard = Color(0xFF1B1B1C)
    val DarkCardHigh = Color(0xFF232324)
    val DarkLine = Color(0xFF2C2C2E)

    // 浅色层：雾蓝底、白卡、淡蓝灰线
    val LightBg = Color(0xFFE2ECF4)
    val LightCard = Color(0xFFFFFFFF)
    val LightCardHigh = Color(0xFFFFFFFF)
    val LightLine = Color(0xFFDCE6EC)
}

/** 主题色（强调色）三套：青绿（默认）/ 蓝 / 橙。2026-11-26 起可在「外观」里选。 */
enum class AppAccent(val key: String, val label: String) {
    Teal("teal", "青绿"),
    Blue("blue", "蓝"),
    Amber("amber", "橙");

    companion object {
        fun of(key: String?): AppAccent = entries.firstOrNull { it.key == key } ?: Teal
    }
}

/** 正文字号三档：缩放整套 Typography（只影响原生页面，网页字号另说）。 */
enum class AppFontSize(val key: String, val label: String, val scale: Float) {
    Small("small", "小", 0.90f),
    Medium("medium", "中", 1.00f),
    Large("large", "大", 1.12f);

    companion object {
        fun of(key: String?): AppFontSize = entries.firstOrNull { it.key == key } ?: Medium
    }
}

/** 界面密度两档：紧凑 = 圆角收一半、行高再挤一点，一屏能多看几行。 */
enum class AppDensity(val key: String, val label: String, val cornerScale: Float, val lineScale: Float) {
    Standard("standard", "标准", 1.0f, 1.0f),
    Compact("compact", "紧凑", 0.55f, 0.94f);

    companion object {
        fun of(key: String?): AppDensity = entries.firstOrNull { it.key == key } ?: Standard
    }
}

/** 某套主题色在深/浅模式下的强调色。 */
fun accentColor(accent: AppAccent, dark: Boolean): Color = when (accent) {
    AppAccent.Teal -> if (dark) AppColors.TealMint else AppColors.TealDeep
    AppAccent.Blue -> if (dark) Color(0xFF6FA8FF) else Color(0xFF2C5A9E)
    AppAccent.Amber -> if (dark) Color(0xFFE8B36A) else Color(0xFFB0722E)
}

private val DarkColors = darkColorScheme(
    primary = AppColors.TealMint,
    onPrimary = Color(0xFF06211B),
    primaryContainer = Color(0xFF1B3A34),
    onPrimaryContainer = Color(0xFFC9EFE4),
    secondary = Color(0xFF8FD9C6),
    onSecondary = Color(0xFF04241F),
    secondaryContainer = Color(0xFF143A34),
    onSecondaryContainer = Color(0xFFD2F4EC),
    tertiary = AppColors.Amber,
    onTertiary = Color(0xFF2A1A05),
    tertiaryContainer = Color(0xFF3D2A12),
    onTertiaryContainer = Color(0xFFFFE7C7),
    background = AppColors.DarkBg,
    onBackground = Color(0xFFF2F2F4),
    surface = AppColors.DarkBg,
    onSurface = Color(0xFFF2F2F4),
    surfaceVariant = Color(0xFF1F1F22),
    onSurfaceVariant = Color(0xFF979DA6),
    outline = Color(0xFF353638),
    outlineVariant = AppColors.DarkLine,
    error = Color(0xFFFF7A7A),
    onError = Color(0xFF2C0A0A),
    errorContainer = Color(0xFF47140F),
    onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFFF2F2F4),
    inverseOnSurface = Color(0xFF151517),
    inversePrimary = Color(0xFF2F4F48),
    scrim = Color(0xCC000000),
)

private val LightColors = lightColorScheme(
    primary = AppColors.TealDeep,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD7E8E2),
    onPrimaryContainer = Color(0xFF16201E),
    secondary = Color(0xFF3E6B5F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD2EDE4),
    onSecondaryContainer = Color(0xFF0A2620),
    tertiary = Color(0xFF9A5B00),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFE7C7),
    onTertiaryContainer = Color(0xFF2A1A05),
    background = AppColors.LightBg,
    onBackground = Color(0xFF141C1E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF141C1E),
    surfaceVariant = Color(0xFFEDF3F7),
    onSurfaceVariant = Color(0xFF5C6E75),
    outline = Color(0xFFC3D2DB),
    outlineVariant = AppColors.LightLine,
    error = Color(0xFFC0392B),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    inverseSurface = Color(0xFF141C1E),
    inverseOnSurface = Color(0xFFF2F7F5),
    inversePrimary = Color(0xFF9FD8C6),
    scrim = Color(0x99000000),
)

/** 统一圆角：小控件 12 / 输入框 14 / 卡片 20 / 大面板 26；紧凑模式整体 ×0.55。 */
private fun buildShapes(cornerScale: Float): Shapes {
    fun r(base: Int) = RoundedCornerShape((base * cornerScale).dp)
    return Shapes(
        extraSmall = r(8),
        small = r(12),
        medium = r(16),
        large = r(20),
        extraLarge = r(26),
    )
}

/** 字体：标题收紧字距，正文行高略放大；字号档和小行高密度都在这里乘出来。 */
private fun buildTypography(fontScale: Float, lineScale: Float): Typography {
    fun ts(size: Float, line: Float, weight: FontWeight? = null, letter: Float = 0f) = TextStyle(
        fontWeight = weight,
        fontSize = (size * fontScale).sp,
        lineHeight = (line * fontScale * lineScale).sp,
        letterSpacing = letter.sp,
    )
    return Typography(
        headlineLarge = ts(30f, 36f, FontWeight.Bold, -0.5f),
        headlineMedium = ts(25f, 31f, FontWeight.Bold, -0.4f),
        headlineSmall = ts(21f, 27f, FontWeight.Bold, -0.3f),
        titleLarge = ts(18f, 24f, FontWeight.SemiBold, -0.1f),
        titleMedium = ts(16f, 22f, FontWeight.SemiBold),
        bodyLarge = ts(16f, 24f),
        bodyMedium = ts(14f, 21f),
        bodySmall = ts(13f, 19f),
        labelLarge = ts(14f, 18f, FontWeight.Medium),
        labelMedium = ts(12f, 16f, FontWeight.Medium),
        labelSmall = ts(11f, 15f, FontWeight.Medium, 0.1f),
    )
}

@Composable
fun AicodeTheme(
    dark: Boolean,
    accent: AppAccent = AppAccent.Teal,
    fontSize: AppFontSize = AppFontSize.Medium,
    density: AppDensity = AppDensity.Standard,
    content: @Composable () -> Unit,
) {
    // 主题色只换强调色（primary），背景/卡片保持原来那套，改小了不会把整体配色弄崩
    val base = if (dark) DarkColors else LightColors
    val scheme = base.copy(primary = accentColor(accent, dark))
    val typography = remember(fontSize, density) {
        buildTypography(fontSize.scale, density.lineScale)
    }
    val shapes = remember(density) { buildShapes(density.cornerScale) }
    CompositionLocalProvider(LocalIsDark provides dark) {
        MaterialTheme(
            colorScheme = scheme,
            shapes = shapes,
            typography = typography,
            content = content,
        )
    }
}


/** 全屏底色渐变：铺满整屏（含状态栏/导航栏区域），网页端 body 透明，两边同一层底色。 */
@Composable
fun appBackgroundBrush(): Brush = if (LocalIsDark.current) {
    Brush.verticalGradient(listOf(Color(0xFF101011), Color(0xFF151517), Color(0xFF121214)))
} else {
    Brush.verticalGradient(listOf(Color(0xFFDFEAF2), Color(0xFFEAF2F7), Color(0xFFF5FAFC)))
}

@Composable
fun appCardColor(): Color = if (LocalIsDark.current) AppColors.DarkCard else AppColors.LightCard

/** 卡片里的次级亮块（选中的胶囊、内嵌小卡、输入框底色等）。 */
@Composable
fun appCardHighColor(): Color = if (LocalIsDark.current) AppColors.DarkCardHigh else AppColors.LightCardHigh

@Composable
fun appLineColor(): Color = if (LocalIsDark.current) AppColors.DarkLine else AppColors.LightLine

/** 页面主背景（浅色是雾蓝，深色是墨绿黑）。 */
@Composable
fun appBackgroundColor(): Color = if (LocalIsDark.current) AppColors.DarkBg else AppColors.LightBg
