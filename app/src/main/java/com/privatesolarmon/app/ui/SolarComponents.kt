package com.privatesolarmon.app.ui

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Router
import com.privatesolarmon.app.net.PiEndpoint
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

@Composable
fun SolarCard(
    modifier: Modifier = Modifier,
    pad: Dp = 16.dp,
    accent: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalSolarColors.current
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .then(
                if (accent) Modifier.background(Brush.verticalGradient(listOf(c.surface2, c.surface)))
                else Modifier.background(c.surface),
            )
            .border(1.dp, if (accent) c.accentLine else c.line, shape)
            .padding(pad),
        content = content,
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color? = null) {
    val c = LocalSolarColors.current
    Text(text.uppercase(), style = SolarType.cardHeading, color = color ?: c.txt3, modifier = modifier)
}

@Composable
fun DataRow(
    label: String,
    value: String,
    unit: String? = null,
    color: Color? = null,
    strong: Boolean = false,
    last: Boolean = false,
) {
    val c = LocalSolarColors.current
    Column {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(label, style = SolarType.body, color = c.txt2)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    value,
                    style = TextStyle(
                        fontFamily = PlexMono,
                        fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Medium,
                        fontSize = 15.sp,
                        letterSpacing = (-0.01).em,
                    ),
                    color = color ?: c.txt,
                )
                if (unit != null) {
                    Text(
                        " $unit",
                        style = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.Medium, fontSize = 12.5.sp),
                        color = c.txt3,
                    )
                }
            }
        }
        if (!last) HorizontalDivider(color = c.line)
    }
}

/** Rounded pill track with a solid fill and ~20 thin cell dividers (reads like cells). */
@Composable
fun SocBar(pct: Int, color: Color? = null, height: Dp = 12.dp, segments: Int = 20) {
    val c = LocalSolarColors.current
    val fillColor = color ?: c.charge
    val frac by animateFloatAsState(pct.coerceIn(0, 100) / 100f, tween(600), label = "soc")
    val shape = RoundedCornerShape(height / 2)
    Box(Modifier.fillMaxWidth().height(height).clip(shape).background(c.track).border(1.dp, c.line, shape)) {
        Box(Modifier.fillMaxWidth(frac).fillMaxHeight().clip(shape).background(fillColor))
        Row(Modifier.fillMaxSize()) {
            repeat(segments) { i ->
                Box(Modifier.weight(1f).fillMaxHeight())
                if (i < segments - 1) Box(Modifier.width(1.dp).fillMaxHeight().background(c.bg.copy(alpha = 0.5f)))
            }
        }
    }
}

/** Vertical state-of-charge bar that fills from the bottom. Caller sets the height via [modifier]. */
@Composable
fun VerticalSocBar(pct: Int, color: Color? = null, width: Dp = 18.dp, segments: Int = 10, modifier: Modifier = Modifier) {
    val c = LocalSolarColors.current
    val fillColor = color ?: c.charge
    val frac by animateFloatAsState(pct.coerceIn(0, 100) / 100f, tween(600), label = "socV")
    val shape = RoundedCornerShape(width / 2)
    Box(modifier.width(width).clip(shape).background(c.track).border(1.dp, c.line, shape), contentAlignment = Alignment.BottomCenter) {
        Box(Modifier.fillMaxWidth().fillMaxHeight(frac).clip(shape).background(fillColor))
        Column(Modifier.fillMaxSize()) {
            repeat(segments) { i ->
                Box(Modifier.weight(1f).fillMaxWidth())
                if (i < segments - 1) Box(Modifier.height(1.dp).fillMaxWidth().background(c.bg.copy(alpha = 0.5f)))
            }
        }
    }
}

@Composable
fun Pill(
    tone: String = "neutral",
    solid: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val c = LocalSolarColors.current
    val col = when (tone) {
        "accent" -> c.accent
        "green" -> c.charge
        "amber" -> c.discharge
        "red" -> c.fault
        else -> c.txt2
    }
    val soft = when (tone) {
        "accent" -> c.accentSoft
        "green" -> c.chargeSoft
        "amber" -> c.dischargeSoft
        "red" -> c.faultSoft
        else -> c.track
    }
    val shape = CircleShape
    CompositionLocalProvider(LocalContentColor provides if (solid) c.accentInk else col) {
        ProvideTextStyle(TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)) {
            Row(
                modifier
                    .clip(shape)
                    .background(if (solid) col else soft)
                    .then(if (solid) Modifier else Modifier.border(1.dp, c.line, shape))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
    }
}

@Composable
fun LiveDot(on: Boolean = true, label: String? = null, color: Color? = null) {
    val c = LocalSolarColors.current
    val col = color ?: if (on) c.charge else c.txt3
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        if (label != null) {
            Text(
                label.uppercase(),
                style = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 0.12.em),
                color = if (on) col else c.txt3,
            )
        }
        Box(Modifier.size(9.dp), contentAlignment = Alignment.Center) {
            if (on) {
                val t = rememberInfiniteTransition(label = "ping")
                val s by t.animateFloat(1f, 2f, infiniteRepeatable(tween(1800), RepeatMode.Restart), label = "s")
                val a by t.animateFloat(0.35f, 0f, infiniteRepeatable(tween(1800), RepeatMode.Restart), label = "a")
                Box(
                    Modifier.size(9.dp)
                        .graphicsLayer { scaleX = s; scaleY = s; alpha = a }
                        .border(2.dp, col, CircleShape),
                )
            }
            Box(Modifier.size(9.dp).clip(CircleShape).background(col))
        }
    }
}

@Composable
fun ThemeToggle(dark: Boolean, onToggle: () -> Unit) {
    val c = LocalSolarColors.current
    Box(
        Modifier.size(38.dp).clip(CircleShape).background(c.surface).border(1.dp, c.line, CircleShape).clickable { onToggle() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (dark) Icons.Outlined.DarkMode else Icons.Outlined.LightMode,
            contentDescription = "Toggle theme",
            tint = c.txt2,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** Pill banner shown on the data screens when the app is reading from a Solar Pi over the LAN
 *  instead of connecting to the batteries/inverter directly. Renders nothing in Direct mode. */
@Composable
fun DataSourceBanner(endpoint: PiEndpoint?, modifier: Modifier = Modifier) {
    if (endpoint == null) return
    val c = LocalSolarColors.current
    val how = if (endpoint.source == PiEndpoint.Source.MANUAL) "manual" else "auto-discovered"
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier.fillMaxWidth().clip(shape).background(c.accentSoft).border(1.dp, c.accentLine, shape)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Outlined.Router, null, tint = c.accent, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "Reading from Solar Pi",
                style = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp),
                color = c.txt,
            )
            Text("${endpoint.host}:${endpoint.port} · $how", style = SolarType.monoSmall, color = c.txt3)
        }
        Pill(tone = "accent") { Text("PI") }
    }
}

@Composable
fun ScreenHeader(
    title: String,
    dark: Boolean,
    onToggleTheme: () -> Unit,
    sub: String? = null,
    right: (@Composable () -> Unit)? = null,
) {
    val c = LocalSolarColors.current
    Row(
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = SolarType.screenTitle, color = c.txt)
            if (sub != null) {
                Row(
                    Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Outlined.Lock, null, tint = c.txt3, modifier = Modifier.size(12.dp))
                    Text(sub, style = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.Medium, fontSize = 11.5.sp), color = c.txt3)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            right?.invoke()
            ThemeToggle(dark, onToggleTheme)
        }
    }
}
