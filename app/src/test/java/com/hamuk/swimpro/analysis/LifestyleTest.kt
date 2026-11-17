package com.hamuk.swimpro.analysis

import com.hamuk.swimpro.data.DailyWellness
import com.hamuk.swimpro.data.Swim
import com.hamuk.swimpro.data.SwimRepository
import com.hamuk.swimpro.health.WellnessReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class LifestyleTest {
    private val hrp = HrProfile(190, 60)
    private val base = SwimMetrics.from(Swim(id = 1, startMs = 0, endMs = 1_200_000, distanceM = 1000.0, activeSec = 1200), emptyList(), emptyList(), 120.0, hrp)

    @Test fun contextUsesSameMorningSleepAndPreviousDayActivity() {
        val days = mapOf(
            9L to DailyWellness(9, steps = 12000, activeKcal = 600.0, exerciseMin = 45),
            10L to DailyWellness(10, sleepMin = 420, deepMin = 80, restingHr = 52, hrvMs = 60.0),
        ) + (1L..8L).associateWith { DailyWellness(it, sleepMin = 480, restingHr = 50, hrvMs = 50.0) }
        val c = Lifestyle.context(base.copy(epochDay = 10), days)
        assertEquals(420, c.sleepMin)
        assertEquals(-60.0, c.sleepVsNormalMin!!, 1e-9)       // normal 480
        assertEquals(12000L, c.stepsPrevDay)
        assertEquals(45, c.exerciseMinPrevDay)
        assertEquals(2.0, c.restingHrVsNormal!!, 1e-9)
        assertEquals(20.0, c.hrvVsNormalPct!!, 1e-9)
    }

    @Test fun pearsonAndBuckets() {
        val pts = (1..9).map { it.toDouble() to it * 2.0 + 1 }
        assertEquals(1.0, Lifestyle.pearson(pts)!!, 1e-9)
        assertEquals(-1.0, Lifestyle.pearson(pts.map { it.first to -it.second })!!, 1e-9)
        assertNull(Lifestyle.pearson(pts.map { it.first to 5.0 }))
        val b = Lifestyle.buckets(pts, Factor.STEPS_PREV)
        assertEquals(3, b.size)
        assertEquals(5.0, b[0].outcomeMean, 1e-9)   // x = 1..3 → y = 3,5,7
        assertEquals(17.0, b[2].outcomeMean, 1e-9)  // x = 7..9 → y = 15,17,19
    }

    @Test fun impactNeedsEnoughSwimsAndAStrongLink() {
        // More sleep → faster: pace +0.5 % per extra hour.
        val ctx = (0 until 10).map { i -> SwimContext(i.toLong(), 360 + i * 20, null, null, null, null, null, null, null, null, null) }
        val out = (0 until 10).map { i -> SwimOutcome(i.toLong(), paceVsNormalPct = i * 0.5 - 2, efVsNormalPct = null, hrResidual = null, rpe = null) }
        val imp = Lifestyle.impact(Factor.SLEEP, Outcome.PACE, ctx, out)
        assertTrue(imp.confident)
        assertTrue(imp.verdict, imp.verdict.startsWith("More sleep → faster swims"))
        val few = Lifestyle.impact(Factor.SLEEP, Outcome.PACE, ctx.take(5), out)
        assertFalse(few.confident)
        assertTrue(few.verdict.startsWith("Collecting data: 5 of 8"))
        // Higher resting HR → higher HR at the same pace is a bad sign.
        val ctx2 = ctx.mapIndexed { i, c -> c.copy(restingHr = 48 + i) }
        val out2 = out.mapIndexed { i, o -> o.copy(hrResidual = i * 1.5) }
        assertTrue(Lifestyle.impact(Factor.RESTING_HR, Outcome.HR_COST, ctx2, out2).verdict.startsWith("More resting HR → higher HR at the same pace"))
    }

    @Test fun readinessRespondsToSleepAndRhr() {
        val normal = (1L..14L).associateWith { DailyWellness(it, sleepMin = 450, restingHr = 50) }
        val good = Lifestyle.readiness(15, normal + (15L to DailyWellness(15, sleepMin = 510, restingHr = 48)), form = 4.0)!!
        val bad = Lifestyle.readiness(15, normal + (15L to DailyWellness(15, sleepMin = 330, restingHr = 56)), form = -20.0)!!
        assertTrue("${good.score}", good.score >= 75)
        assertTrue("${bad.score}", bad.score < 45)
        assertNull(Lifestyle.readiness(15, normal, null))
    }

    @Test fun mergesOverlappingWorkoutsAndPicksRichestDuplicateSwim() {
        fun t(min: Long) = Instant.ofEpochSecond(min * 60)
        assertEquals(60, WellnessReader.mergedMinutes(listOf(t(0) to t(45), t(30) to t(60))))
        assertEquals(50, WellnessReader.mergedMinutes(listOf(t(0) to t(20), t(100) to t(130))))
        val keep = SwimRepository.pickRichest(listOf(
            SwimRepository.SessionCandidate("samsung", 0, 2_400_000, richness = 400),
            SwimRepository.SessionCandidate("googlefit", 60_000, 2_380_000, richness = 0),
            SwimRepository.SessionCandidate("later", 9_000_000, 10_000_000, richness = 0),
        ))
        assertEquals(setOf("samsung", "later"), keep)
    }
}
