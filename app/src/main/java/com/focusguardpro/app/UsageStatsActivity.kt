package com.focusguardpro.app

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

class UsageStatsActivity : AppCompatActivity() {

    private var refreshJob: Job? = null
    private var daySelectionJob: Job? = null

    private lateinit var todayChart: SimpleBarChartView
    private lateinit var weeklyChart: SimpleBarChartView
    private lateinit var appUsageList: RecyclerView
    private lateinit var appUsageEmptyText: TextView
    private lateinit var noAccessCard: LinearLayout
    private lateinit var categorySummaryRow: LinearLayout
    private lateinit var tvVsYesterday: TextView

    // Dynamically injected Digital Wellbeing Executive Header, Day Switcher & 24h Hourly Timeline views
    private var wellbeingHeroCard: LinearLayout? = null
    private var tvHeroTotalTime: TextView? = null
    private var tvHeroDeltaPill: TextView? = null
    private var tvSelectedDayLabel: TextView? = null
    private var btnPrevDay: TextView? = null
    private var btnNextDay: TextView? = null
    private var tvKpiUnlocks: TextView? = null
    private var tvKpiLaunches: TextView? = null
    private var tvKpiPeakHour: TextView? = null
    private var segStudyBar: View? = null
    private var segImproveBar: View? = null
    private var segDistractBar: View? = null
    private var segUtilityBar: View? = null
    private var hourlyHeaderTv: TextView? = null
    private var byAppHeaderTv: TextView? = null
    private var hourlyChartView: SimpleBarChartView? = null

    private var weeklyRanges: List<UsageStatsHelper.WeeklyDayRange> = emptyList()
    private var selectedDayIndex: Int = 6 // Default to Today (index 6 in 7-day list)

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

        // Interactive Last 7 Days bar click: tapping any day (e.g. Tue Nov 17) loads that day's full breakdown
        weeklyChart.onBarClick = { clickedIndex ->
            if (clickedIndex in weeklyRanges.indices) {
                selectDay(clickedIndex, scrollToTop = true)
            }
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

        // Locate existing "Today by App" TextView so we can update its title when switching days
        for (i in 0 until rootContainer.childCount) {
            val v = rootContainer.getChildAt(i)
            if (v is TextView && v.text?.toString()?.contains("by App", ignoreCase = true) == true) {
                byAppHeaderTv = v
                break
            }
        }

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
            setPadding(0, dp(4f), 0, dp(6f))
        }
        tvHeroTotalTime = totalTv
        hero.addView(totalTv)

        // Interactive Date Navigator Row (<  Tue, Nov 17  >) just like Android Digital Wellbeing
        val dayNavRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12f)
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(10f).toFloat()
                setColor(Color.parseColor("#131C2B"))
                setStroke(dp(1f), Color.parseColor("#26354D"))
            }
        }

        val prevBtn = TextView(this).apply {
            text = "‹ Prev"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(dp(10f), dp(4f), dp(10f), dp(4f))
            setOnClickListener {
                if (selectedDayIndex > 0) {
                    selectDay(selectedDayIndex - 1, scrollToTop = false)
                }
            }
        }
        btnPrevDay = prevBtn

        val dayLabelTv = TextView(this).apply {
            text = "Today"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#F1F5F9"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        tvSelectedDayLabel = dayLabelTv

        val nextBtn = TextView(this).apply {
            text = "Next ›"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(dp(10f), dp(4f), dp(10f), dp(4f))
            setOnClickListener {
                if (selectedDayIndex < weeklyRanges.size - 1) {
                    selectDay(selectedDayIndex + 1, scrollToTop = false)
                }
            }
        }
        btnNextDay = nextBtn

        dayNavRow.addView(prevBtn)
        dayNavRow.addView(dayLabelTv)
        dayNavRow.addView(nextBtn)
        hero.addView(dayNavRow)

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

        val (unlockCell, unlockTv) = buildKpiCell("UNLOCKS", Color.parseColor("#38BDF8"))
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
        hourlyHeaderTv = hourlyHeader

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
                dp(145f)
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

        val kpiParent = tvKpiUnlocks?.parent?.parent as? LinearLayout
        if (kpiParent != null) {
            for (i in 0 until kpiParent.childCount) {
                val cell = kpiParent.getChildAt(i) as? LinearLayout ?: continue
                cell.background = GradientDrawable().apply {
                    cornerRadius = dp(12f).toFloat()
                    setColor(UiThemePrefs.getKpiCellBackgroundColor(this@UsageStatsActivity))
                    setStroke(dp(1f), UiThemePrefs.getKpiCellStrokeColor(this@UsageStatsActivity))
                }
                (cell.getChildAt(1) as? TextView)?.setTextColor(subColor)
            }
        }

        UiThemePrefs.applyWindowAndViewTreeTheme(this, rootScroll)
    }

    override fun onPause() {
        super.onPause()
        refreshJob?.cancel()
        daySelectionJob?.cancel()
    }

    private data class PreparedDayData(
        val snapshot: UsageStatsHelper.WellbeingSnapshot,
        val topBarEntries: List<SimpleBarChartView.BarEntry>,
        val rows: List<AppUsageRow>,
        val dayRange: UsageStatsHelper.WeeklyDayRange?
    )

    private fun buildPreparedDayData(
        snapshot: UsageStatsHelper.WellbeingSnapshot,
        dayRange: UsageStatsHelper.WeeklyDayRange?
    ): PreparedDayData {
        val pm = packageManager
        val palette = UiThemePrefs.getSelectedPalette(this)
        val excluded = UsageStatsHelper.getDigitalWellbeingExcludedPackages(this)
        val sortedStats = snapshot.perAppStats.values
            .filter { !excluded.contains(it.packageName) && it.durationMs >= 10_000L }
            .sortedByDescending { it.durationMs }

        val topBarEntries = sortedStats.take(6).map { stat ->
            val label = AppMetadataCache.getLabel(pm, stat.packageName).take(8)
            val mins = (stat.durationMs / 60_000f).coerceAtLeast(0.5f)
            val color = when (stat.category) {
                "STUDY" -> Color.parseColor("#10B981")
                "IMPROVE" -> Color.parseColor("#38BDF8")
                "IGNORED" -> {
                    val customLimit = BlockPolicyEngine.getFastLimitMinutes(this, stat.packageName)
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

        val totalMs = max(1L, snapshot.totalScreenTimeMs)
        val defaultCap = UiThemePrefs.getDefaultSuggestionLimitMinutes(this)
        val rows = sortedStats.map { stat ->
            val rawName = AppMetadataCache.getLabel(pm, stat.packageName)
            val icon = AppMetadataCache.getIcon(pm, stat.packageName)
            val sharePct = ((stat.durationMs * 100f) / totalMs).roundToInt().coerceIn(1, 100)
            val catTag = when (stat.category) {
                "STUDY" -> "STUDY"
                "IMPROVE" -> "IMPROVE"
                "BROWSER" -> "WEB 24/7"
                "IGNORED" -> {
                    val customLimit = BlockPolicyEngine.getFastLimitMinutes(this, stat.packageName)
                    val cap = if (customLimit in 1..29) customLimit else 30
                    if (stat.durationMs >= cap * 60_000L) "IGNORED • ${cap}m CAP REACHED" else "IGNORED • ${cap}m CAP"
                }
                "UTILITY" -> "SYSTEM"
                else -> {
                    val customLimit = BlockPolicyEngine.getFastLimitMinutes(this, stat.packageName)
                    val cap = if (customLimit > 0) customLimit else defaultCap
                    "${cap}m CAP"
                }
            }
            val formattedTime = UsageStatsHelper.formatDuration(stat.durationMs)
            val enrichedName = "$rawName  •  $formattedTime  ($catTag · ${stat.launchCount} opens · $sharePct%)"
            val colorRes = when (stat.category) {
                "STUDY" -> R.color.success_green
                "IMPROVE" -> R.color.accent_purple
                "IGNORED" -> {
                    val customLimit = BlockPolicyEngine.getFastLimitMinutes(this, stat.packageName)
                    val cap = if (customLimit in 1..29) customLimit else 30
                    if (stat.durationMs >= cap * 60_000L) R.color.distract_red else R.color.text_secondary
                }
                "UTILITY" -> R.color.text_secondary
                else -> R.color.distract_red
            }
            val mins = max(1L, (stat.durationMs + 30_000L) / 60_000L)
            AppUsageRow(enrichedName, icon, mins, colorRes)
        }

        return PreparedDayData(
            snapshot = snapshot,
            topBarEntries = topBarEntries,
            rows = rows,
            dayRange = dayRange
        )
    }

    private fun bindDayBreakdown(prepared: PreparedDayData) {
        val dayRange = prepared.dayRange
        val isToday = dayRange?.isToday ?: true
        val dayTitle = dayRange?.fullDateLabel ?: "Today"

        tvSelectedDayLabel?.text = dayTitle
        btnPrevDay?.alpha = if (selectedDayIndex > 0) 1.0f else 0.35f
        btnNextDay?.alpha = if (selectedDayIndex < weeklyRanges.size - 1) 1.0f else 0.35f

        hourlyHeaderTv?.text = if (isToday) {
            "Hourly Activity Timeline (Today)"
        } else {
            "Hourly Activity Timeline (${dayRange?.shortLabel ?: dayTitle})"
        }
        byAppHeaderTv?.text = if (isToday) {
            getString(R.string.today_by_app)
        } else {
            "${dayRange?.fullDateLabel ?: "Selected Day"} — Apps Used"
        }

        bindExecutiveHeroAndCategories(prepared.snapshot, dayRange)
        bindHourlyTimeline(prepared.snapshot)
        if (prepared.rows.isEmpty()) {
            appUsageEmptyText.visibility = View.VISIBLE
            appUsageList.visibility = View.GONE
            todayChart.entries = emptyList()
        } else {
            appUsageEmptyText.visibility = View.GONE
            appUsageList.visibility = View.VISIBLE
            todayChart.entries = prepared.topBarEntries
            appUsageList.adapter = AppUsageAdapter(prepared.rows)
        }
        renderWeeklyChartSelection()
    }

    private fun renderWeeklyChartSelection() {
        if (weeklyRanges.isEmpty()) return
        val palette = UiThemePrefs.getSelectedPalette(this)
        val entries = weeklyRanges.mapIndexed { idx, item ->
            val mins = (item.totalScreenTimeMs / 60_000f)
            val isSelected = idx == selectedDayIndex
            val color = when {
                isSelected -> Color.parseColor("#16D99A") // Highlighted active bar in Emerald
                item.isToday -> Color.parseColor("#38BDF8")
                else -> palette.accentColor
            }
            SimpleBarChartView.BarEntry(
                item.shortLabel,
                mins,
                UsageStatsHelper.formatShort(item.totalScreenTimeMs),
                color
            )
        }
        weeklyChart.entries = entries
    }

    private fun selectDay(index: Int, scrollToTop: Boolean) {
        if (index !in weeklyRanges.indices) return
        selectedDayIndex = index
        val targetRange = weeklyRanges[index]
        renderWeeklyChartSelection()

        daySelectionJob?.cancel()
        daySelectionJob = lifecycleScope.launch(Dispatchers.IO) {
            val snap = if (targetRange.isToday) {
                UsageStatsHelper.getTodayWellbeingSnapshot(this@UsageStatsActivity, forceRefresh = false)
            } else {
                UsageStatsHelper.getDayWellbeingSnapshot(
                    this@UsageStatsActivity,
                    targetRange.startMs,
                    targetRange.endMs,
                    forceRefresh = false
                )
            }
            val prepared = buildPreparedDayData(snap, targetRange)
            withContext(Dispatchers.Main) {
                if (!isFinishing && !isDestroyed) {
                    bindDayBreakdown(prepared)
                    if (scrollToTop) {
                        val rootScroll = (categorySummaryRow.parent?.parent) as? ScrollView
                        rootScroll?.smoothScrollTo(0, 0)
                    }
                }
            }
        }
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

        BlockPolicyEngine.warmCacheAsync(this)

        // Fast zero-latency path: immediately show warm/in-memory snapshot so Today by App is never blank
        val cached = UsageStatsHelper.getCachedTodaySnapshot(this)
        if (cached != null && selectedDayIndex == 6) {
            bindExecutiveHeroAndCategories(cached, null)
            bindHourlyTimeline(cached)
            tvHeroDeltaPill?.text = "Syncing..."
        }

        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch(Dispatchers.IO) {
            if (cached != null && appUsageList.adapter == null && selectedDayIndex == 6) {
                val warmPrepared = buildPreparedDayData(cached, null)
                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed) {
                        bindDayBreakdown(warmPrepared)
                        tvHeroDeltaPill?.text = "Syncing..."
                    }
                }
            }

            // Stage 1: Load 7-day ranges and authoritative snapshot for currently selected day
            val ranges = UsageStatsHelper.getWeeklyDayRanges(this@UsageStatsActivity)
            val safeIdx = selectedDayIndex.coerceIn(0, (ranges.size - 1).coerceAtLeast(0))
            val activeRange = ranges.getOrNull(safeIdx)

            val snapshot = if (activeRange == null || activeRange.isToday) {
                UsageStatsHelper.getTodayWellbeingSnapshot(this@UsageStatsActivity, forceRefresh = true)
            } else {
                UsageStatsHelper.getDayWellbeingSnapshot(
                    this@UsageStatsActivity,
                    activeRange.startMs,
                    activeRange.endMs,
                    forceRefresh = true
                )
            }
            val prepared = buildPreparedDayData(snapshot, activeRange)

            withContext(Dispatchers.Main) {
                if (!isFinishing && !isDestroyed) {
                    weeklyRanges = ranges
                    selectedDayIndex = safeIdx
                    bindDayBreakdown(prepared)
                }
            }
        }
    }

    private fun bindExecutiveHeroAndCategories(
        snapshot: UsageStatsHelper.WellbeingSnapshot,
        dayRange: UsageStatsHelper.WeeklyDayRange?
    ) {
        tvHeroTotalTime?.text = UsageStatsHelper.formatPreciseDuration(snapshot.totalScreenTimeMs)
        tvKpiUnlocks?.text = snapshot.unlockCount.toString()
        tvKpiLaunches?.text = snapshot.totalAppLaunches.toString()
        tvKpiPeakHour?.text = if (snapshot.peakHourMinutes > 0) {
            "${UsageStatsHelper.formatHourLabel(snapshot.peakHourIndex)} (${snapshot.peakHourMinutes}m)"
        } else {
            "—"
        }

        val isToday = dayRange?.isToday ?: true
        val diffMs = snapshot.totalScreenTimeMs - snapshot.yesterdayScreenTimeMs
        val diffMins = kotlin.math.abs(diffMs / 60_000L)
        val diffFormatted = UsageStatsHelper.formatDuration(kotlin.math.abs(diffMs))
        if (!isToday) {
            tvHeroDeltaPill?.text = dayRange?.shortLabel ?: "History"
            tvHeroDeltaPill?.setTextColor(Color.parseColor("#16D99A"))
            tvVsYesterday.text = "Showing full app breakdown for ${dayRange?.fullDateLabel ?: "selected day"} • Tap any bar in Last 7 Days to switch"
        } else if (snapshot.yesterdayScreenTimeMs <= 0L) {
            tvHeroDeltaPill?.text = "✓ Exact Tracking"
            tvVsYesterday.text = "Tap any day bar in 'Last 7 Days' below (or use ◀ ▶ above) to inspect past days"
        } else if (diffMins < 2L) {
            tvHeroDeltaPill?.text = "≈ Same as yesterday"
            tvVsYesterday.text = "On pace with yesterday (${UsageStatsHelper.formatDuration(snapshot.yesterdayScreenTimeMs)} total yesterday) • Tap any day bar below for details"
        } else if (diffMs < 0L) {
            tvHeroDeltaPill?.text = "▼ $diffFormatted vs yesterday"
            tvHeroDeltaPill?.setTextColor(Color.parseColor("#34D399"))
            tvVsYesterday.text = "Down $diffFormatted vs yesterday (${UsageStatsHelper.formatDuration(snapshot.yesterdayScreenTimeMs)}) • Tap any day bar in Last 7 Days to inspect"
        } else {
            tvHeroDeltaPill?.text = "▲ $diffFormatted vs yesterday"
            tvHeroDeltaPill?.setTextColor(Color.parseColor("#F59E0B"))
            tvVsYesterday.text = "Up $diffFormatted vs yesterday (${UsageStatsHelper.formatDuration(snapshot.yesterdayScreenTimeMs)}) • Tap any day bar in Last 7 Days to inspect"
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
}
