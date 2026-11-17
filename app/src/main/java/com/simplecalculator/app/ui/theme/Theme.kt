package com.simplecalculator.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Ink = Color(0xFF08080B)
val Surface1 = Color(0xFF101015)
val Surface2 = Color(0xFF181820)
val Surface3 = Color(0xFF22222C)
val Stroke = Color(0xFF2A2A34)
val Rose = Color(0xFFFF3D6E)
val Violet = Color(0xFF8A6BFF)
val TextPrimary = Color(0xFFF6F6FA)
val TextMuted = Color(0xFF8E8E9E)

private val VaultScheme = darkColorScheme(
    primary = Rose,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3A1020),
    onPrimaryContainer = Color(0xFFFFD9E2),
    secondary = Violet,
    onSecondary = Color.White,
    tertiary = Color(0xFF4ED2A8),
    background = Ink,
    onBackground = TextPrimary,
    surface = Surface1,
    onSurface = TextPrimary,
    surfaceVariant = Surface2,
    surfaceContainer = Surface2,
    surfaceContainerHigh = Surface3,
    onSurfaceVariant = TextMuted,
    outline = Stroke,
    outlineVariant = Stroke,
    scrim = Color.Black
)

private val CalcScheme = darkColorScheme(
    primary = Color(0xFFFF8A3D),
    onPrimary = Color(0xFF1A1208),
    secondary = Color(0xFF57C7A3),
    background = Color(0xFF111114),
    onBackground = Color(0xFFECECEF),
    surface = Color(0xFF1A1A1F),
    onSurface = Color(0xFFECECEF),
    surfaceVariant = Color(0xFF242429),
    onSurfaceVariant = Color(0xFFA9A9B4),
    outline = Color(0xFF33333A)
)

private val VaultTypography = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        bodyMedium = base.bodyMedium.copy(fontWeight = FontWeight.Medium),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold)
    )
}

@Composable
fun VaultTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = VaultScheme,
        typography = VaultTypography,
        content = content
    )
}

@Composable
fun CalculatorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CalcScheme,
        typography = Typography(),
        content = content
    )
}

val CalculatorDisplayStyle = TextStyle(
    fontSize = 56.sp,
    fontWeight = FontWeight.Light,
    letterSpacing = (-1).sp
)
