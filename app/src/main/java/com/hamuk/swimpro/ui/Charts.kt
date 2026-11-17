package com.hamuk.swimpro.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Animates from 0 to [target] once, and smoothly to new targets after that. */
@Composable
fun animatedProgress(target: Float, durationMs: Int = 1100, delayMs: Long = 0): Float {
    val a = remember { Animatable(0f) }
    LaunchedEffect(target) {
        if (delayMs > 0 && a.value == 0f) delay(delayMs)
        a.animateTo(target, tween(durationMs, easing = FastOutSlowInEasing))
    }
    return a.value
}

/** A number that counts up to its value. */
@Composable
fun CountUp(value: Double, format: (Double) -> String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    val v = animatedProgress(value.toFloat(), 900)
    Text(format(v.toDouble()), style = style, color = color, modifier = modifier, maxLines = 1)
}

data class Ring(val progress: Float, val color: Color)

/** Concentric activity rings (Samsung Health / Apple Fitness style) with animated sweep. */
@Composable
fun ActivityRings(rings: List<Ring>, size: Dp = 150.dp, stroke: Dp = 16.dp, gap: Dp = 4.dp) {
    val progresses = rings.mapIndexed { i, r -> animatedProgress(r.progress.coerceIn(0f, 1.5f), 1300, 120L * i) }
    Canvas(Modifier.size(size)) {
        val sw = stroke.toPx()
        rings.forEachIndexed { i, ring ->
            val inset = sw / 2 + i * (sw + gap.toPx())
            val arcSize = Size(this.size.width - inset * 2, this.size.height - inset * 2)
            val tl = Offset(inset, inset)
            drawArc(ring.color.copy(alpha = 0.18f), 0f, 360f, false, tl, arcSize, style = Stroke(sw))
            val sweep = 360f * progresses[i]
            if (sweep > 0.5f) {
                drawArc(
                    Brush.sweepGradient(listOf(ring.color.copy(alpha = 0.75f), ring.color, ring.color.copy(alpha = 0.75f)), center),
                    -90f, sweep, false, tl, arcSize, style = Stroke(sw, cap = StrokeCap.Round),
                )
            }
        }
    }
}

/**
 * Smooth area chart that draws itself in. [invertY] puts low values at the top (for pace).
 */
@Composable
fun AreaChart(
    values: List<Double>,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 150.dp,
    invertY: Boolean = false,
    startLabel: String? = null,
    endLabel: String? = null,
) {
    val p = LocalPalette.current
    if (values.size < 2) {
        Muted("Not enough data yet")
        return
    }
    val progress = animatedProgress(1f, 1400)
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val min = values.min()
            val max = values.max()
            val range = (max - min).takeIf { it > 1e-9 } ?: 1.0
            val padTop = 10.dp.toPx()
            val h = size.height - padTop
            fun pt(i: Int): Offset {
                val x = size.width * i / (values.size - 1)
                val n = ((values[i] - min) / range).toFloat()
                return Offset(x, padTop + if (invertY) h * n * 0.9f else h * (1 - n * 0.9f))
            }
            for (g in 0..3) {
                val y = padTop + h * g / 3f
                drawLine(p.track, Offset(0f, y), Offset(size.width, y), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
            }
            val line = Path()
            val pts = values.indices.map(::pt)
            line.moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) {
                val a = pts[i - 1]
                val b = pts[i]
                val mx = (a.x + b.x) / 2
                line.cubicTo(mx, a.y, mx, b.y, b.x, b.y)
            }
            val fill = Path().apply {
                addPath(line)
                lineTo(pts.last().x, size.height)
                lineTo(pts.first().x, size.height)
                close()
            }
            clipRect(right = size.width * progress) {
                drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.45f), color.copy(alpha = 0f))))
                drawPath(line, color, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
            }
            val lastIdx = ((pts.size - 1) * progress).toInt().coerceIn(0, pts.lastIndex)
            val last = pts[lastIdx]
            drawCircle(color.copy(alpha = 0.3f), 9.dp.toPx(), last)
            drawCircle(Color.White, 4.5.dp.toPx(), last)
            drawCircle(color, 3.dp.toPx(), last)
        }
        if (startLabel != null || endLabel != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Muted(startLabel ?: "")
                Muted(endLabel ?: "")
            }
        }
    }
}

/** Rounded bars that grow in, with optional faint target bars behind them. */
@Composable
fun BarChart(
    values: List<Double>,
    labels: List<String>,
    color: Color,
    modifier: Modifier = Modifier,
    targets: List<Double>? = null,
    height: Dp = 150.dp,
    highlightLast: Boolean = true,
) {
    val p = LocalPalette.current
    if (values.isEmpty()) {
        Muted("Not enough data yet")
        return
    }
    val progress = animatedProgress(1f, 1000)
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val max = (values + (targets ?: emptyList())).maxOrNull()?.takeIf { it > 0 } ?: 1.0
            val slot = size.width / values.size
            val w = (slot * 0.56f).coerceAtMost(28.dp.toPx())
            val r = CornerRadius(w / 2, w / 2)
            values.forEachIndexed { i, v ->
                val left = slot * i + (slot - w) / 2
                val target = targets?.getOrNull(i)?.takeIf { it > 0 }
                val trackH = target?.let { (it / max * size.height).toFloat() } ?: size.height
                drawRoundRect(p.track, Offset(left, size.height - trackH), Size(w, trackH), r)
                if (target != null) drawRoundRect(color.copy(alpha = 0.55f), Offset(left, size.height - trackH), Size(w, trackH), r, style = Stroke(1.5.dp.toPx()))
                val bh = (v / max * size.height * progress).toFloat()
                if (bh > 0.5f) {
                    val c = if (!highlightLast || i == values.lastIndex) color else color.copy(alpha = 0.65f)
                    drawRoundRect(Brush.verticalGradient(listOf(c, c.copy(alpha = 0.6f)), startY = size.height - bh, endY = size.height),
                        Offset(left, size.height - bh), Size(w, bh), r)
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            labels.forEach {
                Text(it, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = p.textDim, maxLines = 1,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

/** Horizontal stacked pill showing training zones Z1–Z5. */
@Composable
fun ZoneSpectrum(modifier: Modifier = Modifier) {
    val progress = animatedProgress(1f, 900)
    Row(modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(50)).graphicsLayer { scaleX = progress; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f) }) {
        (1..5).forEach { z -> Box(Modifier.weight(1f).height(10.dp).background(Accent.zone.getValue(z))) }
    }
}

/** Small coloured zone pill, e.g. "Z2". */
@Composable
fun ZonePill(zone: Int) {
    val color = Accent.zone[zone] ?: Accent.zone.getValue(0)
    val label = when (zone) { 0 -> "Easy"; 6 -> "Test"; else -> "Z$zone" }
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.22f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = color, style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

/** Three bouncing dots: "coach is typing". */
@Composable
fun TypingDots(color: Color) {
    val t = rememberInfiniteTransition(label = "dots")
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val y by t.animateFloat(
                0f, 1f,
                infiniteRepeatable(tween(520, delayMillis = i * 140, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label = "dot$i",
            )
            Box(Modifier.size(8.dp).graphicsLayer { translationY = -y * 6.dp.toPx(); alpha = 0.5f + 0.5f * y }.clip(CircleShape).background(color))
        }
    }
}

