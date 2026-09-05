package hd.kinoshka.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hd.kinoshka.app.data.local.AppThemeMode

/**
 * Темы desktop-приложения — те же палитры, что в KinoTheme Android-приложения
 * (app/.../ui/theme/Theme.kt), без dynamicColor: выбор системная/тёмная/AMOLED
 * из настроек (AppThemeMode) проходит через FilmsViewModel.setThemeMode и там же
 * сохраняется, поэтому тема едина между платформами.
 */

private val ExpressiveLightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF1D4A8A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD4E3FF),
    onPrimaryContainer = Color(0xFF001B3C),
    secondary = Color(0xFF7C4D00),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDEA6),
    tertiary = Color(0xFF155B63),
    tertiaryContainer = Color(0xFFBEEAF2),
    background = Color(0xFFF7F9FF),
    onBackground = Color(0xFF1A1C20),
    surface = Color(0xFFF7F9FF),
    onSurface = Color(0xFF1A1C20),
    surfaceContainer = Color(0xFFE9EEF9),
    surfaceContainerHigh = Color(0xFFDDE5F5)
)

private val ExpressiveDarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFFA7C8FF),
    onPrimary = Color(0xFF002D64),
    primaryContainer = Color(0xFF003F8C),
    onPrimaryContainer = Color(0xFFD4E3FF),
    secondary = Color(0xFFF6BE5E),
    onSecondary = Color(0xFF432C00),
    secondaryContainer = Color(0xFF604100),
    tertiary = Color(0xFFA2D0D8),
    tertiaryContainer = Color(0xFF004B53),
    background = Color(0xFF10141D),
    onBackground = Color(0xFFE2E6EF),
    surface = Color(0xFF10141D),
    onSurface = Color(0xFFE2E6EF),
    surfaceContainer = Color(0xFF1B2231),
    surfaceContainerHigh = Color(0xFF202A3C)
)

private val AmoledDarkColors: ColorScheme = ExpressiveDarkColors.copy(
    primary = Color(0xFFA7C8FF),
    onPrimary = Color(0xFF0B111B),
    primaryContainer = Color(0xFF1A2638),
    onPrimaryContainer = Color(0xFFD7E7FF),
    secondary = Color(0xFFF6BE5E),
    onSecondary = Color(0xFF20180A),
    secondaryContainer = Color(0xFF31240F),
    tertiary = Color(0xFFA2D0D8),
    tertiaryContainer = Color(0xFF163038),
    background = Color(0xFF000000),
    onBackground = Color(0xFFF2F5FA),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFF2F5FA),
    surfaceDim = Color(0xFF000000),
    surfaceBright = Color(0xFF171717),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF040404),
    surfaceContainer = Color(0xFF0A0A0A),
    surfaceContainerHigh = Color(0xFF0E0E0E),
    surfaceContainerHighest = Color(0xFF151515),
    surfaceVariant = Color(0xFF0B0B0B)
)

private val KinoTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Black,
        fontSize = 56.sp,
        lineHeight = 60.sp,
        letterSpacing = (-0.4).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.2).sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    )
)

private val KinoShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(24.dp),
    large = RoundedCornerShape(32.dp),
    extraLarge = RoundedCornerShape(40.dp)
)

@Composable
fun KinoDesktopTheme(
    themeMode: AppThemeMode = AppThemeMode.CURRENT,
    content: @Composable () -> Unit
) {
    val colors = when (themeMode) {
        AppThemeMode.CURRENT -> if (isSystemInDarkTheme()) ExpressiveDarkColors else ExpressiveLightColors
        AppThemeMode.DARK -> ExpressiveDarkColors
        AppThemeMode.AMOLED -> AmoledDarkColors
    }
    MaterialTheme(
        colorScheme = colors,
        typography = KinoTypography,
        shapes = KinoShapes,
        content = content
    )
}
