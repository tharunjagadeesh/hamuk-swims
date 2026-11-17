package com.hamuk.swimpro.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** One swim session, imported from Health Connect or entered by hand. */
@Entity(tableName = "swims", indices = [Index(value = ["hcId"], unique = true)])
data class Swim(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val hcId: String? = null,
    val hcModifiedMs: Long? = null,
    val startMs: Long,
    val endMs: Long,
    val distanceM: Double,
    /** Moving time in seconds (pauses excluded when the watch reports them). */
    val activeSec: Long,
    val poolLengthM: Int? = null,
    val totalKcal: Double? = null,
    val activeKcal: Double? = null,
    val avgHr: Int? = null,
    val maxHr: Int? = null,
    val minHr: Int? = null,
    val openWater: Boolean = false,
    val source: String = "manual",
    val title: String? = null,
    val hcNotes: String? = null,
    val strokeSummary: String? = null,
    // Fields the user edits; preserved across re-syncs.
    val rpe: Int? = null,
    val notes: String? = null,
    val manualSwolf: Int? = null,
) {
    /** Seconds per 100 m, or null if distance is unknown. */
    val pacePer100: Double? get() = if (distanceM > 0) activeSec / distanceM * 100.0 else null
}

@Entity(
    tableName = "swim_laps",
    foreignKeys = [ForeignKey(Swim::class, ["id"], ["swimId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("swimId")],
)
data class SwimLap(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val swimId: Long,
    val idx: Int,
    val startMs: Long,
    val endMs: Long,
    val distanceM: Double?,
    val stroke: String?,
    val strokes: Int?,
    /** "lap" when from ExerciseLap, "segment" when only stroke segments were available. */
    val kind: String,
) {
    val seconds: Double get() = (endMs - startMs) / 1000.0
    val swolf: Int? get() = strokes?.let { (seconds + it).toInt() }
}

@Entity(
    tableName = "hr_samples",
    primaryKeys = ["swimId", "timeMs"],
    foreignKeys = [ForeignKey(Swim::class, ["id"], ["swimId"], onDelete = ForeignKey.CASCADE)],
)
data class HrSample(val swimId: Long, val timeMs: Long, val bpm: Int)

enum class Level { BEGINNER, INTERMEDIATE, ADVANCED }

@Entity(tableName = "profile")
data class Profile(
    @PrimaryKey val id: Int = 1,
    val level: Level = Level.BEGINNER,
    val sessionsPerWeek: Int = 3,
    val minutesPerSession: Int = 40,
    val poolLengthM: Int = 25,
    /** Longest distance the swimmer can do without stopping, in metres. */
    val maxContinuousM: Int = 100,
    // Heart-rate anchors for zones and training load. Overrides win over auto values.
    val ageYears: Int? = null,
    val maxHrOverride: Int? = null,
    val restingHrOverride: Int? = null,
    /** Median resting HR from Samsung Health (via Health Connect), refreshed on sync. */
    val restingHrAuto: Int? = null,
)

enum class GoalType { TIME, DISTANCE, TECHNIQUE, FITNESS }

@Entity(tableName = "goals")
data class Goal(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: GoalType,
    val targetDistanceM: Int? = null,
    val targetTimeSec: Int? = null,
    /** Stroke or skill focus for technique goals, free text. */
    val focus: String? = null,
    /** Weekly minutes target for fitness goals. */
    val weeklyMinutes: Int? = null,
    val startEpochDay: Long,
    val targetEpochDay: Long,
    val active: Boolean = true,
    val createdMs: Long = System.currentTimeMillis(),
)

enum class TestType { CSS, T100, CONTINUOUS }

@Entity(tableName = "tests")
data class TestResult(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epochDay: Long,
    val type: TestType,
    /** CSS: 400 m time. T100: 100 m time. CONTINUOUS: time for [distanceM]. */
    val timeSec: Int,
    /** CSS only: 200 m time. */
    val time200Sec: Int? = null,
    val distanceM: Int,
    /** Derived threshold pace, seconds per 100 m. */
    val cssPer100: Double,
    val note: String? = null,
)

@Entity(tableName = "plan_weeks", indices = [Index("goalId")])
data class PlanWeek(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val goalId: Long,
    val programWeek: Int,
    val totalWeeks: Int,
    val startEpochDay: Long,
    val phase: String,
    val deload: Boolean,
    val note: String? = null,
    val evaluated: Boolean = false,
)

enum class WorkoutStatus { PLANNED, DONE, SKIPPED }

@Entity(
    tableName = "planned_workouts",
    foreignKeys = [ForeignKey(PlanWeek::class, ["id"], ["weekId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("weekId"), Index("goalId")],
)
data class PlannedWorkout(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val weekId: Long,
    val goalId: Long,
    val epochDay: Long,
    val sessionType: String,
    val title: String,
    /** Serialized [com.hamuk.swimpro.coach.Workout]. */
    val json: String,
    val totalM: Int,
    val status: WorkoutStatus = WorkoutStatus.PLANNED,
    val matchedSwimId: Long? = null,
)

@Entity(tableName = "chat")
data class ChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** "user" or "model", as in the Gemini API. */
    val role: String,
    /** Raw Gemini `Content` JSON so function calls round-trip exactly. */
    val contentJson: String,
    val createdMs: Long = System.currentTimeMillis(),
    /** JSON object: function-call part index -> "pending" | "applied" | "dismissed". */
    val callStates: String = "{}",
)

/** Gemini API usage for one calendar day, counted on this phone. */
@Entity(tableName = "gemini_usage")
data class GeminiUsage(
    @PrimaryKey val epochDay: Long,
    val requests: Int = 0,
    val promptTokens: Long = 0,
    val outputTokens: Long = 0,
    /** Requests Google rejected for quota/rate limits (HTTP 429). */
    val rateLimited: Int = 0,
) {
    val totalTokens: Long get() = promptTokens + outputTokens
}

/**
 * One day of non-swim context from Health Connect (any source app: Samsung Health, Google Fit,
 * Fitbit…). Sleep is the main sleep that *ended* on this day.
 */
@Entity(tableName = "daily_wellness")
data class DailyWellness(
    @PrimaryKey val epochDay: Long,
    val sleepMin: Int? = null,
    val deepMin: Int? = null,
    val remMin: Int? = null,
    val lightMin: Int? = null,
    val awakeMin: Int? = null,
    val sleepStartMs: Long? = null,
    val sleepEndMs: Long? = null,
    val sleepSource: String? = null,
    val steps: Long? = null,
    val totalKcal: Double? = null,
    val activeKcal: Double? = null,
    /** Non-swim workouts (overlaps between apps merged), in minutes. */
    val exerciseMin: Int? = null,
    val exerciseTypes: String? = null,
    val restingHr: Int? = null,
    val hrvMs: Double? = null,
    val updatedMs: Long = 0,
)
