package com.hamuk.swimpro.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CompareArrows
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hamuk.swimpro.analysis.AnalysisState
import com.hamuk.swimpro.analysis.Baselines
import com.hamuk.swimpro.analysis.Fit
import com.hamuk.swimpro.analysis.HrPace
import com.hamuk.swimpro.analysis.LoadModel
import com.hamuk.swimpro.analysis.Metric
import com.hamuk.swimpro.coach.CoachRepository.Companion.fmtDay
import com.hamuk.swimpro.coach.fmtPace
import com.hamuk.swimpro.coach.mondayOf
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

/** Fitness / fatigue / form over 90 days, plus today's values and the ACWR gauge. */
@Composable
fun TrainingLoadCard(st: AnalysisState, modifier: Modifier = Modifier) {
    if (st.swims.isEmpty()) return
    val p = LocalPalette.current
    val series = remember(st) { st.loadSeries(90) }
    val state = remember(st) { LoadModel.state(st.loadSeries(400)) } ?: return
    GlassCard(modifier) {
        CardHeader("Training load", Icons.Rounded.Bolt, Accent.Calories, trailing = LoadModel.formLabel(state.form))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MiniMetric("Fitness", "%.0f".format(state.fitness), Accent.Swim, Modifier.weight(1f))
            MiniMetric("Fatigue", "%.0f".format(state.fatigue), Accent.Heart, Modifier.weight(1f))
            MiniMetric("Form", (if (state.form > 0) "+" else "") + "%.0f".format(state.form), Accent.Gold, Modifier.weight(1f))
        }
        MultiLineChart(
            listOf(
                Series(series.map { it.fitness }, Accent.Swim, "Fitness"),
                Series(series.map { it.fatigue }, Accent.Heart, "Fatigue", dashed = true),
                Series(series.map { it.form }, Accent.Gold, "Form"),
            ),
            zeroLine = true, startLabel = series.firstOrNull()?.epochDay?.fmtDay(), endLabel = "today",
        )
        Text("Acute : chronic load", style = MaterialTheme.typography.labelLarge, color = p.textDim)
        AcwrGauge(state.acwr)
        Row {
            Text(state.acwr?.let { "%.2f".format(it) } ?: "–", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = p.text)
            Spacer(Modifier.width(8.dp))
            Text(LoadModel.acwrLabel(state.acwr), style = MaterialTheme.typography.bodyMedium, color = p.textDim)
        }
        Muted("Fitness = 6-week load average, Fatigue = 1-week. Form = Fitness − Fatigue: negative while building, positive when fresh. Load uses swim TSS from your CSS pace.")
    }
}

@Composable
private fun AcwrGauge(acwr: Double?) {
    val p = LocalPalette.current
    val bands = listOf(0.0 to 0.8 to Accent.Swim, 0.8 to 1.3 to Accent.Time, 1.3 to 1.5 to Accent.Calories, 1.5 to 2.0 to Accent.Heart)
    val prog = animatedProgress(((acwr ?: 0.0) / 2.0).toFloat().coerceIn(0f, 1f), 1000)
    Canvas(Modifier.fillMaxWidth().height(18.dp)) {
        val cy = size.height / 2
        bands.forEach { (r, c) ->
            drawLine(c.copy(alpha = 0.55f), Offset(size.width * (r.first / 2).toFloat(), cy), Offset(size.width * (r.second / 2).toFloat(), cy), 8.dp.toPx())
        }
        if (acwr != null) {
            drawCircle(Color.White, 8.dp.toPx(), Offset(size.width * prog, cy))
            drawCircle(p.bgBottom, 4.dp.toPx(), Offset(size.width * prog, cy))
        }
    }
}

@Composable
fun WeekVsWeekCard(st: AnalysisState, onCompare: (Long, Long) -> Unit, modifier: Modifier = Modifier) {
    val thisMonday = mondayOf(LocalDate.now())
    val rows = remember(st) { st.weekCompare(thisMonday) }
    if (rows.isEmpty()) return
    GlassCard(modifier) {
        CardHeader("This week vs last week", Icons.Rounded.CompareArrows, Accent.Swim)
        DeltaHeader("This", "Last")
        rows.take(8).forEach { DeltaRow(it) }
        GlassButton("Compare weeks", { onCompare(thisMonday.toEpochDay(), thisMonday.minusWeeks(1).toEpochDay()) }, Modifier.fillMaxWidth(),
            icon = Icons.Rounded.CompareArrows)
    }
}

/** All swims' HR vs pace, the 28-day fit and the "pace at X bpm" fitness marker. */
@Composable
fun HrPaceTrendCard(st: AnalysisState, onOpenSwim: (Long) -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val today = st.today
    val pts = remember(st) { HrPace.points(st.swims).filter { it.epochDay > today - 120 } }
    if (pts.size < 3) {
        GlassCard(modifier) {
            CardHeader("Heart rate vs pace", Icons.Rounded.Favorite, Accent.Heart)
            Muted("Appears after 3+ pool swims with heart rate.")
        }
        return
    }
    val hr = st.markerHr
    val now = HrPace.paceAtHr(pts, today, hr)
    val before = HrPace.paceAtHr(pts, today - 28, hr)
    val fit = Fit.of(pts.filter { it.epochDay > today - 28 }.map { it.pace to it.hr })?.takeIf { it.b < 0 }
    val oldest = pts.minOf { it.epochDay }
    GlassCard(modifier) {
        CardHeader("Heart rate vs pace", Icons.Rounded.Favorite, Accent.Heart, trailing = "${pts.size} swims")
        ScatterChart(
            pts.map { pt ->
                val age = ((pt.epochDay - oldest).toFloat() / (today - oldest).coerceAtLeast(1)).coerceIn(0f, 1f)
                ScatterPoint(pt.pace, pt.hr, lerp(p.textDim.copy(alpha = 0.35f), Accent.Heart, age), pt.swimId, highlight = pt.epochDay > today - 7)
            },
            fit = fit, xLabel = { fmtPace(it) }, xTitle = "avg pace /100m", yTitle = "avg bpm",
            marker = now?.let { it to hr }, onTap = onOpenSwim,
        )
        if (now != null) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(fmtPace(now), style = MaterialTheme.typography.headlineMedium, color = p.text)
                Text("  /100m at ${hr.roundToInt()} bpm", style = MaterialTheme.typography.labelLarge, color = p.textDim, modifier = Modifier.padding(bottom = 6.dp))
            }
            if (before != null) {
                val pct = (before - now) / before * 100
                Text(
                    "4 weeks ago: ${fmtPace(before)} → ${if (pct >= 0) "%.1f %% fitter".format(pct) else "%.1f %% slower".format(abs(pct))}",
                    style = MaterialTheme.typography.bodyMedium, color = if (pct >= 0) Accent.Time else Accent.Calories,
                )
            }
            val trend = remember(st) { HrPace.trend(pts, today, hr) }
            if (trend.size >= 2) MultiLineChart(listOf(Series(trend.map { it.second }, Accent.Gold, "pace at ${hr.roundToInt()} bpm")),
                height = 90.dp, invertY = true, startLabel = trend.first().first.fmtDay(), endLabel = "now")
        } else Muted("Swim at a few different efforts to estimate your pace at ${hr.roundToInt()} bpm.")
        Muted("Each dot is a swim (brighter = recent, ringed = this week). The gold line is your last 28 days: further right at the same HR = fitter. Tap a dot to open it.")
    }
}

private val explorerMetrics = listOf(Metric.DISTANCE, Metric.MOVING_PACE, Metric.AVG_HR, Metric.EF, Metric.SWOLF, Metric.LOAD, Metric.DECOUPLING, Metric.CV)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MetricExplorerCard(st: AnalysisState, onOpenSwim: (Long) -> Unit, modifier: Modifier = Modifier) {
    if (st.swims.size < 2) return
    val p = LocalPalette.current
    var idx by rememberSaveable { mutableStateOf(0) }
    val available = explorerMetrics.filter { m -> st.swims.count { s -> (if (m == Metric.MOVING_PACE) s.pace else m.get(s)) != null } >= 2 }
    if (available.isEmpty()) return
    val metric = available[idx.coerceIn(0, available.lastIndex)]
    val recent = st.swims.takeLast(30)
    val read: (com.hamuk.swimpro.analysis.SwimMetrics) -> Double? = if (metric == Metric.MOVING_PACE) { s -> s.pace } else metric.get
    val values = recent.map(read)
    val band = Baselines.of(metric, st.swims, st.today + 1)?.let { it.low to it.high }
    val latest = values.lastOrNull { it != null }
    GlassCard(modifier) {
        CardHeader("Metric explorer", Icons.Rounded.QueryStats, Accent.Pace, trailing = "last ${recent.size} swims")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            available.forEachIndexed { i, m -> GlassChip(m.label, metric == m, { idx = i }, Accent.Pace) }
        }
        if (latest != null) Row(verticalAlignment = Alignment.Bottom) {
            Text(metric.format(latest), style = MaterialTheme.typography.headlineMedium, color = p.text)
            Text("  ${metric.unit} latest", style = MaterialTheme.typography.labelLarge, color = p.textDim, modifier = Modifier.padding(bottom = 6.dp))
        }
        MultiLineChart(
            listOf(Series(values, Accent.Pace, "")), band = band, invertY = metric.higherIsBetter == false,
            startLabel = recent.first().epochDay.fmtDay(), endLabel = recent.last().epochDay.fmtDay(),
            onTap = { i -> recent.getOrNull(i)?.let { onOpenSwim(it.swimId) } },
        )
        Muted(
            (if (band != null) "Shaded = your normal range over the last 28 days. " else "") +
                (if (metric.higherIsBetter == false) "Lower is better, so the chart is flipped: up = better. " else "") + "Tap the chart to open a swim.",
        )
    }
}

@Composable
fun RecordsCard(st: AnalysisState, onOpenSwim: (Long) -> Unit, modifier: Modifier = Modifier) {
    val pool = st.swims.filter { !it.openWater }
    if (pool.isEmpty()) return
    val p = LocalPalette.current
    data class Rec(val label: String, val value: String, val day: Long, val id: Long?)
    val recs = remember(st) {
        buildList {
            pool.maxByOrNull { it.distanceM }?.let { add(Rec("Longest swim", "%,.0f m".format(it.distanceM), it.epochDay, it.swimId)) }
            pool.filter { it.distanceM >= 200 }.filter { it.pace != null }.minByOrNull { it.pace!! }?.let { add(Rec("Fastest pace (200 m+)", fmtPace(it.pace!!) + "/100", it.epochDay, it.swimId)) }
            pool.filter { it.swolf != null }.minByOrNull { it.swolf!! }?.let { add(Rec("Best SWOLF", "%.0f".format(it.swolf), it.epochDay, it.swimId)) }
            pool.filter { it.ef != null }.maxByOrNull { it.ef!! }?.let { add(Rec("Best efficiency", "%.2f".format(it.ef), it.epochDay, it.swimId)) }
            pool.maxByOrNull { it.longestContinuousM ?: 0.0 }?.takeIf { (it.longestContinuousM ?: 0.0) > 0 }
                ?.let { add(Rec("Longest non-stop", "%,.0f m".format(it.longestContinuousM), it.epochDay, it.swimId)) }
            st.swims.groupBy { mondayOf(LocalDate.ofEpochDay(it.epochDay)) }.maxByOrNull { (_, l) -> l.sumOf { it.distanceM } }
                ?.let { (m, l) -> add(Rec("Biggest week", "%,.0f m".format(l.sumOf { it.distanceM }), m.toEpochDay(), null)) }
        }
    }
    GlassCard(modifier) {
        CardHeader("Records", Icons.Rounded.EmojiEvents, Accent.Gold)
        recs.forEach { r ->
            Row(
                Modifier.fillMaxWidth().then(if (r.id != null) Modifier.pressable { onOpenSwim(r.id) } else Modifier).padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(r.label, style = MaterialTheme.typography.bodyMedium, color = p.text)
                    Muted(r.day.fmtDay() + " " + LocalDate.ofEpochDay(r.day).year)
                }
                Text(r.value, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = Accent.Gold)
            }
        }
    }
}

