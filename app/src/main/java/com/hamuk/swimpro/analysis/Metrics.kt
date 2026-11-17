package com.hamuk.swimpro.analysis

import com.hamuk.swimpro.data.HrSample
import com.hamuk.swimpro.data.Swim
import com.hamuk.swimpro.data.SwimLap
import java.time.Instant
import java.time.ZoneId
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** Heart-rate anchors used for zones and TRIMP. */
data class HrProfile(val maxHr: Int, val restingHr: Int) {
    /** Zone 1–5 for a heart rate, or 0 below 50 % of max. Zones start at 50/60/70/80/90 %. */
    fun zoneOf(bpm: Int): Int {
        val f = bpm.toDouble() / maxHr
        return when {
            f >= 0.9 -> 5
            f >= 0.8 -> 4
            f >= 0.7 -> 3
            f >= 0.6 -> 2
            f >= 0.5 -> 1
            else -> 0
        }
    }

    companion object {
        /** Override, else highest swim max HR (if plausible), else 220 − age, else 190. */
        fun resolve(maxOverride: Int?, observedMax: Int?, age: Int?, restingOverride: Int?, restingAuto: Int?) = HrProfile(
            maxHr = maxOverride ?: observedMax?.takeIf { it >= 150 } ?: age?.let { 220 - it } ?: 190,
            restingHr = restingOverride ?: restingAuto ?: 60,
        )
    }
}

/** One length: pace (s/100 m) and the average HR while it was swum. */
data class LapPoint(val index: Int, val pacePer100: Double, val hr: Double?)

/**
 * Everything we can derive from one swim. Each field is null when the data behind it is missing
 * (no lengths, no HR, no stroke counts…), so the UI simply hides it.
 */
data class SwimMetrics(
    val swimId: Long,
    val startMs: Long,
    val epochDay: Long,
    val openWater: Boolean,
    val distanceM: Double,
    /** Watch "workout time" (pauses excluded, wall rests included). */
    val activeSec: Long,
    val movingSec: Double?,
    val restSec: Double?,
    val restPct: Double?,
    val avgPace: Double?,
    val movingPace: Double?,
    val swolf: Double?,
    val strokeRate: Double?,
    val dps: Double?,
    val lapCount: Int,
    val laps: List<LapPoint>,
    /** Longest run of back-to-back lengths (turns ≤ 10 s), in metres. */
    val longestContinuousM: Double?,
    val splitPct: Double?,
    val cvPct: Double?,
    val fadePct: Double?,
    val fastestLap: Double?,
    val slowestLap: Double?,
    val avgHr: Int?,
    val maxHr: Int?,
    val avgHrPctMax: Double?,
    /** Seconds in zones 1..5 (index 0 = Z1). */
    val zoneSec: List<Double>?,
    val ef: Double?,
    val decouplingPct: Double?,
    val sTss: Double?,
    val trimp: Double?,
    val sRpe: Double?,
    val kcal: Double?,
    val rpe: Int?,
) {
    /** Pace to use when only one is shown: moving pace when we have lengths. */
    val pace: Double? get() = movingPace ?: avgPace
    val load: Double get() = sTss ?: trimp ?: sRpe ?: 0.0
    val minutes: Double get() = activeSec / 60.0

    companion object {
        fun from(swim: Swim, laps: List<SwimLap>, hr: List<HrSample>, cssPer100: Double, hrp: HrProfile): SwimMetrics {
            val day = Instant.ofEpochMilli(swim.startMs).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
            val lengths = laps.filter { it.kind == "lap" && (it.distanceM ?: 0.0) > 0 && it.endMs > it.startMs }.sortedBy { it.startMs }
            val lapDist = lengths.sumOf { it.distanceM!! }
            // Trust lengths for moving time only if they cover most of the swim.
            val useLaps = lengths.size >= 2 && lapDist >= swim.distanceM * 0.8
            val movingSec = if (useLaps) lengths.sumOf { it.seconds } else null
            val elapsed = (swim.endMs - swim.startMs) / 1000.0
            val restSec = movingSec?.let { max(0.0, elapsed - it) }
            val samples = hr.sortedBy { it.timeMs }

            val lapPoints = if (useLaps) lengths.mapIndexed { i, l ->
                LapPoint(i, l.seconds / l.distanceM!! * 100, avgHrBetween(samples, l.startMs, l.endMs))
            } else emptyList()
            val paces = lapPoints.map { it.pacePer100 }

            val withStrokes = lengths.filter { (it.strokes ?: 0) > 0 }
            val strokesTotal = withStrokes.sumOf { it.strokes!! }

            val avgHr = swim.avgHr ?: samples.takeIf { it.isNotEmpty() }?.map { it.bpm }?.average()?.toInt()
            val maxHr = swim.maxHr ?: samples.maxOfOrNull { it.bpm }
            val avgPace = swim.pacePer100
            val movingPace = movingSec?.let { it / swim.distanceM * 100 }?.takeIf { swim.distanceM > 0 }
            val speedMpm = (movingPace ?: avgPace)?.let { 100.0 / it * 60 }

            val minutes = swim.activeSec / 60.0
            val hrr = avgHr?.let { ((it - hrp.restingHr).toDouble() / (hrp.maxHr - hrp.restingHr)).coerceIn(0.0, 1.0) }

            return SwimMetrics(
                swimId = swim.id,
                startMs = swim.startMs,
                epochDay = day,
                openWater = swim.openWater,
                distanceM = swim.distanceM,
                activeSec = swim.activeSec,
                movingSec = movingSec,
                restSec = restSec,
                restPct = restSec?.let { if (elapsed > 0) it / elapsed * 100 else null },
                avgPace = avgPace,
                movingPace = movingPace,
                swolf = withStrokes.mapNotNull { it.swolf }.takeIf { it.isNotEmpty() }?.average() ?: swim.manualSwolf?.toDouble(),
                strokeRate = if (strokesTotal > 0) strokesTotal / (withStrokes.sumOf { it.seconds } / 60.0) else null,
                dps = if (strokesTotal > 0) withStrokes.sumOf { it.distanceM!! } / strokesTotal else null,
                lapCount = lengths.size,
                laps = lapPoints,
                longestContinuousM = if (useLaps) longestContinuous(lengths) else null,
                splitPct = if (useLaps) splitPct(lengths) else null,
                cvPct = if (paces.size >= 4) cv(paces) else null,
                fadePct = if (paces.size >= 4) fade(paces) else null,
                fastestLap = paces.minOrNull(),
                slowestLap = paces.maxOrNull(),
                avgHr = avgHr,
                maxHr = maxHr,
                avgHrPctMax = avgHr?.let { it * 100.0 / hrp.maxHr },
                zoneSec = if (samples.size >= 2) zoneSeconds(samples, hrp) else null,
                ef = if (speedMpm != null && avgHr != null && avgHr > 0) speedMpm / avgHr else null,
                decouplingPct = if (useLaps && samples.size >= 4) decoupling(lengths, samples) else null,
                sTss = avgPace?.let { p -> (cssPer100 / p).pow(3) * (swim.activeSec / 3600.0) * 100 },
                trimp = hrr?.let { minutes * it * 0.64 * exp(1.92 * it) },
                sRpe = swim.rpe?.let { it * minutes },
                kcal = swim.activeKcal ?: swim.totalKcal,
                rpe = swim.rpe,
            )
        }

        internal fun avgHrBetween(samples: List<HrSample>, from: Long, to: Long): Double? {
            val inside = samples.filter { it.timeMs in from..to }
            if (inside.isNotEmpty()) return inside.map { it.bpm }.average()
            // Short lengths may fall between samples: use the nearest sample within 10 s.
            val mid = (from + to) / 2
            return samples.minByOrNull { kotlin.math.abs(it.timeMs - mid) }
                ?.takeIf { kotlin.math.abs(it.timeMs - mid) <= 10_000 }?.bpm?.toDouble()
        }

        /** (2nd-half pace − 1st-half pace) / 1st-half pace × 100, splitting by distance. */
        internal fun splitPct(lengths: List<SwimLap>): Double? {
            val total = lengths.sumOf { it.distanceM!! }
            var acc = 0.0
            var t1 = 0.0; var d1 = 0.0; var t2 = 0.0; var d2 = 0.0
            for (l in lengths) {
                if (acc + l.distanceM!! / 2 <= total / 2) { t1 += l.seconds; d1 += l.distanceM } else { t2 += l.seconds; d2 += l.distanceM }
                acc += l.distanceM
            }
            if (d1 <= 0 || d2 <= 0) return null
            val p1 = t1 / d1
            val p2 = t2 / d2
            return (p2 - p1) / p1 * 100
        }

        internal fun longestContinuous(lengths: List<SwimLap>, maxGapMs: Long = 10_000): Double {
            var best = 0.0
            var run = 0.0
            lengths.forEachIndexed { i, l ->
                run = if (i > 0 && l.startMs - lengths[i - 1].endMs > maxGapMs) l.distanceM!! else run + l.distanceM!!
                best = maxOf(best, run)
            }
            return best
        }

        internal fun cv(xs: List<Double>): Double {
            val mean = xs.average()
            val sd = sqrt(xs.sumOf { (it - mean).pow(2) } / xs.size)
            return sd / mean * 100
        }

        /** Average pace of the last quarter vs the first quarter of lengths, in %. */
        internal fun fade(paces: List<Double>): Double {
            val q = max(1, paces.size / 4)
            val first = paces.take(q).average()
            val last = paces.takeLast(q).average()
            return (last - first) / first * 100
        }

        internal fun zoneSeconds(samples: List<HrSample>, hrp: HrProfile): List<Double> {
            val z = DoubleArray(5)
            samples.forEachIndexed { i, s ->
                val dur = if (i < samples.lastIndex) min(30.0, (samples[i + 1].timeMs - s.timeMs) / 1000.0) else 5.0
                val zone = hrp.zoneOf(s.bpm)
                if (zone >= 1) z[zone - 1] += dur
            }
            return z.toList()
        }

        /**
         * Aerobic decoupling: efficiency (speed ÷ HR) in the first half of moving time vs the
         * second half. Positive = HR rose relative to pace (aerobic drift).
         */
        internal fun decoupling(lengths: List<SwimLap>, samples: List<HrSample>): Double? {
            val totalMoving = lengths.sumOf { it.seconds }
            var acc = 0.0
            val first = mutableListOf<SwimLap>()
            val second = mutableListOf<SwimLap>()
            for (l in lengths) {
                if (acc + l.seconds / 2 <= totalMoving / 2) first += l else second += l
                acc += l.seconds
            }
            fun ef(part: List<SwimLap>): Double? {
                if (part.isEmpty()) return null
                val speed = part.sumOf { it.distanceM!! } / (part.sumOf { it.seconds } / 60.0)
                val hr = samples.filter { it.timeMs in part.first().startMs..part.last().endMs }.map { it.bpm }
                if (hr.isEmpty()) return null
                return speed / hr.average()
            }
            val e1 = ef(first) ?: return null
            val e2 = ef(second) ?: return null
            return (e1 - e2) / e1 * 100
        }
    }
}

/** Least-squares line y = a + b·x. */
data class Fit(val a: Double, val b: Double, val r2: Double, val n: Int) {
    fun y(x: Double) = a + b * x
    /** Solve for x at a given y (null when the line is flat). */
    fun x(y: Double): Double? = if (kotlin.math.abs(b) < 1e-9) null else (y - a) / b

    companion object {
        fun of(points: List<Pair<Double, Double>>, minPoints: Int = 3): Fit? {
            if (points.size < minPoints) return null
            val mx = points.map { it.first }.average()
            val my = points.map { it.second }.average()
            val sxx = points.sumOf { (it.first - mx).pow(2) }
            if (sxx < 1e-9) return null
            val sxy = points.sumOf { (it.first - mx) * (it.second - my) }
            val b = sxy / sxx
            val a = my - b * mx
            val syy = points.sumOf { (it.second - my).pow(2) }
            val r2 = if (syy < 1e-9) 0.0 else (sxy * sxy) / (sxx * syy)
            return Fit(a, b, r2, points.size)
        }
    }
}

