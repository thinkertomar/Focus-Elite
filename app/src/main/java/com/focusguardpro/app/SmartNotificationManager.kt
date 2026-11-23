package com.focusguardpro.app

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.focusguardpro.app.db.AppDatabase
import com.focusguardpro.app.insights.InsightsDashboardActivity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Adaptive All-Day Smart Notification Engine.
 *
 * Automatically evolves throughout the day based on time of day + live on-device metrics:
 * - Morning (before Focus): Prompts to set Today's Goal & To-Do List in Diary, or displays your plan.
 * - Focus Hours (mid-day): Live tracker of Study time, To-Do progress, Steps & Focus momentum.
 * - Evening (after Focus): Progress check-in across Study, Physical Exercise, Green Discipline Score & Break Browser budget.
 * - Night (9 PM+): Complete Whole-Day Summary (Study, Physical, Tasks, Discipline Rating & Reflection prompt).
 * - Always keeps 1-tap "💳 Pay Safe" (or "🛡️ Resume Shield") right in the notification shade!
 */
object SmartNotificationManager {

    private const val PREFS_NAME = "FocusGuard_SmartNotification"
    private const val KEY_SMART_NOTIF_ENABLED = "smart_notif_enabled"
    private const val KEY_PROMPTED_ONCE = "notif_perm_prompted_once"

    const val REQUEST_CODE_POST_NOTIFICATIONS = 4033

    private const val STUDY_TARGET_MINUTES = 360 // 6h target matches Discipline Insights

    enum class DayPhase {
        PAY_SAFE,
        MORNING,
        FOCUS_ACTIVE,
        EVENING,
        NIGHT_SUMMARY
    }

    data class LiveSmartSnapshot(
        val phase: DayPhase,
        val title: String,
        val shortSummary: String,
        val expandedBody: String,
        val studyMinutes: Int,
        val distractMinutes: Int,
        val steps: Int,
        val stepGoal: Int,
        val workoutSets: Int,
        val todoDone: Int,
        val todoTotal: Int,
        val disciplineScore: Int
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isSmartNotificationEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SMART_NOTIF_ENABLED, true)

    fun setSmartNotificationEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SMART_NOTIF_ENABLED, enabled).apply()
        refreshNow(context)
    }

    fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context.applicationContext,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Requests Android 13+ POST_NOTIFICATIONS runtime permission popup.
     * - When [onlyAfterCoreSetup] is true, waits until Usage Access, Device Admin, and Accessibility
     *   are configured (as requested) so the notification popup appears cleanly right after core setup.
     * - If the user previously denied permanently, [openSettingsIfDenied] routes directly to
     *   Android App Notification Settings with Tamper Guard grace unlocked.
     */
    fun requestNotificationPermissionIfNeeded(
        activity: Activity,
        onlyAfterCoreSetup: Boolean = false,
        openSettingsIfDenied: Boolean = false
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        if (hasNotificationPermission(activity)) return true

        if (onlyAfterCoreSetup && !SettingsTamperGuard.areAllCoreSetupPermissionsGranted(activity)) {
            return false
        }

        val p = prefs(activity)
        val alreadyPrompted = p.getBoolean(KEY_PROMPTED_ONCE, false)

        if (!alreadyPrompted || ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)) {
            p.edit().putBoolean(KEY_PROMPTED_ONCE, true).apply()
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_CODE_POST_NOTIFICATIONS
            )
            return false
        }

        if (openSettingsIfDenied) {
            openAppNotificationSettings(activity)
        }
        return false
    }

    fun openAppNotificationSettings(context: Context) {
        SettingsTamperGuard.grantPermissionSetupGrace(context, 180_000L)
        try {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Throwable) {
            try {
                val fallback = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}")
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                context.startActivity(fallback)
            } catch (_: Throwable) {}
        }
    }

    /**
     * Immediately pushes an updated Smart Notification to the notification shade.
     */
    fun refreshNow(context: Context) {
        if (!hasNotificationPermission(context)) return
        try {
            ensureChannel(context)
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            nm.notify(FocusForegroundMonitorService.NOTIFICATION_ID, buildSmartNotification(context))
        } catch (_: Throwable) {}
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channel = NotificationChannel(
                FocusForegroundMonitorService.CHANNEL_ID,
                "Focus Elite Smart Notification",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Live all-day Smart Notification: Goals, To-Do List, Study & Physical progress, Evening Check-In, Night Summary & 1-Tap Pay Safe"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    fun computeLiveSnapshot(context: Context): LiveSmartSnapshot {
        val appCtx = context.applicationContext
        val nowMs = FocusLayerManager.verifiedNowMs(appCtx)
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val nowMins = hour * 60 + cal.get(Calendar.MINUTE)
        val todayKey = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(nowMs))

        val paySafeActive = PaymentSafeModeManager.isActive(appCtx)
        val a11yEnabled = SettingsTamperGuard.isAccessibilityAlreadyEnabled(appCtx)
        val isFocusNow = FocusLayerManager.isScheduledActiveNow(appCtx, nowMs) ||
            (FocusLayerManager.isManualOverrideActive(appCtx) && FocusLayerManager.isActiveNow(appCtx)) ||
            FocusSessionManager.snapshot.value.state == FocusSessionManager.SessionState.RUNNING
        val focusStartMins = FocusLayerManager.getStartMinutes(appCtx)
        val focusEndMins = FocusLayerManager.getEndMinutes(appCtx)
        val focusStartFmt = FocusLayerManager.formatMinutesOfDay(focusStartMins)
        val focusEndFmt = FocusLayerManager.formatMinutesOfDay(focusEndMins)

        // 1. Read today's To-Do list & Diary Goal from Room
        var goalText = ""
        var todosTotal = 0
        var todosDone = 0
        val pendingTodoTexts = ArrayList<String>(3)
        try {
            FocusGuardApp.ensureRoomMainThreadQueries(appCtx)
            val dao = AppDatabase.getDatabase(appCtx).systemDao()
            val diary = dao.getDiaryEntry(todayKey)
            goalText = diary?.note?.trim().orEmpty()
            val todos = dao.getTodosForDate(todayKey)
            todosTotal = todos.size
            todosDone = todos.count { it.isDone }
            todos.filterNot { it.isDone }.take(3).forEach { item ->
                val clean = item.text.trim()
                if (clean.isNotEmpty()) pendingTodoTexts.add(clean)
            }
        } catch (_: Throwable) {}

        // 2. Read today's Study & Distraction minutes
        var studyMins = 0
        var distractMins = 0
        var hasCategorySplit = false
        try {
            val snap = UsageStatsHelper.getCachedTodaySnapshot(appCtx)
                ?: UsageStatsHelper.getTodayWellbeingSnapshot(appCtx)
            if (snap != null) {
                studyMins = ((snap.studyMs + snap.improveMs) / 60_000L).toInt().coerceAtLeast(0)
                distractMins = (snap.distractMs / 60_000L).toInt().coerceAtLeast(0)
                hasCategorySplit = (snap.studyMs + snap.improveMs + snap.distractMs + snap.utilityMs) > 0L
            }
        } catch (_: Throwable) {}

        // 3. Read today's Steps, Goal & Calisthenics Workout sets
        val stepGoal = try {
            GoalPrefs.getStepGoal(appCtx).coerceAtLeast(1000)
        } catch (_: Throwable) {
            6000
        }
        val steps = try {
            StepPrefs.getDailySteps(appCtx).coerceAtLeast(0)
        } catch (_: Throwable) {
            0
        }
        var workoutSets = 0
        try {
            FocusGuardApp.ensureRoomMainThreadQueries(appCtx)
            val allLogs = AppDatabase.getDatabase(appCtx).exerciseDao().getAllLogs()
            val fmt = SimpleDateFormat("yyyyMMdd", Locale.US)
            workoutSets = allLogs.count { fmt.format(Date(it.timestamp)) == todayKey }
        } catch (_: Throwable) {}

        // 4. Compute Live Green Discipline Score (0–100) aligned with InsightsDashboardActivity
        val studyPts = if (hasCategorySplit) {
            ((studyMins.toFloat() / STUDY_TARGET_MINUTES.toFloat()) * 26f).roundToInt().coerceIn(0, 26)
        } else {
            14
        }
        val distractPts = when {
            distractMins <= 30 -> 14
            distractMins <= 60 -> 11
            distractMins <= 90 -> 8
            distractMins <= 120 -> 5
            else -> 2
        }
        val stepPts = ((steps.toFloat() / stepGoal.toFloat()) * 35f).roundToInt().coerceIn(0, 35)
        val workoutPts = when {
            workoutSets >= 6 -> 25
            workoutSets >= 3 -> 18
            workoutSets >= 1 -> 12
            else -> 0
        }
        val disciplineScore = (studyPts + distractPts + stepPts + workoutPts).coerceIn(0, 100)
        val disciplineBadge = when {
            disciplineScore >= 80 -> "🟢 Elite Green"
            disciplineScore >= 60 -> "🟢 Solid Green"
            disciplineScore >= 40 -> "🟡 Building"
            else -> "⚪ Warm-Up"
        }

        val studyFmt = formatMins(studyMins)
        val distractFmt = formatMins(distractMins)
        val stepsFmt = String.format(Locale.US, "%,d", steps)
        val stepPct = ((steps.toFloat() / stepGoal.toFloat()) * 100f).roundToInt().coerceIn(0, 999)
        val distKm = String.format(Locale.US, "%.2f", (steps * 0.00075))
        val workoutStr = if (workoutSets > 0) " • 🤸 $workoutSets sets" else ""
        val browserWin = BlockPolicyEngine.getBrowserAccessWindowStatus(appCtx, nowMs)

        // Determine active phase automatically from clock & Focus state
        val phase = when {
            paySafeActive -> DayPhase.PAY_SAFE
            hour >= 21 || hour < 5 -> DayPhase.NIGHT_SUMMARY
            isFocusNow -> DayPhase.FOCUS_ACTIVE
            FocusLayerManager.isScheduleEnabled(appCtx) &&
                focusStartMins < focusEndMins &&
                nowMins < focusStartMins -> DayPhase.MORNING
            hour < 11 -> DayPhase.MORNING
            else -> DayPhase.EVENING
        }

        val goalLine = if (goalText.isNotBlank()) {
            "🎯 Goal: ${goalText.lines().first().take(72)}"
        } else {
            "🎯 Goal: Tap 'Today's Plan' to write your main goal"
        }

        val taskBlock = if (todosTotal == 0) {
            "📝 To-Do: No tasks yet — add 2–3 key priorities"
        } else {
            val nextLines = if (pendingTodoTexts.isNotEmpty()) {
                "\n   • Next: " + pendingTodoTexts.joinToString("  |  ") { it.take(28) }
            } else {
                " (All completed! 🎉)"
            }
            "📝 To-Do: $todosDone/$todosTotal done$nextLines"
        }

        return when (phase) {
            DayPhase.PAY_SAFE -> LiveSmartSnapshot(
                phase = phase,
                title = "💳 Pay Safe Active · Open BHIM / Bank App",
                shortSummary = "Accessibility paused for payment • Auto-resumes when you exit",
                expandedBody = "💳 Pay Safe Mode is active — Accessibility is paused so BHIM, YONO SBI & banking apps work with zero Security Alert.\n" +
                    "🛡️ Tap 'Resume Shield' when finished (or exit your payment app for automatic restore).",
                studyMinutes = studyMins,
                distractMinutes = distractMins,
                steps = steps,
                stepGoal = stepGoal,
                workoutSets = workoutSets,
                todoDone = todosDone,
                todoTotal = todosTotal,
                disciplineScore = disciplineScore
            )

            DayPhase.MORNING -> {
                val title = if (todosTotal == 0 && goalText.isBlank()) {
                    "🌅 Morning Setup · Add Today's Goal & To-Do"
                } else {
                    "🌅 Morning Plan · $todosDone/$todosTotal Tasks • Focus at $focusStartFmt"
                }
                val shortSummary = if (todosTotal == 0 && goalText.isBlank()) {
                    "Set your diary goal & to-do list before Focus starts at $focusStartFmt"
                } else {
                    val top = pendingTodoTexts.firstOrNull()?.let { "Next: ${it.take(36)}" }
                        ?: goalText.lines().firstOrNull()?.take(42)
                        ?: "$todosDone/$todosTotal tasks done"
                    "$top • 🚶 $stepsFmt steps"
                }
                val expanded = buildString {
                    appendLine(goalLine)
                    appendLine(taskBlock)
                    appendLine("🏃 Morning Move: $stepsFmt / ${String.format(Locale.US, "%,d", stepGoal)} steps ($stepPct%)$workoutStr")
                    append("⏰ Focus Window: $focusStartFmt – $focusEndFmt · Start strong!")
                }
                LiveSmartSnapshot(phase, title, shortSummary, expanded, studyMins, distractMins, steps, stepGoal, workoutSets, todosDone, todosTotal, disciplineScore)
            }

            DayPhase.FOCUS_ACTIVE -> {
                val title = "🎯 Focus Active · 📚 $studyFmt Study • ✅ $todosDone/$todosTotal Tasks"
                val shortSummary = if (pendingTodoTexts.isNotEmpty()) {
                    "Up next: ${pendingTodoTexts.first().take(34)} • 🚶 $stepsFmt steps"
                } else {
                    "📚 $studyFmt study • 🚶 $stepsFmt steps ($stepPct%) • $disciplineBadge"
                }
                val nudge = when {
                    hour >= 13 && steps < 2000 -> "💡 Quick 5-min walk break will recharge focus & boost physical score."
                    studyMins >= 240 -> "🔥 Strong deep-work streak! Stay locked in until $focusEndFmt."
                    distractMins > 30 -> "🛡️ Guard your focus — distractions at $distractFmt today."
                    else -> "⚡ Keep executing your top tasks until Focus ends at $focusEndFmt."
                }
                val expanded = buildString {
                    if (goalText.isNotBlank()) appendLine(goalLine)
                    appendLine(taskBlock)
                    appendLine("📚 Study: $studyFmt / 6h target  •  🛡️ Distract: $distractFmt")
                    appendLine("🚶 Physical: $stepsFmt steps ($stepPct%)$workoutStr  •  $disciplineBadge ($disciplineScore)")
                    append(nudge)
                }
                LiveSmartSnapshot(phase, title, shortSummary, expanded, studyMins, distractMins, steps, stepGoal, workoutSets, todosDone, todosTotal, disciplineScore)
            }

            DayPhase.EVENING -> {
                val title = "🌆 Evening Progress · $disciplineBadge ($disciplineScore/100)"
                val shortSummary = "📚 $studyFmt Study • 🏃 $stepsFmt steps$workoutStr • ✅ $todosDone/$todosTotal"
                val eveningTip = when {
                    workoutSets == 0 && steps < stepGoal -> "💪 Great time for an evening walk or quick Calisthenics session!"
                    todosTotal > todosDone -> "📌 ${todosTotal - todosDone} task(s) left to complete today's checklist."
                    else -> "✨ Awesome green discipline today — enjoy your guilt-free break!"
                }
                val browserLine = if (browserWin.isEnabled) {
                    "🌤️ Rest-of-Day Browser: ${browserWin.remainingMinutes}m / ${browserWin.durationMinutes}m left"
                } else {
                    "🛡️ Study Sites 24/7 Active"
                }
                val expanded = buildString {
                    appendLine("🟢 Discipline Score: $disciplineScore/100 ($disciplineBadge)")
                    appendLine("📚 Study & Improve: $studyFmt  •  🛡️ Distractions: $distractFmt")
                    appendLine("🏃 Physical: $stepsFmt / ${String.format(Locale.US, "%,d", stepGoal)} steps ($distKm km)$workoutStr")
                    appendLine(taskBlock)
                    appendLine(browserLine)
                    append(eveningTip)
                }
                LiveSmartSnapshot(phase, title, shortSummary, expanded, studyMins, distractMins, steps, stepGoal, workoutSets, todosDone, todosTotal, disciplineScore)
            }

            DayPhase.NIGHT_SUMMARY -> {
                val title = "🌙 Whole-Day Summary · $disciplineScore/100 ($disciplineBadge)"
                val shortSummary = "📚 $studyFmt Study • 🏃 $stepsFmt steps$workoutStr • ✅ $todosDone/$todosTotal Tasks"
                val verdict = when {
                    disciplineScore >= 80 -> "Outstanding disciplined day! Rest well and repeat tomorrow."
                    disciplineScore >= 60 -> "Solid green progress today! Set tomorrow's goal before sleep."
                    else -> "Every day is a fresh reset — log today's reflection & plan tomorrow."
                }
                val expanded = buildString {
                    appendLine("🏆 Day Rating: $disciplineScore/100 — $disciplineBadge")
                    appendLine("📚 Study & Improve: $studyFmt  |  🛡️ Distractions: $distractFmt")
                    appendLine("🏃 Physical: $stepsFmt steps ($distKm km) • ${if (workoutSets > 0) "$workoutSets workout sets" else "0 workout sets"}")
                    appendLine("✅ Tasks Done: $todosDone / $todosTotal${if (goalText.isNotBlank()) "  |  🎯 ${goalText.lines().first().take(36)}" else ""}")
                    append("💭 $verdict")
                }
                LiveSmartSnapshot(phase, title, shortSummary, expanded, studyMins, distractMins, steps, stepGoal, workoutSets, todosDone, todosTotal, disciplineScore)
            }
        }
    }

    /**
     * Builds the unified persistent Smart Notification used by FocusForegroundMonitorService.
     * Always includes the 1-tap Pay Safe / Resume Shield action + phase-contextual quick action.
     */
    fun buildSmartNotification(context: Context): Notification {
        val appCtx = context.applicationContext
        ensureChannel(appCtx)

        val paySafeActive = PaymentSafeModeManager.isActive(appCtx)
        val a11yEnabled = SettingsTamperGuard.isAccessibilityAlreadyEnabled(appCtx)
        val smartEnabled = isSmartNotificationEnabled(appCtx)

        val todayKey = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(FocusLayerManager.verifiedNowMs(appCtx)))

        // Primary tap intent: MainActivity (or Today's Plan during Morning if no tasks)
        val mainIntent = Intent(appCtx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val mainPi = PendingIntent.getActivity(
            appCtx, 0, mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action 1 (ALWAYS PRESENT): 1-Tap Pay Safe / Resume Shield
        val payActionIntent = Intent(appCtx, PaymentSafeActionReceiver::class.java).apply {
            action = if (paySafeActive || !a11yEnabled) {
                PaymentSafeActionReceiver.ACTION_RESUME_SHIELD
            } else {
                PaymentSafeActionReceiver.ACTION_ENTER_PAY_SAFE
            }
        }
        val payActionPi = PendingIntent.getBroadcast(
            appCtx,
            if (paySafeActive || !a11yEnabled) 2091 else 2090,
            payActionIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val payActionLabel = if (paySafeActive || !a11yEnabled) "🛡️ Resume Shield" else "💳 Pay Safe"

        if (!smartEnabled && !paySafeActive) {
            val summary = FocusLayerManager.formatWindowSummary(appCtx)
            val isFocus = FocusLayerManager.isActiveNow(appCtx) ||
                FocusSessionManager.snapshot.value.state == FocusSessionManager.SessionState.RUNNING
            val text = if (isFocus) "Focus Mode Active ($summary) · Distractions blocked" else "Focus Shield Monitoring · Window: $summary"
            return NotificationCompat.Builder(appCtx, FocusForegroundMonitorService.CHANNEL_ID)
                .setContentTitle("Focus Elite Shield")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_shield_security)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(mainPi)
                .addAction(0, payActionLabel, payActionPi)
                .build()
        }

        val snap = computeLiveSnapshot(appCtx)

        // Action 2: Today's Tasks or Night Journal (direct jump to today's date!)
        val todoIntent = if (snap.phase == DayPhase.NIGHT_SUMMARY) {
            Intent(appCtx, EliteJournalActivity::class.java).apply {
                putExtra(EliteJournalActivity.EXTRA_DATE_KEY, todayKey)
                putExtra(EliteJournalActivity.EXTRA_INITIAL_MODE, EliteJournalActivity.MODE_WRITE)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        } else {
            Intent(appCtx, EliteTasksActivity::class.java).apply {
                putExtra(EliteTasksActivity.EXTRA_DATE_KEY, todayKey)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        }
        val todoPi = PendingIntent.getActivity(
            appCtx, 2092, todoIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val todoLabel = when (snap.phase) {
            DayPhase.MORNING -> "📝 Add Goal/To-Do"
            DayPhase.NIGHT_SUMMARY -> "📓 Diary & Plan"
            else -> "📝 To-Do (${snap.todoDone}/${snap.todoTotal})"
        }

        // Action 3: Phase-smart action (Fitness in Focus/Morning, Insights in Evening/Night)
        val thirdIntent = when (snap.phase) {
            DayPhase.EVENING, DayPhase.NIGHT_SUMMARY -> Intent(appCtx, InsightsDashboardActivity::class.java)
            else -> Intent(appCtx, ActivityStatsActivity::class.java)
        }.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val thirdPi = PendingIntent.getActivity(
            appCtx, 2093, thirdIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val thirdLabel = when (snap.phase) {
            DayPhase.EVENING -> "📊 Progress"
            DayPhase.NIGHT_SUMMARY -> "📊 Summary"
            else -> "🏃 Fitness"
        }

        return NotificationCompat.Builder(appCtx, FocusForegroundMonitorService.CHANNEL_ID)
            .setContentTitle(snap.title)
            .setContentText(snap.shortSummary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(snap.expandedBody))
            .setSmallIcon(R.drawable.ic_shield_security)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(mainPi)
            .addAction(0, payActionLabel, payActionPi)
            .addAction(0, todoLabel, todoPi)
            .addAction(0, thirdLabel, thirdPi)
            .build()
    }

    private fun formatMins(mins: Int): String {
        val m = mins.coerceAtLeast(0)
        val h = m / 60
        val rem = m % 60
        return when {
            h > 0 && rem > 0 -> "${h}h ${rem}m"
            h > 0 -> "${h}h"
            else -> "${rem}m"
        }
    }
}
