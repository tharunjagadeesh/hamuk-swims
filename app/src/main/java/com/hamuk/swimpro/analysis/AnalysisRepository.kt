package com.hamuk.swimpro.analysis

import com.hamuk.swimpro.coach.CoachRepository
import com.hamuk.swimpro.coach.mondayOf
import com.hamuk.swimpro.data.AppDatabase
import com.hamuk.swimpro.data.Profile
import com.hamuk.swimpro.data.Swim
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Everything the analysis screens need, recomputed in the background when swims change. */
data class AnalysisState(
    /** Oldest first. */
    val swims: List<SwimMetrics> = emptyList(),
    val hr: HrProfile = HrProfile(190, 60),
    val cssPer100: Double = 0.0,
    val ready: Boolean = false,
) {
    fun byId(id: Long) = swims.firstOrNull { it.swimId == id }
    fun previousOf(m: SwimMetrics) = swims.lastOrNull { it.startMs < m.startMs }
    fun onDay(day: Long) = swims.filter { it.epochDay == day }
    fun previousSwimDay(day: Long): Long? = swims.map { it.epochDay }.filter { it < day }.maxOrNull()
    fun swimDays(): List<Long> = swims.map { it.epochDay }.distinct().sortedDescending()
    fun inWeek(monday: LocalDate) = swims.filter { it.epochDay in monday.toEpochDay()..monday.toEpochDay() + 6 }
    val today: Long get() = LocalDate.now().toEpochDay()

    fun loadSeries(days: Int = 90, endDay: Long = today): List<LoadModel.Day> {
        // Start the model well before the window so fitness has history to build from.
        val first = swims.firstOrNull()?.epochDay ?: endDay
        val start = minOf(first, endDay - days + 1)
        return LoadModel.series(LoadModel.daily(swims), start, endDay).takeLast(days)
    }

    fun loadStateAt(day: Long): LoadModel.State? =
        if (swims.isEmpty()) null else LoadModel.state(LoadModel.series(LoadModel.daily(swims), swims.first().epochDay, day))

    fun insightsFor(m: SwimMetrics, limit: Int = 3) =
        Insights.forSwim(m, previousOf(m), swims, loadStateAt(m.epochDay), limit)

    /**
     * HR level for the "pace at X bpm" fitness marker: your typical swim HR (median of the last
     * 8 weeks, rounded to 5) so it sits inside your own data; ~75 % of max as a fallback.
     */
    val markerHr: Double get() {
        val hrs = swims.filter { it.epochDay > today - 56 && !it.openWater }.mapNotNull { it.avgHr }.sorted()
        val base = if (hrs.size >= 4) hrs[hrs.size / 2].toDouble() else hr.maxHr * 0.75
        return (Math.round(base / 5.0) * 5).toDouble()
    }

    fun weekCompare(thisMonday: LocalDate = mondayOf(LocalDate.now())) =
        Baselines.compare(inWeek(thisMonday), inWeek(thisMonday.minusWeeks(1)))
}

class AnalysisRepository(
    private val db: AppDatabase,
    private val coach: CoachRepository,
    scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(AnalysisState())
    val state: StateFlow<AnalysisState> = _state

    private val cache = HashMap<Long, Pair<Swim, SwimMetrics>>()
    private var cacheKey: Pair<Double, HrProfile>? = null
    private var detailsVersion: Long? = null

    init {
        scope.launch(Dispatchers.Default) {
            combine(
                db.swims().observeAll(), db.coach().observeProfile(), db.coach().observeTests(), db.swims().observeDetailsVersion(),
            ) { swims, profile, _, details -> Triple(swims, profile ?: Profile(), details) }
                .collectLatest { (swims, profile, details) ->
                    // Lengths/HR are written after the swim row: refresh everything when they change.
                    if (details != detailsVersion) { cache.clear(); detailsVersion = details }
                    recompute(swims, profile)
                }
        }
    }

    private suspend fun recompute(swims: List<Swim>, profile: Profile) {
        val css = coach.currentCss()
        val hrp = HrProfile.resolve(profile.maxHrOverride, swims.mapNotNull { it.maxHr }.maxOrNull(), profile.ageYears,
            profile.restingHrOverride, profile.restingHrAuto)
        if (cacheKey != css to hrp) {
            cache.clear()
            cacheKey = css to hrp
        }
        val ids = swims.map { it.id }.toSet()
        cache.keys.retainAll(ids)
        val dao = db.swims()
        val out = swims.sortedBy { it.startMs }.map { s ->
            val hit = cache[s.id]
            if (hit != null && hit.first == s) hit.second
            else SwimMetrics.from(s, dao.laps(s.id), dao.hr(s.id), css, hrp).also { cache[s.id] = s to it }
        }
        _state.value = AnalysisState(out, hrp, css, ready = true)
    }
}
