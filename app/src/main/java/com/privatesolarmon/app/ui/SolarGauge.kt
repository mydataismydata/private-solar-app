package com.privatesolarmon.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 270° radial gauge with a gap at the bottom: tick ring, track, gradient fill,
 * and a filled tip marker at the value. Center stacks the mono value + unit + sub-label.
 */
@Composable
fun RadialGauge(
    value: Float,
    max: Float,
    unit: String,
    sub: String,
    c1: Color,
    c2: Color,
    modifier: Modifier = Modifier,
) {
    val c = LocalSolarColors.current
    val target = (value / (if (max <= 0f) 1f else max)).coerceIn(0f, 1f)
    val pct by animateFloatAsState(target, tween(700), label = "gauge")
    val start = 135f
    val sweep = 270f
    Box(modifier.size(196.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = 13.dp.toPx()
            val pad = 16.dp.toPx()
            val diameter = size.minDimension - pad * 2
            val tl = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
            val arcSize = Size(diameter, diameter)
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = diameter / 2f

            val ticks = 11
            for (i in 0 until ticks) {
                val ang = Math.toRadians((start + sweep * i / (ticks - 1)).toDouble())
                val major = i % 5 == 0
                val rIn = radius + sw / 2 + 4.dp.toPx()
                val rOut = radius + sw / 2 + (if (major) 11 else 7).dp.toPx()
                val cs = cos(ang).toFloat()
                val sn = sin(ang).toFloat()
                drawLine(
                    c.txt3.copy(alpha = if (major) 0.7f else 0.4f),
                    Offset(center.x + rIn * cs, center.y + rIn * sn),
                    Offset(center.x + rOut * cs, center.y + rOut * sn),
                    strokeWidth = (if (major) 1.6f else 1f).dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }

            drawArc(c.track, start, sweep, false, tl, arcSize, style = Stroke(sw, cap = StrokeCap.Round))

            val brush = Brush.linearGradient(listOf(c1, c2), start = Offset(0f, size.height), end = Offset(size.width, 0f))
            drawArc(brush, start, sweep * pct, false, tl, arcSize, style = Stroke(sw, cap = StrokeCap.Round))

            val tipAng = Math.toRadians((start + sweep * pct).toDouble())
            val tip = Offset(center.x + radius * cos(tipAng).toFloat(), center.y + radius * sin(tipAng).toFloat())
            drawCircle(c.surface, radius = sw / 2 + 1.5.dp.toPx(), center = tip)
            drawCircle(c2, radius = sw / 2 + 1.5.dp.toPx(), center = tip, style = Stroke(2.dp.toPx()))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    value.roundToInt().toString(),
                    style = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.SemiBold, fontSize = 38.sp, letterSpacing = (-0.02).em),
                    color = c.txt,
                )
                Text(
                    " $unit",
                    style = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.Medium, fontSize = 16.sp),
                    color = c.txt2,
                    modifier = Modifier.padding(bottom = 5.dp),
                )
            }
            Text(
                sub.uppercase(),
                style = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.12.em),
                color = c.txt3,
            )
        }
    }
}
