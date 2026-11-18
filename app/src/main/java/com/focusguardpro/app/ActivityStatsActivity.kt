package com.focusguardpro.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import com.focusguardpro.app.db.AppDatabase
import com.focusguardpro.app.insights.InsightsDashboardActivity
import kotlinx.coroutines.*
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class ActivityStatsActivity : AppCompatActivity() {

    private val STATS_REFRESH_MS = 4000L
    private var refreshJob: Job? = null

    private lateinit var tvHistoryLog: TextView
    private lateinit var tvLatestWorkoutSummary: TextView
    private lateinit var tvToggleWorkoutHistory: TextView
    private lateinit var tvWeight: TextView
    private lateinit var stepsRing: RingProgressView
    private lateinit var tvSensorStatusPill: TextView
    private lateinit var tvHeroGoalPercent: TextView
    private lateinit var tvHeroRemainingSteps: TextView
    private lateinit var tvHeroPeakWindow: TextView
    private lateinit var tvDistanceSub: TextView

    // Running & Walking Performance Views
    private lateinit var tvWalkJogStatusDot: TextView
    private lateinit var tvAutoWalkJogHeader: TextView
    private lateinit var tvAutoWalkJogSummaryLine: TextView
    private lateinit var tvAvgPace: TextView
    private lateinit var tvAvgSpeed: TextView
    private lateinit var tvMovingTime: TextView
    private lateinit var tvStepCadence: TextView
    private lateinit var stravaPaceChart: StravaPaceChartView
    private lateinit var llKmSplitsContainer: LinearLayout

    // Secondary Collapsible Drawer Views
    private lateinit var rowToggleDataSources: LinearLayout
    private lateinit var llSecondaryDataSources: LinearLayout
    private lateinit var tvDataSourcesChevron: TextView
    private lateinit var tvDataSourcesSummary: TextView

    private var isLocalSensorRegistered = false
    private var isHardwareSensorAvailable = true
    private var sensorManager: SensorManager? = null
    private var lastCounterBaseline: Int = -1
    private var hasAutoPromptedSensorOnOpen = false
    private var isSplitsExpanded = false
    private var isWorkoutHistoryExpanded = false
    private var isDataSourcesExpanded = false

    private val localStepListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            if (event == null) return
            when (event.sensor.type) {
                Sensor.TYPE_STEP_DETECTOR -> {
                    val added = event.values.firstOrNull()?.toInt() ?: 1
                    if (added > 0) {
                        StepPrefs.rolloverCheck(this@ActivityStatsActivity)
                        val current = StepPrefs.getDailySteps(this@ActivityStatsActivity)
                        StepPrefs.setDailySteps(this@ActivityStatsActivity, current + added)
                        lifecycleScope.launch(Dispatchers.IO) {
                            ActivityTelemetryEngine.recordHardwareStepDelta(this@ActivityStatsActivity, added)
                            withContext(Dispatchers.Main) {
                                refreshStepStats()
                            }
                        }
                    }
                }
                Sensor.TYPE_STEP_COUNTER -> {
                    val totalSinceBoot = event.values.firstOrNull()?.toInt() ?: return
                    if (lastCounterBaseline < 0) {
                        lastCounterBaseline = totalSinceBoot
                        return
                    }
                    val delta = (totalSinceBoot - lastCounterBaseline).coerceAtLeast(0)
                    lastCounterBaseline = totalSinceBoot
                    if (delta in 1..500) {
                        StepPrefs.rolloverCheck(this@ActivityStatsActivity)
                        val current = StepPrefs.getDailySteps(this@ActivityStatsActivity)
                        StepPrefs.setDailySteps(this@ActivityStatsActivity, current + delta)
                        lifecycleScope.launch(Dispatchers.IO) {
                            ActivityTelemetryEngine.recordHardwareStepDelta(this@ActivityStatsActivity, delta)
                            withContext(Dispatchers.Main) {
                                refreshStepStats()
                            }
                        }
                    }
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val requestHealthPermissions = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { _ ->
        refreshHealthConnectUi()
        lifecycleScope.launch {
            try {
                HealthConnectManager.syncPastDaysSteps(this@ActivityStatsActivity, 7)
            } catch (_: Exception) {}
            refreshStepStats()
            loadWeeklyTrend()
        }
    }

    private val requestActivityPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        updateSensorStatusUi(granted)
        if (granted) {
            registerStepDetectorIfPermitted()
            lifecycleScope.launch { refreshStepStats() }
        }
    }

    private val requestLocationPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        refreshLocationPermissionButtons()
        if (GpsWalkTracker.hasPermission(this) && !GpsWalkTracker.isTracking) {
            try {
                GpsWalkTracker.start(applicationContext)
            } catch (_: Throwable) {}
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_activity_stats)

        stepsRing = findViewById(R.id.stepsRing)
        stepsRing.ringColor = Color.parseColor("#16D99A") // Refined Emerald Green

        tvHistoryLog = findViewById(R.id.tvHistoryLog)
        tvLatestWorkoutSummary = findViewById(R.id.tvLatestWorkoutSummary)
        tvToggleWorkoutHistory = findViewById(R.id.tvToggleWorkoutHistory)
        tvWeight = findViewById(R.id.tvWeight)
        tvSensorStatusPill = findViewById(R.id.tvSensorStatusPill)
        tvHeroGoalPercent = findViewById(R.id.tvHeroGoalPercent)
        tvHeroRemainingSteps = findViewById(R.id.tvHeroRemainingSteps)
        tvHeroPeakWindow = findViewById(R.id.tvHeroPeakWindow)
        tvDistanceSub = findViewById(R.id.tvDistanceSub)

        tvWalkJogStatusDot = findViewById(R.id.tvWalkJogStatusDot)
        tvAutoWalkJogHeader = findViewById(R.id.tvAutoWalkJogHeader)
        tvAutoWalkJogSummaryLine = findViewById(R.id.tvAutoWalkJogSummaryLine)
        tvAvgPace = findViewById(R.id.tvAvgPace)
        tvAvgSpeed = findViewById(R.id.tvAvgSpeed)
        tvMovingTime = findViewById(R.id.tvMovingTime)
        tvStepCadence = findViewById(R.id.tvStepCadence)
        stravaPaceChart = findViewById(R.id.stravaPaceChart)
        llKmSplitsContainer = findViewById(R.id.llKmSplitsContainer)

        rowToggleDataSources = findViewById(R.id.rowToggleDataSources)
        llSecondaryDataSources = findViewById(R.id.llSecondaryDataSources)
        tvDataSourcesChevron = findViewById(R.id.tvDataSourcesChevron)
        tvDataSourcesSummary = findViewById(R.id.tvDataSourcesSummary)

        findViewById<View>(R.id.activityBackButton)?.setOnClickListener { finish() }

        findViewById<View>(R.id.cardStepsRing)?.setOnClickListener {
            val current = GoalPrefs.getStepGoal(this)
            showGoalDialog("Daily Step Goal", current) { newGoal ->
                GoalPrefs.setStepGoal(this, newGoal)
                lifecycleScope.launch {
                    refreshStepStats()
                    loadWeeklyTrend()
                }
            }
        }

        // Metric Grid 1: Calories
        findViewById<View>(R.id.btnCaloriesGoal)?.setOnClickListener {
            startActivity(Intent(this, CaloriesHistoryActivity::class.java))
        }
        findViewById<View>(R.id.btnCaloriesGoal)?.setOnLongClickListener {
            val current = GoalPrefs.getCalorieGoal(this)
            showGoalDialog("Daily Calorie Goal (kcal)", current) { newGoal ->
                GoalPrefs.setCalorieGoal(this, newGoal)
                lifecycleScope.launch { refreshStepStats() }
            }
            true
        }
        findViewById<View>(R.id.tvCaloriesBreakdownLink)?.setOnClickListener {
            startActivity(Intent(this, CaloriesHistoryActivity::class.java))
        }

        // Metric Grid 2: Distance
        findViewById<View>(R.id.btnDistanceDetail)?.setOnClickListener {
            startActivity(Intent(this, DistanceHistoryActivity::class.java))
        }

        // Running & Walking Performance: View Details
        findViewById<View>(R.id.tvOpenFullPaceHistory)?.setOnClickListener {
            if (llKmSplitsContainer.childCount > 0 && !isSplitsExpanded) {
                isSplitsExpanded = true
                llKmSplitsContainer.visibility = View.VISIBLE
                findViewById<TextView>(R.id.tvOpenFullPaceHistory)?.text = "Full Analysis ›"
            } else {
                startActivity(Intent(this, DistanceHistoryActivity::class.java))
            }
        }
        findViewById<View>(R.id.cardStravaTelemetry)?.setOnClickListener {
            startActivity(Intent(this, DistanceHistoryActivity::class.java))
        }

        // Metric Grid 3: Move Minutes
        findViewById<View>(R.id.btnMoveMinGoal)?.setOnClickListener {
            startActivity(Intent(this, MoveMinHistoryActivity::class.java))
        }
        findViewById<View>(R.id.btnMoveMinGoal)?.setOnLongClickListener {
            val current = GoalPrefs.getMoveMinGoal(this)
            showGoalDialog("Daily Move Minutes Goal", current) { newGoal ->
                GoalPrefs.setMoveMinGoal(this, newGoal)
                lifecycleScope.launch { refreshStepStats() }
            }
            true
        }
        findViewById<View>(R.id.tvMoveMinBreakdownLink)?.setOnClickListener {
            startActivity(Intent(this, MoveMinHistoryActivity::class.java))
        }

        // Metric Grid 4: Weight
        findViewById<View>(R.id.btnWeightDetail)?.setOnClickListener {
            startActivity(Intent(this, WeightHistoryActivity::class.java))
        }

        // Weekly Activity History Link
        findViewById<View>(R.id.tvWeeklyHistoryLink)?.setOnClickListener {
            startActivity(Intent(this, DistanceHistoryActivity::class.java))
        }

        // Strength & Calisthenics
        findViewById<View>(R.id.btnStartCalisthenics)?.setOnClickListener {
            startActivity(Intent(this, CalisthenicsActivity::class.java))
        }
        tvToggleWorkoutHistory.setOnClickListener {
            isWorkoutHistoryExpanded = !isWorkoutHistoryExpanded
            tvHistoryLog.visibility = if (isWorkoutHistoryExpanded) View.VISIBLE else View.GONE
            tvToggleWorkoutHistory.text = if (isWorkoutHistoryExpanded) {
                "Hide session history ▴"
            } else {
                "View session history ▾"
            }
        }

        // Collapsible Secondary Data Sources, Sensors & Reports
        rowToggleDataSources.setOnClickListener {
            isDataSourcesExpanded = !isDataSourcesExpanded
            llSecondaryDataSources.visibility = if (isDataSourcesExpanded) View.VISIBLE else View.GONE
            tvDataSourcesChevron.text = if (isDataSourcesExpanded) "Hide ▴" else "More ▾"
        }

        findViewById<View>(R.id.btnActivityReport)?.setOnClickListener {
            startActivity(Intent(this, InsightsDashboardActivity::class.java))
        }

        findViewById<Button>(R.id.btnConnectHealthConnect)?.setOnClickListener {
            if (!HealthConnectManager.isSupported(this)) {
                Toast.makeText(
                    this,
                    "Standalone hardware step sensor is active! Google Fit / Health Connect is not needed.",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }
            try {
                requestHealthPermissions.launch(HealthConnectManager.PERMISSIONS)
            } catch (_: Exception) {
                Toast.makeText(this, "Hardware sensor is active; Health Connect unavailable", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.grantActivityPermissionButton)?.setOnClickListener {
            promptPhysicalActivitySensorPermission()
        }
        tvSensorStatusPill.setOnClickListener {
            promptPhysicalActivitySensorPermission()
        }

        findViewById<View>(R.id.tvOutdoorGpsPill)?.setOnClickListener {
            promptOutdoorGpsPermission()
        }

        findViewById<Button>(R.id.grantLocationForegroundButton)?.setOnClickListener {
            requestLocationPermissions.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }

        findViewById<Button>(R.id.grantLocationBackgroundButton)?.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 102)
            }
        }

        findViewById<View>(R.id.tvOpenLocationSettings)?.setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            } catch (_: Exception) {}
        }

        checkHardwareSensorAvailability()
        applyDashboardTheme()
        ensureHardwareSensorPermissionFirst()
    }

    override fun onResume() {
        super.onResume()
        applyDashboardTheme()
        refreshLocationPermissionButtons()
        refreshHealthConnectUi()
        registerStepDetectorIfPermitted()
        refreshWeightDisplay()
        loadWeeklyTrend()
        loadWorkoutHistory()
        startStatsRefreshLoop()
    }

    override fun onPause() {
        super.onPause()
        refreshJob?.cancel()
        if (isLocalSensorRegistered) {
            try {
                sensorManager?.unregisterListener(localStepListener)
            } catch (_: Exception) {}
            isLocalSensorRegistered = false
        }
    }

    private fun applyDashboardTheme() {
        val isWhite = UiThemePrefs.isWhiteBackgroundActive(this)
        val isBlack = UiThemePrefs.isBlackBackgroundActive(this)
        val scroll = findViewById<ScrollView>(R.id.scrollPhysicalActivity)
        val bg = when {
            isWhite -> Color.parseColor("#FFFFFF")
            isBlack -> Color.parseColor("#000000")
            else -> Color.parseColor("#080D12")
        }
        window?.statusBarColor = bg
        window?.navigationBarColor = bg
        scroll?.setBackgroundColor(bg)
        if (isWhite) {
            UiThemePrefs.applyWindowAndViewTreeTheme(this, scroll)
        }
    }

    private fun checkHardwareSensorAvailability() {
        val sm = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val hasDetector = sm?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) != null
        val hasCounter = sm?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null
        isHardwareSensorAvailable = hasDetector || hasCounter
    }

    private fun ensureHardwareSensorPermissionFirst() {
        val hasPerm = hasActivityRecognitionPermission()
        updateSensorStatusUi(hasPerm)
        if (isHardwareSensorAvailable && !hasPerm && !hasAutoPromptedSensorOnOpen && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            hasAutoPromptedSensorOnOpen = true
            requestActivityPermission.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        }
    }

    private fun promptPhysicalActivitySensorPermission() {
        if (!isHardwareSensorAvailable) {
            Toast.makeText(
                this,
                "Hardware step sensor not detected on this device. Connect Google Fit below if available.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasActivityRecognitionPermission()) {
            requestActivityPermission.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        } else {
            Toast.makeText(
                this,
                "Hardware step sensor is active and tracking automatically.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun hasActivityRecognitionPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun updateSensorStatusUi(hasPerm: Boolean) {
        val btn = findViewById<Button>(R.id.grantActivityPermissionButton)
        when {
            !isHardwareSensorAvailable -> {
                btn?.visibility = View.GONE
                tvSensorStatusPill.text = "○ Sensor Unavailable"
                tvSensorStatusPill.setTextColor(Color.parseColor("#8995A3"))
            }
            hasPerm -> {
                btn?.visibility = View.GONE
                tvSensorStatusPill.text = "● Sensor Active"
                tvSensorStatusPill.setTextColor(Color.parseColor("#16D99A"))
            }
            else -> {
                btn?.visibility = View.VISIBLE
                tvSensorStatusPill.text = "⚠ Enable Sensor"
                tvSensorStatusPill.setTextColor(Color.parseColor("#F59E0B"))
            }
        }
    }

    private fun registerStepDetectorIfPermitted() {
        val hasPerm = hasActivityRecognitionPermission()
        updateSensorStatusUi(hasPerm)

        if (isHardwareSensorAvailable && hasPerm && !isLocalSensorRegistered) {
            try {
                val sm = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
                val stepDetector = sm?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
                val stepCounter = sm?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
                if (sm != null) {
                    if (stepDetector != null) {
                        sm.registerListener(localStepListener, stepDetector, SensorManager.SENSOR_DELAY_NORMAL)
                        isLocalSensorRegistered = true
                    } else if (stepCounter != null) {
                        sm.registerListener(localStepListener, stepCounter, SensorManager.SENSOR_DELAY_NORMAL)
                        isLocalSensorRegistered = true
                    }
                    sensorManager = sm
                }
            } catch (_: Exception) {}
        }
    }

    private fun promptOutdoorGpsPermission() {
        val hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasBg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else true

        if (!hasFine && !hasCoarse) {
            requestLocationPermissions.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        } else if (!hasBg && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            AlertDialog.Builder(this)
                .setTitle("Outdoor Walk GPS Active")
                .setMessage("Foreground GPS is enabled for outdoor walks. For pocket/screen-off outdoor walk tracking, allow 'All the time' Background Location.")
                .setPositiveButton("Allow Background GPS") { _, _ ->
                    requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 102)
                }
                .setNegativeButton("Keep Foreground Only", null)
                .show()
        } else {
            if (!GpsWalkTracker.isTracking) {
                try {
                    GpsWalkTracker.start(applicationContext)
                } catch (_: Throwable) {}
            }
            Toast.makeText(
                this,
                "📍 Outdoor GPS Ready — Automatically verifies satellite distance when you walk outside.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshLocationPermissionButtons()
        if (GpsWalkTracker.hasPermission(this) && !GpsWalkTracker.isTracking) {
            try {
                GpsWalkTracker.start(applicationContext)
            } catch (_: Throwable) {}
        }
    }

    private fun refreshLocationPermissionButtons() {
        val hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasFg = hasFine || hasCoarse
        val hasBg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else true

        val gpsPill = findViewById<TextView>(R.id.tvOutdoorGpsPill)
        when {
            hasFg && hasBg -> {
                gpsPill?.text = if (GpsWalkTracker.isTracking) "📍 GPS Live" else "📍 GPS Ready"
                gpsPill?.setTextColor(Color.parseColor("#16D99A"))
            }
            hasFg -> {
                gpsPill?.text = "📍 GPS On"
                gpsPill?.setTextColor(Color.parseColor("#16D99A"))
            }
            else -> {
                gpsPill?.text = "📍 Enable GPS"
                gpsPill?.setTextColor(Color.parseColor("#27C7C9"))
            }
        }

        findViewById<View>(R.id.tvGpsExplainer)?.visibility = if (hasFg && hasBg) View.GONE else View.VISIBLE
        findViewById<View>(R.id.grantLocationForegroundButton)?.visibility = if (hasFg) View.GONE else View.VISIBLE
        findViewById<View>(R.id.grantLocationBackgroundButton)?.visibility = if (hasFg && !hasBg) View.VISIBLE else View.GONE
        findViewById<View>(R.id.tvOpenLocationSettings)?.visibility = if (hasFg && hasBg) View.GONE else View.VISIBLE
    }

    private fun refreshHealthConnectUi() {
        val card = findViewById<View>(R.id.cardHealthConnectSync) ?: return
        val statusTv = findViewById<TextView>(R.id.tvHealthConnectStatus)
        val btn = findViewById<Button>(R.id.btnConnectHealthConnect)

        if (!HealthConnectManager.isSupported(this)) {
            card.visibility = View.GONE
            tvDataSourcesSummary.text = "Focus & Activity Report • Hardware Sensor • Optional GPS"
            return
        }
        card.visibility = View.VISIBLE
        lifecycleScope.launch {
            val hasPerms = withContext(Dispatchers.IO) {
                try {
                    HealthConnectManager.hasPermissions(this@ActivityStatsActivity)
                } catch (_: Exception) {
                    false
                }
            }
            if (hasPerms) {
                statusTv?.text = "Synced ✓"
                statusTv?.setTextColor(Color.parseColor("#16D99A"))
                btn?.text = "Sync External Data Now"
                tvDataSourcesSummary.text = "Focus Report • Google Fit Synced ✓ • Optional GPS"
            } else {
                statusTv?.text = "Disconnected"
                statusTv?.setTextColor(Color.parseColor("#8995A3"))
                btn?.text = "Connect Google Fit (Optional)"
                tvDataSourcesSummary.text = "Focus Report • Google Fit (Optional) • GPS"
            }
        }
    }

    private fun startStatsRefreshLoop() {
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            while (isActive) {
                refreshStepStats()
                delay(STATS_REFRESH_MS)
            }
        }
    }

    private suspend fun refreshStepStats() {
        val ctx = this@ActivityStatsActivity
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val todayStartMs = cal.timeInMillis

        val telemetry = withContext(Dispatchers.IO) {
            StepPrefs.rolloverCheck(ctx)
            var steps = StepPrefs.getDailySteps(ctx)

            if (HealthConnectManager.isSupported(ctx)) {
                try {
                    if (HealthConnectManager.hasStepsPermission(ctx)) {
                        val hcSteps = (HealthConnectManager.getTodaySteps(ctx) ?: 0L).toInt()
                        if (hcSteps > steps) {
                            steps = hcSteps
                            StepPrefs.setDailySteps(ctx, steps)
                        }
                    }
                } catch (_: Exception) {}
            }
            ActivityTelemetryEngine.loadDayTelemetry(ctx, todayStartMs)
        }

        val stepGoal = GoalPrefs.getStepGoal(ctx).coerceAtLeast(100)
        val calGoal = GoalPrefs.getCalorieGoal(ctx).coerceAtLeast(50)
        val moveGoal = GoalPrefs.getMoveMinGoal(ctx).coerceAtLeast(5)

        val steps = telemetry.totalSteps
        val pct = ((steps * 100f) / stepGoal.toFloat()).roundToInt()

        findViewById<TextView>(R.id.tvSteps)?.text = formatSteps(steps)
        findViewById<TextView>(R.id.tvStepGoalSub)?.text = "of ${formatSteps(stepGoal)}"
        stepsRing.progress = (steps.toFloat() / stepGoal.toFloat()).coerceIn(0f, 1f)

        // Hero summary states (0%, partial, goal achieved)
        when {
            steps <= 0 -> {
                tvHeroGoalPercent.text = "0% of daily goal"
                tvHeroGoalPercent.setTextColor(Color.parseColor("#8995A3"))
                tvHeroRemainingSteps.text = "${formatSteps(stepGoal)} steps to reach goal"
            }
            steps >= stepGoal -> {
                tvHeroGoalPercent.text = "$pct% • Daily Goal Reached"
                tvHeroGoalPercent.setTextColor(Color.parseColor("#F59E0B")) // Meaningful achievement orange
                val extra = steps - stepGoal
                tvHeroRemainingSteps.text = "+${formatSteps(extra)} steps beyond goal"
            }
            else -> {
                tvHeroGoalPercent.text = "$pct% of daily goal"
                tvHeroGoalPercent.setTextColor(Color.parseColor("#16D99A"))
                val rem = (stepGoal - steps).coerceAtLeast(0)
                tvHeroRemainingSteps.text = "${formatSteps(rem)} steps remaining today"
            }
        }

        val peakW = telemetry.activeWindows.firstOrNull()
        if (peakW != null && peakW.steps > 0) {
            tvHeroPeakWindow.text = String.format(
                Locale.US,
                "Peak %s: %.2f km • %dm • %d kcal",
                peakW.windowRangeLabel,
                peakW.distanceKm,
                peakW.moveMinutes,
                peakW.caloriesKcal
            )
            tvDistanceSub.text = "Peak: ${peakW.windowRangeLabel}"
        } else {
            tvHeroPeakWindow.text = "Peak window: No movement logged yet"
            tvDistanceSub.text = "Hourly windows & pace"
        }

        findViewById<TextView>(R.id.tvCalories)?.text = formatSteps(telemetry.totalCaloriesKcal)
        findViewById<ProgressBar>(R.id.pbCalories)?.progress =
            ((telemetry.totalCaloriesKcal * 100) / calGoal).coerceIn(0, 100)

        findViewById<TextView>(R.id.tvDistance)?.text = String.format(Locale.US, "%.2f", telemetry.totalDistanceKm)

        findViewById<TextView>(R.id.tvMoveMin)?.text = telemetry.totalMoveMinutes.toString()
        findViewById<ProgressBar>(R.id.pbMoveMin)?.progress =
            ((telemetry.totalMoveMinutes * 100) / moveGoal).coerceIn(0, 100)

        renderAutoStravaCard(telemetry)
    }

    private fun renderAutoStravaCard(telemetry: ActivityTelemetryEngine.DayUnifiedTelemetry) {
        val autoSession = telemetry.autoDetectedSessions.firstOrNull()
        val viewDetailsTv = findViewById<TextView>(R.id.tvOpenFullPaceHistory)

        if (autoSession != null && autoSession.splits.isNotEmpty()) {
            tvWalkJogStatusDot.setTextColor(Color.parseColor("#16D99A"))
            tvAutoWalkJogHeader.text = autoSession.title
            val m = autoSession.durationSeconds / 60
            val s = autoSession.durationSeconds % 60
            tvAutoWalkJogSummaryLine.text = String.format(
                Locale.US,
                "%s  •  %.2f km  •  %d kcal",
                autoSession.timeRangeLabel,
                autoSession.distanceKm,
                autoSession.caloriesKcal
            )
            tvAvgPace.text = StravaPaceChartView.formatPace(autoSession.avgPaceSecPerKm)
            tvAvgSpeed.text = String.format(Locale.US, "%.1f", autoSession.avgSpeedKmh)
            tvMovingTime.text = String.format(Locale.US, "%dm %02ds", m, s)
            tvStepCadence.text = "${autoSession.cadenceSpm} spm"

            stravaPaceChart.visibility = View.VISIBLE
            stravaPaceChart.setSplits(
                autoSession.splits,
                subtitle = "PACE CURVE (min/km) • ${autoSession.splits.size} SPLITS"
            )
            renderKmSplitRows(autoSession.splits, autoSession.avgPaceSecPerKm)
            llKmSplitsContainer.visibility = if (isSplitsExpanded) View.VISIBLE else View.GONE
            viewDetailsTv?.text = if (isSplitsExpanded) "Full Analysis ›" else "View Details ›"
        } else {
            // Professional compact empty state — hide empty chart and splits so zero screen space is wasted
            tvWalkJogStatusDot.setTextColor(Color.parseColor("#8995A3"))
            tvAutoWalkJogHeader.text = "Walking & Running Performance"
            tvAutoWalkJogSummaryLine.text = if (telemetry.totalSteps > 0) {
                "Light intermittent movement today • Walk continuously for 1+ min to generate pace splits"
            } else {
                "No continuous walk or run recorded today • Auto-detects pace, speed & splits as you move"
            }
            tvAvgPace.text = if (telemetry.avgPaceSecPerKm > 0f && telemetry.totalDistanceKm >= 0.05) {
                StravaPaceChartView.formatPace(telemetry.avgPaceSecPerKm)
            } else {
                "—:—"
            }
            tvAvgSpeed.text = if (telemetry.totalDistanceKm >= 0.05) {
                String.format(Locale.US, "%.1f", telemetry.avgSpeedKmh)
            } else {
                "0.0"
            }
            tvMovingTime.text = if (telemetry.totalMoveMinutes > 0) "${telemetry.totalMoveMinutes}m 00s" else "0m 00s"
            tvStepCadence.text = if (telemetry.totalSteps > 0) "${telemetry.cadenceSpm} spm" else "— spm"

            stravaPaceChart.visibility = View.GONE
            llKmSplitsContainer.removeAllViews()
            llKmSplitsContainer.visibility = View.GONE
            viewDetailsTv?.text = "View Details ›"
        }
    }

    private fun renderKmSplitRows(splits: List<StravaPaceChartView.SplitPoint>, avgPaceSec: Float) {
        llKmSplitsContainer.removeAllViews()
        if (splits.isEmpty()) return

        val density = resources.displayMetrics.density
        val isLight = UiThemePrefs.isWhiteBackgroundActive(this)
        val textPrimary = if (isLight) Color.parseColor("#0F172A") else Color.parseColor("#F1F5F9")
        val textSecondary = if (isLight) Color.parseColor("#64748B") else Color.parseColor("#8995A3")

        val fastestPace = splits.minOf { it.paceSecondsPerKm }
        val slowestPace = splits.maxOf { it.paceSecondsPerKm }.coerceAtLeast(fastestPace + 10f)

        splits.take(5).forEach { split ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (5 * density).toInt() }
            }

            val tvKm = TextView(this).apply {
                text = split.splitLabel
                textSize = 11.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(textSecondary)
                layoutParams = LinearLayout.LayoutParams((50 * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            row.addView(tvKm)

            val barTrack = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, (12 * density).toInt(), 1f).apply {
                    marginEnd = (8 * density).toInt()
                }
                background = GradientDrawable().apply {
                    cornerRadius = 6f * density
                    setColor(if (isLight) Color.parseColor("#E2E8F0") else Color.parseColor("#17212B"))
                }
            }

            val isFastest = split.paceSecondsPerKm <= fastestPace + 0.5f
            val speedRatio = ((slowestPace - split.paceSecondsPerKm) / (slowestPace - fastestPace).coerceAtLeast(1f))
                .coerceIn(0.35f, 1f)
            val barFill = View(this).apply {
                layoutParams = FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT)
                background = GradientDrawable().apply {
                    cornerRadius = 6f * density
                    setColor(if (isFastest) Color.parseColor("#16D99A") else Color.parseColor("#27C7C9"))
                }
            }
            barTrack.addView(barFill)
            barTrack.post {
                val targetW = (barTrack.width * speedRatio).roundToInt().coerceAtLeast((20 * density).toInt())
                barFill.layoutParams = FrameLayout.LayoutParams(targetW, ViewGroup.LayoutParams.MATCH_PARENT)
            }
            row.addView(barTrack)

            val tvPace = TextView(this).apply {
                text = "${StravaPaceChartView.formatPace(split.paceSecondsPerKm)} /km"
                textSize = 11.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(textPrimary)
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams((64 * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            row.addView(tvPace)

            val deltaSec = (split.paceSecondsPerKm - avgPaceSec).roundToInt()
            val deltaSign = if (deltaSec <= 0) "−" else "+"
            val absDelta = abs(deltaSec)
            val tvDelta = TextView(this).apply {
                text = String.format(Locale.US, "%s%d:%02d", deltaSign, absDelta / 60, absDelta % 60)
                textSize = 10.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (deltaSec <= 0) Color.parseColor("#16D99A") else Color.parseColor("#8995A3"))
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams((44 * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            row.addView(tvDelta)

            llKmSplitsContainer.addView(row)
        }
    }

    private fun showGoalDialog(title: String, current: Int, onSave: (Int) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(current.toString())
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val v = input.text.toString().toIntOrNull()
                if (v != null && v > 0) {
                    onSave(v)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshWeightDisplay() {
        lifecycleScope.launch {
            val latest = withContext(Dispatchers.IO) {
                WeightHelper.latestWeight(this@ActivityStatsActivity) ?: WeightPrefs.getWeight(this@ActivityStatsActivity)
            }
            tvWeight.text = if (latest > 0f) WeightHelper.formatKg(latest) else "-- kg"
        }
    }

    private fun loadWeeklyTrend() {
        val chart = findViewById<SimpleBarChartView>(R.id.weeklyStepsChart) ?: return
        val tvWeeklyEmpty = findViewById<TextView>(R.id.tvWeeklyEmptyState)
        val tvStreak = findViewById<TextView>(R.id.tvStreak)
        val tvBest = findViewById<TextView>(R.id.tvPersonalBest)

        lifecycleScope.launch {
            val today = LocalDate.now()

            val daySteps = withContext(Dispatchers.IO) {
                val ctx = this@ActivityStatsActivity
                StepPrefs.rolloverCheck(ctx)
                if (HealthConnectManager.isSupported(ctx)) {
                    try {
                        if (HealthConnectManager.hasStepsPermission(ctx)) {
                            HealthConnectManager.syncPastDaysSteps(ctx, 7)
                        }
                    } catch (_: Exception) {}
                }
                (6 downTo 0).map { offset ->
                    val d = today.minusDays(offset.toLong())
                    val cal = Calendar.getInstance().apply {
                        set(d.year, d.monthValue - 1, d.dayOfMonth, 0, 0, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    val count = ActivityTelemetryEngine.getHistoricalStepsForDay(ctx, cal.timeInMillis)
                    d to count
                }
            }

            val goal = GoalPrefs.getStepGoal(this@ActivityStatsActivity).coerceAtLeast(100)
            val totalWeekSteps = daySteps.sumOf { it.second }

            if (totalWeekSteps <= 0) {
                // Hide giant blank chart area when all 7 days have 0 steps
                chart.visibility = View.GONE
                tvWeeklyEmpty?.visibility = View.VISIBLE
            } else {
                chart.visibility = View.VISIBLE
                tvWeeklyEmpty?.visibility = View.GONE
                val entries = daySteps.mapIndexed { idx, (d, count) ->
                    val label = if (idx == 6) "Today" else d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                    val disp = when {
                        count >= 1000 -> String.format(Locale.US, "%.1fk", count / 1000f)
                        count > 0 -> count.toString()
                        else -> ""
                    }
                    val color = when {
                        count >= goal -> Color.parseColor("#16D99A") // Goal met: Emerald
                        idx == 6 -> Color.parseColor("#16D99A")      // Today: Emerald
                        else -> Color.parseColor("#27C7C9")          // Active past day: Teal
                    }
                    SimpleBarChartView.BarEntry(label, count.toFloat(), disp, color)
                }
                chart.entries = entries
            }

            var streak = 0
            for (i in daySteps.indices.reversed()) {
                if (daySteps[i].second >= goal) streak++ else if (i < 6) break
            }
            if (streak > 0) {
                tvStreak?.text = "🔥 $streak-day goal streak"
                tvStreak?.setTextColor(Color.parseColor("#F59E0B"))
            } else {
                tvStreak?.text = "${formatSteps(totalWeekSteps)} steps this week"
                tvStreak?.setTextColor(Color.parseColor("#8995A3"))
            }

            val best = daySteps.maxByOrNull { it.second }
            if (best != null && best.second > 0) {
                val dayName = best.first.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                val avgSteps = totalWeekSteps / 7
                tvBest?.text = "Best: ${formatSteps(best.second)} ($dayName)  •  Daily avg: ${formatSteps(avgSteps)}"
            } else {
                tvBest?.text = "Personal best this week: —"
            }
        }
    }

    private fun formatSteps(steps: Int): String =
        NumberFormat.getIntegerInstance(Locale.US).format(steps)

    private fun loadWorkoutHistory() {
        lifecycleScope.launch {
            val logs = withContext(Dispatchers.IO) {
                try {
                    AppDatabase.getDatabase(this@ActivityStatsActivity)
                        .exerciseDao()
                        .getAllLogs()
                        .sortedByDescending { it.timestamp }
                } catch (_: Exception) {
                    emptyList()
                }
            }

            if (logs.isEmpty()) {
                tvLatestWorkoutSummary.text = "No sessions logged yet • Sets, reps & holds"
                tvToggleWorkoutHistory.visibility = View.GONE
                tvHistoryLog.visibility = View.GONE
            } else {
                val sdf = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
                val latest = logs.first()
                val latestDetail = buildString {
                    if (latest.reps > 0) append("${latest.reps} reps")
                    if (latest.durationSeconds > 0) {
                        if (isNotEmpty()) append(" · ")
                        append("${latest.durationSeconds}s hold")
                    }
                }
                tvLatestWorkoutSummary.text =
                    "Latest: ${latest.exerciseType} ($latestDetail) • ${sdf.format(Date(latest.timestamp))}"

                tvToggleWorkoutHistory.visibility = View.VISIBLE
                tvToggleWorkoutHistory.text = if (isWorkoutHistoryExpanded) {
                    "Hide session history (${logs.size}) ▴"
                } else {
                    "View session history (${logs.size}) ▾"
                }

                tvHistoryLog.text = logs.take(12).joinToString("\n") { log ->
                    val detail = buildString {
                        if (log.reps > 0) append("${log.reps} reps")
                        if (log.durationSeconds > 0) {
                            if (isNotEmpty()) append(" · ")
                            append("${log.durationSeconds}s hold")
                        }
                    }
                    "• ${sdf.format(Date(log.timestamp))} — ${log.exerciseType}: $detail (${log.notes})"
                }
                tvHistoryLog.visibility = if (isWorkoutHistoryExpanded) View.VISIBLE else View.GONE
            }
        }
    }
}
