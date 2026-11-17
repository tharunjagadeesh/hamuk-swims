package com.hamuk.swimpro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hamuk.swimpro.analysis.GoalMath
import com.hamuk.swimpro.analysis.GoalNav
import com.hamuk.swimpro.analysis.GoalStatus
import com.hamuk.swimpro.analysis.GoalUnit
import com.hamuk.swimpro.coach.CoachRepository
import com.hamuk.swimpro.coach.CoachRepository.Companion.fmtDay
import com.hamuk.swimpro.coach.PlanEngine
import com.hamuk.swimpro.coach.fmtPace
import com.hamuk.swimpro.container
import com.hamuk.swimpro.data.GoalType
import com.hamuk.swimpro.data.Profile
import kotlinx.coroutines.flow.flowOf
import kotlin.math.abs

/** Collects everything the goal navigator needs and computes it; null without an active goal. */
@Composable
fun rememberGoalNav(): GoalNav? {
    val app = LocalContext.current.container
    val goal by app.coach.goal.collectAsState(initial = null)
    val profile by app.coach.profile.collectAsState(initial = null)
    val tests by app.coach.tests.collectAsState(initial = emptyList())
    val st by app.analysis.state.collectAsState()
    val readiness by app.lifestyle.readiness.collectAsState()
    val planned by remember(goal?.id) { goal?.let { app.coach.workouts(it.id) } ?: flowOf(emptyList()) }.collectAsState(initial = emptyList())
    val g = goal ?: return null
    if (!st.ready) return null
    return remember(g, profile, tests, st, planned, readiness) {
        GoalMath.compute(g, st.today, profile ?: Profile(), st.cssPer100, tests, st.swims, planned, st.hr,
            easyDay = (readiness?.score ?: 100) < 45)
    }
}

private fun statusColor(s: GoalStatus) = when (s) {
    GoalStatus.AHEAD -> Accent.Time
    GoalStatus.ON_TRACK -> Accent.Swim
    GoalStatus.BEHIND -> Accent.Calories
    GoalStatus.UNKNOWN -> Color(0xFF9AA7B8)
}

private fun statusLabel(s: GoalStatus) = when (s) {
    GoalStatus.AHEAD -> "Goal reached"
    GoalStatus.ON_TRACK -> "On track"
    GoalStatus.BEHIND -> "Behind"
    GoalStatus.UNKNOWN -> "Measuring"
}

@Composable
private fun StatusPill(s: GoalStatus) {
    val c = statusColor(s)
    Text(statusLabel(s), style = MaterialTheme.typography.labelLarge, color = c,
        modifier = Modifier.background(c.copy(alpha = 0.16f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp))
}

private fun signed(v: Double, unit: GoalUnit, lower: Boolean): String {
    // Express "required change" in the direction that matters: a time goal needs to drop.
    val mag = GoalMath.fmt(abs(v), unit)
    return if (lower) "−$mag" else "+$mag"
}

/** Compact card for Home and Coach. */
@Composable
fun GoalNavCard(nav: GoalNav, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    GlassCard(modifier, onClick = onOpen, strong = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.Explore, Accent.Gold, 30.dp)
            Spacer(Modifier.width(10.dp))
            Text("Goal navigator", style = MaterialTheme.typography.titleMedium, color = p.text, modifier = Modifier.weight(1f))
            StatusPill(nav.status)
        }
        Text(CoachRepository.goalDescription(nav.goal), style = MaterialTheme.typography.bodyMedium, color = p.textDim)
        GoalProgressBar(nav)
        nav.requiredPerWeek?.takeIf { nav.goal.type != GoalType.FITNESS && abs(it) > 1e-6 && nav.status != GoalStatus.AHEAD }?.let {
            Body("Need ${signed(it, nav.unit, nav.lowerIsBetter)} per week · ${nav.daysLeft} days left")
        }
        if (nav.goal.type == GoalType.FITNESS) nav.requiredPerDay?.let { Body("Need ${GoalMath.fmt(it, GoalUnit.MINUTES)} per remaining day this week") }
        nav.days.firstOrNull { it.workoutId != null && !it.done }?.let { d ->
            val main = d.paces.firstOrNull { it.label == "Main set" } ?: d.paces.firstOrNull()
            Body(
                "Next: ${if (d.epochDay == nav.days.first().epochDay) "today" else d.epochDay.fmtDay()} · ${d.title} · ${d.distanceM} m" +
                    (main?.pacePer100?.let { " · ${main.label.lowercase()} @ ${fmtPace(it)}/100" } ?: ""),
                color = Accent.Swim,
            )
        }
    }
}

@Composable
private fun GoalProgressBar(nav: GoalNav) {
    val s = nav.start; val c = nav.current; val t = nav.target
    if (s == null || c == null || t == null || abs(t - s) < 1e-9) return
    val frac = ((c - s) / (t - s)).toFloat().coerceIn(0f, 1f)
    val exp = nav.expectedToday?.let { ((it - s) / (t - s)).toFloat().coerceIn(0f, 1f) }
    LinearMeterWithMarker(frac, exp, "${GoalMath.fmt(c, nav.unit)} now", GoalMath.fmt(t, nav.unit), statusColor(nav.status))
}

@Composable
fun LinearMeterWithMarker(value: Float, marker: Float?, left: String, right: String, color: Color) {
    val p = LocalPalette.current
    val prog = animatedProgress(value, 1100)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(12.dp)) {
            val cy = size.height / 2
            drawLine(p.track, androidx.compose.ui.geometry.Offset(0f, cy), androidx.compose.ui.geometry.Offset(size.width, cy), size.height,
                cap = androidx.compose.ui.graphics.StrokeCap.Round)
            if (prog > 0.001f) drawLine(color, androidx.compose.ui.geometry.Offset(0f, cy), androidx.compose.ui.geometry.Offset(size.width * prog, cy), size.height,
                cap = androidx.compose.ui.graphics.StrokeCap.Round)
            marker?.let {
                val x = size.width * it
                drawLine(Color.White, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, size.height), 3.dp.toPx())
            }
        }
        Row {
            Text(left, style = MaterialTheme.typography.labelMedium, color = p.text, modifier = Modifier.weight(1f))
            if (marker != null) Text("| = today's checkpoint", style = MaterialTheme.typography.labelSmall, color = p.textDim)
            Spacer(Modifier.width(8.dp))
            Text(right, style = MaterialTheme.typography.labelMedium, color = p.textDim)
        }
    }
}


@Composable
fun GoalNavScreen(onBack: () -> Unit, onOpenWorkout: (Long) -> Unit, onSetGoal: () -> Unit) {
    val p = LocalPalette.current
    val nav = rememberGoalNav()
    val app = LocalContext.current.container
    val st by app.analysis.state.collectAsState()
    Page {
        DetailHeader("Goal navigator", onBack)
        if (nav == null) {
            GlassCard { Body("Set a goal first. The navigator then works out what you need to do each day to get there."); PrimaryButton("Set a goal", onSetGoal) }
            return@Page
        }
        val g = nav.goal

        GlassCard(Modifier.appear(0), strong = true) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Rounded.Flag, Accent.Gold, 34.dp)
                Spacer(Modifier.width(10.dp))
                Text(CoachRepository.goalDescription(g), style = MaterialTheme.typography.titleMedium, color = p.text, modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MiniMetric(nav.metricLabel.substringBefore(" (").let { if (it.length > 18) "Now" else it }, nav.current?.let { GoalMath.fmt(it, nav.unit) } ?: "–", Accent.Swim, Modifier.weight(1f))
                MiniMetric("Target", nav.target?.let { GoalMath.fmt(it, nav.unit) } ?: "–", Accent.Gold, Modifier.weight(1f))
                MiniMetric("Days left", "${nav.daysLeft}", Accent.Pace, Modifier.weight(1f))
            }
            GoalProgressBar(nav)
            Row(verticalAlignment = Alignment.CenterVertically) { StatusPill(nav.status) }
            Body(nav.statusText)
        }

        // What it takes.
        GlassCard(Modifier.appear(1)) {
            CardHeader("What it takes", Icons.Rounded.Timeline, Accent.Calories)
            if (g.type == GoalType.FITNESS) {
                nav.requiredPerDay?.let { KeyValueRow("Per remaining day this week", GoalMath.fmt(it, GoalUnit.MINUTES)) }
                nav.target?.let { KeyValueRow("Weekly target", GoalMath.fmt(it, GoalUnit.MINUTES)) }
            } else if (nav.status != GoalStatus.AHEAD) {
                nav.requiredPerWeek?.let { KeyValueRow("Improvement needed per week", signed(it, nav.unit, nav.lowerIsBetter)) }
                nav.requiredPerDay?.let { KeyValueRow("…per day", signed(it, nav.unit, nav.lowerIsBetter)) }
            }
            if (g.type == GoalType.TIME && g.targetDistanceM != null && g.targetTimeSec != null) {
                val needCss = PlanEngine.requiredCss(g.targetTimeSec, g.targetDistanceM)
                KeyValueRow("Race pace needed", "${fmtPace(nav.goalPace!!)}/100m", Accent.Gold)
                KeyValueRow("Threshold (CSS) needed", "${fmtPace(needCss)}/100m")
                KeyValueRow("Your threshold now", "${fmtPace(st.cssPer100)}/100m",
                    if (st.cssPer100 <= needCss) Accent.Time else Accent.Calories)
            }
            if (g.type == GoalType.DISTANCE) nav.current?.let { cur ->
                val nextLong = nav.days.firstOrNull { it.title.contains("Long", true) }
                KeyValueRow("Proven non-stop", GoalMath.fmt(cur, GoalUnit.METRES))
                nextLong?.let { KeyValueRow("Next long swim", "${it.epochDay.fmtDay()} · ${it.distanceM} m session") }
            }
            Muted("Checkpoint = a straight line from where you started to the goal. The projection extends your recent trend to goal day.")
        }

        // Trajectory chart.
        TrajectoryCard(nav, Modifier.appear(2))

        // Volume.
        GlassCard(Modifier.appear(3)) {
            CardHeader("Volume to goal day", Icons.Rounded.Waves, Accent.Swim)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MiniMetric("Remaining", "%,d m".format(nav.volume.remainingM), Accent.Swim, Modifier.weight(1f))
                MiniMetric("Sessions", "${nav.volume.sessionsLeft}", Accent.Pace, Modifier.weight(1f))
                MiniMetric("Per day", "%.0f m".format(nav.volume.perDayM), Accent.Time, Modifier.weight(1f))
            }
            KeyValueRow("Average per session", "%.0f m".format(nav.volume.perSessionM))
            LinearMeter("This week", nav.volume.weekDoneM.toFloat(), nav.volume.weekPlannedM.toFloat(),
                "%,.0f / %,d m".format(nav.volume.weekDoneM, nav.volume.weekPlannedM), Accent.Swim)
        }

        // Day by day.
        SectionLabel("Next 7 days", Modifier.appear(4))
        nav.days.forEachIndexed { i, d ->
            val isToday = i == 0
            GlassCard(Modifier.appear(5 + i), onClick = d.workoutId?.let { { onOpenWorkout(it) } }, padding = 14.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (isToday) "Today" else d.epochDay.fmtDay(), style = MaterialTheme.typography.labelMedium,
                            color = if (isToday) Accent.Swim else p.textDim)
                        Text(d.title, style = MaterialTheme.typography.titleSmall, color = if (d.workoutId == null) p.textDim else p.text)
                    }
                    if (d.distanceM > 0) Text("${d.distanceM} m", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = Accent.Swim)
                    if (d.done) Text("  ✓", color = Accent.Time)
                }
                d.paces.forEach { t ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ZonePill(t.zone)
                        Spacer(Modifier.width(8.dp))
                        Text("${t.label}: ${t.detail}", style = MaterialTheme.typography.bodySmall, color = p.text, modifier = Modifier.weight(1f))
                        Text(t.pacePer100?.let { "${fmtPace(it)}/100" } ?: "max effort", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = if (t.label == "Goal pace") Accent.Gold else p.text)
                    }
                }
                if (d.workoutId != null) Row {
                    d.hrCap?.let { Muted("Easy sets under $it bpm", Modifier.weight(1f)) }
                    d.lastTimePace?.let { Muted("Last time ${fmtPace(it)}/100") }
                }
            }
        }
        Muted("All of this is calculated on your phone from your swims, tests and plan. No AI or internet needed.")
    }
}

@Composable
private fun TrajectoryCard(nav: GoalNav, modifier: Modifier) {
    val g = nav.goal
    if (g.type == GoalType.FITNESS || nav.start == null || nav.target == null || nav.actual.isEmpty()) return
    val first = minOf(g.startEpochDay, nav.actual.first().first)
    val last = g.targetEpochDay
    val n = (last - first + 1).toInt().coerceIn(2, 400)
    val today = nav.days.first().epochDay
    val start = nav.start; val target = nav.target
    val required = List(n) { i -> val d = first + i; if (d < g.startEpochDay) null else start + (target - start) * (d - g.startEpochDay).toDouble() / (last - g.startEpochDay).coerceAtLeast(1) }
    // Step through actual points up to today.
    val actual = List(n) { i ->
        val d = first + i
        if (d > today) null else nav.actual.lastOrNull { it.first <= d }?.second
    }
    val projection = List(n) { i ->
        val d = first + i
        if (d < today || nav.projected == null || nav.current == null) null
        else nav.current + (nav.projected - nav.current) * (d - today).toDouble() / (last - today).coerceAtLeast(1)
    }
    GlassCard(modifier) {
        CardHeader("Trajectory", Icons.Rounded.CalendarMonth, Accent.Gold, trailing = "${first.fmtDay()} → ${last.fmtDay()}")
        MultiLineChart(
            listOf(
                Series(required, Accent.Gold, "needed", dashed = true),
                Series(actual, Accent.Swim, "you"),
                Series(projection, Accent.Calories, "trend", dashed = true),
            ),
            invertY = nav.lowerIsBetter, startLabel = first.fmtDay(), endLabel = "goal day",
        )
        Muted(if (nav.lowerIsBetter) "Chart is flipped so up = better. Stay on or above the gold line." else "Stay on or above the gold line.")
    }
}
