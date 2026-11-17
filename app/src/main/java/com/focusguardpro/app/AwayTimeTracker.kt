package com.focusguardpro.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object AwayTimeTracker {

    private const val BRIEF_REWAKE_GRACE_MS = 300_000L // 5 minutes
    private const val MAX_AWAY_TRACK_MS = 50_400_000L  // 14 hours
    private const val ALERT_CHANNEL_ID = "FocusGuardAlertChannel"

    private const val KEY_AUTO_POPUP_ENABLED = "audit_screen_off_popup_enabled"
    private const val KEY_POPUP_SNOOZED_UNTIL_MS = "audit_screen_off_popup_snoozed_until"

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences("FocusGuard_ITR", Context.MODE_PRIVATE)
    }

    /**
     * By default, the automatic screen-off Audit Time popup is SHUT DOWN (false)
     * as requested, without deleting the AuditActivity or TimeSplitterActivity features.
     */
    fun isAutoAuditPopupEnabled(context: Context): Boolean {
        val p = prefs(context)
        val enabled = p.getBoolean(KEY_AUTO_POPUP_ENABLED, false)
        if (!enabled) return false
        val snoozedUntil = p.getLong(KEY_POPUP_SNOOZED_UNTIL_MS, 0L)
        if (snoozedUntil > System.currentTimeMillis()) {
            return false
        }
        return true
    }

    fun setAutoAuditPopupEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_AUTO_POPUP_ENABLED, enabled)
            .apply()
        if (!enabled) {
            cancelAuditNotifications(context)
        }
    }

    fun snoozeAutoAuditPopupForHours(context: Context, hours: Int) {
        val until = System.currentTimeMillis() + hours * 3_600_000L
        prefs(context).edit()
            .putLong(KEY_POPUP_SNOOZED_UNTIL_MS, until)
            .apply()
        cancelAuditNotifications(context)
    }

    fun clearSnooze(context: Context) {
        prefs(context).edit()
            .putLong(KEY_POPUP_SNOOZED_UNTIL_MS, 0L)
            .apply()
    }

    fun getSnoozeRemainingMinutes(context: Context): Long {
        val until = prefs(context).getLong(KEY_POPUP_SNOOZED_UNTIL_MS, 0L)
        val diff = until - System.currentTimeMillis()
        return if (diff > 0L) diff / 60_000L else 0L
    }

    private fun cancelAuditNotifications(context: Context) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(8420)
            nm?.cancel(8421)
        } catch (_: Throwable) {}
    }

    fun markScreenOff(context: Context) {
        try {
            prefs(context).edit()
                .putLong("screen_off_time", System.currentTimeMillis())
                .apply()
        } catch (_: Throwable) {}
    }

    fun markSleepAnswered(context: Context) {
        try {
            prefs(context).edit()
                .putLong("last_sleep_answered_at", System.currentTimeMillis())
                .apply()
        } catch (_: Throwable) {}
    }

    private fun getScreenOffTime(p: SharedPreferences): Long {
        return try {
            p.getLong("screen_off_time", 0L)
        } catch (_: ClassCastException) {
            try {
                val v = p.getInt("screen_off_time", 0).toLong()
                p.edit().putLong("screen_off_time", v).apply()
                v
            } catch (_: Exception) {
                0L
            }
        }
    }

    fun reconcileMissedShutdown(context: Context) {
        try {
            val p = prefs(context)
            if (getScreenOffTime(p) > 0L) return
            val heartbeat = p.getLong("heartbeat_at", 0L)
            if (heartbeat <= 0L) return
            val delta = System.currentTimeMillis() - heartbeat
            if (delta in BRIEF_REWAKE_GRACE_MS..MAX_AWAY_TRACK_MS) {
                p.edit().putLong("screen_off_time", heartbeat).apply()
            } else if (delta > MAX_AWAY_TRACK_MS) {
                p.edit().putLong("heartbeat_at", System.currentTimeMillis()).apply()
            }
        } catch (_: Throwable) {}
    }

    fun getPendingAwayMs(context: Context): Long {
        return try {
            prefs(context).getLong("pending_away_ms", 0L)
        } catch (_: Throwable) {
            0L
        }
    }

    fun clearPendingAwayMs(context: Context) {
        try {
            prefs(context).edit().putLong("pending_away_ms", 0L).apply()
        } catch (_: Throwable) {}
    }

    fun hasPendingAway(context: Context): Boolean {
        if (!isAutoAuditPopupEnabled(context)) return false
        return getPendingAwayMs(context) >= BRIEF_REWAKE_GRACE_MS
    }

    fun launchPendingIfAny(context: Context) {
        if (!isAutoAuditPopupEnabled(context)) {
            cancelAuditNotifications(context)
            return
        }
        val pending = getPendingAwayMs(context)
        if (pending < BRIEF_REWAKE_GRACE_MS) return
        val intent = Intent(context, TimeSplitterActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("TIME_AWAY_MINS", pending / 60_000L)
            putExtra("IS_OFFLINE", true)
        }
        tryDirectLaunch(context, intent)
    }

    fun resolveIfDue(context: Context) {
        try {
            val p = prefs(context)
            val offTime = getScreenOffTime(p)
            if (offTime <= 0L) return

            val now = System.currentTimeMillis()
            val awayMs = now - offTime
            p.edit().putLong("screen_off_time", 0L).apply()

            if (awayMs < BRIEF_REWAKE_GRACE_MS) return

            // Record pending away time quietly so manual Daily Time Notebook still knows about it if desired
            p.edit().putLong("pending_away_ms", awayMs).apply()

            // CRITICAL: When screen-off Audit popup is shut down (default), do NOT pop up or post notifications!
            if (!isAutoAuditPopupEnabled(context)) {
                cancelAuditNotifications(context)
                return
            }

            val cal = Calendar.getInstance()
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            val todayStr = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
            val lastMorningDate = p.getString("last_morning_audit_date", "") ?: ""
            val isMorningWindow = hour in 4..11
            val isLongAway = awayMs >= 10_800_000L // 3 hours

            val launchIntent = if (isMorningWindow && isLongAway && lastMorningDate != todayStr) {
                Intent(context, AuditActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("AWAY_TIME_MS", awayMs)
                    putExtra("IS_MORNING", true)
                }
            } else {
                Intent(context, TimeSplitterActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("TIME_AWAY_MINS", awayMs / 60_000L)
                    putExtra("IS_OFFLINE", true)
                }
            }

            if (isMorningWindow && isLongAway && lastMorningDate != todayStr) {
                p.edit().putString("last_morning_audit_date", todayStr).apply()
                postMorningNotification(context, launchIntent, awayMs)
                tryDirectLaunch(context, launchIntent)
            } else {
                postAwayNotification(context, launchIntent, awayMs)
                tryDirectLaunch(context, launchIntent)
            }
        } catch (_: Throwable) {}
    }

    fun tryDirectLaunch(context: Context, intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (_: Throwable) {}
    }

    private fun postMorningNotification(context: Context, intent: Intent, awayMs: Long) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(ALERT_CHANNEL_ID, "Time Reconciliation", NotificationManager.IMPORTANCE_HIGH)
                )
            }
            val pi = PendingIntent.getActivity(
                context,
                8421,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val hrs = awayMs / 3_600_000L
            val mins = (awayMs % 3_600_000L) / 60_000L
            val notif = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
                .setContentTitle("☀️ Morning Time & Sleep Audit")
                .setContentText("You were offline for ${hrs}h ${mins}m. Log your rest & morning plan.")
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            nm.notify(8421, notif)
        } catch (_: Throwable) {}
    }

    private fun postAwayNotification(context: Context, intent: Intent, awayMs: Long) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(ALERT_CHANNEL_ID, "Time Reconciliation", NotificationManager.IMPORTANCE_HIGH)
                )
            }
            val pi = PendingIntent.getActivity(
                context,
                8420,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val mins = awayMs / 60_000L
            val notif = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
                .setContentTitle("⏱️ Time Audit Check-In")
                .setContentText("Away for ${mins}m — tap to categorize offline time.")
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            nm.notify(8420, notif)
        } catch (_: Throwable) {}
    }
}
