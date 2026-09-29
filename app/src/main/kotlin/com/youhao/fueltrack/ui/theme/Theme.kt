package com.youhao.fueltrack.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * 「松间仪表盘」的形状：卡片 18dp、输入框 11dp、弹层 22dp —— 对齐 CSS 的
 * `--radius: 18px` / `--radius-small: 11px` / 移动端 `.modal { border-radius: 22px 22px 0 0 }`。
 *
 * Material 3 把槽位绑在组件上：Card 取 `medium`、OutlinedTextField 与 FilterChip 取 `extraSmall`、
 * AlertDialog 与 BottomSheet 取 `extraLarge`，所以设好这几个槽位，系统组件会自动跟着圆润，
 * 不必在每个调用点单独传 shape。
 */
private val YouHaoShapes = Shapes(
    extraSmall = RoundedCornerShape(11.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(22.dp),
)

private val LightColors = lightColorScheme(
    primary = PineAccent,
    onPrimary = PineHeroInk,
    primaryContainer = PineAccentSoft,
    onPrimaryContainer = PineAccentDark,
    secondary = PineAccentDark,
    onSecondary = PaperSurface,
    secondaryContainer = PineAccentSoft,
    onSecondaryContainer = PineAccentDark,
    tertiary = PineAccentDark,
    onTertiary = PaperSurface,
    tertiaryContainer = PineAccentSoft,
    onTertiaryContainer = PineAccentDark,
    background = PaperBg,
    onBackground = PaperInk,
    surface = PaperSurface,
    onSurface = PaperInk,
    surfaceVariant = PaperSurfaceAlt,
    onSurfaceVariant = PaperMuted,
    surfaceContainerLowest = PaperSurface,
    surfaceContainerLow = PaperSurface,
    surfaceContainer = PaperSurface,
    surfaceContainerHigh = PaperSurfaceAlt,
    surfaceContainerHighest = PaperSurfaceAlt,
    outline = PaperLineStrong,
    outlineVariant = PaperLine,
    error = DangerRed,
    onError = PaperSurface,
    errorContainer = DangerSoft,
    onErrorContainer = Color(0xFF5C1A12),
)

private val DarkColors = darkColorScheme(
    primary = NightAccent,
    onPrimary = Color(0xFF06231A),
    primaryContainer = NightAccentSoft,
    onPrimaryContainer = NightAccentDark,
    secondary = NightAccentDark,
    onSecondary = NightBg,
    secondaryContainer = NightAccentSoft,
    onSecondaryContainer = NightAccentDark,
    tertiary = NightAccentDark,
    onTertiary = NightBg,
    tertiaryContainer = NightAccentSoft,
    onTertiaryContainer = NightAccentDark,
    background = NightBg,
    onBackground = NightInk,
    surface = NightSurface,
    onSurface = NightInk,
    surfaceVariant = NightSurfaceAlt,
    onSurfaceVariant = NightMuted,
    surfaceContainerLowest = NightBg,
    surfaceContainerLow = NightSurface,
    surfaceContainer = NightSurface,
    surfaceContainerHigh = NightSurfaceAlt,
    surfaceContainerHighest = NightSurfaceAlt,
    outline = NightLineStrong,
    outlineVariant = NightLine,
    error = NightDanger,
    onError = NightBg,
    errorContainer = NightErrorContainer,
    onErrorContainer = NightOnErrorContainer,
)

/**
 * 品牌色相的柔和投影颜色。
 *
 * 这一版 Material 3 的 `ColorScheme` 已不再暴露 `shadow` 槽位，所以按当前背景的明度判断深浅：
 * 浅色用带绿调的深色（等价于令牌表的 `rgba(24,55,43,.07)`），深色用纯黑。
 * 这样调用点不必自己判断深浅，也不会和 `YouHaoTheme(darkTheme = ...)` 的参数脱节。
 */
@Composable
fun softShadowColor(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0x66000000) else CardShadow

/** 深绿仪表盘卡的底色：`.metric.featured` / `.sync-summary` / `.vehicle-art` 都用它。 */
@Composable
fun heroColor(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) NightHero else PineHero

/** 深绿卡上的次级文字色。 */
@Composable
fun heroMutedColor(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) NightHeroMuted else PineHeroMuted

/** `.records-summary`: `linear-gradient(135deg, var(--accent), var(--hero))`。 */
@Composable
fun accentToHeroBrush(): Brush =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
        Brush.linearGradient(listOf(NightAccentSoft, NightHero))
    } else {
        Brush.linearGradient(listOf(PineAccent, PineHero))
    }

/** `.metric` 的 `linear-gradient(145deg, #ffffff, #f8faf5)`。 */
@Composable
fun metricBrush(): Brush =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
        Brush.linearGradient(listOf(NightSurfaceAlt, NightSurface))
    } else {
        Brush.linearGradient(listOf(Color(0xFFFFFFFF), Color(0xFFF8FAF5)))
    }

/**
 * 松间仪表盘（Pine Dashboard）主题 —— 还原原 Vue 版的视觉语言。
 *
 * 两处有意偏离设计源的地方，理由是「不出现对比度不足 / 描边消失」优先：
 * 1. `--accent #236B50` 对纸感底 #EDF1EB 是 5.4:1，能当正文；但深色下的同值只有 2.9:1，
 *    所以深色把强调色换成提亮后的 #7FC7A3 / #A9DFC3。
 * 2. `--line #DBE3DA` 对白卡片只有 1.24:1，做输入框描边等于描边消失，
 *    所以 outline 换成同色族加深的 #8FA79A（3.1:1），原色留作 outlineVariant 只做装饰分隔线。
 */
@Composable
fun YouHaoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = YouHaoTypography,
        shapes = YouHaoShapes,
        content = content,
    )
}
