package com.hamuk.swimpro.coach

import com.hamuk.swimpro.data.AppDatabase
import com.hamuk.swimpro.data.Goal
import com.hamuk.swimpro.data.GoalType
import com.hamuk.swimpro.data.PlanWeek
import com.hamuk.swimpro.data.PlannedWorkout
import com.hamuk.swimpro.data.Profile
import com.hamuk.swimpro.data.Swim
import com.hamuk.swimpro.data.TestResult
import com.hamuk.swimpro.data.TestType
import com.hamuk.swimpro.data.WorkoutStatus
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun Swim.localDate(): LocalDate = Instant.ofEpochMilli(startMs).atZone(ZoneId.systemDefault()).toLocalDate()

fun mondayOf(date: LocalDate): LocalDate = date.with(DayOfWeek.MONDAY)

/** Owns the goal, tests and plan rows; turns PlanEngine output into stored weeks and adapts them. */
class CoachRepository(private val db: AppDatabase) {
    private val dao = db.coach()

    val profile = dao.observeProfile()
    val goal = dao.observeActiveGoal()
    val tests = dao.observeTests()
    fun weeks(goalId: Long) = dao.observeWeeks(goalId)
    fun workouts(goalId: Long) = dao.observeWorkouts(goalId)
    fun workout(id: Long) = dao.observeWorkout(id)

    suspend fun profileOrDefault(): Profile = dao.profile() ?: Profile()
    suspend fun activeGoal(): Goal? = dao.activeGoal()
    suspend fun allTests(): List<TestResult> = dao.tests()

    suspend fun saveProfile(profile: Profile, regenerate: Boolean = true) {
        dao.saveProfile(profile)
        if (regenerate) regenerateFuture("Plan updated for your new schedule.")
    }

    suspend fun currentCss(): Double {
        val profile = profileOrDefault()
        val cutoff = System.currentTimeMillis() - 56L * 24 * 3600 * 1000
        val swims = db.swims().since(cutoff).filter { it.distanceM >= 200 && !it.openWater }
        // Prefer moving pace (lengths only, no wall rests) when the watch reports lengths.
        val moving = swims.mapNotNull { s ->
            val laps = db.swims().laps(s.id).filter { it.kind == "lap" && (it.distanceM ?: 0.0) > 0 }
            val dist = laps.sumOf { it.distanceM!! }
            if (laps.size >= 2 && dist >= s.distanceM * 0.8) laps.sumOf { it.seconds } / dist * 100 else null
        }
        return if (moving.size >= 2) PlanEngine.estimateCss(dao.tests(), moving, profile.level, offset = 8.0)
        else PlanEngine.estimateCss(dao.tests(), swims.mapNotNull { it.pacePer100 }, profile.level)
    }

    /** Default plan start: this Monday early in the week, otherwise next Monday. */
    fun defaultStart(today: LocalDate = LocalDate.now()): LocalDate =
        if (today.dayOfWeek <= DayOfWeek.WEDNESDAY) mondayOf(today) else mondayOf(today).plusWeeks(1)

    suspend fun setGoal(goal: Goal): Long {
        dao.deactivateGoals()
        val id = dao.insertGoal(goal.copy(id = 0, active = true))
        val saved = dao.activeGoal()!!
        generateFrom(saved, LocalDate.ofEpochDay(saved.startEpochDay), 1, note = "Plan created.")
        onSwimsSynced()
        return id
    }

    suspend fun endGoal() = dao.deactivateGoals()

    private suspend fun generateFrom(
        goal: Goal,
        weekStart: LocalDate,
        fromProgramWeek: Int,
        forceDeloadFirst: Boolean = false,
        forceTestFirst: Boolean = false,
        note: String? = null,
    ) {
        dao.deleteWeeksFrom(goal.id, weekStart.toEpochDay())
        val profile = profileOrDefault()
        val css = currentCss()
        val existing = dao.weeks(goal.id)
        val total = existing.lastOrNull()?.totalWeeks ?: PlanEngine.totalWeeks(goal.startEpochDay, goal.targetEpochDay)
        val recentTest = dao.tests().any { it.epochDay >= LocalDate.now().minusDays(21).toEpochDay() }
        var start = weekStart
        for (pw in fromProgramWeek..total) {
            val first = pw == fromProgramWeek
            val spec = PlanEngine.weekSpec(
                pw, total, goal.type,
                needsInitialTest = !recentTest,
                forceDeload = first && forceDeloadFirst,
                forceTest = first && forceTestFirst,
            )
            val weekId = dao.insertWeek(
                PlanWeek(
                    goalId = goal.id, programWeek = pw, totalWeeks = total,
                    startEpochDay = start.toEpochDay(), phase = spec.phase, deload = spec.deload,
                    note = if (first) note else null,
                )
            )
            val sessions = PlanEngine.buildWeek(goal, profile, css, spec)
            val days = scheduleDays(start, sessions.map { it.dayOffset })
            dao.insertWorkouts(sessions.mapIndexed { i, it ->
                PlannedWorkout(
                    weekId = weekId, goalId = goal.id,
                    epochDay = days[i],
                    sessionType = it.workout.sessionType, title = it.workout.title,
                    json = it.workout.toJson(), totalM = it.workout.totalM,
                )
            })
            start = start.plusWeeks(1)
        }
    }

    /**
     * Session dates for a week starting [monday]. If that's the current week and some default
     * days have already passed, the sessions are spread over the days that remain instead.
     */
    private fun scheduleDays(monday: LocalDate, offsets: List<Int>): List<Long> {
        val today = LocalDate.now().toEpochDay()
        val default = offsets.map { monday.plusDays(it.toLong()).toEpochDay() }
        if (default.none { it < today } || today > monday.toEpochDay() + 6) return default
        val remaining = (monday.toEpochDay() + 7 - today).toInt()
        return offsets.indices.map { i -> today + (i * remaining / offsets.size) }
    }

    /**
     * Rebuilds the plan from the current week (if nothing has been done in it yet) or from next
     * week, keeping completed history intact.
     */
    private suspend fun regenerateFuture(
        note: String,
        forceDeloadFirst: Boolean = false,
        forceTestFirst: Boolean = false,
        repeatCurrent: Boolean = false,
    ): Boolean {
        val goal = dao.activeGoal() ?: return false
        val weeks = dao.weeks(goal.id)
        if (weeks.isEmpty()) return false
        val thisMonday = mondayOf(LocalDate.now()).toEpochDay()
        val current = weeks.firstOrNull { it.startEpochDay == thisMonday }
        if (current == null) {
            val firstFuture = weeks.firstOrNull { it.startEpochDay > thisMonday } ?: return false
            generateFrom(goal, LocalDate.ofEpochDay(firstFuture.startEpochDay), firstFuture.programWeek, forceDeloadFirst, forceTestFirst, note)
            return true
        }
        val anyDone = dao.workouts(goal.id).any { it.weekId == current.id && it.status == WorkoutStatus.DONE }
        if (anyDone) {
            val pw = if (repeatCurrent) current.programWeek else current.programWeek + 1
            if (pw > current.totalWeeks) return false
            generateFrom(goal, LocalDate.ofEpochDay(thisMonday).plusWeeks(1), pw, forceDeloadFirst, forceTestFirst, note)
        } else {
            generateFrom(goal, LocalDate.ofEpochDay(thisMonday), current.programWeek, forceDeloadFirst, forceTestFirst, note)
        }
        return true
    }

    suspend fun addTest(test: TestResult) {
        dao.insertTest(test)
        val profile = profileOrDefault()
        val continuous = when (test.type) {
            TestType.CONTINUOUS -> test.distanceM
            TestType.CSS -> 400
            TestType.T100 -> 100
        }
        if (continuous > profile.maxContinuousM) dao.saveProfile(profile.copy(maxContinuousM = continuous))
        regenerateFuture("Zones updated from your ${test.type.label()} on ${test.epochDay.fmtDay()}.")
    }

    suspend fun deleteTest(test: TestResult) = dao.deleteTest(test)

    suspend fun setWorkoutStatus(id: Long, status: WorkoutStatus) {
        val w = dao.workout(id) ?: return
        dao.updateWorkout(w.copy(status = status, matchedSwimId = if (status == WorkoutStatus.DONE) w.matchedSwimId else null))
    }

    suspend fun swapWorkout(id: Long, type: SessionType): String {
        val w = dao.workout(id) ?: return "Workout $id not found"
        val goal = dao.activeGoal() ?: return "No active goal"
        val week = dao.weeks(goal.id).firstOrNull { it.id == w.weekId } ?: return "Week not found"
        val profile = profileOrDefault()
        val spec = PlanEngine.weekSpec(week.programWeek, week.totalWeeks, goal.type, false, forceDeload = week.deload)
        val budget = profile.minutesPerSession * PlanEngine.metresPerMinute(profile.level) * spec.volumeFactor
        val workout = PlanEngine.buildWorkout(type, budget, PlanEngine.BuildCtx(profile, goal, currentCss(), spec, rotation = (w.id % 7).toInt()))
        dao.updateWorkout(w.copy(sessionType = workout.sessionType, title = workout.title, json = workout.toJson(), totalM = workout.totalM))
        return "Replaced with ${type.label} (${workout.totalM} m)"
    }

    suspend fun scheduleTest(): String =
        if (regenerateFuture("Test scheduled by your coach.", forceTestFirst = true)) "Test added to the plan" else "No active plan to update"

    suspend fun addRecoveryWeek(): String =
        if (regenerateFuture("Recovery week added by your coach.", forceDeloadFirst = true)) "Recovery week added" else "No active plan to update"

    /** Matches new swims to planned workouts, then reviews finished weeks and adapts the plan. */
    suspend fun onSwimsSynced() {
        val goal = dao.activeGoal() ?: return
        val today = LocalDate.now()
        val workouts = dao.workouts(goal.id)
        val used = dao.matchedSwimIds().toSet()
        val swims = db.swims().since(LocalDate.ofEpochDay(goal.startEpochDay - 2)
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
            .filter { it.id !in used }
        val matches = PlanEngine.matchSwims(
            workouts.filter { it.status == WorkoutStatus.PLANNED && it.epochDay <= today.toEpochDay() + 2 }.map { it.id to it.epochDay },
            swims.map { it.id to it.localDate().toEpochDay() },
        )
        for ((wid, sid) in matches) {
            workouts.first { it.id == wid }.let { dao.updateWorkout(it.copy(status = WorkoutStatus.DONE, matchedSwimId = sid)) }
        }
        evaluateWeeks(goal, today)
    }

    private suspend fun evaluateWeeks(goal: Goal, today: LocalDate) {
        val weeks = dao.weeks(goal.id).filter { !it.evaluated && it.startEpochDay + 7 <= today.toEpochDay() }
        if (weeks.isEmpty()) return
        val css = currentCss()
        val workouts = dao.workouts(goal.id)
        var adaptation = Adaptation.NONE
        var adaptedWeek: PlanWeek? = null
        for (week in weeks) {
            val inWeek = workouts.filter { it.weekId == week.id }
            inWeek.filter { it.status == WorkoutStatus.PLANNED }.forEach {
                dao.updateWorkout(it.copy(status = WorkoutStatus.SKIPPED))
            }
            val matched = inWeek.mapNotNull { w -> w.matchedSwimId?.let { db.swims().get(it) } }
            val review = WeekReview(
                planned = inWeek.size,
                done = inWeek.count { it.status == WorkoutStatus.DONE },
                avgRpe = matched.mapNotNull { it.rpe }.takeIf { it.isNotEmpty() }?.average(),
                fastSessions = matched.count { s -> s.pacePer100?.let { it < css - 3 } == true },
            )
            val summary = "Done ${review.done}/${review.planned}" + (review.avgRpe?.let { " · avg RPE %.1f".format(it) } ?: "")
            dao.updateWeek(week.copy(evaluated = true, note = listOfNotNull(week.note, summary).joinToString(" · ")))
            // Only the most recently finished week drives a change; older backlog is just recorded.
            if (week.startEpochDay + 14 > today.toEpochDay()) {
                adaptation = PlanEngine.adapt(review, week.deload)
                adaptedWeek = week
            }
        }
        when (adaptation) {
            Adaptation.REPEAT_WEEK -> {
                val w = adaptedWeek!!
                val nextMonday = LocalDate.ofEpochDay(w.startEpochDay).plusWeeks(1)
                if (!doneInWeekStarting(goal, nextMonday)) generateFrom(
                    goal, nextMonday, w.programWeek,
                    note = "Repeating week ${w.programWeek}: fewer than half its sessions were completed.",
                )
            }
            Adaptation.RECOVERY_WEEK -> regenerateFuture("Recovery week: last week felt very hard (RPE ≥ 8.5).", forceDeloadFirst = true)
            Adaptation.RETEST -> regenerateFuture("Re-test scheduled: you're swimming faster than your current zones.", forceTestFirst = true)
            Adaptation.NONE -> Unit
        }
    }

    private suspend fun doneInWeekStarting(goal: Goal, monday: LocalDate): Boolean {
        val week = dao.weeks(goal.id).firstOrNull { it.startEpochDay == monday.toEpochDay() } ?: return false
        return dao.workouts(goal.id).any { it.weekId == week.id && it.status == WorkoutStatus.DONE }
    }

    /** Plain-text snapshot of everything the AI coach needs to know. */
    suspend fun contextSummary(): String = buildString {
        val today = LocalDate.now()
        val profile = profileOrDefault()
        val css = currentCss()
        val tests = dao.tests()
        appendLine("Today: $today (${today.dayOfWeek})")
        appendLine()
        appendLine("## Athlete profile")
        appendLine("Level: ${profile.level}; ${profile.sessionsPerWeek} sessions/week × ${profile.minutesPerSession} min; pool ${profile.poolLengthM} m; longest continuous swim ${profile.maxContinuousM} m")
        appendLine("Threshold/CSS pace: ${fmtPace(css)}/100m (${if (tests.isEmpty()) "estimated — no test yet" else "from test"})")
        appendLine("Zones: " + Zones.describe(css).joinToString("; ") { "${it.first} ${it.second}" })
        appendLine()
        appendLine("## Tests (newest first)")
        if (tests.isEmpty()) appendLine("none")
        tests.take(6).forEach {
            appendLine("- ${it.epochDay.fmtDay()} ${it.type.label()}: ${testDescription(it)} → CSS ${fmtPace(it.cssPer100)}")
        }
        appendLine()
        val goal = dao.activeGoal()
        appendLine("## Goal")
        if (goal == null) {
            appendLine("No active goal yet.")
        } else {
            appendLine(goalDescription(goal))
            goal.targetDistanceM?.let { d ->
                if (goal.type == GoalType.TIME) {
                    appendLine("Predicted ${d}m time now: ${fmtDuration(PlanEngine.predictTimeSec(css, d))}; CSS needed ≈ ${fmtPace(PlanEngine.requiredCss(goal.targetTimeSec ?: 0, d))}")
                }
            }
            val weeks = dao.weeks(goal.id)
            val workouts = dao.workouts(goal.id)
            val thisMonday = mondayOf(today).toEpochDay()
            appendLine()
            appendLine("## Plan (week id → workouts; workout_id used by functions)")
            weeks.filter { it.startEpochDay >= thisMonday - 14 && it.startEpochDay <= thisMonday + 7 }.forEach { w ->
                appendLine("Week ${w.programWeek}/${w.totalWeeks} starting ${w.startEpochDay.fmtDay()} — ${w.phase}${if (w.deload) " (recovery)" else ""}${w.note?.let { " — $it" } ?: ""}")
                workouts.filter { it.weekId == w.id }.forEach { p ->
                    appendLine("  - workout_id=${p.id} ${p.epochDay.fmtDay()} ${p.title} ${p.totalM}m [${p.status}]")
                }
            }
            val done = workouts.count { it.status == WorkoutStatus.DONE }
            val past = workouts.count { it.epochDay < today.toEpochDay() }
            appendLine("Overall adherence: $done done of $past past sessions")
        }
        appendLine()
        appendLine("## Swims, last 8 weeks (newest first)")
        val cutoff = System.currentTimeMillis() - 56L * 24 * 3600 * 1000
        val swims = db.swims().since(cutoff)
        if (swims.isEmpty()) appendLine("none recorded")
        swims.take(30).forEach { appendLine("- " + swimLine(it)) }
    }

    suspend fun swimDetail(swim: Swim): String = buildString {
        appendLine(swimLine(swim))
        val laps = db.swims().laps(swim.id)
        if (laps.isNotEmpty()) {
            appendLine("Laps/segments (${laps.size}):")
            laps.take(80).forEach { l ->
                val pace = l.distanceM?.takeIf { it > 0 }?.let { " ${fmtPace(l.seconds / it * 100)}/100" } ?: ""
                appendLine("  #${l.idx + 1} ${l.distanceM?.toInt() ?: "?"}m ${l.seconds.toInt()}s$pace ${l.stroke ?: ""}${l.strokes?.let { " strokes=$it swolf=${l.swolf}" } ?: ""}")
            }
        }
    }

    companion object {
        private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM")
        fun Long.fmtDay(): String = LocalDate.ofEpochDay(this).format(dayFmt)
        fun TestType.label() = when (this) {
            TestType.CSS -> "CSS test"
            TestType.T100 -> "100 m time trial"
            TestType.CONTINUOUS -> "continuous swim"
        }

        fun testDescription(t: TestResult) = when (t.type) {
            TestType.CSS -> "400m ${fmtDuration(t.timeSec.toDouble())}, 200m ${fmtDuration((t.time200Sec ?: 0).toDouble())}"
            TestType.T100 -> "100m ${fmtDuration(t.timeSec.toDouble())}"
            TestType.CONTINUOUS -> "${t.distanceM}m in ${fmtDuration(t.timeSec.toDouble())}"
        }

        fun goalDescription(g: Goal): String {
            val by = "by ${g.targetEpochDay.fmtDay()} ${LocalDate.ofEpochDay(g.targetEpochDay).year}"
            return when (g.type) {
                GoalType.TIME -> "Swim ${g.targetDistanceM}m in ${g.targetTimeSec?.let { fmtDuration(it.toDouble()) }} $by"
                GoalType.DISTANCE -> "Swim ${g.targetDistanceM}m without stopping $by"
                GoalType.TECHNIQUE -> "Technique: ${g.focus ?: "freestyle"} $by"
                GoalType.FITNESS -> "Fitness: ${g.weeklyMinutes ?: "regular"} min/week $by"
            } + (g.focus?.takeIf { g.type != GoalType.TECHNIQUE }?.let { " ($it)" } ?: "")
        }

        fun swimLine(s: Swim): String {
            val date = s.localDate().toEpochDay().fmtDay()
            return listOfNotNull(
                "id=${s.id} $date",
                "${s.distanceM.toInt()}m",
                fmtDuration(s.activeSec.toDouble()),
                s.pacePer100?.let { "${fmtPace(it)}/100" },
                s.avgHr?.let { "HR avg $it/max ${s.maxHr}" },
                s.strokeSummary,
                s.manualSwolf?.let { "SWOLF $it" },
                s.rpe?.let { "RPE $it" },
                s.notes?.takeIf { it.isNotBlank() }?.let { "notes: $it" },
            ).joinToString(", ")
        }
    }
}
