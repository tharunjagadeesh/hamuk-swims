package com.hamuk.swimpro.analysis

import com.hamuk.swimpro.coach.CoachRepository.Companion.fmtDay
import com.hamuk.swimpro.coach.fmtPace
import kotlin.math.roundToInt

/** Compact plain-text analysis for the Gemini coach's system prompt and swim reviews. */
object AiContext {
    fun summary(st: AnalysisState, lifestyle: LifestyleRepository, goal: GoalNav?): String = buildString {
        if (!st.ready || st.swims.isEmpty()) return@buildString
        appendLine("## Analysis (computed on the phone)")
        appendLine("Max HR ${st.hr.maxHr}, resting HR ${st.hr.restingHr}, CSS ${fmtPace(st.cssPer100)}/100m")
        st.loadStateAt(st.today)?.let {
            appendLine("Training load: fitness %.0f, fatigue %.0f, form %+.0f (%s), ACWR %s (%s)".format(
                it.fitness, it.fatigue, it.form, LoadModel.formLabel(it.form), it.acwr?.let { a -> "%.2f".format(a) } ?: "n/a", LoadModel.acwrLabel(it.acwr)))
        }
        val pts = HrPace.points(st.swims)
        HrPace.paceAtHr(pts, st.today, st.markerHr)?.let { now ->
            val before = HrPace.paceAtHr(pts, st.today - 28, st.markerHr)
            appendLine("Pace at ${st.markerHr.roundToInt()} bpm: ${fmtPace(now)}/100m" + (before?.let { " (4 weeks ago ${fmtPace(it)})" } ?: ""))
        }
        lifestyle.readiness.value?.let { r -> appendLine("Readiness today: ${r.score}/100, ${r.label} (" + r.parts.joinToString("; ") { "${it.first} ${it.second}" } + ")") }
        val findings = Lifestyle.findings(lifestyle.contexts(st), lifestyle.outcomes(st))
        if (findings.isNotEmpty()) {
            appendLine("Lifestyle links found in this swimmer's data:")
            findings.take(4).forEach { appendLine("- " + it.verdict) }
        }
        goal?.let { g ->
            appendLine("Goal navigator: ${g.statusText} Days left ${g.daysLeft}." +
                (g.requiredPerWeek?.let { " Needed per week: ${GoalMath.fmt(kotlin.math.abs(it), g.unit)}." } ?: "") +
                (g.goalPace?.let { " Race pace needed ${fmtPace(it)}/100m." } ?: ""))
        }
        appendLine("Recent swims (newest first):")
        st.swims.takeLast(8).reversed().forEach { m -> appendLine("- " + swimLine(m)) }
    }

    fun swimLine(m: SwimMetrics): String = listOfNotNull(
        "${m.epochDay.fmtDay()} ${m.distanceM.roundToInt()}m",
        m.pace?.let { "pace ${fmtPace(it)}" },
        m.avgHr?.let { "HR $it" },
        m.sTss?.let { "load %.0f".format(it) },
        m.ef?.let { "EF %.2f".format(it) },
        m.decouplingPct?.let { "drift %.1f%%".format(it) },
        m.splitPct?.let { "split %+.1f%%".format(it) },
        m.fadePct?.let { "fade %+.1f%%".format(it) },
        m.longestContinuousM?.let { "longest non-stop ${it.roundToInt()}m" },
        m.zoneSec?.let { z -> "zones " + z.joinToString("/") { "${(it / 60).roundToInt()}" } + " min" },
    ).joinToString(", ")

    fun forSwim(st: AnalysisState, lifestyle: LifestyleRepository, swimId: Long): String {
        val m = st.byId(swimId) ?: return ""
        val c = Lifestyle.context(m, lifestyle.days.value)
        return buildString {
            appendLine("Analysis: " + swimLine(m))
            st.insightsFor(m).forEach { appendLine("Insight: ${it.text}") }
            appendLine("Before the swim: " + listOfNotNull(
                c.sleepMin?.let { "sleep %.1f h".format(it / 60.0) + (c.sleepVsNormalMin?.let { d -> " (%+.1f h vs normal)".format(d / 60) } ?: "") },
                c.restingHr?.let { "resting HR $it" },
                c.hrvMs?.let { "HRV %.0f ms".format(it) },
                c.stepsPrevDay?.let { "steps day before $it" },
                c.exerciseMinPrevDay?.let { "other exercise day before $it min" },
            ).joinToString(", ").ifEmpty { "no data" })
        }
    }
}
