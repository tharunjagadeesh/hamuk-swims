package com.hamuk.swimpro.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CompareArrows
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hamuk.swimpro.analysis.AnalysisState
import com.hamuk.swimpro.analysis.Baselines
import com.hamuk.swimpro.analysis.SwimMetrics
import com.hamuk.swimpro.coach.CoachRepository.Companion.fmtDay
import com.hamuk.swimpro.coach.fmtPace
import com.hamuk.swimpro.coach.mondayOf
import com.hamuk.swimpro.container
import com.hamuk.swimpro.data.HrSample
import kotlinx.coroutines.flow.first
import java.time.LocalDate

private val ColorA = Accent.Swim
private val ColorB = Accent.Calories

enum class CompareMode(val label: String) { SWIMS("Swims"), DAYS("Days"), WEEKS("Weeks") }

private data class Option(val key: Long, val title: String, val subtitle: String)

private fun options(st: AnalysisState, mode: CompareMode): List<Option> = when (mode) {
    CompareMode.SWIMS -> st.swims.reversed().map {
        Option(it.swimId, it.epochDay.fmtDay() + " " + LocalDate.ofEpochDay(it.epochDay).year, "%,.0f m · %s".format(it.distanceM, it.pace?.let(::fmtPace)?.plus("/100") ?: ""))
    }
    CompareMode.DAYS -> st.swimDays().map { d ->
        val l = st.onDay(d)
        Option(d, d.fmtDay(), "${l.size} swim${if (l.size > 1) "s" else ""} · %,.0f m".format(l.sumOf { it.distanceM }))
    }
    CompareMode.WEEKS -> {
        val thisMonday = mondayOf(LocalDate.now())
        val mondays = (st.swims.map { mondayOf(LocalDate.ofEpochDay(it.epochDay)) } + thisMonday + thisMonday.minusWeeks(1)).distinct().sortedDescending()
        mondays.map { m ->
            val l = st.inWeek(m)
            Option(m.toEpochDay(), "Week of ${m.toEpochDay().fmtDay()}", "${l.size} swims · %,.0f m".format(l.sumOf { it.distanceM }))
        }
    }
}

private fun select(st: AnalysisState, mode: CompareMode, key: Long): List<SwimMetrics> = when (mode) {
    CompareMode.SWIMS -> listOfNotNull(st.byId(key))
    CompareMode.DAYS -> st.onDay(key)
    CompareMode.WEEKS -> st.inWeek(LocalDate.ofEpochDay(key))
}

/** Resamples a series onto [n] evenly spaced points (by position), for overlaying unequal swims. */
fun resample(values: List<Double>, n: Int = 40): List<Double?> {
    if (values.size < 2) return List(n) { null }
    return List(n) { i ->
        val t = i / (n - 1.0) * (values.size - 1)
        val lo = t.toInt().coerceAtMost(values.size - 2)
        val f = t - lo
        values[lo] * (1 - f) + values[lo + 1] * f
    }
}

@Composable
fun CompareScreen(initialMode: CompareMode, initialA: Long, initialB: Long, onBack: () -> Unit, onOpenSwim: (Long) -> Unit) {
    val app = LocalContext.current.container
    val p = LocalPalette.current
    val st by app.analysis.state.collectAsState()
    var mode by rememberSaveable { mutableStateOf(initialMode) }
    var a by rememberSaveable { mutableLongStateOf(initialA) }
    var b by rememberSaveable { mutableLongStateOf(initialB) }
    var picking by remember { mutableStateOf<Char?>(null) }

    val opts = remember(st, mode) { options(st, mode) }
    // Fill in sensible defaults: newest vs the one before it.
    LaunchedEffect(opts, mode) {
        if (opts.none { it.key == a }) a = opts.getOrNull(0)?.key ?: -1
        if (opts.none { it.key == b } || b == a) b = opts.firstOrNull { it.key != a && it.key < a }?.key ?: opts.getOrNull(1)?.key ?: -1
    }

    Page {
        DetailHeader("Compare", onBack)
        GlassSegmented(CompareMode.entries.map { it.label }, mode.ordinal, { mode = CompareMode.entries[it] }, Modifier.appear(0))
        if (!st.ready) { Muted("Analysing your swims…"); return@Page }
        if (opts.size < 2) { GlassCard { Body("You need at least two ${mode.label.lowercase()} with swims to compare.") }; return@Page }

        val la = select(st, mode, a)
        val lb = select(st, mode, b)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.appear(1)) {
            Selector("A", opts.firstOrNull { it.key == a }, la, ColorA, Modifier.weight(1f)) { picking = 'A' }
            Selector("B", opts.firstOrNull { it.key == b }, lb, ColorB, Modifier.weight(1f)) { picking = 'B' }
        }

        GlassCard(Modifier.appear(2)) {
            CardHeader("Side by side", Icons.Rounded.CompareArrows, Accent.Swim, trailing = "A vs B")
            DeltaHeader("A", "B")
            Baselines.compare(la, lb).forEach { DeltaRow(it) }
            if (mode != CompareMode.SWIMS) Muted("Sums for totals; distance- or time-weighted averages for pace, HR and the rest.")
        }

        if (mode == CompareMode.SWIMS && la.isNotEmpty() && lb.isNotEmpty()) {
            val ma = la.first(); val mb = lb.first()
            if (ma.laps.size >= 2 && mb.laps.size >= 2) GlassCard(Modifier.appear(3)) {
                CardHeader("Pace through the swim", Icons.Rounded.Speed, Accent.Pace, trailing = "higher = faster")
                MultiLineChart(
                    listOf(Series(resample(ma.laps.map { it.pacePer100 }), ColorA, "A"), Series(resample(mb.laps.map { it.pacePer100 }), ColorB, "B")),
                    invertY = true, startLabel = "start", endLabel = "finish",
                )
                Muted("Both swims stretched to the same width so you can compare how pace evolved, start to finish.")
            }
            var hrA by remember(ma.swimId) { mutableStateOf<List<HrSample>>(emptyList()) }
            var hrB by remember(mb.swimId) { mutableStateOf<List<HrSample>>(emptyList()) }
            LaunchedEffect(ma.swimId, mb.swimId) {
                hrA = app.swims.heartRate(ma.swimId).first()
                hrB = app.swims.heartRate(mb.swimId).first()
            }
            if (hrA.size >= 2 && hrB.size >= 2) GlassCard(Modifier.appear(4)) {
                CardHeader("Heart rate through the swim", Icons.Rounded.Favorite, Accent.Heart)
                MultiLineChart(
                    listOf(Series(resample(hrA.map { it.bpm.toDouble() }, 60), ColorA, "A"), Series(resample(hrB.map { it.bpm.toDouble() }, 60), ColorB, "B")),
                    startLabel = "start", endLabel = "finish",
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassButton("Open A", { onOpenSwim(ma.swimId) }, Modifier.weight(1f), tint = ColorA)
                GlassButton("Open B", { onOpenSwim(mb.swimId) }, Modifier.weight(1f), tint = ColorB)
            }
        }

        if (mode == CompareMode.WEEKS) GlassCard(Modifier.appear(3)) {
            CardHeader("Day by day", Icons.Rounded.Waves, Accent.Swim, trailing = "metres")
            fun daily(monday: Long) = (0..6).map { d -> st.onDay(monday + d).sumOf { it.distanceM } }
            MultiLineChart(listOf(Series(daily(a), ColorA, "A"), Series(daily(b), ColorB, "B")), startLabel = "Mon", endLabel = "Sun")
        }
    }

    picking?.let { which ->
        AlertDialog(
            onDismissRequest = { picking = null },
            title = { Text("Choose ${mode.label.lowercase().removeSuffix("s")} $which") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(opts, key = { it.key }) { o ->
                        Column(
                            Modifier.fillMaxWidth().pressable {
                                if (which == 'A') a = o.key else b = o.key
                                picking = null
                            }.padding(vertical = 10.dp),
                        ) {
                            val sel = (which == 'A' && o.key == a) || (which == 'B' && o.key == b)
                            Text(o.title, style = MaterialTheme.typography.titleSmall, color = if (sel) (if (which == 'A') ColorA else ColorB) else p.text)
                            Muted(o.subtitle)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = null }) { Text("Close") } },
        )
    }
}

@Composable
private fun Selector(tag: String, opt: Option?, metrics: List<SwimMetrics>, color: Color, modifier: Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    Column(
        modifier.pressable(onClick).glass(RoundedCornerShape(22.dp), strong = true).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(tag, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = color)
        Text(opt?.title ?: "Choose…", style = MaterialTheme.typography.titleSmall, color = p.text, maxLines = 1)
        Text("%,.0f m".format(metrics.sumOf { it.distanceM }), style = MaterialTheme.typography.headlineSmall, color = p.text)
        Muted("tap to change")
    }
}
