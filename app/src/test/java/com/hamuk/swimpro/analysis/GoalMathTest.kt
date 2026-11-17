package com.hamuk.swimpro.analysis

import com.hamuk.swimpro.coach.Block
import com.hamuk.swimpro.coach.SwimSet
import com.hamuk.swimpro.coach.Workout
import com.hamuk.swimpro.data.Goal
import com.hamuk.swimpro.data.GoalType
import com.hamuk.swimpro.data.PlannedWorkout
import com.hamuk.swimpro.data.Profile
import com.hamuk.swimpro.data.Swim
import com.hamuk.swimpro.data.TestResult
import com.hamuk.swimpro.data.TestType
import com.hamuk.swimpro.data.WorkoutStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalMathTest {
    private val hrp = HrProfile(190, 60)
    private val base = SwimMetrics.from(Swim(id = 1, startMs = 0, endMs = 1_200_000, distanceM = 1000.0, activeSec = 1200), emptyList(), emptyList(), 120.0, hrp)
    private fun swimAt(id: Long, day: Long, longest: Double? = null, swolf: Double? = null) =
        base.copy(swimId = id, epochDay = day, longestContinuousM = longest, swolf = swolf)

    @Test fun timeGoalProjectsTrendAndRequiredRate() {
        val goal = Goal(type = GoalType.TIME, targetDistanceM = 1500, targetTimeSec = 1800, startEpochDay = 0, targetEpochDay = 84)
        // CSS 130 at the start (predicts 1995 s), CSS 125 today (1920 s).
        val tests = listOf(TestResult(epochDay = 0, type = TestType.T100, timeSec = 116, distanceM = 100, cssPer100 = 130.0))
        val nav = GoalMath.compute(goal, today = 42, Profile(), cssNow = 125.0, tests, emptyList(), emptyList(), hrp)
        assertEquals(1920.0, nav.current!!, 1e-9)
        assertEquals(1995.0, nav.start!!, 1e-9)
        assertEquals(1845.0, nav.projected!!, 1e-6)          // −75 s per 42 days → 1845 at day 84
        assertEquals(GoalStatus.BEHIND, nav.status)
        assertEquals(-20.0, nav.requiredPerWeek!!, 1e-9)     // 120 s to gain over 6 weeks
        assertEquals(120.0, nav.goalPace!!, 1e-9)            // 1800 s / 1500 m
        assertEquals(42, nav.daysLeft)
        assertEquals(1897.5, nav.expectedToday!!, 1e-9)      // halfway from 1995 to 1800
    }

    @Test fun distanceGoalUsesRunningBestOfContinuousSwims() {
        val goal = Goal(type = GoalType.DISTANCE, targetDistanceM = 1000, startEpochDay = 0, targetEpochDay = 84)
        val swims = listOf(swimAt(1, 14, 200.0), swimAt(2, 21, 150.0), swimAt(3, 28, 300.0))
        val nav = GoalMath.compute(goal, 28, Profile(maxContinuousM = 100), 150.0, emptyList(), swims, emptyList(), hrp)
        assertEquals(300.0, nav.current!!, 1e-9)
        assertEquals(listOf(0L to 100.0, 14L to 200.0, 21L to 200.0, 28L to 300.0), nav.actual)
        assertEquals(400.0, nav.expectedToday!!, 1e-9)      // 100 + 900 × 28/84
        assertEquals(GoalStatus.BEHIND, nav.status)
        assertEquals(700.0 / 8.0, nav.requiredPerWeek!!, 1e-9) // 700 m over 56 days
    }

    @Test fun volumeAndDayTargets() {
        val goal = Goal(id = 1, type = GoalType.TIME, targetDistanceM = 400, targetTimeSec = 480, startEpochDay = 0, targetEpochDay = 70)
        val w = Workout("Threshold (CSS) set", "CSS", "Threshold pace", listOf(
            Block("Warm-up", listOf(SwimSet(4, 50, zone = 1))),
            Block("Main", listOf(SwimSet(6, 100, zone = 4))),
        ))
        val planned = listOf(
            PlannedWorkout(id = 10, weekId = 1, goalId = 1, epochDay = 30, sessionType = "CSS", title = w.title, json = w.toJson(), totalM = 800),
            PlannedWorkout(id = 11, weekId = 1, goalId = 1, epochDay = 32, sessionType = "AEROBIC", title = "Aerobic", json = w.toJson(), totalM = 1000),
            PlannedWorkout(id = 12, weekId = 1, goalId = 1, epochDay = 25, sessionType = "AEROBIC", title = "Old", json = w.toJson(), totalM = 900, status = WorkoutStatus.DONE),
        )
        val nav = GoalMath.compute(goal, 30, Profile(), 120.0, emptyList(), emptyList(), planned, hrp)
        assertEquals(1800, nav.volume.remainingM)
        assertEquals(2, nav.volume.sessionsLeft)
        assertEquals(45.0, nav.volume.perDayM, 1e-9)          // 1800 m over 40 days
        val today = nav.days.first()
        assertEquals("Threshold (CSS) set", today.title)
        assertEquals(133, today.hrCap)                         // 70 % of 190
        assertEquals(135.0, today.paces.first { it.label == "Warm-up" }.pacePer100!!, 1e-9) // CSS + 15
        assertEquals(121.0, today.paces.first { it.label == "Main set" }.pacePer100!!, 1e-9) // CSS + 1
        assertEquals(120.0, today.paces.first { it.label == "Goal pace" }.pacePer100!!, 1e-9)
        assertEquals("Rest", nav.days[1].title)
        // Low readiness turns today's main set into easy aerobic work.
        val easy = GoalMath.compute(goal, 30, Profile(), 120.0, emptyList(), emptyList(), planned, hrp, easyDay = true)
        assertTrue(easy.days.first().paces.none { it.label == "Goal pace" })
        assertEquals(129.0, easy.days.first().paces.first { it.label == "Main set" }.pacePer100!!, 1e-9) // Z2
    }

    @Test fun techniqueGoalWithoutSwolfIsUnknown() {
        val goal = Goal(type = GoalType.TECHNIQUE, startEpochDay = 0, targetEpochDay = 56)
        val nav = GoalMath.compute(goal, 10, Profile(), 120.0, emptyList(), listOf(swimAt(1, 5)), emptyList(), hrp)
        assertEquals(GoalStatus.UNKNOWN, nav.status)
        assertNull(nav.current)
    }
}
