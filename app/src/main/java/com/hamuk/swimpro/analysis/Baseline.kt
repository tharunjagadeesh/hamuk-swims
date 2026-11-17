package com.hamuk.swimpro.analysis

import com.hamuk.swimpro.coach.fmtDuration
import com.hamuk.swimpro.coach.fmtPace
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/** How a metric combines across several swims (a day or a week). */
enum class Agg { SUM, BY_DISTANCE, BY_TIME }

/**
 * Every comparable metric: how to read it from [SwimMetrics], how to combine it, how to format
 * it, and which direction counts as "better" (null = neither, just different).
 */
enum class Metric(
    val label: String,
    val unit: String,
    val higherIsBetter: Boolean?,
    val agg: Agg,
    val get: (SwimMetrics) -> Double?,
) {
    DISTANCE("Distance", "m", true, Agg.SUM, { it.distanceM }),
    TIME("Workout time", "", null, Agg.SUM, { it.activeSec.toDouble() }),
    MOVING_PACE("Moving pace", "/100m", false, Agg.BY_DISTANCE, { it.movingPace }),
    AVG_PACE("Avg pace", "/100m", false, Agg.BY_DISTANCE, { it.avgPace }),
    REST("Rest", "%", false, Agg.BY_TIME, { it.restPct }),
    AVG_HR("Avg heart rate", "bpm", null, Agg.BY_TIME, { it.avgHr?.toDouble() }),
    MAX_HR("Max heart rate", "bpm", null, Agg.BY_TIME, { it.maxHr?.toDouble() }),
    EF("Efficiency", "m/min/bpm", true, Agg.BY_DISTANCE, { it.ef }),
    DECOUPLING("Aerobic drift", "%", false, Agg.BY_TIME, { it.decouplingPct }),
    SWOLF("SWOLF", "", false, Agg.BY_DISTANCE, { it.swolf }),
    STROKE_RATE("Stroke rate", "spm", null, Agg.BY_TIME, { it.strokeRate }),
    DPS("Distance / stroke", "m", true, Agg.BY_DISTANCE, { it.dps }),
    SPLIT("Split", "%", false, Agg.BY_DISTANCE, { it.splitPct }),
    CV("Pace variation", "%", false, Agg.BY_DISTANCE, { it.cvPct }),
    LOAD("Training load", "sTSS", null, Agg.SUM, { it.sTss }),
    TRIMP("TRIMP", "", null, Agg.SUM, { it.trimp }),
    KCAL("Calories", "kcal", null, Agg.SUM, { it.kcal }),
    ;

    fun format(v: Double): String = when (this) {
        MOVING_PACE, AVG_PACE -> fmtPace(v)
        TIME -> fmtDuration(v)
        DISTANCE -> "%,.0f".format(v)
        EF, DPS -> "%.2f".format(v)
        REST, DECOUPLING, SPLIT, CV -> "%.1f".format(v)
        else -> "%.0f".format(v)
    }

    /** Formats a difference, e.g. "−0:04" for pace or "+120" for distance. */
    fun formatDelta(d: Double): String {
        val sign = if (d > 0) "+" else if (d < 0) "−" else "±"
        return sign + when (this) {
            MOVING_PACE, AVG_PACE, TIME -> fmtDuration(abs(d))
            else -> format(abs(d))
        }
    }

    companion object {
        /** Headline set shown in delta tables, in display order. */
        val headline = listOf(DISTANCE, TIME, MOVING_PACE, AVG_PACE, AVG_HR, EF, DECOUPLING, SWOLF, STROKE_RATE, DPS, SPLIT, CV, REST, LOAD, KCAL)
    }
}

data class MetricDelta(val metric: Metric, val a: Double?, val b: Double?) {
    val diff: Double? get() = if (a != null && b != null) a - b else null
    val pct: Double? get() = if (a != null && b != null && b != 0.0) (a - b) / abs(b) * 100 else null
    /** true = improved, false = worse, null = neutral or unknown. Tiny changes count as neutral. */
    val better: Boolean? get() {
        val d = diff ?: return null
        val dir = metric.higherIsBetter ?: return null
        val p = pct
        if (abs(d) < 1e-9 || (p != null && abs(p) < 1.0)) return null
        return if (dir) d > 0 else d < 0
    }
}

/** Your normal range for a metric over a window. */
data class Baseline(val metric: Metric, val mean: Double, val sd: Double, val n: Int) {
    fun z(x: Double): Double? = if (sd > 1e-9) (x - mean) / sd else null
    fun label(x: Double): String {
        val z = z(x) ?: return "normal"
        return when {
            z > 1 -> "above normal"
            z < -1 -> "below normal"
            else -> "normal"
        }
    }
    val low: Double get() = mean - sd
    val high: Double get() = mean + sd
}

object Baselines {
    /** 28-day baseline from swims before [beforeDay] (exclusive), skipping [excludeId]. */
    fun of(metric: Metric, all: List<SwimMetrics>, beforeDay: Long, windowDays: Int = 28, excludeId: Long? = null): Baseline? {
        val xs = all.filter { it.epochDay in (beforeDay - windowDays) until beforeDay && it.swimId != excludeId }
            .mapNotNull(metric.get)
        if (xs.size < 3) return null
        val mean = xs.average()
        val sd = sqrt(xs.sumOf { (it - mean).pow(2) } / xs.size)
        return Baseline(metric, mean, sd, xs.size)
    }

    /** Combines several swims into one value per metric (a day or a week). */
    fun aggregate(metric: Metric, swims: List<SwimMetrics>): Double? {
        val pairs = swims.mapNotNull { m -> metric.get(m)?.let { it to m } }
        if (pairs.isEmpty()) return null
        return when (metric.agg) {
            Agg.SUM -> pairs.sumOf { it.first }
            Agg.BY_DISTANCE -> weighted(pairs.map { it.first to it.second.distanceM })
            Agg.BY_TIME -> weighted(pairs.map { it.first to it.second.activeSec.toDouble() })
        }
    }

    private fun weighted(xs: List<Pair<Double, Double>>): Double? {
        val w = xs.sumOf { it.second }
        return if (w <= 0) xs.map { it.first }.average() else xs.sumOf { it.first * it.second } / w
    }

    fun compare(a: List<SwimMetrics>, b: List<SwimMetrics>, metrics: List<Metric> = Metric.headline): List<MetricDelta> =
        metrics.map { MetricDelta(it, aggregate(it, a), aggregate(it, b)) }.filter { it.a != null || it.b != null }
}
