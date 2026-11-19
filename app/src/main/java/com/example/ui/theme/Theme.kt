package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

private val TapPilotDarkScheme = darkColorScheme(
    primary = CyberTeal,
    onPrimary = ObsidianBg,
    primaryContainer = CyberTealDark,
    onPrimaryContainer = CyberTeal,
    secondary = ElectricCyan,
    onSecondary = ObsidianBg,
    tertiary = AmberWarning,
    onTertiary = ObsidianBg,
    tertiaryContainer = AmberContainer,
    onTertiaryContainer = AmberWarning,
    error = CrimsonStop,
    onError = TextPrimary,
    errorContainer = CrimsonContainer,
    onErrorContainer = CrimsonStop,
    background = ObsidianBg,
    onBackground = TextPrimary,
    surface = SlateSurface,
    onSurface = TextPrimary,
    surfaceVariant = SlateSurfaceElevated,
    onSurfaceVariant = TextSecondary,
    outline = SlateBorder,
    outlineVariant = TextMuted
)

val TapPilotShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp)
)

@Composable
fun TapPilotTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = TapPilotDarkScheme,
        typography = TapPilotTypography,
        shapes = TapPilotShapes,
        content = content
    )
}
