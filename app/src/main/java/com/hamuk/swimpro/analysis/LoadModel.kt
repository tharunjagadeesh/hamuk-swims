package com.hamuk.swimpro.analysis

/**
 * Training-load model (Banister impulse-response, as used by TrainingPeaks):
 * Fitness = 42-day EWMA of daily load, Fatigue = 7-day EWMA, Form = Fitness − Fatigue.
 * ACWR = mean daily load over 7 days ÷ mean over 28 days.
 */
object LoadModel {
    data class Day(val epochDay: Long, val load: Double, val fitness: Double, val fatigue: Double) {
        val form: Double get() = fitness - fatigue
    }

    data class State(val fitness: Double, val fatigue: Double, val form: Double, val acwr: Double?, val rampPerWeek: Double)

    fun series(dailyLoad: Map<Long, Double>, fromDay: Long, toDay: Long): List<Day> {
        var ctl = 0.0
        var atl = 0.0
        val out = ArrayList<Day>()
        for (d in fromDay..toDay) {
            val l = dailyLoad[d] ?: 0.0
            ctl += (l - ctl) / 42.0
            atl += (l - atl) / 7.0
            out += Day(d, l, ctl, atl)
        }
        return out
    }

    fun state(series: List<Day>): State? {
        if (series.isEmpty()) return null
        val last = series.last()
        val acute = series.takeLast(7).map { it.load }.average()
        val chronic = series.takeLast(28).map { it.load }.average()
        val weekAgo = series.getOrNull(series.size - 8)?.fitness ?: 0.0
        return State(last.fitness, last.fatigue, last.form, if (chronic > 0) acute / chronic else null, last.fitness - weekAgo)
    }

    /** Sum of each day's swim loads. */
    fun daily(metrics: List<SwimMetrics>): Map<Long, Double> =
        metrics.groupBy { it.epochDay }.mapValues { (_, l) -> l.sumOf { it.load } }

    fun acwrLabel(acwr: Double?): String = when {
        acwr == null -> "Not enough history"
        acwr < 0.8 -> "Below your usual load"
        acwr <= 1.3 -> "Sweet spot"
        acwr <= 1.5 -> "Ramping up fast"
        else -> "Load spike: injury risk"
    }

    fun formLabel(form: Double): String = when {
        form > 15 -> "Fresh"
        form > 5 -> "Rested"
        form >= -10 -> "Balanced"
        form >= -25 -> "Building fatigue"
        else -> "Very fatigued"
    }
}
