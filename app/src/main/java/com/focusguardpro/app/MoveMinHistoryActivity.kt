package com.focusguardpro.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale

class MoveMinHistoryActivity : AppCompatActivity() {

    private val cursor: Calendar = Calendar.getInstance()
    private lateinit var ui: ActivityMetricHistoryViewHolder
    private var latestTelemetry: ActivityTelemetryEngine.DayUnifiedTelemetry? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ui = ActivityMetricHistoryViewHolder(
            activity = this,
            title = "Move Minutes",
            barColorHex = "#8AB4F8",
            curveSecondaryHex = "#00E5FF",
            unitLabel = "min",
            explainerText = "Move Minutes are a measure of anything that gets you moving, helping you understand how active you are each day."
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
                        ActivityTelemetryEngine.loadDayTelemetry(this@MoveMinHistoryActivity, dayStart)
                    ActivityMetricHistoryViewHolder.Tab.WEEK ->
                        ActivityTelemetryEngine.loadPeriodTelemetry(this@MoveMinHistoryActivity, dayStart, 7)
                    ActivityMetricHistoryViewHolder.Tab.MONTH ->
                        ActivityTelemetryEngine.loadPeriodTelemetry(this@MoveMinHistoryActivity, dayStart, 30)
                }
            }
            latestTelemetry = telemetry

            ui.tvHeroTotal.text = "${telemetry.totalMoveMinutes} min"

            val goal = GoalPrefs.getMoveMinGoal(this@MoveMinHistoryActivity).coerceAtLeast(10)
            val effectiveGoal = when (tab) {
                ActivityMetricHistoryViewHolder.Tab.DAY -> goal
                ActivityMetricHistoryViewHolder.Tab.WEEK -> goal * 7
                ActivityMetricHistoryViewHolder.Tab.MONTH -> goal * 30
            }
            val pct = ((telemetry.totalMoveMinutes * 100) / effectiveGoal).coerceAtLeast(0)
            val cadencePart = if (telemetry.cadenceSpm > 0) "  •  ${telemetry.cadenceSpm} spm" else ""
            ui.tvCrossMetricsLine.text = String.format(
                Locale.US,
                "%d%% of goal  •  %.2f km  •  %d Cal%s",
                pct,
                telemetry.totalDistanceKm,
                telemetry.totalCaloriesKcal,
                cadencePart
            )

            // 1. Pill-bar chart (24-hour distribution in Day mode, daily bars in Week/Month)
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
                        primaryValue = w.moveMinutes.toFloat(),
                        distanceKm = w.distanceKm,
                        moveMinutes = w.moveMinutes,
                        caloriesKcal = w.caloriesKcal
                    )
                },
                dayMode = (tab == ActivityMetricHistoryViewHolder.Tab.DAY),
                barColorHex = "#8AB4F8",
                unitLabel = "min"
            )

            // 2. Synchronized Curve View (available via 1-tap toggle in top bar)
            ui.curveChart.setTelemetryData(
                data = telemetry.windows.map { w ->
                    UnifiedActivityCurveView.CurvePoint(
                        timeLabel = w.shortLabel,
                        windowRangeLabel = w.windowRangeLabel,
                        primaryValue = w.moveMinutes.toFloat(),
                        distanceKm = w.distanceKm,
                        moveMinutes = w.moveMinutes,
                        caloriesKcal = w.caloriesKcal
                    )
                },
                accentHex = "#00E676",
                secondaryHex = "#8AB4F8",
                unit = "min"
            )

            // 3. Clean borderless Activity Sessions list (tappable -> ActivitySessionDetailActivity)
            ui.renderCleanSessionsList(telemetry, metricKind = "move")
        }
    }
}
