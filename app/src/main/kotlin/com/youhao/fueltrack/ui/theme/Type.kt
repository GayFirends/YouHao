package com.youhao.fueltrack.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 「松间仪表盘」排版：正文比 Material 默认稍大一档，标题改用紧字距。
 *
 * 数值不在这里定死等宽 —— 见 [numeric]，由调用点显式套一层，
 * 这样中文标签不会因为用了等宽字族而变形。
 */
val YouHaoTypography = Typography(
    // headlineMedium 就是页面主标题：对应 CSS `.page-title h1` 的 31px / 680 / -1.1px 字距。
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight(680),
        fontSize = 31.sp,
        lineHeight = 38.sp,
        letterSpacing = (-1.1).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight(650),
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.7).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.4).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 25.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.5.sp,
        lineHeight = 22.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.5.sp,
        lineHeight = 18.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.5.sp,
        lineHeight = 16.sp,
    ),
)

/**
 * CSS `.eyebrow`：11px / 700 / 1.45px 字距 / 大写。
 * 中文没有大小写，所以「大写」这一层由调用方决定要不要 `.uppercase()`。
 */
val EyebrowStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Bold,
    fontSize = 11.sp,
    lineHeight = 14.sp,
    letterSpacing = 1.45.sp,
)

/**
 * 仪表盘数字：对应 CSS `.metric-value` 与 `.records-summary strong`（32px）。
 * 比页面主标题更大更细，是整屏的视觉锚点。
 */
val MetricValueStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight(650),
    fontSize = 34.sp,
    lineHeight = 40.sp,
    letterSpacing = (-0.9).sp,
)

/** 次要仪表盘数字：`.metric-value` 里的小号变体。 */
val MetricValueSmallStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.SemiBold,
    fontSize = 19.sp,
    lineHeight = 26.sp,
    letterSpacing = (-0.3).sp,
)

/**
 * 等宽数字：所有金额 / 油量 / 里程 / 油耗都套这一层。
 *
 * `FontFamily.Monospace` 只作用于数字与拉丁字母，中文会自动回退到系统 CJK 字体，
 * 所以「累计花费」这类标签不会跟着变形；`tnum` 让数字保持等宽字形，
 * 多行金额的小数点可以竖直对齐。
 */
fun TextStyle.numeric(): TextStyle = copy(
    fontFamily = FontFamily.Monospace,
    fontFeatureSettings = "tnum",
)
