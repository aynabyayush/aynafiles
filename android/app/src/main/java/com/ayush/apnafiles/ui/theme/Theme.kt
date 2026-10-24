package com.ayush.apnafiles.ui.theme

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val White = Color(0xFFFFFFFF)
private val Black = Color(0xFF000000)
private val ClayInk = Color(0xFF2C1A08)
private val ErrorInk = Color(0xFF3D0F0A)
private val OutlineLight = Color(0xFFD9D6CB)
private val OutlineVariantLight = Color(0xFFE9E6DC)
private val OutlineDark = Color(0xFF7C8A84)
private val NightHighest = Color(0xFF26302C)
private val NightSecondary = Color(0xFFB8CCC2)

private val LightScheme = lightColorScheme(
    primary = Pine700,
    onPrimary = White,
    primaryContainer = Pine100,
    onPrimaryContainer = Pine900,
    secondary = Pine500,
    onSecondary = White,
    secondaryContainer = Pine050,
    onSecondaryContainer = Pine900,
    tertiary = Clay700,
    onTertiary = White,
    tertiaryContainer = Clay100,
    onTertiaryContainer = ClayInk,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = PaperSunken,
    onSurfaceVariant = InkSoft,
    surfaceContainerLowest = PaperRaised,
    surfaceContainerLow = Paper,
    surfaceContainer = PaperSunken,
    surfaceContainerHigh = PaperSunken,
    surfaceContainerHighest = PaperSunken,
    outline = OutlineLight,
    outlineVariant = OutlineVariantLight,
    error = ErrorRed,
    onError = White,
    errorContainer = ErrorSoft,
    onErrorContainer = ErrorInk,
    inverseSurface = Pine900,
    inverseOnSurface = Paper
)

private val DarkScheme = darkColorScheme(
    primary = NightPine,
    onPrimary = Pine900,
    primaryContainer = NightPineContainer,
    onPrimaryContainer = Pine100,
    secondary = NightSecondary,
    onSecondary = Pine900,
    secondaryContainer = NightSunken,
    onSecondaryContainer = NightInk,
    tertiary = NightClay,
    onTertiary = ClayInk,
    tertiaryContainer = NightClayContainer,
    onTertiaryContainer = Clay100,
    background = Night,
    onBackground = NightInk,
    surface = Night,
    onSurface = NightInk,
    surfaceVariant = NightSunken,
    onSurfaceVariant = NightInkSoft,
    surfaceContainerLowest = Black,
    surfaceContainerLow = Night,
    surfaceContainer = NightRaised,
    surfaceContainerHigh = NightSunken,
    surfaceContainerHighest = NightHighest,
    outline = OutlineDark,
    outlineVariant = NightHighest,
    error = NightError,
    onError = ClayInk,
    errorContainer = NightErrorContainer,
    onErrorContainer = ErrorSoft,
    inverseSurface = Paper,
    inverseOnSurface = Ink
)

private val ApnaShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun ApnaFilesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkScheme
        else -> LightScheme
    }

    MaterialTheme(
        colorScheme = scheme,
        typography = ApnaTypography,
        shapes = ApnaShapes,
        content = content
    )
}
