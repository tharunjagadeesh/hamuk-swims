package com.hamuk.swimpro.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Pool
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Toll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import com.hamuk.swimpro.AppContainer
import com.hamuk.swimpro.coach.UsageTracker
import com.hamuk.swimpro.container
import com.hamuk.swimpro.data.DEFAULT_DAILY_LIMIT
import com.hamuk.swimpro.data.DEFAULT_GEMINI_MODEL
import com.hamuk.swimpro.data.Level
import com.hamuk.swimpro.data.Profile
import kotlinx.coroutines.launch
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Health Connect status + permission request, shared by onboarding and settings. */
@Composable
fun HealthConnectCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.container
    var granted by remember { mutableStateOf<Set<String>>(emptySet()) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh) { granted = runCatching { app.health.grantedPermissions() }.getOrElse { emptySet() } }
    val launcher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { refresh++ }

    GlassCard(modifier) {
        CardHeader("Samsung Health", Icons.Rounded.Favorite, Accent.Heart)
        when (app.health.sdkStatus) {
            HealthConnectClient.SDK_AVAILABLE -> {
                val ok = granted.containsAll(app.health.corePermissions)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Canvas(Modifier.size(10.dp)) { drawCircle(if (ok) Accent.Time else Accent.Calories) }
                    Spacer(Modifier.width(8.dp))
                    Text(if (ok) "Connected through Health Connect" else "Not connected yet", style = MaterialTheme.typography.titleSmall,
                        color = if (ok) Accent.Time else Accent.Calories)
                }
                Muted("1. Samsung Health → Settings → Health Connect: allow Exercise, Heart rate, Distance and Calories.\n2. Grant Hamuk Swims read access below.")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton(if (ok) "Review access" else "Grant access", { launcher.launch(app.health.allPermissions) })
                    GlassButton("Health Connect", { runCatching { context.startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)) } })
                }
            }
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                Body("Health Connect needs to be installed or updated.")
                PrimaryButton("Open Play Store", {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata"))) }
                })
            }
            else -> Body("Health Connect isn't available on this device. You can still add swims by hand.")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileForm(initial: Profile, buttonText: String, onSave: (Profile) -> Unit) {
    val p = LocalPalette.current
    var level by remember(initial) { mutableStateOf(initial.level) }
    var sessions by remember(initial) { mutableIntStateOf(initial.sessionsPerWeek) }
    var minutes by remember(initial) { mutableStateOf(initial.minutesPerSession.toString()) }
    var pool by remember(initial) { mutableStateOf(initial.poolLengthM.toString()) }
    var longest by remember(initial) { mutableStateOf(initial.maxContinuousM.toString()) }
    var age by remember(initial) { mutableStateOf(initial.ageYears?.toString() ?: "") }
    var maxHr by remember(initial) { mutableStateOf(initial.maxHrOverride?.toString() ?: "") }
    var restHr by remember(initial) { mutableStateOf(initial.restingHrOverride?.toString() ?: "") }
    val hrAuto by LocalContext.current.container.analysis.state.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Level", style = MaterialTheme.typography.labelLarge, color = p.textDim)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Level.entries.forEach { l -> GlassChip(l.name.lowercase().replaceFirstChar { it.uppercase() }, level == l, { level = l }) }
        }
        Text("Sessions per week", style = MaterialTheme.typography.labelLarge, color = p.textDim)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..6).forEach { n -> GlassChip("$n", sessions == n, { sessions = n }, Accent.Time) }
        }
        Text("Pool length", style = MaterialTheme.typography.labelLarge, color = p.textDim)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("25", "50", "33", "20").forEach { v -> GlassChip("$v m", pool == v, { pool = v }, Accent.Swolf) }
        }
        GlassTextField(minutes, { minutes = it.filter(Char::isDigit) }, "Minutes per session", numeric = true)
        GlassTextField(longest, { longest = it.filter(Char::isDigit) }, "Longest swim without stopping (m)", numeric = true)
        Text("Heart rate (for zones & training load)", style = MaterialTheme.typography.labelLarge, color = p.textDim)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassTextField(age, { age = it.filter(Char::isDigit).take(3) }, "Age", Modifier.weight(1f), numeric = true)
            GlassTextField(maxHr, { maxHr = it.filter(Char::isDigit).take(3) }, "Max HR", Modifier.weight(1f), numeric = true,
                placeholder = "auto ${hrAuto.hr.maxHr}")
            GlassTextField(restHr, { restHr = it.filter(Char::isDigit).take(3) }, "Rest HR", Modifier.weight(1f), numeric = true,
                placeholder = "auto ${initial.restingHrAuto ?: 60}")
        }
        Muted("Leave max/rest HR empty to use auto values: highest HR seen in your swims, and resting HR from Samsung Health.")
        PrimaryButton(buttonText, {
            onSave(initial.copy(
                level = level,
                sessionsPerWeek = sessions,
                minutesPerSession = (minutes.toIntOrNull() ?: 40).coerceIn(15, 120),
                poolLengthM = (pool.toIntOrNull() ?: 25).coerceIn(10, 100),
                maxContinuousM = (longest.toIntOrNull() ?: 100).coerceIn(0, 10000),
                ageYears = age.toIntOrNull()?.takeIf { it in 8..99 },
                maxHrOverride = maxHr.toIntOrNull()?.takeIf { it in 120..230 },
                restingHrOverride = restHr.toIntOrNull()?.takeIf { it in 30..110 },
            ))
        }, Modifier.fillMaxWidth())
    }
}

@Composable
fun ProfileScreen(onBack: () -> Unit) {
    val app = LocalContext.current.container
    val scope = rememberCoroutineScope()
    var profile by remember { mutableStateOf<Profile?>(null) }
    LaunchedEffect(Unit) { profile = app.coach.profileOrDefault() }
    Page {
        DetailHeader("Swimmer profile", onBack)
        profile?.let { p ->
            GlassCard(Modifier.appear(0)) {
                ProfileForm(p, "Save & update plan") { scope.launch { app.coach.saveProfile(it); onBack() } }
            }
        }
    }
}

@Composable
fun OnboardingScreen() {
    val app = LocalContext.current.container
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(top = 36.dp, bottom = 8.dp).appear(0),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Hamuk Swims", style = MaterialTheme.typography.displaySmall, color = p.text)
            Text("Your Galaxy Watch swims, a plan built around your goal, and a coach in your pocket.",
                style = MaterialTheme.typography.bodyLarge, color = p.textDim, textAlign = TextAlign.Center)
        }
        HealthConnectCard(Modifier.appear(1))
        GlassCard(Modifier.appear(2)) {
            CardHeader("About you", Icons.Rounded.Person, Accent.Pace)
            ProfileForm(Profile(), "Let's swim") { prof ->
                scope.launch {
                    app.coach.saveProfile(prof, regenerate = false)
                    app.settings.setOnboarded(true)
                    runCatching { app.sync() }
                }
            }
        }
        Muted("Add your Gemini key for the coach chat any time in Settings.", Modifier.padding(bottom = 32.dp))
    }
}

@Composable
private fun SettingsRow(icon: ImageVector, color: Color, title: String, subtitle: String? = null, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().pressable(onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(icon, color, 34.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = p.text)
            subtitle?.let { Muted(it) }
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = p.textDim)
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit, onInspector: () -> Unit, onPrivacy: () -> Unit, onProfile: () -> Unit) {
    val context = LocalContext.current
    val app = context.container
    val scope = rememberCoroutineScope()
    val model by app.settings.model.collectAsState(initial = DEFAULT_GEMINI_MODEL)
    val syncMsg by app.settings.lastSyncMessage.collectAsState(initial = null)
    var keyInput by remember { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(app.keys.hasKey()) }
    var modelInput by remember(model) { mutableStateOf(model) }
    var syncing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val askNotifications: () -> Unit = {
        if (Build.VERSION.SDK_INT >= 33 && !UsageTracker.canNotify(context)) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch { status = runCatching { exportCsv(app, uri, context); "Exported CSV ✓" }.getOrElse { it.message } }
    }
    val jsonLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch { status = runCatching { exportJson(app, uri, context); "Exported backup ✓" }.getOrElse { it.message } }
    }

    Page {
        DetailHeader("Settings", onBack)
        HealthConnectCard(Modifier.appear(0))
        DataSourcesCard(Modifier.appear(0))

        GlassCard(Modifier.appear(1)) {
            CardHeader("Sync", Icons.Rounded.Sync, Accent.Swim)
            syncMsg?.let { Muted(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                PrimaryButton(if (syncing) "Syncing…" else "Sync now", {
                    scope.launch { syncing = true; status = runCatching { app.sync().message }.getOrElse { it.message }; syncing = false }
                }, enabled = !syncing)
                if (syncing) CircularProgressIndicator(Modifier.size(24.dp), color = Accent.Swim, strokeWidth = 2.dp)
            }
            Muted("Runs when the app opens and about every 6 hours in the background.")
        }

        GlassCard(Modifier.appear(2)) {
            CardHeader("Coach chat · Gemini", Icons.Rounded.AutoAwesome, Accent.Pace)
            Text(if (hasKey) "API key saved ✓ (encrypted on this phone)" else "No API key yet",
                style = MaterialTheme.typography.titleSmall, color = if (hasKey) Accent.Time else Accent.Calories)
            Muted("Get a free key at aistudio.google.com → Get API key. Your swim summary is sent to Google only when you use the chat.")
            GlassTextField(keyInput, { keyInput = it }, "Paste Gemini API key", password = true)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton("Save key", { app.keys.save(keyInput); keyInput = ""; hasKey = true; askNotifications() }, enabled = keyInput.isNotBlank())
                if (hasKey) GlassButton("Remove", { app.keys.clear(); hasKey = false }, tint = Accent.Heart)
            }
            GlassTextField(modelInput, { modelInput = it }, "Model (auto = newest Flash)")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassButton("Save model", {
                    scope.launch { app.settings.setModel(modelInput); app.settings.setResolvedModel(null); status = "Model saved" }
                })
                GlassButton("Use auto", {
                    modelInput = DEFAULT_GEMINI_MODEL
                    scope.launch { app.settings.setModel(DEFAULT_GEMINI_MODEL); app.settings.setResolvedModel(null) }
                })
            }
        }

        UsageCard(Modifier.appear(3), onEnableAlerts = askNotifications)

        UpdateCard(Modifier.appear(4), alwaysShow = true)

        GlassCard(Modifier.appear(5), padding = 14.dp) {
            SettingsRow(Icons.Rounded.Person, Accent.Pace, "Swimmer profile", "Level, sessions, pool length", onProfile)
            SettingsRow(Icons.Rounded.BugReport, Accent.Swolf, "Data inspector", "What your watch sends via Health Connect", onInspector)
            SettingsRow(Icons.Rounded.Download, Accent.Swim, "Export swims (CSV)", null) { csvLauncher.launch("swimpro-swims.csv") }
            SettingsRow(Icons.Rounded.Download, Accent.Time, "Export backup (JSON)", null) { jsonLauncher.launch("swimpro-backup.json") }
            SettingsRow(Icons.Rounded.Shield, Accent.Gold, "Privacy", null, onPrivacy)
        }
        status?.let { Muted(it) }
    }
}

/** Gemini usage counted on this phone, with daily allowance and alert settings. */
@Composable
private fun UsageCard(modifier: Modifier, onEnableAlerts: () -> Unit) {
    val context = LocalContext.current
    val app = context.container
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val today = LocalDate.now().toEpochDay()
    val usage by remember { app.usage.observeSince(today - 29) }.collectAsState(initial = emptyList())
    val limit by app.settings.dailyLimit.collectAsState(initial = DEFAULT_DAILY_LIMIT)
    val alerts by app.settings.usageAlerts.collectAsState(initial = true)
    var limitInput by remember(limit) { mutableStateOf(limit.toString()) }
    var currentModel by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(usage) { currentModel = app.chat.currentModel() }

    val todayU = usage.firstOrNull { it.epochDay == today }
    val reqToday = todayU?.requests ?: 0
    val ratio = reqToday / limit.toFloat()
    val ringColor = when { ratio >= 1f -> Accent.Heart; ratio >= 0.8f -> Accent.Calories; else -> Accent.Pace }
    val week = (6 downTo 0).map { d -> usage.firstOrNull { it.epochDay == today - d }?.requests?.toDouble() ?: 0.0 }
    val dayFmt = DateTimeFormatter.ofPattern("EEEEE")

    GlassCard(modifier) {
        CardHeader("Gemini usage", Icons.Rounded.Toll, Accent.Pace, trailing = currentModel)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center) {
                ActivityRings(listOf(Ring(ratio, ringColor)), size = 104.dp, stroke = 13.dp)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$reqToday", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = p.text)
                    Text("of $limit", style = MaterialTheme.typography.labelSmall, color = p.textDim)
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                KeyValueRow("Requests today", "$reqToday")
                KeyValueRow("Tokens today", "%,d".format(todayU?.totalTokens ?: 0))
                KeyValueRow("Last 30 days", "%,d req".format(usage.sumOf { it.requests }))
                KeyValueRow("30-day tokens", "%,d".format(usage.sumOf { it.totalTokens }))
                if ((todayU?.rateLimited ?: 0) > 0) KeyValueRow("Rejected (429)", "${todayU!!.rateLimited}", Accent.Heart)
            }
        }
        Text("Requests, last 7 days", style = MaterialTheme.typography.labelLarge, color = p.textDim)
        BarChart(week, (6 downTo 0).map { LocalDate.ofEpochDay(today - it).format(dayFmt) }, Accent.Pace, height = 90.dp,
            targets = List(7) { limit.toDouble() }.takeIf { week.any { it > limit * 0.5 } })

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.NotificationsActive, null, tint = Accent.Calories, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Usage alerts", style = MaterialTheme.typography.titleSmall, color = p.text)
                Muted("Notify at 80% and 100% of your daily allowance, and if Google starts rejecting requests")
            }
            Switch(alerts, { on ->
                scope.launch { app.settings.setUsageAlerts(on) }
                if (on) onEnableAlerts()
            }, colors = SwitchDefaults.colors(checkedTrackColor = Accent.Time))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassTextField(limitInput, { limitInput = it.filter(Char::isDigit) }, "Daily allowance (requests)", Modifier.weight(1f), numeric = true)
            GlassButton("Save", { limitInput.toIntOrNull()?.let { scope.launch { app.settings.setDailyLimit(it) } } })
        }
        Muted(
            "Set this to the requests-per-day limit Google shows for your key under AI Studio → Usage & limits. " +
                "A free key is never charged: when the free quota runs out, Google rejects requests until it resets. " +
                "You only pay if billing is enabled on the key's Google Cloud project — Google doesn't let apps read billing, so these counts are tracked on this phone.",
        )
        GlassButton("Open AI Studio usage", {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/usage"))) }
        }, Modifier.fillMaxWidth(), icon = Icons.Rounded.Key)
    }
}

private suspend fun exportCsv(app: AppContainer, uri: Uri, context: android.content.Context) {
    val swims = app.swims.allSwims()
    val csv = buildString {
        appendLine("date,start,distance_m,active_sec,pace_per_100_sec,avg_hr,max_hr,kcal,pool_m,strokes,rpe,swolf,notes")
        swims.forEach { s ->
            appendLine(listOf(
                fmtDate(s.startMs), fmtDateTime(s.startMs), s.distanceM.toInt(), s.activeSec,
                s.pacePer100?.let { "%.1f".format(it) } ?: "", s.avgHr ?: "", s.maxHr ?: "",
                (s.activeKcal ?: s.totalKcal)?.toInt() ?: "", s.poolLengthM ?: "", s.strokeSummary ?: "",
                s.rpe ?: "", s.manualSwolf ?: "", s.notes ?: "",
            ).joinToString(",") { "\"" + it.toString().replace("\"", "\"\"") + "\"" })
        }
    }
    context.contentResolver.openOutputStream(uri)!!.use { it.write(csv.toByteArray()) }
}

private suspend fun exportJson(app: AppContainer, uri: Uri, context: android.content.Context) {
    val p = app.coach.profileOrDefault()
    val goal = app.coach.activeGoal()
    val tests = app.coach.allTests()
    val swims = app.swims.allSwims()
    val json = buildJsonObject {
        put("exported", java.time.Instant.now().toString())
        putJsonObject("profile") {
            put("level", p.level.name); put("sessionsPerWeek", p.sessionsPerWeek); put("minutesPerSession", p.minutesPerSession)
            put("poolLengthM", p.poolLengthM); put("maxContinuousM", p.maxContinuousM)
        }
        if (goal != null) putJsonObject("goal") {
            put("type", goal.type.name); put("targetDistanceM", goal.targetDistanceM); put("targetTimeSec", goal.targetTimeSec)
            put("focus", goal.focus); put("weeklyMinutes", goal.weeklyMinutes)
            put("start", LocalDate.ofEpochDay(goal.startEpochDay).toString())
            put("target", LocalDate.ofEpochDay(goal.targetEpochDay).toString())
        }
        putJsonArray("tests") {
            tests.forEach { t ->
                add(buildJsonObject {
                    put("date", LocalDate.ofEpochDay(t.epochDay).toString()); put("type", t.type.name)
                    put("timeSec", t.timeSec); put("time200Sec", t.time200Sec); put("distanceM", t.distanceM); put("cssPer100", t.cssPer100)
                })
            }
        }
        putJsonArray("swims") {
            swims.forEach { s ->
                add(buildJsonObject {
                    put("start", java.time.Instant.ofEpochMilli(s.startMs).toString())
                    put("end", java.time.Instant.ofEpochMilli(s.endMs).toString())
                    put("distanceM", s.distanceM); put("activeSec", s.activeSec); put("poolLengthM", s.poolLengthM)
                    put("avgHr", s.avgHr); put("maxHr", s.maxHr); put("totalKcal", s.totalKcal); put("activeKcal", s.activeKcal)
                    put("openWater", s.openWater); put("source", s.source); put("strokes", s.strokeSummary)
                    put("rpe", s.rpe); put("swolf", s.manualSwolf); put("notes", s.notes)
                })
            }
        }
    }
    context.contentResolver.openOutputStream(uri)!!.use { it.write(json.toString().toByteArray()) }
}

@Composable
fun InspectorScreen(onBack: () -> Unit) {
    val app = LocalContext.current.container
    val p = LocalPalette.current
    var text by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { text = runCatching { app.health.inspect() }.getOrElse { "Error: ${it.message}" } }
    Page {
        DetailHeader("Data inspector", onBack)
        if (com.hamuk.swimpro.BuildConfig.DEBUG) {
            val scope = rememberCoroutineScope()
            GlassCard {
                CardHeader("Developer: sample data", Icons.Rounded.BugReport, Accent.Calories)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton("Load sample swims", { scope.launch { com.hamuk.swimpro.data.SampleData.load(app.db) } })
                    GlassButton("Clear", { scope.launch { com.hamuk.swimpro.data.SampleData.clear(app.db) } }, tint = Accent.Heart)
                }
            }
        }
        Muted("Raw data Health Connect returns for your recent swims — shows which watch metrics come through (lengths, strokes, heart rate).")
        GlassCard {
            val t = text
            if (t == null) CircularProgressIndicator(color = Accent.Swim) else SelectionContainer {
                Text(t, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = p.text)
            }
        }
    }
}

@Composable
fun PrivacyScreen(onBack: (() -> Unit)?) {
    Page {
        if (onBack != null) DetailHeader("Privacy", onBack) else LargeTitle("Privacy")
        GlassCard(Modifier.appear(0)) {
            CardHeader("Your data stays on your phone", Icons.Rounded.Shield, Accent.Gold)
            Body("• Hamuk Swims reads exercise, heart rate, distance, speed and calorie data from Health Connect only to show your swims and plan your training. It never writes to Health Connect.")
            Body("• Everything is stored on this phone. The only thing sent anywhere is the coach chat: your profile, goal, plan and a summary of recent swims go to Google's Gemini API using your own key.")
            Body("• Remove the API key, clear the chat, or uninstall the app to delete all data.")
        }
        Spacer(Modifier.height(1.dp))
    }
}
