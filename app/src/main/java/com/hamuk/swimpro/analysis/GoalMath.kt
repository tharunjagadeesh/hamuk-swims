package com.hamuk.swimpro.analysis

import com.hamuk.swimpro.coach.PlanEngine
import com.hamuk.swimpro.coach.Workout
import com.hamuk.swimpro.coach.Zones
import com.hamuk.swimpro.data.Goal
import com.hamuk.swimpro.data.GoalType
import com.hamuk.swimpro.data.PlannedWorkout
import com.hamuk.swimpro.data.Profile
import com.hamuk.swimpro.data.TestResult
import com.hamuk.swimpro.data.TestType
import com.hamuk.swimpro.data.WorkoutStatus
import kotlin.math.abs
import kotlin.math.max

enum class GoalStatus { AHEAD, ON_TRACK, BEHIND, UNKNOWN }

enum class GoalUnit { SECONDS, METRES, MINUTES, SWOLF }

data class PaceTarget(val label: String, val detail: String, val pacePer100: Double?, val zone: Int)

data class DayTarget(
    val epochDay: Long,
    val workoutId: Long?,
    val title: String,
    val distanceM: Int,
    val paces: List<PaceTarget>,
    /** Keep easy/aerobic sets under this HR (top of Z2). */
    val hrCap: Int?,
    val lastTimePace: Double?,
    val done: Boolean,
)

data class GoalVolume(
    val remainingM: Int,
    val sessionsLeft: Int,
    val perDayM: Double,
    val perSessionM: Double,
    val weekDoneM: Double,
    val weekPlannedM: Int,
)

data class GoalNav(
    val goal: Goal,
    val daysLeft: Int,
    val unit: GoalUnit,
    val lowerIsBetter: Boolean,
    val metricLabel: String,
    val start: Double?,
    val current: Double?,
    val target: Double?,
    /** Where the straight line from start to target says you should be today. */
    val expectedToday: Double?,
    val projected: Double?,
    val requiredPerWeek: Double?,
    val requiredPerDay: Double?,
    val status: GoalStatus,
    val statusText: String,
    val actual: List<Pair<Long, Double>>,
    /** Race pace needed (s/100 m) for time goals. */
    val goalPace: Double?,
    val volume: GoalVolume,
    val days: List<DayTarget>,
)

/**
 * Deterministic goal tracking: how far you are from the goal, how much has to change per
 * week/day, whether your trend gets you there, and concrete targets for the next 7 days.
 */
object GoalMath {
    fun compute(
        goal: Goal,
        today: Long,
        profile: Profile,
        cssNow: Double,
        tests: List<TestResult>,
        swims: List<SwimMetrics>,
        planned: List<PlannedWorkout>,
        hr: HrProfile,
        easyDay: Boolean = false,
    ): GoalNav {
        val daysLeft = max(0, (goal.targetEpochDay - today).toInt())
        val weeksLeft = max(daysLeft / 7.0, 1 / 7.0)
        val sinceStart = swims.filter { it.epochDay >= goal.startEpochDay - 28 }

        // ---- The goal's own metric -------------------------------------------------------
        var unit = GoalUnit.SECONDS
        var lower = true
        var label = ""
        var actual: List<Pair<Long, Double>> = emptyList()
        var current: Double? = null
        var target: Double? = null
        var goalPace: Double? = null
        when (goal.type) {
            GoalType.TIME -> {
                val d = goal.targetDistanceM ?: 400
                label = "Predicted ${d} m time"
                target = goal.targetTimeSec?.toDouble()
                goalPace = target?.let { it / d * 100 }
                actual = (tests.map { it.epochDay to PlanEngine.predictTimeSec(it.cssPer100, d) } + (today to PlanEngine.predictTimeSec(cssNow, d)))
                    .groupBy { it.first }.map { (day, l) -> day to l.last().second }.sortedBy { it.first }
                current = PlanEngine.predictTimeSec(cssNow, d)
            }
            GoalType.DISTANCE -> {
                unit = GoalUnit.METRES; lower = false
                label = "Longest non-stop swim"
                target = goal.targetDistanceM?.toDouble()
                val raw = (sinceStart.mapNotNull { m -> m.longestContinuousM?.let { m.epochDay to it } } +
                    tests.filter { it.type == TestType.CONTINUOUS || it.type == TestType.CSS }.map { it.epochDay to it.distanceM.toDouble() } +
                    (goal.startEpochDay to profile.maxContinuousM.toDouble())).sortedBy { it.first }
                // Running best: you keep the distance you've proven.
                var best = 0.0
                actual = raw.groupBy { it.first }.toSortedMap().map { (day, l) -> best = max(best, l.maxOf { it.second }); day to best }
                current = actual.lastOrNull()?.second
            }
            GoalType.FITNESS -> {
                unit = GoalUnit.MINUTES; lower = false
                label = "Swim minutes per week"
                target = goal.weeklyMinutes?.toDouble()
                val mondayToday = today - java.time.LocalDate.ofEpochDay(today).dayOfWeek.value + 1
                actual = sinceStart.groupBy { it.epochDay - java.time.LocalDate.ofEpochDay(it.epochDay).dayOfWeek.value + 1 }
                    .map { (monday, l) -> monday to l.sumOf { it.minutes } }.sortedBy { it.first }
                current = actual.firstOrNull { it.first == mondayToday }?.second ?: 0.0
            }
            GoalType.TECHNIQUE -> {
                unit = GoalUnit.SWOLF
                label = "SWOLF (lower = more efficient)"
                actual = sinceStart.mapNotNull { m -> m.swolf?.let { m.epochDay to it } }
                current = actual.takeLast(3).map { it.second }.takeIf { it.isNotEmpty() }?.average()
                val startSwolf = actual.firstOrNull { it.first >= goal.startEpochDay }?.second ?: actual.firstOrNull()?.second
                target = startSwolf?.let { it - 3 }
            }
        }

        val start = when (goal.type) {
            GoalType.FITNESS -> actual.firstOrNull()?.second
            else -> actual.firstOrNull { it.first >= goal.startEpochDay }?.second ?: actual.firstOrNull()?.second ?: current
        }
        val totalDays = (goal.targetEpochDay - goal.startEpochDay).coerceAtLeast(1)
        val expected = if (start != null && target != null && goal.type != GoalType.FITNESS) {
            val f = ((today - goal.startEpochDay).toDouble() / totalDays).coerceIn(0.0, 1.0)
            start + (target - start) * f
        } else target

        val gap = if (current != null && target != null) target - current else null
        val perWeek = gap?.let { if (goal.type == GoalType.FITNESS) it else it / weeksLeft }
        val perDay = gap?.let {
            if (goal.type == GoalType.FITNESS) {
                val daysLeftInWeek = 8 - java.time.LocalDate.ofEpochDay(today).dayOfWeek.value
                max(0.0, it) / daysLeftInWeek
            } else it / max(daysLeft, 1)
        }

        // Linear trend of actual points projected to the goal date.
        val fitPts = actual.filter { it.first >= goal.startEpochDay - 28 }.map { it.first.toDouble() to it.second }
        val trend = if (goal.type != GoalType.FITNESS && fitPts.map { it.first }.distinct().size >= 2) Fit.of(fitPts, minPoints = 2) else null
        val projected = trend?.y(goal.targetEpochDay.toDouble())

        val (status, text) = status(goal.type, lower, current, target, expected, projected, unit)

        // ---- Volume ------------------------------------------------------------------------
        val ahead = planned.filter { it.status == WorkoutStatus.PLANNED && it.epochDay >= today && it.epochDay <= goal.targetEpochDay }
        val monday = today - java.time.LocalDate.ofEpochDay(today).dayOfWeek.value + 1
        val volume = GoalVolume(
            remainingM = ahead.sumOf { it.totalM },
            sessionsLeft = ahead.size,
            perDayM = ahead.sumOf { it.totalM } / max(daysLeft, 1).toDouble(),
            perSessionM = if (ahead.isEmpty()) 0.0 else ahead.sumOf { it.totalM }.toDouble() / ahead.size,
            weekDoneM = swims.filter { it.epochDay in monday..monday + 6 }.sumOf { it.distanceM },
            weekPlannedM = planned.filter { it.epochDay in monday..monday + 6 }.sumOf { it.totalM },
        )

        // ---- Next 7 days -------------------------------------------------------------------
        val hrCap = (hr.maxHr * 0.70).toInt()
        val days = (0..6).map { off ->
            val day = today + off
            val w = planned.firstOrNull { it.epochDay == day }
            if (w == null) {
                DayTarget(day, null, "Rest", 0, emptyList(), null, null, false)
            } else {
                val workout = runCatching { Workout.fromJson(w.json) }.getOrNull()
                val paces = workout?.let { paceTargets(it, cssNow, goalPace, easyDay && off == 0) } ?: emptyList()
                val last = swims.lastOrNull { s -> planned.any { it.matchedSwimId == s.swimId && it.sessionType == w.sessionType } }
                DayTarget(day, w.id, if (easyDay && off == 0) "${w.title} (keep it easy today)" else w.title, w.totalM, paces, hrCap,
                    last?.pace, w.status == WorkoutStatus.DONE)
            }
        }

        return GoalNav(goal, daysLeft, unit, lower, label, start, current, target, expected, projected, perWeek, perDay,
            status, text, actual, goalPace, volume, days)
    }

    private fun status(type: GoalType, lower: Boolean, current: Double?, target: Double?, expected: Double?, projected: Double?, unit: GoalUnit): Pair<GoalStatus, String> {
        if (current == null || target == null) return GoalStatus.UNKNOWN to when (type) {
            GoalType.TECHNIQUE -> "Log SWOLF on your swims (or let the watch export strokes) to track this goal."
            GoalType.TIME -> "Set a target time to track this goal."
            else -> "Not enough data yet."
        }
        val reached = if (lower) current <= target else current >= target
        if (reached) return GoalStatus.AHEAD to "Goal level reached. Now make it consistent."
        if (projected != null) {
            val ok = if (lower) projected <= target else projected >= target
            val miss = abs(projected - target)
            return if (ok) GoalStatus.ON_TRACK to "On track: your trend reaches ${fmt(projected, unit)} by goal day."
            else GoalStatus.BEHIND to "Behind: your trend reaches ${fmt(projected, unit)}, ${fmt(miss, unit)} short."
        }
        if (expected != null && type != GoalType.FITNESS) {
            val ahead = if (lower) current <= expected else current >= expected
            return (if (ahead) GoalStatus.ON_TRACK else GoalStatus.BEHIND) to
                "Today's checkpoint is ${fmt(expected, unit)}; you're at ${fmt(current, unit)}. Log a test to project your trend."
        }
        return GoalStatus.UNKNOWN to "Keep logging swims to measure progress."
    }

    fun fmt(v: Double, unit: GoalUnit): String = when (unit) {
        GoalUnit.SECONDS -> com.hamuk.swimpro.coach.fmtDuration(v)
        GoalUnit.METRES -> "%,.0f m".format(v)
        GoalUnit.MINUTES -> "%.0f min".format(v)
        GoalUnit.SWOLF -> "%.0f".format(v)
    }

    /** Warm-up, main-set and goal-pace targets for a planned workout at today's CSS. */
    fun paceTargets(w: Workout, css: Double, goalPace: Double?, easy: Boolean): List<PaceTarget> {
        val out = mutableListOf<PaceTarget>()
        w.blocks.firstOrNull { it.name.startsWith("Warm") }?.sets?.firstOrNull()?.let { s ->
            out += PaceTarget("Warm-up", "${s.reps} × ${s.distM} m", Zones.pacePer100(css, s.zone.coerceIn(1, 5)), s.zone)
        }
        w.blocks.filter { it.name.startsWith("Main") || it.name == "Test" || it.name == "Goal" }.flatMap { it.sets }
            .distinctBy { it.zone to it.distM }.take(3).forEach { s ->
                val zone = if (easy) minOf(s.zone, 2) else s.zone
                val pace = if (zone == 6) null else Zones.pacePer100(css, zone.coerceIn(1, 5))
                out += PaceTarget(if (zone == 6) "Time trial" else "Main set", "${if (s.reps > 1) "${s.reps} × " else ""}${s.distM} m", pace, zone)
            }
        if (goalPace != null && !easy && w.sessionType in setOf("CSS", "SPEED", "TEST", "GOAL")) {
            out += PaceTarget("Goal pace", "practise 2–4 × 50 m", goalPace, 4)
        }
        return out
    }
}
