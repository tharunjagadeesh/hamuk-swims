package com.hamuk.swimpro.analysis

import com.hamuk.swimpro.data.DailyWellness
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Lifestyle factors that might affect a swim. Values are read from the swim's context. */
enum class Factor(val label: String, val short: String, val format: (Double) -> String, val get: (SwimContext) -> Double?) {
    SLEEP("Sleep the night before", "sleep", { "%.1f h".format(it) }, { it.sleepMin?.div(60.0) }),
    DEEP_SLEEP("Deep sleep", "deep sleep", { "%.0f min".format(it) }, { it.deepMin?.toDouble() }),
    STEPS_PREV("Steps the day before", "steps", { "%,.0f".format(it) }, { it.stepsPrevDay?.toDouble() }),
    ACTIVE_KCAL_PREV("Active calories the day before", "active calories", { "%,.0f kcal".format(it) }, { it.activeKcalPrevDay }),
    OTHER_EXERCISE_PREV("Other exercise the day before", "other exercise", { "%.0f min".format(it) }, { it.exerciseMinPrevDay?.toDouble() }),
    RESTING_HR("Morning resting HR", "resting HR", { "%.0f bpm".format(it) }, { it.restingHr?.toDouble() }),
    HRV("HRV that morning", "HRV", { "%.0f ms".format(it) }, { it.hrvMs }),
}

/** What we measure about the swim. Positive "better" direction is encoded in [higherIsBetter]. */
enum class Outcome(
    val label: String,
    val higherIsBetter: Boolean,
    val goodPhrase: String,
    val badPhrase: String,
    val format: (Double) -> String,
    val get: (SwimOutcome) -> Double?,
) {
    PACE("Pace vs your normal", true, "faster swims", "slower swims", { (if (it >= 0) "+" else "") + "%.1f %%".format(it) }, { it.paceVsNormalPct }),
    EFFICIENCY("Efficiency vs normal", true, "more efficient swims", "less efficient swims", { (if (it >= 0) "+" else "") + "%.1f %%".format(it) }, { it.efVsNormalPct }),
    HR_COST("HR at the same pace", false, "lower HR at the same pace", "higher HR at the same pace", { (if (it >= 0) "+" else "") + "%.1f bpm".format(it) }, { it.hrResidual }),
    RPE("How hard it felt (RPE)", false, "swims feeling easier", "swims feeling harder", { "%.1f".format(it) }, { it.rpe?.toDouble() }),
}

data class SwimContext(
    val swimId: Long,
    val sleepMin: Int?,
    val deepMin: Int?,
    val sleepVsNormalMin: Double?,
    val stepsPrevDay: Long?,
    val activeKcalPrevDay: Double?,
    val exerciseMinPrevDay: Int?,
    val restingHr: Int?,
    val restingHrVsNormal: Double?,
    val hrvMs: Double?,
    val hrvVsNormalPct: Double?,
)

data class SwimOutcome(val swimId: Long, val paceVsNormalPct: Double?, val efVsNormalPct: Double?, val hrResidual: Double?, val rpe: Int?)

data class Bucket(val label: String, val n: Int, val outcomeMean: Double)

data class Impact(
    val factor: Factor,
    val outcome: Outcome,
    val points: List<Pair<Double, Double>>,
    val r: Double?,
    val buckets: List<Bucket>,
    val confident: Boolean,
    val verdict: String,
) {
    val n: Int get() = points.size
}

data class Readiness(val score: Int, val label: String, val parts: List<Pair<String, String>>)

object Lifestyle {
    const val MIN_SWIMS = 8
    const val MIN_R = 0.3

    private fun mean(xs: List<Double>) = if (xs.isEmpty()) null else xs.average()

    /** Mean of [get] over the [days] days before [day] (exclusive), needing ≥ 3 values. */
    private fun normal(byDay: Map<Long, DailyWellness>, day: Long, days: Int = 14, get: (DailyWellness) -> Double?): Double? {
        val xs = (1..days).mapNotNull { byDay[day - it]?.let(get) }
        return if (xs.size >= 3) xs.average() else null
    }

    fun context(m: SwimMetrics, byDay: Map<Long, DailyWellness>): SwimContext {
        val today = byDay[m.epochDay]
        val prev = byDay[m.epochDay - 1]
        val sleep = today?.sleepMin
        val sleepNormal = normal(byDay, m.epochDay) { it.sleepMin?.toDouble() }
        val rhr = today?.restingHr
        val rhrNormal = normal(byDay, m.epochDay) { it.restingHr?.toDouble() }
        val hrv = today?.hrvMs
        val hrvNormal = normal(byDay, m.epochDay) { it.hrvMs }
        return SwimContext(
            swimId = m.swimId,
            sleepMin = sleep,
            deepMin = today?.deepMin,
            sleepVsNormalMin = if (sleep != null && sleepNormal != null) sleep - sleepNormal else null,
            stepsPrevDay = prev?.steps,
            activeKcalPrevDay = prev?.activeKcal,
            exerciseMinPrevDay = prev?.let { it.exerciseMin ?: if (it.steps != null) 0 else null },
            restingHr = rhr,
            restingHrVsNormal = if (rhr != null && rhrNormal != null) rhr - rhrNormal else null,
            hrvMs = hrv,
            hrvVsNormalPct = if (hrv != null && hrvNormal != null && hrvNormal > 0) (hrv - hrvNormal) / hrvNormal * 100 else null,
        )
    }

    /** How a swim went relative to the 28 days before it. */
    fun outcome(m: SwimMetrics, all: List<SwimMetrics>): SwimOutcome {
        val pace = m.pace
        val paceMetric = if (m.movingPace != null) Metric.MOVING_PACE else Metric.AVG_PACE
        val paceBase = Baselines.of(paceMetric, all, m.epochDay, excludeId = m.swimId)
        val efBase = Baselines.of(Metric.EF, all, m.epochDay, excludeId = m.swimId)
        val hrFit = Fit.of(
            HrPace.points(all.filter { it.epochDay in (m.epochDay - 28) until m.epochDay && it.swimId != m.swimId }).map { it.pace to it.hr },
        )?.takeIf { it.b < 0 }
        return SwimOutcome(
            swimId = m.swimId,
            paceVsNormalPct = if (pace != null && paceBase != null) (paceBase.mean - pace) / paceBase.mean * 100 else null,
            efVsNormalPct = if (m.ef != null && efBase != null) (m.ef - efBase.mean) / efBase.mean * 100 else null,
            hrResidual = if (pace != null && m.avgHr != null && hrFit != null) m.avgHr - hrFit.y(pace) else null,
            rpe = m.rpe,
        )
    }

    fun pearson(points: List<Pair<Double, Double>>): Double? {
        if (points.size < 3) return null
        val mx = points.map { it.first }.average()
        val my = points.map { it.second }.average()
        val sxx = points.sumOf { (it.first - mx).pow(2) }
        val syy = points.sumOf { (it.second - my).pow(2) }
        if (sxx < 1e-12 || syy < 1e-12) return null
        return points.sumOf { (it.first - mx) * (it.second - my) } / sqrt(sxx * syy)
    }

    /** Splits by the factor into thirds (low / mid / high) and averages the outcome in each. */
    fun buckets(points: List<Pair<Double, Double>>, factor: Factor): List<Bucket> {
        if (points.size < 6) return emptyList()
        val sorted = points.sortedBy { it.first }
        val third = sorted.size / 3
        val groups = listOf(sorted.take(third), sorted.subList(third, sorted.size - third), sorted.takeLast(third))
        val names = listOf("Low", "Mid", "High")
        return groups.mapIndexed { i, g ->
            Bucket("${names[i]} (${factor.format(g.first().first)}–${factor.format(g.last().first)})", g.size, g.map { it.second }.average())
        }
    }

    fun impact(factor: Factor, outcome: Outcome, contexts: List<SwimContext>, outcomes: List<SwimOutcome>): Impact {
        val byId = outcomes.associateBy { it.swimId }
        val pts = contexts.mapNotNull { c ->
            val x = factor.get(c) ?: return@mapNotNull null
            val y = byId[c.swimId]?.let(outcome.get) ?: return@mapNotNull null
            x to y
        }
        val r = pearson(pts)
        val confident = pts.size >= MIN_SWIMS && r != null && abs(r) >= MIN_R
        val b = buckets(pts, factor)
        val verdict = when {
            pts.size < MIN_SWIMS -> "Collecting data: ${pts.size} of $MIN_SWIMS swims have ${factor.short} recorded."
            r == null -> "No variation yet to compare."
            !confident -> "No clear link so far (r = ${"%.2f".format(r)}, ${pts.size} swims)."
            else -> {
                val good = (r > 0) == outcome.higherIsBetter
                val lo = b.firstOrNull(); val hi = b.lastOrNull()
                val gap = if (lo != null && hi != null)
                    " Most ${factor.short} (${hi.label.substringAfter("(").removeSuffix(")")}): ${outcome.format(hi.outcomeMean)}; least (${lo.label.substringAfter("(").removeSuffix(")")}): ${outcome.format(lo.outcomeMean)}."
                else ""
                "More ${factor.short} → ${if (good) outcome.goodPhrase else outcome.badPhrase} (r = ${"%.2f".format(r)}, ${pts.size} swims).$gap"
            }
        }
        return Impact(factor, outcome, pts, r, b, confident, verdict)
    }

    /** All confident findings, strongest first. */
    fun findings(contexts: List<SwimContext>, outcomes: List<SwimOutcome>): List<Impact> =
        Factor.entries.flatMap { f -> listOf(Outcome.PACE, Outcome.EFFICIENCY, Outcome.HR_COST).map { o -> impact(f, o, contexts, outcomes) } }
            .filter { it.confident }.sortedByDescending { abs(it.r ?: 0.0) }

    /**
     * Readiness 0–100 from last night's sleep, morning resting HR and HRV against your 14-day
     * normal, plus training-load form. Null when there's no sleep or resting-HR data.
     */
    fun readiness(today: Long, byDay: Map<Long, DailyWellness>, form: Double?): Readiness? {
        val d = byDay[today]
        val sleep = d?.sleepMin?.toDouble()
        val rhr = d?.restingHr?.toDouble()
        if (sleep == null && rhr == null) return null
        var score = 70.0
        val parts = mutableListOf<Pair<String, String>>()
        sleep?.let { s ->
            val n = normal(byDay, today) { it.sleepMin?.toDouble() } ?: 450.0
            score += ((s - n) / 60 * 7.5).coerceIn(-15.0, 10.0)
            if (s < 360) score -= 5
            parts += "Sleep" to "%.1f h (normal %.1f h)".format(s / 60, n / 60)
        }
        rhr?.let { r ->
            normal(byDay, today) { it.restingHr?.toDouble() }?.let { n ->
                score += (-(r - n) * 2.5).coerceIn(-12.0, 8.0)
                parts += "Resting HR" to "%.0f bpm (normal %.0f)".format(r, n)
            } ?: run { parts += "Resting HR" to "%.0f bpm".format(r) }
        }
        d?.hrvMs?.let { h ->
            normal(byDay, today) { it.hrvMs }?.let { n ->
                score += ((h - n) / n * 100 * 0.4).coerceIn(-10.0, 8.0)
                parts += "HRV" to "%.0f ms (normal %.0f)".format(h, n)
            }
        }
        form?.let { f ->
            score += (f / 2).coerceIn(-10.0, 6.0)
            parts += "Training form" to (if (f > 0) "+" else "") + "%.0f".format(f)
        }
        val s = score.roundToInt().coerceIn(0, 100)
        val label = when {
            s >= 75 -> "Ready for a quality session"
            s >= 55 -> "Normal day: train as planned"
            s >= 45 -> "A bit flat: keep intensity moderate"
            else -> "Recovery day: easy swim or rest"
        }
        return Readiness(s, label, parts)
    }
}
