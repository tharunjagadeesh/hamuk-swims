package com.hamuk.swimpro.analysis

import com.hamuk.swimpro.data.AppDatabase
import com.hamuk.swimpro.data.DailyWellness
import com.hamuk.swimpro.health.WellnessReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Daily wellness (sleep, steps, calories, other exercise, RHR, HRV) and what it does to your swims. */
class LifestyleRepository(
    private val db: AppDatabase,
    val reader: WellnessReader,
    analysis: AnalysisRepository,
    scope: CoroutineScope,
) {
    private val dao = db.wellness()
    private val _days = MutableStateFlow<Map<Long, DailyWellness>>(emptyMap())
    val days: StateFlow<Map<Long, DailyWellness>> = _days
    private val _readiness = MutableStateFlow<Readiness?>(null)
    val readiness: StateFlow<Readiness?> = _readiness

    init {
        scope.launch(Dispatchers.Default) {
            combine(dao.observeSince(LocalDate.now().toEpochDay() - 400), analysis.state) { rows, st -> rows to st }
                .collect { (rows, st) ->
                    val map = rows.associateBy { it.epochDay }
                    _days.value = map
                    _readiness.value = Lifestyle.readiness(st.today, map, st.loadStateAt(st.today)?.form)
                }
        }
    }

    /** Pulls the last 120 days the first time, then the last 14 (to pick up late syncs). */
    suspend fun sync() {
        val today = LocalDate.now()
        val first = dao.all().isEmpty()
        val rows = reader.read(today.minusDays(if (first) 120 else 14), today)
        if (rows.isNotEmpty()) dao.saveAll(rows)
    }

    fun contexts(st: AnalysisState): List<SwimContext> = st.swims.map { Lifestyle.context(it, days.value) }
    fun outcomes(st: AnalysisState): List<SwimOutcome> = st.swims.map { Lifestyle.outcome(it, st.swims) }
}
