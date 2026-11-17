package com.hamuk.swimpro

import android.app.Application
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.hamuk.swimpro.coach.CoachChat
import com.hamuk.swimpro.coach.CoachRepository
import com.hamuk.swimpro.coach.GeminiClient
import com.hamuk.swimpro.coach.UsageTracker
import com.hamuk.swimpro.data.AppDatabase
import com.hamuk.swimpro.data.SecureKeyStore
import com.hamuk.swimpro.data.SettingsStore
import com.hamuk.swimpro.data.SwimRepository
import com.hamuk.swimpro.data.SyncResult
import com.hamuk.swimpro.health.HealthConnectManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

class SwimProApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        SyncWorker.schedule(this)
    }
}

/** Manual dependency container. */
class AppContainer(context: Context) {
    val db = AppDatabase.build(context)
    val health = HealthConnectManager(context)
    val settings = SettingsStore(context)
    val keys = SecureKeyStore(context)
    val swims = SwimRepository(db, health)
    val coach = CoachRepository(db)
    val usage = UsageTracker(context, db, settings)
    val updater = com.hamuk.swimpro.update.Updater(context)
    val chat = CoachChat(db, coach, GeminiClient(), keys, settings, usage)

    /** Lives as long as the process; for work that must outlive a screen. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val analysis by lazy { com.hamuk.swimpro.analysis.AnalysisRepository(db, coach, scope) }
    val lifestyle by lazy {
        com.hamuk.swimpro.analysis.LifestyleRepository(db, com.hamuk.swimpro.health.WellnessReader(context, health), analysis, scope)
    }

    init {
        chat.extraContext = {
            val st = analysis.state.value
            val goal = coach.activeGoal()?.let { g ->
                com.hamuk.swimpro.analysis.GoalMath.compute(g, st.today, coach.profileOrDefault(), st.cssPer100, coach.allTests(), st.swims,
                    db.coach().workouts(g.id), st.hr)
            }
            com.hamuk.swimpro.analysis.AiContext.summary(st, lifestyle, goal)
        }
        chat.extraSwimContext = { id -> com.hamuk.swimpro.analysis.AiContext.forSwim(analysis.state.value, lifestyle, id) }
    }

    private val syncLock = Mutex()

    suspend fun sync(): SyncResult = syncLock.withLock {
        val result = swims.syncFromHealthConnect(settings.lastSync(), coach.profileOrDefault().poolLengthM)
        if (result.ok) settings.setLastSync(System.currentTimeMillis(), result.message)
        runCatching { lifestyle.sync() }
        runCatching {
            health.restingHrMedian()?.let { rhr ->
                val p = coach.profileOrDefault()
                if (p.restingHrAuto != rhr) coach.saveProfile(p.copy(restingHrAuto = rhr), regenerate = false)
            }
        }
        runCatching { coach.onSwimsSynced() }
        result
    }
}

val Context.container: AppContainer get() = (applicationContext as SwimProApp).container

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext.container
        runCatching { app.sync() }
        runCatching { app.updater.check()?.let { app.updater.notifyAvailable(it) } }
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "health-connect-sync",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS).build(),
            )
        }
    }
}
