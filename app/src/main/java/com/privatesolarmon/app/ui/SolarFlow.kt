package com.privatesolarmon.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Power
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/**
 * Solar → Inverter → Battery, joined by wires with a single animated dot encoding flow.
 * [vertical] stacks the nodes top-to-bottom (used by the narrow landscape column).
 */
@Composable
fun FlowDiagram(pv: Int, load: Int, battW: Int, vertical: Boolean = false) {
    val c = LocalSolarColors.current
    val charging = battW >= 0
    val battTone = if (charging) c.charge else c.discharge
    val battSoft = if (charging) c.chargeSoft else c.dischargeSoft
    if (vertical) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            FlowNode("Solar", pv, Icons.Outlined.LightMode, c.accent, c.accentSoft)
            FlowWireVertical(active = pv > 0, color = c.accent, reverse = false)
            FlowNode("Inverter", load, Icons.Outlined.Power, c.txt, c.surface2)
            FlowWireVertical(active = abs(battW) > 5, color = battTone, reverse = !charging)
            FlowNode("Battery", battW, Icons.Outlined.BatteryFull, battTone, battSoft, signed = true)
        }
    } else {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            FlowNode("Solar", pv, Icons.Outlined.LightMode, c.accent, c.accentSoft, Modifier.weight(1f))
            FlowWire(active = pv > 0, color = c.accent, reverse = false, modifier = Modifier.weight(1f))
            FlowNode("Inverter", load, Icons.Outlined.Power, c.txt, c.surface2, Modifier.weight(1f))
            FlowWire(active = abs(battW) > 5, color = battTone, reverse = !charging, modifier = Modifier.weight(1f))
            FlowNode("Battery", battW, Icons.Outlined.BatteryFull, battTone, battSoft, Modifier.weight(1f), signed = true)
        }
    }
}

@Composable
private fun FlowNode(
    label: String,
    value: Int,
    icon: ImageVector,
    tone: Color,
    soft: Color,
    modifier: Modifier = Modifier,
    signed: Boolean = false,
) {
    val c = LocalSolarColors.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.size(46.dp).clip(RoundedCornerShape(13.dp)).background(soft).border(1.dp, c.line, RoundedCornerShape(13.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, label, tint = tone, modifier = Modifier.size(22.dp))
        }
        Row(verticalAlignment = Alignment.Bottom) {
            val shown = if (signed && value > 0) "+$value" else value.toString()
            Text(shown, style = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.SemiBold, fontSize = 14.sp), color = c.txt)
            Text(" W", style = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.Medium, fontSize = 10.sp), color = c.txt3)
        }
        Text(
            label.uppercase(),
            style = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.Medium, fontSize = 10.sp, letterSpacing = 0.1.em),
            color = c.txt3,
        )
    }
}

@Composable
private fun FlowWireVertical(active: Boolean, color: Color, reverse: Boolean, modifier: Modifier = Modifier) {
    val c = LocalSolarColors.current
    Box(modifier.height(38.dp).width(46.dp), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.fillMaxHeight().width(2.dp).background(c.line))
        if (active) {
            val t = rememberInfiniteTransition(label = "flowV")
            val p by t.animateFloat(
                if (reverse) 1f else 0f,
                if (reverse) 0f else 1f,
                infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart),
                label = "pV",
            )
            BoxWithConstraints(Modifier.fillMaxHeight()) {
                val h = maxHeight
                Box(
                    Modifier.align(Alignment.TopCenter)
                        .offset(y = h * p - 3.5.dp)
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(color),
                )
            }
        }
    }
}

@Composable
private fun FlowWire(active: Boolean, color: Color, reverse: Boolean, modifier: Modifier = Modifier) {
    val c = LocalSolarColors.current
    Box(modifier.height(46.dp), contentAlignment = Alignment.CenterStart) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(c.line))
        if (active) {
            val t = rememberInfiniteTransition(label = "flow")
            val p by t.animateFloat(
                if (reverse) 1f else 0f,
                if (reverse) 0f else 1f,
                infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart),
                label = "p",
            )
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val w = maxWidth
                Box(
                    Modifier.align(Alignment.CenterStart)
                        .offset(x = w * p - 3.5.dp)
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(color),
                )
            }
        }
    }
}
