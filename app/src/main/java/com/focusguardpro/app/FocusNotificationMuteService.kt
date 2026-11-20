package com.focusguardpro.app

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 100% Offline Smart Focus Notification Mute Service.
 *
 * During active scheduled Focus Hours (respecting non-focus days & Calendar Holidays)
 * or active Deep Focus Studio sessions, cancels distracting app notifications while
 * always allowing calls, alarms, system utilities, OTP/verification codes, and
 * user-selected Study & Improve apps.
 *
 * Privacy guarantee: Notification titles/bodies are NEVER persisted or transmitted.
 * Only anonymous daily mute counters are kept locally in SharedPreferences.
 */
class FocusNotificationMuteService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        sweepActiveDistractingNotifications()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (instance === this) {
            instance = null
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (!isMutingRightNow(applicationContext)) return
        if (shouldMuteNotification(applicationContext, sbn)) {
            try {
                cancelNotification(sbn.key)
                recordMutedNotification(applicationContext, sbn.packageName)
            } catch (_: Throwable) {}
        }
    }

    fun sweepActiveDistractingNotifications() {
        if (!isMutingRightNow(applicationContext)) return
        try {
            val active = activeNotifications ?: return
            for (sbn in active) {
                if (shouldMuteNotification(applicationContext, sbn)) {
                    try {
                        cancelNotification(sbn.key)
                        recordMutedNotification(applicationContext, sbn.packageName)
                    } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}
    }

    companion object {
        private const val PREFS_NAME = "FocusElite_SmartMute"
        private const val KEY_ENABLED = "smart_mute_enabled"
        private const val KEY_MUTED_DAY = "muted_day_key"
        private const val KEY_MUTED_COUNT = "muted_count_today"

        @Volatile
        private var instance: FocusNotificationMuteService? = null

        private val ALLOWED_CATEGORIES = setOf(
            Notification.CATEGORY_CALL,
            Notification.CATEGORY_ALARM,
            Notification.CATEGORY_SYSTEM,
            Notification.CATEGORY_TRANSPORT,
            Notification.CATEGORY_SERVICE,
            Notification.CATEGORY_NAVIGATION,
            Notification.CATEGORY_PROGRESS,
            Notification.CATEGORY_STOPWATCH,
            Notification.CATEGORY_LOCATION_SHARING,
            Notification.CATEGORY_REMINDER
        )

        private val OTP_REGEX = Regex("\\b(otp|one[- ]?time|verification code|security code|passcode|2fa)\\b", RegexOption.IGNORE_CASE)

        fun isSmartMuteEnabled(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, true)
        }

        fun setSmartMuteEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ENABLED, enabled)
                .apply()
            if (enabled) {
                instance?.sweepActiveDistractingNotifications()
            }
        }

        fun isNotificationListenerEnabled(context: Context): Boolean {
            return try {
                val flat = Settings.Secure.getString(
                    context.contentResolver,
                    "enabled_notification_listeners"
                ) ?: return false
                val target = ComponentName(context, FocusNotificationMuteService::class.java).flattenToString()
                flat.split(":").any {
                    it.equals(target, ignoreCase = true) ||
                        it.contains(FocusNotificationMuteService::class.java.name)
                }
            } catch (_: Throwable) {
                false
            }
        }

        fun openNotificationListenerSettings(context: Context) {
            try {
                SettingsTamperGuard.grantPermissionSetupGrace(context, 180_000L)
                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Throwable) {}
        }

        fun triggerSweepIfActive() {
            try {
                instance?.sweepActiveDistractingNotifications()
            } catch (_: Throwable) {}
        }

        /**
         * Returns true when Smart Mute is enabled, permission is granted, AND either
         * Focus Layer (on an active focus day, not an off-day/holiday) or Deep Focus Studio is active.
         */
        fun isMutingRightNow(
            context: Context,
            nowMs: Long = FocusLayerManager.verifiedNowMs(context)
        ): Boolean {
            if (!isSmartMuteEnabled(context)) return false
            val shieldPrefs = context.getSharedPreferences("FocusGuard_ITR", Context.MODE_PRIVATE)
            if (!shieldPrefs.getBoolean("shield_active", true)) return false

            // Active Deep Focus Studio session always activates Smart Mute
            if (FocusSessionManager.snapshot.value.state == FocusSessionManager.SessionState.RUNNING) {
                return true
            }

            // Scheduled Focus Layer (already respects excluded days like Sunday & 24h-verified Calendar Holidays)
            if (FocusLayerManager.isScheduledActiveNow(context, nowMs)) {
                return true
            }

            // Manual Focus Layer override
            if (FocusLayerManager.isManualOverrideActive(context) && FocusLayerManager.isActiveNow(context)) {
                return true
            }

            return false
        }

        fun shouldMuteNotification(context: Context, sbn: StatusBarNotification): Boolean {
            val pkg = sbn.packageName ?: return false

            // 1. Never mute Focus Elite itself or Android system / telephony / alarm / payment exclusions
            if (pkg == context.packageName ||
                pkg == "com.focuselite.app" ||
                pkg == "com.focusguardpro.app" ||
                SystemExclusions.isExcludedPackage(context, pkg)
            ) {
                return false
            }

            // 2. Preserve ongoing / non-clearable notifications (music player, active call, timers, downloads)
            if (sbn.isOngoing || !sbn.isClearable) {
                return false
            }

            // 3. Preserve essential categories (calls, alarms, navigation, reminders, media)
            val notification = sbn.notification
            val category = notification?.category
            if (category != null && ALLOWED_CATEGORIES.contains(category)) {
                return false
            }

            // 4. Preserve Study & Improvement apps (unless explicitly marked Ignored)
            val ignored = try { AppCategoryPrefs.getIgnoredApps(context) } catch (_: Throwable) { emptySet() }
            if (!ignored.contains(pkg)) {
                val study = try { AppCategoryPrefs.getStudyApps(context) } catch (_: Throwable) { emptySet() }
                val improve = try { AppCategoryPrefs.getImprovementApps(context) } catch (_: Throwable) { emptySet() }
                if (study.contains(pkg) || improve.contains(pkg)) {
                    return false
                }
            }

            // 5. Preserve critical OTP / security verification notifications (inspected transiently in RAM only)
            try {
                val extras = notification?.extras
                val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
                val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
                if (OTP_REGEX.containsMatchIn(title) || OTP_REGEX.containsMatchIn(text)) {
                    return false
                }
            } catch (_: Throwable) {}

            return true
        }

        private fun todayKey(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

        private fun recordMutedNotification(context: Context, pkg: String) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val today = todayKey()
            val storedDay = prefs.getString(KEY_MUTED_DAY, null)
            val editor = prefs.edit()
            if (storedDay != today) {
                // Reset daily counters at midnight while preserving user toggle
                val enabled = prefs.getBoolean(KEY_ENABLED, true)
                editor.clear()
                editor.putBoolean(KEY_ENABLED, enabled)
                editor.putString(KEY_MUTED_DAY, today)
                editor.putInt(KEY_MUTED_COUNT, 1)
                editor.putInt("pkg_$pkg", 1)
            } else {
                val cur = prefs.getInt(KEY_MUTED_COUNT, 0)
                val pkgCur = prefs.getInt("pkg_$pkg", 0)
                editor.putInt(KEY_MUTED_COUNT, cur + 1)
                editor.putInt("pkg_$pkg", pkgCur + 1)
            }
            editor.apply()
        }

        fun getMutedCountToday(context: Context): Int {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.getString(KEY_MUTED_DAY, null) != todayKey()) return 0
            return prefs.getInt(KEY_MUTED_COUNT, 0)
        }
    }
}
