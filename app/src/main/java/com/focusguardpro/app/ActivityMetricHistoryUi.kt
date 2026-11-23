package com.focusguardpro.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Shared minimalist, high-clarity screen scaffold for Physical Activity detail sections
 * (Move Minutes, Distance, Calories).
 *
 * Balances effortless Google-Fit-inspired simplicity:
 *   - Clean flat dark canvas (#111318) with crisp hairline dividers (or Light/OLED Black theme)
 *   - Underline Day / Week / Month tab strip
 *   - Centered "< Sunday, November 22 >" date selector with understated total below
 *   - Crisp rounded vertical pill-bar chart (24h 12AM–12AM for Day, daily bars for Week/Month)
 *   - Clean educational caption
 *   - Borderless tappable Activity Session rows ("6:04 PM / Evening walk / 22m 25s • 18 min")
 *     opening ActivitySessionDetailActivity
 *   - "See source data" link showing Sensor + GPS + Health Connect breakdown
 *
 * With Focus Elite's deep analytics 1 tap away:
 *   - Instant "Bars / Curve" toggle to inspect the synchronized 3-metric Bezier curve
 *   - Cross-metric summary strip (Distance · Move Min · Calories) & expandable Deep Insights slot
 */
class ActivityMetricHistoryViewHolder(
    private val activity: AppCompatActivity,
    val title: String,
    val barColorHex: String,
    val curveSecondaryHex: String,
    val unitLabel: String,
    val explainerText: String
) {
    enum class Tab { DAY, WEEK, MONTH }

    var currentTab: Tab = Tab.DAY
        private set
    var isCurveMode: Boolean = false
        private set

    val scrollView: ScrollView
    val rootContainer: LinearLayout
    val tvTabDay: TextView
    val tvTabWeek: TextView
    val tvTabMonth: TextView
    val indDay: View
    val indWeek: View
    val indMonth: View
    val btnPrev: TextView
    val btnNext: TextView
    val tvDateHeader: TextView
    val tvHeroTotal: TextView
    val tvChartToggle: TextView
    val barChart: CleanPillBarChartView
    val curveChart: UnifiedActivityCurveView
    val tvCrossMetricsLine: TextView
    val llSessionsContainer: LinearLayout
    val llDeepExtraContainer: LinearLayout
    val tvSeeSourceData: TextView

    private val density = activity.resources.displayMetrics.density
    private val isWhite = UiThemePrefs.isWhiteBackgroundActive(activity)
    private val isBlack = UiThemePrefs.isBlackBackgroundActive(activity)

    val bgColor = when {
        isWhite -> Color.parseColor("#FFFFFF")
        isBlack -> Color.parseColor("#000000")
        else -> Color.parseColor("#111318")
    }
    val textPrimary = if (isWhite) Color.parseColor("#0F172A") else Color.parseColor("#F1F3F4")
    val textSecondary = if (isWhite) Color.parseColor("#475569") else Color.parseColor("#9AA0A6")
    val dividerColor = if (isWhite) Color.parseColor("#E2E8F0") else Color.parseColor("#242832")
    val surfaceBg = if (isWhite) Color.parseColor("#F8FAFC") else Color.parseColor("#181C24")
    val accentBlue = Color.parseColor("#8AB4F8")

    init {
        activity.window?.statusBarColor = bgColor
        activity.window?.navigationBarColor = bgColor

        scrollView = ScrollView(activity).apply {
            setBackgroundColor(bgColor)
            isFillViewport = true
            fitsSystemWindows = true
        }

        rootContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (8 * density).toInt(), 0, (28 * density).toInt())
        }
        scrollView.addView(rootContainer)

        val padH = (20 * density).toInt()

        // 1. Minimalist Top Bar: ← Title ... [Curve / Bars]
        val topBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((10 * density).toInt(), (4 * density).toInt(), (16 * density).toInt(), (4 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (48 * density).toInt()
            )
        }
        topBar.addView(TextView(activity).apply {
            text = "←"
            textSize = 23f
            setTextColor(textPrimary)
            gravity = Gravity.CENTER
            minWidth = (44 * density).toInt()
            minHeight = (44 * density).toInt()
            contentDescription = "Back"
            setOnClickListener { activity.finish() }
        })
        topBar.addView(TextView(activity).apply {
            text = title
            textSize = 20f
            setTextColor(textPrimary)
            setPadding((8 * density).toInt(), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        tvChartToggle = TextView(activity).apply {
            text = "Curve View"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(accentBlue)
            background = GradientDrawable().apply {
                cornerRadius = 14f * density
                setColor(surfaceBg)
            }
            setPadding((11 * density).toInt(), (6 * density).toInt(), (11 * density).toInt(), (6 * density).toInt())
            setOnClickListener {
                isCurveMode = !isCurveMode
                barChart.visibility = if (isCurveMode) View.GONE else View.VISIBLE
                curveChart.visibility = if (isCurveMode) View.VISIBLE else View.GONE
                text = if (isCurveMode) "Bar View" else "Curve View"
            }
        }
        topBar.addView(tvChartToggle)
        rootContainer.addView(topBar)

        // 2. Clean Underline Tab Bar (Day | Week | Month)
        val tabBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 3f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (44 * density).toInt()
            )
        }

        fun makeTabCell(label: String): Triple<LinearLayout, TextView, View> {
            val col = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            }
            val tv = TextView(activity).apply {
                text = label
                textSize = 14.5f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            }
            val indicator = View(activity).apply {
                background = GradientDrawable().apply {
                    cornerRadius = 3f * density
                    setColor(textPrimary)
                }
                layoutParams = LinearLayout.LayoutParams((32 * density).toInt(), (3 * density).toInt())
            }
            col.addView(tv)
            col.addView(indicator)
            return Triple(col, tv, indicator)
        }

        val (colDay, tDay, iDay) = makeTabCell("Day")
        val (colWeek, tWeek, iWeek) = makeTabCell("Week")
        val (colMonth, tMonth, iMonth) = makeTabCell("Month")
        tvTabDay = tDay; indDay = iDay
        tvTabWeek = tWeek; indWeek = iWeek
        tvTabMonth = tMonth; indMonth = iMonth
        tabBar.addView(colDay)
        tabBar.addView(colWeek)
        tabBar.addView(colMonth)
        rootContainer.addView(tabBar)

        rootContainer.addView(makeDivider())

        // 3. Centered Date Navigator (< Sunday, November 22 >) + Subtle Hero Total Below
        val dateRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * density).toInt(), (18 * density).toInt(), (12 * density).toInt(), (2 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        btnPrev = TextView(activity).apply {
            text = "‹"
            textSize = 26f
            setTextColor(textSecondary)
            gravity = Gravity.CENTER
            minWidth = (48 * density).toInt()
            minHeight = (48 * density).toInt()
            contentDescription = "Previous"
        }
        val dateCenterCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        tvDateHeader = TextView(activity).apply {
            text = "Today"
            textSize = 18.5f
            setTypeface(typeface, Typeface.NORMAL)
            setTextColor(textPrimary)
            gravity = Gravity.CENTER
        }
        tvHeroTotal = TextView(activity).apply {
            text = "0 $unitLabel"
            textSize = 14.5f
            setTextColor(textSecondary)
            gravity = Gravity.CENTER
            setPadding(0, (4 * density).toInt(), 0, 0)
        }
        dateCenterCol.addView(tvDateHeader)
        dateCenterCol.addView(tvHeroTotal)

        btnNext = TextView(activity).apply {
            text = "›"
            textSize = 26f
            setTextColor(textSecondary)
            gravity = Gravity.CENTER
            minWidth = (48 * density).toInt()
            minHeight = (48 * density).toInt()
            contentDescription = "Next"
        }
        dateRow.addView(btnPrev)
        dateRow.addView(dateCenterCol)
        dateRow.addView(btnNext)
        rootContainer.addView(dateRow)

        // 4. Primary Chart Area (CleanPillBarChartView default + UnifiedActivityCurveView 1-tap toggle)
        val chartWrap = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14 * density).toInt(), (6 * density).toInt(), (14 * density).toInt(), (4 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        barChart = CleanPillBarChartView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (215 * density).toInt()
            )
        }
        curveChart = UnifiedActivityCurveView(activity).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (215 * density).toInt()
            )
        }
        chartWrap.addView(barChart)
        chartWrap.addView(curveChart)
        rootContainer.addView(chartWrap)

        // 5. Clean Educational Caption + Synced Cross-Metric Strip
        rootContainer.addView(TextView(activity).apply {
            text = explainerText
            textSize = 14.5f
            setTextColor(textSecondary)
            setLineSpacing(4f * density, 1f)
            setPadding(padH, (12 * density).toInt(), padH, (8 * density).toInt())
        })

        tvCrossMetricsLine = TextView(activity).apply {
            text = ""
            textSize = 12.5f
            setTextColor(accentBlue)
            setPadding(padH, 0, padH, (16 * density).toInt())
        }
        rootContainer.addView(tvCrossMetricsLine)

        rootContainer.addView(makeDivider())

        // 6. Clean Borderless Activity Sessions List (matching screenshot!)
        llSessionsContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padH, (8 * density).toInt(), padH, (8 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        rootContainer.addView(llSessionsContainer)

        // Optional Deep Extra Slot (e.g. Pace & Splits or Goal Progress tucked cleanly)
        llDeepExtraContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padH, 0, padH, (8 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        rootContainer.addView(llDeepExtraContainer)

        rootContainer.addView(makeDivider())

        // 7. Bottom "See source data" Link (matching screenshot!)
        tvSeeSourceData = TextView(activity).apply {
            text = "See source data"
            textSize = 15.5f
            setTextColor(accentBlue)
            setPadding(padH, (18 * density).toInt(), padH, (18 * density).toInt())
            isClickable = true
            isFocusable = true
        }
        rootContainer.addView(tvSeeSourceData)

        updateTabVisuals(Tab.DAY)
    }

    fun bindTabClicks(onTabChanged: (Tab) -> Unit) {
        (tvTabDay.parent as View).setOnClickListener {
            updateTabVisuals(Tab.DAY)
            onTabChanged(Tab.DAY)
        }
        (tvTabWeek.parent as View).setOnClickListener {
            updateTabVisuals(Tab.WEEK)
            onTabChanged(Tab.WEEK)
        }
        (tvTabMonth.parent as View).setOnClickListener {
            updateTabVisuals(Tab.MONTH)
            onTabChanged(Tab.MONTH)
        }
    }

    fun updateTabVisuals(tab: Tab) {
        currentTab = tab
        val pairs = listOf(
            Triple(Tab.DAY, tvTabDay, indDay),
            Triple(Tab.WEEK, tvTabWeek, indWeek),
            Triple(Tab.MONTH, tvTabMonth, indMonth)
        )
        pairs.forEach { (t, tv, ind) ->
            val selected = (t == tab)
            tv.setTextColor(if (selected) textPrimary else textSecondary)
            ind.visibility = if (selected) View.VISIBLE else View.INVISIBLE
        }
    }

    fun formatDateTitle(cursor: Calendar, tab: Tab): String {
        val dayStart = startOfDay(cursor)
        return when (tab) {
            Tab.DAY -> SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date(dayStart))
            Tab.WEEK -> {
                val startMs = dayStart - 6L * 86_400_000L
                val f = SimpleDateFormat("MMM d", Locale.getDefault())
                "${f.format(Date(startMs))} – ${f.format(Date(dayStart))}"
            }
            Tab.MONTH -> {
                val startMs = dayStart - 29L * 86_400_000L
                val f = SimpleDateFormat("MMM d", Locale.getDefault())
                "${f.format(Date(startMs))} – ${f.format(Date(dayStart))}"
            }
        }
    }

    fun renderCleanSessionsList(
        telemetry: ActivityTelemetryEngine.DayUnifiedTelemetry,
        metricKind: String // "move", "distance", "calories"
    ) {
        llSessionsContainer.removeAllViews()
        val sessions = telemetry.autoDetectedSessions
        if (sessions.isEmpty()) {
            llSessionsContainer.addView(TextView(activity).apply {
                text = "No automatic walking or workout sessions detected for this period yet."
                textSize = 14f
                setTextColor(textSecondary)
                setPadding(0, (14 * density).toInt(), 0, (14 * density).toInt())
            })
            return
        }

        val timeStartFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
        sessions.forEach { session ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                isClickable = true
                isFocusable = true
                setPadding(0, (14 * density).toInt(), 0, (14 * density).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setOnClickListener {
                    activity.startActivity(ActivitySessionDetailActivity.createIntent(activity, session))
                }
            }

            val icon = when {
                session.activityType.lowercase(Locale.US).contains("run") ||
                    session.activityType.lowercase(Locale.US).contains("jog") -> "🏃"
                session.activityType.lowercase(Locale.US).contains("cycl") -> "🚴"
                else -> "🚶"
            }

            val startLabel = if (session.startTimeMs > 0L) {
                timeStartFmt.format(Date(session.startTimeMs))
            } else {
                session.timeRangeLabel
            }

            // Line 1: Icon + Start Time (e.g. "🚶  6:04 PM")
            row.addView(TextView(activity).apply {
                text = "$icon   $startLabel"
                textSize = 13f
                setTextColor(textSecondary)
            })

            // Line 2: Clean Activity Name (e.g. "Evening walk") + subtle chevron
            val titleLine = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (3 * density).toInt(), 0, (3 * density).toInt())
            }
            val savedCustom = ActivitySessionDetailActivity.getSavedCustomTitle(
                activity,
                "${session.startTimeMs}_${session.timeRangeLabel}"
            )
            titleLine.addView(TextView(activity).apply {
                text = savedCustom ?: session.title
                textSize = 17.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(textPrimary)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            titleLine.addView(TextView(activity).apply {
                text = "›"
                textSize = 20f
                setTextColor(textSecondary)
            })
            row.addView(titleLine)

            // Line 3: Concise Metric Summary tailored to current section (e.g. "22m 25s • 18 min")
            val durStr = formatDurationShort(session.elapsedSeconds)
            val summaryText = when (metricKind) {
                "distance" -> String.format(
                    Locale.US,
                    "%.2f km  •  %s  •  %s/km",
                    session.distanceKm,
                    durStr,
                    if (session.avgPaceSecPerKm > 0f) StravaPaceChartView.formatPace(session.avgPaceSecPerKm) else "—"
                )
                "calories" -> String.format(
                    Locale.US,
                    "%d Cal  •  %s  •  %.2f km",
                    session.caloriesKcal,
                    durStr,
                    session.distanceKm
                )
                else -> "$durStr  •  ${session.moveMinutes} min"
            }
            row.addView(TextView(activity).apply {
                text = summaryText
                textSize = 14f
                setTextColor(textSecondary)
            })

            llSessionsContainer.addView(row)
        }
    }

    fun showSourceDataDialog(telemetry: ActivityTelemetryEngine.DayUnifiedTelemetry) {
        val hasGps = GpsWalkTracker.hasPermission(activity)
        val hasHealth = HealthConnectManager.isSupported(activity)
        val stepsFmt = NumberFormat.getIntegerInstance(Locale.US).format(telemetry.totalSteps)

        val msg = buildString {
            append("Primary Source: Device Hardware Step Sensor\n")
            append("GPS Verification: ${if (hasGps) "Enabled (Auto-verified outdoors)" else "Off (Stride-calibrated)"}\n")
            append("Health Connect: ${if (hasHealth) "Supported" else "Standalone Sensor Mode"}\n\n")
            append("Period Totals:\n")
            append("• Steps: $stepsFmt\n")
            append(String.format(Locale.US, "• Distance: %.2f km\n", telemetry.totalDistanceKm))
            append("• Move Minutes: ${telemetry.totalMoveMinutes} min\n")
            append("• Energy Expended: ${telemetry.totalCaloriesKcal} Cal\n")
            if (telemetry.cadenceSpm > 0) append("• Average Cadence: ${telemetry.cadenceSpm} spm\n")
            if (telemetry.activeWindows.isNotEmpty()) {
                append("\nTop Active Windows:\n")
                telemetry.activeWindows.take(5).forEach { w ->
                    append(
                        String.format(
                            Locale.US,
                            "• %s: %d min · %.2f km · %d Cal\n",
                            w.windowRangeLabel,
                            w.moveMinutes,
                            w.distanceKm,
                            w.caloriesKcal
                        )
                    )
                }
            }
        }

        AlertDialog.Builder(activity)
            .setTitle("Activity Source Data")
            .setMessage(msg.trim())
            .setPositiveButton("Close", null)
            .show()
    }

    private fun makeDivider(): View {
        return View(activity).apply {
            setBackgroundColor(dividerColor)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (1 * density).toInt()
            )
        }
    }

    private fun startOfDay(cal: Calendar): Long {
        val c = cal.clone() as Calendar
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun formatDurationShort(totalSec: Int): String {
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return when {
            h > 0 -> String.format(Locale.US, "%dh %02dm", h, m)
            m > 0 -> String.format(Locale.US, "%dm %02ds", m, s)
            else -> "${s}s"
        }
    }
}
