package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape

private val DarkColorScheme =
  darkColorScheme(
    primary = Cyan400,
    onPrimary = Slate950,
    primaryContainer = Cyan900,
    onPrimaryContainer = Cyan300,
    secondary = Emerald400,
    onSecondary = Slate950,
    secondaryContainer = Emerald900,
    onSecondaryContainer = Emerald400,
    tertiary = Amber400,
    onTertiary = Slate950,
    tertiaryContainer = Amber900,
    onTertiaryContainer = Amber400,
    background = Slate950,
    onBackground = Slate200,
    surface = Slate900,
    onSurface = Slate200,
    surfaceVariant = Slate800,
    onSurfaceVariant = Slate400,
    surfaceContainerLow = Slate900,
    surfaceContainer = Slate850,
    surfaceContainerHigh = Slate800,
    outline = Slate700,
    outlineVariant = Color(0xFF233044),
    error = Rose400,
    onError = Slate950,
    errorContainer = Rose900,
    onErrorContainer = Rose400,
  )

private val LightColorScheme =
  lightColorScheme(
    primary = CyanPrimaryLight,
    onPrimary = Color.White,
    primaryContainer = CyanContainerLight,
    onPrimaryContainer = Color(0xFF0C4A6E),
    secondary = EmeraldSecondaryLight,
    onSecondary = Color.White,
    secondaryContainer = EmeraldContainerLight,
    onSecondaryContainer = Color(0xFF065F46),
    tertiary = Color(0xFFD97706),
    onTertiary = Color.White,
    background = LightBg,
    onBackground = SlateTextDark,
    surface = LightSurface,
    onSurface = SlateTextDark,
    surfaceVariant = LightSurfaceVar,
    onSurfaceVariant = SlateSubtextDark,
    surfaceContainerLow = LightSurface,
    surfaceContainer = Color(0xFFF1F5F9),
    surfaceContainerHigh = LightSurfaceVar,
    outline = LightOutline,
    outlineVariant = Color(0xFFE2E8F0),
  )

val AppShapes =
  Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
  )

@Composable
fun GsiExtractorTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit,
) {
  val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
  MaterialTheme(
    colorScheme = colorScheme,
    typography = AppTypography,
    shapes = AppShapes,
    content = content,
  )
}
