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
    private lateinit var tvWeight: TextView
    private lateinit var stepsRing: RingProgressView
    private lateinit var tvSensorStatusPill: TextView

    // Auto-Detected Strava Walk/Jog Telemetry Views
    private lateinit var tvAutoWalkJogHeader: TextView
    private lateinit var tvAutoWalkJogSummaryLine: TextView
    private lateinit var tvAvgPace: TextView
    private lateinit var tvAvgSpeed: TextView
    private lateinit var tvMovingTime: TextView
    private lateinit var tvStepCadence: TextView
    private lateinit var stravaPaceChart: StravaPaceChartView
    private lateinit var llKmSplitsContainer: LinearLayout

    private var isLocalSensorRegistered = false
    private var sensorManager: SensorManager? = null
    private var lastCounterBaseline: Int = -1
    private var hasAutoPromptedSensorOnOpen = false

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_activity_stats)

        stepsRing = findViewById(R.id.stepsRing)
        tvHistoryLog = findViewById(R.id.tvHistoryLog)
        tvWeight = findViewById(R.id.tvWeight)
        tvSensorStatusPill = findViewById(R.id.tvSensorStatusPill)

        tvAutoWalkJogHeader = findViewById(R.id.tvAutoWalkJogHeader)
        tvAutoWalkJogSummaryLine = findViewById(R.id.tvAutoWalkJogSummaryLine)
        tvAvgPace = findViewById(R.id.tvAvgPace)
        tvAvgSpeed = findViewById(R.id.tvAvgSpeed)
        tvMovingTime = findViewById(R.id.tvMovingTime)
        tvStepCadence = findViewById(R.id.tvStepCadence)
        stravaPaceChart = findViewById(R.id.stravaPaceChart)
        llKmSplitsContainer = findViewById(R.id.llKmSplitsContainer)

        findViewById<View>(R.id.activityBackButton)?.setOnClickListener { finish() }

        findViewById<View>(R.id.cardStepsRing)?.setOnClickListener {
            val current = GoalPrefs.getStepGoal(this)
            showGoalDialog("Daily Step Goal", current) { newGoal ->
                GoalPrefs.setStepGoal(this, newGoal)
                lifecycleScope.launch { refreshStepStats() }
            }
        }

        // Tapping Calories card opens the new Calorie Curve Breakdown (long-press sets goal)
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

        findViewById<View>(R.id.btnDistanceDetail)?.setOnClickListener {
            startActivity(Intent(this, DistanceHistoryActivity::class.java))
        }

        findViewById<View>(R.id.tvOpenFullPaceHistory)?.setOnClickListener {
            startActivity(Intent(this, DistanceHistoryActivity::class.java))
        }
        findViewById<View>(R.id.cardStravaTelemetry)?.setOnClickListener {
            startActivity(Intent(this, DistanceHistoryActivity::class.java))
        }

        // Tapping Move Minutes card opens the new Move Minutes Curve Breakdown (long-press sets goal)
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

        findViewById<View>(R.id.btnWeightDetail)?.setOnClickListener {
            startActivity(Intent(this, WeightHistoryActivity::class.java))
        }

        findViewById<View>(R.id.btnActivityReport)?.setOnClickListener {
            startActivity(Intent(this, InsightsDashboardActivity::class.java))
        }

        findViewById<View>(R.id.btnStartCalisthenics)?.setOnClickListener {
            startActivity(Intent(this, CalisthenicsActivity::class.java))
        }

        findViewById<Button>(R.id.btnConnectHealthConnect)?.setOnClickListener {
            if (!HealthConnectManager.isSupported(this)) {
                Toast.makeText(
                    this,
                    "Hardware step sensor is active! Google Fit / Health Connect is not needed on this device.",
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

        findViewById<Button>(R.id.grantLocationForegroundButton)?.setOnClickListener {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                101
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

        UiThemePrefs.applyToActivity(this)

        // PRIORITY #1: Automatically ask for Physical Activity Hardware Sensor permission on open!
        ensureHardwareSensorPermissionFirst()
    }

    override fun onResume() {
        super.onResume()
        UiThemePrefs.applyToActivity(this)
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

    /**
     * Immediately prompts for Android's ACTIVITY_RECOGNITION hardware sensor permission when
     * opening Physical Activity if not yet granted, so standalone tracking works without Google Fit.
     */
    private fun ensureHardwareSensorPermissionFirst() {
        val hasPerm = hasActivityRecognitionPermission()
        updateSensorStatusUi(hasPerm)
        if (!hasPerm && !hasAutoPromptedSensorOnOpen && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            hasAutoPromptedSensorOnOpen = true
            requestActivityPermission.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        }
    }

    private fun promptPhysicalActivitySensorPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (!hasActivityRecognitionPermission()) {
                requestActivityPermission.launch(Manifest.permission.ACTIVITY_RECOGNITION)
            } else {
                Toast.makeText(
                    this,
                    "Hardware Step Sensor is active and reading your movement directly!",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun hasActivityRecognitionPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun updateSensorStatusUi(hasPerm: Boolean) {
        findViewById<Button>(R.id.grantActivityPermissionButton)?.visibility =
            if (hasPerm) View.GONE else View.VISIBLE
        if (hasPerm) {
            tvSensorStatusPill.text = "● Hardware Sensor Active"
            tvSensorStatusPill.setTextColor(Color.parseColor("#00E676"))
        } else {
            tvSensorStatusPill.text = "⚠ Tap to Enable Sensor"
            tvSensorStatusPill.setTextColor(Color.parseColor("#FFB74D"))
        }
    }

    private fun registerStepDetectorIfPermitted() {
        val hasPerm = hasActivityRecognitionPermission()
        updateSensorStatusUi(hasPerm)

        if (hasPerm && !isLocalSensorRegistered) {
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

    private fun refreshLocationPermissionButtons() {
        val hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasBg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else true

        findViewById<View>(R.id.tvGpsExplainer)?.visibility = if (hasFine && hasBg) View.GONE else View.VISIBLE
        findViewById<View>(R.id.grantLocationForegroundButton)?.visibility = if (hasFine) View.GONE else View.VISIBLE
        findViewById<View>(R.id.grantLocationBackgroundButton)?.visibility = if (hasFine && !hasBg) View.VISIBLE else View.GONE
        findViewById<View>(R.id.tvOpenLocationSettings)?.visibility = if (hasFine && hasBg) View.GONE else View.VISIBLE
    }

    private fun refreshHealthConnectUi() {
        val card = findViewById<View>(R.id.cardHealthConnectSync) ?: return
        val statusTv = findViewById<TextView>(R.id.tvHealthConnectStatus)
        val btn = findViewById<Button>(R.id.btnConnectHealthConnect)

        if (!HealthConnectManager.isSupported(this)) {
            card.visibility = View.GONE
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
                statusTv?.setTextColor(Color.parseColor("#00E676"))
                btn?.text = "Sync External Data Now"
            } else {
                statusTv?.text = "Optional (Sensor Active)"
                statusTv?.setTextColor(Color.parseColor("#94A3B8"))
                btn?.text = "Connect Google Fit (Optional)"
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

        findViewById<TextView>(R.id.tvSteps)?.text = formatSteps(telemetry.totalSteps)
        findViewById<TextView>(R.id.tvStepGoalSub)?.text = "of ${formatSteps(stepGoal)} goal"
        stepsRing.progress = (telemetry.totalSteps.toFloat() / stepGoal.toFloat()).coerceIn(0f, 1f)

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
        if (autoSession != null) {
            tvAutoWalkJogHeader.text = autoSession.title
            val m = autoSession.durationSeconds / 60
            val s = autoSession.durationSeconds % 60
            tvAutoWalkJogSummaryLine.text = String.format(
                Locale.US,
                "%s  •  %.2f km  •  Pace %s/km  •  %dm %02ds  •  %d kcal",
                autoSession.timeRangeLabel,
                autoSession.distanceKm,
                StravaPaceChartView.formatPace(autoSession.avgPaceSecPerKm),
                m,
                s,
                autoSession.caloriesKcal
            )
            tvAvgPace.text = StravaPaceChartView.formatPace(autoSession.avgPaceSecPerKm)
            tvAvgSpeed.text = String.format(Locale.US, "%.1f", autoSession.avgSpeedKmh)
            tvMovingTime.text = String.format(Locale.US, "%dm %02ds", m, s)
            tvStepCadence.text = "${autoSession.cadenceSpm} spm"

            stravaPaceChart.setSplits(
                autoSession.splits,
                subtitle = "AUTO-DETECTED STRAVA PACE CURVE (min/km) & SPLITS"
            )
            renderKmSplitRows(autoSession.splits, autoSession.avgPaceSecPerKm)
        } else {
            tvAutoWalkJogHeader.text = "Auto-Detected Continuous Walk / Jog"
            tvAutoWalkJogSummaryLine.text =
                "Automatic sensor tracking active • Continuous walks & jogs appear here automatically"
            tvAvgPace.text = if (telemetry.avgPaceSecPerKm > 0f) StravaPaceChartView.formatPace(telemetry.avgPaceSecPerKm) else "—:—"
            tvAvgSpeed.text = String.format(Locale.US, "%.1f", telemetry.avgSpeedKmh)
            tvMovingTime.text = "${telemetry.totalMoveMinutes}m 00s"
            tvStepCadence.text = "${telemetry.cadenceSpm} spm"

            stravaPaceChart.setSplits(emptyList(), subtitle = "AUTO-DETECTED PACE CURVE (min/km)")
            llKmSplitsContainer.removeAllViews()
        }
    }

    private fun renderKmSplitRows(splits: List<StravaPaceChartView.SplitPoint>, avgPaceSec: Float) {
        llKmSplitsContainer.removeAllViews()
        if (splits.isEmpty()) return

        val density = resources.displayMetrics.density
        val isLight = UiThemePrefs.isWhiteBackgroundActive(this)
        val textPrimary = if (isLight) Color.parseColor("#0F172A") else Color.WHITE

        val fastestPace = splits.minOf { it.paceSecondsPerKm }
        val slowestPace = splits.maxOf { it.paceSecondsPerKm }.coerceAtLeast(fastestPace + 10f)

        splits.take(5).forEach { split ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (6 * density).toInt() }
            }

            val tvKm = TextView(this).apply {
                text = split.splitLabel
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(textPrimary)
                layoutParams = LinearLayout.LayoutParams((54 * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            row.addView(tvKm)

            val barTrack = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, (16 * density).toInt(), 1f).apply {
                    marginEnd = (8 * density).toInt()
                }
                background = GradientDrawable().apply {
                    cornerRadius = 8f * density
                    setColor(if (isLight) Color.parseColor("#E2E8F0") else Color.parseColor("#1E293B"))
                }
            }

            val isFastest = split.paceSecondsPerKm <= fastestPace + 0.5f
            val speedRatio = ((slowestPace - split.paceSecondsPerKm) / (slowestPace - fastestPace).coerceAtLeast(1f))
                .coerceIn(0.35f, 1f)
            val barFill = View(this).apply {
                layoutParams = FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT)
                background = GradientDrawable().apply {
                    cornerRadius = 8f * density
                    setColor(if (isFastest) Color.parseColor("#10B981") else Color.parseColor("#FC4C02"))
                }
            }
            barTrack.addView(barFill)
            barTrack.post {
                val targetW = (barTrack.width * speedRatio).roundToInt().coerceAtLeast((24 * density).toInt())
                barFill.layoutParams = FrameLayout.LayoutParams(targetW, ViewGroup.LayoutParams.MATCH_PARENT)
            }
            row.addView(barTrack)

            val tvPace = TextView(this).apply {
                text = "${StravaPaceChartView.formatPace(split.paceSecondsPerKm)} /km"
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(textPrimary)
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams((68 * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            row.addView(tvPace)

            val deltaSec = (split.paceSecondsPerKm - avgPaceSec).roundToInt()
            val deltaSign = if (deltaSec <= 0) "−" else "+"
            val absDelta = abs(deltaSec)
            val tvDelta = TextView(this).apply {
                text = String.format(Locale.US, "%s%d:%02d", deltaSign, absDelta / 60, absDelta % 60)
                textSize = 11f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (deltaSec <= 0) Color.parseColor("#10B981") else Color.parseColor("#FC4C02"))
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams((46 * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
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
        val tvStreak = findViewById<TextView>(R.id.tvStreak)
        val tvBest = findViewById<TextView>(R.id.tvPersonalBest)

        lifecycleScope.launch {
            val today = LocalDate.now()
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

            val daySteps = withContext(Dispatchers.IO) {
                StepPrefs.rolloverCheck(this@ActivityStatsActivity)
                val todayCount = StepPrefs.getDailySteps(this@ActivityStatsActivity)
                (6 downTo 0).map { offset ->
                    val d = today.minusDays(offset.toLong())
                    val cal = Calendar.getInstance().apply {
                        set(d.year, d.monthValue - 1, d.dayOfMonth, 0, 0, 0)
                    }
                    val key = fmt.format(cal.time)
                    val count = if (offset == 0) {
                        todayCount
                    } else {
                        StepHistoryManager.getStepsForDay(this@ActivityStatsActivity, key) ?: 0
                    }
                    d to count
                }
            }

            val entries = daySteps.mapIndexed { idx, (d, count) ->
                val label = if (idx == 6) "Today" else d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                val disp = if (count >= 1000) String.format(Locale.US, "%.1fk", count / 1000f) else count.toString()
                val color = if (idx == 6) Color.parseColor("#00E676") else Color.parseColor("#00E5FF")
                SimpleBarChartView.BarEntry(label, count.toFloat(), disp, color)
            }
            chart.entries = entries

            val goal = GoalPrefs.getStepGoal(this@ActivityStatsActivity)
            var streak = 0
            for (i in daySteps.indices.reversed()) {
                if (daySteps[i].second >= goal) streak++ else if (i < 6) break
            }
            tvStreak?.text = "🔥 $streak-day streak"

            val best = daySteps.maxByOrNull { it.second }
            if (best != null && best.second > 0) {
                val dayName = best.first.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
                tvBest?.text = "Personal best this week: ${formatSteps(best.second)} steps ($dayName)"
            } else {
                tvBest?.text = "Personal best this week: —"
            }
        }
    }

    private fun formatSteps(steps: Int): String =
        NumberFormat.getIntegerInstance(Locale.US).format(steps)

    private fun loadWorkoutHistory() {
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                try {
                    val logs = AppDatabase.getDatabase(this@ActivityStatsActivity).exerciseDao().getAllLogs()
                    if (logs.isEmpty()) {
                        "No calisthenics workouts logged yet. Tap 'LOG CALISTHENICS SESSION' above to start!"
                    } else {
                        val sdf = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
                        logs.sortedByDescending { it.timestamp }
                            .take(12)
                            .joinToString("\n") { log ->
                                val detail = buildString {
                                    if (log.reps > 0) append("${log.reps} reps")
                                    if (log.durationSeconds > 0) {
                                        if (isNotEmpty()) append(" · ")
                                        append("${log.durationSeconds}s hold")
                                    }
                                }
                                "• ${sdf.format(Date(log.timestamp))} — ${log.exerciseType}: $detail (${log.notes})"
                            }
                    }
                } catch (_: Exception) {
                    "Unable to load workout history."
                }
            }
            tvHistoryLog.text = text
        }
    }
}
