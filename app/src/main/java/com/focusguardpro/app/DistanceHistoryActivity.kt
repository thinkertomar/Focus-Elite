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

        findViewById<View>(R.id.btnPrevRange)?.setOnClickListener { v ->
            SensoryFeedbackEngine.tap(this, v)
            when (currentTab) {
                Tab.DAY -> cursor.add(Calendar.DAY_OF_YEAR, -1)
                Tab.WEEK -> cursor.add(Calendar.DAY_OF_YEAR, -7)
                Tab.MONTH -> cursor.add(Calendar.MONTH, -1)
            }
            render()
        }

        findViewById<View>(R.id.btnNextRange)?.setOnClickListener { v ->
            SensoryFeedbackEngine.tap(this, v)
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
        SensoryFeedbackEngine.tap(this)
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

    private fun formatMinSec(totalSec: Int): String {
        val m = totalSec / 60
        val s = totalSec % 60
        return if (m > 0) String.format(Locale.US, "%dm %02ds", m, s) else "${s}s"
    }

    private fun renderAutoStravaSection(telemetry: ActivityTelemetryEngine.DayUnifiedTelemetry) {
        llAutoStravaSplits.removeAllViews()
        val session = telemetry.autoDetectedSessions.firstOrNull()
        val density = resources.displayMetrics.density
        val isLight = UiThemePrefs.isWhiteBackgroundActive(this)
        val textPrimary = if (isLight) Color.parseColor("#0F172A") else Color.parseColor("#F1F5F9")
        val textSecondary = if (isLight) Color.parseColor("#475569") else Color.parseColor("#94A3B8")
        val cardBg = if (isLight) Color.parseColor("#F1F5F9") else Color.parseColor("#0D1826")
        val cardStroke = if (isLight) Color.parseColor("#CBD5E1") else Color.parseColor("#1E344D")

        if (session == null || session.splits.isEmpty()) {
            tvAutoStravaTitle.text = "Activity Pace & Splits"
            tvAutoStravaSubtitle.text = "No continuous activity recorded in this period"
            distancePaceChart.visibility = View.GONE
            return
        }

        distancePaceChart.visibility = View.VISIBLE
        tvAutoStravaTitle.text = session.activityType
        val gpsLabel = if (session.gpsRecorded) "GPS Recorded" else "Motion Sensor"
        val cadencePart = if (session.cadenceSpm > 0) " · ${session.cadenceSpm} spm" else ""
        val calPart = if (session.caloriesKcal > 0) " · ${session.caloriesKcal} kcal" else ""
        tvAutoStravaSubtitle.text = String.format(
            Locale.US,
            "%s · %s · %s\n%.2f km · %s /km · %.1f km/h%s%s",
            session.sourceType,
            session.timeRangeLabel,
            gpsLabel,
            session.distanceKm,
            StravaPaceChartView.formatPace(session.avgPaceSecPerKm),
            session.avgSpeedKmh,
            cadencePart,
            calPart
        )

        distancePaceChart.setSplits(
            session.splits,
            subtitle = "PACE TREND (min/km · lower is faster)"
        )

        // 1. Moving Time vs Elapsed Time + Auto-Pause Telemetry Strip
        val timeStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (8 * density).toInt()
                bottomMargin = (8 * density).toInt()
            }
        }
        fun addMetricTile(title: String, value: String, accentHex: String) {
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    cornerRadius = 11f * density
                    setColor(cardBg)
                    setStroke((1f * density).toInt(), cardStroke)
                }
                setPadding((6 * density).toInt(), (7 * density).toInt(), (6 * density).toInt(), (7 * density).toInt())
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = (5 * density).toInt()
                }
            }
            tile.addView(TextView(this).apply {
                text = value
                textSize = 12.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor(accentHex))
                maxLines = 1
            })
            tile.addView(TextView(this).apply {
                text = title
                textSize = 9.5f
                setTextColor(textSecondary)
                maxLines = 1
            })
            timeStrip.addView(tile)
        }

        addMetricTile("MOVING TIME", formatMinSec(session.movingSeconds), "#16D99A")
        addMetricTile("ELAPSED TIME", formatMinSec(session.elapsedSeconds), "#38BDF8")
        val pauseSummary = if (session.pausedSeconds > 0) {
            val suffix = if (session.pauseCount > 0) " (${session.pauseCount})" else ""
            "${formatMinSec(session.pausedSeconds)}$suffix"
        } else {
            "None"
        }
        addMetricTile("PAUSED", pauseSummary, if (session.pausedSeconds > 0) "#F59E0B" else "#94A3B8")
        llAutoStravaSplits.addView(timeStrip)

        // 2. Fastest Split Highlight Badge (only when 2+ verified splits exist)
        if (session.hasVerifiedSplits && session.splits.size >= 2 && session.fastestSplitLabel.isNotBlank()) {
            llAutoStravaSplits.addView(TextView(this).apply {
                text = "Fastest Split: ${session.fastestSplitLabel} @ ${StravaPaceChartView.formatPace(session.fastestSplitPaceSec)} /km"
                textSize = 11.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#34D399"))
                background = GradientDrawable().apply {
                    cornerRadius = 10f * density
                    setColor(if (isLight) Color.parseColor("#ECFDF5") else Color.parseColor("#0B2922"))
                    setStroke((1f * density).toInt(), Color.parseColor("#10B981"))
                }
                setPadding((10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (8 * density).toInt() }
            })
        }

        // 3. Pace Zone Distribution Bar (Sprint / Jog / Brisk Walk / Easy)
        val z = session.paceZones
        val zoneBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = 5f * density
                setColor(cardBg)
            }
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (7 * density).toInt()
            ).apply { bottomMargin = (4 * density).toInt() }
        }
        fun addZoneSeg(pct: Int, hex: String) {
            if (pct <= 0) return
            zoneBar.addView(View(this).apply {
                setBackgroundColor(Color.parseColor(hex))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, pct.toFloat())
            })
        }
        addZoneSeg(z.sprintPct, "#F43F5E")
        addZoneSeg(z.jogPct, "#F59E0B")
        addZoneSeg(z.briskPct, "#10B981")
        addZoneSeg(z.easyPct, "#38BDF8")
        llAutoStravaSplits.addView(zoneBar)

        val zoneLegendParts = mutableListOf<String>()
        if (z.sprintPct > 0) zoneLegendParts.add("Sprint ${z.sprintPct}%")
        if (z.jogPct > 0) zoneLegendParts.add("Jog ${z.jogPct}%")
        if (z.briskPct > 0) zoneLegendParts.add("Brisk ${z.briskPct}%")
        if (z.easyPct > 0) zoneLegendParts.add("Easy ${z.easyPct}%")
        if (zoneLegendParts.isNotEmpty()) {
            llAutoStravaSplits.addView(TextView(this).apply {
                text = "Pace Zones: ${zoneLegendParts.joinToString("  •  ")}"
                textSize = 10.5f
                setTextColor(textSecondary)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (8 * density).toInt() }
            })
        }

        // 4. Detailed Kilometer Split Timeline Rows
        session.splits.take(10).forEach { sp ->
            val isFastest = session.splits.size >= 2 && sp.splitLabel == session.fastestSplitLabel
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = 9f * density
                    setColor(if (isFastest) (if (isLight) Color.parseColor("#ECFDF5") else Color.parseColor("#0C2420")) else cardBg)
                    setStroke((1f * density).toInt(), if (isFastest) Color.parseColor("#10B981") else cardStroke)
                }
                setPadding((10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (5 * density).toInt() }
            }
            row.addView(TextView(this).apply {
                text = if (isFastest) "★ ${sp.splitLabel}" else sp.splitLabel
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (isFastest) Color.parseColor("#34D399") else Color.parseColor("#16D99A"))
                layoutParams = LinearLayout.LayoutParams((72 * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            val spCadence = if (sp.cadenceSpm > 0) "  •  ${sp.cadenceSpm} spm" else ""
            row.addView(TextView(this).apply {
                text = String.format(
                    Locale.US,
                    "%s /km  •  %.1f km/h%s",
                    StravaPaceChartView.formatPace(sp.paceSecondsPerKm),
                    sp.speedKmh,
                    spCadence
                )
                textSize = 11.8f
                setTextColor(textPrimary)
            })
            llAutoStravaSplits.addView(row)
        }
    }
}
