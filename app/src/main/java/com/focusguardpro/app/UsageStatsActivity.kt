package com.focusguardpro.app

import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class UsageStatsActivity : AppCompatActivity() {

    private var refreshJob: Job? = null
    private var weeklyJob: Job? = null
    private var liveTickerJob: Job? = null
    private var daySelectionJob: Job? = null
    private var prewarmJob: Job? = null

    companion object {
        @Volatile
        private var lastPreparedTodayCache: PreparedDayData? = null
        @Volatile
        private var lastPreparedTodayStartMs: Long = 0L
        @Volatile
        private var lastWeeklyRangesCache: List<UsageStatsHelper.WeeklyDayRange> = emptyList()
    }

    private lateinit var todayChart: SimpleBarChartView
    private lateinit var weeklyChart: SimpleBarChartView
    private lateinit var appUsageList: RecyclerView
    private lateinit var appUsageEmptyText: TextView
    private lateinit var noAccessCard: LinearLayout
    private lateinit var categorySummaryRow: LinearLayout
    private lateinit var tvVsYesterday: TextView

    // Dynamically injected Digital Wellbeing Executive Header, Day Switcher, Filter Chips & 24h Hourly Timeline views
    private var wellbeingHeroCard: LinearLayout? = null
    private var tvHeroTotalTime: TextView? = null
    private var tvHeroDeltaPill: TextView? = null
    private var tvSelectedDayLabel: TextView? = null
    private var btnPrevDay: TextView? = null
    private var btnNextDay: TextView? = null
    private var tvKpiUnlocks: TextView? = null
    private var tvKpiLaunches: TextView? = null
    private var tvKpiPeakHour: TextView? = null
    private var heroProportionBar: SegmentedProportionBarView? = null
    private var hourlyHeaderTv: TextView? = null
    private var hourlySubtitleTv: TextView? = null
    private var byAppHeaderTv: TextView? = null
    private var hourlyChartView: HourlyScreenTimeCurveView? = null
    private var filterChipContainer: LinearLayout? = null
    private var filterChipViews: MutableMap<AppFilter, TextView> = linkedMapOf()

    private enum class AppFilter(val label: String) {
        ALL("All"),
        STUDY("📚 Study"),
        IMPROVE("⚡ Improve"),
        BROWSERS("🌐 Browsers"),
        DISTRACT("🚫 Distract")
    }

    private var currentFilter: AppFilter = AppFilter.ALL
    private var currentPreparedDay: PreparedDayData? = null
    private val preparedDayCache = ConcurrentHashMap<String, PreparedDayData>()

    private var weeklyRanges: List<UsageStatsHelper.WeeklyDayRange> = emptyList()
    private var selectedDayIndex: Int = 6 // Default to Today (index 6 in 7-day list)
    private var smoothAdapter: SmoothAppUsageAdapter? = null
    private var proportionBarAnimator: ValueAnimator? = null

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
        appUsageList.setHasFixedSize(false)
        appUsageList.itemAnimator = null
        appUsageList.setItemViewCacheSize(24)
        smoothAdapter = SmoothAppUsageAdapter { row ->
            if (row.isBrowser) {
                SensoryFeedbackEngine.tap(this, appUsageList)
                val dayStartMs = currentPreparedDay?.dayRange?.startMs ?: startOfTodayMs()
                val dayLabel = currentPreparedDay?.dayRange?.fullDateLabel ?: "Today"
                showBrowserWebsitesDialog(row.packageName, row.displayName, dayStartMs, dayLabel)
            }
        }
        appUsageList.adapter = smoothAdapter

        findViewById<Button>(R.id.btnGrantUsageAccess)?.setOnClickListener {
            SettingsTamperGuard.grantPermissionSetupGrace(this, 180_000L)
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }

        // Interactive Last 7 Days bar click: tapping any day (e.g. Tue Nov 17) loads that day's full breakdown
        weeklyChart.onBarClick = { clickedIndex ->
            if (clickedIndex in weeklyRanges.indices) {
                SensoryFeedbackEngine.tap(this, weeklyChart)
                selectDay(clickedIndex, scrollToTop = true)
            }
        }

        injectWellbeingExecutiveViews()
    }

    private fun startOfTodayMs(): Long {
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
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
            text = "⚡ Live"
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(dp(10f), dp(4f), dp(10f), dp(4f))
            background = GradientDrawable().apply {
                cornerRadius = dp(12f).toFloat()
                setColor(Color.parseColor("#1E293B"))
                setStroke(dp(1f), palette.accentColor)
            }
            setOnClickListener { v ->
                SensoryFeedbackEngine.tap(this@UsageStatsActivity, v)
                triggerInstantLiveSync(showLatencyFeedback = true)
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
            setOnClickListener { v ->
                if (selectedDayIndex > 0) {
                    SensoryFeedbackEngine.tap(this@UsageStatsActivity, v)
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
            setOnClickListener { v ->
                if (selectedDayIndex < weeklyRanges.size - 1) {
                    SensoryFeedbackEngine.tap(this@UsageStatsActivity, v)
                    selectDay(selectedDayIndex + 1, scrollToTop = false)
                }
            }
        }
        btnNextDay = nextBtn

        dayNavRow.addView(prevBtn)
        dayNavRow.addView(dayLabelTv)
        dayNavRow.addView(nextBtn)
        hero.addView(dayNavRow)

        // Multi-segment Category Proportion Bar (Canvas-drawn, exact 100% normalization, smooth 60fps animation)
        val proportionBar = SegmentedProportionBarView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(9f)
            ).apply {
                bottomMargin = dp(14f)
            }
        }
        heroProportionBar = proportionBar
        hero.addView(proportionBar)

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

        // 2. 24-Hour Hourly Activity Curve Section (inserted right above "Today by App")
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
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            background = UiThemePrefs.createCardBackground(this@UsageStatsActivity, cornerDp = 16f, isHero = false)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(18f)
            }
        }

        val hSub = TextView(this).apply {
            text = "Drag across the curve to inspect 3-hour screen time windows"
            textSize = 11.5f
            setTextColor(Color.parseColor("#8995A3"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(6f)
            }
        }
        hourlySubtitleTv = hSub
        hourlyCard.addView(hSub)

        val hChart = HourlyScreenTimeCurveView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(176f)
            )
            onWindowSelected = { pt ->
                val hrs = pt.minutes / 60
                val mins = pt.minutes % 60
                val durStr = if (hrs > 0) "${hrs}h ${mins}m (${pt.minutes}m)" else "${pt.minutes}m"
                val intensity = when {
                    pt.minutes >= 90 -> "High screen load"
                    pt.minutes >= 45 -> "Moderate usage"
                    pt.minutes > 0 -> "Light usage"
                    else -> "Zero screen time"
                }
                hourlySubtitleTv?.text = "${pt.rangeLabel}: $durStr • $intensity"
            }
        }
        hourlyChartView = hChart
        hourlyCard.addView(hChart)

        val vsIndex = rootContainer.indexOfChild(tvVsYesterday)
        val insertPos = if (vsIndex >= 0) vsIndex + 1 else rootContainer.childCount
        rootContainer.addView(hourlyHeader, insertPos)
        rootContainer.addView(hourlyCard, insertPos + 1)

        // 3. Smooth Interactive Category Filter Chips right above appUsageList
        val filterScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10f)
            }
        }
        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        filterChipViews.clear()
        AppFilter.values().forEach { filter ->
            val chip = TextView(this).apply {
                text = filter.label
                textSize = 11.5f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = dp(7f)
                }
                setOnClickListener { v ->
                    if (currentFilter != filter) {
                        SensoryFeedbackEngine.tap(this@UsageStatsActivity, v)
                        currentFilter = filter
                        updateFilterChipStyles()
                        applyFilterToList()
                    }
                }
            }
            filterChipViews[filter] = chip
            chipRow.addView(chip)
        }
        filterChipContainer = chipRow
        filterScroll.addView(chipRow)
        // appUsageList lives inside its own card LinearLayout — insert filter chips directly above that card!
        val appListCard = appUsageList.parent as? View
        val listCardIdx = if (appListCard != null) rootContainer.indexOfChild(appListCard) else -1
        if (listCardIdx >= 0) {
            rootContainer.addView(filterScroll, listCardIdx)
        } else {
            rootContainer.addView(filterScroll)
        }
        updateFilterChipStyles()
    }

    private fun updateFilterChipStyles() {
        val isWhite = UiThemePrefs.isWhiteBackgroundActive(this)
        val palette = UiThemePrefs.getSelectedPalette(this)
        val prepared = currentPreparedDay
        filterChipViews.forEach { (filter, tv) ->
            val count = prepared?.let { countForFilter(it.rows, filter) }
            tv.text = if (count != null && count > 0 && filter != AppFilter.ALL) {
                "${filter.label} ($count)"
            } else if (count != null && filter == AppFilter.ALL) {
                "All ($count)"
            } else {
                filter.label
            }
            val selected = filter == currentFilter
            val activeAccent = when (filter) {
                AppFilter.STUDY -> Color.parseColor("#10B981")
                AppFilter.IMPROVE -> Color.parseColor("#38BDF8")
                AppFilter.BROWSERS -> Color.parseColor("#A78BFA")
                AppFilter.DISTRACT -> Color.parseColor("#F43F5E")
                AppFilter.ALL -> palette.accentColor
            }
            if (selected) {
                tv.setTextColor(if (isWhite) Color.WHITE else Color.WHITE)
                tv.background = GradientDrawable().apply {
                    cornerRadius = dp(18f).toFloat()
                    setColor(activeAccent)
                }
            } else {
                tv.setTextColor(if (isWhite) Color.parseColor("#475569") else Color.parseColor("#94A3B8"))
                tv.background = GradientDrawable().apply {
                    cornerRadius = dp(18f).toFloat()
                    setColor(if (isWhite) Color.parseColor("#F1F5F9") else Color.parseColor("#161F30"))
                    setStroke(dp(1f), if (isWhite) Color.parseColor("#CBD5E1") else Color.parseColor("#26354D"))
                }
            }
        }
    }

    private fun countForFilter(rows: List<RichUsageRow>, filter: AppFilter): Int = when (filter) {
        AppFilter.ALL -> rows.size
        AppFilter.STUDY -> rows.count { it.category == "STUDY" }
        AppFilter.IMPROVE -> rows.count { it.category == "IMPROVE" }
        AppFilter.BROWSERS -> rows.count { it.isBrowser }
        AppFilter.DISTRACT -> rows.count { !it.isBrowser && it.category != "STUDY" && it.category != "IMPROVE" && it.category != "UTILITY" }
    }

    private fun applyFilterToList() {
        val prepared = currentPreparedDay ?: return
        val filtered = when (currentFilter) {
            AppFilter.ALL -> prepared.rows
            AppFilter.STUDY -> prepared.rows.filter { it.category == "STUDY" }
            AppFilter.IMPROVE -> prepared.rows.filter { it.category == "IMPROVE" }
            AppFilter.BROWSERS -> prepared.rows.filter { it.isBrowser }
            AppFilter.DISTRACT -> prepared.rows.filter {
                !it.isBrowser && it.category != "STUDY" && it.category != "IMPROVE" && it.category != "UTILITY"
            }
        }
        if (filtered.isEmpty()) {
            appUsageEmptyText.text = if (currentFilter == AppFilter.ALL) {
                "No app usage recorded for this day yet."
            } else {
                "No ${currentFilter.label.substringAfter(" ")} apps recorded on this day."
            }
            appUsageEmptyText.visibility = View.VISIBLE
            appUsageList.visibility = View.GONE
            smoothAdapter?.submitList(emptyList())
        } else {
            appUsageEmptyText.visibility = View.GONE
            appUsageList.visibility = View.VISIBLE
            smoothAdapter?.submitList(filtered)
        }
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
        updateFilterChipStyles()
    }

    override fun onPause() {
        super.onPause()
        refreshJob?.cancel()
        weeklyJob?.cancel()
        liveTickerJob?.cancel()
        daySelectionJob?.cancel()
        prewarmJob?.cancel()
        proportionBarAnimator?.cancel()
    }

    private data class RichUsageRow(
        val packageName: String,
        val displayName: String,
        val subtitle: String,
        val durationLabel: String,
        val icon: Drawable?,
        val durationMs: Long,
        val sharePct: Int,
        val category: String,
        val isBrowser: Boolean,
        val isUninstalled: Boolean = false,
        val accentColor: Int,
        val primaryBarFraction: Float,
        val primaryBarColor: Int,
        val secondaryBarFraction: Float = 0f,
        val secondaryBarColor: Int = Color.TRANSPARENT
    )

    private data class PreparedDayData(
        val snapshot: UsageStatsHelper.WellbeingSnapshot,
        val topBarEntries: List<SimpleBarChartView.BarEntry>,
        val rows: List<RichUsageRow>,
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
            val label = FocusSelfUsageTracker.getVirtualShortLabel(stat.packageName)
                ?: AppMetadataCache.getLabel(pm, stat.packageName, this).take(8)
            val mins = (stat.durationMs / 60_000f).coerceAtLeast(0.5f)
            val color = when (stat.category) {
                "STUDY" -> Color.parseColor("#10B981")
                "IMPROVE" -> Color.parseColor("#38BDF8")
                "BROWSER" -> Color.parseColor("#A78BFA")
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
        val focusEliteIcon: Drawable? = ContextCompat.getDrawable(this, R.mipmap.ic_launcher)
        val dayStartMs = dayRange?.startMs ?: startOfTodayMs()
        val browserSiteCounts = BrowserWebsiteTracker.getBrowserSiteCountsForDay(this, dayStartMs)

        val rows = sortedStats.map { stat ->
            val isVirtualSelf = FocusSelfUsageTracker.isVirtualFocusElitePackage(stat.packageName)
            val isBrowser = stat.category == "BROWSER" || BlockPolicyEngine.isBrowserPackage(stat.packageName)
            val isUninstalled = !isVirtualSelf && !AppMetadataCache.isPackageInstalled(pm, stat.packageName)
            val rawName = FocusSelfUsageTracker.getVirtualDisplayName(stat.packageName)
                ?: AppMetadataCache.getLabel(pm, stat.packageName, this)
            val icon = when {
                isVirtualSelf -> focusEliteIcon ?: AppMetadataCache.getIcon(pm, packageName)
                isUninstalled -> AppMetadataCache.getIcon(pm, stat.packageName)
                    ?: AppMetadataCache.createDeletedAppBadgeDrawable(this, rawName)
                else -> AppMetadataCache.getIcon(pm, stat.packageName)
                    ?: AppMetadataCache.createDeletedAppBadgeDrawable(this, rawName)
            }
            val sharePct = ((stat.durationMs * 100f) / totalMs).roundToInt().coerceIn(1, 100)
            val opensPart = if (stat.launchCount > 0) " · ${stat.launchCount} ${if (stat.launchCount == 1) "open" else "opens"}" else ""

            var primaryFrac = (sharePct / 100f).coerceIn(0.03f, 1f)
            var primaryColor = Color.parseColor("#94A3B8")
            var secondaryFrac = 0f
            var secondaryColor = Color.TRANSPARENT
            var accentColor = Color.parseColor("#94A3B8")
            val subtitle: String

            when {
                stat.packageName == FocusSelfUsageTracker.VIRTUAL_PKG_STUDY -> {
                    accentColor = Color.parseColor("#10B981")
                    primaryColor = accentColor
                    subtitle = "STUDY • FOCUS ELITE$opensPart · $sharePct%"
                }
                stat.packageName == FocusSelfUsageTracker.VIRTUAL_PKG_IMPROVE -> {
                    accentColor = Color.parseColor("#38BDF8")
                    primaryColor = accentColor
                    subtitle = "IMPROVE • FOCUS ELITE$opensPart · $sharePct%"
                }
                stat.packageName == FocusSelfUsageTracker.VIRTUAL_PKG_UTILITY -> {
                    accentColor = Color.parseColor("#94A3B8")
                    primaryColor = accentColor
                    subtitle = "UTILITY • FOCUS ELITE$opensPart · $sharePct%"
                }
                isBrowser -> {
                    val siteCount = browserSiteCounts[stat.packageName] ?: 0
                    val siteWord = if (siteCount == 1) "1 site" else "$siteCount sites"
                    val siteLabel = if (siteCount > 0) " · $siteWord" else ""

                    if (dayRange == null || dayRange.isToday) {
                        val pool = BlockPolicyEngine.getSharedBrowserPoolStatus(this)
                        val cap = pool.limitMinutes.coerceAtLeast(1)
                        val nonStudyMins = BlockPolicyEngine.getNonStudyBrowserMinutesForPackage(this, stat.packageName).coerceAtLeast(0)
                        val studyMins = BlockPolicyEngine.getStudyBrowserMinutesForPackage(this, stat.packageName).coerceAtLeast(0)
                        val overCap = pool.isFocusHoursActive && pool.usedFocusMinutes >= cap
                        val nearCap = pool.isFocusHoursActive && pool.usedFocusMinutes >= (cap * 0.75f).roundToInt()
                        val nonStudyColor = when {
                            overCap -> Color.parseColor("#F43F5E")
                            nearCap -> Color.parseColor("#F59E0B")
                            else -> Color.parseColor("#A78BFA")
                        }
                        accentColor = when {
                            overCap -> Color.parseColor("#F43F5E")
                            nearCap -> Color.parseColor("#F59E0B")
                            studyMins > nonStudyMins && studyMins > 0 -> Color.parseColor("#10B981")
                            else -> Color.parseColor("#A78BFA")
                        }

                        // Dual-segment bar: Study (Emerald) + Non-Study (Violet/Amber/Coral)
                        val totalTrackedMins = (studyMins + nonStudyMins).coerceAtLeast(0L)
                        if (totalTrackedMins > 0L) {
                            val denominator = max(cap.toLong(), totalTrackedMins).toFloat()
                            primaryFrac = (studyMins / denominator).coerceIn(0f, 1f)
                            primaryColor = Color.parseColor("#10B981")
                            secondaryFrac = (nonStudyMins / denominator).coerceIn(0f, 1f - primaryFrac)
                            secondaryColor = nonStudyColor
                        } else {
                            primaryFrac = (sharePct / 100f).coerceIn(0.04f, 1f)
                            primaryColor = accentColor
                        }

                        val poolNote = if (pool.isFocusHoursActive) {
                            "Focus Pool ${pool.usedFocusMinutes}m/${cap}m"
                        } else {
                            "Free Time (${cap}m in Focus)"
                        }
                        subtitle = "Study ${studyMins}m · Non-Study ${nonStudyMins}m · $poolNote$siteLabel$opensPart"
                    } else {
                        accentColor = Color.parseColor("#A78BFA")
                        primaryColor = accentColor
                        primaryFrac = (sharePct / 100f).coerceIn(0.04f, 1f)
                        subtitle = if (siteCount > 0) {
                            "WEB · $siteWord$opensPart · $sharePct%"
                        } else {
                            "WEB · Tap for sites$opensPart · $sharePct%"
                        }
                    }
                }
                stat.category == "STUDY" -> {
                    accentColor = Color.parseColor("#10B981")
                    primaryColor = accentColor
                    subtitle = "STUDY$opensPart · $sharePct%"
                }
                stat.category == "IMPROVE" -> {
                    accentColor = Color.parseColor("#38BDF8")
                    primaryColor = accentColor
                    subtitle = "IMPROVE$opensPart · $sharePct%"
                }
                stat.category == "UTILITY" -> {
                    accentColor = Color.parseColor("#94A3B8")
                    primaryColor = accentColor
                    subtitle = "SYSTEM$opensPart · $sharePct%"
                }
                else -> {
                    // Distract or Ignored app with a daily timeline limit (cap)
                    val customLimit = BlockPolicyEngine.getFastLimitMinutes(this, stat.packageName)
                    val cap = if (stat.category == "IGNORED") {
                        if (customLimit in 1..29) customLimit else 30
                    } else {
                        if (customLimit > 0) customLimit else defaultCap
                    }.coerceAtLeast(1)
                    val usedMins = (stat.durationMs / 60_000L).toInt()
                    val capRatio = (stat.durationMs.toFloat() / (cap * 60_000f)).coerceAtLeast(0f)
                    val capPct = (capRatio * 100f).roundToInt()

                    accentColor = when {
                        capRatio >= 1f -> Color.parseColor("#F43F5E")
                        capRatio >= 0.7f -> Color.parseColor("#F59E0B")
                        else -> Color.parseColor("#38BDF8")
                    }
                    primaryColor = accentColor
                    primaryFrac = capRatio.coerceIn(0.04f, 1f)

                    val tag = if (stat.category == "IGNORED") "IGNORED" else "LIMIT"
                    val capStatus = if (capRatio >= 1f) "${usedMins}m/${cap}m (${capPct}%)" else "${usedMins}m/${cap}m cap"
                    subtitle = "$tag · $capStatus$opensPart · $sharePct%"
                }
            }

            val finalSubtitle = if (isUninstalled) "DELETED • $subtitle" else subtitle
            RichUsageRow(
                packageName = stat.packageName,
                displayName = rawName,
                subtitle = finalSubtitle,
                durationLabel = UsageStatsHelper.formatDuration(stat.durationMs),
                icon = icon,
                durationMs = stat.durationMs,
                sharePct = sharePct,
                category = stat.category,
                isBrowser = isBrowser,
                isUninstalled = isUninstalled,
                accentColor = accentColor,
                primaryBarFraction = primaryFrac,
                primaryBarColor = primaryColor,
                secondaryBarFraction = secondaryFrac,
                secondaryBarColor = secondaryColor
            )
        }

        return PreparedDayData(
            snapshot = snapshot,
            topBarEntries = topBarEntries,
            rows = rows,
            dayRange = dayRange
        )
    }

    private fun bindDayBreakdown(prepared: PreparedDayData) {
        currentPreparedDay = prepared
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
            "${getString(R.string.today_by_app)}  •  Tap browser for websites"
        } else {
            "${dayRange?.fullDateLabel ?: "Selected Day"} — Apps Used"
        }

        bindExecutiveHeroAndCategories(prepared.snapshot, dayRange)
        bindHourlyTimeline(prepared.snapshot)
        todayChart.entries = prepared.topBarEntries
        updateFilterChipStyles()
        applyFilterToList()
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

    private fun prewarmAdjacentDays(currentIdx: Int) {
        if (weeklyRanges.isEmpty()) return
        prewarmJob?.cancel()
        prewarmJob = lifecycleScope.launch(Dispatchers.IO) {
            val targets = listOf(currentIdx - 1, currentIdx + 1).filter { it in weeklyRanges.indices }
            for (idx in targets) {
                val range = weeklyRanges[idx]
                val cacheKey = range.fullDateLabel
                if (preparedDayCache.containsKey(cacheKey)) continue
                try {
                    val snap = if (range.isToday) {
                        UsageStatsHelper.getTodayWellbeingSnapshot(this@UsageStatsActivity, forceRefresh = false)
                    } else {
                        UsageStatsHelper.getDayWellbeingSnapshot(
                            this@UsageStatsActivity,
                            range.startMs,
                            range.endMs,
                            forceRefresh = false
                        )
                    }
                    preparedDayCache[cacheKey] = buildPreparedDayData(snap, range)
                } catch (_: Throwable) {}
            }
        }
    }

    private fun selectDay(index: Int, scrollToTop: Boolean) {
        if (index !in weeklyRanges.indices) return
        selectedDayIndex = index
        val targetRange = weeklyRanges[index]
        renderWeeklyChartSelection()

        // Instant zero-jank hit from in-memory day cache
        val cachedPrepared = preparedDayCache[targetRange.fullDateLabel]
        if (cachedPrepared != null) {
            bindDayBreakdown(cachedPrepared)
            if (scrollToTop) {
                val rootScroll = (categorySummaryRow.parent?.parent) as? ScrollView
                rootScroll?.smoothScrollTo(0, 0)
            }
            prewarmAdjacentDays(index)
            return
        }

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
            preparedDayCache[targetRange.fullDateLabel] = prepared
            withContext(Dispatchers.Main) {
                if (!isFinishing && !isDestroyed) {
                    bindDayBreakdown(prepared)
                    if (scrollToTop) {
                        val rootScroll = (categorySummaryRow.parent?.parent) as? ScrollView
                        rootScroll?.smoothScrollTo(0, 0)
                    }
                    prewarmAdjacentDays(index)
                }
            }
        }
    }

    private fun startLiveForegroundAutoSync() {
        liveTickerJob?.cancel()
        liveTickerJob = lifecycleScope.launch {
            while (isActive) {
                delay(10_000L)
                if (!isFinishing && !isDestroyed && selectedDayIndex == 6 && UsageStatsHelper.hasUsageAccess(this@UsageStatsActivity)) {
                    triggerInstantLiveSync(showLatencyFeedback = false)
                }
            }
        }
    }

    private fun triggerInstantLiveSync(showLatencyFeedback: Boolean) {
        if (!UsageStatsHelper.hasUsageAccess(this)) return
        val activeRange = weeklyRanges.getOrNull(selectedDayIndex)
        val isTodaySelected = activeRange == null || activeRange.isToday

        if (showLatencyFeedback) {
            tvHeroDeltaPill?.text = "⚡ Syncing…"
            tvHeroDeltaPill?.setTextColor(Color.parseColor("#38BDF8"))
        }

        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch(Dispatchers.IO) {
            val t0 = android.os.SystemClock.elapsedRealtime()
            val snapshot = if (isTodaySelected) {
                UsageStatsHelper.getTodayWellbeingSnapshot(this@UsageStatsActivity, forceRefresh = true)
            } else {
                UsageStatsHelper.getDayWellbeingSnapshot(
                    this@UsageStatsActivity,
                    activeRange!!.startMs,
                    activeRange.endMs,
                    forceRefresh = true
                )
            }
            val prepared = buildPreparedDayData(snapshot, activeRange)
            val elapsedMs = (android.os.SystemClock.elapsedRealtime() - t0).coerceAtLeast(1L)

            if (isTodaySelected) {
                lastPreparedTodayCache = prepared
                lastPreparedTodayStartMs = startOfTodayMs()
            }
            if (activeRange != null) {
                preparedDayCache[activeRange.fullDateLabel] = prepared
            }

            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                // Keep Today's bar in weeklyRanges synced with the new live total without re-querying 6 past days
                if (isTodaySelected && weeklyRanges.isNotEmpty() && weeklyRanges.last().isToday) {
                    weeklyRanges = weeklyRanges.toMutableList().also { list ->
                        val last = list.last()
                        list[list.lastIndex] = last.copy(
                            totalScreenTimeMs = snapshot.totalScreenTimeMs,
                            endMs = snapshot.endMs
                        )
                    }
                    lastWeeklyRangesCache = weeklyRanges
                }
                bindDayBreakdown(prepared)
                if (showLatencyFeedback) {
                    tvHeroDeltaPill?.text = "⚡ Synced · ${elapsedMs}ms"
                    tvHeroDeltaPill?.setTextColor(Color.parseColor("#34D399"))
                    tvHeroDeltaPill?.postDelayed({
                        if (!isFinishing && !isDestroyed && currentPreparedDay != null) {
                            bindExecutiveHeroAndCategories(currentPreparedDay!!.snapshot, currentPreparedDay!!.dayRange)
                        }
                    }, 1800L)
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
        AppMetadataCache.warmUpAsync(this)

        val todayStart = startOfTodayMs()

        // Tier 0 (0ms, synchronous Frame-0 paint): immediately paint full last-prepared Today UI + 7-day chart!
        if (weeklyRanges.isEmpty() && lastWeeklyRangesCache.isNotEmpty()) {
            weeklyRanges = lastWeeklyRangesCache
            renderWeeklyChartSelection()
        }
        val frame0Prepared = if (selectedDayIndex == 6 && lastPreparedTodayCache != null &&
            abs(lastPreparedTodayStartMs - todayStart) < 60_000L
        ) {
            lastPreparedTodayCache
        } else null

        if (frame0Prepared != null) {
            bindDayBreakdown(frame0Prepared)
            tvHeroDeltaPill?.text = "⚡ Syncing…"
        } else {
            val cached = UsageStatsHelper.getCachedTodaySnapshot(this)
            if (cached != null && selectedDayIndex == 6) {
                bindExecutiveHeroAndCategories(cached, null)
                bindHourlyTimeline(cached)
                tvHeroDeltaPill?.text = "⚡ Syncing…"
            }
        }

        // Tier 1 (~5-15ms Incremental Delta Sync): compute ONLY the selected day first — NEVER blocked by 6 past days!
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch(Dispatchers.IO) {
            // If Frame 0 had a warm disk snapshot but no prepared rows yet, paint rows immediately before IPC
            if (frame0Prepared == null && (smoothAdapter?.itemCount ?: 0) == 0 && selectedDayIndex == 6) {
                val warmSnap = UsageStatsHelper.getCachedTodaySnapshot(this@UsageStatsActivity)
                if (warmSnap != null) {
                    val warmPrepared = buildPreparedDayData(warmSnap, weeklyRanges.getOrNull(6))
                    withContext(Dispatchers.Main) {
                        if (!isFinishing && !isDestroyed) {
                            bindDayBreakdown(warmPrepared)
                            tvHeroDeltaPill?.text = "⚡ Syncing…"
                        }
                    }
                }
            }

            val existingActiveRange = weeklyRanges.getOrNull(selectedDayIndex)
            val isTodaySelected = existingActiveRange == null || existingActiveRange.isToday

            val snapshot = if (isTodaySelected) {
                UsageStatsHelper.getTodayWellbeingSnapshot(this@UsageStatsActivity, forceRefresh = true)
            } else {
                UsageStatsHelper.getDayWellbeingSnapshot(
                    this@UsageStatsActivity,
                    existingActiveRange!!.startMs,
                    existingActiveRange.endMs,
                    forceRefresh = false
                )
            }
            val prepared = buildPreparedDayData(snapshot, existingActiveRange)
            if (isTodaySelected) {
                lastPreparedTodayCache = prepared
                lastPreparedTodayStartMs = todayStart
            }
            if (existingActiveRange != null) {
                preparedDayCache[existingActiveRange.fullDateLabel] = prepared
            }

            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                bindDayBreakdown(prepared)
                startLiveForegroundAutoSync()
            }

            // Tier 2 (Parallel 7-day range sync): reuses Today's total (ZERO double compute) & loads 6 past days in parallel!
            weeklyJob?.cancel()
            weeklyJob = lifecycleScope.launch(Dispatchers.IO) {
                val todayOverride = if (isTodaySelected) snapshot.totalScreenTimeMs else null
                val ranges = UsageStatsHelper.getWeeklyDayRangesFast(this@UsageStatsActivity, todayTotalOverrideMs = todayOverride)
                if (ranges.isEmpty()) return@launch
                lastWeeklyRangesCache = ranges
                val safeIdx = selectedDayIndex.coerceIn(0, (ranges.size - 1).coerceAtLeast(0))
                val resolvedRange = ranges.getOrNull(safeIdx)

                // If Yesterday wasn't cached during Tier 1, reconcile delta pill smoothly now
                val yestTotal = ranges.getOrNull(ranges.size - 2)?.totalScreenTimeMs ?: 0L
                val updatedPrepared = if (isTodaySelected && snapshot.yesterdayScreenTimeMs <= 0L && yestTotal > 0L) {
                    val updatedSnap = snapshot.copy(yesterdayScreenTimeMs = yestTotal)
                    prepared.copy(snapshot = updatedSnap, dayRange = resolvedRange)
                } else {
                    prepared.copy(dayRange = resolvedRange)
                }

                if (resolvedRange != null) {
                    preparedDayCache[resolvedRange.fullDateLabel] = updatedPrepared
                }
                if (isTodaySelected) {
                    lastPreparedTodayCache = updatedPrepared
                    lastPreparedTodayStartMs = todayStart
                }

                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed) {
                        weeklyRanges = ranges
                        selectedDayIndex = safeIdx
                        currentPreparedDay = updatedPrepared
                        renderWeeklyChartSelection()
                        bindExecutiveHeroAndCategories(updatedPrepared.snapshot, resolvedRange)
                        prewarmAdjacentDays(safeIdx)
                    }
                }
            }
        }
    }

    private fun showBrowserWebsitesDialog(
        browserPackage: String,
        browserName: String,
        dayStartMs: Long,
        dayLabel: String
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            val sites = BrowserWebsiteTracker.getWebsitesForBrowserOnDay(
                this@UsageStatsActivity,
                browserPackage,
                dayStartMs
            )
            val studyDomains = WebsitePrefs.getFairDomains(this@UsageStatsActivity)
            val blockedDomains = WebsitePrefs.getBlockedDomains(this@UsageStatsActivity)
            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                renderBrowserWebsitesDialog(
                    browserPackage = browserPackage,
                    browserName = browserName,
                    dayStartMs = dayStartMs,
                    dayLabel = dayLabel,
                    initialSites = sites,
                    studyDomains = studyDomains,
                    blockedDomains = blockedDomains
                )
            }
        }
    }

    private fun renderBrowserWebsitesDialog(
        browserPackage: String,
        browserName: String,
        dayStartMs: Long,
        dayLabel: String,
        initialSites: List<BrowserWebsiteTracker.SiteUsageRecord>,
        studyDomains: Set<String>,
        blockedDomains: Set<String>
    ) {
        val isWhite = UiThemePrefs.isWhiteBackgroundActive(this)
        val bgColor = if (isWhite) Color.WHITE else Color.parseColor("#0D1520")
        val cardColor = if (isWhite) Color.parseColor("#F8FAFC") else Color.parseColor("#141F2E")
        val strokeColor = if (isWhite) Color.parseColor("#CBD5E1") else Color.parseColor("#243449")
        val titleColor = if (isWhite) Color.parseColor("#0F172A") else Color.parseColor("#F1F5F9")
        val subColor = if (isWhite) Color.parseColor("#475569") else Color.parseColor("#94A3B8")

        val totalMs = initialSites.sumOf { it.durationMs }.coerceAtLeast(1L)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18f), dp(18f), dp(18f), dp(16f))
            background = GradientDrawable().apply {
                cornerRadius = dp(20f).toFloat()
                setColor(bgColor)
                setStroke(dp(1f), strokeColor)
            }
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4f) }
        }
        headerRow.addView(TextView(this).apply {
            text = "🌐 $browserName — Websites"
            textSize = 16.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(titleColor)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        root.addView(headerRow)

        val summaryText = if (initialSites.isEmpty()) {
            "$dayLabel • No websites logged yet"
        } else {
            val totalLabel = BrowserWebsiteTracker.formatSiteDuration(initialSites.sumOf { it.durationMs })
            "$dayLabel • ${initialSites.size} sites tracked • $totalLabel total"
        }
        root.addView(TextView(this).apply {
            text = summaryText
            textSize = 12f
            setTextColor(subColor)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12f) }
        })

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(330f).coerceAtMost((resources.displayMetrics.heightPixels * 0.48f).roundToInt())
            )
        }
        val listCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        var activeDialog: AlertDialog? = null

        if (initialSites.isEmpty()) {
            listCol.addView(TextView(this).apply {
                text = "No website visits recorded for $browserName on $dayLabel yet.\n\nKeep Focus Elite Accessibility Shield active while browsing — every website you visit and its exact duration will appear here automatically."
                textSize = 12.5f
                setTextColor(subColor)
                setPadding(dp(14f), dp(18f), dp(14f), dp(18f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(14f).toFloat()
                    setColor(cardColor)
                    setStroke(dp(1f), strokeColor)
                }
            })
        } else {
            initialSites.forEach { site ->
                val isStudy = studyDomains.any { d ->
                    site.host.equals(d, ignoreCase = true) || site.host.endsWith(".$d", ignoreCase = true)
                } || site.category == "STUDY"
                val isBlocked = blockedDomains.any { d ->
                    site.host.equals(d, ignoreCase = true) || site.host.endsWith(".$d", ignoreCase = true)
                }
                val accent = when {
                    isBlocked -> Color.parseColor("#F43F5E")
                    isStudy -> Color.parseColor("#10B981")
                    else -> Color.parseColor("#38BDF8")
                }
                val sharePct = ((site.durationMs * 100f) / totalMs).roundToInt().coerceIn(2, 100)

                val itemCard = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(8f) }
                    background = GradientDrawable().apply {
                        cornerRadius = dp(13f).toFloat()
                        setColor(cardColor)
                        setStroke(dp(1f), strokeColor)
                    }
                }

                val topLine = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                topLine.addView(TextView(this).apply {
                    text = site.host
                    textSize = 13.5f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(titleColor)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = dp(8f)
                    }
                })
                topLine.addView(TextView(this).apply {
                    text = BrowserWebsiteTracker.formatSiteDuration(site.durationMs)
                    textSize = 12f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(accent)
                    setPadding(dp(8f), dp(3f), dp(8f), dp(3f))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(9f).toFloat()
                        setColor(Color.argb(30, Color.red(accent), Color.green(accent), Color.blue(accent)))
                        setStroke(dp(1f), accent)
                    }
                })
                itemCard.addView(topLine)

                val statusTag = when {
                    isBlocked -> "Blocked"
                    isStudy -> "Study 24/7"
                    else -> "Non-Study"
                }
                val visitsStr = if (site.visits == 1) "1 visit" else "${site.visits} visits"
                itemCard.addView(TextView(this).apply {
                    text = "$statusTag • $visitsStr • $sharePct% of browser time"
                    textSize = 11f
                    setTextColor(subColor)
                    setPadding(0, dp(3f), 0, dp(6f))
                })

                // Micro proportion bar
                val barTrack = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(4f)
                    ).apply { bottomMargin = dp(8f) }
                    background = GradientDrawable().apply {
                        cornerRadius = dp(2f).toFloat()
                        setColor(if (isWhite) Color.parseColor("#E2E8F0") else Color.parseColor("#1E293B"))
                    }
                    clipToOutline = true
                }
                barTrack.addView(View(this).apply {
                    setBackgroundColor(accent)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, sharePct.toFloat())
                })
                barTrack.addView(View(this).apply {
                    setBackgroundColor(Color.TRANSPARENT)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, (100 - sharePct).coerceAtLeast(0).toFloat())
                })
                itemCard.addView(barTrack)

                // Quick 1-tap Study / Block rule actions
                val btnRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END
                }
                val studyBtn = TextView(this).apply {
                    text = if (isStudy) "✓ Study" else "+ Study"
                    textSize = 11f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.parseColor("#10B981"))
                    setPadding(dp(10f), dp(5f), dp(10f), dp(5f))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(8f).toFloat()
                        setColor(Color.argb(26, 16, 185, 129))
                        setStroke(dp(1f), Color.parseColor("#10B981"))
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = dp(8f) }
                    setOnClickListener { v ->
                        SensoryFeedbackEngine.tap(this@UsageStatsActivity, v)
                        if (isStudy) {
                            WebsitePrefs.removeFairDomain(this@UsageStatsActivity, site.host)
                            Toast.makeText(this@UsageStatsActivity, "Removed ${site.host} from Study", Toast.LENGTH_SHORT).show()
                        } else {
                            WebsitePrefs.removeDomain(this@UsageStatsActivity, site.host)
                            WebsitePrefs.addFairDomain(this@UsageStatsActivity, site.host)
                            Toast.makeText(this@UsageStatsActivity, "Marked ${site.host} as Study 24/7", Toast.LENGTH_SHORT).show()
                        }
                        BlockPolicyEngine.warmCacheAsync(this@UsageStatsActivity)
                        BrowserWebsiteTracker.invalidateCache()
                        activeDialog?.dismiss()
                        showBrowserWebsitesDialog(browserPackage, browserName, dayStartMs, dayLabel)
                    }
                }
                val blockBtn = TextView(this).apply {
                    text = if (isBlocked) "✓ Blocked" else "Block"
                    textSize = 11f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.parseColor("#F43F5E"))
                    setPadding(dp(10f), dp(5f), dp(10f), dp(5f))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(8f).toFloat()
                        setColor(Color.argb(26, 244, 63, 94))
                        setStroke(dp(1f), Color.parseColor("#F43F5E"))
                    }
                    setOnClickListener { v ->
                        SensoryFeedbackEngine.tap(this@UsageStatsActivity, v)
                        if (isBlocked) {
                            WebsitePrefs.removeDomain(this@UsageStatsActivity, site.host)
                            Toast.makeText(this@UsageStatsActivity, "Unblocked ${site.host}", Toast.LENGTH_SHORT).show()
                        } else {
                            WebsitePrefs.removeFairDomain(this@UsageStatsActivity, site.host)
                            WebsitePrefs.addDomain(this@UsageStatsActivity, site.host)
                            Toast.makeText(this@UsageStatsActivity, "Blocked ${site.host}", Toast.LENGTH_SHORT).show()
                        }
                        BlockPolicyEngine.warmCacheAsync(this@UsageStatsActivity)
                        BrowserWebsiteTracker.invalidateCache()
                        activeDialog?.dismiss()
                        showBrowserWebsitesDialog(browserPackage, browserName, dayStartMs, dayLabel)
                    }
                }
                btnRow.addView(studyBtn)
                btnRow.addView(blockBtn)
                itemCard.addView(btnRow)
                listCol.addView(itemCard)
            }
        }

        scroll.addView(listCol)
        root.addView(scroll)

        // Footer Actions: Open Web Shield + Close
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12f) }
        }
        footer.addView(TextView(this).apply {
            text = "Open Web Shield →"
            textSize = 12.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8f) }
            setOnClickListener { v ->
                SensoryFeedbackEngine.tap(this@UsageStatsActivity, v)
                activeDialog?.dismiss()
                startActivity(Intent(this@UsageStatsActivity, EliteWebShieldActivity::class.java))
            }
        })
        footer.addView(TextView(this).apply {
            text = "Close"
            textSize = 12.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
            background = GradientDrawable().apply {
                cornerRadius = dp(10f).toFloat()
                setColor(Color.parseColor("#1E293B"))
            }
            setOnClickListener { activeDialog?.dismiss() }
        })
        root.addView(footer)

        val dialog = AlertDialog.Builder(this)
            .setView(root)
            .create()
        activeDialog = dialog
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }

    private fun bindExecutiveHeroAndCategories(
        snapshot: UsageStatsHelper.WellbeingSnapshot,
        dayRange: UsageStatsHelper.WeeklyDayRange?
    ) {
        val newTotalText = UsageStatsHelper.formatPreciseDuration(snapshot.totalScreenTimeMs)
        val totalView = tvHeroTotalTime
        if (totalView != null && totalView.text?.toString() != newTotalText) {
            totalView.animate().cancel()
            totalView.alpha = 0.55f
            totalView.text = newTotalText
            totalView.animate().alpha(1f).setDuration(180L).start()
        }
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
            tvVsYesterday.text = "Showing full app breakdown for ${dayRange?.fullDateLabel ?: "selected day"} • Tap any browser for websites"
        } else if (snapshot.yesterdayScreenTimeMs <= 0L) {
            tvHeroDeltaPill?.text = "✓ Exact Tracking"
            tvVsYesterday.text = "Tap any browser below to inspect websites • Tap any bar in Last 7 Days to switch"
        } else if (diffMins < 2L) {
            tvHeroDeltaPill?.text = "≈ Same as yesterday"
            tvVsYesterday.text = "On pace with yesterday (${UsageStatsHelper.formatDuration(snapshot.yesterdayScreenTimeMs)}) • Tap any browser for websites"
        } else if (diffMs < 0L) {
            tvHeroDeltaPill?.text = "▼ $diffFormatted vs yesterday"
            tvHeroDeltaPill?.setTextColor(Color.parseColor("#34D399"))
            tvVsYesterday.text = "Down $diffFormatted vs yesterday (${UsageStatsHelper.formatDuration(snapshot.yesterdayScreenTimeMs)}) • Tap any browser for websites"
        } else {
            tvHeroDeltaPill?.text = "▲ $diffFormatted vs yesterday"
            tvHeroDeltaPill?.setTextColor(Color.parseColor("#F59E0B"))
            tvVsYesterday.text = "Up $diffFormatted vs yesterday (${UsageStatsHelper.formatDuration(snapshot.yesterdayScreenTimeMs)}) • Tap any browser for websites"
        }

        // Update Category Summary Row with exact percentage breakdown matching the Hero Bar 1:1
        val catTotalMs = snapshot.studyMs + snapshot.improveMs + snapshot.distractMs + snapshot.utilityMs
        val denom = max(1L, catTotalMs).toFloat()
        val studyPct = if (catTotalMs > 0L) ((snapshot.studyMs * 100f) / denom).roundToInt() else 0
        val improvePct = if (catTotalMs > 0L) ((snapshot.improveMs * 100f) / denom).roundToInt() else 0
        val distractPct = if (catTotalMs > 0L) ((snapshot.distractMs * 100f) / denom).roundToInt() else 0
        val utilityPct = if (catTotalMs > 0L) (100 - studyPct - improvePct - distractPct).coerceAtLeast(0) else 0

        findViewById<TextView>(R.id.tvStudyMinutes)?.text = UsageStatsHelper.formatDuration(snapshot.studyMs)
        findViewById<TextView>(R.id.tvImproveMinutes)?.text = UsageStatsHelper.formatDuration(snapshot.improveMs)
        findViewById<TextView>(R.id.tvDistractMinutes)?.text = UsageStatsHelper.formatDuration(snapshot.distractMs)
        findViewById<TextView>(R.id.tvIgnoreMinutes)?.text = UsageStatsHelper.formatDuration(snapshot.utilityMs)

        findViewById<TextView>(R.id.tvStudyLabel)?.text =
            if (catTotalMs > 0L) "${getString(R.string.category_study)} · $studyPct%" else getString(R.string.category_study)
        findViewById<TextView>(R.id.tvImproveLabel)?.text =
            if (catTotalMs > 0L) "${getString(R.string.category_improve)} · $improvePct%" else getString(R.string.category_improve)
        findViewById<TextView>(R.id.tvDistractLabel)?.text =
            if (catTotalMs > 0L) "${getString(R.string.category_distract)} · $distractPct%" else getString(R.string.category_distract)
        findViewById<TextView>(R.id.tvIgnoreLabel)?.text =
            if (catTotalMs > 0L) "${getString(R.string.category_ignore)} · $utilityPct%" else getString(R.string.category_ignore)

        // Smooth 60 FPS Canvas animation of exact normalized Hero Proportion Bar
        if (catTotalMs > 0L) {
            heroProportionBar?.setFractions(
                study = snapshot.studyMs / denom,
                improve = snapshot.improveMs / denom,
                distract = snapshot.distractMs / denom,
                utility = snapshot.utilityMs / denom
            )
        } else {
            heroProportionBar?.setFractions(0f, 0f, 0f, 0f)
        }
    }

    /**
     * Smooth Canvas-drawn 4-segment Hero Proportion Bar (Study / Improve / Distract / Utility).
     * Strictly normalized to 100% with animated transitions and zero requestLayout() jitter.
     */
    private class SegmentedProportionBarView(context: android.content.Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val segPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val rect = RectF()
        private val clipPath = Path()

        private var curS = 0f
        private var curI = 0f
        private var curD = 0f
        private var curU = 0f
        private var animator: ValueAnimator? = null

        private val colors = intArrayOf(
            Color.parseColor("#10B981"), // Study Emerald
            Color.parseColor("#38BDF8"), // Improve Cyan
            Color.parseColor("#F43F5E"), // Distract Coral
            Color.parseColor("#64748B")  // Utility Slate
        )

        fun setFractions(study: Float, improve: Float, distract: Float, utility: Float) {
            val sum = (study + improve + distract + utility)
            val tS = if (sum > 0f) (study / sum).coerceIn(0f, 1f) else 0f
            val tI = if (sum > 0f) (improve / sum).coerceIn(0f, 1f) else 0f
            val tD = if (sum > 0f) (distract / sum).coerceIn(0f, 1f) else 0f
            val tU = if (sum > 0f) (utility / sum).coerceIn(0f, 1f) else 0f

            val s0 = curS
            val i0 = curI
            val d0 = curD
            val u0 = curU

            animator?.cancel()
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 320L
                interpolator = DecelerateInterpolator()
                addUpdateListener { anim ->
                    val f = anim.animatedValue as Float
                    curS = s0 + (tS - s0) * f
                    curI = i0 + (tI - i0) * f
                    curD = d0 + (tD - d0) * f
                    curU = u0 + (tU - u0) * f
                    invalidate()
                }
                start()
            }
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            animator?.cancel()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val radius = h / 2f
            val isLight = UiThemePrefs.isWhiteBackgroundActive(context)
            trackPaint.color = if (isLight) Color.parseColor("#E2E8F0") else Color.parseColor("#1E293B")

            clipPath.reset()
            rect.set(0f, 0f, w, h)
            clipPath.addRoundRect(rect, radius, radius, Path.Direction.CW)

            val save = canvas.save()
            canvas.clipPath(clipPath)
            canvas.drawRect(0f, 0f, w, h, trackPaint)

            val fracs = floatArrayOf(curS, curI, curD, curU)
            val activeIndices = fracs.indices.filter { fracs[it] > 0.002f }
            val gap = if (activeIndices.size > 1) 2f * density else 0f
            val totalGap = gap * (activeIndices.size - 1).coerceAtLeast(0)
            val usableW = (w - totalGap).coerceAtLeast(0f)

            var x = 0f
            activeIndices.forEachIndexed { pos, idx ->
                val segW = if (pos == activeIndices.lastIndex) {
                    (w - x).coerceAtLeast(0f)
                } else {
                    (usableW * fracs[idx]).coerceAtLeast(3f * density)
                }
                segPaint.color = colors[idx]
                canvas.drawRect(x, 0f, (x + segW).coerceAtMost(w), h, segPaint)
                x += segW + gap
            }
            canvas.restoreToCount(save)
        }
    }

    /**
     * Hardware-accelerated Canvas progress/breakdown bar for per-app cards.
     * Supports single-segment quota/share fill AND dual-segment Study + Non-Study browser breakdown
     * with smooth ValueAnimator interpolation and zero RecyclerView requestLayout() jank.
     */
    private class AppUsageBarView(context: android.content.Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val primaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val secondaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val rect = RectF()
        private val clipPath = Path()

        private var curPrimary = 0f
        private var curSecondary = 0f
        private var animator: ValueAnimator? = null
        private var boundKey: String = ""

        fun bind(
            key: String,
            primaryFrac: Float,
            primaryColor: Int,
            secondaryFrac: Float,
            secondaryColor: Int,
            isLight: Boolean
        ) {
            trackPaint.color = if (isLight) Color.parseColor("#E2E8F0") else Color.parseColor("#182232")
            primaryPaint.color = primaryColor
            secondaryPaint.color = secondaryColor

            val targetP = primaryFrac.coerceIn(0f, 1f)
            val targetS = secondaryFrac.coerceIn(0f, (1f - targetP).coerceAtLeast(0f))

            animator?.cancel()
            val startP = if (boundKey == key) curPrimary else 0f
            val startS = if (boundKey == key) curSecondary else 0f
            boundKey = key

            if (abs(targetP - startP) < 0.005f && abs(targetS - startS) < 0.005f) {
                curPrimary = targetP
                curSecondary = targetS
                invalidate()
                return
            }

            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 280L
                interpolator = DecelerateInterpolator()
                addUpdateListener { anim ->
                    val f = anim.animatedValue as Float
                    curPrimary = startP + (targetP - startP) * f
                    curSecondary = startS + (targetS - startS) * f
                    invalidate()
                }
                start()
            }
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            animator?.cancel()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val r = h / 2f
            clipPath.reset()
            rect.set(0f, 0f, w, h)
            clipPath.addRoundRect(rect, r, r, Path.Direction.CW)

            val save = canvas.save()
            canvas.clipPath(clipPath)
            canvas.drawRect(0f, 0f, w, h, trackPaint)

            var x = 0f
            if (curPrimary > 0.002f) {
                val pw = (w * curPrimary).coerceAtLeast(3f * density).coerceAtMost(w)
                canvas.drawRect(0f, 0f, pw, h, primaryPaint)
                x = pw
            }
            if (curSecondary > 0.002f && x < w) {
                val gap = if (curPrimary > 0.002f) 1.5f * density else 0f
                val startX = (x + gap).coerceAtMost(w)
                val sw = (w * curSecondary).coerceAtLeast(3f * density)
                val endX = (startX + sw).coerceAtMost(w)
                canvas.drawRect(startX, 0f, endX, h, secondaryPaint)
            }
            canvas.restoreToCount(save)
        }
    }

    /**
     * DiffUtil-backed persistent RecyclerView adapter with rich 2-line cards, animated quota/breakdown bars,
     * and interactive browser website drilldown pills.
     */
    private class SmoothAppUsageAdapter(
        private val onRowClick: (RichUsageRow) -> Unit
    ) : RecyclerView.Adapter<SmoothAppUsageAdapter.SmoothRowHolder>() {
        private var items: List<RichUsageRow> = emptyList()

        fun submitList(newItems: List<RichUsageRow>) {
            val oldItems = items
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = oldItems.size
                override fun getNewListSize(): Int = newItems.size
                override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
                    return oldItems[oldPos].packageName == newItems[newPos].packageName
                }
                override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
                    val o = oldItems[oldPos]
                    val n = newItems[newPos]
                    return o.displayName == n.displayName &&
                        o.subtitle == n.subtitle &&
                        o.durationLabel == n.durationLabel &&
                        o.sharePct == n.sharePct &&
                        o.accentColor == n.accentColor &&
                        o.isBrowser == n.isBrowser &&
                        o.isUninstalled == n.isUninstalled &&
                        o.primaryBarFraction == n.primaryBarFraction &&
                        o.secondaryBarFraction == n.secondaryBarFraction &&
                        o.primaryBarColor == n.primaryBarColor &&
                        o.secondaryBarColor == n.secondaryBarColor
                }
            })
            items = newItems
            diff.dispatchUpdatesTo(this)
        }

        class SmoothRowHolder(
            val container: LinearLayout,
            val iconView: ImageView,
            val titleView: TextView,
            val subtitleView: TextView,
            val sitesPill: TextView,
            val badgeView: TextView,
            val barView: AppUsageBarView
        ) : RecyclerView.ViewHolder(container)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SmoothRowHolder {
            val ctx = parent.context
            val density = ctx.resources.displayMetrics.density
            fun dp(v: Float) = (v * density).roundToInt()

            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12f), dp(11f), dp(12f), dp(10f))
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8f) }
            }

            val mainRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            val icon = ImageView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(38f), dp(38f)).apply { marginEnd = dp(12f) }
            }

            val textCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(8f)
                }
            }

            val title = TextView(ctx).apply {
                textSize = 13.8f
                setTypeface(typeface, Typeface.BOLD)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }

            val sub = TextView(ctx).apply {
                textSize = 11f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(2f), 0, 0)
            }

            textCol.addView(title)
            textCol.addView(sub)

            val rightCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
            }

            val badge = TextView(ctx).apply {
                textSize = 11.8f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(9f), dp(4f), dp(9f), dp(4f))
            }

            val sitesPill = TextView(ctx).apply {
                text = "🌐 Sites ›"
                textSize = 10.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#38BDF8"))
                setPadding(dp(7f), dp(2f), dp(7f), dp(2f))
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4f) }
            }

            rightCol.addView(badge)
            rightCol.addView(sitesPill)

            mainRow.addView(icon)
            mainRow.addView(textCol)
            mainRow.addView(rightCol)

            val barView = AppUsageBarView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(5f)
                ).apply { topMargin = dp(9f) }
            }

            card.addView(mainRow)
            card.addView(barView)

            return SmoothRowHolder(card, icon, title, sub, sitesPill, badge, barView)
        }

        override fun onBindViewHolder(holder: SmoothRowHolder, position: Int) {
            val row = items[position]
            val ctx = holder.container.context
            val density = ctx.resources.displayMetrics.density
            val isWhite = UiThemePrefs.isWhiteBackgroundActive(ctx)
            val accentColor = row.accentColor

            holder.container.background = UiThemePrefs.createCardBackground(ctx, cornerDp = 14f, isHero = false)
            holder.iconView.setImageDrawable(row.icon)
            holder.titleView.text = row.displayName
            holder.titleView.setTextColor(UiThemePrefs.getScreenHeaderTextColor(ctx))
            holder.subtitleView.text = row.subtitle
            holder.subtitleView.setTextColor(UiThemePrefs.getScreenSubHeaderTextColor(ctx))

            holder.badgeView.text = row.durationLabel
            holder.badgeView.setTextColor(accentColor)
            holder.badgeView.background = GradientDrawable().apply {
                cornerRadius = 10f * density
                setColor(Color.argb(30, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)))
                setStroke((1f * density).roundToInt(), accentColor)
            }

            if (row.isBrowser) {
                holder.sitesPill.text = if (row.isUninstalled) "Deleted · Sites ›" else "🌐 Sites ›"
                holder.sitesPill.setTextColor(Color.parseColor("#38BDF8"))
                holder.sitesPill.visibility = View.VISIBLE
                holder.sitesPill.background = GradientDrawable().apply {
                    cornerRadius = 8f * density
                    setColor(Color.argb(28, 56, 189, 248))
                    setStroke((1f * density).roundToInt(), Color.parseColor("#38BDF8"))
                }
                holder.container.isClickable = true
                holder.container.isFocusable = true
                holder.container.setOnClickListener { onRowClick(row) }
            } else if (row.isUninstalled) {
                holder.sitesPill.text = "Deleted · Kept"
                holder.sitesPill.setTextColor(Color.parseColor("#FDA4AF"))
                holder.sitesPill.visibility = View.VISIBLE
                holder.sitesPill.background = GradientDrawable().apply {
                    cornerRadius = 8f * density
                    setColor(Color.argb(30, 244, 63, 94))
                    setStroke((1f * density).roundToInt(), Color.parseColor("#F43F5E"))
                }
                holder.container.setOnClickListener(null)
                holder.container.isClickable = false
            } else {
                holder.sitesPill.visibility = View.GONE
                holder.container.setOnClickListener(null)
                holder.container.isClickable = false
            }

            holder.barView.bind(
                key = row.packageName,
                primaryFrac = row.primaryBarFraction,
                primaryColor = row.primaryBarColor,
                secondaryFrac = row.secondaryBarFraction,
                secondaryColor = row.secondaryBarColor,
                isLight = isWhite
            )
        }

        override fun getItemCount(): Int = items.size
    }

    private fun bindHourlyTimeline(snapshot: UsageStatsHelper.WellbeingSnapshot) {
        val chart = hourlyChartView ?: return
        val blockLabels = arrayOf("12A", "3A", "6A", "9A", "12P", "3P", "6P", "9P")
        val rangeLabels = arrayOf(
            "12–3 AM",
            "3–6 AM",
            "6–9 AM",
            "9 AM–12 PM",
            "12–3 PM",
            "3–6 PM",
            "6–9 PM",
            "9 PM–12 AM"
        )
        val pts = ArrayList<HourlyScreenTimeCurveView.WindowPoint>(8)
        for (block in 0 until 8) {
            val hStart = block * 3
            val blockMins = snapshot.hourlyMinutes[hStart] +
                snapshot.hourlyMinutes[hStart + 1] +
                snapshot.hourlyMinutes[hStart + 2]
            pts.add(
                HourlyScreenTimeCurveView.WindowPoint(
                    shortLabel = blockLabels[block],
                    rangeLabel = rangeLabels[block],
                    minutes = blockMins
                )
            )
        }
        chart.setWindows(pts)
        val peak = pts.maxByOrNull { it.minutes }
        if (peak != null && peak.minutes > 0) {
            val hrs = peak.minutes / 60
            val mins = peak.minutes % 60
            val durStr = if (hrs > 0) "${hrs}h ${mins}m (${peak.minutes}m)" else "${peak.minutes}m"
            hourlySubtitleTv?.text = "Peak window ${peak.rangeLabel}: $durStr • Drag curve to inspect"
        } else {
            hourlySubtitleTv?.text = "No screen activity recorded in this day's windows yet"
        }
    }

    /**
     * Smooth Cubic-Bezier Curve + Synchronized Window Distribution Strip for Hourly Activity Timeline.
     */
    private class HourlyScreenTimeCurveView(context: android.content.Context) : View(context) {

        data class WindowPoint(
            val shortLabel: String,
            val rangeLabel: String,
            val minutes: Int
        )

        private var windows: List<WindowPoint> = emptyList()
        private var selectedIndex: Int = -1
        var onWindowSelected: ((WindowPoint) -> Unit)? = null

        private val density = resources.displayMetrics.density
        private val scaledDensity = resources.displayMetrics.scaledDensity

        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f * density
        }
        private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.2f * density
            pathEffect = DashPathEffect(floatArrayOf(4f * density, 4f * density), 0f)
        }
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6.5f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val stripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 10.5f * scaledDensity
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        private val calloutTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11f * scaledDensity
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        private val calloutBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val calloutStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.2f * density
        }

        private val curvePath = Path()
        private val fillPath = Path()
        private val rect = RectF()

        fun setWindows(list: List<WindowPoint>) {
            windows = list
            val peakIdx = list.indices.maxByOrNull { list[it].minutes } ?: -1
            selectedIndex = if (peakIdx >= 0 && list[peakIdx].minutes > 0) peakIdx else list.lastIndex
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f || windows.isEmpty()) return

            val isLight = UiThemePrefs.isWhiteBackgroundActive(context)
            val accentColor = Color.parseColor("#27C7C9") // Teal performance curve
            val emeraldColor = Color.parseColor("#16D99A")
            val warnColor = Color.parseColor("#F59E0B")
            val highColor = Color.parseColor("#F43F5E")
            val textPrimary = if (isLight) Color.parseColor("#0F172A") else Color.parseColor("#F1F5F9")
            val textSecondary = if (isLight) Color.parseColor("#64748B") else Color.parseColor("#8995A3")
            gridPaint.color = if (isLight) Color.parseColor("#E2E8F0") else Color.parseColor("#1E293B")

            val padL = 18f * density
            val padR = 18f * density
            val padTop = 36f * density
            val stripH = 22f * density
            val labelH = 20f * density
            val curveBottom = h - stripH - labelH
            val curveH = (curveBottom - padTop).coerceAtLeast(36f * density)
            val chartW = (w - padL - padR).coerceAtLeast(1f)

            for (i in 0..2) {
                val gy = padTop + (curveH * i / 2f)
                canvas.drawLine(padL, gy, w - padR, gy, gridPaint)
            }

            val maxMins = (windows.maxOfOrNull { it.minutes } ?: 1).coerceAtLeast(15).toFloat()
            val count = windows.size
            val stepX = if (count > 1) chartW / (count - 1).toFloat() else 0f

            val coords = windows.mapIndexed { idx, pt ->
                val x = padL + idx * stepX
                val ratio = (pt.minutes.toFloat() / (maxMins * 1.12f)).coerceIn(0f, 1f)
                val y = curveBottom - ratio * curveH
                PointF(x, y)
            }

            curvePath.reset()
            fillPath.reset()
            curvePath.moveTo(coords[0].x, coords[0].y)
            fillPath.moveTo(coords[0].x, curveBottom)
            fillPath.lineTo(coords[0].x, coords[0].y)

            for (i in 0 until coords.size - 1) {
                val p0 = coords[i]
                val p1 = coords[i + 1]
                val cx1 = p0.x + (p1.x - p0.x) * 0.45f
                val cx2 = p0.x + (p1.x - p0.x) * 0.55f
                curvePath.cubicTo(cx1, p0.y, cx2, p1.y, p1.x, p1.y)
                fillPath.cubicTo(cx1, p0.y, cx2, p1.y, p1.x, p1.y)
            }
            fillPath.lineTo(coords.last().x, curveBottom)
            fillPath.close()

            fillPaint.shader = LinearGradient(
                0f, padTop, 0f, curveBottom,
                intArrayOf(
                    Color.argb(115, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)),
                    Color.argb(35, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)),
                    Color.argb(0, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
                ),
                floatArrayOf(0f, 0.65f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawPath(fillPath, fillPaint)

            glowPaint.color = Color.argb(50, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
            canvas.drawPath(curvePath, glowPaint)

            curvePaint.color = accentColor
            canvas.drawPath(curvePath, curvePaint)

            // Bottom synchronized mini intensity capsules + X-axis labels
            val labelY = curveBottom + 14f * density
            val stripTop = curveBottom + labelH + 3f * density
            val stripBottom = h - 3f * density
            val maxBarH = (stripBottom - stripTop).coerceAtLeast(8f * density)
            val barW = 14f * density

            coords.forEachIndexed { idx, pt ->
                val mins = windows[idx].minutes
                val isSel = idx == selectedIndex
                val ptColor = when {
                    mins >= 90 -> highColor
                    mins >= 45 -> warnColor
                    mins > 0 -> emeraldColor
                    else -> accentColor
                }

                axisPaint.color = if (isSel) accentColor else textSecondary
                canvas.drawText(windows[idx].shortLabel, pt.x, labelY, axisPaint)

                val barRatio = if (mins > 0) (mins.toFloat() / maxMins).coerceIn(0.22f, 1f) else 0.14f
                val bh = barRatio * maxBarH
                stripPaint.color = when {
                    isSel -> ptColor
                    mins > 0 -> Color.argb(150, Color.red(ptColor), Color.green(ptColor), Color.blue(ptColor))
                    isLight -> Color.parseColor("#E2E8F0")
                    else -> Color.parseColor("#17212B")
                }
                rect.set(pt.x - barW / 2f, stripBottom - bh, pt.x + barW / 2f, stripBottom)
                canvas.drawRoundRect(rect, 4f * density, 4f * density, stripPaint)

                if (isSel) {
                    guidePaint.color = Color.argb(130, Color.red(ptColor), Color.green(ptColor), Color.blue(ptColor))
                    canvas.drawLine(pt.x, padTop * 0.8f, pt.x, curveBottom, guidePaint)

                    dotPaint.color = Color.argb(75, Color.red(ptColor), Color.green(ptColor), Color.blue(ptColor))
                    canvas.drawCircle(pt.x, pt.y, 7.5f * density, dotPaint)
                    dotPaint.color = ptColor
                    canvas.drawCircle(pt.x, pt.y, 4.2f * density, dotPaint)
                    dotPaint.color = if (isLight) Color.WHITE else Color.parseColor("#080D12")
                    canvas.drawCircle(pt.x, pt.y, 1.8f * density, dotPaint)
                } else if (mins > 0) {
                    dotPaint.color = ptColor
                    canvas.drawCircle(pt.x, pt.y, 2.8f * density, dotPaint)
                }
            }

            // Floating callout pill for selected window
            val selIdx = selectedIndex.coerceIn(0, windows.lastIndex)
            val selWin = windows[selIdx]
            val selPt = coords[selIdx]
            val durLabel = if (selWin.minutes >= 60) {
                "${selWin.minutes / 60}h ${selWin.minutes % 60}m (${selWin.minutes}m)"
            } else {
                "${selWin.minutes}m"
            }
            val pillText = "${selWin.rangeLabel} • $durLabel"
            val textW = calloutTextPaint.measureText(pillText)
            val pillW = textW + 20f * density
            val pillH = 22f * density
            val pillLeft = (selPt.x - pillW / 2f).coerceIn(padL / 2f, w - padR / 2f - pillW)
            val pillTop = 4f * density
            rect.set(pillLeft, pillTop, pillLeft + pillW, pillTop + pillH)

            val selColor = when {
                selWin.minutes >= 90 -> highColor
                selWin.minutes >= 45 -> warnColor
                selWin.minutes > 0 -> emeraldColor
                else -> accentColor
            }
            calloutBgPaint.color = if (isLight) Color.parseColor("#F1F5F9") else Color.parseColor("#111923")
            calloutStrokePaint.color = selColor
            canvas.drawRoundRect(rect, pillH / 2f, pillH / 2f, calloutBgPaint)
            canvas.drawRoundRect(rect, pillH / 2f, pillH / 2f, calloutStrokePaint)

            calloutTextPaint.color = textPrimary
            val fm = calloutTextPaint.fontMetrics
            val textY = rect.centerY() - (fm.ascent + fm.descent) / 2f
            canvas.drawText(pillText, rect.centerX(), textY, calloutTextPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (windows.isEmpty()) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_MOVE -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    val padL = 18f * density
                    val padR = 18f * density
                    val chartW = (width - padL - padR).coerceAtLeast(1f)
                    val ratio = ((event.x - padL) / chartW).coerceIn(0f, 1f)
                    val idx = (ratio * (windows.size - 1)).roundToInt().coerceIn(0, windows.lastIndex)
                    if (idx != selectedIndex) {
                        selectedIndex = idx
                        onWindowSelected?.invoke(windows[idx])
                        invalidate()
                    }
                    return true
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }
}
