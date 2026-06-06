package com.privatesolarmon.app.ui

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.privatesolarmon.app.R

val PlexSans = FontFamily(
    Font(R.font.ibm_plex_sans_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_sans_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_sans_semibold, FontWeight.SemiBold),
    Font(R.font.ibm_plex_sans_bold, FontWeight.Bold),
)

val PlexMono = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_mono_semibold, FontWeight.SemiBold),
)

/** Named roles. Rule: any measured value is Plex Mono (tabular); labels are Plex Sans. */
object SolarType {
    val screenTitle = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, letterSpacing = (-0.02).em)
    val cardHeading = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 0.14.em)
    val body = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.Normal, fontSize = 14.5.sp)
    val label = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.Medium, fontSize = 12.sp)
    val labelTiny = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.Medium, fontSize = 10.5.sp, letterSpacing = 0.1.em)
    val navLabel = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.Medium, fontSize = 11.sp)
    val metric = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.SemiBold, fontSize = 38.sp, letterSpacing = (-0.02).em)
    val monoValue = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.Medium, fontSize = 15.sp)
    val monoSmall = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.Medium, fontSize = 12.sp)
}
