package com.focusguardpro.app

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class DistanceHistoryActivity : AppCompatActivity() {

    enum class Tab { DAY, WEEK, MONTH }

    private var currentTab = Tab.DAY
    private val cursor: Calendar = Calendar.getInstance()

    private lateinit var tabDay: TextView
    private lateinit var tabWeek: TextView
    private lateinit var tabMonth: TextView
    private lateinit var rangeLabel: TextView
    private lateinit var totalText: TextView
    private lateinit var tvSyncedMoveMinPill: TextView
    private lateinit var tvSyncedCaloriesPill: TextView
    private lateinit var curveView: UnifiedActivityCurveView
    private lateinit var llSyncedWindowsSummary: LinearLayout
    private lateinit var tvAutoStravaTitle: TextView
    private lateinit var tvAutoStravaSubtitle: TextView
    private lateinit var distancePaceChart: StravaPaceChartView
    private lateinit var llAutoStravaSplits: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_distance_history)

        tabDay = findViewById(R.id.tabDay)
        tabWeek = findViewById(R.id.tabWeek)
        tabMonth = findViewById(R.id.tabMonth)
        rangeLabel = findViewById(R.id.tvRangeLabel)
        totalText = findViewById(R.id.tvTotalDistance)
        tvSyncedMoveMinPill = findViewById(R.id.tvSyncedMoveMinPill)
        tvSyncedCaloriesPill = findViewById(R.id.tvSyncedCaloriesPill)
        curveView = findViewById(R.id.distanceUnifiedCurveView)
        llSyncedWindowsSummary = findViewById(R.id.llSyncedWindowsSummary)
        tvAutoStravaTitle = findViewById(R.id.tvAutoStravaTitle)
        tvAutoStravaSubtitle = findViewById(R.id.tvAutoStravaSubtitle)
        distancePaceChart = findViewById(R.id.distancePaceChart)
        llAutoStravaSplits = findViewById(R.id.llAutoStravaSplits)

        findViewById<View>(R.id.distanceBackButton)?.setOnClickListener { finish() }

        tabDay.setOnClickListener { switchTab(Tab.DAY) }
        tabWeek.setOnClickListener { switchTab(Tab.WEEK) }
        tabMonth.setOnClickListener { switchTab(Tab.MONTH) }

        tvSyncedMoveMinPill.setOnClickListener {
            startActivity(Intent(this, MoveMinHistoryActivity::class.java))
        }
        tvSyncedCaloriesPill.setOnClickListener {
            startActivity(Intent(this, CaloriesHistoryActivity::class.java))
        }

        findViewById<View>(R.id.btnPrevRange)?.setOnClickListener {
            when (currentTab) {
                Tab.DAY -> cursor.add(Calendar.DAY_OF_YEAR, -1)
                Tab.WEEK -> cursor.add(Calendar.DAY_OF_YEAR, -7)
                Tab.MONTH -> cursor.add(Calendar.MONTH, -1)
            }
            render()
        }

        findViewById<View>(R.id.btnNextRange)?.setOnClickListener {
            when (currentTab) {
                Tab.DAY -> cursor.add(Calendar.DAY_OF_YEAR, 1)
                Tab.WEEK -> cursor.add(Calendar.DAY_OF_YEAR, 7)
                Tab.MONTH -> cursor.add(Calendar.MONTH, 1)
            }
            render()
        }

        UiThemePrefs.applyToActivity(this)
        render()
    }

    override fun onResume() {
        super.onResume()
        UiThemePrefs.applyToActivity(this)
        render()
    }

    private fun switchTab(tab: Tab) {
        currentTab = tab
        val selectedBg = ContextCompat.getDrawable(this, R.drawable.bg_button_primary)
        val primaryColor = ContextCompat.getColor(this, R.color.text_primary)
        val secondaryColor = ContextCompat.getColor(this, R.color.text_secondary)

        listOf(
            tabDay to Tab.DAY,
            tabWeek to Tab.WEEK,
            tabMonth to Tab.MONTH
        ).forEach { (tv, t) ->
            tv.background = if (t == tab) selectedBg else null
            tv.setTextColor(if (t == tab) primaryColor else secondaryColor)
        }
        render()
    }

    private fun startOfDay(cal: Calendar): Long {
        val c = cal.clone() as Calendar
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun render() {
        val dayStart = startOfDay(cursor)
        val todayStart = startOfDay(Calendar.getInstance())
        val prefix = when (currentTab) {
            Tab.DAY -> when (dayStart) {
                todayStart -> "Today"
                todayStart - 86_400_000L -> "Yesterday"
                else -> SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date(dayStart))
            }
            Tab.WEEK -> "7-Day Total"
            Tab.MONTH -> "30-Day Total"
        }
        rangeLabel.text = prefix

        lifecycleScope.launch {
            val telemetry = withContext(Dispatchers.IO) {
                when (currentTab) {
                    Tab.DAY -> ActivityTelemetryEngine.loadDayTelemetry(this@DistanceHistoryActivity, dayStart)
                    Tab.WEEK -> ActivityTelemetryEngine.loadPeriodTelemetry(this@DistanceHistoryActivity, dayStart, 7)
                    Tab.MONTH -> ActivityTelemetryEngine.loadPeriodTelemetry(this@DistanceHistoryActivity, dayStart, 30)
                }
            }

            totalText.text = String.format(Locale.US, "%s: %.2f km", prefix, telemetry.totalDistanceKm)
            tvSyncedMoveMinPill.text = "🚶 ${telemetry.totalMoveMinutes} Move Min"
            tvSyncedCaloriesPill.text = "🔥 ${telemetry.totalCaloriesKcal} kcal"

            // Style synced pills with glowing borders matching the mockup
            val density = resources.displayMetrics.density
            tvSyncedMoveMinPill.background = GradientDrawable().apply {
                cornerRadius = 20f * density
                setColor(Color.parseColor("#0D2636"))
                setStroke((1.3f * density).toInt(), Color.parseColor("#00E5FF"))
            }
            tvSyncedCaloriesPill.background = GradientDrawable().apply {
                cornerRadius = 20f * density
                setColor(Color.parseColor("#2A170D"))
                setStroke((1.3f * density).toInt(), Color.parseColor("#FF6D00"))
            }

            val curvePoints = telemetry.windows.map { w ->
                UnifiedActivityCurveView.CurvePoint(
                    timeLabel = w.shortLabel,
                    windowRangeLabel = w.windowRangeLabel,
                    primaryValue = w.distanceKm.toFloat(),
                    distanceKm = w.distanceKm,
                    moveMinutes = w.moveMinutes,
                    caloriesKcal = w.caloriesKcal
                )
            }
            curveView.setTelemetryData(
                data = curvePoints,
                accentHex = "#00E5FF",
                secondaryHex = "#10B981",
                unit = "km"
            )

            renderSyncedWindowRows(telemetry.activeWindows)
            renderAutoStravaSection(telemetry)
        }
    }

    private fun renderSyncedWindowRows(activeWindows: List<ActivityTelemetryEngine.SyncedTimeWindow>) {
        llSyncedWindowsSummary.removeAllViews()
        val density = resources.displayMetrics.density
        val isLight = UiThemePrefs.isWhiteBackgroundActive(this)
        val textPrimary = if (isLight) Color.parseColor("#0F172A") else Color.WHITE
        val textSecondary = if (isLight) Color.parseColor("#475569") else Color.parseColor("#94A3B8")

        if (activeWindows.isEmpty()) {
            llSyncedWindowsSummary.addView(TextView(this).apply {
                text = "No movement windows recorded yet in this period."
                textSize = 12.5f
                setTextColor(textSecondary)
                gravity = Gravity.CENTER
            })
            return
        }

        // Single concise breakdown of top active time windows (e.g. "6–7 AM: 2.00 km • 22 Move Min • 118 kcal")
        activeWindows.take(4).forEach { w ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = 12f * density
                    setColor(if (isLight) Color.parseColor("#F1F5F9") else Color.parseColor("#0E1A28"))
                    setStroke((1f * density).toInt(), if (isLight) Color.parseColor("#CBD5E1") else Color.parseColor("#1E344D"))
                }
                val padH = (12 * density).toInt()
                val padV = (9 * density).toInt()
                setPadding(padH, padV, padH, padV)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (6 * density).toInt() }
            }

            val tvWindow = TextView(this).apply {
                text = w.windowRangeLabel
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#00E5FF"))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(tvWindow)

            val tvMetrics = TextView(this).apply {
                text = String.format(
                    Locale.US,
                    "%.2f km  •  %d Move Min  •  %d kcal",
                    w.distanceKm,
                    w.moveMinutes,
                    w.caloriesKcal
                )
                textSize = 12.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(textPrimary)
            }
            row.addView(tvMetrics)

            llSyncedWindowsSummary.addView(row)
        }
    }

    private fun renderAutoStravaSection(telemetry: ActivityTelemetryEngine.DayUnifiedTelemetry) {
        llAutoStravaSplits.removeAllViews()
        val session = telemetry.autoDetectedSessions.firstOrNull()
        val density = resources.displayMetrics.density
        val isLight = UiThemePrefs.isWhiteBackgroundActive(this)
        val textPrimary = if (isLight) Color.parseColor("#0F172A") else Color.WHITE

        if (session == null) {
            tvAutoStravaTitle.text = "Auto-Detected Walk / Jog (Strava Telemetry)"
            tvAutoStravaSubtitle.text = "Automatic detection active • Any continuous walk or jog is analyzed here automatically"
            distancePaceChart.setSplits(emptyList(), subtitle = "AUTO-DETECTED PACE CURVE (min/km)")
            return
        }

        tvAutoStravaTitle.text = session.title
        val mins = session.durationSeconds / 60
        val secs = session.durationSeconds % 60
        tvAutoStravaSubtitle.text = String.format(
            Locale.US,
            "%s  •  %.2f km  •  Pace %s/km  •  %dm %02ds  •  %d spm",
            session.timeRangeLabel,
            session.distanceKm,
            StravaPaceChartView.formatPace(session.avgPaceSecPerKm),
            mins,
            secs,
            session.cadenceSpm
        )

        distancePaceChart.setSplits(
            session.splits,
            subtitle = "AUTO-DETECTED STRAVA PACE CURVE (min/km) & SPLITS"
        )

        session.splits.take(5).forEach { sp ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (4 * density).toInt() }
            }
            row.addView(TextView(this).apply {
                text = sp.splitLabel
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#FC4C02"))
                layoutParams = LinearLayout.LayoutParams((64 * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            row.addView(TextView(this).apply {
                text = String.format(
                    Locale.US,
                    "Pace %s /km  •  %.1f km/h  •  %d spm",
                    StravaPaceChartView.formatPace(sp.paceSecondsPerKm),
                    sp.speedKmh,
                    sp.cadenceSpm
                )
                textSize = 12f
                setTextColor(textPrimary)
            })
            llAutoStravaSplits.addView(row)
        }
    }
}
