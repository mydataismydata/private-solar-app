package com.privatesolarmon.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * Provides the [SolarColors] token set via [LocalSolarColors] and a matching Material3
 * scheme (so ripples / selection use the brand accent). [dark] is controlled by the app
 * and persisted; it switches both the neutrals and the accent hue.
 */
@Composable
fun SolarTheme(dark: Boolean, content: @Composable () -> Unit) {
    val colors = if (dark) DarkColors else LightColors
    val material = if (dark) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = colors.accentInk,
            background = colors.bg,
            surface = colors.surface,
            onBackground = colors.txt,
            onSurface = colors.txt,
            error = colors.fault,
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            onPrimary = colors.accentInk,
            background = colors.bg,
            surface = colors.surface,
            onBackground = colors.txt,
            onSurface = colors.txt,
            error = colors.fault,
        )
    }
    CompositionLocalProvider(LocalSolarColors provides colors) {
        MaterialTheme(colorScheme = material, content = content)
    }
}
