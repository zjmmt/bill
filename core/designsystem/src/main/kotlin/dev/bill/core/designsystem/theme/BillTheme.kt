package dev.bill.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
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

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF123B68),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD5E5F5),
    onPrimaryContainer = Color(0xFF001C38),
    secondary = Color(0xFFB92724),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDAD6),
    onSecondaryContainer = Color(0xFF410002),
    tertiary = Color(0xFF8A5700),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDEA6),
    onTertiaryContainer = Color(0xFF2C1900),
    error = Color(0xFF8E2430),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFD9DE),
    onErrorContainer = Color(0xFF3E0010),
    background = Color(0xFFF3EBD8),
    onBackground = Color(0xFF15171C),
    surface = Color(0xFFFFF9E9),
    onSurface = Color(0xFF15171C),
    surfaceVariant = Color(0xFFE8DDC6),
    onSurfaceVariant = Color(0xFF51493D),
    outline = Color(0xFF766B5B),
    outlineVariant = Color(0xFFBDB19C),
    inverseSurface = Color(0xFF101D3D),
    inverseOnSurface = Color(0xFFFFF6E2),
    inversePrimary = Color(0xFF8AB4E1),
    scrim = Color(0xFF000000),
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF8AB4E1),
    onPrimary = Color(0xFF00344F),
    primaryContainer = Color(0xFF1E4F73),
    onPrimaryContainer = Color(0xFFD5E5F5),
    secondary = Color(0xFFFF6B5E),
    onSecondary = Color(0xFF4B0903),
    secondaryContainer = Color(0xFF8C2115),
    onSecondaryContainer = Color(0xFFFFDAD3),
    tertiary = Color(0xFFFFD18B),
    onTertiary = Color(0xFF482900),
    tertiaryContainer = Color(0xFF684000),
    onTertiaryContainer = Color(0xFFFFDEA6),
    error = Color(0xFFFFB2B8),
    onError = Color(0xFF67001C),
    errorContainer = Color(0xFF8E2430),
    onErrorContainer = Color(0xFFFFD9DE),
    background = Color(0xFF0B1324),
    onBackground = Color(0xFFF9F1DF),
    surface = Color(0xFF111C33),
    onSurface = Color(0xFFF9F1DF),
    surfaceVariant = Color(0xFF1B2945),
    onSurfaceVariant = Color(0xFFD0C5B1),
    outline = Color(0xFF9D927F),
    outlineVariant = Color(0xFF4B5870),
    inverseSurface = Color(0xFFFFF6E2),
    inverseOnSurface = Color(0xFF15171C),
    inversePrimary = Color(0xFF123B68),
    scrim = Color(0xFF000000),
)

object BillArtPalette {
    val SolarGold = Color(0xFFD9A326)
}

private val BillShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
)

private val BillTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Black,
        fontSize = 40.sp,
        lineHeight = 42.sp,
        letterSpacing = (-1).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 32.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 32.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 29.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 27.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.8.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 1.sp,
    ),
)

@Composable
fun BillTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = BillTypography,
        shapes = BillShapes,
        content = content,
    )
}
