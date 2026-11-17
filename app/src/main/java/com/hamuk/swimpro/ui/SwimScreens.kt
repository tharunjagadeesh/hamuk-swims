package com.hamuk.swimpro.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Pool
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hamuk.swimpro.coach.fmtDuration
import com.hamuk.swimpro.coach.fmtPace
import com.hamuk.swimpro.coach.localDate
import com.hamuk.swimpro.coach.parseDuration
import com.hamuk.swimpro.container
import com.hamuk.swimpro.data.Swim
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val monthFmt = DateTimeFormatter.ofPattern("MMMM yyyy")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwimsScreen(onOpen: (Long) -> Unit, onAdd: () -> Unit) {
    val app = LocalContext.current.container
    val swims by app.swims.swims.collectAsState(initial = null)
    val syncMsg by app.settings.lastSyncMessage.collectAsState(initial = null)
    var refreshing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            scope.launch {
                refreshing = true
                status = runCatching { app.sync().message }.getOrElse { it.message }
                refreshing = false
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) {
        Page {
            LargeTitle("Swims", subtitle = "Pull to sync") {
                GlassIconButton(Icons.Rounded.Add, "Add swim", onAdd)
            }
            Muted(status ?: syncMsg ?: "Swims from Samsung Health appear here automatically.", Modifier.appear(1))
            val list = swims
            if (list != null && list.isEmpty()) {
                GlassCard(Modifier.appear(2)) {
                    CardHeader("Nothing here yet", Icons.Rounded.Pool, Accent.Swim)
                    Body("Do a pool swim with your Galaxy Watch, then pull down to sync — or tap + to add one by hand.")
                }
            }
            var idx = 2
            list.orEmpty().groupBy { it.localDate().withDayOfMonth(1) }.forEach { (month, inMonth) ->
                Row(Modifier.fillMaxWidth().appear(idx), verticalAlignment = Alignment.Bottom) {
                    SectionLabel(month.format(monthFmt), Modifier.weight(1f))
                    Muted("${inMonth.size} swims · %.1f km".format(inMonth.sumOf { it.distanceM } / 1000))
                }
                inMonth.forEach { s -> SwimRow(s, Modifier.appear(idx++)) { onOpen(s.id) } }
            }
        }
    }
}

@Composable
fun SwimRow(swim: Swim, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    GlassCard(modifier, onClick = onClick, padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(if (swim.openWater) Icons.Rounded.Waves else Icons.Rounded.Pool, Accent.Swim, 44.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(fmtDateTime(swim.startMs), style = MaterialTheme.typography.labelMedium, color = p.textDim)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("%,d".format(swim.distanceM.toInt()), style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = p.text)
                    Text(" m", style = MaterialTheme.typography.labelLarge, color = p.textDim, modifier = Modifier.padding(bottom = 2.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(fmtDuration(swim.activeSec.toDouble()), style = MaterialTheme.typography.titleSmall, color = Accent.Time, modifier = Modifier.padding(bottom = 2.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    swim.pacePer100?.let { Text("${fmtPace(it)}/100", style = MaterialTheme.typography.labelMedium, color = Accent.Pace) }
                    swim.avgHr?.let { Text("♥ $it", style = MaterialTheme.typography.labelMedium, color = Accent.Heart) }
                    swim.rpe?.let { Text("RPE $it", style = MaterialTheme.typography.labelMedium, color = rpeColor(it)) }
                }
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = p.textDim)
        }
    }
}

fun rpeColor(rpe: Int): Color = when {
    rpe <= 3 -> Accent.Time
    rpe <= 5 -> Color(0xFFB8E04A)
    rpe <= 7 -> Accent.Gold
    rpe <= 8 -> Accent.Calories
    else -> Accent.Heart
}

@Composable
fun SwimDetailScreen(id: Long, onBack: () -> Unit, onReview: () -> Unit, onCompare: (Long, Long) -> Unit, onCompareDays: (Long, Long) -> Unit) {
    val app = LocalContext.current.container
    val p = LocalPalette.current
    val swim by app.swims.swim(id).collectAsState(initial = null)
    val laps by app.swims.laps(id).collectAsState(initial = emptyList())
    val hr by app.swims.heartRate(id).collectAsState(initial = emptyList())
    val hasKey = remember { app.keys.hasKey() }
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(0) }
    val s = swim

    Page {
        DetailHeader("Swim", onBack)
        if (s == null) return@Page

        // Hero
        Column(Modifier.fillMaxWidth().appear(0), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(fmtDateTime(s.startMs) + " – " + fmtTime(s.endMs), style = MaterialTheme.typography.labelLarge, color = p.textDim)
            Row(verticalAlignment = Alignment.Bottom) {
                CountUp(s.distanceM, { "%,.0f".format(it) }, MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Bold), p.text)
                Text(" m", style = MaterialTheme.typography.headlineSmall, color = p.textDim, modifier = Modifier.padding(bottom = 10.dp))
            }
            Text(
                listOfNotNull(if (s.openWater) "Open water" else s.poolLengthM?.let { "$it m pool" }, s.strokeSummary).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium, color = Accent.Swim,
            )
        }

        GlassSegmented(listOf("Overview", "Analysis", "Lengths"), tab, { tab = it }, Modifier.appear(1))

        if (tab == 1) {
            SwimAnalysis(id, onCompare, onCompareDays)
            return@Page
        }
        if (tab == 2) {
            LengthsCard(laps)
            return@Page
        }

        val swolfs = laps.mapNotNull { it.swolf }
        val lapCount = laps.count { it.kind == "lap" }
        Box(Modifier.appear(1)) {
            MetricGrid(listOf(
                { m -> MetricTile(Icons.Rounded.Timer, Accent.Time, "Workout time", fmtDuration(s.activeSec.toDouble()), modifier = m) },
                { m -> MetricTile(Icons.Rounded.Speed, Accent.Pace, "Avg pace", s.pacePer100?.let { fmtPace(it) } ?: "–", "/100m", m) },
                { m -> MetricTile(Icons.Rounded.Favorite, Accent.Heart, "Avg heart rate", s.avgHr?.toString() ?: "–", "bpm", m) },
                { m -> MetricTile(Icons.Rounded.MonitorHeart, Accent.Heart, "Max heart rate", s.maxHr?.toString() ?: "–", "bpm", m) },
                { m -> MetricTile(Icons.Rounded.LocalFireDepartment, Accent.Calories, "Calories", (s.activeKcal ?: s.totalKcal)?.toInt()?.toString() ?: "–", "kcal", m) },
                { m ->
                    if (swolfs.isNotEmpty() || s.manualSwolf != null) {
                        MetricTile(Icons.Rounded.Repeat, Accent.Swolf, "Avg SWOLF", (swolfs.takeIf { it.isNotEmpty() }?.average()?.toInt() ?: s.manualSwolf).toString(), "", m)
                    } else {
                        MetricTile(Icons.Rounded.Straighten, Accent.Swolf, "Lengths", if (lapCount > 0) lapCount.toString() else "–", "", m)
                    }
                },
            ))
        }

        if (hasKey) {
            Box(Modifier.appear(2)) {
                GlassCard(onClick = { app.chat.run(app.scope) { reviewSwim(s) }; onReview() }, padding = 14.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(Icons.Rounded.AutoAwesome, Accent.Pace, 36.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Review with your coach", style = MaterialTheme.typography.titleSmall, color = p.text)
                            Muted("What went well and what to try next time")
                        }
                        Icon(Icons.Rounded.ChevronRight, null, tint = p.textDim)
                    }
                }
            }
        }

        if (hr.size >= 2) GlassCard(Modifier.appear(3)) {
            CardHeader("Heart rate", Icons.Rounded.Favorite, Accent.Heart, trailing = "${hr.minOf { it.bpm }}–${hr.maxOf { it.bpm }} bpm")
            AreaChart(hr.map { it.bpm.toDouble() }, Accent.Heart, startLabel = fmtTime(s.startMs), endLabel = fmtTime(s.endMs))
        }

        val timed = laps.filter { (it.distanceM ?: 0.0) > 0 }
        if (timed.size >= 2) GlassCard(Modifier.appear(4)) {
            val paces = timed.map { it.seconds / it.distanceM!! * 100 }
            CardHeader("Pace per length", Icons.Rounded.Speed, Accent.Pace, trailing = "best ${fmtPace(paces.min())}")
            // Taller bar = faster length.
            val speeds = paces.map { 1.0 / it }
            BarChart(speeds, timed.mapIndexed { i, _ -> if (timed.size <= 12 || i % (timed.size / 8 + 1) == 0) "${i + 1}" else "" }, Accent.Pace, highlightLast = false)
        }

        Box(Modifier.appear(6)) {
            FeelEditor(s) { updated -> scope.launch { app.swims.update(updated); app.coach.onSwimsSynced() } }
        }

        GlassButton("Delete swim", { confirmDelete = true }, Modifier.fillMaxWidth(), tint = Accent.Heart)
        Muted("Source: ${s.source}")
    }

    if (confirmDelete && s != null) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete this swim?") },
        text = { Text("It's removed from Hamuk Swims only — Samsung Health keeps it.") },
        confirmButton = {
            TextButton(onClick = { confirmDelete = false; scope.launch { app.swims.delete(s); onBack() } }) { Text("Delete", color = Accent.Heart) }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}


@Composable
private fun LengthsCard(laps: List<com.hamuk.swimpro.data.SwimLap>) {
    val p = LocalPalette.current
    if (laps.isEmpty()) { Muted("Your watch didn't report individual lengths for this swim."); return }
    GlassCard(Modifier.appear(2)) {
        CardHeader(if (laps.first().kind == "lap") "Lengths" else "Stroke segments", Icons.Rounded.Straighten, Accent.Swim, trailing = "${laps.size}")
        laps.forEach { l ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(26.dp).background(Accent.Swim.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
                    Text("${l.idx + 1}", style = MaterialTheme.typography.labelSmall, color = Accent.Swim)
                }
                Spacer(Modifier.width(10.dp))
                Text(l.stroke ?: "—", style = MaterialTheme.typography.bodyMedium, color = p.text, modifier = Modifier.weight(1f))
                Text(l.distanceM?.let { "${it.toInt()} m" } ?: "", style = MaterialTheme.typography.bodySmall, color = p.textDim, modifier = Modifier.width(50.dp))
                Text("${l.seconds.toInt()} s", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = p.text, modifier = Modifier.width(48.dp))
                Text(l.strokes?.let { "SWOLF ${l.swolf}" } ?: "", style = MaterialTheme.typography.labelSmall, color = Accent.Swolf)
            }
        }
    }

}

/** "How did it feel?" — RPE dots that pop when chosen, SWOLF, pool and notes. */
@Composable
private fun FeelEditor(swim: Swim, onSave: (Swim) -> Unit) {
    val p = LocalPalette.current
    var rpe by remember(swim.id) { mutableStateOf(swim.rpe) }
    var notes by remember(swim.id) { mutableStateOf(swim.notes ?: "") }
    var swolf by remember(swim.id) { mutableStateOf(swim.manualSwolf?.toString() ?: "") }
    var pool by remember(swim.id) { mutableStateOf(swim.poolLengthM?.toString() ?: "") }
    var saved by remember { mutableStateOf(false) }

    GlassCard {
        CardHeader("How did it feel?", trailing = rpe?.let { rpeLabel(it) })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            (1..10).forEach { v ->
                val selected = rpe == v
                val scale by animateFloatAsState(if (selected) 1.22f else 1f, spring(dampingRatio = 0.4f, stiffness = 500f), label = "rpe")
                val bg by animateColorAsState(if (selected) rpeColor(v) else rpeColor(v).copy(alpha = 0.18f), label = "rpeBg")
                Box(
                    Modifier.size(28.dp).graphicsLayer { scaleX = scale; scaleY = scale }
                        .background(bg, CircleShape)
                        .border(1.dp, if (selected) Color.White.copy(alpha = 0.7f) else Color.Transparent, CircleShape)
                        .pressable { rpe = if (rpe == v) null else v; saved = false },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("$v", style = MaterialTheme.typography.labelMedium, color = if (selected) Color.White else p.text)
                }
            }
        }
        Muted("Effort from 1 (very easy) to 10 (all-out)")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassTextField(swolf, { swolf = it.filter(Char::isDigit); saved = false }, "Avg SWOLF", Modifier.weight(1f), numeric = true)
            if (!swim.openWater) GlassTextField(pool, { pool = it.filter(Char::isDigit); saved = false }, "Pool (m)", Modifier.weight(1f), numeric = true)
        }
        GlassTextField(notes, { notes = it; saved = false }, "Notes", singleLine = false, minLines = 2)
        PrimaryButton(if (saved) "Saved ✓" else "Save", {
            onSave(swim.copy(rpe = rpe, notes = notes.ifBlank { null }, manualSwolf = swolf.toIntOrNull(),
                poolLengthM = pool.toIntOrNull() ?: swim.poolLengthM))
            saved = true
        }, Modifier.fillMaxWidth(), color = if (saved) Accent.Time else Accent.Swim)
    }
}

private fun rpeLabel(v: Int) = when {
    v <= 2 -> "Very easy"
    v <= 4 -> "Easy"
    v <= 6 -> "Moderate"
    v <= 8 -> "Hard"
    else -> "Max effort"
}

@Composable
fun AddSwimScreen(onBack: () -> Unit) {
    val app = LocalContext.current.container
    val scope = rememberCoroutineScope()
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    var time by remember { mutableStateOf("07:00") }
    var distance by remember { mutableStateOf("") }
    var duration by remember { mutableStateOf("") }
    var avgHr by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Page {
        DetailHeader("Add swim", onBack)
        GlassCard(Modifier.appear(0)) {
            Muted("For a swim that didn't come through from Samsung Health.")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassTextField(date, { date = it }, "Date (yyyy-mm-dd)", Modifier.weight(1.4f))
                GlassTextField(time, { time = it }, "Start", Modifier.weight(1f))
            }
            GlassTextField(distance, { distance = it.filter(Char::isDigit) }, "Distance (m)", numeric = true)
            GlassTextField(duration, { duration = it }, "Swim time (mm:ss or h:mm:ss)")
            GlassTextField(avgHr, { avgHr = it.filter(Char::isDigit) }, "Average heart rate (optional)", numeric = true)
            error?.let { Text(it, color = Accent.Heart, style = MaterialTheme.typography.bodySmall) }
            PrimaryButton("Save swim", {
                val d = runCatching { LocalDate.parse(date.trim()) }.getOrNull()
                val t = runCatching { LocalTime.parse(time.trim()) }.getOrNull()
                val dist = distance.toIntOrNull()
                val secs = parseDuration(duration)
                if (d == null || t == null || dist == null || secs == null) {
                    error = "Check the date, time, distance and swim time."
                    return@PrimaryButton
                }
                val start = d.atTime(t).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                scope.launch {
                    val pool = app.coach.profileOrDefault().poolLengthM
                    app.swims.addManual(Swim(startMs = start, endMs = start + secs * 1000L, distanceM = dist.toDouble(),
                        activeSec = secs.toLong(), poolLengthM = pool, avgHr = avgHr.toIntOrNull()))
                    app.coach.onSwimsSynced()
                    onBack()
                }
            }, Modifier.fillMaxWidth())
        }
    }
}

