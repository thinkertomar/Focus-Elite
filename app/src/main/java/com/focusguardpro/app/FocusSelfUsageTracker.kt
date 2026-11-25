package com.focusguardpro.app

import android.app.Activity
import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Tracks exact foreground time spent inside Focus Elite itself, split accurately by module:
 * - STUDY   : PracticeSessionActivity, FocusSessionActivity -> "Focus Elite · Study Suite"
 * - IMPROVE : ActivityStatsActivity, CalisthenicsActivity, Distance/Calories/MoveMin/Weight/Sleep -> "Focus Elite · Fitness & Workouts"
 * - UTILITY : MainActivity, Settings, Calendar, Insights, UsageStats, etc. -> "Focus Elite · Shield & Config"
 *
 * Persists daily totals and 24-hour bucket milliseconds in SharedPreferences (100% offline)
 * so UsageStatsHelper & UsageStatsActivity reflect accurate, categorized Focus Elite time.
 */
object FocusSelfUsageTracker {

    private const val PREFS_NAME = "FocusElite_SelfModuleUsage_v1"

    const val MODULE_STUDY = "STUDY"
    const val MODULE_IMPROVE = "IMPROVE"
    const val MODULE_UTILITY = "UTILITY"

    const val VIRTUAL_PKG_STUDY = "com.focuselite.virtual.study"
    const val VIRTUAL_PKG_IMPROVE = "com.focuselite.virtual.improve"
    const val VIRTUAL_PKG_UTILITY = "com.focuselite.virtual.utility"

    @Volatile
    private var activeModule: String? = null
    @Volatile
    private var activeStartMs: Long = 0L
    @Volatile
    private var activeActivityName: String? = null

    data class ModuleUsageSummary(
        val studyMs: Long,
        val studyOpens: Int,
        val studyLongestMs: Long,
        val studyLastUsedMs: Long,
        val improveMs: Long,
        val improveOpens: Int,
        val improveLongestMs: Long,
        val improveLastUsedMs: Long,
        val utilityMs: Long,
        val utilityOpens: Int,
        val utilityLongestMs: Long,
        val utilityLastUsedMs: Long,
        val hourlyStudyMs: LongArray,
        val hourlyImproveMs: LongArray,
        val hourlyUtilityMs: LongArray
    ) {
        val totalMs: Long get() = studyMs + improveMs + utilityMs
    }

    fun isVirtualFocusElitePackage(pkg: String): Boolean {
        return pkg == VIRTUAL_PKG_STUDY || pkg == VIRTUAL_PKG_IMPROVE || pkg == VIRTUAL_PKG_UTILITY
    }

    fun getVirtualDisplayName(pkg: String): String? = when (pkg) {
        VIRTUAL_PKG_STUDY -> "Focus Elite · Study Suite"
        VIRTUAL_PKG_IMPROVE -> "Focus Elite · Fitness & Workouts"
        VIRTUAL_PKG_UTILITY -> "Focus Elite · Shield & Config"
        else -> null
    }

    fun getVirtualShortLabel(pkg: String): String? = when (pkg) {
        VIRTUAL_PKG_STUDY -> "FE Study"
        VIRTUAL_PKG_IMPROVE -> "FE Fit"
        VIRTUAL_PKG_UTILITY -> "FE Guard"
        else -> null
    }

    fun classifyActivity(activity: Activity): String {
        val name = activity.javaClass.simpleName
        return when (name) {
            "PracticeSessionActivity",
            "FocusSessionActivity" -> MODULE_STUDY

            "ActivityStatsActivity",
            "ActivitySessionDetailActivity",
            "CalisthenicsActivity",
            "DistanceHistoryActivity",
            "CaloriesHistoryActivity",
            "MoveMinHistoryActivity",
            "WeightHistoryActivity",
            "SleepLogActivity",
            "SleepSummaryActivity",
            "EliteSleepHistoryActivity",
            "EliteDailyNotebookActivity",
            "EliteJournalActivity",
            "EliteTasksActivity" -> MODULE_IMPROVE

            else -> MODULE_UTILITY
        }
    }

    @Synchronized
    fun onActivityResumed(activity: Activity) {
        val ctx = activity.applicationContext
        val now = System.currentTimeMillis()
        val newModule = classifyActivity(activity)

        // Flush previous segment if another Focus Elite activity was active
        if (activeModule != null && activeStartMs > 0L) {
            commitSegment(ctx, activeModule!!, activeStartMs, now, incrementOpen = false)
        }

        activeModule = newModule
        activeStartMs = now
        val isNewModuleOpen = activeActivityName != activity.javaClass.simpleName
        activeActivityName = activity.javaClass.simpleName
        if (isNewModuleOpen) {
            incrementOpenCount(ctx, newModule, now)
        }
    }

    @Synchronized
    fun onActivityPaused(activity: Activity) {
        val ctx = activity.applicationContext
        val now = System.currentTimeMillis()
        if (activeModule != null && activeStartMs > 0L) {
            commitSegment(ctx, activeModule!!, activeStartMs, now, incrementOpen = false)
        }
        activeStartMs = 0L
        activeModule = null
    }

    @Synchronized
    fun flushActiveSession(context: Context) {
        val mod = activeModule ?: return
        val start = activeStartMs
        if (start <= 0L) return
        val now = System.currentTimeMillis()
        if (now > start) {
            commitSegment(context.applicationContext, mod, start, now, incrementOpen = false)
            activeStartMs = now
        }
    }

    private fun incrementOpenCount(context: Context, module: String, nowMs: Long) {
        try {
            val dayKey = formatDayKey(nowMs)
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val key = "${dayKey}_${module}_opens"
            prefs.edit()
                .putInt(key, prefs.getInt(key, 0) + 1)
                .putLong("${dayKey}_${module}_last", nowMs)
                .apply()
        } catch (_: Throwable) {}
    }

    private fun commitSegment(
        context: Context,
        module: String,
        startMs: Long,
        endMs: Long,
        incrementOpen: Boolean
    ) {
        val duration = (endMs - startMs).coerceIn(0L, 4 * 3_600_000L)
        if (duration < 500L) return
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val editor = prefs.edit()

            var cursor = startMs
            while (cursor < endMs) {
                val cal = Calendar.getInstance().apply { timeInMillis = cursor }
                val dayKey = formatDayKey(cursor)
                val hour = cal.get(Calendar.HOUR_OF_DAY)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                val nextHourMs = cal.timeInMillis + 3_600_000L
                val sliceEnd = min(endMs, nextHourMs)
                val sliceMs = (sliceEnd - cursor).coerceAtLeast(0L)

                if (sliceMs > 0L) {
                    val totalKey = "${dayKey}_${module}_ms"
                    val hourKey = "${dayKey}_${module}_h$hour"
                    val prevTotal = prefs.getLong(totalKey, 0L)
                    val prevHour = prefs.getLong(hourKey, 0L)
                    editor.putLong(totalKey, prevTotal + sliceMs)
                    editor.putLong(hourKey, min(3_600_000L, prevHour + sliceMs))
                    editor.putLong("${dayKey}_${module}_last", sliceEnd)
                    val longestKey = "${dayKey}_${module}_longest"
                    if (duration > prefs.getLong(longestKey, 0L)) {
                        editor.putLong(longestKey, duration)
                    }
                    if (incrementOpen) {
                        val opensKey = "${dayKey}_${module}_opens"
                        editor.putInt(opensKey, prefs.getInt(opensKey, 0) + 1)
                    }
                }
                cursor = max(sliceEnd, cursor + 1L)
            }
            editor.apply()
        } catch (_: Throwable) {}
    }

    fun getDayModuleSummary(context: Context, dayStartMs: Long): ModuleUsageSummary {
        // Flush live session if querying today so dashboard numbers are always fresh to the second
        val todayCal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (kotlin.math.abs(dayStartMs - todayCal.timeInMillis) < 60_000L) {
            flushActiveSession(context)
        }

        val dayKey = formatDayKey(dayStartMs)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val hStudy = LongArray(24)
        val hImprove = LongArray(24)
        val hUtility = LongArray(24)
        for (h in 0 until 24) {
            hStudy[h] = prefs.getLong("${dayKey}_${MODULE_STUDY}_h$h", 0L)
            hImprove[h] = prefs.getLong("${dayKey}_${MODULE_IMPROVE}_h$h", 0L)
            hUtility[h] = prefs.getLong("${dayKey}_${MODULE_UTILITY}_h$h", 0L)
        }

        val studyMs = max(prefs.getLong("${dayKey}_${MODULE_STUDY}_ms", 0L), hStudy.sum())
        val improveMs = max(prefs.getLong("${dayKey}_${MODULE_IMPROVE}_ms", 0L), hImprove.sum())
        val utilityMs = max(prefs.getLong("${dayKey}_${MODULE_UTILITY}_ms", 0L), hUtility.sum())

        return ModuleUsageSummary(
            studyMs = studyMs,
            studyOpens = prefs.getInt("${dayKey}_${MODULE_STUDY}_opens", if (studyMs > 0L) 1 else 0),
            studyLongestMs = prefs.getLong("${dayKey}_${MODULE_STUDY}_longest", studyMs),
            studyLastUsedMs = prefs.getLong("${dayKey}_${MODULE_STUDY}_last", dayStartMs),
            improveMs = improveMs,
            improveOpens = prefs.getInt("${dayKey}_${MODULE_IMPROVE}_opens", if (improveMs > 0L) 1 else 0),
            improveLongestMs = prefs.getLong("${dayKey}_${MODULE_IMPROVE}_longest", improveMs),
            improveLastUsedMs = prefs.getLong("${dayKey}_${MODULE_IMPROVE}_last", dayStartMs),
            utilityMs = utilityMs,
            utilityOpens = prefs.getInt("${dayKey}_${MODULE_UTILITY}_opens", if (utilityMs > 0L) 1 else 0),
            utilityLongestMs = prefs.getLong("${dayKey}_${MODULE_UTILITY}_longest", utilityMs),
            utilityLastUsedMs = prefs.getLong("${dayKey}_${MODULE_UTILITY}_last", dayStartMs),
            hourlyStudyMs = hStudy,
            hourlyImproveMs = hImprove,
            hourlyUtilityMs = hUtility
        )
    }

    private fun formatDayKey(tsMs: Long): String {
        return SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(tsMs))
    }
}
