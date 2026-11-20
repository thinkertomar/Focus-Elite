package com.focusguardpro.app

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.focusguardpro.app.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Upgraded Discipline Calendar & Holiday Planner.
 * Features:
 * - Today quick-jump pill & animated month transitions
 * - 3-Stat Monthly Discipline Summary Strip (Active Days, Step Goal Days, Workout Days)
 * - 4 color-coded daily activity dots per day (Emerald=Steps Goal, Cyan=Workout, Amber=Holiday, Violet=Notes/Tasks)
 * - Selected-day quick inspection card opening CalendarDayEditorActivity
 */
class EliteCalendarActivity : AppCompatActivity() {

    private val displayedMonth: Calendar = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    private var selectedDateKey: String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    private lateinit var rootScroll: ScrollView
    private lateinit var tvMonthTitle: TextView
    private lateinit var tvStatActiveDays: TextView
    private lateinit var tvStatStepGoalDays: TextView
    private lateinit var tvStatWorkoutDays: TextView
    private lateinit var monthGrid: GridLayout
    private lateinit var tvSelectedDayHeader: TextView
    private lateinit var tvSelectedDayMetrics: TextView
    private lateinit var tvSelectedDayNote: TextView
    private lateinit var btnOpenEditor: TextView

    private data class DayActivitySummary(
        val dateKey: String,
        val dayOfMonth: Int,
        val dayStartMs: Long,
        val steps: Int,
        val workoutSets: Int,
        val isHoliday: Boolean,
        val isScheduledOffDay: Boolean,
        val hasNote: Boolean,
        val notePreview: String,
        val todoCount: Int
    ) {
        val metStepGoal: Boolean get() = steps >= STEP_GOAL_THRESHOLD
        val hasWorkout: Boolean get() = workoutSets > 0
        val hasNotesOrTasks: Boolean get() = hasNote || todoCount > 0
        val isActiveDay: Boolean get() = metStepGoal || hasWorkout || steps >= 2000
    }

    private var cachedMonthMap: Map<String, DayActivitySummary> = emptyMap()

    companion object {
        private const val STEP_GOAL_THRESHOLD = 6000
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FocusGuardApp.ensureRoomMainThreadQueries(this)
        buildLayout()
    }

    override fun onResume() {
        super.onResume()
        UiThemePrefs.applyWindowAndViewTreeTheme(this, rootScroll)
        loadAndRenderMonth(animate = false)
    }

    private fun buildLayout() {
        val palette = UiThemePrefs.getSelectedPalette(this)
        val isLight = UiThemePrefs.isWhiteBackgroundActive(this)
        val bgColor = UiThemePrefs.getScreenBackgroundColor(this)
        val textPrimary = UiThemePrefs.getScreenHeaderTextColor(this)
        val textSecondary = UiThemePrefs.getScreenSubHeaderTextColor(this)

        rootScroll = ScrollView(this).apply {
            setBackgroundColor(bgColor)
            isFillViewport = true
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(14f), dp(16f), dp(28f))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        rootScroll.addView(container)

        // 1. Header Row (Back + Title + Today Jump Pill)
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14f) }
        }

        val backBtn = TextView(this).apply {
            text = "←"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (isLight) Color.parseColor("#0284C7") else palette.secondaryColor)
            gravity = Gravity.CENTER
            minimumWidth = dp(44f)
            minimumHeight = dp(44f)
            setOnClickListener {
                SensoryFeedbackEngine.tap(this@EliteCalendarActivity, it)
                finish()
            }
        }
        headerRow.addView(backBtn)

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(4f)
            }
        }
        titleCol.addView(TextView(this).apply {
            text = "Discipline Calendar"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textPrimary)
        })
        titleCol.addView(TextView(this).apply {
            text = "Steps • Workouts • Holidays • Daily Notes"
            textSize = 11.5f
            setTextColor(textSecondary)
        })
        headerRow.addView(titleCol)

        val btnToday = TextView(this).apply {
            text = "Today"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(dp(14f), dp(7f), dp(14f), dp(7f))
            background = GradientDrawable().apply {
                cornerRadius = dp(18f).toFloat()
                setColor(palette.accentColor)
            }
            setOnClickListener { v ->
                SensoryFeedbackEngine.toggle(this@EliteCalendarActivity, v)
                val now = Calendar.getInstance()
                displayedMonth.set(now.get(Calendar.YEAR), now.get(Calendar.MONTH), 1)
                selectedDateKey = SimpleDateFormat("yyyyMMdd", Locale.US).format(now.time)
                loadAndRenderMonth(animate = true)
            }
        }
        headerRow.addView(btnToday)
        container.addView(headerRow)

        // 2. 3-Stat Monthly Discipline Summary Strip
        val statsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14f) }
        }

        fun makeStatTile(label: String, accentHex: String): Pair<LinearLayout, TextView> {
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = UiThemePrefs.createCardBackground(this@EliteCalendarActivity, cornerDp = 14f, isHero = false)
                setPadding(dp(8f), dp(11f), dp(8f), dp(11f))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(6f)
                }
            }
            val valTv = TextView(this).apply {
                text = "0"
                textSize = 19f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor(accentHex))
            }
            val lblTv = TextView(this).apply {
                text = label
                textSize = 10f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(textSecondary)
                setPadding(0, dp(2f), 0, 0)
            }
            tile.addView(valTv)
            tile.addView(lblTv)
            return tile to valTv
        }

        val (c1, v1) = makeStatTile("ACTIVE DAYS", "#34D399")
        val (c2, v2) = makeStatTile("STEP GOAL", "#10B981")
        val (c3, v3) = makeStatTile("WORKOUTS", "#38BDF8")
        (c3.layoutParams as LinearLayout.LayoutParams).marginEnd = 0
        tvStatActiveDays = v1
        tvStatStepGoalDays = v2
        tvStatWorkoutDays = v3
        statsRow.addView(c1)
        statsRow.addView(c2)
        statsRow.addView(c3)
        container.addView(statsRow)

        // 3. Calendar Grid Card
        val calCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UiThemePrefs.createCardBackground(this@EliteCalendarActivity, cornerDp = 18f, isHero = false)
            setPadding(dp(12f), dp(14f), dp(12f), dp(14f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12f) }
        }

        // Month Navigator Row
        val navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10f) }
        }

        val prevMonthBtn = TextView(this).apply {
            text = "‹"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(palette.secondaryColor)
            gravity = Gravity.CENTER
            minimumWidth = dp(44f)
            minimumHeight = dp(40f)
            setOnClickListener { v ->
                SensoryFeedbackEngine.tap(this@EliteCalendarActivity, v)
                displayedMonth.add(Calendar.MONTH, -1)
                loadAndRenderMonth(animate = true)
            }
        }
        tvMonthTitle = TextView(this).apply {
            textSize = 16.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textPrimary)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val nextMonthBtn = TextView(this).apply {
            text = "›"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(palette.secondaryColor)
            gravity = Gravity.CENTER
            minimumWidth = dp(44f)
            minimumHeight = dp(40f)
            setOnClickListener { v ->
                SensoryFeedbackEngine.tap(this@EliteCalendarActivity, v)
                displayedMonth.add(Calendar.MONTH, 1)
                loadAndRenderMonth(animate = true)
            }
        }
        navRow.addView(prevMonthBtn)
        navRow.addView(tvMonthTitle)
        navRow.addView(nextMonthBtn)
        calCard.addView(navRow)

        // Weekday Labels Row
        val weekdayRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6f) }
        }
        val dayLabels = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        dayLabels.forEachIndexed { idx, dName ->
            weekdayRow.addView(TextView(this).apply {
                text = dName
                textSize = 11f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(if (idx == 0) Color.parseColor("#F59E0B") else textSecondary)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
        calCard.addView(weekdayRow)

        // 7-Column Calendar Grid
        monthGrid = GridLayout(this).apply {
            columnCount = 7
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        calCard.addView(monthGrid)

        // Color-Coded Activity Dot Legend
        val legendRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(12f), 0, dp(2f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        fun addLegend(colorHex: String, label: String) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(10f) }
            }
            item.addView(View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor(colorHex))
                }
                layoutParams = LinearLayout.LayoutParams(dp(7f), dp(7f)).apply { marginEnd = dp(4f) }
            })
            item.addView(TextView(this).apply {
                text = label
                textSize = 10.5f
                setTextColor(textSecondary)
            })
            legendRow.addView(item)
        }
        addLegend("#10B981", "Steps")
        addLegend("#38BDF8", "Workout")
        addLegend("#F59E0B", "Holiday")
        addLegend("#A78BFA", "Notes/Tasks")
        calCard.addView(legendRow)

        container.addView(calCard)

        // 4. Selected Day Quick Preview & Editor Action Card
        val detailCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UiThemePrefs.createCardBackground(this@EliteCalendarActivity, cornerDp = 16f, isHero = true)
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        tvSelectedDayHeader = TextView(this).apply {
            textSize = 15.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        tvSelectedDayMetrics = TextView(this).apply {
            textSize = 12.5f
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(0, dp(4f), 0, dp(4f))
        }
        tvSelectedDayNote = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#CBD5E1"))
            setPadding(0, dp(2f), 0, dp(12f))
        }
        btnOpenEditor = TextView(this).apply {
            text = "Open Day Notes, Tasks & Holiday Toggle →"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(14f), dp(11f), dp(14f), dp(11f))
            background = GradientDrawable().apply {
                cornerRadius = dp(12f).toFloat()
                setColor(palette.accentColor)
            }
            setOnClickListener { v ->
                SensoryFeedbackEngine.toggle(this@EliteCalendarActivity, v)
                openDayEditor(selectedDateKey)
            }
        }
        detailCard.addView(tvSelectedDayHeader)
        detailCard.addView(tvSelectedDayMetrics)
        detailCard.addView(tvSelectedDayNote)
        detailCard.addView(btnOpenEditor)
        container.addView(detailCard)

        setContentView(rootScroll)
    }

    private fun openDayEditor(dateKey: String) {
        val intent = Intent(this, CalendarDayEditorActivity::class.java).apply {
            putExtra(CalendarDayEditorActivity.EXTRA_DATE_KEY, dateKey)
        }
        startActivity(intent)
    }

    private fun loadAndRenderMonth(animate: Boolean) {
        val monthFmt = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
        tvMonthTitle.text = monthFmt.format(displayedMonth.time)

        val year = displayedMonth.get(Calendar.YEAR)
        val month = displayedMonth.get(Calendar.MONTH)
        val daysInMonth = displayedMonth.getActualMaximum(Calendar.DAY_OF_MONTH)
        val firstDayCal = (displayedMonth.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1) }
        val leadingEmpty = firstDayCal.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY

        val keyFmt = SimpleDateFormat("yyyyMMdd", Locale.US)
        val firstKey = keyFmt.format(firstDayCal.time)
        val lastDayCal = (firstDayCal.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, daysInMonth) }
        val lastKey = keyFmt.format(lastDayCal.time)
        val todayMs = System.currentTimeMillis()

        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(this@EliteCalendarActivity)
            val diaryMap = try {
                db.systemDao().getDiaryEntriesBetween(firstKey, lastKey).associateBy { it.dateKey }
            } catch (_: Throwable) {
                emptyMap()
            }
            val todoCountMap = try {
                db.systemDao().getTodoCountsBetween(firstKey, lastKey).associate { it.dateKey to it.cnt }
            } catch (_: Throwable) {
                emptyMap()
            }
            val workoutSetsByKey = HashMap<String, Int>()
            try {
                val allLogs = db.exerciseDao().getAllLogs()
                for (log in allLogs) {
                    val dk = keyFmt.format(Date(log.timestamp))
                    if (dk in firstKey..lastKey) {
                        workoutSetsByKey[dk] = (workoutSetsByKey[dk] ?: 0) + 1
                    }
                }
            } catch (_: Throwable) {}

            val summaries = LinkedHashMap<String, DayActivitySummary>(daysInMonth)
            val iterCal = firstDayCal.clone() as Calendar
            for (day in 1..daysInMonth) {
                iterCal.set(year, month, day, 0, 0, 0)
                iterCal.set(Calendar.MILLISECOND, 0)
                val dayStart = iterCal.timeInMillis
                val dk = keyFmt.format(iterCal.time)
                val steps = if (dayStart <= todayMs + 86_400_000L) {
                    try {
                        ActivityTelemetryEngine.getHistoricalStepsForDay(this@EliteCalendarActivity, dayStart)
                    } catch (_: Throwable) {
                        0
                    }
                } else {
                    0
                }
                val diary = diaryMap[dk]
                val sets = workoutSetsByKey[dk] ?: 0
                val todos = todoCountMap[dk] ?: 0
                val isOffDay = FocusLayerManager.isScheduleEnabled(this@EliteCalendarActivity) &&
                    !FocusLayerManager.isDayIncludedInSchedule(this@EliteCalendarActivity, dayStart + 43_200_000L)

                summaries[dk] = DayActivitySummary(
                    dateKey = dk,
                    dayOfMonth = day,
                    dayStartMs = dayStart,
                    steps = steps,
                    workoutSets = sets,
                    isHoliday = diary?.isHoliday == true,
                    isScheduledOffDay = isOffDay,
                    hasNote = !diary?.note.isNullOrBlank(),
                    notePreview = diary?.note?.trim().orEmpty(),
                    todoCount = todos
                )
            }

            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                cachedMonthMap = summaries
                renderGrid(leadingEmpty, summaries, animate)
                updateSelectedDayPreview()
            }
        }
    }

    private fun renderGrid(
        leadingEmpty: Int,
        summaries: Map<String, DayActivitySummary>,
        animate: Boolean
    ) {
        val activeDays = summaries.values.count { it.isActiveDay }
        val stepGoalDays = summaries.values.count { it.metStepGoal }
        val workoutDays = summaries.values.count { it.hasWorkout }
        tvStatActiveDays.text = activeDays.toString()
        tvStatStepGoalDays.text = stepGoalDays.toString()
        tvStatWorkoutDays.text = workoutDays.toString()

        val todayKey = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val isLight = UiThemePrefs.isWhiteBackgroundActive(this)
        val palette = UiThemePrefs.getSelectedPalette(this)
        val textPrimary = if (isLight) Color.parseColor("#0F172A") else Color.parseColor("#F1F5F9")
        val mutedText = if (isLight) Color.parseColor("#94A3B8") else Color.parseColor("#64748B")

        monthGrid.removeAllViews()

        for (i in 0 until leadingEmpty) {
            monthGrid.addView(View(this).apply {
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = dp(52f)
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                }
            })
        }

        summaries.values.forEach { day ->
            val isToday = day.dateKey == todayKey
            val isSelected = day.dateKey == selectedDateKey

            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(2f), dp(5f), dp(2f), dp(5f))
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = dp(52f)
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(dp(2f), dp(3f), dp(2f), dp(3f))
                }
                background = GradientDrawable().apply {
                    cornerRadius = dp(11f).toFloat()
                    when {
                        isSelected -> {
                            setColor(if (isLight) Color.parseColor("#E0F2FE") else Color.parseColor("#142B3E"))
                            setStroke(dp(1.8f), palette.accentColor)
                        }
                        isToday -> {
                            setColor(if (isLight) Color.parseColor("#ECFDF5") else Color.parseColor("#0F2922"))
                            setStroke(dp(1.4f), Color.parseColor("#10B981"))
                        }
                        day.isHoliday -> {
                            setColor(if (isLight) Color.parseColor("#FEF3C7") else Color.parseColor("#2A1E0C"))
                            setStroke(dp(1f), Color.parseColor("#F59E0B"))
                        }
                        else -> {
                            setColor(if (isLight) Color.parseColor("#F8FAFC") else Color.parseColor("#111A27"))
                        }
                    }
                }
                setOnClickListener { v ->
                    SensoryFeedbackEngine.tap(this@EliteCalendarActivity, v)
                    if (selectedDateKey == day.dateKey) {
                        openDayEditor(day.dateKey)
                    } else {
                        selectedDateKey = day.dateKey
                        renderGrid(leadingEmpty, summaries, animate = false)
                        updateSelectedDayPreview()
                    }
                }
            }

            cell.addView(TextView(this).apply {
                text = day.dayOfMonth.toString()
                textSize = 13f
                setTypeface(typeface, if (isToday || isSelected) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(
                    when {
                        isToday -> Color.parseColor("#10B981")
                        day.isHoliday || day.isScheduledOffDay -> Color.parseColor("#F59E0B")
                        else -> textPrimary
                    }
                )
            })

            // Activity Dots Row (up to 4 compact color-coded indicators)
            val dotsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(8f)
                ).apply { topMargin = dp(4f) }
            }
            fun addDot(hex: String) {
                dotsRow.addView(View(this).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.parseColor(hex))
                    }
                    layoutParams = LinearLayout.LayoutParams(dp(5f), dp(5f)).apply {
                        marginStart = dp(1.5f)
                        marginEnd = dp(1.5f)
                    }
                })
            }
            if (day.metStepGoal) addDot("#10B981")
            if (day.hasWorkout) addDot("#38BDF8")
            if (day.isHoliday) addDot("#F59E0B")
            if (day.hasNotesOrTasks) addDot("#A78BFA")

            if (dotsRow.childCount == 0 && day.steps > 0) {
                // Subtle slate dot for light activity (< goal)
                dotsRow.addView(View(this).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(mutedText)
                    }
                    layoutParams = LinearLayout.LayoutParams(dp(4f), dp(4f))
                })
            }

            cell.addView(dotsRow)
            monthGrid.addView(cell)
        }

        if (animate) {
            SensoryFeedbackEngine.animateFadeSlideIn(monthGrid, offsetYDp = 8f)
        }
    }

    private fun updateSelectedDayPreview() {
        val summary = cachedMonthMap[selectedDateKey] ?: cachedMonthMap.values.firstOrNull() ?: return
        selectedDateKey = summary.dateKey

        val prettyFmt = SimpleDateFormat("EEEE, MMM d, yyyy", Locale.getDefault())
        val dateLabel = prettyFmt.format(Date(summary.dayStartMs))
        val holidayTag = when {
            summary.isHoliday -> "  •  🌴 Holiday"
            summary.isScheduledOffDay -> "  •  🌿 Scheduled Off-Day"
            else -> ""
        }
        tvSelectedDayHeader.text = "$dateLabel$holidayTag"

        val stepText = String.format(Locale.US, "%,d steps", summary.steps)
        val workoutText = if (summary.workoutSets > 0) "${summary.workoutSets} workout sets" else "0 workouts"
        val taskText = if (summary.todoCount > 0) "${summary.todoCount} tasks" else "No tasks"
        tvSelectedDayMetrics.text = "🚶 $stepText  •  🤸 $workoutText  •  📝 $taskText"

        tvSelectedDayNote.text = when {
            summary.notePreview.isNotBlank() -> "Note: ${summary.notePreview.take(140)}"
            summary.isScheduledOffDay -> "Off-Day: Focus Layer & all Web Shield rules pause automatically on this day. Tap below to add notes or tasks."
            summary.isHoliday -> "Calendar Holiday active (24h advance rule applies for Focus Layer & Web Shield exemption)."
            else -> "Tap any date twice (or button below) to log diary notes, tasks, or schedule a future Holiday ≥24h in advance."
        }
    }
}
