package com.privatesolarmon.app.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Token palette for the instrument-panel look. Accent is mode-aware (cyan in dark,
 * teal/blue in light). Everything else is cool neutrals + 4 semantic colors.
 */
data class SolarColors(
    val bg: Color,
    val sunken: Color,
    val surface: Color,
    val surface2: Color,
    val line: Color,
    val lineStrong: Color,
    val txt: Color,
    val txt2: Color,
    val txt3: Color,
    val accent: Color,
    val accent2: Color,
    val accentInk: Color,
    val charge: Color,
    val discharge: Color,
    val fault: Color,
    val load: Color,
    val isDark: Boolean,
) {
    val accentSoft get() = accent.copy(alpha = 0.14f)
    val accentLine get() = accent.copy(alpha = 0.40f)
    val track get() = if (isDark) Color.White.copy(alpha = 0.07f) else Color(0xFF0D1E2D).copy(alpha = 0.10f)
    val chargeSoft get() = charge.copy(alpha = 0.16f)
    val dischargeSoft get() = discharge.copy(alpha = 0.16f)
    val faultSoft get() = fault.copy(alpha = 0.16f)
    val loadSoft get() = load.copy(alpha = 0.16f)
}

val DarkColors = SolarColors(
    bg = Color(0xFF0E1116), sunken = Color(0xFF090B0F),
    surface = Color(0xFF161A21), surface2 = Color(0xFF1C212B),
    line = Color(0xFF262C37), lineStrong = Color(0xFF343C4A),
    txt = Color(0xFFEAF0F6), txt2 = Color(0xFF9BA7B6), txt3 = Color(0xFF626C7B),
    accent = Color(0xFF22D3EE), accent2 = Color(0xFF4F9CF9), accentInk = Color(0xFF04212B),
    charge = Color(0xFF34D399), discharge = Color(0xFFFBBF24), fault = Color(0xFFF87171), load = Color(0xFF9C8CFB),
    isDark = true,
)

val LightColors = SolarColors(
    bg = Color(0xFFEAEFF4), sunken = Color(0xFFDFE6ED),
    surface = Color(0xFFFFFFFF), surface2 = Color(0xFFF4F8FB),
    line = Color(0xFFDCE4EC), lineStrong = Color(0xFFC2CDD8),
    txt = Color(0xFF0E141B), txt2 = Color(0xFF54616F), txt3 = Color(0xFF8A97A5),
    accent = Color(0xFF0AA5C0), accent2 = Color(0xFF2E7DE0), accentInk = Color(0xFFFFFFFF),
    charge = Color(0xFF0E9F6E), discharge = Color(0xFFC77800), fault = Color(0xFFDC3838), load = Color(0xFF6A5CE0),
    isDark = false,
)

val LocalSolarColors = staticCompositionLocalOf { DarkColors }
