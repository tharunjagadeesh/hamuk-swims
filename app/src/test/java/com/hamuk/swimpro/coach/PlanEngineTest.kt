package com.hamuk.swimpro.coach

import com.hamuk.swimpro.data.Goal
import com.hamuk.swimpro.data.GoalType
import com.hamuk.swimpro.data.Level
import com.hamuk.swimpro.data.Profile
import com.hamuk.swimpro.data.TestResult
import com.hamuk.swimpro.data.TestType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanEngineTest {
    private val beginner = Profile(level = Level.BEGINNER, sessionsPerWeek = 3, minutesPerSession = 40, poolLengthM = 25, maxContinuousM = 100)

    private fun goal(type: GoalType, distance: Int? = null, time: Int? = null, focus: String? = null) =
        Goal(type = type, targetDistanceM = distance, targetTimeSec = time, focus = focus, startEpochDay = 0, targetEpochDay = 7L * 12 - 1)

    @Test fun cssFormula() {
        // 400 in 8:00, 200 in 3:40 → (480 − 220) / 2 = 130 s per 100 m
        assertEquals(130.0, PlanEngine.cssFromTest(TestType.CSS, 480, 220, 400), 1e-9)
        assertEquals(112.0, PlanEngine.cssFromTest(TestType.T100, 100, null, 100), 1e-9)
        assertEquals(145.0, PlanEngine.cssFromTest(TestType.CONTINUOUS, 600, null, 400), 1e-9)
    }

    @Test fun estimateCssPrefersLatestTest() {
        val tests = listOf(
            TestResult(epochDay = 1, type = TestType.T100, timeSec = 120, distanceM = 100, cssPer100 = 134.4),
            TestResult(epochDay = 9, type = TestType.T100, timeSec = 110, distanceM = 100, cssPer100 = 123.2),
        )
        assertEquals(123.2, PlanEngine.estimateCss(tests, listOf(200.0, 210.0), Level.BEGINNER), 1e-9)
        assertEquals(195.0, PlanEngine.estimateCss(emptyList(), listOf(200.0, 205.0, 210.0), Level.BEGINNER), 1e-9)
        assertEquals(165.0, PlanEngine.estimateCss(emptyList(), emptyList(), Level.BEGINNER), 1e-9)
    }

    @Test fun totalWeeksIsClamped() {
        assertEquals(12, PlanEngine.totalWeeks(0, 83))
        assertEquals(PlanEngine.MIN_WEEKS, PlanEngine.totalWeeks(0, 3))
        assertEquals(PlanEngine.MAX_WEEKS, PlanEngine.totalWeeks(0, 1000))
    }

    @Test fun periodisationAndVolumeProgression() {
        val specs = (1..12).map { PlanEngine.weekSpec(it, 12, GoalType.TIME, needsInitialTest = true) }
        assertTrue(specs[0].hasTest)
        assertEquals("Base", specs[0].phase)
        assertEquals("Taper", specs[11].phase)
        assertTrue(specs[11].isFinal)
        assertEquals(listOf(4, 8), specs.filter { it.deload }.map { it.programWeek })
        assertTrue(specs.any { it.phase == "Build" })
        // No week jumps more than 10 % over the previous normal week.
        var prev = specs[0].volumeFactor
        for (s in specs.drop(1)) {
            if (s.deload || s.phase == "Taper") continue
            assertTrue("week ${s.programWeek}", s.volumeFactor <= prev * 1.10 + 1e-9)
            prev = s.volumeFactor
        }
    }

    @Test fun beginnerWeekHasTechniqueAndRightSessionCount() {
        val g = goal(GoalType.TIME, 400, 600)
        val spec = PlanEngine.weekSpec(2, 12, g.type, needsInitialTest = false)
        val week = PlanEngine.buildWeek(g, beginner.copy(sessionsPerWeek = 2), 150.0, spec)
        assertEquals(2, week.size)
        assertTrue(week.any { it.workout.sessionType == SessionType.TECHNIQUE.name })
    }

    @Test fun testWeekAndGoalWeek() {
        val g = goal(GoalType.DISTANCE, 1000)
        val first = PlanEngine.buildWeek(g, beginner, 160.0, PlanEngine.weekSpec(1, 12, g.type, needsInitialTest = true))
        assertEquals(SessionType.TEST.name, first[0].workout.sessionType)
        assertEquals("Benchmark test", first[0].workout.focus) // can't yet swim 400 m continuously
        val css = PlanEngine.buildWeek(g, beginner.copy(maxContinuousM = 500), 160.0, PlanEngine.weekSpec(1, 12, g.type, true))
        assertEquals("CSS test", css[0].workout.focus)
        val last = PlanEngine.buildWeek(g, beginner, 160.0, PlanEngine.weekSpec(12, 12, g.type, false))
        assertEquals(SessionType.GOAL.name, last.last().workout.sessionType)
        assertEquals(1000, last.last().workout.blocks.first { it.name == "Goal" }.totalM)
    }

    @Test fun workoutsFitThePool() {
        for (type in GoalType.entries) {
            val g = goal(type, 800, 1200, "breaststroke")
            for (w in 1..12) {
                val spec = PlanEngine.weekSpec(w, 12, type, needsInitialTest = w == 1)
                PlanEngine.buildWeek(g, beginner, 160.0, spec).forEach { s ->
                    assertTrue(s.workout.totalM > 0)
                    s.workout.blocks.flatMap { it.sets }.forEach { set ->
                        assertTrue("${s.workout.title}: ${set.distM}", set.distM % 25 == 0 && set.reps >= 1)
                    }
                }
            }
        }
    }

    @Test fun longSwimBuildsTowardsGoal() {
        val g = goal(GoalType.DISTANCE, 1500)
        val distances = (1..12).mapNotNull { w ->
            val spec = PlanEngine.weekSpec(w, 12, g.type, needsInitialTest = false)
            if (spec.deload || spec.hasTest || spec.isFinal) return@mapNotNull null
            PlanEngine.buildWeek(g, beginner, 160.0, spec)
                .first { it.workout.sessionType == SessionType.LONG_SWIM.name }
                .workout.blocks.first { it.name == "Main" }.sets.first().distM
        }
        assertEquals(distances.sorted(), distances)
        assertTrue(distances.first() <= 150)
        assertTrue(distances.last() >= 1000)
    }

    @Test fun techniqueGoalUsesFocusStroke() {
        val g = goal(GoalType.TECHNIQUE, focus = "Learn breaststroke")
        val week = PlanEngine.buildWeek(g, beginner, 160.0, PlanEngine.weekSpec(2, 12, g.type, false))
        val tech = week.first { it.workout.sessionType == SessionType.TECHNIQUE.name }.workout
        assertEquals("Breaststroke technique", tech.focus)
        assertTrue(tech.blocks.first { it.name == "Drills" }.sets.all { it.stroke == "Breaststroke" })
    }

    @Test fun adaptationRules() {
        assertEquals(Adaptation.REPEAT_WEEK, PlanEngine.adapt(WeekReview(3, 1, null, 0), wasDeload = false))
        assertEquals(Adaptation.NONE, PlanEngine.adapt(WeekReview(3, 1, null, 0), wasDeload = true))
        assertEquals(Adaptation.RECOVERY_WEEK, PlanEngine.adapt(WeekReview(3, 3, 9.0, 0), false))
        assertEquals(Adaptation.RETEST, PlanEngine.adapt(WeekReview(3, 3, 6.0, 2), false))
        assertEquals(Adaptation.NONE, PlanEngine.adapt(WeekReview(3, 2, 6.0, 1), false))
    }

    @Test fun matchingPicksClosestWithinTwoDays() {
        val planned = listOf(1L to 10L, 2L to 12L, 3L to 14L)
        val swims = listOf(100L to 11L, 101L to 12L, 102L to 20L)
        val m = PlanEngine.matchSwims(planned, swims)
        assertEquals(100L, m[1L])
        assertEquals(101L, m[2L])
        assertNull(m[3L])
    }

    @Test fun predictionAndRequiredPace() {
        val t = PlanEngine.predictTimeSec(120.0, 400)
        assertEquals(480.0, t, 1e-9)
        assertEquals(120.0, PlanEngine.requiredCss(480, 400), 1e-9)
    }

    @Test fun durationsAndJsonRoundTrip() {
        assertEquals(95, parseDuration("1:35"))
        assertEquals(3725, parseDuration("1:02:05"))
        assertNull(parseDuration("abc"))
        assertEquals("1:35", fmtDuration(95.0))
        val w = PlanEngine.buildWeek(goal(GoalType.FITNESS), beginner, 160.0, PlanEngine.weekSpec(3, 12, GoalType.FITNESS, false))[0].workout
        assertEquals(w, Workout.fromJson(w.toJson()))
    }

    @Test fun setDescriptionIncludesSendOff() {
        // 4 × 50 at Z2 with CSS 2:00 → pace 2:09/100 → 64.5 s + 20 s rest → go every 1:25
        val text = SwimSet(4, 50, "Freestyle", zone = 2, restSec = 20).describe(120.0)
        assertTrue(text, text.contains("go every 1:25"))
        assertFalse(SwimSet(1, 100, zone = 0, restSec = 0).describe(120.0).contains("go every"))
    }
}
