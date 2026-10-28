package com.focusguardpro.app

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * High-reliability foreground monitor service that inspects foreground app activity
 * and aggressively blocks distraction packages during focus windows, deep focus sessions,
 * blackout windows, hard blocks, and daily time limits.
 */
class FocusForegroundMonitorService : Service() {

    companion object {
        const val CHANNEL_ID = "focusguard_focus_monitor"
        const val NOTIFICATION_ID = 1002
        private const val FAST_POLL_MS = 1000L
        private const val IDLE_POLL_MS = 2500L
        private const val ALARM_INTERVAL_MS = 300000L // 5 minutes keep-alive alarm

        fun start(context: Context) {
            val shieldPrefs = context.getSharedPreferences("FocusGuard_ITR", Context.MODE_PRIVATE)
            if (!shieldPrefs.getBoolean("shield_active", true)) return

            val intent = Intent(context, FocusForegroundMonitorService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {}
        }
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private lateinit var timeLimitManager: TimeLimitManager
    private var monitorJob: Job? = null
    private var isScreenOn = true
    private var lastBlockedPkg: String? = null
    private var lastBlockedTimeMs: Long = 0L
    @Volatile
    private var lastResolvedUsagesPkg: String? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenOn = false
                    ForegroundTracker.currentPackage = null
                    ForegroundTracker.currentPackageSince = 0L
                    lastResolvedUsagesPkg = null
                    stopMonitoringLoop()
                }
                Intent.ACTION_SCREEN_ON -> {
                    isScreenOn = true
                    startMonitoringLoop()
                    checkForegroundNow()
                }
                Intent.ACTION_USER_PRESENT -> {
                    isScreenOn = true
                    startMonitoringLoop()
                    checkForegroundNow()
                }
                Intent.ACTION_TIME_TICK -> {
                    SettingsTamperGuard.recordVerifiedHeartbeat(this@FocusForegroundMonitorService)
                    updateNotification()
                    checkForegroundNow()
                }
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED -> {
                    SettingsTamperGuard.onSystemTimeChanged(this@FocusForegroundMonitorService)
                    updateNotification()
                    checkForegroundNow()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        FocusGuardApp.ensureRoomMainThreadQueries(this)
        SettingsTamperGuard.recordVerifiedHeartbeat(this)
        timeLimitManager = TimeLimitManager(this)
        createNotificationChannel()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        } catch (_: Throwable) {}

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(screenReceiver, filter)
            }
        } catch (_: Throwable) {}

        scheduleKeepAliveAlarm()
        startMonitoringLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        updateNotification()
        checkForegroundNow()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {}
        serviceJob.cancel()
        super.onDestroy()
    }

    private fun startMonitoringLoop() {
        if (!isScreenOn) return
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            while (isActive) {
                val delayTime = try {
                    if (isScreenOn && !isKeyguardLocked()) {
                        evaluateAndBlockForeground()
                        val focusActive = FocusLayerManager.isActiveNow(this@FocusForegroundMonitorService) ||
                            FocusSessionManager.snapshot.value.state == FocusSessionManager.SessionState.RUNNING
                        if (focusActive) FAST_POLL_MS else IDLE_POLL_MS
                    } else {
                        IDLE_POLL_MS
                    }
                } catch (_: Exception) {
                    IDLE_POLL_MS
                }
                delay(delayTime)
            }
        }
    }

    private fun stopMonitoringLoop() {
        try {
            monitorJob?.cancel()
            monitorJob = null
        } catch (_: Throwable) {}
    }

    private fun checkForegroundNow() {
        serviceScope.launch {
            if (isScreenOn && !isKeyguardLocked()) {
                evaluateAndBlockForeground()
            }
        }
    }

    private fun isSelfPackage(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        return pkg == packageName ||
            pkg == "com.focuselite.app" ||
            pkg == "com.focusguardpro.elite" ||
            pkg == "com.focusguardpro.app"
    }

    private fun evaluateAndBlockForeground() {
        val shieldPrefs = getSharedPreferences("FocusGuard_ITR", Context.MODE_PRIVATE)
        if (!shieldPrefs.getBoolean("shield_active", true)) return
        if (AppLifecycleCoordinator.isRestoreInProgress) return

        val fgPkg = getActiveForegroundPackage() ?: return
        if (fgPkg.isBlank()) return

        // 1. Never block Focus Elite or critical system processes
        if (isSelfPackage(fgPkg)) {
            ForegroundTracker.currentPackage = fgPkg
            return
        }

        // 2. Never block system keyboards or excluded system packages (Launcher, Dialer, SystemUI)
        if (SystemExclusions.isInputMethod(this, fgPkg) || SystemExclusions.isExcludedPackage(this, fgPkg)) {
            ForegroundTracker.currentPackage = fgPkg
            return
        }

        val nowMs = FocusLayerManager.verifiedNowMs(this)

        // 3. Authoritative policy evaluation (HardBlock, Scheduled/Manual Focus Layer, Deep Focus Session, Blackout, Daily Limit)
        val decision = BlockPolicyEngine.evaluateApp(this, fgPkg, nowMs)
        if (decision is BlockDecision.Blocked) {
            triggerEnforcement(decision.target, decision.reason.wireKey, nowMs)
        }
    }

    private fun triggerEnforcement(pkg: String, reason: String, nowMs: Long) {
        // Debounce repeated blocks of the same package within 1.2 seconds
        if (pkg == lastBlockedPkg && nowMs - lastBlockedTimeMs < 1200L) {
            return
        }
        lastBlockedPkg = pkg
        lastBlockedTimeMs = nowMs

        // Clear tracker package to reset state
        ForegroundTracker.currentPackage = null
        ForegroundTracker.currentPackageSince = 0L
        lastResolvedUsagesPkg = null

        // Enhanced mode: force stop package via Shizuku/Porter
        if (PorterBridge.isEnhancedModeActive()) {
            try {
                PorterBridge.forceStopPackage(pkg)
            } catch (_: Exception) {}
        }

        // Minimise distracting app immediately by routing to home
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(homeIntent)
        } catch (_: Exception) {}

        // Launch full-screen BlockActivity explaining why the app is blocked
        timeLimitManager.triggerBlock(pkg, reason)
    }

    private fun getActiveForegroundPackage(): String? {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        val now = System.currentTimeMillis()

        // 1. Query recent UsageEvents (last 15 seconds) first to catch the freshest app transition
        if (usm != null) {
            try {
                val recentEvents = usm.queryEvents(now - 15_000L, now)
                if (recentEvents != null) {
                    val event = UsageEvents.Event()
                    var recentPkg: String? = null
                    var recentTs = 0L
                    while (recentEvents.hasNextEvent()) {
                        recentEvents.getNextEvent(event)
                        if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED || event.eventType == 1) {
                            val pkg = event.packageName
                            if (!pkg.isNullOrBlank() && pkg != "com.android.systemui" && event.timeStamp >= recentTs) {
                                recentTs = event.timeStamp
                                recentPkg = pkg
                            }
                        }
                    }
                    if (!recentPkg.isNullOrBlank()) {
                        lastResolvedUsagesPkg = recentPkg
                        return recentPkg
                    }
                }
            } catch (_: Exception) {}
        }

        // 2. Check package reported by BlockerAccessibilityService
        val direct = ForegroundTracker.currentPackage
        if (!direct.isNullOrBlank() && direct != "com.android.systemui") {
            return direct
        }

        // 3. Cached foreground package from earlier UsageEvents if user has stayed in the same app >15s
        val cached = lastResolvedUsagesPkg
        if (!cached.isNullOrBlank()) {
            return cached
        }

        // 4. Deep fallback: scan last 30 minutes of UsageEvents if service just started while an app was already open
        if (usm != null) {
            try {
                val events = usm.queryEvents(now - 1800_000L, now) ?: return direct
                val event = UsageEvents.Event()
                var topPkg: String? = null
                var topTs = 0L

                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED || event.eventType == 1) {
                        val pkg = event.packageName
                        if (!pkg.isNullOrBlank() && pkg != "com.android.systemui" && event.timeStamp >= topTs) {
                            topTs = event.timeStamp
                            topPkg = pkg
                        }
                    }
                }
                if (!topPkg.isNullOrBlank()) {
                    lastResolvedUsagesPkg = topPkg
                    return topPkg
                }
            } catch (_: Exception) {}
        }

        return direct
    }

    private fun isKeyguardLocked(): Boolean {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return km?.isKeyguardLocked ?: false
    }

    private fun buildNotification(): Notification {
        val isFocus = FocusLayerManager.isActiveNow(this) ||
            FocusSessionManager.snapshot.value.state == FocusSessionManager.SessionState.RUNNING
        val summary = FocusLayerManager.formatWindowSummary(this)
        val contentText = if (isFocus) {
            "Focus Mode Active ($summary) · Distractions blocked"
        } else {
            "Focus Shield Monitoring · Window: $summary"
        }

        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Focus Elite Shield")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_shield_security)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Focus Foreground Monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps distraction blocking active during scheduled focus hours"
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun scheduleKeepAliveAlarm() {
        try {
            val am = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(this, FocusForegroundMonitorService::class.java)
            val pi = PendingIntent.getService(
                this, 1002, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val triggerAt = SystemClock.elapsedRealtime() + ALARM_INTERVAL_MS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            } else {
                am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            }
        } catch (_: Exception) {}
    }
}
