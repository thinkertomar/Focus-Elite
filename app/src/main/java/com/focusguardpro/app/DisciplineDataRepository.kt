package com.focusguardpro.app

import android.content.Context
import com.focusguardpro.app.db.AppDatabase
import com.focusguardpro.app.db.AuditRecord
import com.focusguardpro.app.db.DiaryEntry
import com.focusguardpro.app.db.ExerciseLog
import com.focusguardpro.app.db.SystemState
import com.focusguardpro.app.db.TodoItem
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.max

/**
 * Unified, 100% offline-first repository backing Calendar, Daily Journal, Task Management,
 * Holidays (enforcing the 24-hour advance Focus Layer rule), and Discipline Insights.
 *
 * Architecture safety:
 * - Reuses existing Room v7 tables (`DiaryEntry`, `TodoItem`, `ExerciseLog`, `AuditRecord`,
 *   `DailyUsageSnapshot`, `SystemState`) with ZERO schema changes and ZERO destructive migrations.
 * - Extended journal metadata, local drafts, and rich task attributes (priority, due time,
 *   reminders, recurrence) are stored transactionally inside Room `SystemState` alongside
 *   canonical `DiaryEntry` / `TodoItem` records so legacy modules, FocusLayerManager,
 *   EliteDailyNotebookActivity, and SmartNotificationManager remain 100% in sync.
 */
object DisciplineDataRepository {

    private const val KEY_JOURNAL_META_PREFIX = "JOURNAL_META_v1_"
    private const val KEY_JOURNAL_DRAFT_PREFIX = "JOURNAL_DRAFT_v1_"
    private const val KEY_TASK_META_MAP = "TASK_META_v1"
    private const val TWENTY_FOUR_HOURS_MS = 24L * 60L * 60L * 1000L

    enum class TaskPriority(val code: String, val label: String, val sortRank: Int) {
        HIGH("HIGH", "High", 0),
        NORMAL("NORMAL", "Normal", 1),
        LOW("LOW", "Low", 2);

        companion object {
            fun fromCode(raw: String?): TaskPriority =
                values().firstOrNull { it.code.equals(raw, ignoreCase = true) } ?: NORMAL
        }
    }

    enum class TaskRecurrence(val code: String, val label: String) {
        NONE("NONE", "One-time"),
        DAILY("DAILY", "Daily"),
        WEEKDAYS("WEEKDAYS", "Weekdays"),
        WEEKLY("WEEKLY", "Weekly");

        companion object {
            fun fromCode(raw: String?): TaskRecurrence =
                values().firstOrNull { it.code.equals(raw, ignoreCase = true) } ?: NONE
        }
    }

    data class TaskMeta(
        val priority: TaskPriority = TaskPriority.NORMAL,
        val dueTimeMinutes: Int? = null,
        val reminderEnabled: Boolean = false,
        val recurrence: TaskRecurrence = TaskRecurrence.NONE,
        val seriesId: String? = null,
        val rescheduledFrom: String? = null
    )

    data class EnrichedTask(
        val item: TodoItem,
        val meta: TaskMeta,
        val isOverdue: Boolean
    )

    data class JournalMeta(
        val mood: String = "",
        val energy: Int = 0, // 0 = unset, 1..5
        val tags: List<String> = emptyList(),
        val wordCount: Int = 0,
        val updatedAt: Long = 0L
    )

    data class JournalDraft(
        val dateKey: String,
        val text: String,
        val mood: String,
        val energy: Int,
        val tags: List<String>,
        val savedAt: Long
    )

    data class JournalRecord(
        val dateKey: String,
        val note: String,
        val isHoliday: Boolean,
        val lastUpdated: Long,
        val meta: JournalMeta
    )

    data class CalendarDaySummary(
        val dateKey: String,
        val dayStartMs: Long,
        val steps: Int?, // null = no record exists (never confused with 0 / failed goal)
        val stepGoal: Int,
        val stepGoalMet: Boolean,
        val workoutLogs: List<ExerciseLog>,
        val walkSessionCount: Int,
        val isHoliday: Boolean,
        val isVerifiedHolidayToday: Boolean,
        val hasDiary: Boolean,
        val diarySnippet: String,
        val journalMeta: JournalMeta?,
        val tasks: List<EnrichedTask>,
        val awayAuditRecords: List<AuditRecord>
    ) {
        // Strictly genuine logged ExerciseLog sets ONLY — ordinary walking sessions are NOT workout sets.
        val hasWorkout: Boolean get() = workoutLogs.isNotEmpty()
        val workoutCount: Int get() = workoutLogs.size
        val totalTasks: Int get() = tasks.size
        val completedTasks: Int get() = tasks.count { it.item.isDone }
        val remainingTasks: Int get() = tasks.count { !it.item.isDone }
        val overdueTasks: Int get() = tasks.count { it.isOverdue }
        val isActiveDay: Boolean
            get() = stepGoalMet || (steps != null && steps >= 1500) || hasWorkout || completedTasks > 0 || hasDiary
    }

    data class MonthlyDisciplineSummary(
        val monthLabel: String,
        val daysInMonth: Int,
        val activeDays: Int,
        val stepGoalDays: Int,
        val recordedStepDays: Int,
        val workoutDays: Int,
        val totalTasks: Int,
        val completedTasks: Int,
        val journalDays: Int,
        val holidayDays: Int
    ) {
        val taskCompletionPercent: Int?
            get() = if (totalTasks > 0) ((completedTasks * 100f) / totalTasks).toInt().coerceIn(0, 100) else null
    }

    sealed class HolidayToggleResult {
        data class Updated(val isHoliday: Boolean, val message: String) : HolidayToggleResult()
        data class Blocked(val reason: String) : HolidayToggleResult()
    }

    data class PeriodInsightsReport(
        val periodDays: Int,
        val stepGoal: Int,
        val dailySteps: List<Pair<String, Int?>>, // dateKey -> steps or null if unrecorded
        val recordedStepDays: Int,
        val stepGoalMetDays: Int,
        val avgRecordedSteps: Int?,
        val dailyWorkoutCounts: List<Pair<String, Int>>, // dateKey -> genuine logged exercise sets only (excludes walking)
        val workoutActiveDays: Int,
        val totalWorkoutSets: Int,
        val dailyTaskStats: List<Triple<String, Int, Int>>, // dateKey, done, total
        val totalTasksScheduled: Int,
        val totalTasksCompleted: Int,
        val overduePendingCount: Int,
        val journalDaysCount: Int,
        val totalJournalWords: Int,
        val avgEnergyRating: Float?,
        val topMood: String?,
        val focusSessionsCompleted: Int,
        val focusSessionMinutes: Int,
        val dailyFocusMinutes: List<Pair<String, Int>>,
        val productiveVsDistractedDays: List<Triple<String, Int, Int>>, // dateKey, productiveMins, distractedMins (only recorded days)
        val totalProductiveMins: Int,
        val totalDistractedMins: Int,
        val telemetrySplitDays: Int,
        val dailyExerciseSets: List<Pair<String, Int>> = dailyWorkoutCounts,
        val sleepRecovery: SmartSleepEngine.PeriodSleepRecoverySummary? = null
    )

    // -------------------------------------------------------------------------
    // Date Key Helpers (strictly local calendar boundaries)
    // -------------------------------------------------------------------------

    fun todayKey(context: Context? = null): String = FocusLayerManager.todayKey(context)

    fun dateKeyFor(cal: Calendar): String =
        SimpleDateFormat("yyyyMMdd", Locale.US).format(cal.time)

    fun dateKeyForMs(ms: Long): String =
        SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(ms))

    fun parseDateKeyToCalendar(dateKey: String): Calendar {
        val cal = Calendar.getInstance()
        try {
            val d = SimpleDateFormat("yyyyMMdd", Locale.US).parse(dateKey)
            if (d != null) cal.time = d
        } catch (_: Throwable) {}
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal
    }

    fun startOfDayMs(dateKey: String): Long = parseDateKeyToCalendar(dateKey).timeInMillis

    fun addDays(dateKey: String, deltaDays: Int): String {
        val cal = parseDateKeyToCalendar(dateKey)
        cal.add(Calendar.DAY_OF_YEAR, deltaDays)
        return dateKeyFor(cal)
    }

    fun formatFriendlyDate(dateKey: String, context: Context? = null): String {
        val today = todayKey(context)
        val yesterday = addDays(today, -1)
        val tomorrow = addDays(today, 1)
        val cal = parseDateKeyToCalendar(dateKey)
        val formatted = SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(cal.time)
        return when (dateKey) {
            today -> "Today · $formatted"
            yesterday -> "Yesterday · $formatted"
            tomorrow -> "Tomorrow · $formatted"
            else -> formatted
        }
    }

    fun formatFullDate(dateKey: String): String {
        val cal = parseDateKeyToCalendar(dateKey)
        return SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault()).format(cal.time)
    }

    fun formatTimeOfDay(minutesOfDay: Int): String = FocusLayerManager.formatMinutesOfDay(minutesOfDay)

    fun countWords(text: String): Int {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return 0
        return trimmed.split(Regex("\\s+")).count { it.isNotBlank() }
    }

    // -------------------------------------------------------------------------
    // Holidays (Preserves 24-Hour Advance Restriction & Focus Layer Integrity)
    // -------------------------------------------------------------------------

    fun evaluateHolidayEligibility(context: Context, dateKey: String): Pair<Boolean, String?> {
        val nowMs = FocusLayerManager.verifiedNowMs(context)
        val today = todayKey(context)
        if (dateKey < today) {
            return false to "Past dates cannot be scheduled as Focus holidays."
        }
        if (dateKey == today) {
            return false to "24h Advance Rule: Today cannot be marked as a new holiday. Schedule rest days at least 24 hours ahead."
        }
        val targetMidnight = startOfDayMs(dateKey)
        val focusStartMins = FocusLayerManager.getStartMinutes(context)
        val targetFocusStartMs = targetMidnight + focusStartMins * 60_000L
        val leadTimeMs = targetFocusStartMs - nowMs
        if (leadTimeMs < TWENTY_FOUR_HOURS_MS) {
            val hoursAhead = (leadTimeMs / 3_600_000L).coerceAtLeast(0L)
            return false to "24h Advance Rule: That date's Focus window starts in ${hoursAhead}h (<24h). Pick a date at least 24 hours ahead."
        }
        return true to null
    }

    /**
     * Toggles a holiday safely while preserving any diary note on that date and enforcing
     * Focus Layer's minimum 24-hour advance rule.
     */
    fun toggleHoliday(context: Context, dateKey: String, enableHoliday: Boolean): HolidayToggleResult {
        val dao = AppDatabase.getDatabase(context).systemDao()
        val existing = dao.getDiaryEntry(dateKey)

        if (enableHoliday) {
            val (eligible, reason) = evaluateHolidayEligibility(context, dateKey)
            if (!eligible) {
                return HolidayToggleResult.Blocked(reason ?: "Holidays must be scheduled at least 24 hours in advance.")
            }
        }

        val existingNote = existing?.note ?: ""
        if (!enableHoliday && existingNote.isBlank()) {
            dao.deleteDiaryEntry(dateKey)
        } else {
            dao.upsertDiaryEntry(
                DiaryEntry(
                    dateKey = dateKey,
                    note = existingNote,
                    isHoliday = enableHoliday,
                    lastUpdated = FocusLayerManager.verifiedNowMs(context)
                )
            )
        }
        FocusLayerManager.anchorUpcomingPlannedHolidays(context)
        try {
            SmartNotificationManager.refreshNow(context)
        } catch (_: Throwable) {}

        return HolidayToggleResult.Updated(
            isHoliday = enableHoliday,
            message = if (enableHoliday) {
                "Holiday scheduled for ${formatFriendlyDate(dateKey, context)} (Focus Layer will pause automatically)."
            } else {
                "Holiday removed for ${formatFriendlyDate(dateKey, context)}."
            }
        )
    }

    // -------------------------------------------------------------------------
    // Daily Journal + Metadata + Auto-Drafts + Search
    // -------------------------------------------------------------------------

    fun getJournalEntry(context: Context, dateKey: String): JournalRecord? {
        FocusGuardApp.ensureRoomMainThreadQueries(context)
        val dao = try { AppDatabase.getDatabase(context).systemDao() } catch (_: Throwable) { return null }
        val entry = try { dao.getDiaryEntry(dateKey) } catch (_: Throwable) { null }
        val meta = try { loadJournalMeta(context, dateKey) } catch (_: Throwable) { null }
        if (entry == null && meta == null) return null
        val note = entry?.note ?: ""
        val effectiveMeta = (meta ?: JournalMeta()).copy(
            wordCount = countWords(note),
            updatedAt = entry?.lastUpdated ?: meta?.updatedAt ?: 0L
        )
        return JournalRecord(
            dateKey = dateKey,
            note = note,
            isHoliday = entry?.isHoliday == true,
            lastUpdated = entry?.lastUpdated ?: effectiveMeta.updatedAt,
            meta = effectiveMeta
        )
    }

    fun saveJournalEntry(
        context: Context,
        dateKey: String,
        noteText: String,
        mood: String,
        energy: Int,
        tags: List<String>
    ): JournalRecord {
        val dao = AppDatabase.getDatabase(context).systemDao()
        val existing = dao.getDiaryEntry(dateKey)
        val preserveHoliday = existing?.isHoliday == true
        val cleanedNote = noteText.trim()
        val now = System.currentTimeMillis()
        val cleanTags = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(8)
        val safeEnergy = energy.coerceIn(0, 5)
        val wc = countWords(cleanedNote)

        if (cleanedNote.isEmpty() && !preserveHoliday && mood.isBlank() && safeEnergy == 0 && cleanTags.isEmpty()) {
            dao.deleteDiaryEntry(dateKey)
            clearJournalMeta(context, dateKey)
        } else {
            dao.upsertDiaryEntry(
                DiaryEntry(
                    dateKey = dateKey,
                    note = cleanedNote,
                    isHoliday = preserveHoliday,
                    lastUpdated = now
                )
            )
            val meta = JournalMeta(
                mood = mood.trim(),
                energy = safeEnergy,
                tags = cleanTags,
                wordCount = wc,
                updatedAt = now
            )
            saveJournalMeta(context, dateKey, meta)
        }

        clearJournalDraft(context, dateKey)
        FocusLayerManager.anchorUpcomingPlannedHolidays(context)
        try {
            SmartNotificationManager.refreshNow(context)
        } catch (_: Throwable) {}

        return JournalRecord(
            dateKey = dateKey,
            note = cleanedNote,
            isHoliday = preserveHoliday,
            lastUpdated = now,
            meta = JournalMeta(mood.trim(), safeEnergy, cleanTags, wc, now)
        )
    }

    /**
     * Deletes journal text & metadata while strictly preserving `isHoliday` if scheduled.
     */
    fun deleteJournalEntry(context: Context, dateKey: String) {
        val dao = AppDatabase.getDatabase(context).systemDao()
        val existing = dao.getDiaryEntry(dateKey)
        if (existing?.isHoliday == true) {
            dao.upsertDiaryEntry(
                DiaryEntry(
                    dateKey = dateKey,
                    note = "",
                    isHoliday = true,
                    lastUpdated = System.currentTimeMillis()
                )
            )
        } else {
            dao.deleteDiaryEntry(dateKey)
        }
        clearJournalMeta(context, dateKey)
        clearJournalDraft(context, dateKey)
        try {
            SmartNotificationManager.refreshNow(context)
        } catch (_: Throwable) {}
    }

    fun saveJournalDraft(
        context: Context,
        dateKey: String,
        text: String,
        mood: String,
        energy: Int,
        tags: List<String>
    ) {
        val dao = AppDatabase.getDatabase(context).systemDao()
        val now = System.currentTimeMillis()
        val json = JSONObject().apply {
            put("text", text)
            put("mood", mood)
            put("energy", energy.coerceIn(0, 5))
            put("tags", JSONArray(tags))
            put("savedAt", now)
        }
        dao.putState(SystemState("$KEY_JOURNAL_DRAFT_PREFIX$dateKey", json.toString(), now))
    }

    fun loadJournalDraft(context: Context, dateKey: String): JournalDraft? {
        FocusGuardApp.ensureRoomMainThreadQueries(context)
        val raw = try {
            AppDatabase.getDatabase(context).systemDao().getState("$KEY_JOURNAL_DRAFT_PREFIX$dateKey")
        } catch (_: Throwable) {
            null
        }
        if (raw.isNullOrBlank()) return null
        return try {
            val obj = JSONObject(raw)
            val tagsArr = obj.optJSONArray("tags")
            val tags = mutableListOf<String>()
            if (tagsArr != null) {
                for (i in 0 until tagsArr.length()) {
                    val t = tagsArr.optString(i).trim()
                    if (t.isNotEmpty()) tags.add(t)
                }
            }
            JournalDraft(
                dateKey = dateKey,
                text = obj.optString("text", ""),
                mood = obj.optString("mood", ""),
                energy = obj.optInt("energy", 0).coerceIn(0, 5),
                tags = tags,
                savedAt = obj.optLong("savedAt", 0L)
            )
        } catch (_: Throwable) {
            null
        }
    }

    fun clearJournalDraft(context: Context, dateKey: String) {
        AppDatabase.getDatabase(context).systemDao()
            .putState(SystemState("$KEY_JOURNAL_DRAFT_PREFIX$dateKey", "", System.currentTimeMillis()))
    }

    fun getAllJournalRecords(context: Context): List<JournalRecord> {
        FocusGuardApp.ensureRoomMainThreadQueries(context)
        val dao = try { AppDatabase.getDatabase(context).systemDao() } catch (_: Throwable) { return emptyList() }
        val allEntries = try { dao.getAllDiaryEntries() } catch (_: Throwable) { emptyList() }
        val allStates = try { dao.getAllSystemState() } catch (_: Throwable) { emptyList() }
        val metaMap = HashMap<String, JournalMeta>()
        for (st in allStates) {
            if (st.stateKey.startsWith(KEY_JOURNAL_META_PREFIX) && st.stateValue.isNotBlank()) {
                val dk = st.stateKey.removePrefix(KEY_JOURNAL_META_PREFIX)
                parseJournalMeta(st.stateValue)?.let { metaMap[dk] = it }
            }
        }
        return allEntries
            .filter { it.note.isNotBlank() || metaMap.containsKey(it.dateKey) }
            .map { e ->
                val m = metaMap[e.dateKey] ?: JournalMeta()
                JournalRecord(
                    dateKey = e.dateKey,
                    note = e.note,
                    isHoliday = e.isHoliday,
                    lastUpdated = e.lastUpdated,
                    meta = m.copy(wordCount = countWords(e.note))
                )
            }
            .sortedByDescending { it.dateKey }
    }

    fun searchJournalRecords(
        context: Context,
        query: String,
        moodFilter: String? = null,
        taggedOnly: Boolean = false
    ): List<JournalRecord> {
        val q = query.trim().lowercase(Locale.getDefault())
        return getAllJournalRecords(context).filter { rec ->
            if (!moodFilter.isNullOrBlank() && !rec.meta.mood.equals(moodFilter, ignoreCase = true)) {
                return@filter false
            }
            if (taggedOnly && rec.meta.tags.isEmpty()) {
                return@filter false
            }
            if (q.isEmpty()) return@filter true
            rec.note.lowercase(Locale.getDefault()).contains(q) ||
                rec.meta.mood.lowercase(Locale.getDefault()).contains(q) ||
                rec.meta.tags.any { it.lowercase(Locale.getDefault()).contains(q) } ||
                formatFullDate(rec.dateKey).lowercase(Locale.getDefault()).contains(q) ||
                rec.dateKey.contains(q)
        }
    }

    private fun saveJournalMeta(context: Context, dateKey: String, meta: JournalMeta) {
        val json = JSONObject().apply {
            put("mood", meta.mood)
            put("energy", meta.energy)
            put("tags", JSONArray(meta.tags))
            put("wordCount", meta.wordCount)
            put("updatedAt", meta.updatedAt)
        }
        AppDatabase.getDatabase(context).systemDao()
            .putState(SystemState("$KEY_JOURNAL_META_PREFIX$dateKey", json.toString(), meta.updatedAt))
    }

    private fun clearJournalMeta(context: Context, dateKey: String) {
        AppDatabase.getDatabase(context).systemDao()
            .putState(SystemState("$KEY_JOURNAL_META_PREFIX$dateKey", "", System.currentTimeMillis()))
    }

    private fun loadJournalMeta(context: Context, dateKey: String): JournalMeta? {
        val raw = AppDatabase.getDatabase(context).systemDao().getState("$KEY_JOURNAL_META_PREFIX$dateKey")
        if (raw.isNullOrBlank()) return null
        return parseJournalMeta(raw)
    }

    private fun parseJournalMeta(raw: String): JournalMeta? {
        return try {
            val obj = JSONObject(raw)
            val tagsArr = obj.optJSONArray("tags")
            val tags = mutableListOf<String>()
            if (tagsArr != null) {
                for (i in 0 until tagsArr.length()) {
                    val t = tagsArr.optString(i).trim()
                    if (t.isNotEmpty()) tags.add(t)
                }
            }
            JournalMeta(
                mood = obj.optString("mood", ""),
                energy = obj.optInt("energy", 0).coerceIn(0, 5),
                tags = tags,
                wordCount = obj.optInt("wordCount", 0),
                updatedAt = obj.optLong("updatedAt", 0L)
            )
        } catch (_: Throwable) {
            null
        }
    }

    // -------------------------------------------------------------------------
    // Task Management (Room TodoItem + SystemState TASK_META_v1)
    // -------------------------------------------------------------------------

    @Synchronized
    private fun loadAllTaskMeta(context: Context): MutableMap<Long, TaskMeta> {
        val out = HashMap<Long, TaskMeta>()
        val raw = try {
            FocusGuardApp.ensureRoomMainThreadQueries(context)
            AppDatabase.getDatabase(context).systemDao().getState(KEY_TASK_META_MAP)
        } catch (_: Throwable) {
            null
        }
        if (raw.isNullOrBlank()) return out
        try {
            val root = JSONObject(raw)
            val keys = root.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val id = key.toLongOrNull() ?: continue
                val obj = root.optJSONObject(key) ?: continue
                val due = if (obj.has("due") && !obj.isNull("due")) obj.optInt("due", -1).takeIf { it in 0..1439 } else null
                out[id] = TaskMeta(
                    priority = TaskPriority.fromCode(obj.optString("pri")),
                    dueTimeMinutes = due,
                    reminderEnabled = obj.optBoolean("rem", false),
                    recurrence = TaskRecurrence.fromCode(obj.optString("rec")),
                    seriesId = obj.optString("sid", "").takeIf { it.isNotBlank() },
                    rescheduledFrom = obj.optString("from", "").takeIf { it.isNotBlank() }
                )
            }
        } catch (_: Throwable) {}
        return out
    }

    @Synchronized
    private fun persistAllTaskMeta(context: Context, map: Map<Long, TaskMeta>) {
        val root = JSONObject()
        for ((id, m) in map) {
            val obj = JSONObject().apply {
                put("pri", m.priority.code)
                if (m.dueTimeMinutes != null) put("due", m.dueTimeMinutes)
                put("rem", m.reminderEnabled)
                put("rec", m.recurrence.code)
                if (!m.seriesId.isNullOrBlank()) put("sid", m.seriesId)
                if (!m.rescheduledFrom.isNullOrBlank()) put("from", m.rescheduledFrom)
            }
            root.put(id.toString(), obj)
        }
        AppDatabase.getDatabase(context).systemDao()
            .putState(SystemState(KEY_TASK_META_MAP, root.toString(), System.currentTimeMillis()))
    }

    fun isTaskOverdue(context: Context, item: TodoItem, meta: TaskMeta): Boolean {
        if (item.isDone) return false
        val today = todayKey(context)
        if (item.dateKey < today) return true
        if (item.dateKey > today) return false
        val dueMins = meta.dueTimeMinutes ?: return false
        val cal = Calendar.getInstance()
        val nowMins = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return nowMins > dueMins
    }

    private fun enrichAndSortTasks(context: Context, items: List<TodoItem>, metaMap: Map<Long, TaskMeta>): List<EnrichedTask> {
        return items.map { item ->
            val m = metaMap[item.id] ?: TaskMeta()
            EnrichedTask(item, m, isTaskOverdue(context, item, m))
        }.sortedWith(
            compareBy<EnrichedTask> { it.item.isDone }
                .thenByDescending { it.isOverdue }
                .thenBy { it.meta.priority.sortRank }
                .thenBy { it.meta.dueTimeMinutes ?: 2000 }
                .thenBy { it.item.createdAt }
        )
    }

    fun getTasksForDate(context: Context, dateKey: String): List<EnrichedTask> {
        FocusGuardApp.ensureRoomMainThreadQueries(context)
        val items = try { AppDatabase.getDatabase(context).systemDao().getTodosForDate(dateKey) } catch (_: Throwable) { emptyList() }
        val metaMap = loadAllTaskMeta(context)
        return enrichAndSortTasks(context, items, metaMap)
    }

    fun getAllEnrichedTasks(context: Context): List<EnrichedTask> {
        FocusGuardApp.ensureRoomMainThreadQueries(context)
        val items = try { AppDatabase.getDatabase(context).systemDao().getAllTodos() } catch (_: Throwable) { emptyList() }
        val metaMap = loadAllTaskMeta(context)
        return enrichAndSortTasks(context, items, metaMap)
    }

    fun getOverdueTasks(context: Context): List<EnrichedTask> {
        return getAllEnrichedTasks(context)
            .filter { it.isOverdue && !it.item.isDone }
            .sortedWith(compareBy<EnrichedTask> { it.item.dateKey }.thenBy { it.meta.priority.sortRank })
    }

    fun createTask(
        context: Context,
        dateKey: String,
        title: String,
        priority: TaskPriority = TaskPriority.NORMAL,
        dueTimeMinutes: Int? = null,
        reminderEnabled: Boolean = false,
        recurrence: TaskRecurrence = TaskRecurrence.NONE,
        seriesId: String? = null
    ): EnrichedTask {
        val dao = AppDatabase.getDatabase(context).systemDao()
        val cleanTitle = title.trim()
        val now = System.currentTimeMillis()
        val newId = dao.insertTodo(
            TodoItem(
                id = 0L,
                dateKey = dateKey,
                text = cleanTitle,
                isDone = false,
                createdAt = now
            )
        )
        val effSeriesId = if (recurrence != TaskRecurrence.NONE) {
            seriesId ?: UUID.randomUUID().toString().take(8)
        } else null

        val meta = TaskMeta(
            priority = priority,
            dueTimeMinutes = dueTimeMinutes,
            reminderEnabled = reminderEnabled && dueTimeMinutes != null,
            recurrence = recurrence,
            seriesId = effSeriesId
        )
        val map = loadAllTaskMeta(context)
        map[newId] = meta
        persistAllTaskMeta(context, map)

        val item = TodoItem(newId, dateKey, cleanTitle, false, now)
        val enriched = EnrichedTask(item, meta, isTaskOverdue(context, item, meta))
        onTaskMutated(context, enriched)
        return enriched
    }

    fun updateTask(
        context: Context,
        taskId: Long,
        dateKey: String,
        title: String,
        isDone: Boolean,
        priority: TaskPriority,
        dueTimeMinutes: Int?,
        reminderEnabled: Boolean,
        recurrence: TaskRecurrence
    ): EnrichedTask? {
        val dao = AppDatabase.getDatabase(context).systemDao()
        val existing = dao.getAllTodos().firstOrNull { it.id == taskId } ?: return null
        val updated = existing.copy(
            dateKey = dateKey,
            text = title.trim(),
            isDone = isDone
        )
        dao.updateTodo(updated)

        val map = loadAllTaskMeta(context)
        val prevMeta = map[taskId] ?: TaskMeta()
        val effSeriesId = if (recurrence != TaskRecurrence.NONE) {
            prevMeta.seriesId ?: UUID.randomUUID().toString().take(8)
        } else null
        val meta = prevMeta.copy(
            priority = priority,
            dueTimeMinutes = dueTimeMinutes,
            reminderEnabled = reminderEnabled && dueTimeMinutes != null,
            recurrence = recurrence,
            seriesId = effSeriesId
        )
        map[taskId] = meta
        persistAllTaskMeta(context, map)

        val enriched = EnrichedTask(updated, meta, isTaskOverdue(context, updated, meta))
        onTaskMutated(context, enriched)
        return enriched
    }

    /**
     * Toggles completion. When completing a recurring task, automatically schedules the next
     * occurrence on the next valid date (if one does not already exist in the same series).
     */
    fun toggleTaskDone(context: Context, taskId: Long, done: Boolean): Pair<EnrichedTask?, String?> {
        val dao = AppDatabase.getDatabase(context).systemDao()
        val existing = dao.getAllTodos().firstOrNull { it.id == taskId } ?: return null to null
        val updated = existing.copy(isDone = done)
        dao.updateTodo(updated)

        val map = loadAllTaskMeta(context)
        val meta = map[taskId] ?: TaskMeta()
        val enriched = EnrichedTask(updated, meta, isTaskOverdue(context, updated, meta))

        var nextOccurrenceDateKey: String? = null
        if (done && meta.recurrence != TaskRecurrence.NONE) {
            val nextDate = computeNextRecurrenceDate(existing.dateKey, meta.recurrence)
            val existingNextDayTodos = dao.getTodosForDate(nextDate)
            val alreadyExists = existingNextDayTodos.any { t ->
                val tm = map[t.id]
                (meta.seriesId != null && tm?.seriesId == meta.seriesId) ||
                    t.text.equals(existing.text, ignoreCase = true)
            }
            if (!alreadyExists) {
                createTask(
                    context = context,
                    dateKey = nextDate,
                    title = existing.text,
                    priority = meta.priority,
                    dueTimeMinutes = meta.dueTimeMinutes,
                    reminderEnabled = meta.reminderEnabled,
                    recurrence = meta.recurrence,
                    seriesId = meta.seriesId
                )
                nextOccurrenceDateKey = nextDate
            }
        }

        onTaskMutated(context, enriched)
        return enriched to nextOccurrenceDateKey
    }

    fun rescheduleTask(context: Context, taskId: Long, targetDateKey: String): EnrichedTask? {
        val dao = AppDatabase.getDatabase(context).systemDao()
        val existing = dao.getAllTodos().firstOrNull { it.id == taskId } ?: return null
        if (existing.dateKey == targetDateKey) {
            val m = loadAllTaskMeta(context)[taskId] ?: TaskMeta()
            return EnrichedTask(existing, m, isTaskOverdue(context, existing, m))
        }
        val moved = existing.copy(dateKey = targetDateKey)
        dao.updateTodo(moved)

        val map = loadAllTaskMeta(context)
        val prev = map[taskId] ?: TaskMeta()
        val nextMeta = prev.copy(rescheduledFrom = prev.rescheduledFrom ?: existing.dateKey)
        map[taskId] = nextMeta
        persistAllTaskMeta(context, map)

        val enriched = EnrichedTask(moved, nextMeta, isTaskOverdue(context, moved, nextMeta))
        onTaskMutated(context, enriched)
        return enriched
    }

    fun rescheduleAllOverdueToToday(context: Context): Int {
        val today = todayKey(context)
        val overdue = getOverdueTasks(context).filter { it.item.dateKey < today }
        var count = 0
        for (task in overdue) {
            if (rescheduleTask(context, task.item.id, today) != null) {
                count++
            }
        }
        return count
    }

    fun deleteTask(context: Context, taskId: Long) {
        val dao = AppDatabase.getDatabase(context).systemDao()
        dao.deleteTodo(taskId)
        val map = loadAllTaskMeta(context)
        if (map.remove(taskId) != null) {
            persistAllTaskMeta(context, map)
        }
        taskReminderHook?.invoke(context, taskId, null)
        try {
            SmartNotificationManager.refreshNow(context)
        } catch (_: Throwable) {}
    }

    fun computeNextRecurrenceDate(fromDateKey: String, recurrence: TaskRecurrence): String {
        val cal = parseDateKeyToCalendar(fromDateKey)
        when (recurrence) {
            TaskRecurrence.DAILY -> cal.add(Calendar.DAY_OF_YEAR, 1)
            TaskRecurrence.WEEKLY -> cal.add(Calendar.DAY_OF_YEAR, 7)
            TaskRecurrence.WEEKDAYS -> {
                do {
                    cal.add(Calendar.DAY_OF_YEAR, 1)
                    val dow = cal.get(Calendar.DAY_OF_WEEK)
                } while (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY)
            }
            TaskRecurrence.NONE -> cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return dateKeyFor(cal)
    }

    /**
     * Hook populated in Stage 2 by TaskReminderScheduler so Stage 1 compiles cleanly without circular deps.
     */
    @Volatile
    var taskReminderHook: ((Context, Long, EnrichedTask?) -> Unit)? = null

    private fun onTaskMutated(context: Context, task: EnrichedTask) {
        taskReminderHook?.invoke(context, task.item.id, task)
        try {
            SmartNotificationManager.refreshNow(context)
        } catch (_: Throwable) {}
    }

    // -------------------------------------------------------------------------
    // Calendar Aggregation (Month / Week / Agenda / Single Day)
    // -------------------------------------------------------------------------

    fun getStepsOrNullForDate(
        context: Context,
        dateKey: String,
        dayStartMs: Long,
        preloadedDaySessionSteps: Int? = null
    ): Int? {
        val today = todayKey(context)
        if (dateKey > today) return null
        return try {
            ActivityTelemetryEngine.getFastLocalStepsForDay(
                context = context,
                dayStartMs = dayStartMs,
                preloadedDaySessionSteps = preloadedDaySessionSteps
            )
        } catch (_: Throwable) {
            if (dateKey == today) {
                try { StepPrefs.getDailySteps(context) } catch (_: Throwable) { 0 }
            } else {
                try { StepHistoryManager.getStepsForDay(context, dateKey) } catch (_: Throwable) { null }
            }
        }
    }

    fun loadDayRangeSummaries(
        context: Context,
        startDateKey: String,
        endDateKey: String
    ): Map<String, CalendarDaySummary> {
        FocusGuardApp.ensureRoomMainThreadQueries(context)
        val db = try { AppDatabase.getDatabase(context) } catch (_: Throwable) { null }
        val sysDao = db?.systemDao()
        val exDao = db?.exerciseDao()

        val stepGoal = try { GoalPrefs.getStepGoal(context).coerceAtLeast(1000) } catch (_: Throwable) { 6000 }
        val today = todayKey(context)
        val isVerifiedHolidayToday = try { FocusLayerManager.isHolidayToday(context) } catch (_: Throwable) { false }

        val diariesByDate = try {
            sysDao?.getDiaryEntriesBetween(startDateKey, endDateKey)?.associateBy { it.dateKey } ?: emptyMap()
        } catch (_: Throwable) {
            emptyMap()
        }
        val allTodos = try {
            sysDao?.getAllTodos()?.filter { it.dateKey in startDateKey..endDateKey } ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
        val todosByDate = allTodos.groupBy { it.dateKey }
        val taskMetaMap = loadAllTaskMeta(context)

        val allStates = try {
            sysDao?.getAllSystemState() ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
        val journalMetaMap = HashMap<String, JournalMeta>()
        for (st in allStates) {
            if (st.stateKey.startsWith(KEY_JOURNAL_META_PREFIX) && st.stateValue.isNotBlank()) {
                val dk = st.stateKey.removePrefix(KEY_JOURNAL_META_PREFIX)
                if (dk in startDateKey..endDateKey) {
                    parseJournalMeta(st.stateValue)?.let { journalMetaMap[dk] = it }
                }
            }
        }

        val rangeStartMs = startOfDayMs(startDateKey)
        val rangeEndMs = startOfDayMs(endDateKey) + 86_400_000L - 1L

        val workoutsByDate = try {
            exDao?.getAllLogs()
                ?.filter { it.timestamp in rangeStartMs..rangeEndMs }
                ?.groupBy { dateKeyForMs(it.timestamp) }
                ?: emptyMap()
        } catch (_: Throwable) {
            emptyMap()
        }

        // Trigger non-blocking background Health Connect sync instead of blocking 30x in the day loop
        ActivityTelemetryEngine.triggerBackgroundHealthConnectSync(context, 31)

        val allWalkSessionsByDate = try {
            DistanceHelper.getSessions(context, rangeStartMs, rangeEndMs)
                .groupBy { dateKeyForMs(it.startTime) }
        } catch (_: Throwable) {
            emptyMap()
        }
        val walkSessionsByDate = allWalkSessionsByDate.mapValues { (_, list) ->
            list.filter { it.distanceKm >= 0.12 || it.durationMillis >= 90_000L }
        }

        val auditByDate = try {
            sysDao?.getAuditRecordsBetween(rangeStartMs, rangeEndMs)
                ?.filter { it.eventType.startsWith("AWAY_") }
                ?.groupBy { dateKeyForMs(it.timestamp) }
                ?: emptyMap()
        } catch (_: Throwable) {
            emptyMap()
        }

        val result = LinkedHashMap<String, CalendarDaySummary>()
        val cursor = parseDateKeyToCalendar(startDateKey)
        val endCal = parseDateKeyToCalendar(endDateKey)

        while (!cursor.after(endCal)) {
            val dk = dateKeyFor(cursor)
            val dStart = cursor.timeInMillis
            val daySessionSteps = allWalkSessionsByDate[dk]?.sumOf { it.steps } ?: 0
            val steps = getStepsOrNullForDate(context, dk, dStart, preloadedDaySessionSteps = daySessionSteps)
            val diary = diariesByDate[dk]
            val jMeta = journalMetaMap[dk]
            val dayWorkouts = workoutsByDate[dk] ?: emptyList()
            val walkSessions = if (dk <= today) (walkSessionsByDate[dk]?.size ?: 0) else 0
            val dayTasks = enrichAndSortTasks(context, todosByDate[dk] ?: emptyList(), taskMetaMap)
            val dayAudits = (auditByDate[dk] ?: emptyList()).sortedByDescending { it.timestamp }
            val hasNote = !diary?.note.isNullOrBlank()
            val snippet = diary?.note?.trim()?.replace('\n', ' ')?.take(120) ?: ""

            result[dk] = CalendarDaySummary(
                dateKey = dk,
                dayStartMs = dStart,
                steps = steps,
                stepGoal = stepGoal,
                stepGoalMet = steps != null && steps >= stepGoal,
                workoutLogs = dayWorkouts,
                walkSessionCount = walkSessions,
                isHoliday = diary?.isHoliday == true,
                isVerifiedHolidayToday = (dk == today && isVerifiedHolidayToday),
                hasDiary = hasNote,
                diarySnippet = snippet,
                journalMeta = jMeta,
                tasks = dayTasks,
                awayAuditRecords = dayAudits
            )
            cursor.add(Calendar.DAY_OF_YEAR, 1)
        }
        return result
    }

    fun loadMonthSummaries(
        context: Context,
        year: Int,
        monthZeroBased: Int
    ): Pair<Map<String, CalendarDaySummary>, MonthlyDisciplineSummary> {
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, monthZeroBased)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val monthLabel = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(cal.time)
        val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val startKey = dateKeyFor(cal)
        val endCal = (cal.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, daysInMonth) }
        val endKey = dateKeyFor(endCal)

        val daysMap = loadDayRangeSummaries(context, startKey, endKey)
        val today = todayKey(context)

        var activeDays = 0
        var stepGoalDays = 0
        var recordedStepDays = 0
        var workoutDays = 0
        var totalTasks = 0
        var completedTasks = 0
        var journalDays = 0
        var holidayDays = 0

        for ((dk, s) in daysMap) {
            if (s.isHoliday) holidayDays++
            totalTasks += s.totalTasks
            completedTasks += s.completedTasks
            if (s.hasDiary) journalDays++
            if (dk <= today) {
                if (s.steps != null) recordedStepDays++
                if (s.stepGoalMet) stepGoalDays++
                if (s.hasWorkout) workoutDays++
                if (s.isActiveDay) activeDays++
            }
        }

        val summary = MonthlyDisciplineSummary(
            monthLabel = monthLabel,
            daysInMonth = daysInMonth,
            activeDays = activeDays,
            stepGoalDays = stepGoalDays,
            recordedStepDays = recordedStepDays,
            workoutDays = workoutDays,
            totalTasks = totalTasks,
            completedTasks = completedTasks,
            journalDays = journalDays,
            holidayDays = holidayDays
        )
        return daysMap to summary
    }

    // -------------------------------------------------------------------------
    // Transparent Discipline Insights Report (Zero Invented Historical Data)
    // -------------------------------------------------------------------------

    fun loadPeriodInsights(
        context: Context,
        periodDays: Int,
        precomputedWellbeingByDateKey: Map<String, UsageStatsHelper.WellbeingSnapshot?>? = null
    ): PeriodInsightsReport {
        val today = todayKey(context)
        val startKey = addDays(today, -(periodDays - 1))
        val dayMap = loadDayRangeSummaries(context, startKey, today)
        val stepGoal = try { GoalPrefs.getStepGoal(context).coerceAtLeast(1000) } catch (_: Throwable) { 6000 }

        val dailySteps = ArrayList<Pair<String, Int?>>(periodDays)
        val dailyWorkouts = ArrayList<Pair<String, Int>>(periodDays)
        val dailyTasks = ArrayList<Triple<String, Int, Int>>(periodDays)

        var recordedStepDays = 0
        var stepGoalMetDays = 0
        var stepSum = 0
        var workoutActiveDays = 0
        var totalWorkoutSets = 0
        var totalTasksScheduled = 0
        var totalTasksCompleted = 0
        var journalDaysCount = 0
        var totalJournalWords = 0
        var energySum = 0
        var energyCount = 0
        val moodCounts = HashMap<String, Int>()

        for ((dk, s) in dayMap) {
            dailySteps.add(dk to s.steps)
            if (s.steps != null) {
                recordedStepDays++
                stepSum += s.steps
                if (s.stepGoalMet) stepGoalMetDays++
            }
            val genuineSets = s.workoutLogs.size
            dailyWorkouts.add(dk to genuineSets)
            if (genuineSets > 0) workoutActiveDays++
            totalWorkoutSets += genuineSets

            dailyTasks.add(Triple(dk, s.completedTasks, s.totalTasks))
            totalTasksScheduled += s.totalTasks
            totalTasksCompleted += s.completedTasks

            if (s.hasDiary) {
                journalDaysCount++
                totalJournalWords += s.journalMeta?.wordCount ?: countWords(s.diarySnippet)
            }
            val jMeta = s.journalMeta
            if (jMeta != null) {
                if (jMeta.energy in 1..5) {
                    energySum += jMeta.energy
                    energyCount++
                }
                if (jMeta.mood.isNotBlank()) {
                    moodCounts[jMeta.mood] = (moodCounts[jMeta.mood] ?: 0) + 1
                }
            }
        }

        val overdueCount = getOverdueTasks(context).size

        // Focus sessions from FocusSessionManager (real logged history only)
        val startMs = startOfDayMs(startKey)
        val focusByDay = HashMap<String, Int>()
        var focusSessionsCompleted = 0
        var focusMinutesTotal = 0
        try {
            val sessions = FocusSessionManager.getSessionHistory(context)
            for (s in sessions) {
                if (s.timestampMs >= startMs) {
                    val dk = dateKeyForMs(s.timestampMs)
                    if (dk in startKey..today) {
                        val mins = if (s.completed) s.durationMinutes else (s.elapsedSeconds / 60)
                        if (mins > 0) {
                            focusByDay[dk] = (focusByDay[dk] ?: 0) + mins
                            focusMinutesTotal += mins
                        }
                        if (s.completed) focusSessionsCompleted++
                    }
                }
            }
        } catch (_: Throwable) {}

        val dailyFocus = dayMap.keys.map { dk -> dk to (focusByDay[dk] ?: 0) }

        // Productive vs Distracted time (strictly from real available UsageEvents splits — never fabricated)
        val prodVsDist = ArrayList<Triple<String, Int, Int>>()
        var totalProd = 0
        var totalDist = 0
        val nowMs = System.currentTimeMillis()
        for ((dk, s) in dayMap) {
            val daysAgo = ((startOfDayMs(today) - s.dayStartMs) / 86_400_000L).toInt()
            if (daysAgo !in 0..9) continue
            val dEnd = if (dk == today) nowMs else s.dayStartMs + 86_400_000L - 1L
            val snap = if (precomputedWellbeingByDateKey != null && precomputedWellbeingByDateKey.containsKey(dk)) {
                precomputedWellbeingByDateKey[dk]
            } else {
                try {
                    if (dk == today) UsageStatsHelper.getTodayWellbeingSnapshot(context, forceRefresh = false)
                    else UsageStatsHelper.getDayWellbeingSnapshot(context, s.dayStartMs, dEnd, forceRefresh = false)
                } catch (_: Throwable) {
                    null
                }
            }
            if (snap != null && (snap.studyMs + snap.improveMs + snap.distractMs + snap.utilityMs) > 0L) {
                val pMins = ((snap.studyMs + snap.improveMs) / 60_000L).toInt().coerceIn(0, 1440)
                val dMins = (snap.distractMs / 60_000L).toInt().coerceIn(0, 1440)
                prodVsDist.add(Triple(dk, pMins, dMins))
                totalProd += pMins
                totalDist += dMins
            }
        }

        val sleepRecovery = try {
            SmartSleepEngine.syncRecentNightsArchive(context, lookbackDays = periodDays.coerceAtMost(14))
            SmartSleepEngine.getPeriodRecoverySummary(context, periodDays = periodDays)
        } catch (_: Throwable) {
            null
        }

        return PeriodInsightsReport(
            periodDays = periodDays,
            stepGoal = stepGoal,
            dailySteps = dailySteps,
            recordedStepDays = recordedStepDays,
            stepGoalMetDays = stepGoalMetDays,
            avgRecordedSteps = if (recordedStepDays > 0) stepSum / recordedStepDays else null,
            dailyWorkoutCounts = dailyWorkouts,
            workoutActiveDays = workoutActiveDays,
            totalWorkoutSets = totalWorkoutSets,
            dailyTaskStats = dailyTasks,
            totalTasksScheduled = totalTasksScheduled,
            totalTasksCompleted = totalTasksCompleted,
            overduePendingCount = overdueCount,
            journalDaysCount = journalDaysCount,
            totalJournalWords = totalJournalWords,
            avgEnergyRating = if (energyCount > 0) energySum.toFloat() / energyCount else null,
            topMood = moodCounts.maxByOrNull { it.value }?.key,
            focusSessionsCompleted = focusSessionsCompleted,
            focusSessionMinutes = focusMinutesTotal,
            dailyFocusMinutes = dailyFocus,
            productiveVsDistractedDays = prodVsDist,
            totalProductiveMins = totalProd,
            totalDistractedMins = totalDist,
            telemetrySplitDays = prodVsDist.size,
            dailyExerciseSets = dailyWorkouts,
            sleepRecovery = sleepRecovery
        )
    }
}
