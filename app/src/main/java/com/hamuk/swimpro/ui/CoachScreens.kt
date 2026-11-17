package com.hamuk.swimpro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DirectionsRun
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Pool
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hamuk.swimpro.coach.CoachRepository
import com.hamuk.swimpro.coach.CoachRepository.Companion.fmtDay
import com.hamuk.swimpro.coach.CoachRepository.Companion.label
import com.hamuk.swimpro.coach.PlanEngine
import com.hamuk.swimpro.coach.SessionType
import com.hamuk.swimpro.coach.SwimSet
import com.hamuk.swimpro.coach.Workout
import com.hamuk.swimpro.coach.Zones
import com.hamuk.swimpro.coach.describe
import com.hamuk.swimpro.coach.fmtDuration
import com.hamuk.swimpro.coach.fmtPace
import com.hamuk.swimpro.coach.mondayOf
import com.hamuk.swimpro.coach.parseDuration
import com.hamuk.swimpro.container
import com.hamuk.swimpro.data.Goal
import com.hamuk.swimpro.data.GoalType
import com.hamuk.swimpro.data.PlanWeek
import com.hamuk.swimpro.data.PlannedWorkout
import com.hamuk.swimpro.data.Swim
import com.hamuk.swimpro.data.TestResult
import com.hamuk.swimpro.data.TestType
import com.hamuk.swimpro.data.WorkoutStatus
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

fun sessionIcon(type: String): Pair<ImageVector, Color> = when (runCatching { SessionType.valueOf(type) }.getOrNull()) {
    SessionType.ENDURANCE -> Icons.Rounded.Timeline to Accent.Swim
    SessionType.LONG_SWIM -> Icons.Rounded.Waves to Accent.SwimDeep
    SessionType.CSS -> Icons.Rounded.Speed to Accent.Calories
    SessionType.SPEED -> Icons.Rounded.Bolt to Accent.Heart
    SessionType.AEROBIC -> Icons.Rounded.BarChart to Accent.Time
    SessionType.TECHNIQUE -> Icons.Rounded.Tune to Accent.Pace
    SessionType.RECOVERY -> Icons.Rounded.SelfImprovement to Accent.Swolf
    SessionType.TEST -> Icons.Rounded.Science to Color(0xFFB57BFF)
    SessionType.GOAL -> Icons.Rounded.Flag to Accent.Gold
    null -> Icons.Rounded.Pool to Accent.Swim
}

@Composable
fun CoachScreen(
    onSetGoal: () -> Unit,
    onLogTest: () -> Unit,
    onOpenWorkout: (Long) -> Unit,
    onChat: () -> Unit,
    onProfile: () -> Unit,
    onGoalNav: () -> Unit,
) {
    val app = LocalContext.current.container
    val p = LocalPalette.current
    val goal by app.coach.goal.collectAsState(initial = null)
    val nav = rememberGoalNav()
    val profile by app.coach.profile.collectAsState(initial = null)
    val tests by app.coach.tests.collectAsState(initial = emptyList())
    val swims by app.swims.swims.collectAsState(initial = emptyList())
    val weeks by remember(goal?.id) { goal?.let { app.coach.weeks(it.id) } ?: flowOf(emptyList()) }.collectAsState(initial = emptyList())
    val workouts by remember(goal?.id) { goal?.let { app.coach.workouts(it.id) } ?: flowOf(emptyList()) }.collectAsState(initial = emptyList())
    var css by remember { mutableDoubleStateOf(0.0) }
    LaunchedEffect(tests, swims, profile) { css = app.coach.currentCss() }
    val scope = rememberCoroutineScope()
    var confirmEnd by remember { mutableStateOf(false) }

    val today = LocalDate.now()
    val thisMonday = mondayOf(today).toEpochDay()
    val current = weeks.firstOrNull { it.startEpochDay == thisMonday }
    val next = weeks.firstOrNull { it.startEpochDay == thisMonday + 7 }

    Page {
        LargeTitle("Coach", subtitle = current?.let { "Week ${it.programWeek} of ${it.totalWeeks} · ${it.phase}" } ?: "Your plan") {
            GlassIconButton(Icons.Rounded.AutoAwesome, "Chat", onChat, tint = Accent.Pace)
        }

        val g = goal
        if (g == null) {
            GlassCard(Modifier.appear(1), strong = true) {
                CardHeader("What's your goal?", Icons.Rounded.EmojiEvents, Accent.Gold)
                Body("A faster time, a longer non-stop swim, a new stroke or better fitness. The coach builds a week-by-week plan from your pace tests and adapts it as your swims come in.")
                PrimaryButton("Set a goal", onSetGoal, Modifier.fillMaxWidth())
            }
        } else {
            GoalHero(g, current, css, tests, swims, Modifier.appear(1), provenM = nav?.current)
            nav?.let { GoalNavCard(it, onGoalNav, Modifier.appear(1)) }
            if (current != null) {
                WeekStrip(current, workouts, today, Modifier.appear(2))
                WeekCard("This week", current, workouts, today, onOpenWorkout, Modifier.appear(3))
            }
            if (next != null) WeekCard("Next week", next, workouts, today, onOpenWorkout, Modifier.appear(4))
        }

        GlassCard(Modifier.appear(5)) {
            CardHeader("Your paces", Icons.Rounded.Speed, Accent.Calories, trailing = if (tests.isEmpty()) "estimate" else "from test")
            Row(verticalAlignment = Alignment.Bottom) {
                Text(fmtPace(css), style = MaterialTheme.typography.headlineLarge, color = p.text)
                Text("  /100m threshold", style = MaterialTheme.typography.labelLarge, color = p.textDim, modifier = Modifier.padding(bottom = 6.dp))
            }
            ZoneSpectrum()
            Zones.describe(css).forEachIndexed { i, (name, pace) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(Accent.zone.getValue(i + 1), CircleShape))
                    Spacer(Modifier.width(10.dp))
                    Text(name, style = MaterialTheme.typography.bodyMedium, color = p.text, modifier = Modifier.weight(1f))
                    Text(pace, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = p.text)
                }
            }
            tests.firstOrNull()?.let { Muted("Latest: ${it.type.label()} on ${it.epochDay.fmtDay()} — ${CoachRepository.testDescription(it)}") }
                ?: Muted("Do the test session in week 1 and log it for accurate zones.")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassButton("Log test", onLogTest, icon = Icons.Rounded.Science, tint = Accent.Swolf)
                GlassButton("Profile", onProfile, icon = Icons.Rounded.Tune)
            }
        }

        if (g != null) {
            val past = weeks.filter { it.startEpochDay <= thisMonday }.takeLast(8)
            if (past.size >= 2) GlassCard(Modifier.appear(6)) {
                CardHeader("Planned vs swum", Icons.Rounded.Insights, Accent.Swim, trailing = "metres")
                val doneM = past.map { w ->
                    workouts.filter { it.weekId == w.id && it.matchedSwimId != null }
                        .sumOf { pw -> swims.firstOrNull { it.id == pw.matchedSwimId }?.distanceM ?: 0.0 }
                }
                val planned = past.map { w -> workouts.filter { it.weekId == w.id }.sumOf { it.totalM }.toDouble() }
                BarChart(doneM, past.map { "W${it.programWeek}" }, Accent.Swim, targets = planned)
            }
        }

        GlassCard(Modifier.appear(7), onClick = onChat) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Rounded.AutoAwesome, Accent.Pace, 44.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Talk to your coach", style = MaterialTheme.typography.titleMedium, color = p.text)
                    Muted("Questions, swim reviews, plan changes — you approve every change.")
                }
                Icon(Icons.Rounded.ChevronRight, null, tint = p.textDim)
            }
            swims.firstOrNull()?.let { last ->
                GlassButton("Review my last swim", { app.chat.run(app.scope) { reviewSwim(last) }; onChat() },
                    Modifier.fillMaxWidth(), icon = Icons.Rounded.Pool, tint = Accent.Swim)
            }
        }

        if (g != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.appear(8)) {
                GlassButton("New goal", onSetGoal, Modifier.weight(1f))
                GlassButton("End goal", { confirmEnd = true }, Modifier.weight(1f), tint = Accent.Heart)
            }
        }
        Muted("Method: CSS pace zones (Swim Smooth), base/build/taper periodisation with recovery weeks, USMS-style sets, drill progressions from Swim England & Total Immersion.")
    }

    if (confirmEnd) AlertDialog(
        onDismissRequest = { confirmEnd = false },
        title = { Text("End this goal?") },
        text = { Text("The plan is archived. Your swims and tests are kept.") },
        confirmButton = { TextButton(onClick = { confirmEnd = false; scope.launch { app.coach.endGoal() } }) { Text("End goal", color = Accent.Heart) } },
        dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("Cancel") } },
    )
}

@Composable
private fun GoalHero(goal: Goal, week: PlanWeek?, css: Double, tests: List<TestResult>, swims: List<Swim>, modifier: Modifier, provenM: Double? = null) {
    val p = LocalPalette.current
    val planProgress = week?.let { it.programWeek / it.totalWeeks.toFloat() } ?: 0f
    GlassCard(modifier, strong = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center) {
                ActivityRings(listOf(Ring(planProgress, Accent.Gold)), size = 96.dp, stroke = 12.dp)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${week?.programWeek ?: "–"}", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = p.text)
                    Text("of ${week?.totalWeeks ?: "–"}", style = MaterialTheme.typography.labelSmall, color = p.textDim)
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("GOAL", style = MaterialTheme.typography.labelMedium, color = Accent.Gold)
                Text(CoachRepository.goalDescription(goal), style = MaterialTheme.typography.titleMedium, color = p.text)
            }
        }
        when (goal.type) {
            GoalType.TIME -> {
                val d = goal.targetDistanceM ?: 0
                val target = goal.targetTimeSec ?: 0
                val predicted = PlanEngine.predictTimeSec(css, d)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricTile(Icons.Rounded.Flag, Accent.Gold, "Target", fmtDuration(target.toDouble()), modifier = Modifier.weight(1f))
                    MetricTile(Icons.Rounded.Insights, if (predicted <= target) Accent.Time else Accent.Calories, "Predicted",
                        fmtDuration(predicted), modifier = Modifier.weight(1f))
                }
            }
            GoalType.DISTANCE -> {
                val d = goal.targetDistanceM ?: 0
                // Same "proven non-stop" figure as the goal navigator (tests + continuous stretches in swims).
                val longest = provenM?.toInt() ?: tests.filter { it.type == TestType.CONTINUOUS }.maxOfOrNull { it.distanceM }
                LinearMeter("Longest non-stop", (longest ?: 0).toFloat(), d.toFloat(), "${longest ?: 0} / $d m", Accent.Swim)
            }
            GoalType.FITNESS -> {
                val target = goal.weeklyMinutes ?: 0
                val monday = mondayOf(LocalDate.now()).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val minutes = swims.filter { it.startMs >= monday }.sumOf { it.activeSec } / 60
                LinearMeter("Minutes this week", minutes.toFloat(), target.toFloat(), "$minutes / $target min", Accent.Heart)
            }
            GoalType.TECHNIQUE -> Muted("Track SWOLF on each swim — fewer strokes for the same time means better technique.")
        }
    }
}

@Composable
fun LinearMeter(label: String, value: Float, max: Float, text: String, color: Color) {
    val p = LocalPalette.current
    val prog = animatedProgress((if (max > 0) value / max else 0f).coerceIn(0f, 1f), 1200)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row {
            Text(label, style = MaterialTheme.typography.labelLarge, color = p.textDim, modifier = Modifier.weight(1f))
            Text(text, style = MaterialTheme.typography.labelLarge, color = p.text)
        }
        Box(Modifier.fillMaxWidth().height(12.dp).background(p.track, RoundedCornerShape(50))) {
            Box(Modifier.fillMaxWidth(prog.coerceAtLeast(0.02f)).height(12.dp)
                .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(color.copy(alpha = 0.7f), color)), RoundedCornerShape(50)))
        }
    }
}

/** Mon–Sun strip with a status dot per planned session, today ringed. */
@Composable
private fun WeekStrip(week: PlanWeek, workouts: List<PlannedWorkout>, today: LocalDate, modifier: Modifier) {
    val p = LocalPalette.current
    GlassCard(modifier, padding = 14.dp) {
        Row(Modifier.fillMaxWidth()) {
            (0..6).forEach { d ->
                val day = LocalDate.ofEpochDay(week.startEpochDay + d)
                val w = workouts.firstOrNull { it.weekId == week.id && it.epochDay == day.toEpochDay() }
                val isToday = day == today
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(day.dayOfWeek.name.take(1), style = MaterialTheme.typography.labelMedium, color = if (isToday) Accent.Swim else p.textDim)
                    val (fill, ring) = when (w?.status) {
                        WorkoutStatus.DONE -> Accent.Time to Accent.Time
                        WorkoutStatus.SKIPPED -> p.track to p.track
                        WorkoutStatus.PLANNED -> Color.Transparent to sessionIcon(w.sessionType).second
                        null -> Color.Transparent to Color.Transparent
                    }
                    Box(
                        Modifier.size(34.dp)
                            .border(if (isToday) 2.dp else 0.dp, if (isToday) Accent.Swim else Color.Transparent, CircleShape)
                            .padding(3.dp)
                            .background(fill, CircleShape)
                            .border(2.dp, ring, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (w?.status == WorkoutStatus.DONE) {
                            Icon(Icons.Rounded.CheckCircle, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        } else {
                            Text("${day.dayOfMonth}", style = MaterialTheme.typography.labelMedium, color = p.text)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WeekCard(
    title: String,
    week: PlanWeek,
    workouts: List<PlannedWorkout>,
    today: LocalDate,
    onOpen: (Long) -> Unit,
    modifier: Modifier,
) {
    val p = LocalPalette.current
    val list = workouts.filter { it.weekId == week.id }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            SectionLabel(title, Modifier.weight(1f))
            Muted("${list.sumOf { it.totalM }} m · ${week.phase}${if (week.deload) " · recovery" else ""}")
        }
        week.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Accent.Calories, modifier = Modifier.padding(horizontal = 6.dp)) }
        list.forEach { w ->
            val (icon, color) = sessionIcon(w.sessionType)
            GlassCard(onClick = { onOpen(w.id) }, padding = 14.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(icon, color, 42.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(w.epochDay.fmtDay() + if (w.epochDay == today.toEpochDay()) " · Today" else "",
                            style = MaterialTheme.typography.labelMedium, color = if (w.epochDay == today.toEpochDay()) Accent.Swim else p.textDim)
                        Text(w.title, style = MaterialTheme.typography.titleMedium, color = p.text)
                        Text("${w.totalM} m", style = MaterialTheme.typography.labelLarge, color = color)
                    }
                    StatusPill(w.status)
                }
            }
        }
    }
}

@Composable
private fun StatusPill(status: WorkoutStatus) {
    val p = LocalPalette.current
    val (text, color) = when (status) {
        WorkoutStatus.DONE -> "Done" to Accent.Time
        WorkoutStatus.SKIPPED -> "Skipped" to p.textDim
        WorkoutStatus.PLANNED -> "Planned" to Accent.Swim
    }
    Box(Modifier.background(color.copy(alpha = 0.16f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 5.dp)) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
fun WorkoutScreen(id: Long, onBack: () -> Unit, onOpenSwim: (Long) -> Unit) {
    val app = LocalContext.current.container
    val p = LocalPalette.current
    val pw by app.coach.workout(id).collectAsState(initial = null)
    val analysis by app.analysis.state.collectAsState()
    val css = analysis.cssPer100.takeIf { analysis.ready && it > 0 }
    val scope = rememberCoroutineScope()

    Page {
        DetailHeader("Workout", onBack)
        val w = pw ?: return@Page
        val workout = remember(w.json) { runCatching { Workout.fromJson(w.json) }.getOrNull() } ?: return@Page
        val (icon, color) = sessionIcon(w.sessionType)

        Column(Modifier.fillMaxWidth().appear(0), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            IconBadge(icon, color, 64.dp)
            Text(workout.title, style = MaterialTheme.typography.headlineMedium, color = p.text)
            Text("${w.epochDay.fmtDay()} · ${workout.focus}", style = MaterialTheme.typography.bodyMedium, color = p.textDim)
            Row(verticalAlignment = Alignment.Bottom) {
                CountUp(workout.totalM.toDouble(), { "%,.0f".format(it) }, MaterialTheme.typography.displaySmall, color)
                Text(" m", style = MaterialTheme.typography.titleMedium, color = p.textDim, modifier = Modifier.padding(bottom = 6.dp))
            }
        }

        // Status segmented control
        Row(Modifier.fillMaxWidth().appear(1).glass(RoundedCornerShape(50)).padding(4.dp)) {
            WorkoutStatus.entries.forEach { st ->
                val sel = w.status == st
                val c = when (st) { WorkoutStatus.DONE -> Accent.Time; WorkoutStatus.SKIPPED -> Accent.Heart; WorkoutStatus.PLANNED -> Accent.Swim }
                Box(
                    Modifier.weight(1f).pressable { scope.launch { app.coach.setWorkoutStatus(w.id, st) } }
                        .background(if (sel) c else Color.Transparent, RoundedCornerShape(50)).padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(st.name.lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelLarge,
                        color = if (sel) Color.White else p.text)
                }
            }
        }

        css?.let { Muted("Paces from your threshold ${fmtPace(it)}/100m · “go every” = when to push off for the next rep.", Modifier.appear(2)) }

        workout.blocks.forEachIndexed { bi, b ->
            GlassCard(Modifier.appear(3 + bi)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(b.name, style = MaterialTheme.typography.titleMedium, color = p.text, modifier = Modifier.weight(1f))
                    Text("${b.totalM} m", style = MaterialTheme.typography.labelLarge, color = p.textDim)
                }
                b.sets.forEach { s -> SetRow(s, css) }
            }
        }
        workout.notes?.let { GlassCard { Body(it) } }
        w.matchedSwimId?.let { sid -> GlassButton("Open the matched swim", { onOpenSwim(sid) }, Modifier.fillMaxWidth(), icon = Icons.Rounded.Pool, tint = Accent.Swim) }
        Muted("Watch swims are matched to the nearest planned session within 2 days automatically.")
    }
}

@Composable
private fun SetRow(s: SwimSet, css: Double?) {
    val p = LocalPalette.current
    val full = s.describe(css)
    val main = full.substringBefore("\n")
    val note = full.substringAfter("\n", "").trim()
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Box(Modifier.padding(top = 2.dp)) { ZonePill(s.zone) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(main, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = p.text)
            if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.bodySmall, color = p.textDim)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GoalScreen(onBack: () -> Unit) {
    val app = LocalContext.current.container
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var type by remember { mutableStateOf(GoalType.DISTANCE) }
    var distance by remember { mutableStateOf("1000") }
    var time by remember { mutableStateOf("") }
    var focus by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf("120") }
    var weeks by remember { mutableStateOf(12f) }
    var error by remember { mutableStateOf<String?>(null) }

    val options = listOf(
        Triple(GoalType.DISTANCE, "Swim further", Icons.Rounded.Waves to Accent.Swim),
        Triple(GoalType.TIME, "Swim faster", Icons.Rounded.Speed to Accent.Calories),
        Triple(GoalType.TECHNIQUE, "Technique", Icons.Rounded.Tune to Accent.Pace),
        Triple(GoalType.FITNESS, "Fitness", Icons.Rounded.DirectionsRun to Accent.Heart),
    )

    Page {
        DetailHeader("Set a goal", onBack)
        Text("What do you want to achieve?", style = MaterialTheme.typography.headlineSmall, color = p.text, modifier = Modifier.appear(0))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.appear(1)) {
            options.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { (t, label, ic) ->
                        val sel = type == t
                        Column(
                            Modifier.weight(1f).pressable { type = t }.glass(RoundedCornerShape(24.dp), strong = sel)
                                .border(if (sel) 2.dp else 0.dp, if (sel) ic.second else Color.Transparent, RoundedCornerShape(24.dp))
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            IconBadge(ic.first, ic.second, 40.dp)
                            Text(label, style = MaterialTheme.typography.titleMedium, color = p.text)
                        }
                    }
                }
            }
        }
        GlassCard(Modifier.appear(2)) {
            Muted(when (type) {
                GoalType.DISTANCE -> "Build up to swimming a distance without stopping, e.g. 1000 m."
                GoalType.TIME -> "Swim a distance under a target time, e.g. 400 m in 9:00."
                GoalType.TECHNIQUE -> "Drill-focused plan for a stroke or skill, e.g. breaststroke or bilateral breathing."
                GoalType.FITNESS -> "Consistent aerobic swimming towards a weekly minutes target."
            })
            if (type == GoalType.DISTANCE || type == GoalType.TIME) GlassTextField(distance, { distance = it.filter(Char::isDigit) }, "Distance (m)", numeric = true)
            if (type == GoalType.TIME) GlassTextField(time, { time = it }, "Target time (mm:ss)")
            if (type == GoalType.TECHNIQUE || type == GoalType.FITNESS) GlassTextField(focus, { focus = it },
                if (type == GoalType.TECHNIQUE) "Focus (e.g. breaststroke, breathing)" else "Notes (optional)")
            if (type == GoalType.FITNESS) GlassTextField(minutes, { minutes = it.filter(Char::isDigit) }, "Minutes per week", numeric = true)

            Row(verticalAlignment = Alignment.Bottom) {
                Text("${weeks.toInt()}", style = MaterialTheme.typography.headlineMedium, color = p.text)
                Text(" weeks", style = MaterialTheme.typography.titleMedium, color = p.textDim, modifier = Modifier.padding(bottom = 4.dp))
            }
            Slider(weeks, { weeks = it }, valueRange = PlanEngine.MIN_WEEKS.toFloat()..PlanEngine.MAX_WEEKS.toFloat(),
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Accent.Swim, inactiveTrackColor = p.track,
                    activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent))
            val start = app.coach.defaultStart()
            val end = start.plusWeeks(weeks.toLong() - 1).plusDays(6)
            Muted("Starts ${start.toEpochDay().fmtDay()} · goal day ${end.toEpochDay().fmtDay()} ${end.year}")
            error?.let { Text(it, color = Accent.Heart, style = MaterialTheme.typography.bodySmall) }
            PrimaryButton("Build my plan", {
                val d = distance.toIntOrNull()
                val t = parseDuration(time)
                error = when {
                    (type == GoalType.DISTANCE || type == GoalType.TIME) && (d == null || d < 50) -> "Enter a distance of at least 50 m."
                    type == GoalType.TIME && t == null -> "Enter a target time like 9:30."
                    else -> null
                }
                if (error != null) return@PrimaryButton
                scope.launch {
                    app.coach.setGoal(Goal(
                        type = type,
                        targetDistanceM = if (type == GoalType.DISTANCE || type == GoalType.TIME) d else null,
                        targetTimeSec = if (type == GoalType.TIME) t else null,
                        focus = focus.ifBlank { null },
                        weeklyMinutes = if (type == GoalType.FITNESS) minutes.toIntOrNull() else null,
                        startEpochDay = start.toEpochDay(),
                        targetEpochDay = end.toEpochDay(),
                    ))
                    onBack()
                }
            }, Modifier.fillMaxWidth())
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TestScreen(onBack: () -> Unit) {
    val app = LocalContext.current.container
    val scope = rememberCoroutineScope()
    var type by remember { mutableStateOf(TestType.T100) }
    LaunchedEffect(Unit) { type = PlanEngine.recommendedTest(app.coach.profileOrDefault()) }
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    var t1 by remember { mutableStateOf("") }
    var t2 by remember { mutableStateOf("") }
    var dist by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Page {
        DetailHeader("Log a test", onBack)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.appear(0)) {
            TestType.entries.forEach { t -> GlassChip(t.label().replaceFirstChar { it.uppercase() }, type == t, { type = t }, Accent.Swolf) }
        }
        GlassCard(Modifier.appear(1)) {
            CardHeader(type.label().replaceFirstChar { it.uppercase() }, Icons.Rounded.Science, Accent.Swolf)
            Muted(when (type) {
                TestType.CSS -> "After a warm-up: 400 m time trial, 5–8 min easy, then 200 m time trial. CSS = (T400 − T200) ÷ 2."
                TestType.T100 -> "After a warm-up: 100 m as fast as you can with good form. Used until you can swim 400 m non-stop."
                TestType.CONTINUOUS -> "Swim steadily as far as you can without stopping. Enter the distance and time."
            })
            GlassTextField(date, { date = it }, "Date (yyyy-mm-dd)")
            when (type) {
                TestType.CSS -> {
                    GlassTextField(t1, { t1 = it }, "400 m time (mm:ss)")
                    GlassTextField(t2, { t2 = it }, "200 m time (mm:ss)")
                }
                TestType.T100 -> GlassTextField(t1, { t1 = it }, "100 m time (mm:ss)")
                TestType.CONTINUOUS -> {
                    GlassTextField(dist, { dist = it.filter(Char::isDigit) }, "Distance (m)", numeric = true)
                    GlassTextField(t1, { t1 = it }, "Time (mm:ss)")
                }
            }
            error?.let { Text(it, color = Accent.Heart, style = MaterialTheme.typography.bodySmall) }
            PrimaryButton("Save & update plan", {
                val d = runCatching { LocalDate.parse(date.trim()) }.getOrNull()
                val a = parseDuration(t1)
                val b = parseDuration(t2)
                val distance = when (type) { TestType.CSS -> 400; TestType.T100 -> 100; TestType.CONTINUOUS -> dist.toIntOrNull() ?: 0 }
                if (d == null || a == null || (type == TestType.CSS && (b == null || b >= a)) || distance < 25) {
                    error = "Check the date and times (the 200 m must be faster than the 400 m)."
                    return@PrimaryButton
                }
                val css = PlanEngine.cssFromTest(type, a, b, distance)
                scope.launch {
                    app.coach.addTest(TestResult(epochDay = d.toEpochDay(), type = type, timeSec = a, time200Sec = b.takeIf { type == TestType.CSS },
                        distanceM = distance, cssPer100 = css))
                    onBack()
                }
            }, Modifier.fillMaxWidth())
        }
    }
}
