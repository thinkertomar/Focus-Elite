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

class MoveMinHistoryActivity : AppCompatActivity() {

    enum class Tab { DAY, WEEK, MONTH }

    private var currentTab = Tab.DAY
    private val cursor: Calendar = Calendar.getInstance()

    private lateinit var tabDay: TextView
    private lateinit var tabWeek: TextView
    private lateinit var tabMonth: TextView
    private lateinit var rangeLabel: TextView
    private lateinit var totalText: TextView
    private lateinit var tvSyncedDistancePill: TextView
    private lateinit var tvSyncedCaloriesPill: TextView
    private lateinit var curveView: UnifiedActivityCurveView
    private lateinit var windowListContainer: LinearLayout
    private lateinit var tvMoveMinGoalSummary: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_movemin_history)

        tabDay = findViewById(R.id.moveMinTabDay)
        tabWeek = findViewById(R.id.moveMinTabWeek)
        tabMonth = findViewById(R.id.moveMinTabMonth)
        rangeLabel = findViewById(R.id.moveMinRangeLabel)
        totalText = findViewById(R.id.moveMinTotalText)
        tvSyncedDistancePill = findViewById(R.id.tvMoveMinSyncedDistancePill)
        tvSyncedCaloriesPill = findViewById(R.id.tvMoveMinSyncedCaloriesPill)
        curveView = findViewById(R.id.moveMinUnifiedCurveView)
        windowListContainer = findViewById(R.id.moveMinSessionListContainer)
        tvMoveMinGoalSummary = findViewById(R.id.tvMoveMinGoalSummary)

        findViewById<View>(R.id.moveMinBackButton)?.setOnClickListener { finish() }

        tabDay.setOnClickListener { switchTab(Tab.DAY) }
        tabWeek.setOnClickListener { switchTab(Tab.WEEK) }
        tabMonth.setOnClickListener { switchTab(Tab.MONTH) }

        tvSyncedDistancePill.setOnClickListener {
            startActivity(Intent(this, DistanceHistoryActivity::class.java))
        }
        tvSyncedCaloriesPill.setOnClickListener {
            startActivity(Intent(this, CaloriesHistoryActivity::class.java))
        }

        findViewById<View>(R.id.moveMinPrevButton)?.setOnClickListener {
            when (currentTab) {
                Tab.DAY -> cursor.add(Calendar.DAY_OF_YEAR, -1)
                Tab.WEEK -> cursor.add(Calendar.DAY_OF_YEAR, -7)
                Tab.MONTH -> cursor.add(Calendar.MONTH, -1)
            }
            render()
        }

        findViewById<View>(R.id.moveMinNextButton)?.setOnClickListener {
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
                    Tab.DAY -> ActivityTelemetryEngine.loadDayTelemetry(this@MoveMinHistoryActivity, dayStart)
                    Tab.WEEK -> ActivityTelemetryEngine.loadPeriodTelemetry(this@MoveMinHistoryActivity, dayStart, 7)
                    Tab.MONTH -> ActivityTelemetryEngine.loadPeriodTelemetry(this@MoveMinHistoryActivity, dayStart, 30)
                }
            }

            totalText.text = "$prefix: ${telemetry.totalMoveMinutes} Move Min"
            tvSyncedDistancePill.text = String.format(Locale.US, "📍 %.2f km", telemetry.totalDistanceKm)
            tvSyncedCaloriesPill.text = "🔥 ${telemetry.totalCaloriesKcal} kcal"

            val density = resources.displayMetrics.density
            tvSyncedDistancePill.background = GradientDrawable().apply {
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
                    primaryValue = w.moveMinutes.toFloat(),
                    distanceKm = w.distanceKm,
                    moveMinutes = w.moveMinutes,
                    caloriesKcal = w.caloriesKcal
                )
            }
            curveView.setTelemetryData(
                data = curvePoints,
                accentHex = "#00E676",
                secondaryHex = "#00E5FF",
                unit = "min"
            )

            val goal = GoalPrefs.getMoveMinGoal(this@MoveMinHistoryActivity).coerceAtLeast(10)
            val pct = ((telemetry.totalMoveMinutes * 100) / goal).coerceAtLeast(0)
            tvMoveMinGoalSummary.text = String.format(
                Locale.US,
                "%d Move Min (%d%% of %d min daily goal) • Cadence %d spm • %.2f km & %d kcal",
                telemetry.totalMoveMinutes,
                pct,
                goal,
                telemetry.cadenceSpm,
                telemetry.totalDistanceKm,
                telemetry.totalCaloriesKcal
            )

            renderSyncedWindowRows(telemetry.activeWindows)
        }
    }

    private fun renderSyncedWindowRows(activeWindows: List<ActivityTelemetryEngine.SyncedTimeWindow>) {
        windowListContainer.removeAllViews()
        val density = resources.displayMetrics.density
        val isLight = UiThemePrefs.isWhiteBackgroundActive(this)
        val textPrimary = if (isLight) Color.parseColor("#0F172A") else Color.WHITE
        val textSecondary = if (isLight) Color.parseColor("#475569") else Color.parseColor("#94A3B8")

        if (activeWindows.isEmpty()) {
            windowListContainer.addView(TextView(this).apply {
                text = "No active movement windows recorded yet in this period."
                textSize = 12.5f
                setTextColor(textSecondary)
                gravity = Gravity.CENTER
            })
            return
        }

        activeWindows.take(5).forEach { w ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = 12f * density
                    setColor(if (isLight) Color.parseColor("#ECFDF5") else Color.parseColor("#0B241C"))
                    setStroke((1f * density).toInt(), Color.parseColor("#00E676"))
                }
                val padH = (12 * density).toInt()
                val padV = (9 * density).toInt()
                setPadding(padH, padV, padH, padV)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (6 * density).toInt() }
            }

            row.addView(TextView(this).apply {
                text = w.windowRangeLabel
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#00E676"))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })

            row.addView(TextView(this).apply {
                text = String.format(
                    Locale.US,
                    "%d Move Min  •  %.2f km  •  %d kcal",
                    w.moveMinutes,
                    w.distanceKm,
                    w.caloriesKcal
                )
                textSize = 12.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(textPrimary)
            })

            windowListContainer.addView(row)
        }
    }
}
