package com.hamuk.swimpro.coach

import com.hamuk.swimpro.data.Goal
import com.hamuk.swimpro.data.GoalType
import com.hamuk.swimpro.data.Level
import com.hamuk.swimpro.data.Profile
import com.hamuk.swimpro.data.TestResult
import com.hamuk.swimpro.data.TestType
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class SessionType(val label: String) {
    ENDURANCE("Endurance intervals"),
    LONG_SWIM("Long continuous swim"),
    CSS("Threshold (CSS) set"),
    SPEED("Speed + aerobic"),
    AEROBIC("Aerobic pyramid"),
    TECHNIQUE("Technique"),
    RECOVERY("Easy recovery"),
    TEST("Test session"),
    GOAL("Goal attempt"),
}

data class WeekSpec(
    val programWeek: Int,
    val totalWeeks: Int,
    val phase: String,
    val deload: Boolean,
    val volumeFactor: Double,
    val hasTest: Boolean,
    val isFinal: Boolean,
) {
    /** 0.0 at the first week, 1.0 at the last. */
    val progress: Double get() = if (totalWeeks <= 1) 1.0 else (programWeek - 1).toDouble() / (totalWeeks - 1)
}

data class PlannedSession(val dayOffset: Int, val workout: Workout)

enum class Adaptation { NONE, REPEAT_WEEK, RECOVERY_WEEK, RETEST }

data class WeekReview(val planned: Int, val done: Int, val avgRpe: Double?, val fastSessions: Int)

/**
 * Deterministic plan generator. Methodology:
 *  - Pace zones from Critical Swim Speed (CSS = (T400 − T200) / 2), as popularised by Swim Smooth.
 *  - Beginners who can't swim 400 m continuously use a 100 m time trial + longest-continuous-swim
 *    benchmark instead, and move to a CSS test once 400 m is possible.
 *  - Periodisation: Base → Build → (Taper for time/distance goals), deload every 4th week,
 *    weekly volume increase well under 10 %, retest every 5 weeks.
 *  - Session structure (warm-up / drills / main / cool-down, send-off times) follows the
 *    conventions used in US Masters Swimming workouts.
 */
object PlanEngine {
    const val MIN_WEEKS = 4
    const val MAX_WEEKS = 26

    fun totalWeeks(startEpochDay: Long, targetEpochDay: Long): Int =
        (((targetEpochDay - startEpochDay) / 7) + 1).toInt().coerceIn(MIN_WEEKS, MAX_WEEKS)

    private fun hasTaper(type: GoalType) = type == GoalType.TIME || type == GoalType.DISTANCE

    fun weekSpec(
        programWeek: Int,
        totalWeeks: Int,
        goalType: GoalType,
        needsInitialTest: Boolean,
        forceDeload: Boolean = false,
        forceTest: Boolean = false,
    ): WeekSpec {
        val taperWeeks = if (hasTaper(goalType) && totalWeeks >= 6) 1 else 0
        val trainingWeeks = totalWeeks - taperWeeks
        val baseWeeks = ceil(trainingWeeks * 0.45).toInt()
        val isFinal = programWeek >= totalWeeks
        val phase = when {
            programWeek > trainingWeeks -> "Taper"
            programWeek <= baseWeeks -> "Base"
            else -> "Build"
        }
        val deload = forceDeload || (programWeek % 4 == 0 && phase != "Taper" && !isFinal)
        var factor = min(1.25, 0.85 + 0.04 * (programWeek - 1))
        if (deload) factor *= 0.75
        if (phase == "Taper") factor *= 0.65
        val hasTest = forceTest || (programWeek == 1 && needsInitialTest) ||
            (programWeek % 5 == 0 && !isFinal)
        return WeekSpec(programWeek, totalWeeks, phase, deload, factor, hasTest, isFinal)
    }

    // ---- Pace estimation ----------------------------------------------------------------

    fun cssFromTest(type: TestType, timeSec: Int, time200Sec: Int?, distanceM: Int): Double = when (type) {
        TestType.CSS -> {
            val t200 = time200Sec ?: (timeSec / 2)
            val css = (timeSec - t200) / 2.0
            if (css > 0) css else timeSec / 4.0
        }
        // A beginner's all-out 100 m is markedly faster than sustainable threshold pace.
        TestType.T100 -> timeSec * 1.12
        // A steady continuous swim is roughly endurance pace, a few seconds slower than CSS.
        TestType.CONTINUOUS -> timeSec.toDouble() / distanceM * 100.0 - 5.0
    }

    fun defaultCss(level: Level): Double = when (level) {
        Level.BEGINNER -> 165.0
        Level.INTERMEDIATE -> 120.0
        Level.ADVANCED -> 95.0
    }

    /**
     * Best available threshold pace (s/100 m): latest test, else recent swims' typical pace minus
     * [offset] (10 s for average pace, which includes wall rests; ~8 s for moving pace, since
     * steady swims sit around endurance zone, CSS + 9), else a level default.
     */
    fun estimateCss(tests: List<TestResult>, recentPaces: List<Double>, level: Level, offset: Double = 10.0): Double {
        tests.maxByOrNull { it.epochDay }?.let { return it.cssPer100 }
        if (recentPaces.size >= 2) {
            val sorted = recentPaces.sorted()
            return (sorted[sorted.size / 2] - offset).coerceAtLeast(60.0)
        }
        return defaultCss(level)
    }

    private fun raceOffset(distanceM: Int): Double = when {
        distanceM <= 50 -> -8.0
        distanceM <= 100 -> -5.0
        distanceM <= 200 -> -2.0
        distanceM <= 400 -> 0.0
        distanceM <= 800 -> 2.0
        distanceM <= 1500 -> 3.0
        else -> 5.0
    }

    fun predictTimeSec(cssPer100: Double, distanceM: Int): Double =
        (cssPer100 + raceOffset(distanceM)) * distanceM / 100.0

    fun requiredCss(targetTimeSec: Int, distanceM: Int): Double =
        targetTimeSec.toDouble() / distanceM * 100.0 - raceOffset(distanceM)

    fun recommendedTest(profile: Profile): TestType =
        if (profile.maxContinuousM >= 400) TestType.CSS else TestType.T100

    // ---- Week generation ---------------------------------------------------------------

    fun sessionTypes(goal: Goal, profile: Profile, spec: WeekSpec): List<SessionType> {
        val s = profile.sessionsPerWeek.coerceIn(1, 6)
        val base = when (goal.type) {
            GoalType.TIME -> if (spec.phase == "Base") {
                listOf(SessionType.CSS, SessionType.AEROBIC, SessionType.TECHNIQUE, SessionType.ENDURANCE, SessionType.AEROBIC, SessionType.TECHNIQUE)
            } else {
                listOf(SessionType.CSS, SessionType.ENDURANCE, SessionType.TECHNIQUE, SessionType.SPEED, SessionType.AEROBIC, SessionType.CSS)
            }
            GoalType.DISTANCE -> listOf(SessionType.LONG_SWIM, SessionType.TECHNIQUE, SessionType.ENDURANCE, SessionType.AEROBIC, SessionType.CSS, SessionType.ENDURANCE)
            GoalType.TECHNIQUE -> listOf(SessionType.TECHNIQUE, SessionType.AEROBIC, SessionType.TECHNIQUE, SessionType.ENDURANCE, SessionType.TECHNIQUE, SessionType.AEROBIC)
            GoalType.FITNESS -> listOf(SessionType.AEROBIC, SessionType.ENDURANCE, SessionType.TECHNIQUE, SessionType.CSS, SessionType.AEROBIC, SessionType.ENDURANCE)
        }
        val types = base.take(s).toMutableList()
        if (profile.level == Level.BEGINNER && SessionType.TECHNIQUE !in types && types.size >= 2) {
            types[types.lastIndex] = SessionType.TECHNIQUE
        }
        if (spec.deload && types.size >= 2) {
            val hard = types.indexOfFirst { it == SessionType.SPEED || it == SessionType.CSS }
            types[if (hard >= 0) hard else 1] = SessionType.RECOVERY
        }
        if (spec.hasTest) types[0] = SessionType.TEST
        if (spec.isFinal && hasTaper(goal.type) && goal.targetDistanceM != null) {
            types[types.lastIndex] = SessionType.GOAL
        }
        return types
    }

    fun dayOffsets(sessions: Int): List<Int> = when (sessions.coerceIn(1, 6)) {
        1 -> listOf(2)
        2 -> listOf(1, 4)
        3 -> listOf(0, 2, 4)
        4 -> listOf(0, 1, 3, 5)
        5 -> listOf(0, 1, 2, 4, 5)
        else -> listOf(0, 1, 2, 3, 4, 5)
    }

    fun metresPerMinute(level: Level): Int = when (level) {
        Level.BEGINNER -> 20
        Level.INTERMEDIATE -> 32
        Level.ADVANCED -> 45
    }

    fun buildWeek(goal: Goal, profile: Profile, cssPer100: Double, spec: WeekSpec): List<PlannedSession> {
        val types = sessionTypes(goal, profile, spec)
        val offsets = dayOffsets(types.size)
        val budget = profile.minutesPerSession * metresPerMinute(profile.level) * spec.volumeFactor
        return types.mapIndexed { i, type ->
            val ctx = BuildCtx(profile, goal, cssPer100, spec, rotation = spec.programWeek * 3 + i)
            PlannedSession(offsets[i], buildWorkout(type, budget, ctx))
        }
    }

    // ---- Workout builder ---------------------------------------------------------------

    data class BuildCtx(
        val profile: Profile,
        val goal: Goal,
        val cssPer100: Double,
        val spec: WeekSpec,
        val rotation: Int,
    ) {
        val unit: Int get() = if (profile.poolLengthM >= 50) 50 else 25
        val level: Level get() = profile.level
        val focusStroke: String get() = focusStrokeOf(goal)
        fun round(m: Double): Int = max(unit, (m / unit).roundToInt() * unit)
    }

    fun focusStrokeOf(goal: Goal): String {
        val f = goal.focus?.lowercase() ?: return "Freestyle"
        return when {
            "breast" in f -> "Breaststroke"
            "back" in f -> "Backstroke"
            "fly" in f || "butterfly" in f -> "Butterfly"
            else -> "Freestyle"
        }
    }

    fun buildWorkout(type: SessionType, budgetM: Double, ctx: BuildCtx): Workout {
        val unit = ctx.unit
        val beginner = ctx.level == Level.BEGINNER
        val warm = ctx.round(budgetM * 0.2).coerceIn(4 * unit, 400)
        val cool = ctx.round(budgetM * 0.1).coerceIn(2 * unit, 200)

        val warmUp = if (beginner) {
            Block("Warm-up", listOf(
                SwimSet(reps = max(2, warm / (2 * unit)), distM = 2 * unit, stroke = "Choice", zone = 1, restSec = 20,
                    note = "Relaxed, long strokes. Exhale fully underwater."),
            ))
        } else {
            Block("Warm-up", listOf(
                SwimSet(1, max(2 * unit, warm - 4 * unit), "Choice", 1, 15),
                SwimSet(4, unit, "Freestyle", 3, 15, note = "Build speed each length"),
            ))
        }
        val coolDown = Block("Cool-down", listOf(SwimSet(1, cool, "Choice", 1, 0, note = "Very easy, mix strokes")))

        if (type == SessionType.TEST) return testWorkout(ctx, warmUp, coolDown)
        if (type == SessionType.GOAL) return goalWorkout(ctx, warmUp, coolDown)

        val drillStroke = if (type == SessionType.TECHNIQUE) ctx.focusStroke else "Freestyle"
        val drills = DrillLibrary.rotate(drillStroke, ctx.rotation, 2)
        val drillReps = if (beginner) 2 else 3
        val drillBlock = Block("Drills", drills.map {
            SwimSet(drillReps, unit, it.stroke, 0, 20, drill = it.name, note = it.cue)
        })

        val main = max(2 * unit, ((budgetM - warmUp.totalM - drillBlock.totalM - coolDown.totalM) / unit).toInt() * unit)
        val p = ctx.spec.progress
        val mainBlocks: List<Block>
        val focus: String
        when (type) {
            SessionType.ENDURANCE -> {
                val (start, end) = ladderRange(ctx)
                val rep = ctx.round(start + (end - start) * p).coerceAtMost(max(2 * unit, main))
                val rest = (30 - 20 * p).roundToInt().coerceIn(10, 30)
                mainBlocks = listOf(Block("Main", listOf(
                    SwimSet(max(2, main / rep), rep, "Freestyle", 2, rest,
                        note = "Even pace on every rep — the last should feel like the first."),
                )))
                focus = "Aerobic endurance"
            }
            SessionType.LONG_SWIM -> {
                val goalDist = ctx.goal.targetDistanceM ?: 1000
                val startDist = max(ctx.profile.maxContinuousM, 2 * unit)
                var target = ctx.round(startDist + (goalDist * 0.9 - startDist) * p).coerceAtMost(goalDist)
                if (ctx.spec.deload) target = ctx.round(target * 0.8)
                val sets = mutableListOf(
                    SwimSet(1, target, "Freestyle", 2, 0,
                        note = "Continuous at a pace you could hold all day. If you must stop, rest 15–20 s at the wall, then carry on and note where you stopped."),
                )
                val extra = main - target
                if (extra >= 4 * unit) sets += SwimSet(extra / (2 * unit), 2 * unit, "Freestyle", 2, 20)
                mainBlocks = listOf(Block("Main", sets))
                focus = "Continuous distance"
            }
            SessionType.CSS -> {
                val rep = when (ctx.level) { Level.BEGINNER -> 2 * unit; Level.INTERMEDIATE -> 100; Level.ADVANCED -> 200 }
                val rest = when (ctx.level) { Level.BEGINNER -> 20; Level.INTERMEDIATE -> 15; Level.ADVANCED -> 20 }
                val zone = if (beginner && ctx.spec.phase == "Base") 3 else 4
                mainBlocks = listOf(Block("Main", listOf(
                    SwimSet(max(3, main / rep), rep, "Freestyle", zone, rest,
                        note = "Hit the target pace on every rep. Too hard to hold? Add 5 s rest rather than slowing down."),
                )))
                focus = "Threshold pace"
            }
            SessionType.SPEED -> {
                val sprints = (main * 0.3 / unit).roundToInt().coerceIn(4, 10)
                val aerobic = main - sprints * unit
                mainBlocks = listOf(Block("Main", buildList {
                    add(SwimSet(sprints, unit, "Freestyle", 5, 40, note = "Fast but smooth — hold good form, full recovery."))
                    if (aerobic >= 2 * unit) add(SwimSet(aerobic / (2 * unit), 2 * unit, "Freestyle", 2, 15))
                }))
                focus = "Speed & form at pace"
            }
            SessionType.AEROBIC -> {
                val steps = when (ctx.level) {
                    Level.BEGINNER -> listOf(1, 2, 3, 2, 1)
                    Level.INTERMEDIATE -> listOf(2, 4, 6, 4, 2)
                    Level.ADVANCED -> listOf(4, 8, 12, 8, 4)
                }.map { it * unit }
                val rounds = max(1, main / steps.sum())
                val pyramid = steps.mapIndexed { i, d ->
                    SwimSet(1, d, "Freestyle", if (i == steps.size / 2) 3 else 2, 15)
                }
                mainBlocks = List(rounds) { r -> Block(if (rounds > 1) "Main — pyramid ${r + 1}/$rounds" else "Main — pyramid", pyramid) }
                focus = "Aerobic base"
            }
            SessionType.TECHNIQUE -> {
                val stroke = ctx.focusStroke
                val focusDrills = DrillLibrary.rotate(stroke, ctx.rotation + 2, 3)
                val pairs = max(2, (main * 0.6 / (2 * unit)).roundToInt())
                val counting = max(2, ((main - pairs * 2 * unit) / unit.toDouble()).roundToInt())
                mainBlocks = listOf(Block("Main", focusDrills.mapIndexed { i, d ->
                    SwimSet(max(1, pairs / focusDrills.size + if (i == 0) pairs % focusDrills.size else 0), 2 * unit, stroke, 2, 20,
                        note = "Length 1: ${d.name} drill. Length 2: swim, keeping the feel — ${d.cue}")
                } + SwimSet(counting, unit, stroke, 2, 20,
                    note = "Count strokes each length. Try to take 1–2 fewer at the same effort (lower SWOLF).")))
                focus = "$stroke technique"
            }
            SessionType.RECOVERY -> {
                val kick = 4 * unit
                mainBlocks = listOf(Block("Main", listOf(
                    SwimSet(max(1, (main - kick) / (4 * unit)), 4 * unit, "Choice", 1, 20, note = "Easy. Focus on breathing rhythm."),
                    SwimSet(4, unit, "Kick", 1, 20, note = "Kick with a board, relaxed ankles."),
                )))
                focus = "Recovery"
            }
            SessionType.TEST, SessionType.GOAL -> error("handled above")
        }
        val blocks = listOf(warmUp, drillBlock) + mainBlocks + coolDown
        return Workout(type.label, type.name, focus, blocks)
    }

    /** Rep length range for endurance intervals over the plan. */
    private fun ladderRange(ctx: BuildCtx): Pair<Double, Double> {
        val unit = ctx.unit.toDouble()
        if (ctx.goal.type == GoalType.DISTANCE && ctx.goal.targetDistanceM != null) {
            val start = max(2 * unit, ctx.profile.maxContinuousM * 0.5)
            return start to max(start, ctx.goal.targetDistanceM * 0.4)
        }
        return when (ctx.level) {
            Level.BEGINNER -> 2 * unit to 4 * unit
            Level.INTERMEDIATE -> 100.0 to 200.0
            Level.ADVANCED -> 200.0 to 400.0
        }
    }

    private fun testWorkout(ctx: BuildCtx, warmUp: Block, coolDown: Block): Workout {
        val unit = ctx.unit
        return if (recommendedTest(ctx.profile) == TestType.CSS) {
            Workout(
                SessionType.TEST.label, SessionType.TEST.name, "CSS test",
                listOf(
                    warmUp,
                    Block("Test", listOf(
                        SwimSet(1, 400, "Freestyle", 6, 300, note = "400 m TIME TRIAL — best even-paced effort. Record your time."),
                        SwimSet(1, 2 * unit, "Choice", 1, 0, note = "Very easy recovery, then rest until ready (5–8 min total)."),
                        SwimSet(1, 200, "Freestyle", 6, 0, note = "200 m TIME TRIAL — all-out but controlled. Record your time."),
                    )),
                    coolDown,
                ),
                notes = "Enter both times in Coach → Log test. CSS pace = (T400 − T200) ÷ 2 per 100 m.",
            )
        } else {
            val cont = ctx.round(max(ctx.profile.maxContinuousM + 2.0 * unit, 100.0)).coerceAtMost(400)
            Workout(
                SessionType.TEST.label, SessionType.TEST.name, "Benchmark test",
                listOf(
                    warmUp,
                    Block("Test", listOf(
                        SwimSet(1, 100, "Freestyle", 6, 240, note = "100 m TIME TRIAL — fast but with good form. Record your time. Rest 3–5 min."),
                        SwimSet(1, cont, "Freestyle", 6, 0,
                            note = "LONGEST CONTINUOUS SWIM: steady, comfortable pace, as far as you can up to $cont m. Record distance and time."),
                    )),
                    coolDown,
                ),
                notes = "Log both results in Coach → Log test. Once you can swim 400 m without stopping the coach switches you to the CSS test.",
            )
        }
    }

    private fun goalWorkout(ctx: BuildCtx, warmUp: Block, coolDown: Block): Workout {
        val d = ctx.goal.targetDistanceM ?: 400
        val note = when (ctx.goal.type) {
            GoalType.TIME -> "GOAL SWIM: ${d} m for time — target ${ctx.goal.targetTimeSec?.let { fmtDuration(it.toDouble()) } ?: "best"}. Start controlled, build in the second half."
            else -> "GOAL SWIM: ${d} m without stopping. Settle into an all-day pace from the first length."
        }
        return Workout(
            SessionType.GOAL.label, SessionType.GOAL.name, "Goal attempt",
            listOf(warmUp, Block("Goal", listOf(SwimSet(1, d, "Freestyle", 6, 0, note = note))), coolDown),
            notes = "Log it as a test afterwards so your progress chart updates.",
        )
    }

    // ---- Adaptation & matching ---------------------------------------------------------

    fun adapt(review: WeekReview, wasDeload: Boolean): Adaptation = when {
        review.planned > 0 && review.done.toDouble() / review.planned < 0.5 && !wasDeload -> Adaptation.REPEAT_WEEK
        (review.avgRpe ?: 0.0) >= 8.5 -> Adaptation.RECOVERY_WEEK
        review.fastSessions >= 2 -> Adaptation.RETEST
        else -> Adaptation.NONE
    }

    /**
     * Greedily matches swims to planned workouts within ±2 days, closest date first.
     * [planned] and [swims] are (id, epochDay) pairs. Returns workoutId → swimId.
     */
    fun matchSwims(planned: List<Pair<Long, Long>>, swims: List<Pair<Long, Long>>): Map<Long, Long> {
        val result = mutableMapOf<Long, Long>()
        for ((swimId, swimDay) in swims.sortedBy { it.second }) {
            val best = planned
                .filter { it.first !in result && abs(it.second - swimDay) <= 2 }
                .minWithOrNull(compareBy({ abs(it.second - swimDay) }, { it.second }))
                ?: continue
            result[best.first] = swimId
        }
        return result
    }
}
