package com.hamuk.swimpro.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.hamuk.swimpro.data.DailyWellness
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import kotlin.reflect.KClass

/**
 * Reads daily context (sleep, steps, calories, other workouts, resting HR, HRV) from Health
 * Connect. Source-agnostic: Samsung Health, Google Fit, Fitbit or any other app that writes to
 * Health Connect is picked up automatically.
 */
class WellnessReader(private val context: Context, private val hc: HealthConnectManager) {
    private val client by lazy { HealthConnectClient.getOrCreate(context) }
    private val zone: ZoneId get() = ZoneId.systemDefault()

    val permissions = setOf(
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
    )

    suspend fun missingPermissions(): Set<String> =
        if (!hc.available) emptySet() else (permissions + hc.restingHrPermission) - hc.grantedPermissions()

    suspend fun read(from: LocalDate, to: LocalDate): List<DailyWellness> {
        if (!hc.available) return emptyList()
        val granted = hc.grantedPermissions()
        fun has(t: KClass<out Record>) = HealthPermission.getReadPermission(t) in granted
        val days = HashMap<Long, DailyWellness>()
        fun day(d: Long) = days.getOrPut(d) { DailyWellness(epochDay = d, updatedMs = System.currentTimeMillis()) }

        // Steps & calories: Health Connect aggregates de-duplicate across apps by user priority.
        val metrics = buildSet {
            if (has(StepsRecord::class)) add(StepsRecord.COUNT_TOTAL)
            if (has(TotalCaloriesBurnedRecord::class)) add(TotalCaloriesBurnedRecord.ENERGY_TOTAL)
            if (has(ActiveCaloriesBurnedRecord::class)) add(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
        }
        if (metrics.isNotEmpty()) runCatching {
            client.aggregateGroupByPeriod(
                AggregateGroupByPeriodRequest(
                    metrics = metrics,
                    timeRangeFilter = TimeRangeFilter.between(from.atStartOfDay(), to.plusDays(1).atStartOfDay()),
                    timeRangeSlicer = Period.ofDays(1),
                ),
            ).forEach { g ->
                val d = g.startTime.toLocalDate().toEpochDay()
                days[d] = day(d).copy(
                    steps = g.result[StepsRecord.COUNT_TOTAL],
                    totalKcal = g.result[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories,
                    activeKcal = g.result[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories,
                )
            }
        }

        val range = TimeRangeFilter.between(from.minusDays(1).atStartOfDay(zone).toInstant(), to.plusDays(1).atStartOfDay(zone).toInstant())

        // Sleep: the longest session that ended on each day (duplicates from two apps collapse).
        if (has(SleepSessionRecord::class)) runCatching { readAll(SleepSessionRecord::class, range) }.getOrNull()?.let { sessions ->
            sessions.groupBy { it.endTime.atZone(zone).toLocalDate().toEpochDay() }.forEach { (d, list) ->
                val main = list.maxByOrNull { Duration.between(it.startTime, it.endTime).toMinutes() } ?: return@forEach
                fun stageMin(vararg types: Int) = main.stages.filter { it.stage in types }
                    .sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }.toInt().takeIf { main.stages.isNotEmpty() }
                val awake = stageMin(SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED, SleepSessionRecord.STAGE_TYPE_OUT_OF_BED) ?: 0
                val total = Duration.between(main.startTime, main.endTime).toMinutes().toInt() - awake
                days[d] = day(d).copy(
                    sleepMin = total,
                    deepMin = stageMin(SleepSessionRecord.STAGE_TYPE_DEEP),
                    remMin = stageMin(SleepSessionRecord.STAGE_TYPE_REM),
                    lightMin = stageMin(SleepSessionRecord.STAGE_TYPE_LIGHT),
                    awakeMin = awake.takeIf { main.stages.isNotEmpty() },
                    sleepStartMs = main.startTime.toEpochMilli(),
                    sleepEndMs = main.endTime.toEpochMilli(),
                    sleepSource = main.metadata.dataOrigin.packageName,
                )
            }
        }

        // Other exercise (not swimming), merging overlapping sessions from different apps.
        runCatching { readAll(ExerciseSessionRecord::class, range) }.getOrNull()?.let { sessions ->
            sessions.filter { it.exerciseType !in HealthConnectManager.SWIM_TYPES }
                .groupBy { it.startTime.atZone(zone).toLocalDate().toEpochDay() }
                .forEach { (d, list) ->
                    days[d] = day(d).copy(
                        exerciseMin = mergedMinutes(list.map { it.startTime to it.endTime }),
                        exerciseTypes = list.map { HealthConnectManager.exerciseTypeName(it.exerciseType).lowercase() }.distinct().joinToString(", "),
                    )
                }
        }

        if (hc.restingHrPermission in granted) runCatching { readAll(RestingHeartRateRecord::class, range) }.getOrNull()?.let { recs ->
            recs.groupBy { it.time.atZone(zone).toLocalDate().toEpochDay() }.forEach { (d, l) ->
                days[d] = day(d).copy(restingHr = l.minOf { it.beatsPerMinute }.toInt())
            }
        }
        if (has(HeartRateVariabilityRmssdRecord::class)) runCatching { readAll(HeartRateVariabilityRmssdRecord::class, range) }.getOrNull()?.let { recs ->
            recs.groupBy { it.time.atZone(zone).toLocalDate().toEpochDay() }.forEach { (d, l) ->
                days[d] = day(d).copy(hrvMs = l.map { it.heartRateVariabilityMillis }.average())
            }
        }
        return days.values.filter { it.epochDay in from.toEpochDay()..to.toEpochDay() }.sortedBy { it.epochDay }
    }

    data class Source(val packageName: String, val name: String, val provides: Set<String>)

    /** Apps that have written data we use to Health Connect recently, with what each provides. */
    suspend fun sources(): List<Source> {
        if (!hc.available) return emptyList()
        val granted = hc.grantedPermissions()
        val now = Instant.now()
        val out = HashMap<String, MutableSet<String>>()
        suspend fun <T : Record> scan(type: KClass<T>, label: String, days: Long) {
            if (HealthPermission.getReadPermission(type) !in granted) return
            runCatching { readAll(type, TimeRangeFilter.between(now.minus(Duration.ofDays(days)), now)) }.getOrNull()
                ?.forEach { out.getOrPut(it.metadata.dataOrigin.packageName) { mutableSetOf() } += label }
        }
        runCatching { readAll(ExerciseSessionRecord::class, TimeRangeFilter.between(now.minus(Duration.ofDays(60)), now)) }.getOrNull()?.forEach {
            out.getOrPut(it.metadata.dataOrigin.packageName) { mutableSetOf() } += if (it.exerciseType in HealthConnectManager.SWIM_TYPES) "swims" else "workouts"
        }
        scan(SleepSessionRecord::class, "sleep", 30)
        scan(StepsRecord::class, "steps", 2)
        scan(RestingHeartRateRecord::class, "resting HR", 30)
        scan(HeartRateVariabilityRmssdRecord::class, "HRV", 30)
        return out.map { (pkg, kinds) -> Source(pkg, appName(pkg), kinds) }.sortedBy { it.name }
    }

    private suspend fun <T : Record> readAll(type: KClass<T>, range: TimeRangeFilter): List<T> {
        val out = mutableListOf<T>()
        var token: String? = null
        do {
            val resp = client.readRecords(ReadRecordsRequest(recordType = type, timeRangeFilter = range, pageToken = token))
            out += resp.records
            token = resp.pageToken
        } while (token != null)
        return out
    }

    companion object {
        fun appName(pkg: String): String = when (pkg) {
            "com.sec.android.app.shealth" -> "Samsung Health"
            "com.google.android.apps.fitness" -> "Google Fit"
            "com.fitbit.FitbitMobile" -> "Fitbit"
            "com.google.android.apps.healthdata" -> "Health Connect"
            "com.google.android.wearable.healthservices" -> "Pixel Watch"
            "com.garmin.android.apps.connectmobile" -> "Garmin Connect"
            "com.strava" -> "Strava"
            "com.hamuk.swimpro" -> "Hamuk Swims"
            else -> pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        }

        /** Total minutes covered by possibly-overlapping intervals. */
        fun mergedMinutes(intervals: List<Pair<Instant, Instant>>): Int {
            var total = 0L
            var curStart: Instant? = null
            var curEnd: Instant? = null
            for ((s, e) in intervals.sortedBy { it.first }) {
                if (curEnd == null || s.isAfter(curEnd)) {
                    if (curStart != null) total += Duration.between(curStart, curEnd).toMinutes()
                    curStart = s; curEnd = e
                } else if (e.isAfter(curEnd)) curEnd = e
            }
            if (curStart != null) total += Duration.between(curStart, curEnd).toMinutes()
            return total.toInt()
        }
    }
}
