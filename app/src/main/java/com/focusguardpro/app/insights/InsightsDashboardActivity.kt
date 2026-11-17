package com.focusguardpro.app.insights

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.focusguardpro.app.DailyRollupManager
import com.focusguardpro.app.GoalPrefs
import com.focusguardpro.app.R
import com.focusguardpro.app.RingProgressView
import com.focusguardpro.app.StepHistoryManager
import com.focusguardpro.app.StepPrefs
import com.focusguardpro.app.UiThemePrefs
import com.focusguardpro.app.UsageStatsHelper
import com.focusguardpro.app.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class InsightsDashboardActivity : AppCompatActivity() {

    private lateinit var tvSynergyScore: TextView
    private lateinit var tvAvgFocus: TextView
    private lateinit var tvAvgSteps: TextView
    private lateinit var adapter: InsightDayAdapter
    private var loadJob: Job? = null
    private var currentDays: Int = 7

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_insights_dashboard)

        tvSynergyScore = findViewById(R.id.tv_synergy_score)
        tvAvgFocus = findViewById(R.id.tv_avg_focus)
        tvAvgSteps = findViewById(R.id.tv_avg_steps)

        val rv = findViewById<RecyclerView>(R.id.rv_insights_breakdown)
        rv.layoutManager = LinearLayoutManager(this)
        adapter = InsightDayAdapter().apply {
            onItemClick = { day -> showDayReportDialog(day) }
        }
        rv.adapter = adapter

        val rg = findViewById<RadioGroup>(R.id.rg_time_range)
        rg.setOnCheckedChangeListener { _, checkedId ->
            currentDays = if (checkedId == R.id.rb_30_days) 30 else 7
            loadInsights(currentDays)
        }

        applyThemeStyling()
        loadInsights(currentDays)
    }

    override fun onResume() {
        super.onResume()
        applyThemeStyling()
    }

    private fun applyThemeStyling() {
        val root = (findViewById<android.view.View>(android.R.id.content) as? ViewGroup)?.getChildAt(0)
        root?.setBackgroundColor(UiThemePrefs.getScreenBackgroundColor(this))
        UiThemePrefs.applyWindowAndViewTreeTheme(this, root)
    }

    private fun loadInsights(days: Int) {
        loadJob?.cancel()

        // Fast instant frame-0 binding so "--/100" never hangs while IO queries complete
        val cachedSnap = UsageStatsHelper.getCachedTodaySnapshot(this)
        if (cachedSnap != null && adapter.itemCount == 0) {
            val todayMins = (cachedSnap.totalScreenTimeMs / 60_000L).toInt().coerceAtLeast(0)
            val screenScore = (40 - (todayMins / 60) * 8).coerceIn(0, 40)
            val quickScore = (screenScore + 25).coerceIn(0, 100)
            tvSynergyScore.text = "$quickScore/100"
            tvAvgFocus.text = "${todayMins / 60}h ${todayMins % 60}m"
            tvAvgSteps.text = "Syncing..."
        }

        // Kick off non-blocking rollup in background
        DailyRollupManager.rollupIfNeeded(applicationContext)

        loadJob = lifecycleScope.launch(Dispatchers.IO) {
            val appCtx = applicationContext
            val keyFmt = SimpleDateFormat("yyyyMMdd", Locale.US)
            val displayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

            val stepGoal = try {
                GoalPrefs.getStepGoal(appCtx).coerceAtLeast(1000)
            } catch (_: Throwable) {
                6000
            }

            val todaySteps = try {
                StepPrefs.getDailySteps(appCtx).coerceAtLeast(0)
            } catch (_: Throwable) {
                0
            }

            val exerciseLogs = try {
                AppDatabase.getDatabase(appCtx).exerciseDao().getAllLogs()
            } catch (_: Throwable) {
                emptyList()
            }

            val snapshotMinutesByDay = HashMap<String, Int>()
            try {
                val startCal = Calendar.getInstance().apply {
                    add(Calendar.DAY_OF_YEAR, -(days - 1))
                }
                val startKey = keyFmt.format(startCal.time)
                val endKey = keyFmt.format(Date())
                val rows = AppDatabase.getDatabase(appCtx)
                    .systemDao()
                    .getDailyUsageSnapshotsBetween(startKey, endKey)
                for (row in rows) {
                    snapshotMinutesByDay[row.dayKey] = (snapshotMinutesByDay[row.dayKey] ?: 0) + row.minutes
                }
            } catch (_: Throwable) {}

            val dayList = ArrayList<DayInsight>(days)
            var totalScreenMins = 0
            var totalSteps = 0
            var totalScore = 0
            var countedDays = 0

            val cal = Calendar.getInstance()
            val nowMs = System.currentTimeMillis()

            for (i in 0 until days) {
                cal.timeInMillis = nowMs
                cal.add(Calendar.DAY_OF_YEAR, -i)
                val dateStr = displayFmt.format(cal.time)
                val dayKey = keyFmt.format(cal.time)

                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                val dStart = cal.timeInMillis
                val dEnd = if (i == 0) nowMs else dStart + 86_400_000L - 1L

                val screenTimeMins = if (i == 0) {
                    (UsageStatsHelper.getTodayScreenTimeMillis(appCtx) / 60_000L).toInt().coerceIn(0, 1440)
                } else {
                    val fromHist = (UsageStatsHelper.getHistoricalDayScreenTimeMillis(appCtx, dStart, dEnd) / 60_000L).toInt()
                    if (fromHist > 0) {
                        fromHist.coerceIn(0, 1080)
                    } else {
                        (snapshotMinutesByDay[dayKey] ?: 0).coerceIn(0, 1080)
                    }
                }

                val steps = if (i == 0) {
                    todaySteps
                } else {
                    try {
                        StepHistoryManager.getStepsForDay(appCtx, dayKey) ?: 0
                    } catch (_: Throwable) {
                        0
                    }
                }

                val exerciseSets = exerciseLogs.count { log ->
                    log.timestamp in dStart..dEnd
                }

                val screenScore = (40 - (screenTimeMins / 60) * 8).coerceIn(0, 40)
                val stepScore = ((steps.toFloat() / stepGoal.toFloat()) * 35f).toInt().coerceIn(0, 35)
                val exerciseScore = (exerciseSets * 5).coerceIn(0, 25)
                val dayScore = (screenScore + stepScore + exerciseScore).coerceIn(0, 100)

                totalScreenMins += screenTimeMins
                totalSteps += steps
                totalScore += dayScore
                countedDays++

                dayList.add(
                    DayInsight(
                        dateStr = dateStr,
                        screenTimeMinutes = screenTimeMins,
                        steps = steps,
                        exerciseSets = exerciseSets,
                        score = dayScore,
                        screenScore = screenScore,
                        stepScore = stepScore,
                        exerciseScore = exerciseScore,
                        stepGoal = stepGoal
                    )
                )
            }

            val denom = countedDays.coerceAtLeast(1)
            val avgScore = (totalScore / denom).coerceIn(0, 100)
            val avgMins = (totalScreenMins / denom).coerceAtLeast(0)
            val avgDailySteps = (totalSteps / denom).coerceAtLeast(0)

            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                tvSynergyScore.text = "$avgScore/100"
                tvAvgFocus.text = "${avgMins / 60}h ${avgMins % 60}m"
                tvAvgSteps.text = String.format(Locale.getDefault(), "%,d", avgDailySteps)
                adapter.submitList(dayList)
                applyThemeStyling()
            }
        }
    }

    private fun gradeFor(score: Int): String = when {
        score >= 90 -> "A"
        score >= 80 -> "B"
        score >= 70 -> "C"
        score >= 60 -> "D"
        else -> "F"
    }

    private fun gradeColor(score: Int): Int {
        val colorRes = when {
            score >= 80 -> R.color.success_green
            score >= 60 -> R.color.improve_blue
            else -> R.color.distract_red
        }
        return ContextCompat.getColor(this, colorRes)
    }

    private fun buildTips(day: DayInsight): List<String> {
        val tips = ArrayList<String>()
        if (day.screenScore < 24) {
            val h = day.screenTimeMinutes / 60
            val m = day.screenTimeMinutes % 60
            tips.add("Screen time reached ${h}h ${m}m. Enable Focus Layer or tighten 30m caps on top distracting apps.")
        }
        if (day.stepScore < 21) {
            val remaining = (day.stepGoal - day.steps).coerceAtLeast(0)
            tips.add("You were ${String.format(Locale.getDefault(), "%,d", remaining)} steps shy of your ${String.format(Locale.getDefault(), "%,d", day.stepGoal)} daily step target.")
        }
        if (day.exerciseScore < 15) {
            tips.add("Log at least 3 calisthenics or workout sets to unlock full physical synergy points.")
        }
        if (tips.isEmpty()) {
            tips.add("Outstanding discipline across screen hygiene, movement, and exercise!")
        }
        return tips
    }

    private fun showDayReportDialog(day: DayInsight) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_day_report_card, null)
        val grade = gradeFor(day.score)
        val gColor = gradeColor(day.score)

        view.findViewById<TextView>(R.id.tvReportDate)?.text = day.dateStr
        view.findViewById<TextView>(R.id.tvReportGrade)?.apply {
            text = "Grade $grade"
            setTextColor(gColor)
        }
        view.findViewById<TextView>(R.id.tvReportScore)?.apply {
            text = day.score.toString()
            setTextColor(gColor)
        }
        view.findViewById<RingProgressView>(R.id.reportScoreRing)?.apply {
            progress = day.score / 100f
        }

        view.findViewById<TextView>(R.id.tvScreenScoreLabel)?.text = "${day.screenScore} / 40"
        view.findViewById<ProgressBar>(R.id.pbScreenScore)?.progress = (day.screenScore * 100) / 40

        view.findViewById<TextView>(R.id.tvStepScoreLabel)?.text = "${day.stepScore} / 35"
        view.findViewById<ProgressBar>(R.id.pbStepScore)?.progress = (day.stepScore * 100) / 35

        view.findViewById<TextView>(R.id.tvExerciseScoreLabel)?.text = "${day.exerciseScore} / 25"
        view.findViewById<ProgressBar>(R.id.pbExerciseScore)?.progress = (day.exerciseScore * 100) / 25

        val h = day.screenTimeMinutes / 60
        val m = day.screenTimeMinutes % 60
        val rawStats = StringBuilder()
            .append("• Screen Time: ${h}h ${m}m\n")
            .append("• Steps: ${String.format(Locale.getDefault(), "%,d", day.steps)} / ${String.format(Locale.getDefault(), "%,d", day.stepGoal)}\n")
            .append("• Exercise Sets: ${day.exerciseSets}")
            .toString()
        view.findViewById<TextView>(R.id.tvReportRawStats)?.text = rawStats

        val tipsText = buildTips(day).joinToString("\n\n") { "• $it" }
        view.findViewById<TextView>(R.id.tvReportTips)?.text = tipsText

        UiThemePrefs.applyWindowAndViewTreeTheme(this, view)

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        view.findViewById<Button>(R.id.btnCloseReport)?.setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
    }
}
