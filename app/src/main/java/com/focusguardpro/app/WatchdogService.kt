package com.focusguardpro.app

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.focusguardpro.app.db.AppDatabase
import com.focusguardpro.app.db.AuditRecord
import com.focusguardpro.app.db.SystemState
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

class WatchdogService : Service() {

    companion object {
        const val AUDIT_CHANNEL_ID = "focusguard_watchdog"
        const val AUDIT_NOTIFICATION_ID = 1001
        private const val ALERT_CHANNEL_ID = "FocusGuardAlertChannel"
        private const val POLL_INTERVAL_MS = 3000L
        private const val LONG_WALK_GPS_THRESHOLD_MS = 600000L
        const val AWAY_THRESHOLD_MINUTES = 5

        fun start(context: Context) {
            if (AppLifecycleCoordinator.isRestoreInProgress) return
            val intent = Intent(context, WatchdogService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Throwable) {}
        }
    }

    private val exceptionHandler = CoroutineExceptionHandler { _, _ -> }
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob + exceptionHandler)
    private lateinit var timeLimitManager: TimeLimitManager
    private var pollingJob: Job? = null

    private var lastRawStepReading: Long? = null
    private var lastStepTickAt: Long = 0L
    private val WALK_SESSION_GAP_MS = 240000L
    private var isStepCounterRegistered = false

    // Physical Accelerometer Step Detection State
    private var isAccelPeak = false
    private var lastPhysicalStepTimeMs = 0L

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (AppLifecycleCoordinator.isRestoreInProgress) return
            val ctx = context ?: this@WatchdogService
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    AwayTimeTracker.markScreenOff(ctx)
                    ScreenStateTracker.isScreenOn = false
                    ForegroundTracker.currentPackage = null
                    ForegroundTracker.currentPackageSince = 0L
                }
                Intent.ACTION_USER_PRESENT -> {
                    ScreenStateTracker.isScreenOn = true
                    AwayTimeTracker.resolveIfDue(ctx)
                }
                Intent.ACTION_SCREEN_ON -> {
                    ScreenStateTracker.isScreenOn = true
                    if (!isKeyguardLocked(ctx)) {
                        AwayTimeTracker.resolveIfDue(ctx)
                    }
                }
            }
        }
    }

    private val stepListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            if (event == null) return
            when (event.sensor.type) {
                Sensor.TYPE_STEP_DETECTOR -> {
                    onSingleStepDetected()
                }
                Sensor.TYPE_STEP_COUNTER -> {
                    val reading = event.values.getOrNull(0)?.toLong() ?: return
                    try {
                        updateStepBaseline(reading)
                        trackWalkSession(reading)
                    } catch (_: Exception) {}
                }
                Sensor.TYPE_ACCELEROMETER -> {
                    processAccelerometerSensor(event.values)
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun processAccelerometerSensor(values: FloatArray?) {
        if (values == null || values.size < 3) return
        val x = values[0]
        val y = values[1]
        val z = values[2]
        val magnitude = sqrt((x * x + y * y + z * z).toDouble()).toFloat()
        val now = System.currentTimeMillis()

        // Walking peak detection with threshold
        if (!isAccelPeak && magnitude > 11.5f) {
            isAccelPeak = true
        } else if (isAccelPeak && magnitude < 9.5f) {
            isAccelPeak = false
            // Human cadence debounce (260ms to 1800ms)
            if (now - lastPhysicalStepTimeMs in 260..1800 || lastPhysicalStepTimeMs == 0L) {
                lastPhysicalStepTimeMs = now
                onSingleStepDetected()
            }
        }
    }

    private fun onSingleStepDetected() {
        try {
            val current = StepPrefs.getDailySteps(this) + 1
            StepPrefs.setDailySteps(this, current)
            val reading = lastRawStepReading?.let { it + 1 } ?: current.toLong()
            trackWalkSession(reading)
        } catch (_: Exception) {}
    }

    override fun onCreate() {
        super.onCreate()
        timeLimitManager = TimeLimitManager(this)
        createNotificationChannels()
        startForegroundWithCurrentType()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
        }

        serviceScope.launch {
            try {
                AutoBackupManager.runIfNeeded(this@WatchdogService)
            } catch (_: Exception) {}
        }

        registerStepCounter()
        startPolling()

        try {
            FocusForegroundMonitorService.start(this)
            FocusMonitorWorker.schedule(this)
        } catch (_: Exception) {}
    }

    private fun isKeyguardLocked(context: Context): Boolean {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return km?.isKeyguardLocked ?: false
    }

    private fun registerStepCounter() {
        val sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        var registered = false

        val hasActivityPermission = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.ACTIVITY_RECOGNITION
        ) == PackageManager.PERMISSION_GRANTED

        // 1. Try Hardware Step Counter (battery efficient & accurate)
        if (hasActivityPermission) {
            val stepCounter = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
            if (stepCounter != null && sensorManager.registerListener(stepListener, stepCounter, SensorManager.SENSOR_DELAY_NORMAL)) {
                registered = true
            }

            // 2. Try Hardware Step Detector if Step Counter is unavailable
            if (!registered) {
                val stepDetector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
                if (stepDetector != null && sensorManager.registerListener(stepListener, stepDetector, SensorManager.SENSOR_DELAY_NORMAL)) {
                    registered = true
                }
            }
        }

        // 3. Physical Accelerometer fallback: only if hardware step counter/detector are NOT registered
        // This ensures tracking works seamlessly on physical hardware sensor without double-counting!
        if (!registered) {
            val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (accel != null && sensorManager.registerListener(stepListener, accel, SensorManager.SENSOR_DELAY_UI)) {
                registered = true
            }
        }

        isStepCounterRegistered = registered
    }

    private fun updateStepBaseline(currentReading: Long) {
        val dao = AppDatabase.getDatabase(this).systemDao()
        val now = System.currentTimeMillis()
        val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val baselineDate = dao.getState("STEP_BASELINE_DATE")
        val storedBaseline = dao.getState("STEP_BASELINE")?.toLongOrNull()
        val storedAccumulated = dao.getState("STEP_ACCUMULATED")?.toIntOrNull() ?: 0

        var baseline: Long
        var accumulated: Int

        if (baselineDate != today) {
            val rawSteps = dao.getState("DAILY_STEPS")?.toIntOrNull() ?: 0
            val finishedSteps = maxOf(rawSteps, StepPrefs.getDailySteps(this))
            if (baselineDate != null && finishedSteps > 0) {
                StepHistoryManager.recordDayFinal(this, baselineDate, finishedSteps)
            }
            accumulated = 0
            baseline = currentReading
            dao.putState(SystemState("STEP_ACCUMULATED", "0", now))
            dao.putState(SystemState("STEP_BASELINE", baseline.toString(), now))
            dao.putState(SystemState("STEP_BASELINE_DATE", today, now))
            StepPrefs.setDailySteps(this, 0)
        } else if (storedBaseline == null) {
            accumulated = storedAccumulated
            baseline = currentReading
            dao.putState(SystemState("STEP_BASELINE", baseline.toString(), now))
            dao.putState(SystemState("STEP_BASELINE_DATE", today, now))
        } else if (currentReading < storedBaseline) {
            accumulated = StepPrefs.getDailySteps(this)
            baseline = currentReading
            dao.putState(SystemState("STEP_ACCUMULATED", accumulated.toString(), now))
            dao.putState(SystemState("STEP_BASELINE", baseline.toString(), now))
            dao.putState(SystemState("STEP_BASELINE_DATE", today, now))
        } else {
            accumulated = storedAccumulated
            baseline = storedBaseline
        }

        val delta = (currentReading - baseline).coerceAtLeast(0L).toInt()
        val sensorSteps = accumulated + delta
        val currentSteps = StepPrefs.getDailySteps(this)

        val dailySteps = if (currentSteps > sensorSteps) {
            accumulated = currentSteps
            baseline = currentReading
            dao.putState(SystemState("STEP_ACCUMULATED", accumulated.toString(), now))
            dao.putState(SystemState("STEP_BASELINE", baseline.toString(), now))
            currentSteps
        } else {
            sensorSteps
        }

        StepPrefs.setDailySteps(this, dailySteps)
    }

    private fun trackWalkSession(currentReading: Long) {
        val previous = lastRawStepReading
        lastRawStepReading = currentReading
        if (previous == null || currentReading <= previous) {
            return
        }
        val delta = (currentReading - previous).toInt()
        if (delta <= 0) return

        val now = System.currentTimeMillis()
        lastStepTickAt = now
        val dao = AppDatabase.getDatabase(this).systemDao()
        val last = dao.getMostRecentAuditRecord("WALK_SESSION")

        if (last != null && now - last.timestamp <= WALK_SESSION_GAP_MS) {
            val (prevSteps, prevMeters) = DistanceHelper.parseSessionDetails(last.details)
            val updatedSteps = prevSteps + delta
            val updatedMeters = (prevMeters ?: 0.0) + (delta * 0.76)
            dao.updateAuditRecord(
                last.copy(
                    details = DistanceHelper.buildSessionDetails(updatedSteps, updatedMeters),
                    durationMillis = now - last.timestamp
                )
            )
        } else {
            val meters = delta * 0.76
            dao.insertAuditRecord(
                AuditRecord(
                    id = 0,
                    eventType = "WALK_SESSION",
                    timestamp = now,
                    details = DistanceHelper.buildSessionDetails(delta, meters),
                    durationMillis = 0L
                )
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun markHeartbeat() {
        val now = System.currentTimeMillis()
        AppDatabase.getDatabase(this).systemDao().putState(
            SystemState("WATCHDOG_HEARTBEAT", now.toString(), now)
        )
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = serviceScope.launch {
            while (isActive) {
                try {
                    markHeartbeat()
                } catch (_: Exception) {}

                try {
                    if (!isStepCounterRegistered) {
                        registerStepCounter()
                    }
                } catch (_: Exception) {}

                try {
                    if (HealthConnectManager.hasStepsPermission(this@WatchdogService)) {
                        val hcSteps = HealthConnectManager.getTodaySteps(this@WatchdogService)
                        if (hcSteps != null && hcSteps.toInt() > StepPrefs.getDailySteps(this@WatchdogService)) {
                            StepPrefs.setDailySteps(this@WatchdogService, hcSteps.toInt())
                        }
                    }
                } catch (_: Exception) {}

                try {
                    StepPrefs.rolloverCheck(this@WatchdogService)
                } catch (_: Exception) {}

                try {
                    if (ScreenStateTracker.isScreenOn && !isKeyguardLocked(this@WatchdogService)) {
                        val fgPkg = ForegroundTracker.currentPackage ?: UsageStatsHelper.getForegroundPackage(this@WatchdogService)
                        if (fgPkg != null &&
                            fgPkg != packageName &&
                            fgPkg != "com.focusguardpro.elite" &&
                            fgPkg != "com.focusguardpro.app" &&
                            !SystemExclusions.isExcludedPackage(this@WatchdogService, fgPkg) &&
                            !SystemExclusions.isInputMethod(this@WatchdogService, fgPkg)
                        ) {
                            val decision = BlockPolicyEngine.evaluateApp(this@WatchdogService, fgPkg)
                            if (decision is BlockDecision.Blocked) {
                                if (PorterBridge.isEnhancedModeActive()) {
                                    try {
                                        PorterBridge.forceStopPackage(decision.target)
                                    } catch (_: Exception) {}
                                }
                                timeLimitManager.triggerBlock(decision.target, decision.reason.wireKey)
                            }
                        } else if (fgPkg != null && SystemExclusions.isExcludedPackage(this@WatchdogService, fgPkg)) {
                            ForegroundTracker.currentPackage = fgPkg
                            ForegroundTracker.currentPackageSince = System.currentTimeMillis()
                        }
                    }
                } catch (_: Exception) {}

                try {
                    if (PorterBridge.isEnhancedModeActive()) {
                        PorterBridge.ensureAccessibilityServiceEnabled(this@WatchdogService)
                    }
                } catch (_: Exception) {}

                try {
                    if (GpsWalkTracker.isTracking && System.currentTimeMillis() - lastStepTickAt >= WALK_SESSION_GAP_MS) {
                        finalizeGpsTracking()
                    }
                } catch (_: Exception) {}

                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun finalizeGpsTracking() {
        val meters = GpsWalkTracker.stop(this)
        if (meters <= 0.0) return
        val dao = AppDatabase.getDatabase(this).systemDao()
        val last = dao.getMostRecentAuditRecord("WALK_SESSION") ?: return
        val steps = DistanceHelper.parseSessionDetails(last.details).first
        dao.updateAuditRecord(
            last.copy(
                details = DistanceHelper.buildSessionDetails(steps, meters)
            )
        )
    }

    private fun startForegroundWithCurrentType() {
        FocusGuardApp.ensureRoomMainThreadQueries(this)
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                var type = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                if (GpsWalkTracker.hasPermission(this)) {
                    type = type or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                }
                try {
                    startForeground(AUDIT_NOTIFICATION_ID, notification, type)
                } catch (_: Throwable) {
                    startForeground(
                        AUDIT_NOTIFICATION_ID,
                        notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (GpsWalkTracker.hasPermission(this)) {
                    try {
                        startForeground(
                            AUDIT_NOTIFICATION_ID,
                            notification,
                            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                        )
                    } catch (_: Throwable) {
                        startForeground(AUDIT_NOTIFICATION_ID, notification)
                    }
                } else {
                    startForeground(AUDIT_NOTIFICATION_ID, notification)
                }
            } else {
                startForeground(AUDIT_NOTIFICATION_ID, notification)
            }
        } catch (_: Throwable) {}
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(AUDIT_CHANNEL_ID, "Shield Service", NotificationManager.IMPORTANCE_LOW)
            )
            manager.createNotificationChannel(
                NotificationChannel(ALERT_CHANNEL_ID, "Time Reconciliation", NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, AUDIT_CHANNEL_ID)
            .setContentTitle("Focus Elite Active")
            .setContentText("Monitoring context and guarding focus.")
            .setSmallIcon(R.drawable.ic_shield_security)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        scheduleRestart()
    }

    override fun onDestroy() {
        super.onDestroy()
        scheduleRestart()
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {}

        val sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        sensorManager?.unregisterListener(stepListener)

        try {
            finalizeGpsTracking()
        } catch (_: Exception) {}

        serviceJob.cancel()
    }

    private fun scheduleRestart() {
        if (AppLifecycleCoordinator.isRestoreInProgress) return
        val prefs = getSharedPreferences("FocusGuard_ITR", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("shield_active", true)) return

        val restartIntent = Intent(this, WatchdogService::class.java)
        val pendingIntent = PendingIntent.getService(
            this,
            1,
            restartIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val triggerAt = System.currentTimeMillis() + 1000L
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        } else {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
    }
}
