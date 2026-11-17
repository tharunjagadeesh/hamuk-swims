package com.hamuk.swimpro.coach

import com.hamuk.swimpro.data.AppDatabase
import com.hamuk.swimpro.data.ChatMessage
import com.hamuk.swimpro.data.Goal
import com.hamuk.swimpro.data.GoalType
import com.hamuk.swimpro.data.SecureKeyStore
import com.hamuk.swimpro.data.SettingsStore
import com.hamuk.swimpro.data.Swim
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class GeminiException(message: String, val code: Int = 0) : Exception(message)

/** A model turn plus the token counts Gemini reported for the request. */
data class GeminiReply(val content: JsonObject, val promptTokens: Long, val outputTokens: Long)

/** Minimal Gemini REST client (generateContent with function calling). */
class GeminiClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Returns the model's `Content` object ({role, parts}) and usage. */
    suspend fun generate(
        apiKey: String,
        model: String,
        system: String,
        contents: List<JsonObject>,
        functions: JsonArray?,
    ): GeminiReply = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("systemInstruction") { putJsonArray("parts") { add(buildJsonObject { put("text", system) }) } }
            put("contents", JsonArray(contents))
            if (functions != null) putJsonArray("tools") { add(buildJsonObject { put("functionDeclarations", functions) }) }
            putJsonObject("generationConfig") { put("temperature", 0.6) }
        }
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            if (!resp.isSuccessful) {
                val msg = obj?.get("error")?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                throw GeminiException("Gemini error ${resp.code}: ${msg ?: text.take(200)}", resp.code)
            }
            val candidate = obj?.get("candidates")?.jsonArray?.firstOrNull()?.jsonObject
            val content = candidate?.get("content")?.jsonObject
            if (content == null || content["parts"] == null) {
                val reason = obj?.get("promptFeedback")?.jsonObject?.get("blockReason")?.jsonPrimitive?.contentOrNull
                    ?: candidate?.get("finishReason")?.jsonPrimitive?.contentOrNull
                throw GeminiException("No answer from Gemini (${reason ?: "empty response"})")
            }
            val usage = obj["usageMetadata"]?.jsonObject
            fun tokens(k: String) = usage?.get(k)?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
            val modelContent = buildJsonObject {
                put("role", "model")
                // Drop internal thought parts but keep signatures on the remaining parts.
                put("parts", JsonArray(content["parts"]!!.jsonArray.filter {
                    it.jsonObject["thought"]?.jsonPrimitive?.contentOrNull != "true"
                }))
            }
            // Thinking tokens are billed as output, so count them with the candidates.
            GeminiReply(modelContent, tokens("promptTokenCount"), tokens("candidatesTokenCount") + tokens("thoughtsTokenCount"))
        }
    }

    /** Names (without "models/") of models this key can call with generateContent. */
    suspend fun listModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
        val names = mutableListOf<String>()
        var pageToken: String? = null
        do {
            val url = "https://generativelanguage.googleapis.com/v1beta/models?pageSize=200" +
                (pageToken?.let { "&pageToken=$it" } ?: "")
            val request = Request.Builder().url(url).header("x-goog-api-key", apiKey).get().build()
            val obj = http.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw GeminiException("Couldn't list Gemini models (${resp.code})", resp.code)
                json.parseToJsonElement(text).jsonObject
            }
            obj["models"]?.jsonArray?.forEach { m ->
                val o = m.jsonObject
                val methods = o["supportedGenerationMethods"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
                val name = o["name"]?.jsonPrimitive?.contentOrNull?.removePrefix("models/")
                if (name != null && "generateContent" in methods) names += name
            }
            pageToken = obj["nextPageToken"]?.jsonPrimitive?.contentOrNull
        } while (pageToken != null)
        names
    }

    companion object {
        private val versioned = Regex("""^gemini-(\d+(?:\.\d+)?)-flash$""")

        /**
         * Picks the newest stable "gemini-X.Y-flash" model; falls back to any other Flash text
         * model (skipping lite/image/audio/live/TTS variants), then to anything Gemini.
         */
        fun pickFlash(models: List<String>): String? {
            models.mapNotNull { n -> versioned.find(n)?.let { n to it.groupValues[1].toDouble() } }
                .maxByOrNull { it.second }?.let { return it.first }
            val skip = listOf("lite", "image", "audio", "live", "tts", "embedding", "thinking")
            return models.filter { "gemini" in it && "flash" in it && skip.none { s -> s in it } }
                .maxByOrNull { Regex("""\d+(\.\d+)?""").find(it)?.value?.toDoubleOrNull() ?: 0.0 }
                ?: models.firstOrNull { it.startsWith("gemini") && skip.none { s -> s in it } }
        }
    }
}

/** Functions Gemini may call. Each call is shown to the user as a proposal to approve. */
object CoachTools {
    val declarations: JsonArray = buildJsonArray {
        add(fn("set_goal", "Replace the athlete's goal and generate a new training plan.") {
            putJsonObject("type") {
                put("type", "string"); putJsonArray("enum") { GoalType.entries.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.name)) } }
                put("description", "TIME = swim a distance under a time; DISTANCE = swim a distance non-stop; TECHNIQUE = stroke/skill; FITNESS = consistency/volume")
            }
            putJsonObject("weeks") { put("type", "integer"); put("description", "Plan length in weeks (4-26)") }
            putJsonObject("target_distance_m") { put("type", "integer") }
            putJsonObject("target_time") { put("type", "string"); put("description", "Target time as m:ss or h:mm:ss (TIME goals)") }
            putJsonObject("focus") { put("type", "string"); put("description", "Stroke or skill focus, e.g. 'breaststroke' or 'bilateral breathing'") }
            putJsonObject("weekly_minutes") { put("type", "integer") }
        }.withRequired("type", "weeks"))
        add(fn("update_schedule", "Change how many sessions per week and how long each session is; the plan is rebuilt.") {
            putJsonObject("sessions_per_week") { put("type", "integer") }
            putJsonObject("minutes_per_session") { put("type", "integer") }
        })
        add(fn("swap_workout", "Replace one planned workout with a different session type.") {
            putJsonObject("workout_id") { put("type", "integer") }
            putJsonObject("session_type") {
                put("type", "string")
                putJsonArray("enum") {
                    SessionType.entries.filter { it != SessionType.TEST && it != SessionType.GOAL }
                        .forEach { add(kotlinx.serialization.json.JsonPrimitive(it.name)) }
                }
            }
        }.withRequired("workout_id", "session_type"))
        add(fn("schedule_test", "Put a pace test (CSS or beginner benchmark) at the start of the next plan week.", null))
        add(fn("add_recovery_week", "Make the next plan week a lighter recovery week.", null))
    }

    private fun fn(name: String, description: String, props: (kotlinx.serialization.json.JsonObjectBuilder.() -> Unit)?) =
        buildJsonObject {
            put("name", name)
            put("description", description)
            if (props != null) putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties", props)
            }
        }

    private fun JsonObject.withRequired(vararg names: String): JsonObject {
        val params = this["parameters"]!!.jsonObject
        val newParams = JsonObject(params + ("required" to JsonArray(names.map { kotlinx.serialization.json.JsonPrimitive(it) })))
        return JsonObject(this + ("parameters" to newParams))
    }

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.int(k: String) = this[k]?.jsonPrimitive?.intOrNull
        ?: this[k]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toInt()

    /** Human-readable summary of a proposed call. */
    fun describe(name: String, args: JsonObject): String = when (name) {
        "set_goal" -> {
            val type = args.str("type") ?: "?"
            listOfNotNull(
                "New goal: $type",
                args.int("target_distance_m")?.let { "${it} m" },
                args.str("target_time")?.let { "in $it" },
                args.str("focus")?.let { "focus: $it" },
                args.int("weekly_minutes")?.let { "$it min/week" },
                args.int("weeks")?.let { "over $it weeks" },
            ).joinToString(" · ")
        }
        "update_schedule" -> "Schedule: ${args.int("sessions_per_week") ?: "same"} sessions/week × ${args.int("minutes_per_session") ?: "same"} min"
        "swap_workout" -> "Swap workout #${args.int("workout_id")} → ${args.str("session_type")?.let { runCatching { SessionType.valueOf(it).label }.getOrDefault(it) }}"
        "schedule_test" -> "Schedule a pace test next plan week"
        "add_recovery_week" -> "Make next plan week a recovery week"
        else -> "$name $args"
    }

    suspend fun execute(name: String, args: JsonObject, coach: CoachRepository): String = when (name) {
        "set_goal" -> {
            val type = args.str("type")?.let { runCatching { GoalType.valueOf(it) }.getOrNull() }
                ?: throw IllegalArgumentException("Unknown goal type")
            val weeks = (args.int("weeks") ?: 12).coerceIn(PlanEngine.MIN_WEEKS, PlanEngine.MAX_WEEKS)
            val start = coach.defaultStart()
            val time = args.str("target_time")?.let { parseDuration(it) }
            if (type == GoalType.TIME && (time == null || args.int("target_distance_m") == null)) {
                throw IllegalArgumentException("A time goal needs a distance and a target time")
            }
            coach.setGoal(
                Goal(
                    type = type,
                    targetDistanceM = args.int("target_distance_m"),
                    targetTimeSec = time,
                    focus = args.str("focus"),
                    weeklyMinutes = args.int("weekly_minutes"),
                    startEpochDay = start.toEpochDay(),
                    targetEpochDay = start.plusWeeks(weeks.toLong() - 1).plusDays(6).toEpochDay(),
                )
            )
            "Goal set and a $weeks-week plan generated."
        }
        "update_schedule" -> {
            val p = coach.profileOrDefault()
            coach.saveProfile(
                p.copy(
                    sessionsPerWeek = (args.int("sessions_per_week") ?: p.sessionsPerWeek).coerceIn(1, 6),
                    minutesPerSession = (args.int("minutes_per_session") ?: p.minutesPerSession).coerceIn(15, 120),
                )
            )
            "Schedule updated and plan rebuilt."
        }
        "swap_workout" -> {
            val id = args.int("workout_id")?.toLong() ?: throw IllegalArgumentException("workout_id missing")
            val type = args.str("session_type")?.let { runCatching { SessionType.valueOf(it) }.getOrNull() }
                ?: throw IllegalArgumentException("Unknown session type")
            coach.swapWorkout(id, type)
        }
        "schedule_test" -> coach.scheduleTest()
        "add_recovery_week" -> coach.addRecoveryWeek()
        else -> throw IllegalArgumentException("Unknown function $name")
    }
}

/** Chat state machine: persists Gemini contents and turns function calls into approvable proposals. */
class CoachChat(
    db: AppDatabase,
    private val coach: CoachRepository,
    private val gemini: GeminiClient,
    private val keys: SecureKeyStore,
    private val settings: SettingsStore,
    private val usage: UsageTracker,
) {
    private val dao = db.chat()
    private val json = Json { ignoreUnknownKeys = true }

    val messages = dao.observeAll()
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    /** Runs a chat action in [scope] (an app-wide scope, so leaving the screen doesn't cancel it). */
    fun run(scope: CoroutineScope, block: suspend CoachChat.() -> Unit) {
        scope.launch {
            busy.value = true
            error.value = null
            try {
                block()
            } catch (e: Exception) {
                error.value = e.message ?: e.toString()
            } finally {
                busy.value = false
            }
        }
    }

    data class Call(val partIndex: Int, val name: String, val args: JsonObject, val state: String, val result: String?)

    fun parts(m: ChatMessage): List<JsonObject> =
        runCatching { json.parseToJsonElement(m.contentJson).jsonObject["parts"]!!.jsonArray.map { it.jsonObject } }
            .getOrElse { emptyList() }

    fun text(m: ChatMessage): String =
        parts(m).mapNotNull { it["text"]?.jsonPrimitive?.contentOrNull }.joinToString("\n").trim()

    fun calls(m: ChatMessage): List<Call> {
        val states = runCatching { json.parseToJsonElement(m.callStates).jsonObject }.getOrElse { JsonObject(emptyMap()) }
        return parts(m).mapIndexedNotNull { i, p ->
            val fc = p["functionCall"]?.jsonObject ?: return@mapIndexedNotNull null
            val st = states[i.toString()]?.jsonObject
            Call(
                i,
                fc["name"]?.jsonPrimitive?.contentOrNull ?: "?",
                fc["args"]?.jsonObject ?: JsonObject(emptyMap()),
                st?.get("state")?.jsonPrimitive?.contentOrNull ?: "pending",
                st?.get("result")?.jsonPrimitive?.contentOrNull,
            )
        }
    }

    suspend fun clear() = dao.clear()

    /** Extra analysis text for the system prompt, and per swim (set by the app container). */
    var extraContext: suspend () -> String = { "" }
    var extraSwimContext: suspend (Long) -> String = { "" }

    suspend fun reviewSwim(swim: Swim) =
        send("Please review this swim and tell me what went well, what to work on, and how it fits my plan:\n" + coach.swimDetail(swim) + extraSwimContext(swim.id))

    /** Sends a user message; any unanswered proposals are recorded as declined first. */
    suspend fun send(text: String) {
        val responses = mutableListOf<JsonObject>()
        for (m in dao.all().filter { it.role == "model" }) {
            val pending = calls(m).filter { it.state == "pending" }
            if (pending.isEmpty()) continue
            var updated = m
            for (c in pending) {
                updated = withState(updated, c.partIndex, "dismissed", "User did not apply this change.")
                responses += functionResponse(c.name, "User did not apply this change.")
            }
            dao.update(updated)
        }
        val content = buildJsonObject {
            put("role", "user")
            put("parts", JsonArray(responses + buildJsonObject { put("text", text) }))
        }
        dao.insert(ChatMessage(role = "user", contentJson = content.toString()))
        askModel()
    }

    /** Applies or dismisses one proposed function call; when all calls in the turn are resolved, Gemini replies. */
    suspend fun resolve(messageId: Long, partIndex: Int, apply: Boolean) {
        val m = dao.get(messageId) ?: return
        val call = calls(m).firstOrNull { it.partIndex == partIndex && it.state == "pending" } ?: return
        val (state, result) = if (apply) {
            try {
                "applied" to CoachTools.execute(call.name, call.args, coach)
            } catch (e: Exception) {
                "failed" to "Could not apply: ${e.message}"
            }
        } else {
            "dismissed" to "User declined this change."
        }
        val updated = withState(m, partIndex, state, result)
        dao.update(updated)
        val all = calls(updated)
        if (all.any { it.state == "pending" }) return
        val content = buildJsonObject {
            put("role", "user")
            put("parts", JsonArray(all.map { functionResponse(it.name, it.result ?: it.state) }))
        }
        dao.insert(ChatMessage(role = "user", contentJson = content.toString()))
        askModel()
    }

    private suspend fun askModel() {
        val key = keys.load() ?: throw GeminiException("Add your Gemini API key in Settings first.")
        val history = dao.all()
        // Keep the last ~40 contents, starting at a user turn that carries text (not a bare function response).
        var startIdx = (history.size - 40).coerceAtLeast(0)
        while (startIdx < history.size && !(history[startIdx].role == "user" && text(history[startIdx]).isNotEmpty())) startIdx++
        val contents = history.drop(startIdx).map { json.parseToJsonElement(it.contentJson).jsonObject }
        val system = systemPrompt()
        val configured = settings.model()
        val auto = configured.equals("auto", ignoreCase = true)
        var model = if (auto) settings.resolvedModel() ?: resolveModel(key) else configured
        val result = try {
            try {
                gemini.generate(key, model, system, contents, CoachTools.declarations)
            } catch (e: GeminiException) {
                // Model retired or unknown: discover the newest available Flash model and retry once.
                if (e.code != 404 && e.code != 400) throw e
                val fresh = resolveModel(key)
                if (fresh == model) throw e
                model = fresh
                gemini.generate(key, model, system, contents, CoachTools.declarations)
            }
        } catch (e: GeminiException) {
            if (e.code == 429) {
                usage.recordRateLimited()
                throw GeminiException("Gemini's free limit is used up for now (429). It resets on its own — try again later.", 429)
            }
            throw e
        }
        usage.recordSuccess(result.promptTokens, result.outputTokens)
        val reply = result.content
        val callIdx = reply["parts"]!!.jsonArray.withIndex().filter { it.value.jsonObject["functionCall"] != null }.map { it.index }
        val states = buildJsonObject { callIdx.forEach { putJsonObject(it.toString()) { put("state", "pending") } } }
        dao.insert(ChatMessage(role = "model", contentJson = reply.toString(), callStates = states.toString()))
    }

    private suspend fun resolveModel(key: String): String {
        val picked = GeminiClient.pickFlash(gemini.listModels(key))
            ?: throw GeminiException("No Gemini model available for this API key.")
        settings.setResolvedModel(picked)
        return picked
    }

    /** The model the chat is currently using (for display in Settings). */
    suspend fun currentModel(): String? =
        settings.model().let { if (it.equals("auto", true)) settings.resolvedModel() else it }

    private fun withState(m: ChatMessage, idx: Int, state: String, result: String): ChatMessage {
        val states = runCatching { json.parseToJsonElement(m.callStates).jsonObject }.getOrElse { JsonObject(emptyMap()) }
        val next = JsonObject(states + (idx.toString() to buildJsonObject { put("state", state); put("result", result) }))
        return m.copy(callStates = next.toString())
    }

    private fun functionResponse(name: String, result: String): JsonObject = buildJsonObject {
        putJsonObject("functionResponse") {
            put("name", name)
            putJsonObject("response") { put("result", result) }
        }
    }

    private suspend fun systemPrompt(): String = """
You are the Hamuk Swims coach, a warm, knowledgeable swimming coach inside the athlete's personal Android app.
The athlete swims with a Galaxy Watch; their swims are imported automatically.

How to coach:
- Ground every answer in the athlete data below. Paces are per 100 m, written m:ss.
- The app's plan engine builds the plan (CSS-based zones, base/build/taper periods, a recovery week every 4th week, ≤10% weekly volume increases, retests every 5 weeks). Do not write a whole new plan in text. To change the plan, call one of your functions — the athlete sees each call as a proposal and must approve it. Briefly say why you're proposing it.
- To change a single session, use swap_workout with the workout_id shown in the plan.
- Coach for a beginner unless the data says otherwise: technique, relaxed breathing and consistency first; progress gradually; praise effort.
- When reviewing a swim, comment on distance, pace, pacing consistency across laps, heart rate, stroke mix and SWOLF if present, and relate it to the plan and goal. Give 1–3 concrete things to try next time.
- Safety: if the athlete mentions chest pain, dizziness, fainting, unusual breathlessness or injury, tell them to stop and get medical advice. You are not a doctor.
- Keep replies short and phone-friendly (under ~150 words unless asked for more). Use short bullet lists for sets.

${coach.contextSummary()}
${extraContext()}
""".trimIndent()
}
