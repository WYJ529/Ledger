package com.example.cpen321application.ui.theme

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

// Navy ledger theme. outline is the dark ink used for the thick panel and bar borders.
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFA9C4E4),
    onPrimary = Color(0xFF0E2238),
    primaryContainer = Color(0xFF2A4A70),
    onPrimaryContainer = Color(0xFFDCE8F6),
    secondary = Color(0xFFB5C4D6),
    secondaryContainer = Color(0xFF34465A),
    onSecondaryContainer = Color(0xFFD8E3EF),
    tertiary = Color(0xFFE5C28C),
    background = Color(0xFF10161D),
    surface = Color(0xFF151C24),
    surfaceVariant = Color(0xFF2C3744),
    surfaceContainerLowest = Color(0xFF0C1117),
    surfaceContainerLow = Color(0xFF18202A),
    surfaceContainer = Color(0xFF1C2530),
    surfaceContainerHigh = Color(0xFF253040),
    surfaceContainerHighest = Color(0xFF2E3A4A),
    onBackground = Color(0xFFE3E8EE),
    onSurface = Color(0xFFE3E8EE),
    onSurfaceVariant = Color(0xFFB6C1CD),
    outline = Color(0xFFC9D3DE),
    outlineVariant = Color(0xFF3A4654),
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1F3B5C),
    onPrimary = Color(0xFFF4F6F8),
    primaryContainer = Color(0xFF1F3B5C),
    onPrimaryContainer = Color(0xFFF4F6F8),
    secondary = Color(0xFF4A5D72),
    secondaryContainer = Color(0xFFDCE4EE),
    onSecondaryContainer = Color(0xFF172B42),
    tertiary = Color(0xFF79591E),
    tertiaryContainer = Color(0xFFFFE8BB),
    onTertiaryContainer = Color(0xFF4B350D),
    background = Color(0xFFE8ECF0),
    surface = Color(0xFFF4F6F8),
    surfaceVariant = Color(0xFFDCE2E9),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F3F6),
    surfaceContainer = Color(0xFFECF0F4),
    surfaceContainerHigh = Color(0xFFE4E9EF),
    surfaceContainerHighest = Color(0xFFDCE2E9),
    onBackground = Color(0xFF172B42),
    onSurface = Color(0xFF172B42),
    onSurfaceVariant = Color(0xFF4A5D72),
    outline = Color(0xFF1E2A36),
    outlineVariant = Color(0xFFB8C3CF),
)

// Small, nearly square corners in the style of the ESP32 monitor client.
private val LedgerShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(8.dp),
)

@Composable
fun CPEN321ApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = LedgerShapes,
        content = content
    )
}
