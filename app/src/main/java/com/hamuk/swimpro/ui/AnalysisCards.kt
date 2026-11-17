package com.hamuk.swimpro.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CompareArrows
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.ShowChart
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.TrendingDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hamuk.swimpro.analysis.Baselines
import com.hamuk.swimpro.analysis.HrPace
import com.hamuk.swimpro.analysis.Insight
import com.hamuk.swimpro.analysis.Metric
import com.hamuk.swimpro.analysis.MetricDelta
import com.hamuk.swimpro.analysis.Tone
import com.hamuk.swimpro.coach.CoachRepository.Companion.fmtDay
import com.hamuk.swimpro.coach.fmtPace
import com.hamuk.swimpro.container

/** Training-zone index for a pace relative to CSS (same offsets as the plan's zones). */
fun paceZone(pace: Double, css: Double): Int {
    val d = pace - css
    return when {
        d <= -4 -> 5
        d <= 1 -> 4
        d <= 5 -> 3
        d <= 9 -> 2
        else -> 1
    }
}

@Composable
fun InsightsCard(insights: List<Insight>, modifier: Modifier = Modifier) {
    if (insights.isEmpty()) return
    val p = LocalPalette.current
    GlassCard(modifier, strong = true) {
        CardHeader("Insights", Icons.Rounded.AutoAwesome, Accent.Pace)
        insights.forEach { i ->
            val c = when (i.tone) { Tone.GOOD -> Accent.Time; Tone.WARN -> Accent.Calories; Tone.INFO -> Accent.Swim }
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 6.dp).size(8.dp).background(c, CircleShape))
                Spacer(Modifier.width(10.dp))
                Text(i.text, style = MaterialTheme.typography.bodyMedium, color = p.text)
            }
        }
    }
}

/** Header row for delta tables: blank | A | B | Δ. */
@Composable
fun DeltaHeader(a: String, b: String?) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth()) {
        Spacer(Modifier.weight(1.3f))
        Text(a, Modifier.weight(1f), textAlign = TextAlign.End, style = MaterialTheme.typography.labelMedium, color = Accent.Swim)
        if (b != null) Text(b, Modifier.weight(1f), textAlign = TextAlign.End, style = MaterialTheme.typography.labelMedium, color = p.textDim)
        Text("change", Modifier.weight(1.1f), textAlign = TextAlign.End, style = MaterialTheme.typography.labelMedium, color = p.textDim)
    }
}

private enum class VsMode(val label: String) { PREVIOUS("Previous swim"), DAY("Previous day"), NORMAL("28-day normal") }

/** Full analysis of one swim, shown in the swim detail's Analysis tab. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SwimAnalysis(id: Long, onCompare: (Long, Long) -> Unit, onCompareDays: (Long, Long) -> Unit) {
    val app = LocalContext.current.container
    val p = LocalPalette.current
    val st by app.analysis.state.collectAsState()
    val m = st.byId(id)
    if (m == null) {
        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
            if (!st.ready) CircularProgressIndicator(color = Accent.Swim) else Muted("No analysis for this swim yet.")
        }
        return
    }
    val prev = st.previousOf(m)
    var mode by remember { mutableStateOf(VsMode.PREVIOUS) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        InsightsCard(remember(m, st) { st.insightsFor(m) }, Modifier.appear(0))
        BeforeSwimCard(m.swimId, Modifier.appear(1))

        // Headline tiles.
        Box(Modifier.appear(1)) {
            MetricGrid(listOfNotNull(
                { mod -> MetricTile(Icons.Rounded.Bolt, Accent.Calories, "Load (sTSS)", m.sTss?.let { "%.0f".format(it) } ?: "–", "", mod) },
                { mod -> MetricTile(Icons.Rounded.Speed, Accent.Pace, "Moving pace", m.pace?.let { fmtPace(it) } ?: "–", "/100m", mod) },
                { mod -> MetricTile(Icons.Rounded.ShowChart, Accent.Time, "Efficiency", m.ef?.let { "%.2f".format(it) } ?: "–", "m/min/bpm", mod) },
                { mod -> MetricTile(Icons.Rounded.TrendingDown, Accent.Heart, "Aerobic drift", m.decouplingPct?.let { "%.1f".format(it) } ?: "–", "%", mod) },
                { mod -> MetricTile(Icons.Rounded.MonitorHeart, Accent.Heart, "TRIMP", m.trimp?.let { "%.0f".format(it) } ?: "–", "", mod) },
                { mod -> MetricTile(Icons.Rounded.Timeline, Accent.Swim, "Rest", m.restPct?.let { "%.0f".format(it) } ?: "–", "%", mod) },
            ))
        }

        // Comparison table.
        GlassCard(Modifier.appear(2)) {
            CardHeader("Compared with", Icons.Rounded.CompareArrows, Accent.Swim)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                VsMode.entries.forEach { v -> GlassChip(v.label, mode == v, { mode = v }) }
            }
            when (mode) {
                VsMode.PREVIOUS -> if (prev == null) Muted("This is your first swim: nothing to compare yet.") else {
                    Muted("vs ${prev.epochDay.fmtDay()}")
                    DeltaHeader("This", "Prev")
                    Baselines.compare(listOf(m), listOf(prev)).forEach { DeltaRow(it) }
                    GlassButton("Open side-by-side", { onCompare(m.swimId, prev.swimId) }, Modifier.fillMaxWidth(), icon = Icons.Rounded.CompareArrows)
                }
                VsMode.DAY -> {
                    val prevDay = st.previousSwimDay(m.epochDay)
                    if (prevDay == null) Muted("No earlier swim day yet.") else {
                        val today = st.onDay(m.epochDay)
                        Muted("${m.epochDay.fmtDay()} (${today.size} swim${if (today.size > 1) "s" else ""}) vs ${prevDay.fmtDay()}")
                        DeltaHeader("Day", "Prev")
                        Baselines.compare(today, st.onDay(prevDay)).forEach { DeltaRow(it) }
                        GlassButton("Open side-by-side", { onCompareDays(m.epochDay, prevDay) }, Modifier.fillMaxWidth(), icon = Icons.Rounded.CompareArrows)
                    }
                }
                VsMode.NORMAL -> {
                    val rows = Metric.headline.mapNotNull { metric ->
                        val v = metric.get(m) ?: return@mapNotNull null
                        val b = Baselines.of(metric, st.swims, m.epochDay, excludeId = m.swimId) ?: return@mapNotNull null
                        Triple(MetricDelta(metric, v, b.mean), b, v)
                    }
                    if (rows.isEmpty()) Muted("Needs at least 3 swims in the 28 days before this one.") else {
                        DeltaHeader("This", "Avg")
                        rows.forEach { (d, b, _) -> DeltaRow(d, band = b.low to b.high) }
                        Muted("Bar = your normal range (average ± 1 SD over the previous 28 days).")
                    }
                }
            }
        }

        // HR zones.
        m.zoneSec?.let { z ->
            GlassCard(Modifier.appear(3)) {
                CardHeader("Heart-rate zones", Icons.Rounded.Favorite, Accent.Heart, trailing = "max ${st.hr.maxHr} bpm")
                StackedBar(z, (1..5).map { Accent.zone.getValue(it) }, listOf("Z1", "Z2", "Z3", "Z4", "Z5"))
                Muted("Z1 easy · Z2 aerobic base · Z3 tempo · Z4 threshold · Z5 max. Zones = 50/60/70/80/90 % of max HR.")
            }
        }

        // HR vs pace within the swim.
        HrPace.within(m)?.let { w ->
            GlassCard(Modifier.appear(4)) {
                CardHeader("Heart rate vs pace", Icons.Rounded.Favorite, Accent.Heart, trailing = "${w.points.size} lengths")
                val n = w.points.size
                ScatterChart(
                    w.points.map { lp ->
                        ScatterPoint(lp.pacePer100, lp.hr!!, lerp(Accent.Swim, Accent.Heart, lp.index / (n - 1f).coerceAtLeast(1f)),
                            highlight = lp.index in w.outliers)
                    },
                    fit = w.fit, xLabel = { fmtPace(it) }, yTitle = "bpm", xTitle = "pace /100m",
                )
                w.fit?.let { f ->
                    if (f.b < 0) Muted("Each 5 s/100m faster costs about ${"%.0f".format(-f.b * 5)} bpm (fit r² ${"%.2f".format(f.r2)}). Blue = early lengths, red = late; ringed = unusual lengths.")
                    else Muted("HR didn't rise with pace here: likely an easy, even swim.")
                }
            }
        }

        // Pacing.
        if (m.laps.size >= 4) GlassCard(Modifier.appear(5)) {
            CardHeader("Pacing", Icons.Rounded.Timeline, Accent.Pace, trailing = "best ${fmtPace(m.fastestLap!!)}")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MiniMetric("Split", m.splitPct?.let { (if (it > 0) "+" else "") + "%.1f %%".format(it) } ?: "–",
                    if ((m.splitPct ?: 0.0) <= 0) Accent.Time else Accent.Calories, Modifier.weight(1f))
                MiniMetric("Variation", m.cvPct?.let { "%.1f %%".format(it) } ?: "–", Accent.Swim, Modifier.weight(1f))
                MiniMetric("Fade", m.fadePct?.let { (if (it > 0) "+" else "") + "%.1f %%".format(it) } ?: "–",
                    if ((m.fadePct ?: 0.0) <= 3) Accent.Time else Accent.Heart, Modifier.weight(1f))
            }
            ZonePaceBars(m.laps.map { it.pacePer100 }, st.cssPer100)
            Muted("Bars are coloured by training zone (from your CSS ${fmtPace(st.cssPer100)}/100m); taller = faster.")
        }

        // Technique.
        if (m.swolf != null || m.strokeRate != null) GlassCard(Modifier.appear(6)) {
            CardHeader("Technique", Icons.Rounded.Repeat, Accent.Swolf)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MiniMetric("SWOLF", m.swolf?.let { "%.0f".format(it) } ?: "–", Accent.Swolf, Modifier.weight(1f))
                MiniMetric("Stroke rate", m.strokeRate?.let { "%.0f spm".format(it) } ?: "–", Accent.Pace, Modifier.weight(1f))
                MiniMetric("Dist/stroke", m.dps?.let { "%.2f m".format(it) } ?: "–", Accent.Time, Modifier.weight(1f))
            }
        }
        if (m.laps.isEmpty()) Muted("Length-by-length data wasn't available for this swim, so pacing, drift and HR-vs-pace are hidden.")
    }
}

@Composable
fun MiniMetric(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Column(modifier.background(color.copy(alpha = 0.12f), RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(value, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = p.text, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

/** Per-length bars: height = speed, colour = training zone of that length's pace. */
@Composable
fun ZonePaceBars(paces: List<Double>, css: Double) {
    val p = LocalPalette.current
    val progress = animatedProgress(1f, 900)
    Canvas(Modifier.fillMaxWidth().height(110.dp)) {
        val speeds = paces.map { 1.0 / it }
        val max = speeds.max(); val min = speeds.min() * 0.85
        val slot = size.width / paces.size
        val w = (slot * 0.7f).coerceAtLeast(2f)
        paces.forEachIndexed { i, pace ->
            val f = ((speeds[i] - min) / (max - min).coerceAtLeast(1e-9)).toFloat().coerceIn(0.08f, 1f)
            val h = size.height * f * progress
            drawRoundRect(p.track, Offset(slot * i + (slot - w) / 2, 0f), Size(w, size.height), CornerRadius(w / 2))
            drawRoundRect(Accent.zone.getValue(paceZone(pace, css)), Offset(slot * i + (slot - w) / 2, size.height - h), Size(w, h), CornerRadius(w / 2))
        }
    }
}
