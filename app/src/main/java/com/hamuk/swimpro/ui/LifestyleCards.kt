package com.hamuk.swimpro.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import com.hamuk.swimpro.analysis.Factor
import com.hamuk.swimpro.analysis.Lifestyle
import com.hamuk.swimpro.analysis.Outcome
import com.hamuk.swimpro.container
import com.hamuk.swimpro.health.WellnessReader
import kotlinx.coroutines.launch

/** Asks for the optional sleep / steps / HRV / resting-HR permissions when any are missing. */
@Composable
fun WellnessPermissionPrompt(modifier: Modifier = Modifier) {
    val app = LocalContext.current.container
    var missing by remember { mutableStateOf<Set<String>>(emptySet()) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh) { missing = runCatching { app.lifestyle.reader.missingPermissions() }.getOrElse { emptySet() } }
    val launcher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        refresh++
        app.scope.launch { runCatching { app.sync() } }
    }
    if (missing.isEmpty()) return
    GlassButton("Allow sleep, steps & HRV from Health Connect", { launcher.launch(app.health.allPermissions) }, modifier.fillMaxWidth(),
        icon = Icons.Rounded.Bedtime, tint = Accent.Pace)
}

@Composable
fun ReadinessCard(modifier: Modifier = Modifier) {
    val app = LocalContext.current.container
    val p = LocalPalette.current
    val r by app.lifestyle.readiness.collectAsState()
    GlassCard(modifier) {
        val ready = r
        if (ready == null) {
            CardHeader("Readiness", Icons.Rounded.Bedtime, Accent.Pace)
            Muted("Needs last night's sleep or this morning's resting HR from your watch.")
            WellnessPermissionPrompt()
            return@GlassCard
        }
        val color = when {
            ready.score >= 75 -> Accent.Time
            ready.score >= 55 -> Accent.Swim
            ready.score >= 45 -> Accent.Calories
            else -> Accent.Heart
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center) {
                ActivityRings(listOf(Ring(ready.score / 100f, color)), size = 92.dp, stroke = 11.dp)
                Text("${ready.score}", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), color = p.text)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("READINESS", style = MaterialTheme.typography.labelMedium, color = color)
                Text(ready.label, style = MaterialTheme.typography.titleSmall, color = p.text)
                ready.parts.forEach { (k, v) -> Text("$k · $v", style = MaterialTheme.typography.labelMedium, color = p.textDim) }
            }
        }
        WellnessPermissionPrompt()
    }
}

/** Sleep, activity and recovery context before one swim, vs your normal. */
@Composable
fun BeforeSwimCard(swimId: Long, modifier: Modifier = Modifier) {
    val app = LocalContext.current.container
    val st by app.analysis.state.collectAsState()
    val days by app.lifestyle.days.collectAsState()
    val m = st.byId(swimId) ?: return
    val c = remember(m, days) { Lifestyle.context(m, days) }
    val any = listOf(c.sleepMin, c.stepsPrevDay, c.restingHr, c.hrvMs, c.exerciseMinPrevDay).any { it != null }
    GlassCard(modifier) {
        CardHeader("Before this swim", Icons.Rounded.Bedtime, Accent.Pace)
        if (!any) { Muted("No sleep, steps or recovery data for this day yet."); WellnessPermissionPrompt(); return@GlassCard }
        fun dev(v: Double?, unit: String, digits: Int = 0) = v?.let { "  (" + (if (it >= 0) "+" else "−") + "%.${digits}f".format(kotlin.math.abs(it)) + "$unit vs normal)" } ?: ""
        c.sleepMin?.let { KeyValueRow("Sleep", "%.1f h".format(it / 60.0) + dev(c.sleepVsNormalMin?.div(60), " h", 1)) }
        c.deepMin?.let { KeyValueRow("Deep sleep", "$it min") }
        c.restingHr?.let { KeyValueRow("Resting HR", "$it bpm" + dev(c.restingHrVsNormal, " bpm")) }
        c.hrvMs?.let { KeyValueRow("HRV", "%.0f ms".format(it) + dev(c.hrvVsNormalPct, " %")) }
        c.stepsPrevDay?.let { KeyValueRow("Steps the day before", "%,d".format(it)) }
        c.activeKcalPrevDay?.let { KeyValueRow("Active kcal the day before", "%,.0f".format(it)) }
        c.exerciseMinPrevDay?.let { KeyValueRow("Other exercise the day before", "$it min") }
    }
}

/** "What affects your swimming": factor vs outcome scatter, thirds comparison and verdict. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LifestyleImpactCard(onOpenSwim: (Long) -> Unit, modifier: Modifier = Modifier) {
    val app = LocalContext.current.container
    val p = LocalPalette.current
    val st by app.analysis.state.collectAsState()
    val days by app.lifestyle.days.collectAsState()
    var fIdx by rememberSaveable { mutableIntStateOf(0) }
    var oIdx by rememberSaveable { mutableIntStateOf(0) }
    val contexts = remember(st, days) { app.lifestyle.contexts(st) }
    val outcomes = remember(st) { app.lifestyle.outcomes(st) }
    val outcomesShown = listOf(Outcome.PACE, Outcome.EFFICIENCY, Outcome.HR_COST, Outcome.RPE)
    val factor = Factor.entries[fIdx]
    val outcome = outcomesShown[oIdx]
    val impact = remember(contexts, outcomes, factor, outcome) { Lifestyle.impact(factor, outcome, contexts, outcomes) }
    val findings = remember(contexts, outcomes) { Lifestyle.findings(contexts, outcomes) }

    GlassCard(modifier) {
        CardHeader("What affects your swimming", Icons.Rounded.Psychology, Accent.Pace)
        if (findings.isNotEmpty()) {
            Text("Strongest links so far", style = MaterialTheme.typography.labelLarge, color = Accent.Time)
            findings.take(2).forEach { Body("• " + it.verdict.substringBefore(" Most")) }
        }
        Text("Factor", style = MaterialTheme.typography.labelLarge, color = p.textDim)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Factor.entries.forEachIndexed { i, f -> GlassChip(f.short.replaceFirstChar { it.uppercase() }, i == fIdx, { fIdx = i }, Accent.Pace) }
        }
        Text("Swim outcome", style = MaterialTheme.typography.labelLarge, color = p.textDim)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            outcomesShown.forEachIndexed { i, o -> GlassChip(o.label, i == oIdx, { oIdx = i }, Accent.Swim) }
        }
        if (impact.n >= 3) {
            val idsByIndex = contexts.filter { c -> factor.get(c) != null && outcomes.firstOrNull { it.swimId == c.swimId }?.let(outcome.get) != null }.map { it.swimId }
            ScatterChart(
                impact.points.mapIndexed { i, (x, y) ->
                    val good = if (outcome.higherIsBetter) y > 0 else y < 0
                    ScatterPoint(x, y, if (good) Accent.Time else Accent.Calories, idsByIndex.getOrElse(i) { -1L })
                },
                invertX = false, xLabel = factor.format, yLabel = outcome.format, xTitle = factor.label, yTitle = outcome.label,
                fit = com.hamuk.swimpro.analysis.Fit.of(impact.points), onTap = onOpenSwim,
            )
        }
        if (impact.buckets.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            impact.buckets.forEachIndexed { i, b ->
                val good = if (outcome.higherIsBetter) b.outcomeMean > 0 else b.outcomeMean < 0
                MiniMetric(b.label.substringBefore(" ("), outcome.format(b.outcomeMean),
                    lerp(if (good) Accent.Time else Accent.Calories, Accent.Swim, if (i == 1) 0.6f else 0f), Modifier.weight(1f))
            }
        }
        Body(impact.verdict, color = if (impact.confident) p.text else p.textDim)
        Muted("Each swim is compared with your own 28-day normal, then matched with the night's sleep, the previous day's steps, calories and workouts, and morning resting HR / HRV. Findings appear once there are ${Lifestyle.MIN_SWIMS}+ swims and a correlation of at least ${Lifestyle.MIN_R}. Correlation isn't proof, but it's a strong hint.")
        WellnessPermissionPrompt()
    }
}

@Composable
fun DataSourcesCard(modifier: Modifier = Modifier) {
    val app = LocalContext.current.container
    val p = LocalPalette.current
    var sources by remember { mutableStateOf<List<WellnessReader.Source>?>(null) }
    LaunchedEffect(Unit) { sources = runCatching { app.lifestyle.reader.sources() }.getOrElse { emptyList() } }
    GlassCard(modifier) {
        CardHeader("Data sources", Icons.Rounded.Hub, Accent.Swolf)
        Muted("Everything comes through Health Connect, so any app that writes there is used automatically: Samsung Health today, Google Fit, Fitbit or a Pixel Watch later. If two apps record the same swim, the one with more detail is kept.")
        val s = sources
        when {
            s == null -> Muted("Checking…")
            s.isEmpty() -> Muted("No apps have shared data yet.")
            else -> s.forEach { src ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Rounded.Science, Accent.Swolf, 28.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(src.name, style = MaterialTheme.typography.titleSmall, color = p.text)
                        Muted(src.provides.sorted().joinToString(" · "))
                    }
                }
            }
        }
        WellnessPermissionPrompt()
    }
}
