package com.hamuk.swimpro.analysis

import com.hamuk.swimpro.data.HrSample
import com.hamuk.swimpro.data.Swim
import com.hamuk.swimpro.data.SwimLap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.exp
import kotlin.math.sqrt

class AnalysisTest {
    private val hrp = HrProfile(maxHr = 190, restingHr = 60)
    private val day0 = LocalDate.of(2026, 10, 1)

    private fun startMs(day: Long) = day0.plusDays(day).atTime(7, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun swim(id: Long, day: Long, dist: Double, activeSec: Long, elapsedSec: Long = activeSec, avgHr: Int? = null, rpe: Int? = null) =
        Swim(id = id, startMs = startMs(day), endMs = startMs(day) + elapsedSec * 1000, distanceM = dist, activeSec = activeSec, avgHr = avgHr, rpe = rpe)

    /** 25 m lengths back to back (1 s turn gap) starting at the swim start. */
    private fun lengths(s: Swim, secs: List<Double>, strokes: Int? = null): List<SwimLap> {
        var t = s.startMs
        return secs.mapIndexed { i, sec ->
            val lap = SwimLap(swimId = s.id, idx = i, startMs = t, endMs = t + (sec * 1000).toLong(), distanceM = 25.0, stroke = "Freestyle", strokes = strokes, kind = "lap")
            t = lap.endMs + 1000
            lap
        }
    }

    @Test fun movingTimeRestAndPacing() {
        val s = swim(1, 0, 100.0, activeSec = 200, elapsedSec = 200)
        val m = SwimMetrics.from(s, lengths(s, listOf(30.0, 30.0, 33.0, 33.0)), emptyList(), 120.0, hrp)
        assertEquals(126.0, m.movingSec!!, 1e-9)
        assertEquals(74.0, m.restSec!!, 1e-9)
        assertEquals(37.0, m.restPct!!, 1e-9)
        assertEquals(126.0, m.movingPace!!, 1e-9)       // 126 s per 100 m
        assertEquals(10.0, m.splitPct!!, 1e-9)          // 66 s vs 60 s per 50 m
        assertEquals(10.0, m.fadePct!!, 1e-9)           // last length 132 vs first 120 s/100
        assertEquals(120.0, m.fastestLap!!, 1e-9)
        assertEquals(132.0, m.slowestLap!!, 1e-9)
        val mean = 126.0; val sd = sqrt((4 * 36.0) / 4)
        assertEquals(sd / mean * 100, m.cvPct!!, 1e-9)
    }

    @Test fun strokeMetrics() {
        val s = swim(2, 0, 100.0, 160)
        val m = SwimMetrics.from(s, lengths(s, listOf(30.0, 30.0, 30.0, 30.0), strokes = 20), emptyList(), 120.0, hrp)
        assertEquals(50.0, m.swolf!!, 1e-9)        // 30 s + 20 strokes
        assertEquals(40.0, m.strokeRate!!, 1e-9)   // 80 strokes in 2 min
        assertEquals(1.25, m.dps!!, 1e-9)          // 100 m / 80 strokes
    }

    @Test fun loadFormulas() {
        val s = swim(3, 0, 1000.0, activeSec = 1200, avgHr = 150, rpe = 6)
        val m = SwimMetrics.from(s, emptyList(), emptyList(), cssPer100 = 120.0, hrp = hrp)
        assertEquals(100.0 / 3, m.sTss!!, 1e-6)   // IF = 1, 1/3 hour
        val hrr = 90.0 / 130.0
        assertEquals(20 * hrr * 0.64 * exp(1.92 * hrr), m.trimp!!, 1e-9)
        assertEquals(120.0, m.sRpe!!, 1e-9)
        assertEquals(50.0 / 150, m.ef!!, 1e-9)   // 50 m/min ÷ 150 bpm
    }

    @Test fun hrZonesAndDecoupling() {
        val s = swim(4, 0, 100.0, 130)
        val laps = lengths(s, listOf(30.0, 30.0, 30.0, 30.0))
        // HR 120 in the first two lengths, 132 in the last two.
        val samples = laps.flatMapIndexed { i, l -> listOf(HrSample(4, l.startMs + 5000, if (i < 2) 120 else 132), HrSample(4, l.startMs + 20000, if (i < 2) 120 else 132)) }
        val m = SwimMetrics.from(s, laps, samples, 120.0, hrp)
        assertEquals((1 - 120.0 / 132.0) * 100, m.decouplingPct!!, 1e-9)
        assertNotNull(m.zoneSec)
        assertEquals(m.zoneSec!!.sum(), m.zoneSec!![1], 1e-9) // 120 and 132 bpm are both Z2 (63 % and 69 % of 190)
        assertTrue(m.laps.all { it.hr != null })
    }

    @Test fun zoneSecondsCapsGapsAndUsesFiveSecondsForLastSample() {
        val samples = listOf(HrSample(1, 0, 100), HrSample(1, 10_000, 120), HrSample(1, 20_000, 160))
        val z = SwimMetrics.zoneSeconds(samples, hrp)
        assertEquals(listOf(10.0, 10.0, 0.0, 5.0, 0.0), z)
        val gap = SwimMetrics.zoneSeconds(listOf(HrSample(1, 0, 120), HrSample(1, 120_000, 120)), hrp)
        assertEquals(35.0, gap[1], 1e-9) // 30 s cap + 5 s
    }

    @Test fun missingDataGivesNullsNotCrashes() {
        val s = swim(5, 0, 400.0, 600)
        val m = SwimMetrics.from(s, emptyList(), emptyList(), 120.0, hrp)
        assertNull(m.movingPace); assertNull(m.zoneSec); assertNull(m.ef); assertNull(m.decouplingPct)
        assertNull(m.swolf); assertNull(m.splitPct); assertNull(m.trimp)
        assertEquals(150.0, m.pace!!, 1e-9)
        assertNotNull(m.sTss)
    }

    @Test fun hrVsPaceFitAndPaceAtHr() {
        // HR = 300 − pace: a clean, faster-is-harder line.
        val pts = listOf(150.0, 140.0, 130.0, 120.0).mapIndexed { i, p -> HrPace.Point(i.toLong(), 10L + i, p, 300 - p) }
        assertEquals(135.0, HrPace.paceAtHr(pts, endDay = 13, atHr = 165.0)!!, 1e-9)
        assertNull(HrPace.paceAtHr(pts.take(3), 13, 165.0))               // too few swims
        assertNull(HrPace.paceAtHr(pts, endDay = 100, atHr = 165.0))       // outside the window
        val wrongWay = pts.map { it.copy(hr = it.pace) }                    // HR falling as pace speeds up
        assertNull(HrPace.paceAtHr(wrongWay, 13, 140.0))
        val fit = Fit.of(pts.map { it.pace to it.hr })!!
        assertEquals(-1.0, fit.b, 1e-9); assertEquals(1.0, fit.r2, 1e-9)
    }

    @Test fun loadModelSteadyStateAndSpike() {
        val steady = LoadModel.series((0L..300L).associateWith { 50.0 }, 0, 300)
        val s = LoadModel.state(steady)!!
        assertEquals(50.0, s.fitness, 0.1); assertEquals(50.0, s.fatigue, 0.01); assertEquals(0.0, s.form, 0.1)
        assertEquals(1.0, s.acwr!!, 1e-9)
        val spike = LoadModel.state(LoadModel.series(mapOf(27L to 100.0), 0, 27))!!
        assertEquals(4.0, spike.acwr!!, 1e-9)
        assertTrue(spike.form < 0)
        assertEquals("Load spike: injury risk", LoadModel.acwrLabel(spike.acwr))
    }

    @Test fun baselineAggregationAndDeltas() {
        val ms = listOf(10.0, 12.0, 14.0).mapIndexed { i, d ->
            SwimMetrics.from(swim(i.toLong(), i.toLong(), d * 100, (d * 120).toLong()), emptyList(), emptyList(), 120.0, hrp)
        }
        val b = Baselines.of(Metric.DISTANCE, ms, beforeDay = day0.toEpochDay() + 5)!!
        assertEquals(1200.0, b.mean, 1e-9)
        assertEquals(sqrt(80000.0 / 3), b.sd, 1e-9)
        assertEquals("above normal", b.label(1500.0))
        assertNull(Baselines.of(Metric.DISTANCE, ms, beforeDay = day0.toEpochDay() + 2)) // only 2 swims before day 2

        val a1 = SwimMetrics.from(swim(10, 0, 1000.0, 1200), emptyList(), emptyList(), 120.0, hrp) // 120 s/100
        val a2 = SwimMetrics.from(swim(11, 0, 500.0, 750), emptyList(), emptyList(), 120.0, hrp)   // 150 s/100
        assertEquals(130.0, Baselines.aggregate(Metric.AVG_PACE, listOf(a1, a2))!!, 1e-9)
        assertEquals(1500.0, Baselines.aggregate(Metric.DISTANCE, listOf(a1, a2))!!, 1e-9)

        assertEquals(true, MetricDelta(Metric.AVG_PACE, 115.0, 120.0).better)
        assertEquals(false, MetricDelta(Metric.DISTANCE, 900.0, 1000.0).better)
        assertNull(MetricDelta(Metric.DISTANCE, 1000.0, 1003.0).better)  // < 1 % is noise
        assertNull(MetricDelta(Metric.AVG_HR, 140.0, 150.0).better)       // neutral metric
        assertEquals("−0:05", Metric.AVG_PACE.formatDelta(-5.0))
    }

    @Test fun insightsFlagFasterThanNormal() {
        val history = (0L..5L).map { d -> SwimMetrics.from(swim(d, d, 1000.0, 1500, avgHr = 140), emptyList(), emptyList(), 120.0, hrp) }
        val today = SwimMetrics.from(swim(99, 8, 1000.0, 1350, avgHr = 137), emptyList(), emptyList(), 120.0, hrp)
        val ins = Insights.forSwim(today, history.last(), history + today, null)
        assertTrue(ins.joinToString { it.text }, ins.any { it.text.contains("faster than your 28-day normal") && it.text.contains("3 bpm lower") })
        assertTrue(ins.size <= 3)
        val dull = Insights.forSwim(history.last(), history[4], history, null)
        assertFalse(dull.any { it.tone == Tone.WARN })
    }

    @Test fun hrProfileResolution() {
        assertEquals(185, HrProfile.resolve(185, 199, 30, null, null).maxHr)
        assertEquals(176, HrProfile.resolve(null, 176, 30, null, null).maxHr)
        assertEquals(190, HrProfile.resolve(null, 140, 30, null, null).maxHr) // 140 too low to trust → 220 − 30
        assertEquals(190, HrProfile.resolve(null, null, null, null, null).maxHr)
        assertEquals(52, HrProfile.resolve(null, null, null, null, 52).restingHr)
        assertEquals(48, HrProfile.resolve(null, null, null, 48, 52).restingHr)
    }
}

class LongestContinuousTest {
    @Test fun breaksRunOnLongRest() {
        fun lap(i: Int, start: Long, sec: Long) = SwimLap(swimId = 1, idx = i, startMs = start, endMs = start + sec * 1000, distanceM = 25.0, stroke = null, strokes = null, kind = "lap")
        // 4 lengths with 2 s turns, a 30 s rest, then 6 lengths.
        val laps = mutableListOf<SwimLap>()
        var t = 0L
        repeat(4) { laps += lap(laps.size, t, 30); t = laps.last().endMs + 2000 }
        t += 30_000
        repeat(6) { laps += lap(laps.size, t, 30); t = laps.last().endMs + 2000 }
        org.junit.Assert.assertEquals(150.0, SwimMetrics.longestContinuous(laps), 1e-9)
    }
}
