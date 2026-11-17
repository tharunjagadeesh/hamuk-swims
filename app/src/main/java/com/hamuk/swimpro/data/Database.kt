package com.hamuk.swimpro.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SwimDao {
    @Query("SELECT * FROM swims ORDER BY startMs DESC")
    fun observeAll(): Flow<List<Swim>>

    @Query("SELECT * FROM swims ORDER BY startMs DESC")
    suspend fun all(): List<Swim>

    @Query("SELECT * FROM swims WHERE id = :id")
    fun observe(id: Long): Flow<Swim?>

    @Query("SELECT * FROM swims WHERE id = :id")
    suspend fun get(id: Long): Swim?

    @Query("SELECT * FROM swims WHERE hcId = :hcId")
    suspend fun byHcId(hcId: String): Swim?

    @Query("SELECT * FROM swims WHERE startMs >= :fromMs ORDER BY startMs DESC")
    suspend fun since(fromMs: Long): List<Swim>

    @Insert
    suspend fun insert(swim: Swim): Long

    @Update
    suspend fun update(swim: Swim)

    @Delete
    suspend fun delete(swim: Swim)

    @Query("SELECT * FROM swim_laps WHERE swimId = :swimId ORDER BY idx")
    fun observeLaps(swimId: Long): Flow<List<SwimLap>>

    @Query("SELECT * FROM swim_laps WHERE swimId = :swimId ORDER BY idx")
    suspend fun laps(swimId: Long): List<SwimLap>

    @Query("SELECT * FROM swim_laps WHERE kind = 'lap' ORDER BY swimId, idx")
    suspend fun allLaps(): List<SwimLap>

    @Query("DELETE FROM swim_laps WHERE swimId = :swimId")
    suspend fun deleteLaps(swimId: Long)

    @Insert
    suspend fun insertLaps(laps: List<SwimLap>)

    @Query("SELECT * FROM hr_samples WHERE swimId = :swimId ORDER BY timeMs")
    fun observeHr(swimId: Long): Flow<List<HrSample>>

    @Query("SELECT * FROM hr_samples WHERE swimId = :swimId ORDER BY timeMs")
    suspend fun hr(swimId: Long): List<HrSample>

    /** Changes whenever lengths or HR samples are added/replaced, so analysis can refresh. */
    @Query("SELECT (SELECT COUNT(*) FROM swim_laps) * 1000003 + (SELECT COUNT(*) FROM hr_samples)")
    fun observeDetailsVersion(): Flow<Long>

    @Query("DELETE FROM hr_samples WHERE swimId = :swimId")
    suspend fun deleteHr(swimId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHr(samples: List<HrSample>)

    @Transaction
    suspend fun replaceDetails(swimId: Long, laps: List<SwimLap>, hr: List<HrSample>) {
        deleteLaps(swimId)
        insertLaps(laps)
        deleteHr(swimId)
        insertHr(hr)
    }
}

@Dao
interface CoachDao {
    @Query("SELECT * FROM profile WHERE id = 1")
    fun observeProfile(): Flow<Profile?>

    @Query("SELECT * FROM profile WHERE id = 1")
    suspend fun profile(): Profile?

    @Upsert
    suspend fun saveProfile(profile: Profile)

    @Query("SELECT * FROM goals WHERE active = 1 ORDER BY id DESC LIMIT 1")
    fun observeActiveGoal(): Flow<Goal?>

    @Query("SELECT * FROM goals WHERE active = 1 ORDER BY id DESC LIMIT 1")
    suspend fun activeGoal(): Goal?

    @Insert
    suspend fun insertGoal(goal: Goal): Long

    @Update
    suspend fun updateGoal(goal: Goal)

    @Query("UPDATE goals SET active = 0")
    suspend fun deactivateGoals()

    @Query("SELECT * FROM tests ORDER BY epochDay DESC, id DESC")
    fun observeTests(): Flow<List<TestResult>>

    @Query("SELECT * FROM tests ORDER BY epochDay DESC, id DESC")
    suspend fun tests(): List<TestResult>

    @Insert
    suspend fun insertTest(test: TestResult): Long

    @Delete
    suspend fun deleteTest(test: TestResult)

    @Query("SELECT * FROM plan_weeks WHERE goalId = :goalId ORDER BY startEpochDay")
    fun observeWeeks(goalId: Long): Flow<List<PlanWeek>>

    @Query("SELECT * FROM plan_weeks WHERE goalId = :goalId ORDER BY startEpochDay")
    suspend fun weeks(goalId: Long): List<PlanWeek>

    @Insert
    suspend fun insertWeek(week: PlanWeek): Long

    @Update
    suspend fun updateWeek(week: PlanWeek)

    @Query("DELETE FROM plan_weeks WHERE goalId = :goalId AND startEpochDay >= :fromEpochDay")
    suspend fun deleteWeeksFrom(goalId: Long, fromEpochDay: Long)

    @Query("SELECT * FROM planned_workouts WHERE goalId = :goalId ORDER BY epochDay")
    fun observeWorkouts(goalId: Long): Flow<List<PlannedWorkout>>

    @Query("SELECT * FROM planned_workouts WHERE goalId = :goalId ORDER BY epochDay")
    suspend fun workouts(goalId: Long): List<PlannedWorkout>

    @Query("SELECT * FROM planned_workouts WHERE id = :id")
    fun observeWorkout(id: Long): Flow<PlannedWorkout?>

    @Query("SELECT * FROM planned_workouts WHERE id = :id")
    suspend fun workout(id: Long): PlannedWorkout?

    @Insert
    suspend fun insertWorkouts(workouts: List<PlannedWorkout>)

    @Update
    suspend fun updateWorkout(workout: PlannedWorkout)

    @Query("SELECT matchedSwimId FROM planned_workouts WHERE matchedSwimId IS NOT NULL")
    suspend fun matchedSwimIds(): List<Long>
}

@Dao
interface UsageDao {
    @Query("SELECT * FROM gemini_usage WHERE epochDay >= :fromDay ORDER BY epochDay")
    fun observeSince(fromDay: Long): Flow<List<GeminiUsage>>

    @Query("SELECT * FROM gemini_usage WHERE epochDay = :day")
    suspend fun get(day: Long): GeminiUsage?

    @Upsert
    suspend fun save(usage: GeminiUsage)
}

@Dao
interface WellnessDao {
    @Query("SELECT * FROM daily_wellness WHERE epochDay >= :fromDay ORDER BY epochDay")
    fun observeSince(fromDay: Long): Flow<List<DailyWellness>>

    @Query("SELECT * FROM daily_wellness ORDER BY epochDay")
    suspend fun all(): List<DailyWellness>

    @Upsert
    suspend fun saveAll(days: List<DailyWellness>)

    @Query("DELETE FROM daily_wellness WHERE epochDay IN (:days)")
    suspend fun deleteDays(days: List<Long>)
}

@Dao
interface ChatDao {
    @Query("SELECT * FROM chat ORDER BY id")
    fun observeAll(): Flow<List<ChatMessage>>

    @Query("SELECT * FROM chat ORDER BY id")
    suspend fun all(): List<ChatMessage>

    @Insert
    suspend fun insert(message: ChatMessage): Long

    @Update
    suspend fun update(message: ChatMessage)

    @Query("SELECT * FROM chat WHERE id = :id")
    suspend fun get(id: Long): ChatMessage?

    @Query("DELETE FROM chat")
    suspend fun clear()
}

class Converters {
    @androidx.room.TypeConverter fun levelToString(v: Level) = v.name
    @androidx.room.TypeConverter fun stringToLevel(v: String) = Level.valueOf(v)
    @androidx.room.TypeConverter fun goalTypeToString(v: GoalType) = v.name
    @androidx.room.TypeConverter fun stringToGoalType(v: String) = GoalType.valueOf(v)
    @androidx.room.TypeConverter fun testTypeToString(v: TestType) = v.name
    @androidx.room.TypeConverter fun stringToTestType(v: String) = TestType.valueOf(v)
    @androidx.room.TypeConverter fun statusToString(v: WorkoutStatus) = v.name
    @androidx.room.TypeConverter fun stringToStatus(v: String) = WorkoutStatus.valueOf(v)
}

@Database(
    entities = [
        Swim::class, SwimLap::class, HrSample::class, Profile::class, Goal::class,
        TestResult::class, PlanWeek::class, PlannedWorkout::class, ChatMessage::class, GeminiUsage::class, DailyWellness::class,
    ],
    version = 3,
    exportSchema = false,
)
@androidx.room.TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun swims(): SwimDao
    abstract fun coach(): CoachDao
    abstract fun chat(): ChatDao
    abstract fun usage(): UsageDao
    abstract fun wellness(): WellnessDao

    companion object {
        /** v2 adds Gemini usage tracking; keeps all existing swims and plans. */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `gemini_usage` (`epochDay` INTEGER NOT NULL, `requests` INTEGER NOT NULL, " +
                        "`promptTokens` INTEGER NOT NULL, `outputTokens` INTEGER NOT NULL, `rateLimited` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`epochDay`))"
                )
            }
        }

        /** v3 adds heart-rate profile fields and daily wellness; existing data is kept. */
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                listOf("ageYears", "maxHrOverride", "restingHrOverride", "restingHrAuto").forEach {
                    db.execSQL("ALTER TABLE `profile` ADD COLUMN `$it` INTEGER")
                }
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `daily_wellness` (`epochDay` INTEGER NOT NULL, `sleepMin` INTEGER, `deepMin` INTEGER, " +
                        "`remMin` INTEGER, `lightMin` INTEGER, `awakeMin` INTEGER, `sleepStartMs` INTEGER, `sleepEndMs` INTEGER, " +
                        "`sleepSource` TEXT, `steps` INTEGER, `totalKcal` REAL, `activeKcal` REAL, `exerciseMin` INTEGER, " +
                        "`exerciseTypes` TEXT, `restingHr` INTEGER, `hrvMs` REAL, `updatedMs` INTEGER NOT NULL, PRIMARY KEY(`epochDay`))"
                )
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "swimpro.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
