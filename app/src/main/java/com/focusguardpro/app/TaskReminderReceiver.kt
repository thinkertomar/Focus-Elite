package com.focusguardpro.app

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Local, 100%-offline reminder scheduler for Focus Elite tasks.
 * Uses AlarmManager with safe Android 12+ exact-alarm fallback (never throws SecurityException).
 */
object TaskReminderScheduler {

    const val CHANNEL_ID = "focus_elite_task_reminders"
    const val ACTION_TASK_REMINDER = "com.focusguardpro.app.ACTION_TASK_REMINDER"
    const val ACTION_MARK_TASK_DONE = "com.focusguardpro.app.ACTION_MARK_TASK_DONE"
    const val EXTRA_TASK_ID = "EXTRA_TASK_ID"
    const val EXTRA_TASK_TITLE = "EXTRA_TASK_TITLE"
    const val EXTRA_TASK_DATE_KEY = "EXTRA_TASK_DATE_KEY"
    const val EXTRA_TASK_PRIORITY = "EXTRA_TASK_PRIORITY"
    const val EXTRA_TASK_DUE_MINS = "EXTRA_TASK_DUE_MINS"

    fun install() {
        DisciplineDataRepository.taskReminderHook = { ctx, taskId, task ->
            if (task == null || task.item.isDone || !task.meta.reminderEnabled || task.meta.dueTimeMinutes == null) {
                cancelReminder(ctx, taskId)
            } else {
                scheduleReminder(ctx, task)
            }
        }
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "Task Reminders",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Scheduled local reminders for your Focus Elite tasks"
                }
                nm.createNotificationChannel(ch)
            }
        }
    }

    fun scheduleReminder(context: Context, task: DisciplineDataRepository.EnrichedTask) {
        val dueMins = task.meta.dueTimeMinutes ?: return
        if (!task.meta.reminderEnabled || task.item.isDone) {
            cancelReminder(context, task.item.id)
            return
        }
        val triggerMs = DisciplineDataRepository.startOfDayMs(task.item.dateKey) + dueMins * 60_000L
        if (triggerMs <= System.currentTimeMillis() - 60_000L) {
            // Do not fire immediately for reminders already in the past
            cancelReminder(context, task.item.id)
            return
        }

        val appCtx = context.applicationContext
        val am = appCtx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = buildReminderPendingIntent(appCtx, task)

        try {
            val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.canScheduleExactAlarms()
            } else {
                true
            }
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
            }
        } catch (_: SecurityException) {
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
            } catch (_: Throwable) {}
        } catch (_: Throwable) {}
    }

    fun cancelReminder(context: Context, taskId: Long) {
        val appCtx = context.applicationContext
        val am = appCtx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(appCtx, TaskReminderReceiver::class.java).apply {
            action = ACTION_TASK_REMINDER
        }
        val pi = PendingIntent.getBroadcast(
            appCtx,
            requestCodeFor(taskId),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pi != null) {
            try {
                am.cancel(pi)
                pi.cancel()
            } catch (_: Throwable) {}
        }
        try {
            val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(notificationIdFor(taskId))
        } catch (_: Throwable) {}
    }

    fun rescheduleAllPendingReminders(context: Context) {
        val appCtx = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                FocusGuardApp.ensureRoomMainThreadQueries(appCtx)
                val all = DisciplineDataRepository.getAllEnrichedTasks(appCtx)
                for (task in all) {
                    if (!task.item.isDone && task.meta.reminderEnabled && task.meta.dueTimeMinutes != null) {
                        scheduleReminder(appCtx, task)
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    private fun buildReminderPendingIntent(
        context: Context,
        task: DisciplineDataRepository.EnrichedTask
    ): PendingIntent {
        val intent = Intent(context, TaskReminderReceiver::class.java).apply {
            action = ACTION_TASK_REMINDER
            putExtra(EXTRA_TASK_ID, task.item.id)
            putExtra(EXTRA_TASK_TITLE, task.item.text)
            putExtra(EXTRA_TASK_DATE_KEY, task.item.dateKey)
            putExtra(EXTRA_TASK_PRIORITY, task.meta.priority.label)
            putExtra(EXTRA_TASK_DUE_MINS, task.meta.dueTimeMinutes ?: -1)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCodeFor(task.item.id),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun requestCodeFor(taskId: Long): Int = 41000 + (taskId % 10000L).toInt()
    fun notificationIdFor(taskId: Long): Int = 51000 + (taskId % 10000L).toInt()
}

class TaskReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val appCtx = context.applicationContext

        when (action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                TaskReminderScheduler.rescheduleAllPendingReminders(appCtx)
            }

            TaskReminderScheduler.ACTION_MARK_TASK_DONE -> {
                val taskId = intent.getLongExtra(TaskReminderScheduler.EXTRA_TASK_ID, -1L)
                if (taskId > 0L) {
                    val pendingResult = goAsync()
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            FocusGuardApp.ensureRoomMainThreadQueries(appCtx)
                            DisciplineDataRepository.toggleTaskDone(appCtx, taskId, true)
                            val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                            nm?.cancel(TaskReminderScheduler.notificationIdFor(taskId))
                        } catch (_: Throwable) {
                        } finally {
                            pendingResult.finish()
                        }
                    }
                }
            }

            TaskReminderScheduler.ACTION_TASK_REMINDER -> {
                val taskId = intent.getLongExtra(TaskReminderScheduler.EXTRA_TASK_ID, -1L)
                if (taskId <= 0L) return
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        FocusGuardApp.ensureRoomMainThreadQueries(appCtx)
                        val task = DisciplineDataRepository.getAllEnrichedTasks(appCtx)
                            .firstOrNull { it.item.id == taskId }
                        if (task == null || task.item.isDone) return@launch
                        showTaskReminderNotification(appCtx, task)
                    } catch (_: Throwable) {
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }

    private fun showTaskReminderNotification(
        context: Context,
        task: DisciplineDataRepository.EnrichedTask
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        }
        TaskReminderScheduler.ensureChannel(context)

        val openIntent = Intent(context, EliteTasksActivity::class.java).apply {
            putExtra(EliteTasksActivity.EXTRA_DATE_KEY, task.item.dateKey)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPi = PendingIntent.getActivity(
            context,
            TaskReminderScheduler.requestCodeFor(task.item.id) + 10000,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val doneIntent = Intent(context, TaskReminderReceiver::class.java).apply {
            action = TaskReminderScheduler.ACTION_MARK_TASK_DONE
            putExtra(TaskReminderScheduler.EXTRA_TASK_ID, task.item.id)
        }
        val donePi = PendingIntent.getBroadcast(
            context,
            TaskReminderScheduler.requestCodeFor(task.item.id) + 20000,
            doneIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val dueStr = task.meta.dueTimeMinutes?.let { DisciplineDataRepository.formatTimeOfDay(it) } ?: ""
        val subtitle = buildString {
            append(task.meta.priority.label).append(" priority")
            if (dueStr.isNotEmpty()) append(" · Due ").append(dueStr)
            if (task.meta.recurrence != DisciplineDataRepository.TaskRecurrence.NONE) {
                append(" · ").append(task.meta.recurrence.label)
            }
        }

        val notif = NotificationCompat.Builder(context, TaskReminderScheduler.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield_security)
            .setContentTitle("Task Reminder: ${task.item.text}")
            .setContentText(subtitle)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openPi)
            .addAction(0, "Mark Done", donePi)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(TaskReminderScheduler.notificationIdFor(task.item.id), notif)
    }
}
