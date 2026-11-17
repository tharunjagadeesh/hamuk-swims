package com.hamuk.swimpro.coach

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.ceil
import kotlin.math.roundToInt

val CoachJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * Training zones as offsets from threshold (CSS) pace per 100 m, following the common
 * Swim Smooth-style CSS zone model. Zone 0 means "easy, no pace target".
 */
object Zones {
    val names = mapOf(
        0 to "Easy",
        1 to "Z1 Recovery",
        2 to "Z2 Endurance",
        3 to "Z3 Tempo",
        4 to "Z4 Threshold (CSS)",
        5 to "Z5 Speed",
        6 to "Test effort",
    )

    /** Seconds per 100 m to add to CSS pace for each zone. */
    private val offsets = mapOf(1 to 15.0, 2 to 9.0, 3 to 5.0, 4 to 1.0, 5 to -4.0)

    fun pacePer100(cssPer100: Double, zone: Int): Double? = offsets[zone]?.let { cssPer100 + it }

    fun describe(cssPer100: Double): List<Pair<String, String>> = (1..5).map { z ->
        val p = pacePer100(cssPer100, z)!!
        names.getValue(z) to "${fmtPace(p)}/100m"
    }
}

@Serializable
data class SwimSet(
    val reps: Int,
    val distM: Int,
    val stroke: String = "Freestyle",
    val zone: Int = 2,
    val restSec: Int = 15,
    val drill: String? = null,
    val note: String? = null,
) {
    val totalM: Int get() = reps * distM
}

@Serializable
data class Block(val name: String, val sets: List<SwimSet>) {
    val totalM: Int get() = sets.sumOf { it.totalM }
}

@Serializable
data class Workout(
    val title: String,
    val sessionType: String,
    val focus: String,
    val blocks: List<Block>,
    val notes: String? = null,
) {
    val totalM: Int get() = blocks.sumOf { it.totalM }

    fun toJson(): String = CoachJson.encodeToString(serializer(), this)

    companion object {
        fun fromJson(json: String): Workout = CoachJson.decodeFromString(serializer(), json)
    }
}

/** Renders a set as e.g. "4 × 50m Freestyle · Z2 1:58/100 · rest 20s (go every 1:50)". */
fun SwimSet.describe(cssPer100: Double?): String {
    val head = if (reps > 1) "$reps × ${distM}m" else "${distM}m"
    val what = drill?.let { "$it drill" } ?: stroke
    val parts = mutableListOf("$head $what")
    val pace = cssPer100?.let { Zones.pacePer100(it, zone) }
    if (zone == 0 || pace == null) {
        parts += Zones.names.getValue(if (zone in 1..6) zone else 0)
    } else {
        parts += "${Zones.names.getValue(zone).substringBefore(' ')} ${fmtPace(pace)}/100"
    }
    if (reps > 1 || restSec > 0) {
        var rest = "rest ${restSec}s"
        if (pace != null && reps > 1 && drill == null) {
            val sendOff = roundUpTo(pace * distM / 100.0 + restSec, 5)
            rest += " (go every ${fmtDuration(sendOff.toDouble())})"
        }
        parts += rest
    }
    return parts.joinToString(" · ") + (note?.let { "\n   $it" } ?: "")
}

fun roundUpTo(x: Double, step: Int): Int = (ceil(x / step) * step).toInt()

fun fmtPace(secPer100: Double): String = fmtDuration(secPer100)

fun fmtDuration(totalSec: Double): String {
    val s = totalSec.roundToInt()
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** Parses "m:ss", "h:mm:ss" or plain seconds. */
fun parseDuration(text: String): Int? {
    val parts = text.trim().split(":").map { it.trim().toIntOrNull() ?: return null }
    return when (parts.size) {
        1 -> parts[0]
        2 -> parts[0] * 60 + parts[1]
        3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
        else -> null
    }.takeIf { it != null && it > 0 }
}
