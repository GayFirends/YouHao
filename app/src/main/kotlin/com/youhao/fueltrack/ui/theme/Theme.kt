package com.youhao.fueltrack.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val YouHaoShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp), small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(22.dp),
)
private val LightColors = lightColorScheme(
    primary = Brick, onPrimary = OnBrick,
    primaryContainer = BrickSoft, onPrimaryContainer = Brick,
    secondary = PaperInk, onSecondary = PaperBg,
    secondaryContainer = PaperSurfaceAlt, onSecondaryContainer = PaperInk,
    tertiary = PaperInk, onTertiary = PaperBg,
    tertiaryContainer = PaperSurfaceAlt, onTertiaryContainer = PaperInk,
    background = PaperBg, onBackground = PaperInk,
    surface = PaperSurface, onSurface = PaperInk,
    surfaceVariant = PaperSurfaceAlt, onSurfaceVariant = PaperMuted,
    surfaceContainerLowest = PaperBg, surfaceContainerLow = PaperBg,
    surfaceContainer = PaperSurfaceAlt, surfaceContainerHigh = PaperSurfaceAlt,
    surfaceContainerHighest = PaperSurfaceAlt,
    outline = PaperLineStrong, outlineVariant = PaperLine,
    error = DangerRed, onError = OnBrick,
    errorContainer = DangerSoft, onErrorContainer = Color(0xFF5C1A12),
)
private val DarkColors = darkColorScheme(
    primary = NightBrick, onPrimary = NightOnBrick,
    primaryContainer = NightBrickSoft, onPrimaryContainer = NightBrick,
    secondary = NightInk, onSecondary = NightBg,
    secondaryContainer = NightSurfaceAlt, onSecondaryContainer = NightInk,
    tertiary = NightInk, onTertiary = NightBg,
    tertiaryContainer = NightSurfaceAlt, onTertiaryContainer = NightInk,
    background = NightBg, onBackground = NightInk,
    surface = NightSurface, onSurface = NightInk,
    surfaceVariant = NightSurfaceAlt, onSurfaceVariant = NightMuted,
    surfaceContainerLowest = NightBg, surfaceContainerLow = NightBg,
    surfaceContainer = NightSurfaceAlt, surfaceContainerHigh = NightSurfaceAlt,
    surfaceContainerHighest = NightSurfaceAlt,
    outline = NightLineStrong, outlineVariant = NightLine,
    error = NightDanger, onError = NightBg,
    errorContainer = Color(0xFF5C2420), onErrorContainer = Color(0xFFF9DEDC),
)
@Composable
fun YouHaoTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = YouHaoTypography, shapes = YouHaoShapes, content = content,
    )
}
