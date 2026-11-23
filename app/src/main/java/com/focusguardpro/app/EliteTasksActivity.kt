package com.focusguardpro.app

import android.app.DatePickerDialog
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
import android.widget.CheckBox
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * TickTick-inspired Personal Discipline Task Manager integrated directly with Room `TodoItem`,
 * Calendar dates, recurring schedules, priorities, optional due times/reminders,
 * overdue indicators, 1-tap rescheduling, and daily completion tracking.
 */
class EliteTasksActivity : AppCompatActivity(), SelfThemedDisciplineActivity {

    companion object {
        const val EXTRA_DATE_KEY = "EXTRA_DATE_KEY"
        private const val TAB_DAY = 0
        private const val TAB_OVERDUE = 1
        private const val TAB_UPCOMING = 2
        private const val TAB_COMPLETED = 3
    }

    private lateinit var theme: DisciplineTheme.Tokens
    private var selectedDateKey: String = ""
    private var todayKey: String = ""
    private var activeTab: Int = TAB_DAY

    private var allTasks: List<DisciplineDataRepository.EnrichedTask> = emptyList()

    // Views
    private lateinit var rootLayout: LinearLayout
    private lateinit var btnTabDay: TextView
    private lateinit var btnTabOverdue: TextView
    private lateinit var btnTabUpcoming: TextView
    private lateinit var btnTabCompleted: TextView
    private lateinit var dayNavCard: LinearLayout
    private lateinit var tvSelectedDate: TextView
    private lateinit var tvDailyProgressLabel: TextView
    private lateinit var pbDailyProgress: ProgressBar
    private lateinit var overdueBanner: LinearLayout
    private lateinit var tvOverdueBannerText: TextView
    private lateinit var tasksListContainer: LinearLayout

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        todayKey = DisciplineDataRepository.todayKey(this)
        selectedDateKey = intent?.getStringExtra(EXTRA_DATE_KEY)
            ?.takeIf { it.length == 8 }
            ?: todayKey

        theme = DisciplineTheme.resolve(this)

        buildUi()
        DisciplineTheme.applySystemBars(this, theme)
        loadTasks()
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
        loadTasks()
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

        // 1. Compact Header
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
            text = "Tasks"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(theme.textPrimary)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(4f), 0, 0, 0)
        })

        header.addView(TextView(this).apply {
            text = "Calendar"
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(theme.textPrimary)
            setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
            background = DisciplineTheme.pillDrawable(theme.surface, theme.border, d)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6f) }
            setOnClickListener {
                startActivity(Intent(this@EliteTasksActivity, EliteCalendarActivity::class.java).apply {
                    putExtra(EliteCalendarActivity.EXTRA_DATE_KEY, selectedDateKey)
                })
            }
        })

        header.addView(TextView(this).apply {
            text = "+ New"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(theme.accentOnFill)
            setPadding(dp(13f), dp(7f), dp(13f), dp(7f))
            background = DisciplineTheme.pillDrawable(theme.accent, null, d)
            setOnClickListener { showTaskEditorDialog(existing = null, defaultDateKey = selectedDateKey) }
        })
        rootLayout.addView(header)

        // 2. Segmented Filter Tabs (Day | Overdue | Upcoming | Completed)
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(3f), dp(3f), dp(3f), dp(3f))
            background = DisciplineTheme.cardDrawable(theme.surface, theme.border, 10f, d)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(14f), dp(2f), dp(14f), dp(8f)) }
        }
        btnTabDay = createTabBtn("Day") { switchTab(TAB_DAY) }
        btnTabOverdue = createTabBtn("Overdue") { switchTab(TAB_OVERDUE) }
        btnTabUpcoming = createTabBtn("Upcoming") { switchTab(TAB_UPCOMING) }
        btnTabCompleted = createTabBtn("Done") { switchTab(TAB_COMPLETED) }
        tabBar.addView(btnTabDay)
        tabBar.addView(btnTabOverdue)
        tabBar.addView(btnTabUpcoming)
        tabBar.addView(btnTabCompleted)
        rootLayout.addView(tabBar)

        // 3. Scrollable Content Area
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), 0, dp(14f), dp(28f))
        }

        // Day Navigator + Daily Completion Progress Card (shown in Day tab)
        dayNavCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            background = DisciplineTheme.cardDrawable(theme.surface, theme.border, 12f, d)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8f) }
        }

        val navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        navRow.addView(TextView(this).apply {
            text = "‹"
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setTextColor(theme.textPrimary)
            gravity = Gravity.CENTER
            minWidth = dp(38f)
            minHeight = dp(38f)
            contentDescription = "Previous day"
            setOnClickListener {
                selectedDateKey = DisciplineDataRepository.addDays(selectedDateKey, -1)
                renderCurrentTab()
            }
        })
        tvSelectedDate = TextView(this).apply {
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(theme.textPrimary)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { showDatePicker(selectedDateKey) { picked -> selectedDateKey = picked; renderCurrentTab() } }
        }
        navRow.addView(tvSelectedDate)
        navRow.addView(TextView(this).apply {
            text = "Today"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(theme.accent)
            setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
            background = DisciplineTheme.pillDrawable(theme.accentSoft, null, d)
            setOnClickListener {
                selectedDateKey = todayKey
                renderCurrentTab()
            }
        })
        navRow.addView(TextView(this).apply {
            text = "›"
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setTextColor(theme.textPrimary)
            gravity = Gravity.CENTER
            minWidth = dp(38f)
            minHeight = dp(38f)
            contentDescription = "Next day"
            setOnClickListener {
                selectedDateKey = DisciplineDataRepository.addDays(selectedDateKey, 1)
                renderCurrentTab()
            }
        })
        dayNavCard.addView(navRow)

        tvDailyProgressLabel = TextView(this).apply {
            textSize = 11.5f
            setTextColor(theme.textSecondary)
            setPadding(dp(2f), dp(6f), dp(2f), dp(4f))
        }
        dayNavCard.addView(tvDailyProgressLabel)

        pbDailyProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(theme.accent)
            progressBackgroundTintList = ColorStateList.valueOf(theme.surfaceAlt)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(5f)
            )
        }
        dayNavCard.addView(pbDailyProgress)
        body.addView(dayNavCard)

        // Overdue Quick-Reschedule Banner
        overdueBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(9f), dp(12f), dp(9f))
            background = DisciplineTheme.cardDrawable(
                ColorUtils.setAlphaComponent(theme.overdueCoral, 22),
                ColorUtils.setAlphaComponent(theme.overdueCoral, 100),
                11f,
                d
            )
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8f) }
        }
        tvOverdueBannerText = TextView(this).apply {
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(theme.overdueCoral)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        overdueBanner.addView(tvOverdueBannerText)
        overdueBanner.addView(TextView(this).apply {
            text = "Move All to Today"
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
            background = DisciplineTheme.pillDrawable(theme.overdueCoral, null, d)
            setOnClickListener { rescheduleAllOverdue() }
        })
        body.addView(overdueBanner)

        // Task list container
        tasksListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        body.addView(tasksListContainer)

        scroll.addView(body)
        rootLayout.addView(scroll)
        setContentView(rootLayout)
        updateTabStyles()
    }

    private fun createTabBtn(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(6f), dp(7f), dp(6f), dp(7f))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onClick() }
        }
    }

    private fun switchTab(tab: Int) {
        activeTab = tab
        updateTabStyles()
        renderCurrentTab()
    }

    private fun updateTabStyles() {
        val d = resources.displayMetrics.density
        val overdueCount = allTasks.count { it.isOverdue && !it.item.isDone }
        btnTabOverdue.text = if (overdueCount > 0) "Overdue ($overdueCount)" else "Overdue"

        val list = listOf(
            TAB_DAY to btnTabDay,
            TAB_OVERDUE to btnTabOverdue,
            TAB_UPCOMING to btnTabUpcoming,
            TAB_COMPLETED to btnTabCompleted
        )
        for ((tab, btn) in list) {
            val active = activeTab == tab
            val fill = if (tab == TAB_OVERDUE && overdueCount > 0 && active) theme.overdueCoral else theme.accent
            btn.background = if (active) DisciplineTheme.cardDrawable(fill, fill, 8f, d, 0f) else null
            btn.setTextColor(
                when {
                    active && tab == TAB_OVERDUE && overdueCount > 0 -> Color.WHITE
                    active -> theme.accentOnFill
                    tab == TAB_OVERDUE && overdueCount > 0 -> theme.overdueCoral
                    else -> theme.textSecondary
                }
            )
        }
    }

    private fun loadTasks() {
        lifecycleScope.launch {
            val loaded = try {
                withContext(Dispatchers.IO) {
                    DisciplineDataRepository.getAllEnrichedTasks(this@EliteTasksActivity)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                emptyList()
            }
            if (isFinishing || isDestroyed) return@launch
            allTasks = loaded
            updateTabStyles()
            renderCurrentTab()
        }
    }

    private fun renderCurrentTab() {
        val d = resources.displayMetrics.density
        tasksListContainer.removeAllViews()

        val overduePast = allTasks.filter { it.isOverdue && !it.item.isDone && it.item.dateKey < todayKey }
        val overdueAll = allTasks.filter { it.isOverdue && !it.item.isDone }

        dayNavCard.visibility = if (activeTab == TAB_DAY) View.VISIBLE else View.GONE
        if (overduePast.isNotEmpty() && (activeTab == TAB_DAY || activeTab == TAB_OVERDUE)) {
            overdueBanner.visibility = View.VISIBLE
            tvOverdueBannerText.text = "${overduePast.size} incomplete task${if (overduePast.size == 1) "" else "s"} from earlier days"
        } else {
            overdueBanner.visibility = View.GONE
        }

        val listToRender: List<DisciplineDataRepository.EnrichedTask> = when (activeTab) {
            TAB_DAY -> {
                tvSelectedDate.text = DisciplineDataRepository.formatFriendlyDate(selectedDateKey, this)
                val dayTasks = allTasks.filter { it.item.dateKey == selectedDateKey }
                val total = dayTasks.size
                val done = dayTasks.count { it.item.isDone }
                val pct = if (total > 0) (done * 100) / total else 0
                tvDailyProgressLabel.text = if (total > 0) {
                    "$done of $total completed · $pct% daily completion"
                } else {
                    "No tasks scheduled for this date"
                }
                pbDailyProgress.progress = pct
                dayTasks
            }
            TAB_OVERDUE -> overdueAll.sortedBy { it.item.dateKey }
            TAB_UPCOMING -> allTasks.filter { !it.item.isDone && it.item.dateKey >= todayKey }
                .sortedWith(compareBy<DisciplineDataRepository.EnrichedTask> { it.item.dateKey }.thenBy { it.meta.priority.sortRank })
            else -> allTasks.filter { it.item.isDone }.sortedByDescending { it.item.dateKey }
        }

        if (listToRender.isEmpty()) {
            val emptyTitle = when (activeTab) {
                TAB_DAY -> "No tasks for ${DisciplineDataRepository.formatFriendlyDate(selectedDateKey, this)}"
                TAB_OVERDUE -> "All caught up — no overdue tasks"
                TAB_UPCOMING -> "No upcoming tasks scheduled"
                else -> "No completed tasks yet"
            }
            tasksListContainer.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(20f), dp(34f), dp(20f), dp(34f))
                background = DisciplineTheme.cardDrawable(theme.surface, theme.border, 12f, d)
                addView(TextView(this@EliteTasksActivity).apply {
                    text = emptyTitle
                    textSize = 14f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(theme.textPrimary)
                    gravity = Gravity.CENTER
                })
                if (activeTab == TAB_DAY || activeTab == TAB_UPCOMING) {
                    addView(TextView(this@EliteTasksActivity).apply {
                        text = "+ Add a Task"
                        textSize = 12.5f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(theme.accent)
                        setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
                        background = DisciplineTheme.pillDrawable(theme.accentSoft, null, d)
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = dp(10f) }
                        setOnClickListener { showTaskEditorDialog(null, selectedDateKey) }
                    })
                }
            })
            return
        }

        var lastGroupDate = ""
        for (task in listToRender) {
            if (activeTab != TAB_DAY && task.item.dateKey != lastGroupDate) {
                lastGroupDate = task.item.dateKey
                tasksListContainer.addView(TextView(this).apply {
                    text = DisciplineDataRepository.formatFriendlyDate(lastGroupDate, this@EliteTasksActivity)
                    textSize = 11f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(if (lastGroupDate < todayKey && activeTab == TAB_OVERDUE) theme.overdueCoral else theme.textMuted)
                    setPadding(dp(2f), dp(8f), 0, dp(4f))
                })
            }
            tasksListContainer.addView(buildTaskCard(task, d))
        }
    }

    private fun buildTaskCard(task: DisciplineDataRepository.EnrichedTask, d: Float): View {
        val priColor = when (task.meta.priority) {
            DisciplineDataRepository.TaskPriority.HIGH -> theme.priorityHigh
            DisciplineDataRepository.TaskPriority.NORMAL -> theme.priorityNormal
            DisciplineDataRepository.TaskPriority.LOW -> theme.priorityLow
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            background = DisciplineTheme.cardDrawable(
                theme.surface,
                if (task.isOverdue) ColorUtils.setAlphaComponent(theme.overdueCoral, 120) else theme.border,
                12f,
                d
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(7f) }
        }

        val mainRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // Completion checkbox
        val checkColor = when {
            task.item.isDone -> theme.stepGreen
            task.isOverdue -> theme.overdueCoral
            else -> priColor
        }
        val checkBtn = TextView(this).apply {
            text = if (task.item.isDone) "✓" else ""
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(26f), dp(26f)).apply {
                marginEnd = dp(10f)
            }
            background = if (task.item.isDone) {
                DisciplineTheme.circleDrawable(checkColor)
            } else {
                DisciplineTheme.circleDrawable(Color.TRANSPARENT, checkColor, dp(2f))
            }
            contentDescription = if (task.item.isDone) "Mark task incomplete" else "Mark task complete"
            setOnClickListener { toggleTaskCompletion(task) }
        }
        mainRow.addView(checkBtn)

        // Title + Metadata pills
        val infoCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { showTaskEditorDialog(existing = task, defaultDateKey = task.item.dateKey) }
        }
        infoCol.addView(TextView(this).apply {
            text = task.item.text
            textSize = 14f
            setTypeface(null, if (task.item.isDone) Typeface.NORMAL else Typeface.BOLD)
            setTextColor(if (task.item.isDone) theme.textMuted else theme.textPrimary)
            if (task.item.isDone) {
                paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            }
        })

        val metaRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4f), 0, 0)
        }
        metaRow.addView(createMetaBadge(task.meta.priority.label, priColor, d))
        if (task.isOverdue) {
            metaRow.addView(createMetaBadge("Overdue", theme.overdueCoral, d))
        }
        task.meta.dueTimeMinutes?.let { mins ->
            val timeLabel = DisciplineDataRepository.formatTimeOfDay(mins) + if (task.meta.reminderEnabled) " · Bell" else ""
            metaRow.addView(createMetaBadge(timeLabel, theme.taskBlue, d))
        }
        if (task.meta.recurrence != DisciplineDataRepository.TaskRecurrence.NONE) {
            metaRow.addView(createMetaBadge(task.meta.recurrence.label, theme.diaryViolet, d))
        }
        infoCol.addView(metaRow)
        mainRow.addView(infoCol)

        // Quick reschedule / edit actions
        if (!task.item.isDone) {
            mainRow.addView(TextView(this).apply {
                text = "Move"
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                setTextColor(theme.accent)
                setPadding(dp(9f), dp(6f), dp(9f), dp(6f))
                background = DisciplineTheme.pillDrawable(theme.accentSoft, null, d)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(6f) }
                setOnClickListener { showRescheduleSheet(task) }
            })
        }

        card.addView(mainRow)
        return card
    }

    private fun createMetaBadge(label: String, color: Int, d: Float): TextView {
        return TextView(this).apply {
            text = label
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            setTextColor(color)
            setPadding(dp(6f), dp(2f), dp(6f), dp(2f))
            background = DisciplineTheme.pillDrawable(ColorUtils.setAlphaComponent(color, 26), null, d)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(5f) }
        }
    }

    private fun toggleTaskCompletion(task: DisciplineDataRepository.EnrichedTask) {
        val newDone = !task.item.isDone
        lifecycleScope.launch {
            try {
                val (_, nextKey) = withContext(Dispatchers.IO) {
                    DisciplineDataRepository.toggleTaskDone(this@EliteTasksActivity, task.item.id, newDone)
                }
                if (isFinishing || isDestroyed) return@launch
                if (nextKey != null) {
                    Toast.makeText(
                        this@EliteTasksActivity,
                        "Recurring task scheduled for ${DisciplineDataRepository.formatFriendlyDate(nextKey, this@EliteTasksActivity)}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                loadTasks()
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {}
        }
    }

    private fun showRescheduleSheet(task: DisciplineDataRepository.EnrichedTask) {
        val tomorrow = DisciplineDataRepository.addDays(todayKey, 1)
        val nextWeek = DisciplineDataRepository.addDays(todayKey, 7)
        val options = arrayOf(
            "Today (${DisciplineDataRepository.formatFriendlyDate(todayKey, this)})",
            "Tomorrow (${DisciplineDataRepository.formatFriendlyDate(tomorrow, this)})",
            "Next Week (${DisciplineDataRepository.formatFriendlyDate(nextWeek, this)})",
            "Pick a specific date..."
        )
        AlertDialog.Builder(this)
            .setTitle("Reschedule \"${task.item.text}\"")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> performReschedule(task.item.id, todayKey)
                    1 -> performReschedule(task.item.id, tomorrow)
                    2 -> performReschedule(task.item.id, nextWeek)
                    3 -> showDatePicker(task.item.dateKey) { picked ->
                        performReschedule(task.item.id, picked)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performReschedule(taskId: Long, targetDateKey: String) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                DisciplineDataRepository.rescheduleTask(this@EliteTasksActivity, taskId, targetDateKey)
            }
            Toast.makeText(
                this@EliteTasksActivity,
                "Rescheduled to ${DisciplineDataRepository.formatFriendlyDate(targetDateKey, this@EliteTasksActivity)}",
                Toast.LENGTH_SHORT
            ).show()
            loadTasks()
        }
    }

    private fun rescheduleAllOverdue() {
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                DisciplineDataRepository.rescheduleAllOverdueToToday(this@EliteTasksActivity)
            }
            Toast.makeText(
                this@EliteTasksActivity,
                "Moved $count overdue task${if (count == 1) "" else "s"} to Today",
                Toast.LENGTH_SHORT
            ).show()
            loadTasks()
        }
    }

    private fun showDatePicker(initialDateKey: String, onPicked: (String) -> Unit) {
        val cal = DisciplineDataRepository.parseDateKeyToCalendar(initialDateKey)
        DatePickerDialog(
            this,
            { _, y, m, day ->
                val chosen = Calendar.getInstance().apply {
                    set(Calendar.YEAR, y)
                    set(Calendar.MONTH, m)
                    set(Calendar.DAY_OF_MONTH, day)
                }
                onPicked(DisciplineDataRepository.dateKeyFor(chosen))
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun showTaskEditorDialog(
        existing: DisciplineDataRepository.EnrichedTask?,
        defaultDateKey: String
    ) {
        val d = resources.displayMetrics.density
        var chosenDateKey = existing?.item?.dateKey ?: defaultDateKey
        var chosenPriority = existing?.meta?.priority ?: DisciplineDataRepository.TaskPriority.NORMAL
        var chosenDueMins: Int? = existing?.meta?.dueTimeMinutes
        var chosenReminder = existing?.meta?.reminderEnabled ?: false
        var chosenRecurrence = existing?.meta?.recurrence ?: DisciplineDataRepository.TaskRecurrence.NONE

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18f), dp(12f), dp(18f), dp(6f))
        }

        val etTitle = EditText(this).apply {
            hint = "What needs to get done?"
            setText(existing?.item?.text ?: "")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            isSingleLine = true
        }
        container.addView(etTitle)

        // Date Picker Row
        val tvDateBtn = TextView(this).apply {
            text = "Date: ${DisciplineDataRepository.formatFriendlyDate(chosenDateKey, this@EliteTasksActivity)}"
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(theme.accent)
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
            background = DisciplineTheme.cardDrawable(theme.accentSoft, theme.accent, 9f, d)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10f) }
            setOnClickListener {
                showDatePicker(chosenDateKey) { picked ->
                    chosenDateKey = picked
                    text = "Date: ${DisciplineDataRepository.formatFriendlyDate(chosenDateKey, this@EliteTasksActivity)}"
                }
            }
        }
        container.addView(tvDateBtn)

        // Priority selector
        container.addView(TextView(this).apply {
            text = "PRIORITY"
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            setTextColor(theme.textMuted)
            setPadding(0, dp(10f), 0, dp(4f))
        })
        val priRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val priViews = ArrayList<Pair<DisciplineDataRepository.TaskPriority, TextView>>()
        fun refreshPriorityUi() {
            for ((p, v) in priViews) {
                val sel = p == chosenPriority
                v.setTextColor(if (sel) theme.accentOnFill else theme.textSecondary)
                v.background = if (sel) {
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
            val v = TextView(this).apply {
                text = p.label
                textSize = 11.5f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(5f)
                }
                setOnClickListener { chosenPriority = p; refreshPriorityUi() }
            }
            priViews.add(p to v)
            priRow.addView(v)
        }
        refreshPriorityUi()
        container.addView(priRow)

        // Due Time + Clear + Reminder checkbox
        val timeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10f), 0, 0)
        }
        val cbReminder = CheckBox(this).apply {
            text = "Remind me"
            isChecked = chosenReminder
            isEnabled = chosenDueMins != null
            setOnCheckedChangeListener { _, checked -> chosenReminder = checked }
        }
        val tvDueBtn = TextView(this).apply {
            text = chosenDueMins?.let { "Due: ${DisciplineDataRepository.formatTimeOfDay(it)}" } ?: "Set Due Time"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(theme.accent)
            setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
            background = DisciplineTheme.pillDrawable(theme.accentSoft, null, d)
            setOnClickListener {
                val initH = chosenDueMins?.div(60) ?: Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                val initM = chosenDueMins?.rem(60) ?: Calendar.getInstance().get(Calendar.MINUTE)
                TimePickerDialog(
                    this@EliteTasksActivity,
                    { _, h, m ->
                        chosenDueMins = h * 60 + m
                        text = "Due: ${DisciplineDataRepository.formatTimeOfDay(chosenDueMins!!)}"
                        cbReminder.isEnabled = true
                        if (!chosenReminder) {
                            chosenReminder = true
                            cbReminder.isChecked = true
                        }
                    },
                    initH, initM, false
                ).show()
            }
        }
        val tvClearTime = TextView(this).apply {
            text = "Clear"
            textSize = 11.5f
            setTextColor(theme.textMuted)
            setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
            setOnClickListener {
                chosenDueMins = null
                chosenReminder = false
                tvDueBtn.text = "Set Due Time"
                cbReminder.isChecked = false
                cbReminder.isEnabled = false
            }
        }
        timeRow.addView(tvDueBtn)
        timeRow.addView(tvClearTime)
        container.addView(timeRow)
        container.addView(cbReminder)

        // Recurrence selector
        container.addView(TextView(this).apply {
            text = "RECURRENCE"
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            setTextColor(theme.textMuted)
            setPadding(0, dp(8f), 0, dp(4f))
        })
        val recRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val recViews = ArrayList<Pair<DisciplineDataRepository.TaskRecurrence, TextView>>()
        fun refreshRecUi() {
            for ((r, v) in recViews) {
                val sel = r == chosenRecurrence
                v.setTextColor(if (sel) theme.accentOnFill else theme.textSecondary)
                v.background = if (sel) {
                    DisciplineTheme.pillDrawable(theme.accent, null, d)
                } else {
                    DisciplineTheme.pillDrawable(theme.surfaceAlt, theme.border, d)
                }
            }
        }
        for (r in DisciplineDataRepository.TaskRecurrence.values()) {
            val v = TextView(this).apply {
                text = r.label
                textSize = 10.5f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(4f)
                }
                setOnClickListener { chosenRecurrence = r; refreshRecUi() }
            }
            recViews.add(r to v)
            recRow.addView(v)
        }
        refreshRecUi()
        container.addView(recRow)

        val builder = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "New Task" else "Edit Task")
            .setView(container)
            .setPositiveButton(if (existing == null) "Create" else "Save") { _, _ ->
                val title = etTitle.text.toString().trim()
                if (title.isEmpty()) {
                    Toast.makeText(this, "Enter a task title", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        if (existing == null) {
                            DisciplineDataRepository.createTask(
                                context = this@EliteTasksActivity,
                                dateKey = chosenDateKey,
                                title = title,
                                priority = chosenPriority,
                                dueTimeMinutes = chosenDueMins,
                                reminderEnabled = chosenReminder,
                                recurrence = chosenRecurrence
                            )
                        } else {
                            DisciplineDataRepository.updateTask(
                                context = this@EliteTasksActivity,
                                taskId = existing.item.id,
                                dateKey = chosenDateKey,
                                title = title,
                                isDone = existing.item.isDone,
                                priority = chosenPriority,
                                dueTimeMinutes = chosenDueMins,
                                reminderEnabled = chosenReminder,
                                recurrence = chosenRecurrence
                            )
                        }
                    }
                    loadTasks()
                }
            }
            .setNegativeButton("Cancel", null)

        if (existing != null) {
            builder.setNeutralButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        DisciplineDataRepository.deleteTask(this@EliteTasksActivity, existing.item.id)
                    }
                    Toast.makeText(this@EliteTasksActivity, "Task deleted", Toast.LENGTH_SHORT).show()
                    loadTasks()
                }
            }
        }
        builder.show()
    }
}
