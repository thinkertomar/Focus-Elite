package com.focusguardpro.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * FocusWidgetService
 *
 * Background state & telemetry communication bridge between the Focus Elite app
 * (Health Connect steps, UsageStats screen time, FocusLayerManager shield state,
 * and FocusSessionManager Pomodoro/40Hz audio engine) and the Home Screen Command Deck Widget.
 */
class FocusWidgetService : Service() {

    companion object {
        const val ACTION_SYNC_TELEMETRY = "com.focusguardpro.app.widget.ACTION_SYNC_TELEMETRY"
        const val ACTION_TOGGLE_SHIELD = "com.focusguardpro.app.widget.ACTION_TOGGLE_SHIELD"
        const val ACTION_START_QUICK_FOCUS = "com.focusguardpro.app.widget.ACTION_START_QUICK_FOCUS"
        const val ACTION_STOP_FOCUS = "com.focusguardpro.app.widget.ACTION_STOP_FOCUS"
        const val ACTION_TELEMETRY_UPDATED = "com.focusguardpro.app.widget.ACTION_TELEMETRY_UPDATED"

        fun syncTelemetryNow(context: Context) {
            try {
                val appCtx = context.applicationContext
                val intent = Intent(appCtx, FocusWidgetService::class.java).apply {
                    action = ACTION_SYNC_TELEMETRY
                }
                appCtx.startService(intent)
            } catch (_: Throwable) {
                // Fallback if background start restrictions apply while app is in background
                EliteCommandWidgetProvider.updateAllWidgets(context.applicationContext)
            }
        }

        fun toggleShieldFromWidget(context: Context) {
            try {
                val appCtx = context.applicationContext
                val intent = Intent(appCtx, FocusWidgetService::class.java).apply {
                    action = ACTION_TOGGLE_SHIELD
                }
                appCtx.startService(intent)
            } catch (_: Throwable) {
                handleToggleShieldInline(context.applicationContext)
            }
        }

        fun triggerQuickFocusFromWidget(context: Context) {
            try {
                val appCtx = context.applicationContext
                val intent = Intent(appCtx, FocusWidgetService::class.java).apply {
                    action = ACTION_START_QUICK_FOCUS
                }
                appCtx.startService(intent)
            } catch (_: Throwable) {
                handleQuickFocusInline(context.applicationContext)
            }
        }

        internal fun handleToggleShieldInline(context: Context) {
            if (FocusLayerManager.isLockedUntilCalendarHoliday(context)) {
                Toast.makeText(
                    context,
                    "🔒 Strict Focus Lock Active! Opening Schedule & Holiday Hub...",
                    Toast.LENGTH_SHORT
                ).show()
                context.startActivity(
                    Intent(context, AuxiliarySettingsActivity::class.java).apply {
                        putExtra(AuxiliarySettingsActivity.EXTRA_INITIAL_TAB, AuxiliarySettingsActivity.TAB_AUXILIARY)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            } else {
                val nowActive = FocusLayerManager.toggleManualOverride(context)
                Toast.makeText(
                    context,
                    if (nowActive) "🟢 Focus Shield Activated from Command Deck" else "🛡️ Focus Shield Paused",
                    Toast.LENGTH_SHORT
                ).show()
            }
            EliteCommandWidgetProvider.updateAllWidgets(context)
        }

        internal fun handleQuickFocusInline(context: Context) {
            FocusSessionManager.init(context)
            val currentState = FocusSessionManager.snapshot.value.state
            if (currentState == FocusSessionManager.SessionState.IDLE ||
                currentState == FocusSessionManager.SessionState.COMPLETED
            ) {
                val mins = CommandDeckWidgetPrefs.getQuickFocusMinutes(context)
                val auto40Hz = CommandDeckWidgetPrefs.isAuto40HzEnabled(context)
                val sound = if (auto40Hz) {
                    AmbientFocusAudioEngine.SoundMode.GAMMA_40HZ
                } else {
                    AmbientFocusAudioEngine.SoundMode.OFF
                }
                FocusSessionManager.startSession(
                    context = context,
                    durationMinutes = mins,
                    intention = "⚡ Command Deck Deep Flow",
                    soundMode = sound
                )
                val audioMsg = if (auto40Hz) " + 40Hz Gamma Waves" else ""
                Toast.makeText(
                    context,
                    "⚡ ${mins}m Deep Focus$audioMsg Started!",
                    Toast.LENGTH_SHORT
                ).show()
                EliteCommandWidgetProvider.updateAllWidgets(context)
            } else {
                context.startActivity(
                    Intent(context, FocusSessionActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                )
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var sessionObserverJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        FocusGuardApp.ensureRoomMainThreadQueries(applicationContext)
        FocusSessionManager.init(applicationContext)
        ensureSessionObserver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_SYNC_TELEMETRY
        when (action) {
            ACTION_TOGGLE_SHIELD -> {
                handleToggleShieldInline(applicationContext)
                performAsyncTelemetrySync(startId)
            }
            ACTION_START_QUICK_FOCUS -> {
                handleQuickFocusInline(applicationContext)
                ensureSessionObserver()
                performAsyncTelemetrySync(startId)
            }
            ACTION_STOP_FOCUS -> {
                FocusSessionManager.stopSession(applicationContext, completed = false)
                performAsyncTelemetrySync(startId)
            }
            ACTION_SYNC_TELEMETRY -> {
                performAsyncTelemetrySync(startId)
            }
            else -> {
                performAsyncTelemetrySync(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun ensureSessionObserver() {
        if (sessionObserverJob?.isActive == true) return
        sessionObserverJob = serviceScope.launch {
            FocusSessionManager.snapshot.collectLatest { snap ->
                withContext(Dispatchers.Main) {
                    EliteCommandWidgetProvider.updateAllWidgets(applicationContext)
                }
                if (snap.state == FocusSessionManager.SessionState.IDLE ||
                    snap.state == FocusSessionManager.SessionState.COMPLETED
                ) {
                    // Stop observing once session returns to idle
                    stopSelf()
                }
            }
        }
    }

    private fun performAsyncTelemetrySync(startId: Int) {
        serviceScope.launch {
            try {
                // 1. Pull fresh steps from Health Connect if permission is granted
                if (HealthConnectManager.hasStepsPermission(applicationContext)) {
                    val hcSteps = HealthConnectManager.getTodaySteps(applicationContext)
                    if (hcSteps != null && hcSteps > 0L) {
                        StepPrefs.setDailySteps(applicationContext, hcSteps.toInt())
                    }
                }
                // 2. Touch UsageStatsHelper so screen-time cache is warm
                UsageStatsHelper.getTodayScreenTimeMillis(applicationContext)
            } catch (_: Throwable) {}

            withContext(Dispatchers.Main) {
                EliteCommandWidgetProvider.updateAllWidgets(applicationContext)
                try {
                    val broadcast = Intent(ACTION_TELEMETRY_UPDATED).apply {
                        setPackage(packageName)
                    }
                    sendBroadcast(broadcast)
                } catch (_: Throwable) {}

                val activeSession = FocusSessionManager.snapshot.value.state
                if (activeSession != FocusSessionManager.SessionState.RUNNING &&
                    activeSession != FocusSessionManager.SessionState.PAUSED
                ) {
                    stopSelfResult(startId)
                }
            }
        }
    }

    override fun onDestroy() {
        sessionObserverJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
