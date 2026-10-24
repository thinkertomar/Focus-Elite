package com.focusguardpro.app

import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.focusguardpro.app.db.AppDatabase
import com.focusguardpro.app.db.SystemState
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object FocusLayerManager {
    const val DEFAULT_START_MINUTES = 540 // 09:00 AM
    const val DEFAULT_END_MINUTES = 1080  // 06:00 PM

    const val DAYS_MON_SAT = 0
    const val DAYS_MON_FRI = 1
    const val DAYS_EVERY_DAY = 2
    const val DAYS_CUSTOM = 3

    private const val PREFS_NAME = "focus_layer_criteria_prefs"
    private const val KEY_SCHEDULE_ENABLED = "focus_schedule_enabled"
    private const val KEY_START_MINUTES = "focus_start_minutes"
    private const val KEY_END_MINUTES = "focus_end_minutes"
    private const val KEY_DAYS_MODE = "focus_days_mode"
    private const val KEY_CUSTOM_DAYS_SET = "focus_custom_days_set"
    private const val KEY_STRICT_LOCK_ENABLED = "focus_strict_lock_enabled"
    private const val KEY_ADAPTIVE_CALENDAR_SYNC = "focus_adaptive_calendar_sync"
    private const val KEY_ADAPTIVE_BREAK_UNTIL_MS = "focus_adaptive_break_until_ms"
    private const val OVERRIDE_KEY = "FOCUS_MANUAL_OVERRIDE"

    private val HOLIDAY_EVENT_KEYWORDS = listOf(
        "holiday", "vacation", "day off", "day-off", "off day", "leave",
        "trip", "travel", "break", "rest day", "free day", "festive",
        "festival", "sick", "doctor", "relax", "outing", "diwali",
        "christmas", "eid", "new year", "exam off", "event"
    )

    sealed class CalendarExemption {
        object None : CalendarExemption()
        data class Holiday(val reason: String) : CalendarExemption()
        data class CalendarEvent(val title: String) : CalendarExemption()
        data class AdaptiveBreak(val remainingMinutes: Int) : CalendarExemption()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun todayKey(): String {
        return SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
    }

    fun isSunday(nowMs: Long = System.currentTimeMillis()): Boolean {
        val cal = Calendar.getInstance()
        cal.timeInMillis = nowMs
        return cal.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY
    }

    fun isStrictLockEnabled(context: Context): Boolean {
        // Disabled by default for complete flexibility during configuration and presentations
        return prefs(context).getBoolean(KEY_STRICT_LOCK_ENABLED, false)
    }

    fun setStrictLockEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_STRICT_LOCK_ENABLED, enabled).apply()
    }

    fun getCustomDays(context: Context): Set<Int> {
        val raw = prefs(context).getStringSet(KEY_CUSTOM_DAYS_SET, null)
        if (raw != null) {
            val parsed = raw.mapNotNull { it.toIntOrNull() }.toSet()
            if (parsed.isNotEmpty()) return parsed
        }
        return setOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY)
    }

    fun setCustomDays(context: Context, days: Set<Int>) {
        val strSet = days.map { it.toString() }.toSet()
        prefs(context).edit()
            .putStringSet(KEY_CUSTOM_DAYS_SET, strSet)
            .putInt(KEY_DAYS_MODE, DAYS_CUSTOM)
            .apply()
    }

    fun toggleCustomDay(context: Context, dayOfWeek: Int): Boolean {
        val current = getCustomDays(context).toMutableSet()
        val newState = if (current.contains(dayOfWeek)) {
            current.remove(dayOfWeek)
            false
        } else {
            current.add(dayOfWeek)
            true
        }
        setCustomDays(context, current)
        return newState
    }

    fun isDayIncludedInSchedule(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        return when (getActiveDaysMode(context)) {
            DAYS_MON_FRI -> dow != Calendar.SATURDAY && dow != Calendar.SUNDAY
            DAYS_EVERY_DAY -> true
            DAYS_CUSTOM -> getCustomDays(context).contains(dow)
            else -> dow != Calendar.SUNDAY // Default: Monday to Saturday
        }
    }

    fun isScheduleEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_SCHEDULE_ENABLED, true)
    }

    fun isAdaptiveCalendarSyncEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_ADAPTIVE_CALENDAR_SYNC, true)
    }

    fun setAdaptiveCalendarSyncEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ADAPTIVE_CALENDAR_SYNC, enabled).apply()
    }

    fun getStartMinutes(context: Context): Int {
        return prefs(context).getInt(KEY_START_MINUTES, DEFAULT_START_MINUTES).coerceIn(0, 1439)
    }

    fun getEndMinutes(context: Context): Int {
        return prefs(context).getInt(KEY_END_MINUTES, DEFAULT_END_MINUTES).coerceIn(0, 1439)
    }

    fun getActiveDaysMode(context: Context): Int {
        return prefs(context).getInt(KEY_DAYS_MODE, DAYS_MON_SAT)
    }

    fun getActiveDaysLabel(context: Context): String {
        return when (getActiveDaysMode(context)) {
            DAYS_MON_FRI -> "Mon – Fri"
            DAYS_EVERY_DAY -> "Every Day (Mon – Sun)"
            DAYS_CUSTOM -> {
                val days = getCustomDays(context)
                val names = listOf(
                    Calendar.MONDAY to "Mon",
                    Calendar.TUESDAY to "Tue",
                    Calendar.WEDNESDAY to "Wed",
                    Calendar.THURSDAY to "Thu",
                    Calendar.FRIDAY to "Fri",
                    Calendar.SATURDAY to "Sat",
                    Calendar.SUNDAY to "Sun"
                )
                val selected = names.filter { days.contains(it.first) }.map { it.second }
                if (selected.isEmpty()) "None" else selected.joinToString(", ")
            }
            else -> "Mon – Sat"
        }
    }

    fun getCurrentDeviceTimeFormatted(nowMs: Long = System.currentTimeMillis()): String {
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        val dayName = SimpleDateFormat("EEEE", Locale.US).format(cal.time)
        val timeStr = SimpleDateFormat("h:mm a", Locale.US).format(cal.time)
        return "$timeStr ($dayName)"
    }

    fun formatMinutesOfDay(minutes: Int): String {
        val m = minutes.coerceIn(0, 1439)
        val h24 = m / 60
        val min = m % 60
        val amPm = if (h24 < 12) "AM" else "PM"
        val h12 = when {
            h24 == 0 -> 12
            h24 > 12 -> h24 - 12
            else -> h24
        }
        return String.format(Locale.US, "%d:%02d %s", h12, min, amPm)
    }

    fun formatWindowSummary(context: Context): String {
        if (!isScheduleEnabled(context)) {
            return "Flexible (Manual Only)"
        }
        val startStr = formatMinutesOfDay(getStartMinutes(context))
        val endStr = formatMinutesOfDay(getEndMinutes(context))
        return "$startStr – $endStr (${getActiveDaysLabel(context)})"
    }

    // =========================================================================
    // USER-ADAPTIVE BREAK & CALENDAR EVENT DETECTION
    // =========================================================================

    fun grantAdaptiveBreak(context: Context, minutes: Int = 15) {
        val until = System.currentTimeMillis() + (minutes * 60_000L)
        prefs(context).edit().putLong(KEY_ADAPTIVE_BREAK_UNTIL_MS, until).apply()
    }

    fun cancelAdaptiveBreak(context: Context) {
        prefs(context).edit().putLong(KEY_ADAPTIVE_BREAK_UNTIL_MS, 0L).apply()
    }

    fun getAdaptiveBreakRemainingMinutes(context: Context): Int {
        val until = prefs(context).getLong(KEY_ADAPTIVE_BREAK_UNTIL_MS, 0L)
        val diff = until - System.currentTimeMillis()
        return if (diff > 0L) (diff / 60_000L).toInt().coerceAtLeast(1) else 0
    }

    fun isAdaptiveBreakActive(context: Context): Boolean {
        return getAdaptiveBreakRemainingMinutes(context) > 0
    }

    /**
     * Inspects active calendar events or holidays across:
     * 1. 15-minute user-initiated adaptive breaks
     * 2. In-app Calendar DiaryEntry (marked holiday or break note)
     * 3. In-app Calendar TodoItem events (tasks marked [break], [holiday], etc.)
     * 4. Android Device Calendar (if READ_CALENDAR is granted)
     *
     * Returns CalendarExemption which describes why strict focus blocking is relaxed/paused.
     */
    fun getCalendarExemption(
        context: Context,
        nowMs: Long = System.currentTimeMillis()
    ): CalendarExemption {
        // 1. Check temporary adaptive break
        val breakMins = getAdaptiveBreakRemainingMinutes(context)
        if (breakMins > 0) {
            return CalendarExemption.AdaptiveBreak(breakMins)
        }

        if (!isAdaptiveCalendarSyncEnabled(context)) {
            // If user turned off adaptive sync, still respect direct holiday marks in DiaryEntry
            return if (isExplicitHolidayInDatabase(context, todayKey())) {
                CalendarExemption.Holiday("Marked as Holiday in Calendar")
            } else {
                CalendarExemption.None
            }
        }

        // 2. Check In-App Calendar Diary Entry for Today
        try {
            FocusGuardApp.ensureRoomMainThreadQueries(context)
            val entry = AppDatabase.getDatabase(context).systemDao().getDiaryEntry(todayKey())
            if (entry != null) {
                if (entry.isHoliday) {
                    val label = if (entry.note.isNotBlank()) entry.note else "Marked as Holiday in Calendar"
                    return CalendarExemption.Holiday(label)
                }
                val noteLower = entry.note.lowercase()
                for (kw in HOLIDAY_EVENT_KEYWORDS) {
                    if (noteLower.contains(kw)) {
                        return CalendarExemption.Holiday(entry.note)
                    }
                }
            }
        } catch (_: Throwable) {}

        // 3. Check In-App Calendar TodoItems / Events for Today
        try {
            FocusGuardApp.ensureRoomMainThreadQueries(context)
            val todos = AppDatabase.getDatabase(context).systemDao().getTodosForDate(todayKey())
            for (t in todos) {
                val textLower = t.text.lowercase()
                for (kw in HOLIDAY_EVENT_KEYWORDS) {
                    if (textLower.contains(kw)) {
                        return CalendarExemption.CalendarEvent(t.text)
                    }
                }
            }
        } catch (_: Throwable) {}

        // 4. Check Android Device Calendar Events if permission is available
        val deviceCalendarEvent = checkDeviceCalendarEvents(context, nowMs)
        if (deviceCalendarEvent != null) {
            return deviceCalendarEvent
        }

        return CalendarExemption.None
    }

    private fun isExplicitHolidayInDatabase(context: Context, dateKey: String): Boolean {
        return try {
            FocusGuardApp.ensureRoomMainThreadQueries(context)
            val entry = AppDatabase.getDatabase(context).systemDao().getDiaryEntry(dateKey)
            entry?.isHoliday ?: false
        } catch (_: Throwable) {
            false
        }
    }

    private fun checkDeviceCalendarEvents(context: Context, nowMs: Long): CalendarExemption? {
        val hasCalPermission = ContextCompat.checkSelfPermission(
            context,
            "android.permission.READ_CALENDAR"
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasCalPermission) return null

        var cursor: Cursor? = null
        return try {
            val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(builder, nowMs - 60_000L)
            ContentUris.appendId(builder, nowMs + 60_000L)
            val uri: Uri = builder.build()

            val projection = arrayOf(
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END
            )

            cursor = context.contentResolver.query(
                uri,
                projection,
                null,
                null,
                null
            )

            if (cursor != null && cursor.moveToFirst()) {
                val titleIdx = cursor.getColumnIndex(CalendarContract.Instances.TITLE)
                val allDayIdx = cursor.getColumnIndex(CalendarContract.Instances.ALL_DAY)

                while (!cursor.isAfterLast) {
                    val title = if (titleIdx >= 0) cursor.getString(titleIdx) ?: "" else ""
                    val isAllDay = if (allDayIdx >= 0) cursor.getInt(allDayIdx) == 1 else false
                    val titleLower = title.lowercase()

                    for (kw in HOLIDAY_EVENT_KEYWORDS) {
                        if (titleLower.contains(kw) || isAllDay) {
                            return CalendarExemption.CalendarEvent(title.ifBlank { "Calendar Holiday" })
                        }
                    }
                    cursor.moveToNext()
                }
            }
            null
        } catch (_: Throwable) {
            null
        } finally {
            try {
                cursor?.close()
            } catch (_: Throwable) {}
        }
    }

    fun isHolidayToday(context: Context): Boolean {
        return getCalendarExemption(context) !is CalendarExemption.None
    }

    fun isHoliday(context: Context, dateKey: String): Boolean {
        if (dateKey == todayKey()) {
            return isHolidayToday(context)
        }
        return isExplicitHolidayInDatabase(context, dateKey)
    }

    fun getCalendarExemptionDetails(context: Context): String? {
        return when (val ex = getCalendarExemption(context)) {
            is CalendarExemption.None -> null
            is CalendarExemption.Holiday -> "🌴 ${ex.reason}"
            is CalendarExemption.CalendarEvent -> "🗓️ Active Event: ${ex.title}"
            is CalendarExemption.AdaptiveBreak -> "☕ 15-Minute Focus Break (${ex.remainingMinutes}m left)"
        }
    }

    // =========================================================================
    // FOCUS LOCK & SCHEDULE ENFORCEMENT
    // =========================================================================

    fun getFocusStatusDescription(context: Context, nowMs: Long = System.currentTimeMillis()): String {
        val exemption = getCalendarExemption(context, nowMs)
        if (exemption !is CalendarExemption.None) {
            val exText = getCalendarExemptionDetails(context) ?: "Exemption Active"
            return "$exText • Focus Paused"
        }
        if (!isScheduleEnabled(context)) {
            return "Flexible Mode (Schedule Disabled)"
        }
        val scheduled = isScheduledActiveNow(context, nowMs)
        val window = formatWindowSummary(context)
        return if (scheduled) {
            "🟢 Focus Layer Active (Inside $window)"
        } else {
            "⚪ Inactive (Outside $window)"
        }
    }

    /**
     * Checks whether the Focus Layer is currently strictly locked.
     * By default, strict locking is OFF so users and presenters can customize their hours freely.
     * When strict lock is enabled, rules can be relaxed via a 15-minute break or Calendar Holiday.
     */
    fun isLockedUntilCalendarHoliday(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!isStrictLockEnabled(context)) {
            return false
        }
        if (isHolidayToday(context)) {
            return false
        }
        return isScheduledActiveNow(context, nowMs)
    }

    fun canModifyFocusSettingsNow(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        return !isLockedUntilCalendarHoliday(context, nowMs)
    }

    fun updateFocusScheduleCriteria(
        context: Context,
        scheduleEnabled: Boolean,
        startMinutes: Int,
        endMinutes: Int,
        daysMode: Int
    ): Boolean {
        prefs(context).edit()
            .putBoolean(KEY_SCHEDULE_ENABLED, scheduleEnabled)
            .putInt(KEY_START_MINUTES, startMinutes.coerceIn(0, 1439))
            .putInt(KEY_END_MINUTES, endMinutes.coerceIn(0, 1439))
            .putInt(KEY_DAYS_MODE, daysMode)
            .apply()
        return true
    }

    fun isActiveNow(context: Context): Boolean {
        val nowMs = System.currentTimeMillis()
        if (isHolidayToday(context)) {
            val manual = getManualOverride(context)
            return manual == true
        }
        if (!isDayIncludedInSchedule(context, nowMs)) {
            val manual = getManualOverride(context)
            return manual == true
        }
        if (isScheduledActiveNow(context, nowMs)) {
            return true
        }
        val manual = getManualOverride(context)
        return manual == true
    }

    fun isScheduledActiveNow(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        val shieldPrefs = context.getSharedPreferences("FocusGuard_ITR", Context.MODE_PRIVATE)
        if (!shieldPrefs.getBoolean("shield_active", true)) {
            return false
        }
        if (!isScheduleEnabled(context)) {
            return false
        }
        if (!isDayIncludedInSchedule(context, nowMs)) {
            return false
        }
        // User-adaptive: Active calendar event or holiday relaxes strict scheduled blocking
        if (isHolidayToday(context)) {
            return false
        }
        val cal = Calendar.getInstance()
        cal.timeInMillis = nowMs
        val nowMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val startMinutes = getStartMinutes(context)
        val endMinutes = getEndMinutes(context)
        if (startMinutes == endMinutes) return false

        return if (startMinutes < endMinutes) {
            nowMinutes in startMinutes until endMinutes
        } else {
            nowMinutes >= startMinutes || nowMinutes < endMinutes
        }
    }

    private fun getManualOverride(context: Context): Boolean? {
        return try {
            FocusGuardApp.ensureRoomMainThreadQueries(context)
            val raw = AppDatabase.getDatabase(context).systemDao().getState(OVERRIDE_KEY) ?: return null
            val parts = raw.split(":")
            if (parts.size != 2 || parts[1] != todayKey()) {
                return null
            }
            parts[0] == "ON"
        } catch (_: Throwable) {
            null
        }
    }

    fun isManualOverrideActive(context: Context): Boolean {
        return getManualOverride(context) != null
    }

    fun clearManualOverride(context: Context) {
        try {
            FocusGuardApp.ensureRoomMainThreadQueries(context)
            AppDatabase.getDatabase(context).systemDao().putState(
                SystemState(OVERRIDE_KEY, "", System.currentTimeMillis())
            )
        } catch (_: Throwable) {}
    }

    fun toggleManualOverride(context: Context): Boolean {
        val currentlyActive = isActiveNow(context)
        if (currentlyActive && isLockedUntilCalendarHoliday(context)) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    context.applicationContext,
                    "🔒 Focus Layer is locked during active hours! Mark today as a Holiday or Event in Calendar first to turn off Focus.",
                    Toast.LENGTH_LONG
                ).show()
            }
            return true
        }

        val newState = !currentlyActive
        try {
            FocusGuardApp.ensureRoomMainThreadQueries(context)
            AppDatabase.getDatabase(context).systemDao().putState(
                SystemState(OVERRIDE_KEY, "${if (newState) "ON" else "OFF"}:${todayKey()}", System.currentTimeMillis())
            )
        } catch (_: Throwable) {}

        if (newState) {
            FocusForegroundMonitorService.start(context)
        }
        return newState
    }

    fun isAllowedDuringFocus(context: Context, packageName: String): Boolean {
        if (SystemExclusions.isExcludedPackage(context, packageName)) {
            return true
        }
        if (AppCategoryPrefs.getStudyApps(context).contains(packageName) ||
            AppCategoryPrefs.getImprovementApps(context).contains(packageName)
        ) {
            return true
        }
        if (AppCategoryPrefs.getIgnoredApps(context).contains(packageName)) {
            val usedMinutes = UsageStatsHelper.getTodayScreenTimeForPackage(context, packageName) / 60000
            return usedMinutes < 30
        }
        return false
    }
}
