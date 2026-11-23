package com.focusguardpro.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale

class CaloriesHistoryActivity : AppCompatActivity() {

    private val cursor: Calendar = Calendar.getInstance()
    private lateinit var ui: ActivityMetricHistoryViewHolder
    private var latestTelemetry: ActivityTelemetryEngine.DayUnifiedTelemetry? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ui = ActivityMetricHistoryViewHolder(
            activity = this,
            title = "Energy Expended",
            barColorHex = "#FB923C",
            curveSecondaryHex = "#FFD54F",
            unitLabel = "Cal",
            explainerText = "Energy expended estimates active calories burned through walking, jogging, and daily movement."
        )
        setContentView(ui.scrollView)

        ui.bindTabClicks { render() }

        ui.btnPrev.setOnClickListener {
            when (ui.currentTab) {
                ActivityMetricHistoryViewHolder.Tab.DAY -> cursor.add(Calendar.DAY_OF_YEAR, -1)
                ActivityMetricHistoryViewHolder.Tab.WEEK -> cursor.add(Calendar.DAY_OF_YEAR, -7)
                ActivityMetricHistoryViewHolder.Tab.MONTH -> cursor.add(Calendar.MONTH, -1)
            }
            render()
        }

        ui.btnNext.setOnClickListener {
            val todayEnd = startOfDay(Calendar.getInstance())
            when (ui.currentTab) {
                ActivityMetricHistoryViewHolder.Tab.DAY -> if (startOfDay(cursor) < todayEnd) cursor.add(Calendar.DAY_OF_YEAR, 1)
                ActivityMetricHistoryViewHolder.Tab.WEEK -> if (startOfDay(cursor) < todayEnd) cursor.add(Calendar.DAY_OF_YEAR, 7)
                ActivityMetricHistoryViewHolder.Tab.MONTH -> if (startOfDay(cursor) < todayEnd) cursor.add(Calendar.MONTH, 1)
            }
            render()
        }

        ui.tvSeeSourceData.setOnClickListener {
            latestTelemetry?.let { ui.showSourceDataDialog(it) }
        }

        render()
    }

    override fun onResume() {
        super.onResume()
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
        val tab = ui.currentTab
        ui.tvDateHeader.text = ui.formatDateTitle(cursor, tab)

        lifecycleScope.launch {
            val telemetry = withContext(Dispatchers.IO) {
                when (tab) {
                    ActivityMetricHistoryViewHolder.Tab.DAY ->
                        ActivityTelemetryEngine.loadDayTelemetry(this@CaloriesHistoryActivity, dayStart)
                    ActivityMetricHistoryViewHolder.Tab.WEEK ->
                        ActivityTelemetryEngine.loadPeriodTelemetry(this@CaloriesHistoryActivity, dayStart, 7)
                    ActivityMetricHistoryViewHolder.Tab.MONTH ->
                        ActivityTelemetryEngine.loadPeriodTelemetry(this@CaloriesHistoryActivity, dayStart, 30)
                }
            }
            latestTelemetry = telemetry

            ui.tvHeroTotal.text = "${telemetry.totalCaloriesKcal} Cal"

            val goal = GoalPrefs.getCalorieGoal(this@CaloriesHistoryActivity).coerceAtLeast(100)
            val effectiveGoal = when (tab) {
                ActivityMetricHistoryViewHolder.Tab.DAY -> goal
                ActivityMetricHistoryViewHolder.Tab.WEEK -> goal * 7
                ActivityMetricHistoryViewHolder.Tab.MONTH -> goal * 30
            }
            val pct = ((telemetry.totalCaloriesKcal * 100) / effectiveGoal).coerceAtLeast(0)
            ui.tvCrossMetricsLine.text = String.format(
                Locale.US,
                "%d%% of daily target  •  %.2f km  •  %d Move Min",
                pct,
                telemetry.totalDistanceKm,
                telemetry.totalMoveMinutes
            )

            val barSource = if (tab == ActivityMetricHistoryViewHolder.Tab.DAY) {
                telemetry.hourlyWindows24
            } else {
                telemetry.windows
            }
            ui.barChart.setBarData(
                data = barSource.map { w ->
                    CleanPillBarChartView.BarPoint(
                        axisLabel = w.shortLabel,
                        rangeLabel = w.windowRangeLabel,
                        primaryValue = w.caloriesKcal.toFloat(),
                        distanceKm = w.distanceKm,
                        moveMinutes = w.moveMinutes,
                        caloriesKcal = w.caloriesKcal
                    )
                },
                dayMode = (tab == ActivityMetricHistoryViewHolder.Tab.DAY),
                barColorHex = "#FB923C",
                unitLabel = "kcal"
            )

            ui.curveChart.setTelemetryData(
                data = telemetry.windows.map { w ->
                    UnifiedActivityCurveView.CurvePoint(
                        timeLabel = w.shortLabel,
                        windowRangeLabel = w.windowRangeLabel,
                        primaryValue = w.caloriesKcal.toFloat(),
                        distanceKm = w.distanceKm,
                        moveMinutes = w.moveMinutes,
                        caloriesKcal = w.caloriesKcal
                    )
                },
                accentHex = "#FF6D00",
                secondaryHex = "#FFD54F",
                unit = "kcal"
            )

            ui.renderCleanSessionsList(telemetry, metricKind = "calories")
        }
    }
}
