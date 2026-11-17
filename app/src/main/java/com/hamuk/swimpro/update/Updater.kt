package com.hamuk.swimpro.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.hamuk.swimpro.BuildConfig
import com.hamuk.swimpro.MainActivity
import com.hamuk.swimpro.R
import com.hamuk.swimpro.coach.UsageTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

data class AppUpdate(val versionName: String, val notes: String, val apkUrl: String, val sizeBytes: Long)

/**
 * Over-the-air updates from GitHub Releases. The app checks the latest release of
 * [BuildConfig.UPDATE_REPO]; if its tag (vX.Y.Z) is newer than this build, it downloads the
 * APK and installs it with PackageInstaller. The first update asks the user to confirm; after
 * that this app is the installer of record, so Android 12+ can apply updates without a prompt.
 */
class Updater(private val context: Context) {
    val available = MutableStateFlow<AppUpdate?>(null)
    /** 0..1 while downloading, null otherwise. */
    val progress = MutableStateFlow<Float?>(null)
    val message = MutableStateFlow<String?>(null)

    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }

    val enabled: Boolean get() = BuildConfig.UPDATE_REPO.isNotBlank()
    val currentVersion: String get() = BuildConfig.VERSION_NAME

    suspend fun check(): AppUpdate? = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext null
        val req = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        val obj = http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("Update check failed (${resp.code})")
            json.parseToJsonElement(resp.body!!.string()).jsonObject
        }
        val tag = obj["tag_name"]?.jsonPrimitive?.contentOrNull?.removePrefix("v") ?: return@withContext null
        val asset = obj["assets"]?.jsonArray?.map { it.jsonObject }
            ?.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull?.endsWith(".apk") == true }
            ?: return@withContext null
        val update = AppUpdate(
            versionName = tag,
            notes = obj["body"]?.jsonPrimitive?.contentOrNull.orEmpty().trim(),
            apkUrl = asset["browser_download_url"]!!.jsonPrimitive.content,
            sizeBytes = asset["size"]?.jsonPrimitive?.longOrNull ?: 0L,
        )
        val newer = isNewer(update.versionName, currentVersion)
        available.value = if (newer) update else null
        if (newer) update else null
    }

    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun installPermissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    suspend fun downloadAndInstall(update: AppUpdate) {
        message.value = null
        val file = File(context.cacheDir, "update.apk")
        withContext(Dispatchers.IO) {
            progress.value = 0f
            try {
                http.newCall(Request.Builder().url(update.apkUrl).build()).execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("Download failed (${resp.code})")
                    val body = resp.body!!
                    val total = body.contentLength().takeIf { it > 0 } ?: update.sizeBytes
                    body.byteStream().use { input ->
                        file.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var read = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                read += n
                                if (total > 0) progress.value = (read.toFloat() / total).coerceAtMost(1f)
                            }
                        }
                    }
                }
                install(file)
            } finally {
                progress.value = null
            }
        }
    }

    private fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val callback = PendingIntent.getBroadcast(
                context, sessionId, Intent(context, InstallResultReceiver::class.java),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            session.commit(callback.intentSender)
        }
        message.value = "Installing ${apk.length() / 1024 / 1024} MB update…"
    }

    /** Posts a notification once per new version (used by the background sync). */
    fun notifyAvailable(update: AppUpdate) {
        val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
        if (prefs.getString("notified", null) == update.versionName) return
        if (!UsageTracker.canNotify(context)) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(
            context, 1, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_swim)
            .setContentTitle("Hamuk Swims ${update.versionName} is ready")
            .setContentText(update.notes.lineSequence().firstOrNull { it.isNotBlank() } ?: "Tap to update")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(4301, n)
            prefs.edit().putString("notified", update.versionName).apply()
        } catch (_: SecurityException) {
        }
    }

    companion object {
        private const val CHANNEL = "app_updates"

        /** Compares dotted numeric versions, e.g. 0.10.0 > 0.9.3. */
        fun isNewer(candidate: String, current: String): Boolean {
            fun parts(v: String) = v.split('.', '-', '+').mapNotNull { it.toIntOrNull() }
            val a = parts(candidate)
            val b = parts(current)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}

/** Receives PackageInstaller results; launches the system confirmation when Android requires it. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { context.startActivity(it) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // The app restarts into the new version.
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Update failed"
                android.widget.Toast.makeText(context, "Update not installed: $msg", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
}
