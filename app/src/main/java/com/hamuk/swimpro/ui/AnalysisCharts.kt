package com.hamuk.swimpro.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hamuk.swimpro.analysis.Fit
import com.hamuk.swimpro.analysis.MetricDelta
import kotlin.math.abs

data class Series(val values: List<Double?>, val color: Color, val label: String, val dashed: Boolean = false)

/**
 * Several lines on one shared y-axis (nulls leave gaps), with an optional shaded normal-range
 * band. [invertY] puts low values on top (pace). [onTap] reports the nearest x index.
 */
@Composable
fun MultiLineChart(
    series: List<Series>,
    modifier: Modifier = Modifier,
    height: Dp = 170.dp,
    band: Pair<Double, Double>? = null,
    invertY: Boolean = false,
    zeroLine: Boolean = false,
    startLabel: String? = null,
    endLabel: String? = null,
    onTap: ((Int) -> Unit)? = null,
) {
    val p = LocalPalette.current
    val all = series.flatMap { it.values.filterNotNull() } + listOfNotNull(band?.first, band?.second) + (if (zeroLine) listOf(0.0) else emptyList())
    val n = series.maxOfOrNull { it.values.size } ?: 0
    if (all.size < 2 || n < 2) { Muted("Not enough data yet"); return }
    val progress = animatedProgress(1f, 1200)
    Column(modifier) {
        Canvas(
            Modifier.fillMaxWidth().height(height).then(
                if (onTap != null) Modifier.pointerInput(n) {
                    detectTapGestures { o -> onTap(((o.x / size.width) * (n - 1)).let { Math.round(it) }.toInt().coerceIn(0, n - 1)) }
                } else Modifier,
            ),
        ) {
            val min = all.min(); val max = all.max()
            val range = (max - min).takeIf { it > 1e-9 } ?: 1.0
            val pad = 8.dp.toPx()
            val h = size.height - pad * 2
            fun y(v: Double): Float { val t = ((v - min) / range).toFloat(); return pad + if (invertY) h * t else h * (1 - t) }
            fun x(i: Int) = size.width * i / (n - 1)
            for (g in 0..3) {
                val gy = pad + h * g / 3f
                drawLine(p.track, Offset(0f, gy), Offset(size.width, gy), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
            }
            band?.let { (lo, hi) ->
                val top = minOf(y(lo), y(hi)); val bot = maxOf(y(lo), y(hi))
                drawRect(Accent.Swim.copy(alpha = 0.10f), Offset(0f, top), Size(size.width, bot - top))
            }
            if (zeroLine) drawLine(p.textDim.copy(alpha = 0.5f), Offset(0f, y(0.0)), Offset(size.width, y(0.0)), 1.dp.toPx())
            clipRect(right = size.width * progress) {
                series.forEach { s ->
                    val path = Path()
                    var started = false
                    s.values.forEachIndexed { i, v ->
                        if (v == null) { started = false; return@forEachIndexed }
                        if (!started) { path.moveTo(x(i), y(v)); started = true } else path.lineTo(x(i), y(v))
                    }
                    drawPath(path, s.color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round,
                        pathEffect = if (s.dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null))
                    if (s.values.count { it != null } <= 24) s.values.forEachIndexed { i, v ->
                        if (v != null) drawCircle(s.color, 3.dp.toPx(), Offset(x(i), y(v)))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Muted(startLabel ?: "")
            Spacer(Modifier.weight(1f))
            series.filter { it.label.isNotEmpty() }.forEach { s ->
                Box(Modifier.size(8.dp).background(s.color, CircleShape))
                Text(s.label, style = MaterialTheme.typography.labelSmall, color = p.textDim)
            }
            Spacer(Modifier.weight(1f))
            Muted(endLabel ?: "")
        }
    }
}

data class ScatterPoint(val x: Double, val y: Double, val color: Color, val id: Long = -1, val highlight: Boolean = false)

/**
 * Scatter plot with optional fit line. [invertX] puts low x on the right (pace: faster →).
 * Tapping reports the nearest point's id.
 */
@Composable
fun ScatterChart(
    points: List<ScatterPoint>,
    modifier: Modifier = Modifier,
    fit: Fit? = null,
    height: Dp = 200.dp,
    invertX: Boolean = true,
    xLabel: (Double) -> String = { "%.0f".format(it) },
    yLabel: (Double) -> String = { "%.0f".format(it) },
    xTitle: String = "",
    yTitle: String = "",
    marker: Pair<Double, Double>? = null,
    onTap: ((Long) -> Unit)? = null,
) {
    val p = LocalPalette.current
    if (points.size < 3) { Muted("Needs at least 3 points with heart rate."); return }
    val progress = animatedProgress(1f, 900)
    val xs = points.map { it.x }; val ys = points.map { it.y }
    val xMin = xs.min(); val xMax = xs.max(); val yMin = ys.min(); val yMax = ys.max()
    val xr = (xMax - xMin).takeIf { it > 1e-9 } ?: 1.0
    val yr = (yMax - yMin).takeIf { it > 1e-9 } ?: 1.0
    Column(modifier) {
        Row {
            Text(yTitle, style = MaterialTheme.typography.labelSmall, color = p.textDim, modifier = Modifier.weight(1f))
            Text("${yLabel(yMin)}–${yLabel(yMax)}", style = MaterialTheme.typography.labelSmall, color = p.textDim)
        }
        Canvas(
            Modifier.fillMaxWidth().height(height).then(
                if (onTap != null) Modifier.pointerInput(points) {
                    detectTapGestures { o ->
                        val pad = 12.dp.toPx()
                        val w = size.width - 2 * pad; val hh = size.height - 2 * pad
                        val nearest = points.minByOrNull { pt ->
                            val tx = ((pt.x - xMin) / xr).toFloat().let { if (invertX) 1 - it else it }
                            val px = pad + w * tx; val py = pad + hh * (1 - ((pt.y - yMin) / yr).toFloat())
                            (px - o.x) * (px - o.x) + (py - o.y) * (py - o.y)
                        }
                        nearest?.let { if (it.id >= 0) onTap(it.id) }
                    }
                } else Modifier,
            ),
        ) {
            val pad = 12.dp.toPx()
            val w = size.width - 2 * pad; val hh = size.height - 2 * pad
            fun px(x: Double) = pad + w * ((x - xMin) / xr).toFloat().let { if (invertX) 1 - it else it }
            fun py(y: Double) = pad + hh * (1 - ((y - yMin) / yr).toFloat())
            for (g in 0..3) {
                val gy = pad + hh * g / 3f
                drawLine(p.track, Offset(pad, gy), Offset(size.width - pad, gy), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
            }
            fit?.let { f ->
                val x0 = xMin; val x1 = xMax
                drawLine(Accent.Gold.copy(alpha = 0.9f), Offset(px(x0), py(f.y(x0)).coerceIn(pad, size.height - pad)),
                    Offset(px(x1), py(f.y(x1)).coerceIn(pad, size.height - pad)), 2.dp.toPx(), cap = StrokeCap.Round)
            }
            marker?.let { (mx, my) ->
                if (mx in xMin..xMax) {
                    drawLine(Accent.Gold.copy(alpha = 0.5f), Offset(px(mx), pad), Offset(px(mx), size.height - pad), 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                    drawCircle(Accent.Gold, 6.dp.toPx(), Offset(px(mx), py(my).coerceIn(pad, size.height - pad)))
                }
            }
            points.forEachIndexed { i, pt ->
                val r = (if (pt.highlight) 6.5f else 5f).dp.toPx() * (progress * points.size - i).coerceIn(0f, 1f)
                if (r > 0) {
                    drawCircle(pt.color, r, Offset(px(pt.x), py(pt.y)))
                    if (pt.highlight) drawCircle(Color.White, r, Offset(px(pt.x), py(pt.y)), style = Stroke(1.5.dp.toPx()))
                }
            }
        }
        Row {
            Text(if (invertX) "${xLabel(xMax)} slower" else xLabel(xMin), style = MaterialTheme.typography.labelSmall, color = p.textDim)
            Text(xTitle, style = MaterialTheme.typography.labelSmall, color = p.textDim, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            Text(if (invertX) "faster ${xLabel(xMin)}" else xLabel(xMax), style = MaterialTheme.typography.labelSmall, color = p.textDim)
        }
    }
}

/** Horizontal stacked bar (e.g. time in HR zones) with labels underneath. */
@Composable
fun StackedBar(values: List<Double>, colors: List<Color>, labels: List<String>, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val total = values.sum()
    if (total <= 0) { Muted("No data"); return }
    val progress = animatedProgress(1f, 900)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().height(16.dp).clip(RoundedCornerShape(50)).background(p.track)) {
            values.forEachIndexed { i, v ->
                val f = (v / total * progress).toFloat()
                if (f > 0.001f) Box(Modifier.weight(f).height(16.dp).background(colors[i]))
            }
            val rest = 1f - progress
            if (rest > 0.001f) Spacer(Modifier.weight(rest))
        }
        Row(Modifier.fillMaxWidth()) {
            values.forEachIndexed { i, v ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(labels[i], style = MaterialTheme.typography.labelSmall, color = colors[i])
                    Text("${(v / 60).toInt()}′", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = p.text)
                    Text("${(v / total * 100).toInt()} %", style = MaterialTheme.typography.labelSmall, color = p.textDim)
                }
            }
        }
    }
}

/** One comparison row: label, value A, value B and a coloured ▲▼ delta. */
@Composable
fun DeltaRow(d: MetricDelta, showB: Boolean = true, band: Pair<Double, Double>? = null) {
    val p = LocalPalette.current
    val color = when (d.better) { true -> Accent.Time; false -> Accent.Heart; null -> p.textDim }
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1.3f)) {
            Text(d.metric.label, style = MaterialTheme.typography.bodyMedium, color = p.text)
            if (d.metric.unit.isNotEmpty()) Text(d.metric.unit, style = MaterialTheme.typography.labelSmall, color = p.textDim)
        }
        Text(d.a?.let { d.metric.format(it) } ?: "–", Modifier.weight(1f), textAlign = TextAlign.End,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = p.text)
        if (showB) Text(d.b?.let { d.metric.format(it) } ?: "–", Modifier.weight(1f), textAlign = TextAlign.End,
            style = MaterialTheme.typography.bodyMedium, color = p.textDim)
        Box(Modifier.weight(1.1f), contentAlignment = Alignment.CenterEnd) {
            val diff = d.diff
            // A change that rounds to zero at display precision is shown as "=".
            val visible = diff != null && d.metric.format(abs(diff)).any { it in '1'..'9' }
            if (diff != null && visible) {
                val arrow = if (diff > 0) "▲" else "▼"
                Text("$arrow ${d.metric.formatDelta(diff)}", style = MaterialTheme.typography.labelLarge, color = color,
                    modifier = Modifier.background(color.copy(alpha = 0.14f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 3.dp))
            } else if (diff != null) Text("=", color = p.textDim)
        }
    }
    if (band != null && d.a != null) RangeBar(band.first, band.second, d.a, d.metric.higherIsBetter)
}

/** Thin bar showing the normal range (mean ± SD) and where today's value sits. */
@Composable
fun RangeBar(low: Double, high: Double, value: Double, higherIsBetter: Boolean?) {
    val p = LocalPalette.current
    val span = (high - low).takeIf { it > 1e-9 } ?: 1.0
    val lo = low - span; val hi = high + span
    Canvas(Modifier.fillMaxWidth().height(10.dp).padding(horizontal = 2.dp)) {
        fun x(v: Double) = (((v - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f)) * size.width
        val cy = size.height / 2
        drawLine(p.track, Offset(0f, cy), Offset(size.width, cy), 3.dp.toPx(), cap = StrokeCap.Round)
        drawLine(Accent.Swim.copy(alpha = 0.45f), Offset(x(low), cy), Offset(x(high), cy), 5.dp.toPx(), cap = StrokeCap.Round)
        val inRange = value in low..high
        val good = if (higherIsBetter == null || inRange) null else (value > high) == higherIsBetter
        val c = when (good) { true -> Accent.Time; false -> Accent.Heart; null -> p.text }
        drawCircle(c, 4.5.dp.toPx(), Offset(x(value), cy))
    }
}
