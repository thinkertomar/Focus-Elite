package com.focusguardpro.app

import android.app.Activity
import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SystemDiagnosticsAndUpdatesManager {

    private const val PREFS_NAME = "focus_elite_diagnostics_and_updates"
    private const val KEY_LAST_SEEN_VERSION_CODE = "last_seen_version_code"
    private const val KEY_DIAGNOSTIC_LOGS_JSON = "diagnostic_logs_json"
    private const val KEY_SAVED_FEEDBACK_JSON = "saved_feedback_json"
    private const val KEY_LAST_SCAN_TIMESTAMP = "last_scan_timestamp"

    const val CURRENT_VERSION_CODE = 5
    const val CURRENT_VERSION_NAME = "1.4"
    const val GITHUB_REPO_URL = "https://github.com/thinkertomar/Focus-Elite"
    const val GITHUB_RELEASES_URL = "https://github.com/thinkertomar/Focus-Elite/releases/latest"
    const val GITHUB_ACTIONS_URL = "https://github.com/thinkertomar/Focus-Elite/actions"

    private const val UPDATE_NOTIFICATION_CHANNEL_ID = "focus_elite_updates_channel"
    private const val UPDATE_NOTIFICATION_ID = 4105

    data class ReleaseNote(
        val versionName: String,
        val versionCode: Int,
        val dateLabel: String,
        val headline: String,
        val highlights: List<String>
    )

    data class DiagnosticLogEntry(
        val timestampMs: Long,
        val level: String, // "ERROR", "WARN", "INFO", "REPAIR"
        val title: String,
        val details: String
    )

    data class HealthCheckItem(
        val id: String,
        val title: String,
        val status: String, // "PASS", "WARN", "ACTION"
        val detail: String,
        val actionLabel: String? = null
    )

    data class FeedbackSubmission(
        val id: Long,
        val timestampMs: Long,
        val category: String,
        val subject: String,
        val body: String,
        val includeDiagnostics: Boolean,
        val formattedReport: String
    )

    val RELEASE_HISTORY: List<ReleaseNote> = listOf(
        ReleaseNote(
            versionName = "1.4",
            versionCode = 5,
            dateLabel = "Official Milestone • Build 5",
            headline = "Direct Daily Time Notebook, Ocean Cyan Default + White/Black Backgrounds & Selective Release Control",
            highlights = listOf(
                "📓 Direct Daily Time Notebook Launch: Tapping 'Daily Time Notebook' now opens your Daily Time Notebook directly, bypassing the 'Welcome Back!' popup.",
                "🌊 Ocean Cyan Default + Pure Black & Pure White Backgrounds: Ocean Cyan is now the default color palette, plus two new background modes — True AMOLED Black (#000000) and Clean Daylight White (#F8FAFC).",
                "🛡️ Hardened Real-Time App Blocker: Eliminated SystemUI status-bar interference and 10s UsageEvents blind spots across Hard Blocks, Focus Schedules, Deep Focus Studio, and Daily Caps.",
                "🎯 Selective Milestone Release Control: Separate private test builds (GitHub Actions tab) from official public releases (GitHub Main Page Releases) so users only receive the milestone updates you choose."
            )
        ),
        ReleaseNote(
            versionName = "1.3",
            versionCode = 4,
            dateLabel = "Previous Release • Build 4",
            headline = "What's New Showcase, Offline Self-Diagnostics & Privacy-Driven Feedback Studio",
            highlights = listOf(
                "🎉 In-App 'What's New' Presentation & Notification: Automatically detects newly installed versions and showcases all new features and upgrades.",
                "🌐 Zero-Internet GitHub Release Updater Link: Open Focus-Elite GitHub Releases via your system browser to download & install new APK versions in-place without losing data.",
                "🩺 7-Point System Health & Error Scanner: Detects service drops, permission gaps, database state, and clock integrity with 1-Tap Auto-Repair.",
                "💬 Privacy-Driven Feedback & Upgrade Studio: Write feature ideas, self-opinions, or bug reports with optional diagnostic snapshots."
            )
        ),
        ReleaseNote(
            versionName = "1.2",
            versionCode = 3,
            dateLabel = "Release • Build 3",
            headline = "Guided 4-Step Setup (Zero Self-Lockout), System Apps Safe & 100% Offline Audit",
            highlights = listOf(
                "✨ Guided 4-Step Setup Order: 1. Usage Access → 2. App Criteria → 3. Focus Schedule → 4. Admin & Accessibility Shield Last.",
                "🛡️ 3-Minute Setup Grace Window: Eliminates false-positive Settings blocks while enabling Accessibility or Device Admin.",
                "⚙️ Automatic System Apps Exclusion: Phone, SMS, Camera, Clock, Calculator, Contacts, Files, Settings, and UPI/Payments are allowed 24/7.",
                "🔒 100% Offline Privacy Seal: Verified zero Internet permission and zero GPS/location permissions."
            )
        )
    )

    @Volatile
    private var exceptionHandlerInstalled = false

    fun installGlobalExceptionLogger(context: Context) {
        if (exceptionHandlerInstalled) return
        synchronized(this) {
            if (exceptionHandlerInstalled) return
            val appCtx = context.applicationContext
            val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    val stackPreview = throwable.stackTrace
                        .take(5)
                        .joinToString(" -> ") { "${it.className.substringAfterLast('.')}:${it.lineNumber}" }
                    recordDiagnosticEvent(
                        context = appCtx,
                        level = "ERROR",
                        title = "Uncaught Exception (${throwable.javaClass.simpleName})",
                        details = "Thread: ${thread.name} | Msg: ${throwable.message ?: "N/A"} | Trace: $stackPreview"
                    )
                } catch (_: Throwable) {}
                previousHandler?.uncaughtException(thread, throwable)
            }
            exceptionHandlerInstalled = true
        }
    }

    fun hasUnseenVersionUpdate(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastSeen = prefs.getInt(KEY_LAST_SEEN_VERSION_CODE, 0)
        return lastSeen < CURRENT_VERSION_CODE
    }

    fun markCurrentVersionSeen(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_LAST_SEEN_VERSION_CODE, CURRENT_VERSION_CODE)
            .apply()
    }

    fun postUpdateNotificationIfNeeded(context: Context) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    UPDATE_NOTIFICATION_CHANNEL_ID,
                    "Focus Elite Updates & What's New",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Notifies you when Focus Elite is updated with new features."
                }
                nm.createNotificationChannel(channel)
            }

            val intent = Intent(context, AuxiliarySettingsActivity::class.java).apply {
                putExtra(AuxiliarySettingsActivity.EXTRA_INITIAL_TAB, AuxiliarySettingsActivity.TAB_UPDATES_HEALTH)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                4104,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val latest = RELEASE_HISTORY.first()
            val notif = NotificationCompat.Builder(context, UPDATE_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_settings_gear)
                .setContentTitle("🎉 Focus Elite v${latest.versionName} Installed!")
                .setContentText("Tap to see What's New, System Health Scanner & Feedback Studio")
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        "${latest.headline}\n" + latest.highlights.joinToString("\n") { "• $it" }
                    )
                )
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            nm.notify(UPDATE_NOTIFICATION_ID, notif)
        } catch (_: Throwable) {}
    }

    fun openGitHubReleasesInBrowser(context: Context) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_RELEASES_URL)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Throwable) {
            Toast.makeText(
                context,
                "Could not open browser. Visit: $GITHUB_RELEASES_URL",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun openGitHubRepoInBrowser(context: Context) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_REPO_URL)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Throwable) {
            Toast.makeText(
                context,
                "Could not open browser. Visit: $GITHUB_REPO_URL",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun recordDiagnosticEvent(context: Context, level: String, title: String, details: String) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_DIAGNOSTIC_LOGS_JSON, "[]") ?: "[]"
            val arr = JSONArray(raw)
            val newObj = JSONObject().apply {
                put("ts", System.currentTimeMillis())
                put("level", level)
                put("title", title)
                put("details", details)
            }
            val updated = JSONArray()
            updated.put(newObj)
            val maxItems = 25
            for (i in 0 until arr.length().coerceAtMost(maxItems - 1)) {
                updated.put(arr.getJSONObject(i))
            }
            prefs.edit().putString(KEY_DIAGNOSTIC_LOGS_JSON, updated.toString()).apply()
        } catch (_: Throwable) {}
    }

    fun getDiagnosticLogs(context: Context): List<DiagnosticLogEntry> {
        val list = mutableListOf<DiagnosticLogEntry>()
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_DIAGNOSTIC_LOGS_JSON, "[]") ?: "[]"
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    DiagnosticLogEntry(
                        timestampMs = obj.optLong("ts", System.currentTimeMillis()),
                        level = obj.optString("level", "INFO"),
                        title = obj.optString("title", "System Event"),
                        details = obj.optString("details", "")
                    )
                )
            }
        } catch (_: Throwable) {}
        return list
    }

    fun clearDiagnosticLogs(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DIAGNOSTIC_LOGS_JSON, "[]")
            .apply()
    }

    fun runSevenPointHealthScan(context: Context): List<HealthCheckItem> {
        val items = mutableListOf<HealthCheckItem>()

        // 1. Zero-Internet Privacy Seal
        val hasInternet = try {
            val pkgInfo = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            val perms = pkgInfo.requestedPermissions?.toList() ?: emptyList()
            perms.contains("android.permission.INTERNET")
        } catch (_: Throwable) {
            false
        }
        items.add(
            HealthCheckItem(
                id = "privacy",
                title = "1. Zero-Internet Privacy Seal",
                status = if (!hasInternet) "PASS" else "WARN",
                detail = if (!hasInternet) {
                    "Verified: android.permission.INTERNET is absent. 100% offline."
                } else {
                    "Internet permission detected in manifest."
                }
            )
        )

        // 2. Room SQLite Database Engine
        val dbOk = try {
            FocusGuardApp.ensureRoomMainThreadQueries(context)
            true
        } catch (t: Throwable) {
            recordDiagnosticEvent(context, "ERROR", "Database Check Failed", t.message ?: "Unknown")
            false
        }
        items.add(
            HealthCheckItem(
                id = "database",
                title = "2. Local SQLite Database Engine",
                status = if (dbOk) "PASS" else "WARN",
                detail = if (dbOk) {
                    "Healthy: On-device Room SQLite storage is open and responsive."
                } else {
                    "Database connection needed re-initialization."
                }
            )
        )

        // 3. Usage Access Sensor
        val usageGranted = hasUsageStatsPermission(context)
        items.add(
            HealthCheckItem(
                id = "usage",
                title = "3. Usage Access Telemetry Sensor",
                status = if (usageGranted) "PASS" else "ACTION",
                detail = if (usageGranted) {
                    "Granted: Screen time & 30m app limit calculations active."
                } else {
                    "Missing: Grant Usage Access so Focus Elite can measure app usage."
                },
                actionLabel = if (!usageGranted) "Grant Usage" else null
            )
        )

        // 4. App Criteria & Schedule Setup
        val studyCount = AppCategoryPrefs.getStudyApps(context).size
        val improveCount = AppCategoryPrefs.getImprovementApps(context).size
        val ignoredCount = AppCategoryPrefs.getIgnoredApps(context).size
        val criteriaTotal = studyCount + improveCount + ignoredCount
        val scheduleSummary = FocusLayerManager.formatWindowSummary(context)
        items.add(
            HealthCheckItem(
                id = "criteria",
                title = "4. App Criteria & Focus Schedule",
                status = if (criteriaTotal > 0 || UiThemePrefs.isSetupCategoriesConfigured(context)) "PASS" else "WARN",
                detail = "Configured ($studyCount Study, $improveCount Improve, $ignoredCount Ignored) • Window: $scheduleSummary • System Apps Safe.",
                actionLabel = "Edit Apps"
            )
        )

        // 5. Device Admin Anti-Uninstall Protection
        val adminActive = isDeviceAdminActive(context)
        items.add(
            HealthCheckItem(
                id = "admin",
                title = "5. Device Admin Anti-Uninstall Shield",
                status = if (adminActive) "PASS" else "ACTION",
                detail = if (adminActive) {
                    "Active: Focus Elite is protected against impulse uninstall."
                } else {
                    "Inactive: Enable Device Admin in Step 4 for full protection."
                },
                actionLabel = if (!adminActive) "Enable Admin" else null
            )
        )

        // 6. Accessibility Blocker Shield
        val accessibilityActive = isAccessibilityServiceEnabled(context)
        items.add(
            HealthCheckItem(
                id = "accessibility",
                title = "6. Accessibility Blocker Service",
                status = if (accessibilityActive) "PASS" else "ACTION",
                detail = if (accessibilityActive) {
                    "Active: Real-time app & browser distraction shield is running."
                } else {
                    "Inactive: Enable 'Focus Elite' in Accessibility Settings (Step 4)."
                },
                actionLabel = if (!accessibilityActive) "Enable Shield" else null
            )
        )

        // 7. Battery Optimization & Clock Tamper Integrity
        val batteryUnrestricted = isIgnoringBatteryOptimizations(context)
        val clockTampered = SettingsTamperGuard.isClockTamperActive(context)
        val status7 = when {
            clockTampered -> "WARN"
            batteryUnrestricted -> "PASS"
            else -> "WARN"
        }
        val detail7 = when {
            clockTampered -> "Warning: Backward device clock shift detected! Restore automatic network time."
            batteryUnrestricted -> "Healthy: Unrestricted battery granted & monotonic clock verified."
            else -> "Recommended: Allow Unrestricted Battery so your OEM never kills the blocker."
        }
        items.add(
            HealthCheckItem(
                id = "battery_clock",
                title = "7. Battery Keep-Alive & Clock Integrity",
                status = status7,
                detail = detail7,
                actionLabel = if (!batteryUnrestricted) "Fix Battery" else null
            )
        )

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_SCAN_TIMESTAMP, System.currentTimeMillis())
            .apply()

        return items
    }

    fun performAutoRepair(context: Context): String {
        val repairedSteps = mutableListOf<String>()
        try {
            FocusGuardApp.ensureRoomMainThreadQueries(context)
            repairedSteps.add("SQLite Room connection verified")
        } catch (_: Throwable) {}

        try {
            WatchdogService.start(context)
            FocusForegroundMonitorService.start(context)
            FocusMonitorWorker.schedule(context)
            repairedSteps.add("Watchdog & Foreground Monitor services restarted")
        } catch (_: Throwable) {}

        try {
            FocusWidgetService.syncTelemetryNow(context)
            repairedSteps.add("Home widget telemetry synchronized")
        } catch (_: Throwable) {}

        val summary = if (repairedSteps.isEmpty()) {
            "Scan complete."
        } else {
            repairedSteps.joinToString(" • ")
        }
        recordDiagnosticEvent(
            context = context,
            level = "REPAIR",
            title = "Auto-Repair & Service Rebind Executed",
            details = summary
        )
        return summary
    }

    fun saveAndFormatFeedbackReport(
        context: Context,
        category: String,
        subject: String,
        body: String,
        includeDiagnostics: Boolean
    ): FeedbackSubmission {
        val now = System.currentTimeMillis()
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val dateStr = sdf.format(Date(now))

        val sb = StringBuilder()
        sb.appendLine("### 🛡️ Focus Elite — User Feedback & Upgrade Report")
        sb.appendLine("- **Repository:** `Focus-Elite`")
        sb.appendLine("- **Version:** v$CURRENT_VERSION_NAME (Build $CURRENT_VERSION_CODE) • 100% Offline")
        sb.appendLine("- **Category:** $category")
        sb.appendLine("- **Subject:** ${subject.ifBlank { "General Update / Feedback" }}")
        sb.appendLine("- **Date:** $dateStr")
        sb.appendLine()
        sb.appendLine("#### 💬 User Details / Upgrade Request")
        sb.appendLine(body.trim().ifBlank { "(No additional text provided)" })

        if (includeDiagnostics) {
            sb.appendLine()
            sb.appendLine("#### 🩺 System Health & Diagnostic Snapshot")
            sb.appendLine("- **Device:** ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})")
            sb.appendLine("- **Focus Schedule:** ${FocusLayerManager.formatWindowSummary(context)}")
            val checks = runSevenPointHealthScan(context)
            for (c in checks) {
                sb.appendLine("- **[${c.status}] ${c.title}:** ${c.detail}")
            }
            val logs = getDiagnosticLogs(context).take(5)
            if (logs.isNotEmpty()) {
                sb.appendLine()
                sb.appendLine("#### 📋 Recent Diagnostic Log Entries")
                for (l in logs) {
                    val tStr = sdf.format(Date(l.timestampMs))
                    sb.appendLine("- `$tStr` **[${l.level}]** ${l.title} — ${l.details}")
                }
            }
        }

        val reportText = sb.toString().trim()
        val submission = FeedbackSubmission(
            id = now,
            timestampMs = now,
            category = category,
            subject = subject.ifBlank { "General Update / Feedback" },
            body = body.trim(),
            includeDiagnostics = includeDiagnostics,
            formattedReport = reportText
        )

        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_SAVED_FEEDBACK_JSON, "[]") ?: "[]"
            val arr = JSONArray(raw)
            val obj = JSONObject().apply {
                put("id", submission.id)
                put("ts", submission.timestampMs)
                put("category", submission.category)
                put("subject", submission.subject)
                put("body", submission.body)
                put("diag", submission.includeDiagnostics)
                put("report", submission.formattedReport)
            }
            val updated = JSONArray()
            updated.put(obj)
            for (i in 0 until arr.length().coerceAtMost(19)) {
                updated.put(arr.getJSONObject(i))
            }
            prefs.edit().putString(KEY_SAVED_FEEDBACK_JSON, updated.toString()).apply()
        } catch (_: Throwable) {}

        return submission
    }

    fun getSavedFeedbackList(context: Context): List<FeedbackSubmission> {
        val result = mutableListOf<FeedbackSubmission>()
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_SAVED_FEEDBACK_JSON, "[]") ?: "[]"
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                result.add(
                    FeedbackSubmission(
                        id = obj.optLong("id", 0L),
                        timestampMs = obj.optLong("ts", 0L),
                        category = obj.optString("category", "Feedback"),
                        subject = obj.optString("subject", "Feedback"),
                        body = obj.optString("body", ""),
                        includeDiagnostics = obj.optBoolean("diag", true),
                        formattedReport = obj.optString("report", "")
                    )
                )
            }
        } catch (_: Throwable) {}
        return result
    }

    fun copyReportToClipboard(context: Context, reportText: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("Focus Elite Feedback & Diagnostic Report", reportText))
        Toast.makeText(
            context,
            "📋 Report copied to clipboard! Paste it directly in chat or GitHub.",
            Toast.LENGTH_LONG
        ).show()
    }

    fun shareReportViaSystemChooser(context: Context, subject: String, reportText: String) {
        try {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Focus Elite Report: $subject")
                putExtra(Intent.EXTRA_TEXT, reportText)
            }
            context.startActivity(Intent.createChooser(sendIntent, "Share Focus Elite Report"))
        } catch (_: Throwable) {
            copyReportToClipboard(context, reportText)
        }
    }

    private fun hasUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expected = ComponentName(context, BlockerAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = android.text.TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            val component = ComponentName.unflattenFromString(splitter.next())
            if (component != null && component == expected) return true
        }
        return false
    }

    private fun isDeviceAdminActive(context: Context): Boolean {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(context, MyAdminReceiver::class.java)
            dpm.isAdminActive(admin)
        } catch (_: Throwable) {
            false
        }
    }

    private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }
}
