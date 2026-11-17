package com.hamuk.swimpro.data

import androidx.health.connect.client.records.ExerciseSessionRecord
import com.hamuk.swimpro.health.HealthConnectManager
import java.time.Duration
import java.time.Instant

data class SyncResult(val ok: Boolean, val message: String, val newSwims: Int = 0)

class SwimRepository(
    private val db: AppDatabase,
    private val health: HealthConnectManager,
) {
    private val dao = db.swims()

    val swims = dao.observeAll()
    fun swim(id: Long) = dao.observe(id)
    fun laps(id: Long) = dao.observeLaps(id)
    fun heartRate(id: Long) = dao.observeHr(id)

    suspend fun update(swim: Swim) = dao.update(swim)
    suspend fun delete(swim: Swim) = dao.delete(swim)
    suspend fun addManual(swim: Swim): Long = dao.insert(swim)

    /**
     * Imports swim sessions from Health Connect. Re-reads [lookbackDays] (or two years on first
     * sync). Sessions whose lastModifiedTime hasn't changed are skipped; user-entered fields are
     * preserved on re-import.
     */
    suspend fun syncFromHealthConnect(lastSyncMs: Long?, defaultPoolM: Int): SyncResult {
        if (!health.available) return SyncResult(false, "Health Connect is not available on this device")
        if (!health.hasExercisePermission()) return SyncResult(false, "Health Connect permission not granted")

        val since = if (lastSyncMs == null) {
            Instant.now().minus(Duration.ofDays(730))
        } else {
            Instant.ofEpochMilli(lastSyncMs).minus(Duration.ofDays(14))
        }
        val sessions = try {
            health.readSwimSessions(since)
        } catch (e: Exception) {
            return SyncResult(false, "Reading Health Connect failed: ${e.message}")
        }

        // The same swim may be recorded by two apps (e.g. Samsung Health and Google Fit): keep the richest.
        val keepIds = pickRichest(sessions.map {
            SessionCandidate(it.metadata.id, it.startTime.toEpochMilli(), it.endTime.toEpochMilli(), it.laps.size * 10 + it.segments.size)
        })
        var added = 0
        var updated = 0
        var merged = 0
        val known = dao.all()
        for (s in sessions.filter { it.metadata.id in keepIds }) {
            val existing = dao.byHcId(s.metadata.id)
            val modified = s.metadata.lastModifiedTime.toEpochMilli()
            if (existing != null && existing.hcModifiedMs == modified) continue
            if (existing == null) {
                // Already have this swim from another app? Keep whichever has more detail.
                val start = s.startTime.toEpochMilli(); val end = s.endTime.toEpochMilli()
                val twin = known.firstOrNull { it.hcId != null && overlapFraction(start, end, it.startMs, it.endMs) > 0.5 }
                if (twin != null) {
                    val twinRich = dao.laps(twin.id).count { it.kind == "lap" } * 10
                    if (s.laps.size * 10 + s.segments.size <= twinRich) { merged++; continue }
                    dao.delete(twin)
                    merged++
                }
            }
            importSession(s, existing, defaultPoolM)
            if (existing == null) added++ else updated++
        }
        val dup = (sessions.size - keepIds.size) + merged
        return SyncResult(true, "Synced: $added new, $updated updated (${sessions.size} swims checked" +
            (if (dup > 0) ", $dup duplicate${if (dup > 1) "s" else ""} from other apps merged" else "") + ")", added)
    }

    private suspend fun importSession(s: ExerciseSessionRecord, existing: Swim?, defaultPoolM: Int) {
        val totals = health.totals(s)
        val laps = buildLaps(s)
        val hr = runCatching { health.heartRate(s.startTime, s.endTime) }.getOrElse { emptyList() }

        val lapDistance = laps.filter { it.kind == "lap" }.sumOf { it.distanceM ?: 0.0 }
        val distance = totals.distanceM?.takeIf { it > 0 } ?: lapDistance
        val pool = inferPoolLength(laps) ?: existing?.poolLengthM ?: defaultPoolM
        val activeSec = totals.activeDuration?.seconds?.takeIf { it > 0 }
            ?: Duration.between(s.startTime, s.endTime).seconds

        val strokeSummary = laps.filter { it.stroke != null && it.stroke !in setOf("Pool", "Other") }
            .groupBy { it.stroke!! }
            .mapValues { (_, l) -> l.sumOf { it.distanceM ?: 0.0 } }
            .entries.sortedByDescending { it.value }
            .joinToString(" · ") { (k, v) -> if (v > 0) "$k ${v.toInt()}m" else k }
            .ifEmpty { null }

        val swim = Swim(
            id = existing?.id ?: 0,
            hcId = s.metadata.id,
            hcModifiedMs = s.metadata.lastModifiedTime.toEpochMilli(),
            startMs = s.startTime.toEpochMilli(),
            endMs = s.endTime.toEpochMilli(),
            distanceM = distance,
            activeSec = activeSec,
            poolLengthM = if (s.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER) null else pool,
            totalKcal = totals.totalKcal,
            activeKcal = totals.activeKcal,
            avgHr = totals.avgHr?.toInt(),
            maxHr = totals.maxHr?.toInt(),
            minHr = totals.minHr?.toInt(),
            openWater = s.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER,
            source = s.metadata.dataOrigin.packageName,
            title = s.title,
            hcNotes = s.notes,
            strokeSummary = strokeSummary,
            rpe = existing?.rpe,
            notes = existing?.notes,
            manualSwolf = existing?.manualSwolf,
        )
        val id = if (existing == null) dao.insert(swim) else swim.id.also { dao.update(swim) }
        dao.replaceDetails(
            id,
            laps.map { it.copy(swimId = id) },
            hr.map { HrSample(id, it.time.toEpochMilli(), it.beatsPerMinute.toInt()) }.distinctBy { it.timeMs },
        )
    }

    private fun buildLaps(s: ExerciseSessionRecord): List<SwimLap> {
        val strokeSegs = s.segments.filter { HealthConnectManager.strokeName(it.segmentType) != null }
        if (s.laps.isNotEmpty()) {
            return s.laps.sortedBy { it.startTime }.mapIndexed { i, lap ->
                val mid = lap.startTime.plus(Duration.between(lap.startTime, lap.endTime).dividedBy(2))
                val seg = strokeSegs.firstOrNull { !it.startTime.isAfter(mid) && !it.endTime.isBefore(mid) }
                // Only treat segment repetitions as a stroke count when the segment covers just this lap.
                val strokes = seg?.takeIf {
                    !it.startTime.isBefore(lap.startTime.minusSeconds(1)) &&
                        !it.endTime.isAfter(lap.endTime.plusSeconds(1)) && it.repetitions > 0
                }?.repetitions
                SwimLap(
                    swimId = 0, idx = i,
                    startMs = lap.startTime.toEpochMilli(), endMs = lap.endTime.toEpochMilli(),
                    distanceM = lap.length?.inMeters,
                    stroke = seg?.let { HealthConnectManager.strokeName(it.segmentType) },
                    strokes = strokes, kind = "lap",
                )
            }
        }
        return strokeSegs.sortedBy { it.startTime }.mapIndexed { i, seg ->
            SwimLap(
                swimId = 0, idx = i,
                startMs = seg.startTime.toEpochMilli(), endMs = seg.endTime.toEpochMilli(),
                distanceM = null,
                stroke = HealthConnectManager.strokeName(seg.segmentType),
                strokes = seg.repetitions.takeIf { it > 0 }, kind = "segment",
            )
        }
    }

    private fun inferPoolLength(laps: List<SwimLap>): Int? =
        laps.mapNotNull { it.distanceM?.toInt()?.takeIf { d -> d in 10..100 } }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

    suspend fun allLaps(): List<SwimLap> = dao.allLaps()
    suspend fun allSwims(): List<Swim> = dao.all()

    data class SessionCandidate(val id: String, val startMs: Long, val endMs: Long, val richness: Int)

    companion object {
        /** Overlap of two intervals as a fraction of the shorter one. */
        fun overlapFraction(aStart: Long, aEnd: Long, bStart: Long, bEnd: Long): Double {
            val overlap = minOf(aEnd, bEnd) - maxOf(aStart, bStart)
            val shorter = minOf(aEnd - aStart, bEnd - bStart)
            return if (overlap <= 0 || shorter <= 0) 0.0 else overlap.toDouble() / shorter
        }

        /** Groups sessions that overlap > 50 % and keeps the richest of each group. */
        fun pickRichest(items: List<SessionCandidate>): Set<String> {
            val keep = mutableSetOf<String>()
            val used = mutableSetOf<String>()
            for (c in items.sortedBy { it.startMs }) {
                if (c.id in used) continue
                val group = items.filter { it.id !in used && overlapFraction(c.startMs, c.endMs, it.startMs, it.endMs) > 0.5 }
                used += group.map { it.id }
                keep += group.maxWith(compareBy<SessionCandidate>({ it.richness }, { it.endMs - it.startMs })).id
            }
            return keep
        }
    }
}
