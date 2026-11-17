package com.hamuk.swimpro.analysis

import com.hamuk.swimpro.coach.fmtPace
import kotlin.math.abs
import kotlin.math.roundToInt

enum class Tone { GOOD, WARN, INFO }

data class Insight(val text: String, val tone: Tone, val score: Double)

/**
 * Rule-based observations about one swim, ranked by how notable they are. Each rule only fires
 * when the change is big enough to matter, so a normal swim produces few or no insights.
 */
object Insights {
    fun forSwim(
        m: SwimMetrics,
        previous: SwimMetrics?,
        history: List<SwimMetrics>,
        load: LoadModel.State?,
        limit: Int = 3,
    ): List<Insight> {
        val out = mutableListOf<Insight>()
        val earlier = history.filter { it.startMs < m.startMs && it.swimId != m.swimId }

        // Pace vs your 28-day normal, with HR context.
        val pace = m.pace
        val paceBase = Baselines.of(if (m.movingPace != null) Metric.MOVING_PACE else Metric.AVG_PACE, history, m.epochDay, excludeId = m.swimId)
        if (pace != null && paceBase != null) {
            val pct = (paceBase.mean - pace) / paceBase.mean * 100 // positive = faster
            if (abs(pct) >= 3) {
                val hrBase = Baselines.of(Metric.AVG_HR, history, m.epochDay, excludeId = m.swimId)
                val hrNote = if (m.avgHr != null && hrBase != null) {
                    val d = (m.avgHr - hrBase.mean).roundToInt()
                    when {
                        d <= -2 -> " at ${abs(d)} bpm lower HR"
                        d >= 2 -> " at $d bpm higher HR"
                        else -> " at the same HR"
                    }
                } else ""
                out += Insight(
                    "Pace ${pct.absPct()} ${if (pct > 0) "faster" else "slower"} than your 28-day normal$hrNote.",
                    if (pct > 0) Tone.GOOD else Tone.INFO, abs(pct),
                )
            }
        }

        // Efficiency (speed per heartbeat) vs normal.
        val efBase = Baselines.of(Metric.EF, history, m.epochDay, excludeId = m.swimId)
        if (m.ef != null && efBase != null) {
            val pct = (m.ef - efBase.mean) / efBase.mean * 100
            if (abs(pct) >= 5) out += Insight(
                "Efficiency ${if (pct > 0) "up" else "down"} ${pct.absPct()}: ${if (pct > 0) "more speed per heartbeat than usual." else "you worked harder for your speed today."}",
                if (pct > 0) Tone.GOOD else Tone.WARN, abs(pct),
            )
        }

        // Aerobic drift.
        m.decouplingPct?.let { d ->
            if (d > 8) out += Insight("HR drifted ${d.roundToInt()} % against pace in the second half: aerobic endurance is the limiter. More steady Z2 work helps.", Tone.WARN, d)
            else if (d < 3 && m.minutes >= 20) out += Insight("Only ${"%.1f".format(d)} % aerobic drift: solid aerobic base for this distance.", Tone.GOOD, 4.0)
        }

        // Pacing.
        m.fadePct?.let { f -> if (f > 6) out += Insight("You faded: the last quarter was ${f.roundToInt()} % slower than the first. Start a touch easier.", Tone.WARN, f) }
        m.splitPct?.let { s -> if (s < -2) out += Insight("Negative split: second half ${abs(s).roundToInt()} % faster. Great pacing.", Tone.GOOD, abs(s) + 3) }
        m.cvPct?.let { c -> if (c < 4 && m.lapCount >= 8) out += Insight("Very even pacing (lengths within ${"%.1f".format(c)} %).", Tone.GOOD, 3.0) }

        // Records.
        if (earlier.isNotEmpty()) {
            if (m.distanceM > earlier.maxOf { it.distanceM }) out += Insight("Longest swim ever: ${"%,.0f".format(m.distanceM)} m.", Tone.GOOD, 30.0)
            val bestPace = earlier.filter { it.distanceM >= m.distanceM * 0.8 }.mapNotNull { it.pace }.minOrNull()
            if (pace != null && bestPace != null && pace < bestPace) out += Insight("Fastest pace at this distance: ${fmtPace(pace)}/100m.", Tone.GOOD, 25.0)
        }

        // vs previous swim (only if nothing bigger was said about pace).
        if (previous != null && pace != null && out.none { it.text.startsWith("Pace") }) {
            val pp = previous.pace
            if (pp != null) {
                val pct = (pp - pace) / pp * 100
                if (abs(pct) >= 2) out += Insight("${pct.absPct()} ${if (pct > 0) "faster" else "slower"} than your previous swim.", if (pct > 0) Tone.GOOD else Tone.INFO, abs(pct) * 0.8)
            }
        }

        // Load / readiness.
        load?.let { s ->
            s.acwr?.let { r -> if (r > 1.5) out += Insight("Training load spiked to ${"%.1f".format(r)}× your 4-week average: build gradually to avoid shoulder overuse.", Tone.WARN, 12 + r) }
            if (s.form < -25) out += Insight("Form ${s.form.roundToInt()}: you're carrying fatigue. Keep the next swim easy.", Tone.WARN, 10.0)
            else if (s.form > 15 && (s.acwr ?: 1.0) < 0.9) out += Insight("You're fresh (form +${s.form.roundToInt()}). Good day for a test or a hard set.", Tone.INFO, 6.0)
        }

        return out.sortedByDescending { it.score }.take(limit)
    }

    private fun Double.absPct() = "${abs(this).let { if (it >= 10) it.roundToInt().toString() else "%.1f".format(it) }} %"
}
