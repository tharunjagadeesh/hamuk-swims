package com.hamuk.swimpro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hamuk.swimpro.coach.fmtDuration
import com.hamuk.swimpro.coach.fmtPace
import com.hamuk.swimpro.coach.localDate
import com.hamuk.swimpro.coach.mondayOf
import com.hamuk.swimpro.container
import com.hamuk.swimpro.data.Swim
import com.hamuk.swimpro.data.SwimLap
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun ProgressScreen(onOpenSwim: (Long) -> Unit, onCompareWeeks: (Long, Long) -> Unit) {
    val app = LocalContext.current.container
    val p = LocalPalette.current
    val swims by app.swims.swims.collectAsState(initial = emptyList())
    val tests by app.coach.tests.collectAsState(initial = emptyList())
    val st by app.analysis.state.collectAsState()
    val goal by app.coach.goal.collectAsState(initial = null)
    val planned by remember(goal?.id) { goal?.let { app.coach.workouts(it.id) } ?: kotlinx.coroutines.flow.flowOf(emptyList()) }.collectAsState(initial = emptyList())
    var bests by remember { mutableStateOf<Map<Int, Pair<Double, Long>>>(emptyMap()) }
    LaunchedEffect(swims) { bests = personalBests(app.swims.allLaps(), swims) }

    val thisMonday = mondayOf(LocalDate.now())
    val weeks = (7 downTo 0).map { thisMonday.minusWeeks(it.toLong()) }
    val byWeek = swims.groupBy { mondayOf(it.localDate()) }
    val volumes = weeks.map { w -> byWeek[w].orEmpty().sumOf { it.distanceM } }
    val labelFmt = DateTimeFormatter.ofPattern("d/M")

    Page {
        LargeTitle("Progress", subtitle = "Consistency & trends")

        TrainingLoadCard(st, Modifier.appear(1))
        WeekVsWeekCard(st, onCompareWeeks, Modifier.appear(2))
        ConsistencyCalendar(swims, planned, onOpenSwim, Modifier.appear(3))
        ConsistencyHeatmap(swims, modifier = Modifier.appear(4))
        HrPaceTrendCard(st, onOpenSwim, Modifier.appear(5))
        LifestyleImpactCard(onOpenSwim, Modifier.appear(5))
        MetricExplorerCard(st, onOpenSwim, Modifier.appear(6))
        RecordsCard(st, onOpenSwim, Modifier.appear(7))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.appear(1)) {
            MetricTile(Icons.Rounded.Waves, Accent.Swim, "Total", "%.1f".format(swims.sumOf { it.distanceM } / 1000), "km", Modifier.weight(1f))
            MetricTile(Icons.Rounded.LocalFireDepartment, Accent.Calories, "Streak", weekStreak(byWeek.keys, thisMonday).toString(), "wks", Modifier.weight(1f))
        }

        GlassCard(Modifier.appear(2)) {
            CardHeader("Weekly distance", Icons.Rounded.BarChart, Accent.Swim, trailing = "%,.0f m this week".format(volumes.last()))
            BarChart(volumes, weeks.map { it.format(labelFmt) }, Accent.Swim)
        }

        val recent = swims.filter { !it.openWater && it.distanceM >= 100 }.take(20).reversed()
        val paces = recent.mapNotNull { it.pacePer100 }
        GlassCard(Modifier.appear(3)) {
            CardHeader("Average pace", Icons.Rounded.Speed, Accent.Pace, trailing = paces.minOrNull()?.let { "best ${fmtPace(it)}" })
            AreaChart(paces, Accent.Pace, invertY = true, startLabel = "older", endLabel = "newer")
            Muted("Higher is faster. Watch time includes rests at the wall, so this is slower than swimming pace.")
        }

        val hrs = recent.mapNotNull { it.avgHr?.toDouble() }
        if (hrs.size >= 2) GlassCard(Modifier.appear(4)) {
            CardHeader("Heart rate per swim", Icons.Rounded.Favorite, Accent.Heart)
            AreaChart(hrs, Accent.Heart, startLabel = "older", endLabel = "newer")
            Muted("Same pace at a lower heart rate over time = better aerobic fitness.")
        }

        GlassCard(Modifier.appear(5)) {
            val t = tests.sortedBy { it.epochDay }
            CardHeader("Threshold (CSS) pace", Icons.Rounded.Speed, Accent.Calories, trailing = t.lastOrNull()?.let { fmtPace(it.cssPer100) + "/100" })
            if (t.size >= 2) AreaChart(t.map { it.cssPer100 }, Accent.Calories, invertY = true, startLabel = "first test", endLabel = "latest")
            else Muted(if (t.isEmpty()) "Log a test from the Coach tab to start tracking." else "Retest to see the trend.")
        }

        GlassCard(Modifier.appear(6)) {
            CardHeader("Personal bests", Icons.Rounded.EmojiEvents, Accent.Gold, trailing = "back-to-back lengths")
            if (bests.isEmpty()) Muted("Appears once your watch reports individual lengths.")
            bests.toSortedMap().forEach { (d, v) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(Accent.Gold, CircleShape))
                    Spacer(Modifier.width(10.dp))
                    Text("$d m", style = MaterialTheme.typography.titleMedium, color = p.text, modifier = Modifier.weight(1f))
                    Text(fmtDuration(v.first), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = Accent.Gold)
                    Spacer(Modifier.width(12.dp))
                    Muted(fmtDate(v.second))
                }
            }
        }
    }
}

private fun weekStreak(weeksWithSwims: Set<LocalDate>, thisMonday: LocalDate): Int {
    var w = if (thisMonday in weeksWithSwims) thisMonday else thisMonday.minusWeeks(1)
    var n = 0
    while (w in weeksWithSwims) { n++; w = w.minusWeeks(1) }
    return n
}

/** Fastest time over 100/200/400/800/1500 m using back-to-back lengths (≤5 s gap at turns). */
private fun personalBests(laps: List<SwimLap>, swims: List<Swim>): Map<Int, Pair<Double, Long>> {
    val targets = listOf(100, 200, 400, 800, 1500)
    val start = swims.associate { it.id to it.startMs }
    val best = mutableMapOf<Int, Pair<Double, Long>>()
    for ((swimId, list) in laps.groupBy { it.swimId }) {
        val sorted = list.filter { (it.distanceM ?: 0.0) > 0 }.sortedBy { it.startMs }
        for (i in sorted.indices) {
            var dist = 0.0
            for (j in i until sorted.size) {
                if (j > i && sorted[j].startMs - sorted[j - 1].endMs > 5000) break
                dist += sorted[j].distanceM!!
                val d = dist.toInt()
                if (d in targets) {
                    val t = (sorted[j].endMs - sorted[i].startMs) / 1000.0
                    if (best[d] == null || t < best[d]!!.first) best[d] = t to (start[swimId] ?: sorted[i].startMs)
                }
                if (d > targets.last()) break
            }
        }
    }
    return best
}
