package com.hamuk.swimpro.analysis

/**
 * Heart rate vs pace. Pace is s/100 m (lower = faster), so a fitter swimmer shows a lower HR at
 * the same pace, or a faster pace at the same HR.
 */
object HrPace {
    data class Point(val swimId: Long, val epochDay: Long, val pace: Double, val hr: Double)

    /** Lengths of one swim as (pace, HR) points plus the fitted line and outlier lengths. */
    data class Within(val points: List<LapPoint>, val fit: Fit?, val outliers: Set<Int>)

    fun within(m: SwimMetrics): Within? {
        val pts = m.laps.filter { it.hr != null }
        if (pts.size < 4) return null
        val fit = Fit.of(pts.map { it.pacePer100 to it.hr!! })
        val outliers = if (fit == null) emptySet() else {
            val resid = pts.map { it.hr!! - fit.y(it.pacePer100) }
            val sd = kotlin.math.sqrt(resid.sumOf { it * it } / resid.size)
            pts.zip(resid).filter { sd > 0 && kotlin.math.abs(it.second) > 2 * sd }.map { it.first.index }.toSet()
        }
        return Within(pts, fit, outliers)
    }

    fun points(all: List<SwimMetrics>): List<Point> = all.mapNotNull { m ->
        val p = m.pace ?: return@mapNotNull null
        val hr = m.avgHr ?: return@mapNotNull null
        if (m.openWater || m.distanceM < 100) null else Point(m.swimId, m.epochDay, p, hr.toDouble())
    }

    /**
     * Pace (s/100 m) you'd hold at [atHr] bpm, from swims in the 28 days up to [endDay].
     * Null unless the fit is meaningful: ≥4 swims and HR rising as pace gets faster.
     */
    fun paceAtHr(points: List<Point>, endDay: Long, atHr: Double, windowDays: Int = 28): Double? {
        val win = points.filter { it.epochDay in (endDay - windowDays + 1)..endDay }
        if (win.size < 4) return null
        val fit = Fit.of(win.map { it.pace to it.hr })
        if (fit != null && fit.b < 0) {
            val pace = fit.x(atHr)
            // Don't extrapolate wildly beyond the observed paces.
            val lo = win.minOf { it.pace } * 0.85
            val hi = win.maxOf { it.pace } * 1.15
            if (pace != null && pace in lo..hi) return pace
        }
        // Fallback when the line doesn't fit (e.g. tired days are both slower and higher-HR):
        // the median pace of swims within ±5 bpm of the target.
        val near = win.filter { kotlin.math.abs(it.hr - atHr) <= 5 }.map { it.pace }.sorted()
        return if (near.size >= 3) near[near.size / 2] else null
    }

    /** Weekly series of "pace at [atHr]" ending today, for the trend chart. */
    fun trend(points: List<Point>, today: Long, atHr: Double, weeks: Int = 12): List<Pair<Long, Double>> =
        (weeks - 1 downTo 0).mapNotNull { w ->
            val end = today - w * 7L
            paceAtHr(points, end, atHr)?.let { end to it }
        }
}
