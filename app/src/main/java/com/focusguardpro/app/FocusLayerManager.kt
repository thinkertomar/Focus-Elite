package com.focusguardpro.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.focusguardpro.app.db.AppDatabase
import com.focusguardpro.app.db.SystemState
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object FocusLayerManager {
    const val DEFAULT_START_MINUTES = 540 // 09:00 AM
    const val DEFAULT_END_MINUTES = 1080  // 06:00 PM
    const val PRE_WINDOW_GRACE_MINUTES = 30 // 30-minute pre-window edit grace period

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
    private const val KEY_HOLIDAY_FIRST_SEEN_PREFIX = "holiday_first_seen_"
    private const val OVERRIDE_KEY = "FOCUS_MANUAL_OVERRIDE"

    private const val TWENTY_FOUR_HOURS_MS = 24L * 60L * 60L * 1000L

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

    fun verifiedNowMs(context: Context? = null): Long {
        return if (context != null) {
            SettingsTamperGuard.getVerifiedCurrentTimeMillis(context)
        } else {
            System.currentTimeMillis()
        }
    }

    fun todayKey(context: Context? = null): String {
        return SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(verifiedNowMs(context)))
    }

    fun isSunday(nowMs: Long = System.currentTimeMillis()): Boolean {
        val cal = Calendar.getInstance()
        cal.timeInMillis = nowMs
        return cal.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY
    }

    /**
     * Strict Fortress Lock is ON by default so there is no unguarded escape during focus hours.
     */
    fun isStrictLockEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_STRICT_LOCK_ENABLED, true)
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
        return setOf(
            Calendar.MONDAY,
            Calendar.TUESDAY,
            Calendar.WEDNESDAY,
            Calendar.THURSDAY,
            Calendar.FRIDAY,
            Calendar.SATURDAY
        )
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

    fun isDayIncludedInSchedule(context: Context, nowMs: Long = verifiedNowMs(context)): Boolean {
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
    // 30-MINUTE PRE-WINDOW EDIT GRACE PERIOD
    // =========================================================================

    /**
     * Returns how many minutes remain in the 30-minute Pre-Window Edit Grace Period
     * right before today's scheduled Focus Layer starts (or 0 if not in the grace period).
     */
    fun getPreWindowGraceRemainingMinutes(
        context: Context,
        nowMs: Long = verifiedNowMs(context)
    ): Int {
        if (!isScheduleEnabled(context) || !isDayIncludedInSchedule(context, nowMs)) {
            return 0
        }
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        val nowMins = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val startMins = getStartMinutes(context)
        val diff = if (startMins >= nowMins) {
            startMins - nowMins
        } else {
            (1440 - nowMins) + startMins
        }
        return if (diff in 1..PRE_WINDOW_GRACE_MINUTES) diff else 0
    }

    fun isInPreWindowGracePeriod(
        context: Context,
        nowMs: Long = verifiedNowMs(context)
    ): Boolean {
        return getPreWindowGraceRemainingMinutes(context, nowMs) > 0
    }

    // =========================================================================
    // USER-ADAPTIVE BREAK & 24-HOUR ADVANCE CALENDAR HOLIDAY VALIDATION
    // =========================================================================

    fun grantAdaptiveBreak(context: Context, minutes: Int = 15) {
        val until = verifiedNowMs(context) + (minutes * 60_000L)
        prefs(context).edit().putLong(KEY_ADAPTIVE_BREAK_UNTIL_MS, until).apply()
    }

    fun cancelAdaptiveBreak(context: Context) {
        prefs(context).edit().putLong(KEY_ADAPTIVE_BREAK_UNTIL_MS, 0L).apply()
    }

    fun getAdaptiveBreakRemainingMinutes(context: Context): Int {
        val until = prefs(context).getLong(KEY_ADAPTIVE_BREAK_UNTIL_MS, 0L)
        val diff = until - verifiedNowMs(context)
        return if (diff > 0L) (diff / 60_000L).toInt().coerceAtLeast(1) else 0
    }

    fun isAdaptiveBreakActive(context: Context): Boolean {
        return getAdaptiveBreakRemainingMinutes(context) > 0
    }

    /**
     * Enforces the 24-Hour Advance Calendar Holiday Rule:
     * When a date has a holiday flag or keyword, records the first timestamp it was seen.
     * For today's date, returns true ONLY if the holiday was scheduled at least 24 hours in advance.
     */
    private fun verifyHolidayAdvanceEligibility(
        context: Context,
        dateKey: String,
        nowMs: Long = verifiedNowMs(context)
    ): Boolean {
        val key = "$KEY_HOLIDAY_FIRST_SEEN_PREFIX$dateKey"
        val p = prefs(context)
        var firstSeenMs = p.getLong(key, 0L)
        if (firstSeenMs <= 0L) {
            firstSeenMs = nowMs
            p.edit().putLong(key, firstSeenMs).apply()
        }

        // Future dates being pre-scheduled are always eligible if scheduled >=24h before that day's window
        val today = todayKey(context)
        if (dateKey != today) {
            return true
        }

        // For today: check if it was created at least 24 hours ago
        return (nowMs - firstSeenMs) >= TWENTY_FOUR_HOURS_MS
    }

    /**
     * Pre-scans future calendar entries (next 14 days) so any holiday planned in advance
     * gets its creation timestamp anchored immediately.
     */
    fun anchorUpcomingPlannedHolidays(context: Context) {
        try {
            FocusGuardApp.ensureRoomMainThreadQueries(context)
            val dao = AppDatabase.getDatabase(context).systemDao()
            val cal = Calendar.getInstance().apply { timeInMillis = verifiedNowMs(context) }
            val fmt = SimpleDateFormat("yyyyMMdd", Locale.US)
            val p = prefs(context)
            val now = verifiedNowMs(context)

            for (offset in 1..14) {
                cal.add(Calendar.DAY_OF_YEAR, 1)
                val dKey = fmt.format(cal.time)
                val entry = dao.getDiaryEntry(dKey)
                val todos = dao.getTodosForDate(dKey)
                val hasHoliday = (entry?.isHoliday == true) ||
                    (entry != null && HOLIDAY_EVENT_KEYWORDS.any { entry.note.lowercase().contains(it) }) ||
                    todos.any { t -> HOLIDAY_EVENT_KEYWORDS.any { t.text.lowercase().contains(it) } }

                val prefKey = "$KEY_HOLIDAY_FIRST_SEEN_PREFIX$dKey"
                if (hasHoliday) {
                    if (p.getLong(prefKey, 0L) <= 0L) {
                        p.edit().putLong(prefKey, now).apply()
                    }
                } else {
                    p.edit().remove(prefKey).apply()
                }
            }
        } catch (_: Throwable) {}
    }

    /**
     * Returns a non-null warning string if the user marked Today as a Holiday on the same day
     * (violating the 24-hour advance rule), so UI screens can explain why Focus Lock remains active.
     */
    fun getSameDayHolidayRejectionNotice(
        context: Context,
        nowMs: Long = verifiedNowMs(context)
    ): String? {
        val rawExemption = detectRawTodayCalendarEntry(context) ?: return null
        val tKey = todayKey(context)
        val eligible = verifyHolidayAdvanceEligibility(context, tKey, nowMs)
        return if (!eligible) {
            val firstSeen = prefs(context).getLong("$KEY_HOLIDAY_FIRST_SEEN_PREFIX$tKey", nowMs)
            val hoursRemaining = ((TWENTY_FOUR_HOURS_MS - (nowMs - firstSeen)) / 3600_000L).coerceIn(1L, 24L)
            "🛡️ 24h Advance Rule: '$rawExemption' was added today (${hoursRemaining}h cooldown left). Same-day holidays cannot unlock active Focus!"
        } else {
            null
        }
    }

    private fun detectRawTodayCalendarEntry(context: Context): String? {
        val tKey = todayKey(context)
        try {
            FocusGuardApp.ensureRoomMainThreadQueries(context)
            val entry = AppDatabase.getDatabase(context).systemDao().getDiaryEntry(tKey)
            if (entry != null) {
                if (entry.isHoliday) {
                    return entry.note.ifBlank { "Marked as Holiday" }
                }
                val noteLower = entry.note.lowercase()
                for (kw in HOLIDAY_EVENT_KEYWORDS) {
                    if (noteLower.contains(kw)) {
                        return entry.note
                    }
                }
            }
            val todos = AppDatabase.getDatabase(context).systemDao().getTodosForDate(tKey)
            for (t in todos) {
                val textLower = t.text.lowercase()
                for (kw in HOLIDAY_EVENT_KEYWORDS) {
                    if (textLower.contains(kw)) {
                        return t.text
                    }
                }
            }
        } catch (_: Throwable) {}
        return null
    }

    /**
     * Inspects active calendar events or holidays across:
     * 1. 15-minute user-initiated adaptive breaks (unlocked via Physical Proof-of-Effort)
     * 2. In-app Calendar DiaryEntry (must satisfy 24h advance rule)
     * 3. In-app Calendar TodoItem events (must satisfy 24h advance rule)
     * 4. Android Device Calendar (must satisfy 24h advance rule)
     */
    fun getCalendarExemption(
        context: Context,
        nowMs: Long = verifiedNowMs(context)
    ): CalendarExemption {
        anchorUpcomingPlannedHolidays(context)

        // 1. Check temporary adaptive break
        val breakMins = getAdaptiveBreakRemainingMinutes(context)
        if (breakMins > 0) {
            return CalendarExemption.AdaptiveBreak(breakMins)
        }

        val tKey = todayKey(context)

        if (!isAdaptiveCalendarSyncEnabled(context)) {
            if (isExplicitHolidayInDatabase(context, tKey)) {
                if (verifyHolidayAdvanceEligibility(context, tKey, nowMs)) {
                    return CalendarExemption.Holiday("Marked as Holiday in Calendar (24h+ Verified)")
                }
            } else {
                prefs(context).edit().remove("$KEY_HOLIDAY_FIRST_SEEN_PREFIX$tKey").apply()
            }
            return CalendarExemption.None
        }

        // 2. Check In-App Calendar Diary Entry for Today
        var foundTodayHolidayOrKeyword = false
        try {
            FocusGuardApp.ensureRoomMainThreadQueries(context)
            val entry = AppDatabase.getDatabase(context).systemDao().getDiaryEntry(tKey)
            if (entry != null) {
                if (entry.isHoliday) {
                    foundTodayHolidayOrKeyword = true
                    if (verifyHolidayAdvanceEligibility(context, tKey, nowMs)) {
                        val label = if (entry.note.isNotBlank()) entry.note else "Marked as Holiday in Calendar"
                        return CalendarExemption.Holiday(label)
                    }
                }
                val noteLower = entry.note.lowercase()
                for (kw in HOLIDAY_EVENT_KEYWORDS) {
                    if (noteLower.contains(kw)) {
                        foundTodayHolidayOrKeyword = true
                        if (verifyHolidayAdvanceEligibility(context, tKey, nowMs)) {
                            return CalendarExemption.Holiday(entry.note)
                        }
                    }
                }
            }
        } catch (_: Throwable) {}

        // 3. Check In-App Calendar TodoItems / Events for Today
        try {
            FocusGuardApp.ensureRoomMainThreadQueries(context)
            val todos = AppDatabase.getDatabase(context).systemDao().getTodosForDate(tKey)
            for (t in todos) {
                val textLower = t.text.lowercase()
                for (kw in HOLIDAY_EVENT_KEYWORDS) {
                    if (textLower.contains(kw)) {
                        foundTodayHolidayOrKeyword = true
                        if (verifyHolidayAdvanceEligibility(context, tKey, nowMs)) {
                            return CalendarExemption.CalendarEvent(t.text)
                        }
                    }
                }
            }
        } catch (_: Throwable) {}

        if (!foundTodayHolidayOrKeyword) {
            prefs(context).edit().remove("$KEY_HOLIDAY_FIRST_SEEN_PREFIX$tKey").apply()
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

    fun isHolidayToday(context: Context): Boolean {
        return getCalendarExemption(context) !is CalendarExemption.None
    }

    fun isHoliday(context: Context, dateKey: String): Boolean {
        if (dateKey == todayKey(context)) {
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

    fun getFocusStatusDescription(context: Context, nowMs: Long = verifiedNowMs(context)): String {
        val exemption = getCalendarExemption(context, nowMs)
        if (exemption !is CalendarExemption.None) {
            val exText = getCalendarExemptionDetails(context) ?: "Exemption Active"
            return "$exText • Focus Paused"
        }
        if (!isScheduleEnabled(context)) {
            return "Flexible Mode (Schedule Disabled)"
        }
        val graceMins = getPreWindowGraceRemainingMinutes(context, nowMs)
        if (graceMins > 0) {
            return "⚡ Pre-Window Grace Open (${graceMins}m left before Fortress Lock)"
        }
        val scheduled = isScheduledActiveNow(context, nowMs)
        val window = formatWindowSummary(context)
        return if (scheduled) {
            "🔒 Fortress Focus Active (Inside $window)"
        } else {
            "⚪ Inactive (Outside $window)"
        }
    }

    /**
     * Checks whether the Focus Layer is currently strictly locked.
     * Strict Fortress Lock is ON by default. During initial 4-step setup (before core permissions
     * and app categories are configured) or during the 30-minute Pre-Window Grace Period,
     * users can freely edit their rules. Once setup is complete and focus hours start,
     * rules and category promotions freeze unless unlocked via Physical Proof-of-Effort.
     */
    fun isLockedUntilCalendarHoliday(context: Context, nowMs: Long = verifiedNowMs(context)): Boolean {
        if (!SettingsTamperGuard.isInitialSetupComplete(context)) {
            return false
        }
        if (!isStrictLockEnabled(context)) {
            return false
        }
        if (isHolidayToday(context)) {
            return false
        }
        if (isInPreWindowGracePeriod(context, nowMs)) {
            return false
        }
        return isScheduledActiveNow(context, nowMs)
    }

    fun canModifyFocusSettingsNow(context: Context, nowMs: Long = verifiedNowMs(context)): Boolean {
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
        UiThemePrefs.markSetupScheduleConfigured(context)
        return true
    }

    fun isActiveNow(context: Context): Boolean {
        val nowMs = verifiedNowMs(context)
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

    fun isScheduledActiveNow(context: Context, nowMs: Long = verifiedNowMs(context)): Boolean {
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
        // User-adaptive: Verified 24h+ calendar holiday or Proof-of-Effort adaptive break relaxes blocking
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
            if (parts.size != 2 || parts[1] != todayKey(context)) {
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
                SystemState(OVERRIDE_KEY, "", verifiedNowMs(context))
            )
        } catch (_: Throwable) {}
    }

    fun toggleManualOverride(context: Context): Boolean {
        val currentlyActive = isActiveNow(context)
        if (currentlyActive && isLockedUntilCalendarHoliday(context)) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    context.applicationContext,
                    "🔒 Fortress Lock is active! Complete Physical Proof-of-Effort in Focus & Rules Hub to pause.",
                    Toast.LENGTH_LONG
                ).show()
            }
            return true
        }

        val newState = !currentlyActive
        try {
            FocusGuardApp.ensureRoomMainThreadQueries(context)
            AppDatabase.getDatabase(context).systemDao().putState(
                SystemState(OVERRIDE_KEY, "${if (newState) "ON" else "OFF"}:${todayKey(context)}", verifiedNowMs(context))
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
        if (HardBlockManager.isHardBlocked(context, packageName) && !HardBlockManager.isEmergencyActive(context, packageName)) {
            return false
        }
        if (AppCategoryPrefs.getStudyApps(context).contains(packageName) ||
            AppCategoryPrefs.getImprovementApps(context).contains(packageName)
        ) {
            val customLimit = TimeLimitManager.getLimitMinutes(context, packageName)
            if (customLimit > 0) {
                val usedMinutes = UsageStatsHelper.getTodayScreenTimeForPackage(context, packageName) / 60000
                return usedMinutes < customLimit
            }
            return true
        }
        if (AppCategoryPrefs.getIgnoredApps(context).contains(packageName)) {
            val customLimit = TimeLimitManager.getLimitMinutes(context, packageName)
            val cap = if (customLimit in 1..29) customLimit else 30
            val usedMinutes = UsageStatsHelper.getTodayScreenTimeForPackage(context, packageName) / 60000
            return usedMinutes < cap
        }
        return false
    }
}
