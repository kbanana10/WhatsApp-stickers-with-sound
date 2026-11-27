package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

enum class ThemeMode {
    AUTO, LIGHT, DARK
}

private val LightColors = lightColorScheme(
    primary = VioletPrimaryLight,
    onPrimary = VioletOnPrimaryLight,
    primaryContainer = VioletContainerLight,
    onPrimaryContainer = VioletOnContainerLight,
    secondary = EmeraldSecondaryLight,
    onSecondary = EmeraldOnSecondaryLight,
    secondaryContainer = EmeraldContainerLight,
    onSecondaryContainer = EmeraldOnContainerLight,
    tertiary = AmberTertiaryLight,
    background = BackgroundLight,
    onBackground = TextPrimaryLight,
    surface = SurfaceLight,
    onSurface = TextPrimaryLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = TextMutedLight,
    outline = OutlineLight,
    error = StatusErrorLight
)

private val DarkColors = darkColorScheme(
    primary = VioletPrimaryDark,
    onPrimary = VioletOnPrimaryDark,
    primaryContainer = VioletContainerDark,
    onPrimaryContainer = VioletOnContainerDark,
    secondary = EmeraldSecondaryDark,
    onSecondary = EmeraldOnSecondaryDark,
    secondaryContainer = EmeraldContainerDark,
    onSecondaryContainer = EmeraldOnContainerDark,
    tertiary = AmberTertiaryDark,
    background = BackgroundDark,
    onBackground = TextPrimaryDark,
    surface = SurfaceDark,
    onSurface = TextPrimaryDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = TextMutedDark,
    outline = OutlineDark,
    error = StatusErrorDark
)

@Composable
fun AudioStickersTheme(
    themeMode: ThemeMode = ThemeMode.AUTO,
    isArabic: Boolean = true,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.AUTO -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = buildAppTypography(isArabic),
        content = content
    )
}
