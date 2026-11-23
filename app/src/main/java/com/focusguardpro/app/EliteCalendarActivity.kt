package com.focusguardpro.app

import android.app.TimePickerDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import com.focusguardpro.app.insights.InsightsDashboardActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Redesigned Personal Discipline Calendar (Month / Week / Agenda views) with:
 * - Distinct 5-signal activity indicators (Steps goal, Workouts, Holidays, Diary, Tasks) + legend
 * - Compact Monthly Discipline Summary (Active days, Step goal, Workouts, Tasks %, Journal)
 * - Rich Selected-Day Overview with inline Tasks checklist and 1-tap quick actions
 * - Full preservation of 24-hour advance Holiday rule & Focus Layer holiday exemptions
 * - Seamless theme compatibility across Ocean Cyan, AMOLED Black, Daylight White, and all palettes
 */
class EliteCalendarActivity : AppCompatActivity(), SelfThemedDisciplineActivity {

    companion object {
        const val EXTRA_DATE_KEY = "EXTRA_DATE_KEY"
        private const val MODE_MONTH = 0
        private const val MODE_WEEK = 1
        private const val MODE_AGENDA = 2
    }

    private lateinit var theme: DisciplineTheme.Tokens
    private var viewMode: Int = MODE_MONTH
    private val displayMonthCal: Calendar = Calendar.getInstance()
    private var selectedDateKey: String = ""
    private var todayKey: String = ""

    private var monthDaysMap: Map<String, DisciplineDataRepository.CalendarDaySummary> = emptyMap()
    private var monthSummary: DisciplineDataRepository.MonthlyDisciplineSummary? = null
    private var loadJob: Job? = null

    // Top-level views
    private lateinit var rootLayout: LinearLayout
    private lateinit var btnModeMonth: TextView
    private lateinit var btnModeWeek: TextView
    private lateinit var btnModeAgenda: TextView
    private lateinit var tvPeriodTitle: TextView
    private lateinit var summaryStripContainer: LinearLayout
    private lateinit var mainCalendarContent: LinearLayout
    private lateinit var selectedDayOverviewContainer: LinearLayout

    // Optional Tasks navigation hook wired in Stage 2 (gracefully falls back to quick-add in Stage 1)
    private var onOpenTasksActivity: ((String) -> Unit)? = null

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        todayKey = DisciplineDataRepository.todayKey(this)
        selectedDateKey = intent?.getStringExtra(EXTRA_DATE_KEY)
            ?.takeIf { it.length == 8 }
            ?: todayKey

        val selCal = DisciplineDataRepository.parseDateKeyToCalendar(selectedDateKey)
        displayMonthCal.set(Calendar.YEAR, selCal.get(Calendar.YEAR))
        displayMonthCal.set(Calendar.MONTH, selCal.get(Calendar.MONTH))
        displayMonthCal.set(Calendar.DAY_OF_MONTH, 1)

        wireTasksHookIfPresent()
        theme = DisciplineTheme.resolve(this)

        buildUi()
        DisciplineTheme.applySystemBars(this, theme)
        loadMonthAndRender()
    }

    override fun onResume() {
        super.onResume()
        todayKey = DisciplineDataRepository.todayKey(this)
        val freshTheme = DisciplineTheme.resolve(this)
        if (freshTheme.bg != theme.bg || freshTheme.accent != theme.accent) {
            theme = freshTheme
            buildUi()
            DisciplineTheme.applySystemBars(this, theme)
        }
        loadMonthAndRender()
    }

    private fun wireTasksHookIfPresent() {
        onOpenTasksActivity = { dk ->
            startActivity(Intent(this, EliteTasksActivity::class.java).apply {
                putExtra(EliteTasksActivity.EXTRA_DATE_KEY, dk)
            })
        }
    }

    private fun buildUi() {
        val d = resources.displayMetrics.density
        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.bg)
            fitsSystemWindows = true
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // 1. Compact Header Bar
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10f), dp(8f), dp(12f), dp(6f))
        }

        header.addView(TextView(this).apply {
            text = "←"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(theme.textPrimary)
            gravity = Gravity.CENTER
            minWidth = dp(44f)
            minHeight = dp(44f)
            contentDescription = "Back"
            setOnClickListener { finish() }
        })

        header.addView(TextView(this).apply {
            text = "Calendar"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(theme.textPrimary)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(4f), 0, 0, 0)
        })

        // Tasks Manager Shortcut
        header.addView(TextView(this).apply {
            text = "Tasks"
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(theme.accent)
            setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
            background = DisciplineTheme.pillDrawable(theme.accentSoft, null, d)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(5f) }
            setOnClickListener {
                startActivity(Intent(this@EliteCalendarActivity, EliteTasksActivity::class.java).apply {
                    putExtra(EliteTasksActivity.EXTRA_DATE_KEY, selectedDateKey)
                })
            }
        })

        // Journal Archive Shortcut
        header.addView(TextView(this).apply {
            text = "Journal"
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(theme.textPrimary)
            setPadding(dp(9f), dp(6f), dp(9f), dp(6f))
            background = DisciplineTheme.pillDrawable(theme.surface, theme.border, d)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(5f) }
            setOnClickListener {
                startActivity(Intent(this@EliteCalendarActivity, EliteJournalActivity::class.java).apply {
                    putExtra(EliteJournalActivity.EXTRA_DATE_KEY, selectedDateKey)
                    putExtra(EliteJournalActivity.EXTRA_INITIAL_MODE, EliteJournalActivity.MODE_HISTORY)
                })
            }
        })

        // Insights Shortcut
        header.addView(TextView(this).apply {
            text = "Insights"
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(theme.textPrimary)
            setPadding(dp(9f), dp(6f), dp(9f), dp(6f))
            background = DisciplineTheme.pillDrawable(theme.surface, theme.border, d)
            setOnClickListener {
                startActivity(Intent(this@EliteCalendarActivity, InsightsDashboardActivity::class.java))
            }
        })
        rootLayout.addView(header)

        // 2. Period Navigation + Today Shortcut
        val navStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14f), dp(4f), dp(14f), dp(8f))
        }

        val btnPrev = TextView(this).apply {
            text = "‹"
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setTextColor(theme.textPrimary)
            gravity = Gravity.CENTER
            minWidth = dp(38f)
            minHeight = dp(38f)
            background = DisciplineTheme.cardDrawable(theme.surface, theme.border, 9f, d)
            contentDescription = "Previous period"
            setOnClickListener { stepPeriod(-1) }
        }
        navStrip.addView(btnPrev)

        tvPeriodTitle = TextView(this).apply {
            textSize = 14.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(theme.textPrimary)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(8f), 0, dp(8f), 0)
        }
        navStrip.addView(tvPeriodTitle)

        navStrip.addView(TextView(this).apply {
            text = "Today"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(theme.accent)
            setPadding(dp(9f), dp(6f), dp(9f), dp(6f))
            background = DisciplineTheme.pillDrawable(theme.accentSoft, null, d)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6f) }
            setOnClickListener { jumpToToday() }
        })

        val btnNext = TextView(this).apply {
            text = "›"
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setTextColor(theme.textPrimary)
            gravity = Gravity.CENTER
            minWidth = dp(38f)
            minHeight = dp(38f)
            background = DisciplineTheme.cardDrawable(theme.surface, theme.border, 9f, d)
            contentDescription = "Next period"
            setOnClickListener { stepPeriod(1) }
        }
        navStrip.addView(btnNext)
        rootLayout.addView(navStrip)

        // Segmented Mode Bar
        val modeWrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(3f), dp(3f), dp(3f), dp(3f))
            background = DisciplineTheme.cardDrawable(theme.surface, theme.border, 10f, d)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(14f), 0, dp(14f), dp(8f))
            }
        }
        btnModeMonth = createModeButton("Month") { switchViewMode(MODE_MONTH) }
        btnModeWeek = createModeButton("Week") { switchViewMode(MODE_WEEK) }
        btnModeAgenda = createModeButton("Agenda") { switchViewMode(MODE_AGENDA) }
        modeWrap.addView(btnModeMonth)
        modeWrap.addView(btnModeWeek)
        modeWrap.addView(btnModeAgenda)
        rootLayout.addView(modeWrap)

        // 3. Scrollable Main Body (Compact Monthly Summary + Calendar View + Selected Day Overview)
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), 0, dp(14f), dp(24f))
        }

        summaryStripContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8f) }
        }
        body.addView(summaryStripContainer)

        mainCalendarContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10f) }
        }
        body.addView(mainCalendarContent)

        selectedDayOverviewContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        body.addView(selectedDayOverviewContainer)

        scroll.addView(body)
        rootLayout.addView(scroll)
        setContentView(rootLayout)
        updateModeButtonsStyle()
    }

    private fun createModeButton(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(8f), dp(7f), dp(8f), dp(7f))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onClick() }
        }
    }

    private fun updateModeButtonsStyle() {
        val d = resources.displayMetrics.density
        val list = listOf(MODE_MONTH to btnModeMonth, MODE_WEEK to btnModeWeek, MODE_AGENDA to btnModeAgenda)
        for ((mode, btn) in list) {
            val active = viewMode == mode
            btn.background = if (active) DisciplineTheme.cardDrawable(theme.accent, theme.accent, 8f, d, 0f) else null
            btn.setTextColor(if (active) theme.accentOnFill else theme.textSecondary)
        }
    }

    private fun switchViewMode(mode: Int) {
        if (viewMode == mode) return
        viewMode = mode
        updateModeButtonsStyle()
        mainCalendarContent.alpha = 0.25f
        renderActiveCalendarView()
        mainCalendarContent.animate().alpha(1f).setDuration(150L).start()
    }

    private fun jumpToToday() {
        todayKey = DisciplineDataRepository.todayKey(this)
        selectedDateKey = todayKey
        val cal = DisciplineDataRepository.parseDateKeyToCalendar(todayKey)
        displayMonthCal.set(Calendar.YEAR, cal.get(Calendar.YEAR))
        displayMonthCal.set(Calendar.MONTH, cal.get(Calendar.MONTH))
        displayMonthCal.set(Calendar.DAY_OF_MONTH, 1)
        loadMonthAndRender()
    }

    private fun stepPeriod(delta: Int) {
        if (viewMode == MODE_WEEK) {
            selectedDateKey = DisciplineDataRepository.addDays(selectedDateKey, delta * 7)
            val selCal = DisciplineDataRepository.parseDateKeyToCalendar(selectedDateKey)
            val monthChanged = selCal.get(Calendar.YEAR) != displayMonthCal.get(Calendar.YEAR) ||
                selCal.get(Calendar.MONTH) != displayMonthCal.get(Calendar.MONTH)
            if (monthChanged) {
                displayMonthCal.set(Calendar.YEAR, selCal.get(Calendar.YEAR))
                displayMonthCal.set(Calendar.MONTH, selCal.get(Calendar.MONTH))
                displayMonthCal.set(Calendar.DAY_OF_MONTH, 1)
                loadMonthAndRender()
            } else {
                renderActiveCalendarView()
                renderSelectedDayOverview()
            }
        } else {
            displayMonthCal.add(Calendar.MONTH, delta)
            // Keep selected date inside the navigated month (day 1 or today if current month)
            val nowCal = DisciplineDataRepository.parseDateKeyToCalendar(todayKey)
            if (displayMonthCal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR) &&
                displayMonthCal.get(Calendar.MONTH) == nowCal.get(Calendar.MONTH)
            ) {
                selectedDateKey = todayKey
            } else {
                val first = (displayMonthCal.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1) }
                selectedDateKey = DisciplineDataRepository.dateKeyFor(first)
            }
            loadMonthAndRender()
        }
    }

    private fun loadMonthAndRender() {
        loadJob?.cancel()
        val year = displayMonthCal.get(Calendar.YEAR)
        val month = displayMonthCal.get(Calendar.MONTH)
        tvPeriodTitle.text = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(displayMonthCal.time)

        // Frame-0 instant paint: never leave the calendar body blank while IO loads
        if (mainCalendarContent.childCount == 0) {
            renderActiveCalendarView()
            renderSelectedDayOverview()
        }

        loadJob = lifecycleScope.launch {
            try {
                val (daysMap, summary) = withContext(Dispatchers.IO) {
                    DisciplineDataRepository.loadMonthSummaries(this@EliteCalendarActivity, year, month)
                }
                if (isFinishing || isDestroyed) return@launch
                monthDaysMap = daysMap
                monthSummary = summary
                renderMonthlySummaryStrip(summary)
                renderActiveCalendarView()
                renderSelectedDayOverview()
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                if (!isFinishing && !isDestroyed) {
                    renderActiveCalendarView()
                    renderSelectedDayOverview()
                }
            }
        }
    }

    private fun renderMonthlySummaryStrip(summary: DisciplineDataRepository.MonthlyDisciplineSummary) {
        val d = resources.displayMetrics.density
        summaryStripContainer.removeAllViews()

        val taskPctLabel = summary.taskCompletionPercent?.let { "$it%" } ?: "—"
        val stepLabel = if (summary.recordedStepDays > 0) {
            "${summary.stepGoalDays}/${summary.recordedStepDays}"
        } else "—"

        val metrics = listOf(
            Triple("Active", "${summary.activeDays}d", theme.accent),
            Triple("Steps", stepLabel, theme.stepGreen),
            Triple("Workouts", "${summary.workoutDays}d", theme.workoutCyan),
            Triple("Tasks", taskPctLabel, theme.taskBlue),
            Triple("Journal", "${summary.journalDays}d", theme.diaryViolet)
        )

        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6f), dp(8f), dp(6f), dp(8f))
            background = DisciplineTheme.cardDrawable(theme.surface, theme.border, 12f, d)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        metrics.forEachIndexed { idx, (label, value, color) ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            cell.addView(TextView(this).apply {
                text = value
                textSize = 13.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(color)
            })
            cell.addView(TextView(this).apply {
                text = label
                textSize = 10f
                setTextColor(theme.textSecondary)
                setPadding(0, dp(1f), 0, 0)
            })
            strip.addView(cell)
            if (idx < metrics.lastIndex) {
                strip.addView(View(this).apply {
                    setBackgroundColor(theme.border)
                    layoutParams = LinearLayout.LayoutParams(dp(1f), dp(24f)).apply {
                        gravity = Gravity.CENTER_VERTICAL
                    }
                })
            }
        }
        summaryStripContainer.addView(strip)
    }

    private fun renderActiveCalendarView() {
        when (viewMode) {
            MODE_MONTH -> {
                tvPeriodTitle.text = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(displayMonthCal.time)
                selectedDayOverviewContainer.visibility = View.VISIBLE
                renderMonthGrid()
            }
            MODE_WEEK -> {
                selectedDayOverviewContainer.visibility = View.VISIBLE
                renderWeekStrip()
            }
            MODE_AGENDA -> {
                tvPeriodTitle.text = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(displayMonthCal.time)
                selectedDayOverviewContainer.visibility = View.VISIBLE
                renderAgendaList()
            }
        }
    }

    // =========================================================================
    // MONTH VIEW
    // =========================================================================

    private fun renderMonthGrid() {
        val d = resources.displayMetrics.density
        mainCalendarContent.removeAllViews()

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8f), dp(10f), dp(8f), dp(10f))
            background = DisciplineTheme.cardDrawable(theme.surface, theme.border, 14f, d)
        }

        // Weekday headers
        val dowRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6f) }
        }
        val headers = listOf("S", "M", "T", "W", "T", "F", "S")
        for (h in headers) {
            dowRow.addView(TextView(this).apply {
                text = h
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                setTextColor(theme.textMuted)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
        card.addView(dowRow)

        val firstCal = (displayMonthCal.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1) }
        val offset = firstCal.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY
        val daysInMonth = firstCal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val totalSlots = ((offset + daysInMonth + 6) / 7) * 7

        var row = createWeekRow()
        for (slot in 0 until totalSlots) {
            if (slot > 0 && slot % 7 == 0) {
                card.addView(row)
                row = createWeekRow()
            }
            val dayNum = slot - offset + 1
            if (dayNum < 1 || dayNum > daysInMonth) {
                row.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, dp(46f), 1f)
                })
            } else {
                val cellCal = (firstCal.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, dayNum) }
                val dk = DisciplineDataRepository.dateKeyFor(cellCal)
                val summary = monthDaysMap[dk]
                row.addView(buildMonthDayCell(dayNum, dk, summary, d))
            }
        }
        card.addView(row)

        // Compact Readable Indicator Legend
        card.addView(buildIndicatorLegend(d))
        mainCalendarContent.addView(card)
    }

    private fun createWeekRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(3f) }
    }

    private fun buildMonthDayCell(
        dayNum: Int,
        dateKey: String,
        summary: DisciplineDataRepository.CalendarDaySummary?,
        d: Float
    ): View {
        val isSelected = dateKey == selectedDateKey
        val isToday = dateKey == todayKey

        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, dp(46f), 1f).apply {
                setMargins(dp(1.5f), dp(1f), dp(1.5f), dp(1f))
            }
            background = when {
                isSelected -> DisciplineTheme.cardDrawable(theme.accentSoft, theme.accent, 10f, d, 1.5f)
                summary?.isHoliday == true -> DisciplineTheme.cardDrawable(
                    ColorUtils.setAlphaComponent(theme.holidayAmber, 22),
                    ColorUtils.setAlphaComponent(theme.holidayAmber, 95),
                    10f,
                    d,
                    1f
                )
                isToday -> DisciplineTheme.cardDrawable(Color.TRANSPARENT, theme.border, 10f, d, 1.2f)
                else -> null
            }
            setOnClickListener {
                selectedDateKey = dateKey
                renderActiveCalendarView()
                renderSelectedDayOverview()
            }
        }

        cell.addView(TextView(this).apply {
            text = dayNum.toString()
            textSize = 13f
            setTypeface(null, if (isSelected || isToday) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(
                when {
                    isSelected -> theme.accent
                    isToday -> theme.textPrimary
                    dateKey > todayKey -> theme.textSecondary
                    else -> theme.textPrimary
                }
            )
        })

        // Distinct 5-indicator dot strip (missing step records NEVER shown as failed!)
        val dots = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(3f), 0, 0)
        }

        fun addDot(color: Int) {
            dots.addView(View(this).apply {
                background = DisciplineTheme.circleDrawable(color)
                layoutParams = LinearLayout.LayoutParams(dp(4.5f), dp(4.5f)).apply {
                    marginStart = dp(1.5f)
                    marginEnd = dp(1.5f)
                }
            })
        }

        if (summary != null) {
            // 1. Steps (emerald if goal met; subtle muted green only if recorded & > 0; nothing if no record)
            if (summary.stepGoalMet) {
                addDot(theme.stepGreen)
            } else if (summary.steps != null && summary.steps > 0) {
                addDot(ColorUtils.setAlphaComponent(theme.stepGreen, 110))
            }
            // 2. Workouts
            if (summary.hasWorkout) addDot(theme.workoutCyan)
            // 3. Holiday
            if (summary.isHoliday) addDot(theme.holidayAmber)
            // 4. Diary
            if (summary.hasDiary) addDot(theme.diaryViolet)
            // 5. Tasks (coral if overdue pending, sky blue if scheduled/done)
            if (summary.overdueTasks > 0) {
                addDot(theme.overdueCoral)
            } else if (summary.totalTasks > 0) {
                addDot(theme.taskBlue)
            }
        }

        cell.addView(dots)
        return cell
    }

    private fun buildIndicatorLegend(d: Float): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(4f), dp(8f), dp(4f), dp(2f))
        }
        val items = listOf(
            "Steps" to theme.stepGreen,
            "Workout" to theme.workoutCyan,
            "Holiday" to theme.holidayAmber,
            "Diary" to theme.diaryViolet,
            "Tasks" to theme.taskBlue
        )
        for ((label, color) in items) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(10f) }
            }
            item.addView(View(this).apply {
                background = DisciplineTheme.circleDrawable(color)
                layoutParams = LinearLayout.LayoutParams(dp(6f), dp(6f)).apply {
                    marginEnd = dp(4f)
                }
            })
            item.addView(TextView(this).apply {
                text = label
                textSize = 10f
                setTextColor(theme.textSecondary)
            })
            wrap.addView(item)
        }
        return wrap
    }

    // =========================================================================
    // WEEK VIEW
    // =========================================================================

    private fun renderWeekStrip() {
        val d = resources.displayMetrics.density
        mainCalendarContent.removeAllViews()

        val selCal = DisciplineDataRepository.parseDateKeyToCalendar(selectedDateKey)
        val weekStart = (selCal.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, -(get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY))
        }
        val weekEnd = (weekStart.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 6) }
        val startKey = DisciplineDataRepository.dateKeyFor(weekStart)
        val endKey = DisciplineDataRepository.dateKeyFor(weekEnd)

        val fmtShort = SimpleDateFormat("MMM d", Locale.getDefault())
        tvPeriodTitle.text = "${fmtShort.format(weekStart.time)} – ${fmtShort.format(weekEnd.time)}"

        // Ensure week spanning month boundary has complete data
        lifecycleScope.launch {
            val weekMap = try {
                withContext(Dispatchers.IO) {
                    DisciplineDataRepository.loadDayRangeSummaries(this@EliteCalendarActivity, startKey, endKey)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                emptyMap()
            }
            if (isFinishing || isDestroyed || viewMode != MODE_WEEK) return@launch
            mainCalendarContent.removeAllViews()

            val card = LinearLayout(this@EliteCalendarActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
                background = DisciplineTheme.cardDrawable(theme.surface, theme.border, 14f, d)
            }

            val stripRow = LinearLayout(this@EliteCalendarActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            val dowFmt = SimpleDateFormat("EEE", Locale.getDefault())
            val cursor = weekStart.clone() as Calendar
            for (i in 0..6) {
                val dk = DisciplineDataRepository.dateKeyFor(cursor)
                val daySummary = weekMap[dk]
                val isSel = dk == selectedDateKey
                val isToday = dk == todayKey

                val col = LinearLayout(this@EliteCalendarActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(3f), dp(8f), dp(3f), dp(8f))
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = dp(2f)
                        marginEnd = dp(2f)
                    }
                    background = when {
                        isSel -> DisciplineTheme.cardDrawable(theme.accentSoft, theme.accent, 10f, d, 1.5f)
                        isToday -> DisciplineTheme.cardDrawable(theme.surfaceAlt, theme.border, 10f, d)
                        else -> DisciplineTheme.cardDrawable(Color.TRANSPARENT, Color.TRANSPARENT, 10f, d, 0f)
                    }
                    setOnClickListener {
                        selectedDateKey = dk
                        monthDaysMap = monthDaysMap + weekMap
                        renderWeekStrip()
                        renderSelectedDayOverview()
                    }
                }

                col.addView(TextView(this@EliteCalendarActivity).apply {
                    text = dowFmt.format(cursor.time).take(3)
                    textSize = 10f
                    setTextColor(if (isSel) theme.accent else theme.textMuted)
                })
                col.addView(TextView(this@EliteCalendarActivity).apply {
                    text = cursor.get(Calendar.DAY_OF_MONTH).toString()
                    textSize = 15f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(if (isSel) theme.accent else theme.textPrimary)
                    setPadding(0, dp(2f), 0, dp(4f))
                })

                // Micro step progress bar if recorded
                val ratio = if (daySummary?.steps != null && daySummary.stepGoal > 0) {
                    (daySummary.steps.toFloat() / daySummary.stepGoal).coerceIn(0f, 1f)
                } else 0f
                val barBg = LinearLayout(this@EliteCalendarActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(26f), dp(4f)).apply { bottomMargin = dp(4f) }
                    background = DisciplineTheme.pillDrawable(theme.surfaceAlt)
                }
                if (daySummary?.steps != null && daySummary.steps > 0) {
                    barBg.addView(View(this@EliteCalendarActivity).apply {
                        layoutParams = LinearLayout.LayoutParams((dp(26f) * ratio).toInt().coerceAtLeast(dp(3f)), ViewGroup.LayoutParams.MATCH_PARENT)
                        background = DisciplineTheme.pillDrawable(theme.stepGreen)
                    })
                }
                col.addView(barBg)

                // Dots
                val dots = LinearLayout(this@EliteCalendarActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                }
                fun addDot(color: Int) {
                    dots.addView(View(this@EliteCalendarActivity).apply {
                        background = DisciplineTheme.circleDrawable(color)
                        layoutParams = LinearLayout.LayoutParams(dp(4.5f), dp(4.5f)).apply {
                            marginStart = dp(1f); marginEnd = dp(1f)
                        }
                    })
                }
                if (daySummary != null) {
                    if (daySummary.hasWorkout) addDot(theme.workoutCyan)
                    if (daySummary.isHoliday) addDot(theme.holidayAmber)
                    if (daySummary.hasDiary) addDot(theme.diaryViolet)
                    if (daySummary.overdueTasks > 0) addDot(theme.overdueCoral)
                    else if (daySummary.totalTasks > 0) addDot(theme.taskBlue)
                }
                col.addView(dots)

                stripRow.addView(col)
                cursor.add(Calendar.DAY_OF_YEAR, 1)
            }

            card.addView(stripRow)
            card.addView(buildIndicatorLegend(d))
            mainCalendarContent.addView(card)

            // Sync selected day cache if week crossed month boundary
            if (!monthDaysMap.containsKey(selectedDateKey) && weekMap.containsKey(selectedDateKey)) {
                monthDaysMap = monthDaysMap + weekMap
                renderSelectedDayOverview()
            }
        }
    }

    // =========================================================================
    // AGENDA VIEW
    // =========================================================================

    private fun renderAgendaList() {
        val d = resources.displayMetrics.density
        mainCalendarContent.removeAllViews()

        val activeAgendaDays = monthDaysMap.values
            .filter {
                it.isHoliday || it.totalTasks > 0 || it.hasDiary || it.hasWorkout || (it.steps != null && it.steps > 0) || it.dateKey == selectedDateKey
            }
            .sortedBy { it.dateKey }

        if (activeAgendaDays.isEmpty()) {
            mainCalendarContent.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(18f), dp(26f), dp(18f), dp(26f))
                background = DisciplineTheme.cardDrawable(theme.surface, theme.border, 12f, d)
                addView(TextView(this@EliteCalendarActivity).apply {
                    text = "No scheduled items or activity in this month yet."
                    textSize = 13.5f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(theme.textPrimary)
                })
                addView(TextView(this@EliteCalendarActivity).apply {
                    text = "Use the quick actions below to add tasks, write a journal entry, or plan a holiday."
                    textSize = 11.5f
                    setTextColor(theme.textSecondary)
                    setPadding(0, dp(4f), 0, 0)
                })
            })
            return
        }

        for (day in activeAgendaDays) {
            val isSel = day.dateKey == selectedDateKey
            val rowCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
                background = DisciplineTheme.cardDrawable(
                    theme.surface,
                    if (isSel) theme.accent else theme.border,
                    12f,
                    d,
                    if (isSel) 1.5f else 1f
                )
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(7f) }
                setOnClickListener {
                    selectedDateKey = day.dateKey
                    renderAgendaList()
                    renderSelectedDayOverview()
                }
            }

            // Date Header
            val top = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            top.addView(TextView(this).apply {
                text = DisciplineDataRepository.formatFriendlyDate(day.dateKey, this@EliteCalendarActivity)
                textSize = 13.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(if (isSel) theme.accent else theme.textPrimary)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (day.isHoliday) {
                top.addView(createTinyPill("Holiday", theme.holidayAmber, d))
            }
            if (day.steps != null) {
                top.addView(createTinyPill("${String.format(Locale.US, "%,d", day.steps)} steps", theme.stepGreen, d))
            }
            if (day.hasWorkout) {
                top.addView(createTinyPill("${day.workoutCount} workout", theme.workoutCyan, d))
            }
            rowCard.addView(top)

            // Tasks preview (interactive checkboxes directly in Agenda!)
            for (task in day.tasks.take(4)) {
                rowCard.addView(buildInlineTaskRow(task, d))
            }
            if (day.tasks.size > 4) {
                rowCard.addView(TextView(this).apply {
                    text = "+${day.tasks.size - 4} more tasks"
                    textSize = 11f
                    setTextColor(theme.accent)
                    setPadding(dp(26f), dp(3f), 0, 0)
                })
            }

            // Diary snippet
            if (day.hasDiary) {
                rowCard.addView(TextView(this).apply {
                    text = "Journal: ${day.diarySnippet}"
                    textSize = 11.5f
                    maxLines = 2
                    setTextColor(theme.diaryViolet)
                    setPadding(0, dp(5f), 0, 0)
                })
            }

            mainCalendarContent.addView(rowCard)
        }
    }

    // =========================================================================
    // SELECTED-DAY OVERVIEW & QUICK ACTIONS
    // =========================================================================

    private fun renderSelectedDayOverview() {
        val d = resources.displayMetrics.density
        selectedDayOverviewContainer.removeAllViews()

        val summary = monthDaysMap[selectedDateKey] ?: DisciplineDataRepository.CalendarDaySummary(
            dateKey = selectedDateKey,
            dayStartMs = DisciplineDataRepository.startOfDayMs(selectedDateKey),
            steps = null,
            stepGoal = try { GoalPrefs.getStepGoal(this) } catch (_: Throwable) { 6000 },
            stepGoalMet = false,
            workoutLogs = emptyList(),
            walkSessionCount = 0,
            isHoliday = false,
            isVerifiedHolidayToday = false,
            hasDiary = false,
            diarySnippet = "",
            journalMeta = null,
            tasks = emptyList(),
            awayAuditRecords = emptyList()
        )

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(12f), dp(14f), dp(14f))
            background = DisciplineTheme.cardDrawable(theme.surface, theme.border, 14f, d)
        }

        // 1. Selected Day Header + Holiday Status + Notebook Link
        val headRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10f) }
        }

        val dateCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        dateCol.addView(TextView(this).apply {
            text = DisciplineDataRepository.formatFriendlyDate(selectedDateKey, this@EliteCalendarActivity)
            textSize = 15.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(theme.textPrimary)
        })
        val holidaySub = when {
            summary.isVerifiedHolidayToday -> "Verified Focus Holiday · Focus Layer paused"
            summary.isHoliday && selectedDateKey == todayKey -> "Holiday marked (subject to 24h advance rule)"
            summary.isHoliday -> "Focus Holiday scheduled (≥24h verified)"
            else -> DisciplineDataRepository.formatFullDate(selectedDateKey)
        }
        dateCol.addView(TextView(this).apply {
            text = holidaySub
            textSize = 11f
            setTextColor(if (summary.isHoliday) theme.holidayAmber else theme.textSecondary)
        })
        headRow.addView(dateCol)

        headRow.addView(TextView(this).apply {
            text = "Time Audit ›"
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(theme.accent)
            setPadding(dp(8f), dp(6f), dp(4f), dp(6f))
            setOnClickListener {
                startActivity(Intent(this@EliteCalendarActivity, EliteDailyNotebookActivity::class.java))
            }
        })
        card.addView(headRow)

        // 2. Compact 2x2 Metrics Grid (Steps, Workouts, Tasks, Diary)
        val gridRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6f) }
        }
        val stepVal = if (summary.steps != null) {
            "${String.format(Locale.US, "%,d", summary.steps)} / ${String.format(Locale.US, "%,d", summary.stepGoal)}"
        } else {
            if (selectedDateKey > todayKey) "Upcoming" else "No record"
        }
        gridRow1.addView(buildCompactMetricBox(
            title = "STEPS",
            value = stepVal,
            accent = if (summary.stepGoalMet) theme.stepGreen else theme.textPrimary,
            marginEndPx = dp(6f),
            d = d
        ))
        val workoutVal = when {
            summary.workoutLogs.isNotEmpty() && summary.walkSessionCount > 0 ->
                "${summary.workoutLogs.size} sets · ${summary.walkSessionCount} walks"
            summary.workoutLogs.isNotEmpty() -> "${summary.workoutLogs.size} exercise sets"
            summary.walkSessionCount > 0 -> "${summary.walkSessionCount} walk sessions"
            else -> "None logged"
        }
        gridRow1.addView(buildCompactMetricBox(
            title = "WORKOUTS",
            value = workoutVal,
            accent = if (summary.hasWorkout) theme.workoutCyan else theme.textSecondary,
            marginEndPx = 0,
            d = d
        ))
        card.addView(gridRow1)

        val gridRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10f) }
        }
        val taskVal = if (summary.totalTasks > 0) {
            val pct = (summary.completedTasks * 100) / summary.totalTasks
            "${summary.completedTasks}/${summary.totalTasks} done ($pct%)"
        } else {
            "No tasks"
        }
        gridRow2.addView(buildCompactMetricBox(
            title = "TASKS",
            value = taskVal,
            accent = when {
                summary.overdueTasks > 0 -> theme.overdueCoral
                summary.totalTasks > 0 -> theme.taskBlue
                else -> theme.textSecondary
            },
            marginEndPx = dp(6f),
            d = d
        ))
        val diaryVal = if (summary.hasDiary) {
            val wc = summary.journalMeta?.wordCount ?: DisciplineDataRepository.countWords(summary.diarySnippet)
            val mood = summary.journalMeta?.mood?.takeIf { it.isNotBlank() }
            if (mood != null) "$mood · $wc words" else "Saved · $wc words"
        } else {
            "Not written"
        }
        gridRow2.addView(buildCompactMetricBox(
            title = "JOURNAL",
            value = diaryVal,
            accent = if (summary.hasDiary) theme.diaryViolet else theme.textSecondary,
            marginEndPx = 0,
            d = d
        ))
        card.addView(gridRow2)

        // 3. Quick Actions Row (Direct 1-Tap: + Task, Write Diary, Holiday)
        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12f) }
        }

        actionsRow.addView(buildQuickActionBtn(
            label = "+ Task",
            fill = theme.accent,
            textColor = theme.accentOnFill,
            stroke = null,
            marginEndPx = dp(6f),
            d = d
        ) {
            showQuickAddTaskDialog(selectedDateKey)
        })

        actionsRow.addView(buildQuickActionBtn(
            label = if (summary.hasDiary) "Edit Diary" else "Write Diary",
            fill = theme.surfaceAlt,
            textColor = theme.textPrimary,
            stroke = theme.border,
            marginEndPx = dp(6f),
            d = d
        ) {
            startActivity(Intent(this@EliteCalendarActivity, EliteJournalActivity::class.java).apply {
                putExtra(EliteJournalActivity.EXTRA_DATE_KEY, selectedDateKey)
                putExtra(EliteJournalActivity.EXTRA_INITIAL_MODE, EliteJournalActivity.MODE_WRITE)
            })
        })

        actionsRow.addView(buildQuickActionBtn(
            label = if (summary.isHoliday) "Unset Holiday" else "Holiday",
            fill = if (summary.isHoliday) ColorUtils.setAlphaComponent(theme.holidayAmber, 30) else theme.surfaceAlt,
            textColor = if (summary.isHoliday) theme.holidayAmber else theme.textPrimary,
            stroke = if (summary.isHoliday) theme.holidayAmber else theme.border,
            marginEndPx = 0,
            d = d
        ) {
            handleHolidayToggle(selectedDateKey, !summary.isHoliday)
        })
        card.addView(actionsRow)

        // 4. Inline Tasks Section for Selected Day
        val tasksHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2f), 0, dp(6f))
        }
        tasksHead.addView(TextView(this).apply {
            text = "TASKS (${summary.completedTasks}/${summary.totalTasks})"
            textSize = 10.5f
            setTypeface(null, Typeface.BOLD)
            letterSpacing = 0.05f
            setTextColor(theme.textMuted)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (onOpenTasksActivity != null) {
            tasksHead.addView(TextView(this).apply {
                text = "All Tasks ›"
                textSize = 11.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(theme.accent)
                setOnClickListener { onOpenTasksActivity?.invoke(selectedDateKey) }
            })
        }
        card.addView(tasksHead)

        if (summary.tasks.isEmpty()) {
            card.addView(TextView(this).apply {
                text = "No tasks scheduled for this date."
                textSize = 12f
                setTextColor(theme.textMuted)
                setPadding(0, dp(2f), 0, dp(8f))
            })
        } else {
            // Daily task completion progress bar
            val pct = (summary.completedTasks * 100) / summary.totalTasks
            card.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = pct
                progressTintList = ColorStateList.valueOf(theme.accent)
                progressBackgroundTintList = ColorStateList.valueOf(theme.surfaceAlt)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(4f)
                ).apply { bottomMargin = dp(6f) }
            })
            for (task in summary.tasks) {
                card.addView(buildInlineTaskRow(task, d))
            }
        }

        // 5. Diary Preview Snippet
        if (summary.hasDiary) {
            val diaryBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
                background = DisciplineTheme.cardDrawable(theme.surfaceAlt, theme.border, 10f, d)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8f) }
                setOnClickListener {
                    startActivity(Intent(this@EliteCalendarActivity, EliteJournalActivity::class.java).apply {
                        putExtra(EliteJournalActivity.EXTRA_DATE_KEY, selectedDateKey)
                        putExtra(EliteJournalActivity.EXTRA_INITIAL_MODE, EliteJournalActivity.MODE_WRITE)
                    })
                }
            }
            diaryBox.addView(TextView(this).apply {
                text = "DAILY JOURNAL"
                textSize = 10f
                setTypeface(null, Typeface.BOLD)
                letterSpacing = 0.05f
                setTextColor(theme.diaryViolet)
            })
            diaryBox.addView(TextView(this).apply {
                text = summary.diarySnippet
                textSize = 12.5f
                maxLines = 3
                setTextColor(theme.textPrimary)
                setPadding(0, dp(3f), 0, 0)
            })
            card.addView(diaryBox)
        }

        // 6. Activity & Workout Records (if any)
        if (summary.workoutLogs.isNotEmpty() || summary.awayAuditRecords.isNotEmpty()) {
            val recBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
                background = DisciplineTheme.cardDrawable(theme.surfaceAlt, theme.border, 10f, d)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8f) }
            }
            recBox.addView(TextView(this).apply {
                text = "ACTIVITY RECORDS"
                textSize = 10f
                setTypeface(null, Typeface.BOLD)
                letterSpacing = 0.05f
                setTextColor(theme.workoutCyan)
                setPadding(0, 0, 0, dp(4f))
            })
            for (log in summary.workoutLogs.take(4)) {
                val detail = if (log.reps > 0) "${log.exerciseType} · ${log.reps} reps"
                else "${log.exerciseType} · ${log.durationSeconds}s"
                recBox.addView(TextView(this).apply {
                    text = "• $detail"
                    textSize = 12f
                    setTextColor(theme.textPrimary)
                    setPadding(0, dp(1f), 0, dp(1f))
                })
            }
            for (audit in summary.awayAuditRecords.take(3)) {
                val mins = (audit.durationMillis / 60_000L).toInt().coerceAtLeast(1)
                recBox.addView(TextView(this).apply {
                    text = "• ${audit.details} (${mins}m)"
                    textSize = 12f
                    setTextColor(theme.textSecondary)
                    setPadding(0, dp(1f), 0, dp(1f))
                })
            }
            card.addView(recBox)
        }

        selectedDayOverviewContainer.addView(card)
    }

    private fun buildInlineTaskRow(task: DisciplineDataRepository.EnrichedTask, d: Float): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4f), dp(6f), dp(4f), dp(6f))
        }

        val checkColor = when {
            task.item.isDone -> theme.stepGreen
            task.isOverdue -> theme.overdueCoral
            task.meta.priority == DisciplineDataRepository.TaskPriority.HIGH -> theme.priorityHigh
            else -> theme.accent
        }
        val checkBox = TextView(this).apply {
            text = if (task.item.isDone) "✓" else ""
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(22f), dp(22f)).apply {
                marginEnd = dp(10f)
            }
            background = if (task.item.isDone) {
                DisciplineTheme.circleDrawable(checkColor)
            } else {
                DisciplineTheme.circleDrawable(Color.TRANSPARENT, checkColor, dp(1.8f))
            }
        }

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(TextView(this).apply {
            text = task.item.text
            textSize = 13f
            setTextColor(if (task.item.isDone) theme.textMuted else theme.textPrimary)
            if (task.item.isDone) {
                paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            }
        })

        val metaBits = ArrayList<String>()
        if (task.meta.priority != DisciplineDataRepository.TaskPriority.NORMAL) {
            metaBits.add(task.meta.priority.label)
        }
        task.meta.dueTimeMinutes?.let { metaBits.add(DisciplineDataRepository.formatTimeOfDay(it)) }
        if (task.meta.recurrence != DisciplineDataRepository.TaskRecurrence.NONE) {
            metaBits.add(task.meta.recurrence.label)
        }
        if (task.isOverdue) metaBits.add("Overdue")
        if (metaBits.isNotEmpty()) {
            textCol.addView(TextView(this).apply {
                text = metaBits.joinToString(" · ")
                textSize = 10.5f
                setTextColor(if (task.isOverdue) theme.overdueCoral else theme.textSecondary)
            })
        }

        row.addView(checkBox)
        row.addView(textCol)

        row.setOnClickListener {
            val newDone = !task.item.isDone
            lifecycleScope.launch {
                try {
                    val (_, nextKey) = withContext(Dispatchers.IO) {
                        DisciplineDataRepository.toggleTaskDone(this@EliteCalendarActivity, task.item.id, newDone)
                    }
                    if (isFinishing || isDestroyed) return@launch
                    if (nextKey != null) {
                        Toast.makeText(
                            this@EliteCalendarActivity,
                            "Next recurring task scheduled for ${DisciplineDataRepository.formatFriendlyDate(nextKey, this@EliteCalendarActivity)}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    loadMonthAndRender()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Throwable) {}
            }
        }
        row.setOnLongClickListener {
            onOpenTasksActivity?.invoke(task.item.dateKey) ?: run {
                confirmDeleteTask(task)
            }
            true
        }
        return row
    }

    private fun confirmDeleteTask(task: DisciplineDataRepository.EnrichedTask) {
        AlertDialog.Builder(this)
            .setTitle("Delete Task?")
            .setMessage(task.item.text)
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        DisciplineDataRepository.deleteTask(this@EliteCalendarActivity, task.item.id)
                    }
                    loadMonthAndRender()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showQuickAddTaskDialog(dateKey: String) {
        val d = resources.displayMetrics.density
        var selectedPriority = DisciplineDataRepository.TaskPriority.NORMAL
        var selectedDueMins: Int? = null
        var selectedRecurrence = DisciplineDataRepository.TaskRecurrence.NONE

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18f), dp(12f), dp(18f), dp(6f))
        }

        val etTitle = EditText(this).apply {
            hint = "Task title"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            isSingleLine = true
        }
        container.addView(etTitle)

        // Priority selector
        val priRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10f), 0, dp(6f))
        }
        val priButtons = ArrayList<Pair<DisciplineDataRepository.TaskPriority, TextView>>()
        fun refreshPri() {
            for ((p, btn) in priButtons) {
                val sel = p == selectedPriority
                btn.setTextColor(if (sel) theme.accentOnFill else theme.textSecondary)
                btn.background = if (sel) {
                    DisciplineTheme.pillDrawable(theme.accent, null, d)
                } else {
                    DisciplineTheme.pillDrawable(theme.surfaceAlt, theme.border, d)
                }
            }
        }
        for (p in listOf(
            DisciplineDataRepository.TaskPriority.LOW,
            DisciplineDataRepository.TaskPriority.NORMAL,
            DisciplineDataRepository.TaskPriority.HIGH
        )) {
            val btn = TextView(this).apply {
                text = p.label
                textSize = 11.5f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(5f)
                }
                setOnClickListener {
                    selectedPriority = p
                    refreshPri()
                }
            }
            priButtons.add(p to btn)
            priRow.addView(btn)
        }
        refreshPri()
        container.addView(priRow)

        // Optional Due Time + Recurrence row
        val optRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6f), 0, 0)
        }
        val tvDueBtn = TextView(this).apply {
            text = "Due time: Any"
            textSize = 11.5f
            setTextColor(theme.accent)
            setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
            background = DisciplineTheme.pillDrawable(theme.accentSoft, null, d)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8f) }
            setOnClickListener {
                val nowCal = Calendar.getInstance()
                TimePickerDialog(
                    this@EliteCalendarActivity,
                    { _, h, m ->
                        selectedDueMins = h * 60 + m
                        text = "Due: ${DisciplineDataRepository.formatTimeOfDay(selectedDueMins!!)}"
                    },
                    nowCal.get(Calendar.HOUR_OF_DAY),
                    nowCal.get(Calendar.MINUTE),
                    false
                ).show()
            }
        }
        val tvRecBtn = TextView(this).apply {
            text = "Repeat: One-time"
            textSize = 11.5f
            setTextColor(theme.textSecondary)
            setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
            background = DisciplineTheme.pillDrawable(theme.surfaceAlt, theme.border, d)
            setOnClickListener {
                val vals = DisciplineDataRepository.TaskRecurrence.values()
                val nextIdx = (vals.indexOf(selectedRecurrence) + 1) % vals.size
                selectedRecurrence = vals[nextIdx]
                text = "Repeat: ${selectedRecurrence.label}"
            }
        }
        optRow.addView(tvDueBtn)
        optRow.addView(tvRecBtn)
        container.addView(optRow)

        AlertDialog.Builder(this)
            .setTitle("Add Task · ${DisciplineDataRepository.formatFriendlyDate(dateKey, this)}")
            .setView(container)
            .setPositiveButton("Add Task") { _, _ ->
                val title = etTitle.text.toString().trim()
                if (title.isEmpty()) {
                    Toast.makeText(this, "Enter a task title", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            DisciplineDataRepository.createTask(
                                context = this@EliteCalendarActivity,
                                dateKey = dateKey,
                                title = title,
                                priority = selectedPriority,
                                dueTimeMinutes = selectedDueMins,
                                reminderEnabled = selectedDueMins != null,
                                recurrence = selectedRecurrence
                            )
                        }
                        if (!isFinishing && !isDestroyed) loadMonthAndRender()
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Throwable) {}
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun handleHolidayToggle(dateKey: String, enableHoliday: Boolean) {
        lifecycleScope.launch {
            try {
                val res = withContext(Dispatchers.IO) {
                    DisciplineDataRepository.toggleHoliday(this@EliteCalendarActivity, dateKey, enableHoliday)
                }
                if (isFinishing || isDestroyed) return@launch
                when (res) {
                    is DisciplineDataRepository.HolidayToggleResult.Updated -> {
                        Toast.makeText(this@EliteCalendarActivity, res.message, Toast.LENGTH_LONG).show()
                        loadMonthAndRender()
                    }
                    is DisciplineDataRepository.HolidayToggleResult.Blocked -> {
                        AlertDialog.Builder(this@EliteCalendarActivity)
                            .setTitle("24-Hour Advance Holiday Rule")
                            .setMessage(res.reason)
                            .setPositiveButton("Understood", null)
                            .show()
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {}
        }
    }

    private fun buildCompactMetricBox(
        title: String,
        value: String,
        accent: Int,
        marginEndPx: Int,
        d: Float
    ): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
            background = DisciplineTheme.cardDrawable(theme.surfaceAlt, theme.border, 10f, d)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = marginEndPx
            }
            addView(TextView(this@EliteCalendarActivity).apply {
                text = title
                textSize = 9.5f
                setTypeface(null, Typeface.BOLD)
                letterSpacing = 0.05f
                setTextColor(theme.textMuted)
            })
            addView(TextView(this@EliteCalendarActivity).apply {
                text = value
                textSize = 12.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(accent)
                setPadding(0, dp(2f), 0, 0)
            })
        }
    }

    private fun buildQuickActionBtn(
        label: String,
        fill: Int,
        textColor: Int,
        stroke: Int?,
        marginEndPx: Int,
        d: Float,
        onClick: () -> Unit
    ): View {
        return TextView(this).apply {
            text = label
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            gravity = Gravity.CENTER
            minHeight = dp(40f)
            setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
            background = DisciplineTheme.cardDrawable(fill, stroke ?: fill, 10f, d, if (stroke != null) 1f else 0f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = marginEndPx
            }
            setOnClickListener { onClick() }
        }
    }

    private fun createTinyPill(label: String, color: Int, d: Float): TextView {
        return TextView(this).apply {
            text = label
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            setTextColor(color)
            setPadding(dp(7f), dp(2f), dp(7f), dp(2f))
            background = DisciplineTheme.pillDrawable(ColorUtils.setAlphaComponent(color, 26), null, d)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(5f) }
        }
    }
}
