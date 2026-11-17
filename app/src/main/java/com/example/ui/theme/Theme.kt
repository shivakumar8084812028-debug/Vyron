package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val VyronDarkColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = OnCyan,
    primaryContainer = CyanContainer,
    onPrimaryContainer = ElectricCyan,
    secondary = HdrAmber,
    onSecondary = OnAmber,
    secondaryContainer = AmberContainer,
    onSecondaryContainer = HdrAmber,
    tertiary = EmeraldNominal,
    onTertiary = OnCyan,
    tertiaryContainer = EmeraldContainer,
    onTertiaryContainer = EmeraldNominal,
    background = ObsidianVoid,
    onBackground = TextPrimary,
    surface = CarbonPanel,
    onSurface = TextPrimary,
    surfaceVariant = SlateElevated,
    onSurfaceVariant = TextSecondary,
    surfaceContainerLowest = ObsidianVoid,
    surfaceContainerLow = CarbonPanel,
    surfaceContainer = SlateElevated,
    surfaceContainerHigh = SlateHighlight,
    outline = HairlineBorder,
    outlineVariant = ColorTokens.SubtleBorder,
    error = CoralThermal,
    onError = TextPrimary,
    errorContainer = CoralContainer,
    onErrorContainer = CoralThermal
)

private object ColorTokens {
    val SubtleBorder = androidx.compose.ui.graphics.Color(0xFF1C2333)
}

private val VyronLightColorScheme = lightColorScheme(
    primary = CyanLightPrimary,
    secondary = AmberLightSecondary,
    background = StudioPaper,
    surface = StudioCardLight
)

val VyronShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun VyronPlayerTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) VyronDarkColorScheme else VyronLightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = VyronTypography,
        shapes = VyronShapes,
        content = content
    )
}
