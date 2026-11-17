package com.hamuk.swimpro.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Pool
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hamuk.swimpro.coach.PlanEngine
import com.hamuk.swimpro.coach.Workout
import com.hamuk.swimpro.coach.fmtDuration
import com.hamuk.swimpro.coach.fmtPace
import com.hamuk.swimpro.coach.localDate
import com.hamuk.swimpro.coach.mondayOf
import com.hamuk.swimpro.container
import com.hamuk.swimpro.data.Profile
import com.hamuk.swimpro.data.WorkoutStatus
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

@Composable
fun HomeScreen(
    onOpenSwim: (Long) -> Unit,
    onOpenWorkout: (Long) -> Unit,
    onSettings: () -> Unit,
    onChat: () -> Unit,
    onSetGoal: () -> Unit,
    onLogTest: () -> Unit,
    onAddSwim: () -> Unit,
    onCompare: (Long, Long) -> Unit,
    onGoalNav: () -> Unit,
) {
    val app = LocalContext.current.container
    val p = LocalPalette.current
    val st by app.analysis.state.collectAsState()
    val swims by app.swims.swims.collectAsState(initial = emptyList())
    val profile by app.coach.profile.collectAsState(initial = null)
    val goal by app.coach.goal.collectAsState(initial = null)
    val workouts by remember(goal?.id) { goal?.let { app.coach.workouts(it.id) } ?: flowOf(emptyList()) }.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var syncing by remember { mutableStateOf(false) }

    val today = LocalDate.now()
    val monday = mondayOf(today)
    val weekSwims = swims.filter { !it.localDate().isBefore(monday) }
    val weekWorkouts = workouts.filter { it.epochDay in monday.toEpochDay()..monday.toEpochDay() + 6 }
    val prof = profile ?: Profile()
    val plannedM = weekWorkouts.sumOf { it.totalM }.takeIf { it > 0 }
        ?: (prof.sessionsPerWeek * prof.minutesPerSession * PlanEngine.metresPerMinute(prof.level))
    val plannedSessions = weekWorkouts.size.takeIf { it > 0 } ?: prof.sessionsPerWeek
    val plannedMin = goal?.weeklyMinutes ?: (prof.sessionsPerWeek * prof.minutesPerSession)
    val doneM = weekSwims.sumOf { it.distanceM }
    val doneMin = weekSwims.sumOf { it.activeSec } / 60.0

    val hour = LocalTime.now().hour
    val greeting = when (hour) { in 5..11 -> "Good morning"; in 12..17 -> "Good afternoon"; in 18..22 -> "Good evening"; else -> "Good night" }

    Page {
        LargeTitle("Hamuk Swims", subtitle = "$greeting · ${today.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }}") {
            SyncButton(syncing) {
                if (!syncing) scope.launch { syncing = true; runCatching { app.sync() }; syncing = false }
            }
            GlassIconButton(Icons.Rounded.Settings, "Settings", onSettings)
        }

        UpdateCard(Modifier.appear(1))

        ReadinessCard(Modifier.appear(1))

        // Hero: weekly rings
        GlassCard(Modifier.appear(1), strong = true) {
            Text("This week", style = MaterialTheme.typography.titleMedium, color = p.text)
            Row(verticalAlignment = Alignment.CenterVertically) {
                ActivityRings(
                    listOf(
                        Ring((doneM / plannedM).toFloat(), Accent.Swim),
                        Ring(weekSwims.size / plannedSessions.toFloat(), Accent.Time),
                        Ring((doneMin / plannedMin).toFloat(), Accent.Heart),
                    ),
                    size = 140.dp, stroke = 15.dp,
                )
                Spacer(Modifier.width(18.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    RingLegend("Distance", Accent.Swim, doneM, " / $plannedM m") { "%,.0f".format(it) }
                    RingLegend("Sessions", Accent.Time, weekSwims.size.toDouble(), " / $plannedSessions") { "%.0f".format(it) }
                    RingLegend("Minutes", Accent.Heart, doneMin, " / $plannedMin") { "%.0f".format(it) }
                }
            }
        }

        // Today's session
        val todays = workouts.firstOrNull { it.epochDay == today.toEpochDay() }
        val next = todays ?: workouts.firstOrNull { it.epochDay > today.toEpochDay() && it.status == WorkoutStatus.PLANNED }
        if (goal == null) {
            GlassCard(Modifier.appear(2), onClick = onSetGoal) {
                CardHeader("Set your goal", Icons.Rounded.EmojiEvents, Accent.Gold)
                Body("Swim further, swim faster, learn a stroke or get fitter. Your coach builds the plan and adapts it after every swim.")
                PrimaryButton("Set a goal", onSetGoal)
            }
        } else if (next != null) {
            val w = remember(next.json) { runCatching { Workout.fromJson(next.json) }.getOrNull() }
            GlassCard(Modifier.appear(2), onClick = { onOpenWorkout(next.id) }) {
                CardHeader(
                    if (next == todays) "Today's session" else "Next session",
                    Icons.Rounded.Waves, Accent.Swim,
                    trailing = if (next == todays) null else com.hamuk.swimpro.coach.CoachRepository.run { next.epochDay.fmtDay() },
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(next.title, style = MaterialTheme.typography.headlineSmall, color = p.text, modifier = Modifier.weight(1f))
                    Text("${next.totalM} m", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = Accent.Swim)
                }
                w?.let { Muted("Focus: ${it.focus} · ${it.blocks.size} blocks") }
                when (next.status) {
                    WorkoutStatus.DONE -> Text("Completed ✓", color = Accent.Time, style = MaterialTheme.typography.labelLarge)
                    else -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Open workout", color = Accent.Swim, style = MaterialTheme.typography.labelLarge)
                        Icon(Icons.Rounded.ChevronRight, null, tint = Accent.Swim, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }

        // Latest swim
        val last = swims.firstOrNull()
        if (last != null) {
            SectionLabel("Latest swim", Modifier.appear(3))
            GlassCard(Modifier.appear(3), onClick = { onOpenSwim(last.id) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Rounded.Pool, Accent.Swim, 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(fmtDateTime(last.startMs), style = MaterialTheme.typography.labelLarge, color = p.textDim)
                        Row(verticalAlignment = Alignment.Bottom) {
                            CountUp(last.distanceM, { "%,.0f".format(it) }, MaterialTheme.typography.headlineLarge, p.text)
                            Text(" m", style = MaterialTheme.typography.titleMedium, color = p.textDim)
                        }
                    }
                    Icon(Icons.Rounded.ChevronRight, null, tint = p.textDim)
                }
                MetricGrid(listOf(
                    { m -> MetricTile(Icons.Rounded.Timer, Accent.Time, "Time", fmtDuration(last.activeSec.toDouble()), modifier = m) },
                    { m -> MetricTile(Icons.Rounded.Speed, Accent.Pace, "Pace", last.pacePer100?.let { fmtPace(it) } ?: "–", "/100m", m) },
                    { m -> MetricTile(Icons.Rounded.Favorite, Accent.Heart, "Avg HR", last.avgHr?.toString() ?: "–", "bpm", m) },
                    { m -> MetricTile(Icons.Rounded.LocalFireDepartment, Accent.Calories, "Calories", (last.activeKcal ?: last.totalKcal)?.toInt()?.toString() ?: "–", "kcal", m) },
                ))
            }
        } else {
            GlassCard(Modifier.appear(3)) {
                CardHeader("No swims yet", Icons.Rounded.Pool, Accent.Swim)
                Body("Record a pool swim on your Galaxy Watch. It appears here automatically after Samsung Health syncs.")
            }
        }

        rememberGoalNav()?.let { GoalNavCard(it, onGoalNav, Modifier.appear(3)) }
        SinceLastSwimCard(st, onCompare, Modifier.appear(4))

        // Quick actions
        SectionLabel("Quick actions", Modifier.appear(4))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.appear(4)) {
            item { GlassButton("Ask coach", onChat, icon = Icons.Rounded.AutoAwesome, tint = Accent.Pace) }
            item { GlassButton("Log test", onLogTest, icon = Icons.Rounded.Science, tint = Accent.Swolf) }
            item { GlassButton("Add swim", onAddSwim, icon = Icons.Rounded.AddCircle, tint = Accent.Swim) }
        }

        // All-time tiles
        val total = swims.sumOf { it.distanceM }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.appear(5)) {
            MetricTile(Icons.Rounded.Waves, Accent.Swim, "All time", "%.1f".format(total / 1000), "km", Modifier.weight(1f))
            MetricTile(Icons.Rounded.EmojiEvents, Accent.Gold, "Swims", swims.size.toString(), "", Modifier.weight(1f))
        }
    }
}

@Composable
private fun RingLegend(label: String, color: Color, value: Double, suffix: String, fmt: (Double) -> String) {
    val p = LocalPalette.current
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
        Row(verticalAlignment = Alignment.Bottom) {
            CountUp(value, fmt, MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), p.text)
            Text(suffix, style = MaterialTheme.typography.labelMedium, color = p.textDim)
        }
    }
}

/** Glass sync button whose icon spins while a sync runs. */
@Composable
fun SyncButton(syncing: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "sync")
    val spin by t.animateFloat(
        0f, 360f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(900, easing = androidx.compose.animation.core.LinearEasing)),
        label = "spin",
    )
    val angle = if (syncing) spin else 0f
    androidx.compose.foundation.layout.Box(
        Modifier.size(44.dp).pressable(onClick).glass(androidx.compose.foundation.shape.CircleShape, strong = true),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Sync, "Sync", tint = if (syncing) Accent.Swim else p.text, modifier = Modifier.size(21.dp).graphicsLayer { rotationZ = -angle })
    }
}

/** Headline deltas of the latest swim vs the one before, plus the top insight. */
@Composable
fun SinceLastSwimCard(st: com.hamuk.swimpro.analysis.AnalysisState, onCompare: (Long, Long) -> Unit, modifier: Modifier = Modifier) {
    val last = st.swims.lastOrNull() ?: return
    val prev = st.previousOf(last) ?: return
    val insight = remember(st) { st.insightsFor(last, 1).firstOrNull() }
    val deltas = com.hamuk.swimpro.analysis.Baselines.compare(listOf(last), listOf(prev),
        listOf(com.hamuk.swimpro.analysis.Metric.MOVING_PACE, com.hamuk.swimpro.analysis.Metric.AVG_PACE,
            com.hamuk.swimpro.analysis.Metric.AVG_HR, com.hamuk.swimpro.analysis.Metric.EF, com.hamuk.swimpro.analysis.Metric.DISTANCE))
        .filter { it.a != null && it.b != null }.let { l -> if (l.any { it.metric == com.hamuk.swimpro.analysis.Metric.MOVING_PACE }) l.filter { it.metric != com.hamuk.swimpro.analysis.Metric.AVG_PACE } else l }
        .take(3)
    GlassCard(modifier, onClick = { onCompare(last.swimId, prev.swimId) }) {
        CardHeader("Since your last swim", Icons.Rounded.Insights, Accent.Pace, trailing = "vs ${com.hamuk.swimpro.coach.CoachRepository.run { prev.epochDay.fmtDay() }}")
        deltas.forEach { DeltaRow(it, showB = false) }
        insight?.let { Body(it.text) }
    }
}
