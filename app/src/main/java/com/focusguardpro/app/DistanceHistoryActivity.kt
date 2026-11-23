package com.focusguardpro.app

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale

class DistanceHistoryActivity : AppCompatActivity() {

    private val cursor: Calendar = Calendar.getInstance()
    private lateinit var ui: ActivityMetricHistoryViewHolder
    private var latestTelemetry: ActivityTelemetryEngine.DayUnifiedTelemetry? = null
    private var isPaceDrawerOpen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ui = ActivityMetricHistoryViewHolder(
            activity = this,
            title = "Distance",
            barColorHex = "#38BDF8",
            curveSecondaryHex = "#10B981",
            unitLabel = "km",
            explainerText = "Distance tracks every kilometre covered from walks, jogs, and runs using calibrated step length and outdoor GPS."
        )
        setContentView(ui.scrollView)

        ui.bindTabClicks { render() }

        ui.btnPrev.setOnClickListener {
            SensoryFeedbackEngine.tap(this, it)
            when (ui.currentTab) {
                ActivityMetricHistoryViewHolder.Tab.DAY -> cursor.add(Calendar.DAY_OF_YEAR, -1)
                ActivityMetricHistoryViewHolder.Tab.WEEK -> cursor.add(Calendar.DAY_OF_YEAR, -7)
                ActivityMetricHistoryViewHolder.Tab.MONTH -> cursor.add(Calendar.MONTH, -1)
            }
            render()
        }

        ui.btnNext.setOnClickListener {
            SensoryFeedbackEngine.tap(this, it)
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
                        ActivityTelemetryEngine.loadDayTelemetry(this@DistanceHistoryActivity, dayStart)
                    ActivityMetricHistoryViewHolder.Tab.WEEK ->
                        ActivityTelemetryEngine.loadPeriodTelemetry(this@DistanceHistoryActivity, dayStart, 7)
                    ActivityMetricHistoryViewHolder.Tab.MONTH ->
                        ActivityTelemetryEngine.loadPeriodTelemetry(this@DistanceHistoryActivity, dayStart, 30)
                }
            }
            latestTelemetry = telemetry

            ui.tvHeroTotal.text = String.format(Locale.US, "%.2f km", telemetry.totalDistanceKm)

            val pacePart = if (telemetry.avgPaceSecPerKm > 0f) {
                "  •  Avg ${StravaPaceChartView.formatPace(telemetry.avgPaceSecPerKm)}/km"
            } else ""
            ui.tvCrossMetricsLine.text = String.format(
                Locale.US,
                "%d min active  •  %d Cal%s",
                telemetry.totalMoveMinutes,
                telemetry.totalCaloriesKcal,
                pacePart
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
                        primaryValue = w.distanceKm.toFloat(),
                        distanceKm = w.distanceKm,
                        moveMinutes = w.moveMinutes,
                        caloriesKcal = w.caloriesKcal
                    )
                },
                dayMode = (tab == ActivityMetricHistoryViewHolder.Tab.DAY),
                barColorHex = "#38BDF8",
                unitLabel = "km"
            )

            ui.curveChart.setTelemetryData(
                data = telemetry.windows.map { w ->
                    UnifiedActivityCurveView.CurvePoint(
                        timeLabel = w.shortLabel,
                        windowRangeLabel = w.windowRangeLabel,
                        primaryValue = w.distanceKm.toFloat(),
                        distanceKm = w.distanceKm,
                        moveMinutes = w.moveMinutes,
                        caloriesKcal = w.caloriesKcal
                    )
                },
                accentHex = "#00E5FF",
                secondaryHex = "#10B981",
                unit = "km"
            )

            // Clean borderless sessions (tapping opens ActivitySessionDetailActivity with splits & pace bars!)
            ui.renderCleanSessionsList(telemetry, metricKind = "distance")

            // Optional expandable deep Pace Trend chart tucked neatly below sessions
            renderCompactPaceInsight(telemetry)
        }
    }

    private fun renderCompactPaceInsight(telemetry: ActivityTelemetryEngine.DayUnifiedTelemetry) {
        ui.llDeepExtraContainer.removeAllViews()
        val best = telemetry.autoDetectedSessions.maxByOrNull { it.distanceKm }
        if (best == null || best.splits.isEmpty()) return

        val density = resources.displayMetrics.density
        val drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (isPaceDrawerOpen) View.VISIBLE else View.GONE
            background = GradientDrawable().apply {
                cornerRadius = 14f * density
                setColor(ui.surfaceBg)
            }
            setPadding((14 * density).toInt(), (12 * density).toInt(), (14 * density).toInt(), (12 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (10 * density).toInt() }
        }

        val toggle = TextView(this).apply {
            text = if (isPaceDrawerOpen) "Hide Pace & Split Graph ⌃" else "Inspect Pace & Split Graph ⌄"
            textSize = 13.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(0, (6 * density).toInt(), 0, (10 * density).toInt())
            setOnClickListener {
                isPaceDrawerOpen = !isPaceDrawerOpen
                drawer.visibility = if (isPaceDrawerOpen) View.VISIBLE else View.GONE
                text = if (isPaceDrawerOpen) "Hide Pace & Split Graph ⌃" else "Inspect Pace & Split Graph ⌄"
            }
        }
        ui.llDeepExtraContainer.addView(toggle)

        drawer.addView(TextView(this).apply {
            text = String.format(
                Locale.US,
                "%s · %.2f km · Avg %s/km · %.1f km/h",
                best.title,
                best.distanceKm,
                StravaPaceChartView.formatPace(best.avgPaceSecPerKm),
                best.avgSpeedKmh
            )
            textSize = 12.5f
            setTextColor(ui.textSecondary)
            setPadding(0, 0, 0, (8 * density).toInt())
        })
        drawer.addView(StravaPaceChartView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (140 * density).toInt()
            )
            setSplits(best.splits, "PACE TREND (min/km · lower is faster)")
        })
        ui.llDeepExtraContainer.addView(drawer)
    }
}
