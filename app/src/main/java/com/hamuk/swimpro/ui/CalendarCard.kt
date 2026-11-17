package com.hamuk.swimpro.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hamuk.swimpro.coach.localDate
import com.hamuk.swimpro.coach.mondayOf
import com.hamuk.swimpro.data.PlannedWorkout
import com.hamuk.swimpro.data.Swim
import com.hamuk.swimpro.data.WorkoutStatus
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

private val monthTitle = DateTimeFormatter.ofPattern("MMMM yyyy")

/** Intensity 0..1 for a day's distance relative to a typical session, for heat colouring. */
private fun intensity(meters: Double, ref: Double): Float =
    if (meters <= 0) 0f else (0.35f + 0.65f * (meters / ref).toFloat()).coerceIn(0.35f, 1f)

/**
 * Month calendar of swim days (Samsung Health style): filled = swam (brighter = further),
 * ring = planned session, outline = today. Swipe or use the arrows to change month.
 */
@Composable
fun ConsistencyCalendar(
    swims: List<Swim>,
    planned: List<PlannedWorkout>,
    onOpenSwim: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    val today = LocalDate.now()
    var month by remember { mutableStateOf(YearMonth.now()) }
    var direction by remember { mutableStateOf(1) }
    val byDay = remember(swims) { swims.groupBy { it.localDate() } }
    val plannedDays = remember(planned) { planned.associateBy { it.epochDay } }
    val ref = remember(swims) { swims.map { it.distanceM }.sorted().let { if (it.isEmpty()) 1000.0 else it[it.size * 3 / 4] }.coerceAtLeast(200.0) }

    fun go(delta: Int) {
        val next = month.plusMonths(delta.toLong())
        if (next.isAfter(YearMonth.now())) return
        direction = delta
        month = next
    }

    GlassCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.CalendarMonth, Accent.Swim, 30.dp)
            Spacer(Modifier.width(10.dp))
            Text(month.format(monthTitle), style = MaterialTheme.typography.titleMedium, color = p.text, modifier = Modifier.weight(1f))
            GlassIconButton(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous month", { go(-1) }, size = 36.dp)
            Spacer(Modifier.width(8.dp))
            GlassIconButton(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next month", { go(1) }, size = 36.dp,
                tint = if (month == YearMonth.now()) p.textDim.copy(alpha = 0.3f) else null)
        }

        Row(Modifier.fillMaxWidth()) {
            listOf("M", "T", "W", "T", "F", "S", "S").forEach {
                Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium, color = p.textDim)
            }
        }

        AnimatedContent(
            targetState = month,
            transitionSpec = {
                (slideInHorizontally(tween(320)) { it * direction / 3 } + fadeIn(tween(240))) togetherWith
                    (slideOutHorizontally(tween(260)) { -it * direction / 3 } + fadeOut(tween(180)))
            },
            label = "month",
            modifier = Modifier.pointerInput(Unit) {
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onHorizontalDrag = { _, dx -> total += dx },
                    onDragEnd = { if (total > 80) go(-1) else if (total < -80) go(1) },
                )
            },
        ) { m ->
            val first = m.atDay(1)
            val lead = first.dayOfWeek.value - 1
            val cells = lead + m.lengthOfMonth()
            val rows = (cells + 6) / 7
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (r in 0 until rows) {
                    Row(Modifier.fillMaxWidth()) {
                        for (c in 0 until 7) {
                            val idx = r * 7 + c - lead
                            Box(Modifier.weight(1f).aspectRatio(1f), contentAlignment = Alignment.Center) {
                                if (idx in 0 until m.lengthOfMonth()) {
                                    val day = first.plusDays(idx.toLong())
                                    DayCell(day, today, byDay[day].orEmpty(), plannedDays[day.toEpochDay()], ref, onOpenSwim)
                                }
                            }
                        }
                    }
                }
            }
        }

        val inMonth = swims.filter { YearMonth.from(it.localDate()) == month }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MiniStat("Swims", "${inMonth.size}", Accent.Swim, Modifier.weight(1f))
            MiniStat("Distance", "%.1f km".format(inMonth.sumOf { it.distanceM } / 1000), Accent.Time, Modifier.weight(1f))
            MiniStat("Days", "${inMonth.map { it.localDate() }.distinct().size}", Accent.Pace, Modifier.weight(1f))
        }
        Legend()
    }
}

@Composable
private fun DayCell(day: LocalDate, today: LocalDate, swims: List<Swim>, planned: PlannedWorkout?, ref: Double, onOpenSwim: (Long) -> Unit) {
    val p = LocalPalette.current
    val meters = swims.sumOf { it.distanceM }
    val heat = intensity(meters, ref)
    val future = day.isAfter(today)
    val missed = planned != null && swims.isEmpty() && !future && (planned.status != WorkoutStatus.DONE)
    Box(
        Modifier.fillMaxWidth(0.86f).aspectRatio(1f)
            .then(if (swims.isNotEmpty()) Modifier.pressable { onOpenSwim(swims.first().id) } else Modifier)
            .background(if (heat > 0) Accent.Swim.copy(alpha = heat) else Color.Transparent, CircleShape)
            .border(
                width = if (day == today) 2.dp else if (planned != null && swims.isEmpty()) 1.5.dp else 0.dp,
                color = when {
                    day == today -> p.text
                    missed -> Accent.Heart.copy(alpha = 0.6f)
                    planned != null && swims.isEmpty() -> Accent.Swim.copy(alpha = 0.7f)
                    else -> Color.Transparent
                },
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "${day.dayOfMonth}",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (heat > 0 || day == today) FontWeight.Bold else FontWeight.Normal),
            color = when {
                heat > 0.6f -> Color.White
                future -> p.textDim.copy(alpha = 0.5f)
                else -> p.text
            },
        )
    }
}

@Composable
private fun MiniStat(label: String, value: String, color: Color, modifier: Modifier) {
    val p = LocalPalette.current
    Column(modifier.background(color.copy(alpha = 0.12f), RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(value, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = p.text)
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
private fun Legend() {
    val p = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(12.dp).background(Accent.Swim, CircleShape))
        Text("swam", style = MaterialTheme.typography.labelSmall, color = p.textDim)
        Spacer(Modifier.width(6.dp))
        Box(Modifier.size(12.dp).border(1.5.dp, Accent.Swim.copy(alpha = 0.7f), CircleShape))
        Text("planned", style = MaterialTheme.typography.labelSmall, color = p.textDim)
        Spacer(Modifier.width(6.dp))
        Box(Modifier.size(12.dp).border(1.5.dp, Accent.Heart.copy(alpha = 0.6f), CircleShape))
        Text("missed", style = MaterialTheme.typography.labelSmall, color = p.textDim)
    }
}

/** GitHub-style heatmap of the last [weeks] weeks: one column per week, Mon–Sun rows. */
@Composable
fun ConsistencyHeatmap(swims: List<Swim>, weeks: Int = 20, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val today = LocalDate.now()
    val start = mondayOf(today).minusWeeks((weeks - 1).toLong())
    val byDay = remember(swims) { swims.groupBy { it.localDate() }.mapValues { (_, l) -> l.sumOf { it.distanceM } } }
    val ref = remember(swims) { swims.map { it.distanceM }.sorted().let { if (it.isEmpty()) 1000.0 else it[it.size * 3 / 4] }.coerceAtLeast(200.0) }
    val weekly = (0 until weeks).map { w -> (0..6).count { d -> (byDay[start.plusDays(w * 7L + d)] ?: 0.0) > 0 } }
    val streak = run { var n = 0; for (w in weekly.indices.reversed()) { if (weekly[w] > 0) n++ else if (w != weekly.lastIndex) break }; n }
    val best = run { var b = 0; var c = 0; weekly.forEach { if (it > 0) { c++; b = maxOf(b, c) } else c = 0 }; b }
    val activeDays = byDay.keys.count { !it.isBefore(start) }

    GlassCard(modifier) {
        CardHeader("Last $weeks weeks", Icons.Rounded.GridView, Accent.Time, trailing = "$activeDays swim days")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            for (w in 0 until weeks) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    for (d in 0..6) {
                        val day = start.plusDays(w * 7L + d)
                        val m = byDay[day] ?: 0.0
                        val color = when {
                            day.isAfter(today) -> Color.Transparent
                            m > 0 -> Accent.Time.copy(alpha = intensity(m, ref))
                            else -> p.track
                        }
                        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(color, RoundedCornerShape(3.dp))
                            .then(if (day == today) Modifier.border(1.dp, p.text, RoundedCornerShape(3.dp)) else Modifier))
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MiniStat("Week streak", "$streak", Accent.Calories, Modifier.weight(1f))
            MiniStat("Best streak", "$best wks", Accent.Gold, Modifier.weight(1f))
            MiniStat("Avg / week", "%.1f".format(weekly.sum() / weeks.toDouble()), Accent.Time, Modifier.weight(1f))
        }
    }
}
