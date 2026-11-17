package com.hamuk.swimpro.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.reflect.KClass

/** Thin wrapper around Health Connect for reading swim data written by Samsung Health. */
class HealthConnectManager(private val context: Context) {

    val sdkStatus: Int get() = HealthConnectClient.getSdkStatus(context)
    val available: Boolean get() = sdkStatus == HealthConnectClient.SDK_AVAILABLE

    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    val corePermissions = setOf(
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(SpeedRecord::class),
    )

    val restingHrPermission = HealthPermission.getReadPermission(RestingHeartRateRecord::class)

    /** Optional context used for readiness and lifestyle-impact analysis. */
    val wellnessPermissions = setOf(
        restingHrPermission,
        HealthPermission.getReadPermission(androidx.health.connect.client.records.SleepSessionRecord::class),
        HealthPermission.getReadPermission(androidx.health.connect.client.records.StepsRecord::class),
        HealthPermission.getReadPermission(androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord::class),
    )

    val allPermissions = corePermissions + wellnessPermissions + setOf(PERMISSION_HISTORY, PERMISSION_BACKGROUND)

    /** Median resting HR over the last [days] days, or null if not permitted / no data. */
    suspend fun restingHrMedian(days: Long = 30): Int? {
        if (!available || restingHrPermission !in grantedPermissions()) return null
        val recs = runCatching {
            readAll(RestingHeartRateRecord::class, TimeRangeFilter.after(Instant.now().minus(Duration.ofDays(days))))
        }.getOrNull() ?: return null
        val xs = recs.map { it.beatsPerMinute.toInt() }.filter { it in 30..110 }.sorted()
        return if (xs.isEmpty()) null else xs[xs.size / 2]
    }

    suspend fun grantedPermissions(): Set<String> =
        if (available) client.permissionController.getGrantedPermissions() else emptySet()

    suspend fun hasExercisePermission(): Boolean =
        HealthPermission.getReadPermission(ExerciseSessionRecord::class) in grantedPermissions()

    suspend fun readSwimSessions(since: Instant): List<ExerciseSessionRecord> =
        readAll(ExerciseSessionRecord::class, TimeRangeFilter.after(since))
            .filter { it.exerciseType in SWIM_TYPES }

    suspend fun readAllSessions(since: Instant): List<ExerciseSessionRecord> =
        readAll(ExerciseSessionRecord::class, TimeRangeFilter.after(since))

    suspend fun heartRate(start: Instant, end: Instant): List<HeartRateRecord.Sample> =
        readAll(HeartRateRecord::class, TimeRangeFilter.between(start, end))
            .flatMap { it.samples }
            .filter { !it.time.isBefore(start) && !it.time.isAfter(end) }
            .sortedBy { it.time }

    data class SessionTotals(
        val distanceM: Double?,
        val totalKcal: Double?,
        val activeKcal: Double?,
        val avgHr: Long?,
        val maxHr: Long?,
        val minHr: Long?,
        val activeDuration: Duration?,
    )

    /** Aggregates the metrics we have permission for over the session's time range. */
    suspend fun totals(session: ExerciseSessionRecord): SessionTotals {
        val granted = grantedPermissions()
        fun has(type: KClass<out Record>) = HealthPermission.getReadPermission(type) in granted
        val metrics = buildSet {
            if (has(DistanceRecord::class)) add(DistanceRecord.DISTANCE_TOTAL)
            if (has(TotalCaloriesBurnedRecord::class)) add(TotalCaloriesBurnedRecord.ENERGY_TOTAL)
            if (has(ActiveCaloriesBurnedRecord::class)) add(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
            if (has(HeartRateRecord::class)) {
                add(HeartRateRecord.BPM_AVG); add(HeartRateRecord.BPM_MAX); add(HeartRateRecord.BPM_MIN)
            }
            add(ExerciseSessionRecord.EXERCISE_DURATION_TOTAL)
        }
        val range = TimeRangeFilter.between(session.startTime, session.endTime)
        val origin = setOf(DataOrigin(session.metadata.dataOrigin.packageName))
        val r = runCatching { client.aggregate(AggregateRequest(metrics, range, origin)) }.getOrNull()
        // Fall back to any app's data when the session's own app didn't write distance/HR.
        val anyOrigin: AggregationResult? =
            if (r?.get(DistanceRecord.DISTANCE_TOTAL) == null || r.get(HeartRateRecord.BPM_AVG) == null) {
                runCatching { client.aggregate(AggregateRequest(metrics, range)) }.getOrNull()
            } else null
        return SessionTotals(
            distanceM = r?.get(DistanceRecord.DISTANCE_TOTAL)?.inMeters
                ?: anyOrigin?.get(DistanceRecord.DISTANCE_TOTAL)?.inMeters,
            totalKcal = r?.get(TotalCaloriesBurnedRecord.ENERGY_TOTAL)?.inKilocalories,
            activeKcal = r?.get(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)?.inKilocalories,
            avgHr = r?.get(HeartRateRecord.BPM_AVG) ?: anyOrigin?.get(HeartRateRecord.BPM_AVG),
            maxHr = r?.get(HeartRateRecord.BPM_MAX) ?: anyOrigin?.get(HeartRateRecord.BPM_MAX),
            minHr = r?.get(HeartRateRecord.BPM_MIN) ?: anyOrigin?.get(HeartRateRecord.BPM_MIN),
            activeDuration = r?.get(ExerciseSessionRecord.EXERCISE_DURATION_TOTAL),
        )
    }

    /**
     * Human-readable dump of everything Health Connect returns around recent swims, so we can
     * see exactly which metrics Samsung Health exports from the watch.
     */
    suspend fun inspect(days: Long = 60): String = buildString {
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
        appendLine("SDK status: $sdkStatus (3 = available)")
        val granted = grantedPermissions()
        appendLine("Granted permissions:")
        allPermissions.forEach { appendLine("  ${if (it in granted) "✓" else "✗"} ${it.substringAfterLast('.')}") }
        if (!available) return@buildString

        val since = Instant.now().minus(Duration.ofDays(days))
        val all = runCatching { readAllSessions(since) }.getOrElse {
            appendLine("Reading sessions failed: ${it.message}"); return@buildString
        }
        appendLine()
        appendLine("All exercise sessions in last $days days: ${all.size}")
        all.groupBy { it.exerciseType }.forEach { (type, list) ->
            appendLine("  type $type (${exerciseTypeName(type)}): ${list.size}")
        }
        val swims = all.filter { it.exerciseType in SWIM_TYPES }.sortedByDescending { it.startTime }.take(5)
        appendLine()
        appendLine("Most recent swim sessions (up to 5): ${swims.size}")
        for (s in swims) {
            appendLine("────────────────")
            appendLine("id: ${s.metadata.id}")
            appendLine("origin: ${s.metadata.dataOrigin.packageName}")
            appendLine("device: ${s.metadata.device?.manufacturer} ${s.metadata.device?.model}")
            appendLine("type: ${s.exerciseType} ${exerciseTypeName(s.exerciseType)}")
            appendLine("time: ${fmt.format(s.startTime)} → ${fmt.format(s.endTime)}")
            appendLine("title: ${s.title}  notes: ${s.notes}")
            appendLine("segments: ${s.segments.size}")
            s.segments.take(40).forEach {
                appendLine("  ${segmentName(it.segmentType)} reps=${it.repetitions} ${secs(it.startTime, it.endTime)}s")
            }
            appendLine("laps: ${s.laps.size}")
            s.laps.take(40).forEach {
                appendLine("  len=${it.length?.inMeters}m ${secs(it.startTime, it.endTime)}s")
            }
            val t = runCatching { totals(s) }.getOrNull()
            appendLine("totals: $t")
            val range = TimeRangeFilter.between(s.startTime, s.endTime)
            for ((label, type) in listOf<Pair<String, KClass<out Record>>>(
                "Distance" to DistanceRecord::class,
                "Speed" to SpeedRecord::class,
                "HeartRate" to HeartRateRecord::class,
                "TotalCalories" to TotalCaloriesBurnedRecord::class,
                "ActiveCalories" to ActiveCaloriesBurnedRecord::class,
            )) {
                val recs = runCatching { readAll(type, range) }.getOrNull()
                if (recs == null) {
                    appendLine("$label: (no permission / error)")
                } else {
                    val origins = recs.groupingBy { it.metadata.dataOrigin.packageName }.eachCount()
                    appendLine("$label records: ${recs.size} $origins")
                    if (type == DistanceRecord::class) recs.take(10).forEach {
                        val d = it as DistanceRecord
                        appendLine("   ${d.distance.inMeters}m ${secs(d.startTime, d.endTime)}s")
                    }
                    if (type == HeartRateRecord::class) {
                        appendLine("   samples: ${recs.sumOf { (it as HeartRateRecord).samples.size }}")
                    }
                }
            }
        }
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

    private fun secs(a: Instant, b: Instant) = Duration.between(a, b).seconds

    companion object {
        const val PERMISSION_HISTORY = "android.permission.health.READ_HEALTH_DATA_HISTORY"
        const val PERMISSION_BACKGROUND = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"

        val SWIM_TYPES = setOf(
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL,
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER,
        )

        fun exerciseTypeName(type: Int): String = when (type) {
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL -> "SWIMMING_POOL"
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "SWIMMING_OPEN_WATER"
            ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "WALKING"
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> "RUNNING"
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> "BIKING"
            ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT -> "OTHER_WORKOUT"
            else -> "other"
        }

        /** Maps a Health Connect segment type to a stroke name, or null for non-stroke segments. */
        fun strokeName(type: Int): String? = when (type) {
            ExerciseSegment.EXERCISE_SEGMENT_TYPE_SWIMMING_FREESTYLE -> "Freestyle"
            ExerciseSegment.EXERCISE_SEGMENT_TYPE_SWIMMING_BACKSTROKE -> "Backstroke"
            ExerciseSegment.EXERCISE_SEGMENT_TYPE_SWIMMING_BREASTSTROKE -> "Breaststroke"
            ExerciseSegment.EXERCISE_SEGMENT_TYPE_SWIMMING_BUTTERFLY -> "Butterfly"
            ExerciseSegment.EXERCISE_SEGMENT_TYPE_SWIMMING_MIXED -> "Mixed"
            ExerciseSegment.EXERCISE_SEGMENT_TYPE_SWIMMING_OPEN_WATER -> "Open water"
            ExerciseSegment.EXERCISE_SEGMENT_TYPE_SWIMMING_POOL -> "Pool"
            ExerciseSegment.EXERCISE_SEGMENT_TYPE_SWIMMING_OTHER -> "Other"
            else -> null
        }

        fun segmentName(type: Int): String = strokeName(type) ?: when (type) {
            ExerciseSegment.EXERCISE_SEGMENT_TYPE_REST -> "Rest"
            ExerciseSegment.EXERCISE_SEGMENT_TYPE_PAUSE -> "Pause"
            else -> "segment($type)"
        }
    }
}
