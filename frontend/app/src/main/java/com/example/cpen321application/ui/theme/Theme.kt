package com.example.cpen321application.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF82D5C2),
    onPrimary = Color(0xFF00382D),
    primaryContainer = Color(0xFF174E43),
    onPrimaryContainer = Color(0xFFC6F4E7),
    secondary = Color(0xFFB3CCC3),
    secondaryContainer = Color(0xFF304D44),
    onSecondaryContainer = Color(0xFFD2EADF),
    tertiary = Color(0xFFE5C28C),
    background = Color(0xFF101B19),
    surface = Color(0xFF101B19),
    surfaceVariant = Color(0xFF334540),
    surfaceContainerLowest = Color(0xFF0B1512),
    surfaceContainerLow = Color(0xFF182520),
    surfaceContainer = Color(0xFF1C2B25),
    surfaceContainerHigh = Color(0xFF26372F),
    surfaceContainerHighest = Color(0xFF304139),
    onSurface = Color(0xFFE4EAE4),
    onSurfaceVariant = Color(0xFFBDCCC4),
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF166B57),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF173F38),
    onPrimaryContainer = Color(0xFFF2FCF7),
    secondary = Color(0xFF466459),
    secondaryContainer = Color(0xFFDCEDE1),
    onSecondaryContainer = Color(0xFF173C2D),
    tertiary = Color(0xFF79591E),
    tertiaryContainer = Color(0xFFFFE8BB),
    onTertiaryContainer = Color(0xFF4B350D),
    background = Color(0xFFF8F9F3),
    surface = Color(0xFFF8F9F3),
    surfaceVariant = Color(0xFFE8EEE6),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF4F7EF),
    surfaceContainer = Color(0xFFEEF3EA),
    surfaceContainerHigh = Color(0xFFE8EFE4),
    surfaceContainerHighest = Color(0xFFE2EBDD),
    onBackground = Color(0xFF1B3029),
    onSurface = Color(0xFF1B3029),
    onSurfaceVariant = Color(0xFF4A6057),
    outline = Color(0xFF788A7F),
    outlineVariant = Color(0xFFCED9CF),
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
        content = content
    )
}
