package com.focusguardpro.app

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.max
import kotlin.math.roundToInt

class UsageStatsActivity : AppCompatActivity() {

    private lateinit var todayChart: SimpleBarChartView
    private lateinit var weeklyChart: SimpleBarChartView
    private lateinit var appUsageList: RecyclerView
    private lateinit var appUsageEmptyText: TextView
    private lateinit var noAccessCard: LinearLayout
    private lateinit var categorySummaryRow: LinearLayout
    private lateinit var tvVsYesterday: TextView

    // Dynamically injected Digital Wellbeing Executive Header & 24h Hourly Timeline views
    private var wellbeingHeroCard: LinearLayout? = null
    private var tvHeroTotalTime: TextView? = null
    private var tvHeroDeltaPill: TextView? = null
    private var tvKpiUnlocks: TextView? = null
    private var tvKpiLaunches: TextView? = null
    private var tvKpiPeakHour: TextView? = null
    private var segStudyBar: View? = null
    private var segImproveBar: View? = null
    private var segDistractBar: View? = null
    private var segUtilityBar: View? = null
    private var hourlyChartView: SimpleBarChartView? = null

    private fun dp(value: Float): Int =
        (value * resources.displayMetrics.density).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_usage_stats)

        findViewById<TextView>(R.id.backButton)?.setOnClickListener {
            finish()
        }

        todayChart = findViewById(R.id.todayChart)
        weeklyChart = findViewById(R.id.weeklyChart)
        appUsageList = findViewById(R.id.appUsageList)
        appUsageEmptyText = findViewById(R.id.appUsageEmptyText)
        noAccessCard = findViewById(R.id.noAccessCard)
        categorySummaryRow = findViewById(R.id.categorySummaryRow)
        tvVsYesterday = findViewById(R.id.tvVsYesterday)

        appUsageList.layoutManager = LinearLayoutManager(this)

        findViewById<Button>(R.id.btnGrantUsageAccess)?.setOnClickListener {
            SettingsTamperGuard.grantPermissionSetupGrace(this, 180_000L)
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }

        injectWellbeingExecutiveViews()
    }

    override fun onResume() {
        super.onResume()
        applyThemeStyling()
        refresh()
    }

    private fun injectWellbeingExecutiveViews() {
        val rootContainer = categorySummaryRow.parent as? LinearLayout ?: return
        if (wellbeingHeroCard != null) return

        val palette = UiThemePrefs.getSelectedPalette(this)

        // 1. Digital Wellbeing Executive Telemetry Hero Card
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18f), dp(16f), dp(18f), dp(16f))
            background = UiThemePrefs.createCardBackground(this@UsageStatsActivity, cornerDp = 18f, isHero = true)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12f)
            }
        }

        val topBadgeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val badgeLabel = TextView(this).apply {
            text = "DIGITAL WELLBEING • EXACT FOREGROUND ENGINE"
            textSize = 10.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(palette.secondaryColor)
            letterSpacing = 0.06f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val deltaPill = TextView(this).apply {
            text = "Live"
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(dp(10f), dp(4f), dp(10f), dp(4f))
            background = GradientDrawable().apply {
                cornerRadius = dp(12f).toFloat()
                setColor(Color.parseColor("#1E293B"))
                setStroke(dp(1f), palette.accentColor)
            }
        }
        tvHeroDeltaPill = deltaPill
        topBadgeRow.addView(badgeLabel)
        topBadgeRow.addView(deltaPill)
        hero.addView(topBadgeRow)

        val totalTv = TextView(this).apply {
            text = "0m"
            textSize = 34f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, dp(4f), 0, dp(10f))
        }
        tvHeroTotalTime = totalTv
        hero.addView(totalTv)

        // Multi-segment Category Proportion Bar
        val barContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(8f)
            ).apply {
                bottomMargin = dp(14f)
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(4f).toFloat()
                setColor(Color.parseColor("#1E293B"))
            }
            clipToOutline = true
        }

        val sBar = View(this).apply {
            setBackgroundColor(Color.parseColor("#10B981")) // Study Emerald
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }
        val iBar = View(this).apply {
            setBackgroundColor(Color.parseColor("#38BDF8")) // Improve Cyan
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }
        val dBar = View(this).apply {
            setBackgroundColor(Color.parseColor("#F43F5E")) // Distract Coral
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }
        val uBar = View(this).apply {
            setBackgroundColor(Color.parseColor("#64748B")) // Utility Slate
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }
        segStudyBar = sBar
        segImproveBar = iBar
        segDistractBar = dBar
        segUtilityBar = uBar
        barContainer.addView(sBar)
        barContainer.addView(iBar)
        barContainer.addView(dBar)
        barContainer.addView(uBar)
        hero.addView(barContainer)

        // 3-Column Structural KPI Strip (Unlocks, App Opens, Peak Hour)
        val kpiRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        fun buildKpiCell(title: String, valueColor: Int): Pair<LinearLayout, TextView> {
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(4f)
                    marginStart = dp(4f)
                }
                background = GradientDrawable().apply {
                    cornerRadius = dp(12f).toFloat()
                    setColor(Color.parseColor("#161F30"))
                    setStroke(dp(1f), Color.parseColor("#26354D"))
                }
            }
            val valTv = TextView(this).apply {
                text = "0"
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(valueColor)
            }
            val lblTv = TextView(this).apply {
                text = title
                textSize = 10.5f
                setTextColor(Color.parseColor("#94A3B8"))
                setPadding(0, dp(2f), 0, 0)
            }
            col.addView(valTv)
            col.addView(lblTv)
            return col to valTv
        }

        val (unlockCell, unlockTv) = buildKpiCell("UNLOCKS TODAY", Color.parseColor("#38BDF8"))
        val (launchCell, launchTv) = buildKpiCell("APP OPENS", Color.parseColor("#A78BFA"))
        val (peakCell, peakTv) = buildKpiCell("PEAK HOUR", Color.parseColor("#34D399"))
        tvKpiUnlocks = unlockTv
        tvKpiLaunches = launchTv
        tvKpiPeakHour = peakTv

        kpiRow.addView(unlockCell)
        kpiRow.addView(launchCell)
        kpiRow.addView(peakCell)
        hero.addView(kpiRow)

        val catIndex = rootContainer.indexOfChild(categorySummaryRow).coerceAtLeast(0)
        rootContainer.addView(hero, catIndex)
        wellbeingHeroCard = hero

        // 2. 24-Hour Hourly Activity Breakdown Section (inserted right above "Today by App")
        val hourlyHeader = TextView(this).apply {
            text = "Hourly Activity Timeline (Today)"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(UiThemePrefs.getScreenHeaderTextColor(this@UsageStatsActivity))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8f)
            }
        }
        val hourlyCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
            background = UiThemePrefs.createCardBackground(this@UsageStatsActivity, cornerDp = 16f, isHero = false)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(18f)
            }
        }
        val hChart = SimpleBarChartView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(155f)
            )
        }
        hourlyChartView = hChart
        hourlyCard.addView(hChart)

        val vsIndex = rootContainer.indexOfChild(tvVsYesterday)
        val insertPos = if (vsIndex >= 0) vsIndex + 1 else rootContainer.childCount
        rootContainer.addView(hourlyHeader, insertPos)
        rootContainer.addView(hourlyCard, insertPos + 1)
    }

    private fun applyThemeStyling() {
        val palette = UiThemePrefs.getSelectedPalette(this)
        val rootLinear = categorySummaryRow.parent as? LinearLayout
        val rootScroll = rootLinear?.parent as? ScrollView
        rootScroll?.setBackgroundColor(UiThemePrefs.getScreenBackgroundColor(this))

        val headerColor = UiThemePrefs.getScreenHeaderTextColor(this)
        val subColor = UiThemePrefs.getScreenSubHeaderTextColor(this)

        findViewById<TextView>(R.id.backButton)?.setTextColor(
            if (UiThemePrefs.isWhiteBackgroundActive(this)) Color.parseColor("#0284C7") else palette.secondaryColor
        )
        tvVsYesterday.setTextColor(subColor)

        if (rootLinear != null) {
            for (i in 0 until rootLinear.childCount) {
                val child = rootLinear.getChildAt(i)
                if (child is TextView && child.id != R.id.backButton && child.id != R.id.tvVsYesterday) {
                    child.setTextColor(headerColor)
                } else if (child is LinearLayout && child != wellbeingHeroCard) {
                    child.background = UiThemePrefs.createCardBackground(this, cornerDp = 16f, isHero = false)
                }
            }
        }
        wellbeingHeroCard?.background = UiThemePrefs.createCardBackground(this, cornerDp = 18f, isHero = true)
    }

    private fun refresh() {
        val hasAccess = UsageStatsHelper.hasUsageAccess(this)
        if (!hasAccess) {
            noAccessCard.visibility = View.VISIBLE
            wellbeingHeroCard?.visibility = View.GONE
            categorySummaryRow.visibility = View.GONE
            tvVsYesterday.visibility = View.GONE
            appUsageEmptyText.visibility = View.VISIBLE
            return
        }

        noAccessCard.visibility = View.GONE
        wellbeingHeroCard?.visibility = View.VISIBLE
        categorySummaryRow.visibility = View.VISIBLE
        tvVsYesterday.visibility = View.VISIBLE

        val snapshot = UsageStatsHelper.getTodayWellbeingSnapshot(this, forceRefresh = true)
        bindExecutiveHeroAndCategories(snapshot)
        bindHourlyTimeline(snapshot)
        bindTodayByApp(snapshot)
        bindWeeklyTrend()
    }

    private fun bindExecutiveHeroAndCategories(snapshot: UsageStatsHelper.WellbeingSnapshot) {
        tvHeroTotalTime?.text = UsageStatsHelper.formatPreciseDuration(snapshot.totalScreenTimeMs)
        tvKpiUnlocks?.text = snapshot.unlockCount.toString()
        tvKpiLaunches?.text = snapshot.totalAppLaunches.toString()
        tvKpiPeakHour?.text = if (snapshot.peakHourMinutes > 0) {
            "${UsageStatsHelper.formatHourLabel(snapshot.peakHourIndex)} (${snapshot.peakHourMinutes}m)"
        } else {
            "—"
        }

        val diffMs = snapshot.totalScreenTimeMs - snapshot.yesterdayScreenTimeMs
        val diffMins = kotlin.math.abs(diffMs / 60_000L)
        val diffFormatted = UsageStatsHelper.formatDuration(kotlin.math.abs(diffMs))
        if (snapshot.yesterdayScreenTimeMs <= 0L) {
            tvHeroDeltaPill?.text = "✓ Exact Tracking"
            tvVsYesterday.text = "Digital Wellbeing foreground state machine active • Tap any bar or app below for details"
        } else if (diffMins < 2L) {
            tvHeroDeltaPill?.text = "≈ Same as yesterday"
            tvVsYesterday.text = "On pace with yesterday (${UsageStatsHelper.formatDuration(snapshot.yesterdayScreenTimeMs)} total yesterday)"
        } else if (diffMs < 0L) {
            tvHeroDeltaPill?.text = "▼ $diffFormatted vs yesterday"
            tvHeroDeltaPill?.setTextColor(Color.parseColor("#34D399"))
            tvVsYesterday.text = "Great discipline! Down $diffFormatted compared to yesterday (${UsageStatsHelper.formatDuration(snapshot.yesterdayScreenTimeMs)})"
        } else {
            tvHeroDeltaPill?.text = "▲ $diffFormatted vs yesterday"
            tvHeroDeltaPill?.setTextColor(Color.parseColor("#F59E0B"))
            tvVsYesterday.text = "Up $diffFormatted compared to yesterday (${UsageStatsHelper.formatDuration(snapshot.yesterdayScreenTimeMs)})"
        }

        // Update Category Summary Row
        findViewById<TextView>(R.id.tvStudyMinutes)?.text = UsageStatsHelper.formatDuration(snapshot.studyMs)
        findViewById<TextView>(R.id.tvImproveMinutes)?.text = UsageStatsHelper.formatDuration(snapshot.improveMs)
        findViewById<TextView>(R.id.tvDistractMinutes)?.text = UsageStatsHelper.formatDuration(snapshot.distractMs)
        findViewById<TextView>(R.id.tvIgnoreMinutes)?.text = UsageStatsHelper.formatDuration(snapshot.utilityMs)

        // Update Segmented Proportion Bar weights
        val total = max(1L, snapshot.studyMs + snapshot.improveMs + snapshot.distractMs + snapshot.utilityMs).toFloat()
        (segStudyBar?.layoutParams as? LinearLayout.LayoutParams)?.weight =
            (snapshot.studyMs / total).coerceAtLeast(if (snapshot.studyMs > 0L) 0.05f else 0.0f)
        (segImproveBar?.layoutParams as? LinearLayout.LayoutParams)?.weight =
            (snapshot.improveMs / total).coerceAtLeast(if (snapshot.improveMs > 0L) 0.05f else 0.0f)
        (segDistractBar?.layoutParams as? LinearLayout.LayoutParams)?.weight =
            (snapshot.distractMs / total).coerceAtLeast(if (snapshot.distractMs > 0L) 0.05f else 0.0f)
        (segUtilityBar?.layoutParams as? LinearLayout.LayoutParams)?.weight =
            (snapshot.utilityMs / total).coerceAtLeast(if (snapshot.utilityMs > 0L) 0.05f else 0.0f)

        if (snapshot.totalScreenTimeMs <= 0L) {
            (segStudyBar?.layoutParams as? LinearLayout.LayoutParams)?.weight = 1f
        }
        segStudyBar?.parent?.requestLayout()
    }

    private fun bindHourlyTimeline(snapshot: UsageStatsHelper.WellbeingSnapshot) {
        val chart = hourlyChartView ?: return
        val palette = UiThemePrefs.getSelectedPalette(this)
        // Group into 8 clean 3-hour blocks or active hours so labels stay crisp and legible on all screen widths
        val entries = ArrayList<SimpleBarChartView.BarEntry>()
        val blockLabels = arrayOf("12A", "3A", "6A", "9A", "12P", "3P", "6P", "9P")
        for (block in 0 until 8) {
            val hStart = block * 3
            val blockMins = snapshot.hourlyMinutes[hStart] +
                snapshot.hourlyMinutes[hStart + 1] +
                snapshot.hourlyMinutes[hStart + 2]
            val barColor = when {
                blockMins >= 90 -> Color.parseColor("#F43F5E")
                blockMins >= 45 -> Color.parseColor("#F59E0B")
                else -> palette.accentColor
            }
            entries.add(
                SimpleBarChartView.BarEntry(
                    blockLabels[block],
                    blockMins.toFloat(),
                    if (blockMins > 0) "${blockMins}m" else "",
                    barColor
                )
            )
        }
        chart.entries = entries
    }

    private fun bindTodayByApp(snapshot: UsageStatsHelper.WellbeingSnapshot) {
        val pm = packageManager
        val palette = UiThemePrefs.getSelectedPalette(this)
        val excluded = UsageStatsHelper.getDigitalWellbeingExcludedPackages(this)
        val sortedStats = snapshot.perAppStats.values
            .filter { !excluded.contains(it.packageName) && it.durationMs >= 10_000L }
            .sortedByDescending { it.durationMs }

        if (sortedStats.isEmpty()) {
            appUsageEmptyText.visibility = View.VISIBLE
            appUsageList.visibility = View.GONE
            todayChart.entries = emptyList()
            return
        }

        appUsageEmptyText.visibility = View.GONE
        appUsageList.visibility = View.VISIBLE

        // Top 6 apps bar chart
        val topBarEntries = sortedStats.take(6).map { stat ->
            val label = try {
                val ai = pm.getApplicationInfo(stat.packageName, 0)
                pm.getApplicationLabel(ai).toString().take(8)
            } catch (_: Throwable) {
                stat.packageName.substringAfterLast('.').take(8)
            }
            val mins = (stat.durationMs / 60_000f).coerceAtLeast(0.5f)
            val color = when (stat.category) {
                "STUDY" -> Color.parseColor("#10B981")
                "IMPROVE" -> Color.parseColor("#38BDF8")
                "IGNORED" -> {
                    val customLimit = TimeLimitManager.getLimitMinutes(this, stat.packageName)
                    val cap = if (customLimit in 1..29) customLimit else 30
                    if (stat.durationMs >= cap * 60_000L) Color.parseColor("#F43F5E") else Color.parseColor("#F59E0B")
                }
                "UTILITY" -> Color.parseColor("#64748B")
                else -> palette.accentColor
            }
            SimpleBarChartView.BarEntry(
                label,
                mins,
                UsageStatsHelper.formatShort(stat.durationMs),
                color
            )
        }
        todayChart.entries = topBarEntries

        // Build enriched Digital Wellbeing rows
        val totalMs = max(1L, snapshot.totalScreenTimeMs)
        val defaultCap = UiThemePrefs.getDefaultSuggestionLimitMinutes(this)
        val rows = sortedStats.map { stat ->
            val rawName = try {
                val ai = pm.getApplicationInfo(stat.packageName, 0)
                pm.getApplicationLabel(ai).toString()
            } catch (_: Throwable) {
                stat.packageName
            }
            val icon = try {
                pm.getApplicationIcon(stat.packageName)
            } catch (_: Throwable) {
                null
            }
            val sharePct = ((stat.durationMs * 100f) / totalMs).roundToInt().coerceIn(1, 100)
            val catTag = when (stat.category) {
                "STUDY" -> "STUDY"
                "IMPROVE" -> "IMPROVE"
                "BROWSER" -> "WEB 24/7"
                "IGNORED" -> {
                    val customLimit = TimeLimitManager.getLimitMinutes(this, stat.packageName)
                    val cap = if (customLimit in 1..29) customLimit else 30
                    if (stat.durationMs >= cap * 60_000L) "IGNORED • ${cap}m CAP REACHED" else "IGNORED • ${cap}m CAP"
                }
                "UTILITY" -> "SYSTEM"
                else -> {
                    val customLimit = TimeLimitManager.getLimitMinutes(this, stat.packageName)
                    val cap = if (customLimit > 0) customLimit else defaultCap
                    "${cap}m CAP"
                }
            }
            val enrichedName = "$rawName  •  $catTag  (${stat.launchCount} opens · $sharePct%)"
            val colorRes = when (stat.category) {
                "STUDY" -> R.color.success_green
                "IMPROVE" -> R.color.accent_purple
                "IGNORED" -> {
                    val customLimit = TimeLimitManager.getLimitMinutes(this, stat.packageName)
                    val cap = if (customLimit in 1..29) customLimit else 30
                    if (stat.durationMs >= cap * 60_000L) R.color.distract_red else R.color.text_secondary
                }
                "UTILITY" -> R.color.text_secondary
                else -> R.color.distract_red
            }
            val mins = max(1L, (stat.durationMs + 30_000L) / 60_000L)
            AppUsageRow(enrichedName, icon, mins, colorRes)
        }

        appUsageList.adapter = AppUsageAdapter(rows)
    }

    private fun bindWeeklyTrend() {
        val palette = UiThemePrefs.getSelectedPalette(this)
        val weekly = UsageStatsHelper.getWeeklyTotals(this)
        val entries = weekly.map { (dayLabel, ms) ->
            val mins = (ms / 60_000f)
            val color = if (dayLabel == "Today") {
                Color.parseColor("#38BDF8")
            } else {
                palette.accentColor
            }
            SimpleBarChartView.BarEntry(
                dayLabel,
                mins,
                UsageStatsHelper.formatShort(ms),
                color
            )
        }
        weeklyChart.entries = entries
    }
}
