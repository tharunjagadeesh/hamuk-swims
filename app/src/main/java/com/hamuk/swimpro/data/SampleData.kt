package com.hamuk.swimpro.data

import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Debug-only generator: 10 weeks of realistic pool swims (lengths, strokes, HR) and daily
 * wellness. Built-in signals for testing the analysis: fitness improves over time, and less
 * sleep makes the next swim slower with a higher heart rate.
 */
object SampleData {
    const val SOURCE = "sample"

    suspend fun load(db: AppDatabase, weeks: Int = 10) {
        val rnd = Random(42)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val start = today.minusWeeks(weeks.toLong())
        val wellness = mutableListOf<DailyWellness>()
        var day = start
        while (!day.isAfter(today)) {
            val sleepMin = (420 + rnd.nextInt(-90, 75))
            val rhr = 52 + ((420 - sleepMin) / 30.0).roundToInt() + rnd.nextInt(-1, 2)
            wellness += DailyWellness(
                epochDay = day.toEpochDay(), sleepMin = sleepMin, deepMin = (sleepMin * (0.15 + rnd.nextDouble(0.08))).toInt(),
                remMin = (sleepMin * 0.2).toInt(), lightMin = (sleepMin * 0.55).toInt(), awakeMin = rnd.nextInt(5, 30),
                steps = rnd.nextLong(3500, 14000), totalKcal = 2100.0 + rnd.nextInt(600), activeKcal = 250.0 + rnd.nextInt(500),
                exerciseMin = if (rnd.nextInt(4) == 0) rnd.nextInt(20, 60) else 0, exerciseTypes = "walking",
                restingHr = rhr, hrvMs = 55.0 + (sleepMin - 420) / 6.0 + rnd.nextDouble(-6.0, 6.0), sleepSource = SOURCE,
                updatedMs = System.currentTimeMillis(),
            )
            val swimDay = day.dayOfWeek.value in setOf(1, 3, 5, 6) && rnd.nextInt(6) != 0
            if (swimDay) {
                val weeksIn = (day.toEpochDay() - start.toEpochDay()) / 7.0
                val sleepEffect = (420 - sleepMin) / 60.0 * 2.5 // s/100 m slower per hour short
                val basePace = 165 - weeksIn * 1.2 + sleepEffect + rnd.nextDouble(-2.0, 2.0)
                insertSwim(db, day.atTime(7, 15).atZone(zone).toInstant().toEpochMilli(), basePace, sleepEffect, rnd)
            }
            day = day.plusDays(1)
        }
        db.wellness().saveAll(wellness)
    }

    private suspend fun insertSwim(db: AppDatabase, startMs: Long, basePace: Double, sleepEffect: Double, rnd: Random) {
        val lengths = 24 + rnd.nextInt(0, 9) * 4
        var t = startMs + 60_000
        val laps = mutableListOf<SwimLap>()
        val hr = mutableListOf<HrSample>()
        var sinceRest = 0
        val restEvery = 4 + rnd.nextInt(0, 5)
        for (i in 0 until lengths) {
            val fade = i.toDouble() / lengths * (4 + sleepEffect)
            val pace = basePace + fade + rnd.nextDouble(-3.0, 3.0)
            val sec = pace / 4
            val strokes = (17 + (pace - 150) / 10 + rnd.nextInt(-1, 2)).roundToInt()
            laps += SwimLap(swimId = 0, idx = i, startMs = t, endMs = t + (sec * 1000).toLong(), distanceM = 25.0, stroke = "Freestyle", strokes = strokes, kind = "lap")
            val bpm = (260 - pace * 0.62 + i * 0.25 + sleepEffect * 1.5 + rnd.nextDouble(-2.0, 2.0)).roundToInt()
            var s = t
            while (s < laps.last().endMs) { hr += HrSample(0, s, bpm); s += 5000 }
            t = laps.last().endMs + 2000
            if (++sinceRest >= restEvery) { t += rnd.nextLong(20_000, 40_000); sinceRest = 0 }
        }
        val end = t + 30_000
        val avgHr = hr.map { it.bpm }.average().roundToInt()
        val id = db.swims().insert(
            Swim(
                hcId = "sample-$startMs", startMs = startMs, endMs = end, distanceM = lengths * 25.0,
                activeSec = (end - startMs) / 1000, poolLengthM = 25, activeKcal = lengths * 8.0, avgHr = avgHr,
                maxHr = hr.maxOf { it.bpm }, minHr = hr.minOf { it.bpm }, source = SOURCE, strokeSummary = "Freestyle ${lengths * 25}m",
                rpe = (5 + sleepEffect / 2 + rnd.nextInt(-1, 2)).roundToInt().coerceIn(2, 9),
            ),
        )
        db.swims().replaceDetails(id, laps.map { it.copy(swimId = id) }, hr.map { it.copy(swimId = id) })
    }

    suspend fun clear(db: AppDatabase) {
        db.swims().all().filter { it.source == SOURCE }.forEach { db.swims().delete(it) }
        db.wellness().all().filter { it.sleepSource == SOURCE }.let { rows -> if (rows.isNotEmpty()) db.wellness().deleteDays(rows.map { it.epochDay }) }
    }
}
