package com.hamuk.swimpro.coach

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.hamuk.swimpro.MainActivity
import com.hamuk.swimpro.R
import com.hamuk.swimpro.data.AppDatabase
import com.hamuk.swimpro.data.GeminiUsage
import com.hamuk.swimpro.data.SettingsStore
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/**
 * Counts Gemini requests and tokens per day (from each response's usageMetadata) and warns
 * before the user runs past the daily allowance they set.
 *
 * Google doesn't expose remaining quota or billing to apps, so this is local bookkeeping:
 * free-tier keys are never charged — Google just answers 429 once the free quota is used up.
 * Charges only happen if billing is enabled on the key's Google Cloud project.
 */
class UsageTracker(
    private val context: Context,
    db: AppDatabase,
    private val settings: SettingsStore,
) {
    private val dao = db.usage()

    fun observeSince(fromDay: Long) = dao.observeSince(fromDay)

    suspend fun recordSuccess(promptTokens: Long, outputTokens: Long) {
        val day = LocalDate.now().toEpochDay()
        val u = dao.get(day) ?: GeminiUsage(day)
        val next = u.copy(requests = u.requests + 1, promptTokens = u.promptTokens + promptTokens, outputTokens = u.outputTokens + outputTokens)
        dao.save(next)
        checkThresholds(next)
    }

    suspend fun recordRateLimited() {
        val day = LocalDate.now().toEpochDay()
        val u = dao.get(day) ?: GeminiUsage(day)
        dao.save(u.copy(rateLimited = u.rateLimited + 1))
        notifyOnce(day, "429",
            "Gemini free limit reached",
            "Google is rejecting coach requests for now (rate or daily quota). It resets automatically — no charge on a free key.")
    }

    private suspend fun checkThresholds(u: GeminiUsage) {
        val limit = settings.dailyLimit.first()
        val ratio = u.requests.toDouble() / limit
        when {
            ratio >= 1.0 -> notifyOnce(u.epochDay, "100",
                "Daily Gemini limit reached",
                "${u.requests} of your $limit requests used today. Further chats may hit Google's free-tier limit — or cost money if billing is on.")
            ratio >= 0.8 -> notifyOnce(u.epochDay, "80",
                "80% of today's Gemini allowance used",
                "${u.requests} of $limit requests · ${"%,d".format(u.totalTokens)} tokens today.")
        }
    }

    private suspend fun notifyOnce(day: Long, level: String, title: String, text: String) {
        if (!settings.usageAlerts.first()) return
        val stamp = "$day:$level"
        val last = settings.lastUsageNotification()
        // Don't repeat a level, and don't send 80% after 100% on the same day.
        if (last == stamp || (level == "80" && last == "$day:100")) return
        if (!canNotify(context)) return
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_swim)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, n)
            settings.setLastUsageNotification(stamp)
        } catch (_: SecurityException) {
        }
    }

    companion object {
        private const val CHANNEL = "gemini_usage"
        private const val NOTIFICATION_ID = 4201

        fun canNotify(context: Context): Boolean =
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Gemini usage alerts", NotificationManager.IMPORTANCE_DEFAULT)
                        .apply { description = "Warns before the coach chat uses up your daily Gemini allowance" }
                )
            }
        }
    }
}
