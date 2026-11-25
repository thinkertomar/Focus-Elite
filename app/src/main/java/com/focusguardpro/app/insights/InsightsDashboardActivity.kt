package com.focusguardpro.app.insights

import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.content.Intent
import com.focusguardpro.app.ActivityTelemetryEngine
import com.focusguardpro.app.DailyRollupManager
import com.focusguardpro.app.DisciplineDataRepository
import com.focusguardpro.app.EliteCalendarActivity
import com.focusguardpro.app.EliteDailyNotebookActivity
import com.focusguardpro.app.EliteJournalActivity
import com.focusguardpro.app.EliteTasksActivity
import com.focusguardpro.app.GoalPrefs
import com.focusguardpro.app.R
import com.focusguardpro.app.RingProgressView
import com.focusguardpro.app.SmartSleepEngine
import com.focusguardpro.app.StepPrefs
import com.focusguardpro.app.UiThemePrefs
import com.focusguardpro.app.DistanceHelper
import com.focusguardpro.app.UsageStatsHelper
import com.focusguardpro.app.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

class InsightsDashboardActivity : AppCompatActivity() {

    private lateinit var tvSynergyScore: TextView
    private lateinit var tvAvgFocus: TextView
    private lateinit var tvAvgSteps: TextView
    private lateinit var tvAvgBestDay: TextView
    private lateinit var synergyHeroRing: RingProgressView
    private lateinit var tvSynergyGradeBadge: TextView
    private lateinit var tvPillarScreenScore: TextView
    private lateinit var pbPillarScreen: ProgressBar
    private lateinit var tvPillarStepScore: TextView
    private lateinit var pbPillarStep: ProgressBar
    private lateinit var tvPillarWorkoutScore: TextView
    private lateinit var pbPillarWorkout: ProgressBar
    private lateinit var tvTrendCurveCallout: TextView
    private lateinit var trendCurveView: SynergyTrendCurveView
    private lateinit var eliteAdapter: EliteInsightDayAdapter

    companion object {
        // Lowered from 8h max study cap -> 6h (360 min) earns full Study pillar points; studying up to 8h+ is never penalized.
        private const val STUDY_TARGET_MINUTES = 360
    }

    private var loadJob: Job? = null
    private var currentDays: Int = 7
    private var disciplineInsightsSlot: LinearLayout? = null
    private val studyMinsByDate = HashMap<String, Int>()
    private val distractMinsByDate = HashMap<String, Int>()
    private val hasSplitByDate = HashMap<String, Boolean>()
    private val sleepByDate = HashMap<String, SmartSleepEngine.SleepDetectionResult>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_insights_dashboard)

        findViewById<View>(R.id.btnInsightsBack)?.setOnClickListener { finish() }

        tvSynergyScore = findViewById(R.id.tv_synergy_score)
        tvAvgFocus = findViewById(R.id.tv_avg_focus)
        tvAvgSteps = findViewById(R.id.tv_avg_steps)
        tvAvgBestDay = findViewById(R.id.tvAvgBestDay)
        synergyHeroRing = findViewById(R.id.synergyHeroRing)
        synergyHeroRing.ringColor = Color.parseColor("#16D99A")
        tvSynergyGradeBadge = findViewById(R.id.tvSynergyGradeBadge)
        tvPillarScreenScore = findViewById(R.id.tvPillarScreenScore)
        pbPillarScreen = findViewById(R.id.pbPillarScreen)
        tvPillarStepScore = findViewById(R.id.tvPillarStepScore)
        pbPillarStep = findViewById(R.id.pbPillarStep)
        tvPillarWorkoutScore = findViewById(R.id.tvPillarWorkoutScore)
        pbPillarWorkout = findViewById(R.id.pbPillarWorkout)
        tvTrendCurveCallout = findViewById(R.id.tvTrendCurveCallout)

        val curveContainer = findViewById<FrameLayout>(R.id.synergyTrendCurveContainer)
        trendCurveView = SynergyTrendCurveView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            onDaySelected = { day ->
                val studyM = studyMinsByDate[day.dateStr]
                val hasSplit = hasSplitByDate[day.dateStr] == true
                val sleep = sleepByDate[day.dateStr]
                val labelTime = if (hasSplit && studyM != null) {
                    "📚 ${studyM / 60}h ${studyM % 60}m study"
                } else {
                    "${day.screenTimeMinutes / 60}h ${day.screenTimeMinutes % 60}m screen"
                }
                val setsTag = if (day.exerciseSets > 0) " + ${day.exerciseSets} sets" else ""
                val sleepTag = if (sleep != null && sleep.hasValidSleep) {
                    " • 🌙 ${sleep.grade} (${SmartSleepEngine.formatDuration(sleep.effectiveSleepMs)})"
                } else ""
                tvTrendCurveCallout.text =
                    "${formatShortDate(day.dateStr)}: ${day.score}/100 ($labelTime • ${String.format(Locale.US, "%,d", day.steps)} stp$setsTag$sleepTag)"
            }
        }
        curveContainer.addView(trendCurveView)

        // Tap Sleep & Recovery KPI tile to open Daily Notebook Sleep Center
        (tvAvgBestDay.parent as? View)?.setOnClickListener {
            startActivity(Intent(this, EliteDailyNotebookActivity::class.java))
        }

        // Transparent formula caption & dedicated Personal Discipline Consistency slot
        findViewById<TextView>(R.id.tvInsightsSubtitle)?.text =
            "Formula: Study & Focus (40) + Fitness Tracker (35) + Sleep & Recovery (25)"
        val trendCard = findViewById<View>(R.id.cardSynergyTrendCurve)
        val mainColumn = trendCard?.parent as? LinearLayout
        if (mainColumn != null) {
            val slot = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            val insertIdx = mainColumn.indexOfChild(trendCard) + 1
            mainColumn.addView(slot, insertIdx)
            disciplineInsightsSlot = slot
        }

        val rv = findViewById<RecyclerView>(R.id.rv_insights_breakdown)
        rv.layoutManager = LinearLayoutManager(this)
        eliteAdapter = EliteInsightDayAdapter { day -> showDayReportDialog(day) }
        rv.adapter = eliteAdapter

        val rg = findViewById<RadioGroup>(R.id.rg_time_range)
        val rb7 = findViewById<RadioButton>(R.id.rb_7_days)
        val rb30 = findViewById<RadioButton>(R.id.rb_30_days)
        updateRangePillStyle(rb7, rb30, is7Days = true)

        rg.setOnCheckedChangeListener { _, checkedId ->
            val is7 = checkedId != R.id.rb_30_days
            currentDays = if (is7) 7 else 30
            updateRangePillStyle(rb7, rb30, is7)
            loadInsights(currentDays)
        }

        applyThemeStyling()
        loadInsights(currentDays)
    }

    private fun updateRangePillStyle(rb7: RadioButton?, rb30: RadioButton?, is7Days: Boolean) {
        if (is7Days) {
            rb7?.setBackgroundResource(R.drawable.bg_fitness_pill)
            rb7?.setTextColor(Color.parseColor("#16D99A"))
            rb30?.background = null
            rb30?.setTextColor(Color.parseColor("#8995A3"))
        } else {
            rb30?.setBackgroundResource(R.drawable.bg_fitness_pill)
            rb30?.setTextColor(Color.parseColor("#16D99A"))
            rb7?.background = null
            rb7?.setTextColor(Color.parseColor("#8995A3"))
        }
    }

    private var hasCompletedInitialLoad = false

    override fun onResume() {
        super.onResume()
        applyThemeStyling()
        if (hasCompletedInitialLoad) {
            loadInsights(currentDays)
        }
        hasCompletedInitialLoad = true
    }

    private fun applyThemeStyling() {
        val isWhite = UiThemePrefs.isWhiteBackgroundActive(this)
        val isBlack = UiThemePrefs.isBlackBackgroundActive(this)
        val bg = when {
            isWhite -> Color.parseColor("#FFFFFF")
            isBlack -> Color.parseColor("#000000")
            else -> Color.parseColor("#080D12")
        }
        window?.statusBarColor = bg
        window?.navigationBarColor = bg
        val root = findViewById<View>(R.id.scrollInsightsDashboard)
        root?.setBackgroundColor(bg)
        if (isWhite) {
            UiThemePrefs.applyWindowAndViewTreeTheme(this, root)
        }
    }

    /**
     * Study & Focus Hygiene Score (0–40 points):
     * - Study Effort (0–26 pts): Target lowered from 8h max to 6h (360 min) so 4h–6h study earns
     *   strong/full marks and studying up to 8h+ is rewarded, never penalized.
     * - Distraction Hygiene (0–14 pts): Penalizes ONLY distracting app/site time, never Study/Improve.
     * - Legacy fallback (older days without per-category events): gently scores up to 8h without harsh wipeout.
     */
    private fun computeStudyAndFocusScore(
        studyMins: Int,
        distractMins: Int,
        totalScreenMins: Int,
        hasCategorySplit: Boolean
    ): Int {
        if (hasCategorySplit) {
            val studyPts = ((studyMins.toFloat() / STUDY_TARGET_MINUTES.toFloat()) * 26f)
                .roundToInt()
                .coerceIn(0, 26)
            val distractPts = when {
                distractMins <= 30 -> 14
                distractMins <= 60 -> 11
                distractMins <= 90 -> 8
                distractMins <= 120 -> 5
                else -> (5 - ((distractMins - 120) / 20)).coerceIn(0, 5)
            }
            return (studyPts + distractPts).coerceIn(0, 40)
        }
        // Fallback when only total screen time exists (e.g., >10 days ago): do not penalize up to 8h study days
        return when {
            totalScreenMins <= 0 -> 18
            totalScreenMins <= 360 -> 30
            totalScreenMins <= 480 -> 26
            else -> (26 - ((totalScreenMins - 480) / 30) * 4).coerceIn(8, 26)
        }
    }

    /**
     * Fitness Tracker Pillar (0–35 points):
     * Combines Daily Steps (0–23 pts) + Genuine Daily Exercise Sets logged in ExerciseDao (0–12 pts).
     * Walking sessions are strictly NEVER counted as workout sets; they contribute only via steps
     * (plus a capped +2..+3 cardio endurance credit on >=125% step days when 0 strength sets are logged,
     * capped at 26/35 so genuine exercise sets are needed for top fitness scores).
     */
    private fun computeFitnessTrackerScore(
        steps: Int,
        stepGoal: Int,
        genuineExerciseSets: Int
    ): Int {
        val safeGoal = stepGoal.coerceAtLeast(1000)
        val stepRatio = steps.toFloat() / safeGoal.toFloat()
        val stepPts = (stepRatio * 23f).roundToInt().coerceIn(0, 23)
        val setPts = when {
            genuineExerciseSets >= 4 -> 12
            genuineExerciseSets == 3 -> 10
            genuineExerciseSets == 2 -> 7
            genuineExerciseSets == 1 -> 4
            stepRatio >= 1.50f -> 3
            stepRatio >= 1.25f -> 2
            else -> 0
        }
        val total = (stepPts + setPts).coerceIn(0, 35)
        return if (genuineExerciseSets == 0) total.coerceAtMost(26) else total
    }

    /**
     * Clinical Sleep & Recovery Pillar (0–25 points):
     * Maps SmartSleepEngine's 5-domain sleep medicine recovery score (0–100) into 0–25 pillar points.
     */
    private fun computeSleepRecoveryPillarScore(sleep: SmartSleepEngine.SleepDetectionResult?): Int {
        if (sleep == null || !sleep.hasValidSleep || sleep.qualityScore <= 0) return 0
        return ((sleep.qualityScore * 25f) / 100f).roundToInt().coerceIn(1, 25)
    }

    private fun loadInsights(days: Int) {
        loadJob?.cancel()

        // Fast instant frame-0 binding so "--/100" never hangs while IO queries complete
        val cachedSnap = UsageStatsHelper.getCachedTodaySnapshot(this)
        if (cachedSnap != null && eliteAdapter.itemCount == 0) {
            val todayTotalMins = (cachedSnap.totalScreenTimeMs / 60_000L).toInt().coerceAtLeast(0)
            val todayStudyMins = ((cachedSnap.studyMs + cachedSnap.improveMs) / 60_000L).toInt().coerceAtLeast(0)
            val todayDistractMins = (cachedSnap.distractMs / 60_000L).toInt().coerceAtLeast(0)
            val hasSplit = (cachedSnap.studyMs + cachedSnap.improveMs + cachedSnap.distractMs + cachedSnap.utilityMs) > 0L
            val screenScore = computeStudyAndFocusScore(todayStudyMins, todayDistractMins, todayTotalMins, hasSplit)
            val quickScore = (screenScore + 25).coerceIn(0, 100)
            tvSynergyScore.text = "$quickScore/100"
            synergyHeroRing.progress = quickScore / 100f
            val showMins = if (hasSplit) todayStudyMins else todayTotalMins
            tvAvgFocus.text = "${showMins / 60}h ${showMins % 60}m"
            val quickSteps = try { StepPrefs.getDailySteps(this).coerceAtLeast(0) } catch (_: Throwable) { 0 }
            tvAvgSteps.text = if (quickSteps > 0) String.format(Locale.getDefault(), "%,d", quickSteps) else "—"
        }

        DailyRollupManager.rollupIfNeeded(applicationContext)

        loadJob = lifecycleScope.launch(Dispatchers.IO) {
            val appCtx = applicationContext
            val keyFmt = SimpleDateFormat("yyyyMMdd", Locale.US)
            val displayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

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

            val nowMs = System.currentTimeMillis()
            val oldestCal = Calendar.getInstance().apply {
                timeInMillis = nowMs
                add(Calendar.DAY_OF_YEAR, -(days - 1))
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val rangeStartMs = oldestCal.timeInMillis

            // Trigger non-blocking background Health Connect sync once instead of blocking per day
            ActivityTelemetryEngine.triggerBackgroundHealthConnectSync(appCtx, days.coerceAtLeast(7))

            val walkSessionsByDayKey = try {
                DistanceHelper.getSessions(appCtx, rangeStartMs, nowMs)
                    .groupBy { keyFmt.format(Date(it.startTime)) }
            } catch (_: Throwable) {
                emptyMap()
            }

            // Build day descriptors once and fetch recent WellbeingSnapshots in PARALLEL
            data class DayDescriptor(
                val idx: Int,
                val dateStr: String,
                val dayKey: String,
                val dStart: Long,
                val dEnd: Long
            )

            val descriptors = ArrayList<DayDescriptor>(days)
            val cal = Calendar.getInstance()
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
                descriptors.add(DayDescriptor(i, dateStr, dayKey, dStart, dEnd))
            }

            val wellbeingByDayKey = HashMap<String, UsageStatsHelper.WellbeingSnapshot?>(12)
            coroutineScope {
                descriptors.filter { it.idx <= 9 }.map { desc ->
                    async(Dispatchers.IO) {
                        val snap = try {
                            if (desc.idx == 0) {
                                UsageStatsHelper.getTodayWellbeingSnapshot(appCtx, forceRefresh = false)
                            } else {
                                UsageStatsHelper.getDayWellbeingSnapshot(appCtx, desc.dStart, desc.dEnd, forceRefresh = false)
                            }
                        } catch (_: Throwable) {
                            null
                        }
                        desc.dayKey to snap
                    }
                }.awaitAll().forEach { (dk, snap) ->
                    wellbeingByDayKey[dk] = snap
                }
            }

            // Sync & load multi-day clinical Sleep & Recovery summary across the selected period
            val periodSleep = try {
                SmartSleepEngine.syncRecentNightsArchive(appCtx, lookbackDays = days.coerceAtMost(14))
                SmartSleepEngine.getPeriodRecoverySummary(appCtx, periodDays = days)
            } catch (_: Throwable) {
                null
            }
            val sleepByDayKey = periodSleep?.dailyNights?.toMap().orEmpty()

            val dayList = ArrayList<DayInsight>(days)
            val localStudyMap = HashMap<String, Int>()
            val localDistractMap = HashMap<String, Int>()
            val localSplitMap = HashMap<String, Boolean>()
            val localSleepMap = HashMap<String, SmartSleepEngine.SleepDetectionResult>()

            var totalScreenMins = 0
            var totalStudyMins = 0
            var studySplitDays = 0
            var totalSteps = 0
            var totalScore = 0
            var totalScreenScore = 0
            var totalFitnessScore = 0
            var totalSleepPillarScore = 0
            var sleepTrackedDays = 0
            var countedDays = 0

            for (desc in descriptors) {
                val i = desc.idx
                val dateStr = desc.dateStr
                val dayKey = desc.dayKey
                val dStart = desc.dStart
                val dEnd = desc.dEnd

                val wellbeingSnap = wellbeingByDayKey[dayKey]

                val hasSplit = wellbeingSnap != null &&
                    (wellbeingSnap.studyMs + wellbeingSnap.improveMs + wellbeingSnap.distractMs + wellbeingSnap.utilityMs) > 0L

                val studyMins = if (hasSplit && wellbeingSnap != null) {
                    ((wellbeingSnap.studyMs + wellbeingSnap.improveMs) / 60_000L).toInt().coerceIn(0, 1440)
                } else {
                    0
                }
                val distractMins = if (hasSplit && wellbeingSnap != null) {
                    (wellbeingSnap.distractMs / 60_000L).toInt().coerceIn(0, 1440)
                } else {
                    0
                }

                val screenTimeMins = if (hasSplit && wellbeingSnap != null) {
                    (wellbeingSnap.totalScreenTimeMs / 60_000L).toInt().coerceIn(0, 1440)
                } else if (i == 0) {
                    (UsageStatsHelper.getTodayScreenTimeMillis(appCtx) / 60_000L).toInt().coerceIn(0, 1440)
                } else if (i <= 9) {
                    val fromHist = (UsageStatsHelper.getCachedHistoricalDayTotalMillis(appCtx, dStart).coerceAtLeast(0L) / 60_000L).toInt()
                    if (fromHist > 0) {
                        fromHist.coerceIn(0, 1080)
                    } else {
                        (snapshotMinutesByDay[dayKey] ?: 0).coerceIn(0, 1080)
                    }
                } else {
                    // Older than 10 days: use Room DailyUsageSnapshot or cached historical total directly (never scan pruned UsageEvents)
                    val cachedMs = UsageStatsHelper.getCachedHistoricalDayTotalMillis(appCtx, dStart)
                    val fromCached = if (cachedMs > 0L) (cachedMs / 60_000L).toInt() else 0
                    max(fromCached, snapshotMinutesByDay[dayKey] ?: 0).coerceIn(0, 1080)
                }

                localStudyMap[dateStr] = studyMins
                localDistractMap[dateStr] = distractMins
                localSplitMap[dateStr] = hasSplit
                if (hasSplit) {
                    totalStudyMins += studyMins
                    studySplitDays++
                }

                val daySessionSteps = walkSessionsByDayKey[dayKey]?.sumOf { it.steps } ?: 0
                val localSteps = try {
                    ActivityTelemetryEngine.getFastLocalStepsForDay(appCtx, dStart, preloadedDaySessionSteps = daySessionSteps) ?: 0
                } catch (_: Throwable) {
                    0
                }
                val steps = if (i == 0) max(todaySteps, localSteps) else localSteps

                // Strictly genuine ExerciseDao logs — walking sessions are NEVER counted as workout sets
                val exerciseSets = exerciseLogs.count { log ->
                    log.timestamp in dStart..dEnd
                }

                val sleepResult = sleepByDayKey[dayKey]
                val hasSleep = sleepResult != null && sleepResult.hasValidSleep
                if (hasSleep && sleepResult != null) {
                    localSleepMap[dateStr] = sleepResult
                }

                val screenScore = computeStudyAndFocusScore(studyMins, distractMins, screenTimeMins, hasSplit)
                val fitnessScore = computeFitnessTrackerScore(steps, stepGoal, exerciseSets)
                val sleepPillarScore = computeSleepRecoveryPillarScore(sleepResult)
                val dayScore = if (hasSleep) {
                    (screenScore + fitnessScore + sleepPillarScore).coerceIn(0, 100)
                } else {
                    // Fair scaling across tracked pillars (75 max -> 100) when no overnight sleep record exists yet
                    (((screenScore + fitnessScore) * 100f) / 75f).roundToInt().coerceIn(0, 100)
                }

                totalScreenMins += screenTimeMins
                totalSteps += steps
                totalScore += dayScore
                totalScreenScore += screenScore
                totalFitnessScore += fitnessScore
                if (hasSleep) {
                    totalSleepPillarScore += sleepPillarScore
                    sleepTrackedDays++
                }
                countedDays++

                dayList.add(
                    DayInsight(
                        dateStr = dateStr,
                        screenTimeMinutes = screenTimeMins,
                        steps = steps,
                        exerciseSets = exerciseSets,
                        score = dayScore,
                        screenScore = screenScore,
                        stepScore = fitnessScore,
                        exerciseScore = sleepPillarScore,
                        stepGoal = stepGoal
                    )
                )
            }

            val denom = countedDays.coerceAtLeast(1)
            val avgScore = (totalScore / denom).coerceIn(0, 100)
            val avgScreenPts = (totalScreenScore / denom).coerceIn(0, 40)
            val avgFitnessPts = (totalFitnessScore / denom).coerceIn(0, 35)
            val avgSleepPts = if (sleepTrackedDays > 0) (totalSleepPillarScore / sleepTrackedDays).coerceIn(0, 25) else 0
            val avgMins = (totalScreenMins / denom).coerceAtLeast(0)
            val avgStudyMins = if (studySplitDays > 0) (totalStudyMins / studySplitDays).coerceAtLeast(0) else avgMins
            val avgDailySteps = (totalSteps / denom).coerceAtLeast(0)
            val disciplineReport = try {
                DisciplineDataRepository.loadPeriodInsights(
                    context = appCtx,
                    periodDays = days,
                    precomputedWellbeingByDateKey = wellbeingByDayKey
                )
            } catch (_: Throwable) {
                null
            }

            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                studyMinsByDate.clear()
                studyMinsByDate.putAll(localStudyMap)
                distractMinsByDate.clear()
                distractMinsByDate.putAll(localDistractMap)
                hasSplitByDate.clear()
                hasSplitByDate.putAll(localSplitMap)
                sleepByDate.clear()
                sleepByDate.putAll(localSleepMap)

                tvSynergyScore.text = "$avgScore/100"
                synergyHeroRing.progress = (avgScore / 100f).coerceIn(0f, 1f)
                synergyHeroRing.ringColor = scoreAccentColor(avgScore)

                val grade = gradeFor(avgScore)
                val statusWord = when {
                    avgScore >= 80 -> "Elite Discipline"
                    avgScore >= 65 -> "Strong Momentum"
                    avgScore >= 50 -> "Building Consistency"
                    else -> "Needs Focus Reset"
                }
                tvSynergyGradeBadge.text = "Grade $grade • $statusWord"
                tvSynergyGradeBadge.setTextColor(scoreAccentColor(avgScore))

                tvPillarScreenScore.text = "$avgScreenPts / 40"
                pbPillarScreen.progress = (avgScreenPts * 100) / 40

                tvPillarStepScore.text = "$avgFitnessPts / 35"
                pbPillarStep.progress = (avgFitnessPts * 100) / 35

                if (periodSleep != null && periodSleep.trackedNights > 0) {
                    tvPillarWorkoutScore.text = "${periodSleep.grade} · $avgSleepPts/25"
                    pbPillarWorkout.progress = periodSleep.avgRecoveryScore.coerceIn(0, 100)
                    tvAvgBestDay.text = "${periodSleep.grade} · ${SmartSleepEngine.formatDuration(periodSleep.avgEffectiveSleepMs)}"
                } else {
                    tvPillarWorkoutScore.text = "— / 25"
                    pbPillarWorkout.progress = 0
                    tvAvgBestDay.text = "No data"
                }

                tvAvgFocus.text = "${avgStudyMins / 60}h ${avgStudyMins % 60}m"
                tvAvgSteps.text = String.format(Locale.getDefault(), "%,d", avgDailySteps)

                // Chronological order (oldest -> newest) for the Synergy Trend Curve
                val chronological = dayList.reversed()
                trendCurveView.setDays(chronological)
                val peak = chronological.maxByOrNull { it.score }
                if (peak != null) {
                    tvTrendCurveCallout.text = "Best: ${formatShortDate(peak.dateStr)} (${peak.score}/100)"
                }

                eliteAdapter.submitList(dayList)

                disciplineInsightsSlot?.let { slot ->
                    slot.removeAllViews()
                    if (disciplineReport != null) {
                        slot.addView(
                            DisciplineInsightsCharts.buildDisciplineInsightsSection(
                                context = this@InsightsDashboardActivity,
                                report = disciplineReport,
                                onOpenCalendar = {
                                    startActivity(Intent(this@InsightsDashboardActivity, EliteCalendarActivity::class.java))
                                },
                                onOpenTasks = {
                                    startActivity(Intent(this@InsightsDashboardActivity, EliteTasksActivity::class.java))
                                },
                                onOpenJournal = {
                                    startActivity(Intent(this@InsightsDashboardActivity, EliteJournalActivity::class.java).apply {
                                        putExtra(EliteJournalActivity.EXTRA_INITIAL_MODE, EliteJournalActivity.MODE_HISTORY)
                                    })
                                },
                                onOpenNotebook = {
                                    startActivity(Intent(this@InsightsDashboardActivity, com.focusguardpro.app.EliteSleepHistoryActivity::class.java))
                                }
                            )
                        )
                    }
                }
                applyThemeStyling()
            }
        }
    }

    private fun scoreAccentColor(score: Int): Int = when {
        score >= 75 -> Color.parseColor("#16D99A") // Refined Emerald
        score >= 55 -> Color.parseColor("#27C7C9") // Secondary Teal
        else -> Color.parseColor("#F59E0B")        // Warning Amber
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

    private fun formatFriendlyDate(isoDate: String, indexFromToday: Int): String {
        return try {
            val parser = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val parsed = parser.parse(isoDate) ?: return isoDate
            val out = SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(parsed)
            when (indexFromToday) {
                0 -> "Today · $out"
                1 -> "Yesterday · $out"
                else -> out
            }
        } catch (_: Throwable) {
            isoDate
        }
    }

    private fun formatShortDate(isoDate: String): String {
        return try {
            val parser = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val parsed = parser.parse(isoDate) ?: return isoDate
            SimpleDateFormat("MMM d", Locale.getDefault()).format(parsed)
        } catch (_: Throwable) {
            isoDate
        }
    }

    private fun buildTips(day: DayInsight): List<String> {
        val tips = ArrayList<String>()
        val hasSplit = hasSplitByDate[day.dateStr] == true
        val studyMins = studyMinsByDate[day.dateStr] ?: 0
        val distractMins = distractMinsByDate[day.dateStr] ?: 0
        val sleep = sleepByDate[day.dateStr]

        if (hasSplit) {
            if (studyMins < STUDY_TARGET_MINUTES) {
                val rem = STUDY_TARGET_MINUTES - studyMins
                tips.add("Logged ${studyMins / 60}h ${studyMins % 60}m of Study/Improve (${rem / 60}h ${rem % 60}m shy of the 6h study target).")
            } else {
                tips.add("Strong Study consistency (${studyMins / 60}h ${studyMins % 60}m) — hit the 6h benchmark cleanly!")
            }
            if (distractMins > 60) {
                tips.add("Distracting screen time reached ${distractMins / 60}h ${distractMins % 60}m. Keep distractions under 30–60m to earn full hygiene points.")
            }
        } else if (day.screenScore < 24) {
            val h = day.screenTimeMinutes / 60
            val m = day.screenTimeMinutes % 60
            tips.add("Total screen time reached ${h}h ${m}m. Keep distracting apps capped while preserving study sessions.")
        }

        // Fitness Tracker guidance (Steps + Genuine Exercise Sets, walking excluded from workout sets)
        if (day.steps < day.stepGoal) {
            val remaining = (day.stepGoal - day.steps).coerceAtLeast(0)
            tips.add("Fitness Tracker: ${String.format(Locale.getDefault(), "%,d", remaining)} steps shy of your ${String.format(Locale.getDefault(), "%,d", day.stepGoal)} daily step goal.")
        }
        if (day.exerciseSets < 3) {
            tips.add("Log ${3 - day.exerciseSets}+ dedicated strength/exercise sets (walking sessions count toward steps only) to unlock full 35/35 Fitness Tracker points.")
        }

        // Clinical Sleep & Recovery guidance (AASM sleep medicine standards)
        if (sleep != null && sleep.hasValidSleep) {
            val effMins = (sleep.effectiveSleepMs / 60_000L).toInt()
            val awakeMins = (sleep.awakeTotalMs / 60_000L).toInt()
            when {
                effMins < 420 -> tips.add("Sleep Medicine Note: ${SmartSleepEngine.formatDuration(sleep.effectiveSleepMs)} effective sleep is below the 7–9h restorative window (Grade ${sleep.grade}). Aim for an earlier wind-down tonight.")
                sleep.efficiencyPct < 85 -> tips.add("Sleep Efficiency was ${sleep.efficiencyPct}% with ${awakeMins}m awake after sleep onset. Keep the phone out of reach during night awakenings.")
                effMins > 570 -> tips.add("Extended sleep (${SmartSleepEngine.formatDuration(sleep.effectiveSleepMs)}) can signal accumulated sleep debt or circadian drift — stabilize wake time within ±30 min.")
                sleep.qualityScore >= 84 -> tips.add("Restorative recovery achieved (Grade ${sleep.grade} · ${sleep.qualityScore}/100, ${sleep.efficiencyPct}% efficiency).")
            }
        } else {
            tips.add("Sleep & Recovery pending for this date — review or log your overnight sleep in Daily Notebook for clinical recovery grading.")
        }

        if (tips.isEmpty()) {
            tips.add("Outstanding discipline across study target, fitness tracker, and restorative sleep!")
        }
        return tips
    }

    private fun showDayReportDialog(day: DayInsight) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_day_report_card, null)
        val grade = gradeFor(day.score)
        val gColor = gradeColor(day.score)
        val sleep = sleepByDate[day.dateStr]
        val hasSleep = sleep != null && sleep.hasValidSleep

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

        if (hasSleep && sleep != null) {
            view.findViewById<TextView>(R.id.tvExerciseScoreLabel)?.text =
                "Grade ${sleep.grade} (${sleep.qualityScore}/100) · ${day.exerciseScore}/25"
            view.findViewById<ProgressBar>(R.id.pbExerciseScore)?.progress = sleep.qualityScore.coerceIn(0, 100)
        } else {
            view.findViewById<TextView>(R.id.tvExerciseScoreLabel)?.text = "Pending · —/25"
            view.findViewById<ProgressBar>(R.id.pbExerciseScore)?.progress = 0
        }

        val h = day.screenTimeMinutes / 60
        val m = day.screenTimeMinutes % 60
        val hasSplit = hasSplitByDate[day.dateStr] == true
        val studyM = studyMinsByDate[day.dateStr] ?: 0
        val distractM = distractMinsByDate[day.dateStr] ?: 0

        val rawStats = StringBuilder().apply {
            if (hasSplit) {
                append("• Study & Improve: ${studyM / 60}h ${studyM % 60}m (6h target)\n")
                append("• Distracting Time: ${distractM / 60}h ${distractM % 60}m\n")
            }
            append("• Total Screen Time: ${h}h ${m}m\n")
            append("• Fitness Tracker: ${String.format(Locale.getDefault(), "%,d", day.steps)} / ${String.format(Locale.getDefault(), "%,d", day.stepGoal)} steps")
            append(" + ${day.exerciseSets} exercise set${if (day.exerciseSets == 1) "" else "s"} (walks excluded)\n")
            if (hasSleep && sleep != null) {
                val awStr = if (sleep.awakenings.isNotEmpty()) " · ${sleep.awakenings.size} wake-up (${sleep.awakeTotalMs / 60_000L}m)" else " · Uninterrupted"
                append("• Sleep & Recovery: ${SmartSleepEngine.formatDuration(sleep.effectiveSleepMs)} (${SmartSleepEngine.formatClock(sleep.bedtimeMs)}–${SmartSleepEngine.formatClock(sleep.wakeTimeMs)}) · ${sleep.efficiencyPct}% eff$awStr\n")
                append("• Clinical Recovery: Grade ${sleep.grade} (${sleep.qualityScore}/100 · ${sleep.qualityLabel})")
            } else {
                append("• Sleep & Recovery: No overnight record logged")
            }
        }.toString()
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

    /**
     * Smooth Cubic-Bezier Discipline Synergy Curve across 7D / 30D with interactive scrubbing.
     */
    private inner class SynergyTrendCurveView(context: Context) : View(context) {
        private var days: List<DayInsight> = emptyList()
        private var selectedIdx: Int = -1
        var onDaySelected: ((DayInsight) -> Unit)? = null

        private val density = resources.displayMetrics.density
        private val scaledDensity = resources.displayMetrics.scaledDensity

        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f * density
            color = Color.parseColor("#1E293B")
        }
        private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.2f * density
            pathEffect = DashPathEffect(floatArrayOf(4f * density, 4f * density), 0f)
        }
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.8f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 10f * scaledDensity
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        private val curvePath = Path()
        private val fillPath = Path()

        fun setDays(list: List<DayInsight>) {
            days = list
            selectedIdx = list.lastIndex
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f || days.isEmpty()) return

            val emerald = Color.parseColor("#16D99A")
            val teal = Color.parseColor("#27C7C9")
            val textSec = Color.parseColor("#8995A3")

            val padL = 16f * density
            val padR = 16f * density
            val padTop = 14f * density
            val padBottom = 22f * density
            val curveBottom = h - padBottom
            val curveH = (curveBottom - padTop).coerceAtLeast(30f * density)
            val chartW = (w - padL - padR).coerceAtLeast(1f)

            for (i in 0..2) {
                val gy = padTop + (curveH * i / 2f)
                canvas.drawLine(padL, gy, w - padR, gy, gridPaint)
            }

            val count = days.size
            val stepX = if (count > 1) chartW / (count - 1).toFloat() else 0f
            val coords = days.mapIndexed { idx, d ->
                val x = if (count == 1) padL + chartW / 2f else padL + idx * stepX
                val ratio = (d.score.toFloat() / 100f).coerceIn(0.05f, 1f)
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
                    Color.argb(110, Color.red(emerald), Color.green(emerald), Color.blue(emerald)),
                    Color.argb(30, Color.red(teal), Color.green(teal), Color.blue(teal)),
                    Color.argb(0, Color.red(teal), Color.green(teal), Color.blue(teal))
                ),
                floatArrayOf(0f, 0.65f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawPath(fillPath, fillPaint)

            glowPaint.color = Color.argb(50, Color.red(emerald), Color.green(emerald), Color.blue(emerald))
            canvas.drawPath(curvePath, glowPaint)

            curvePaint.color = emerald
            canvas.drawPath(curvePath, curvePaint)

            val labelInterval = max(1, count / 5)
            val labelY = h - 5f * density
            coords.forEachIndexed { idx, pt ->
                val isSel = idx == selectedIdx
                if (idx % labelInterval == 0 || idx == count - 1) {
                    axisPaint.color = if (isSel) emerald else textSec
                    val lbl = if (idx == count - 1) "Today" else formatShortDate(days[idx].dateStr)
                    canvas.drawText(lbl, pt.x, labelY, axisPaint)
                }
                if (isSel) {
                    guidePaint.color = Color.argb(130, Color.red(emerald), Color.green(emerald), Color.blue(emerald))
                    canvas.drawLine(pt.x, padTop, pt.x, curveBottom, guidePaint)
                    dotPaint.color = Color.argb(75, Color.red(emerald), Color.green(emerald), Color.blue(emerald))
                    canvas.drawCircle(pt.x, pt.y, 7f * density, dotPaint)
                    dotPaint.color = emerald
                    canvas.drawCircle(pt.x, pt.y, 4f * density, dotPaint)
                } else if (count <= 10) {
                    dotPaint.color = teal
                    canvas.drawCircle(pt.x, pt.y, 2.5f * density, dotPaint)
                }
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (days.isEmpty()) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_MOVE -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    val padL = 16f * density
                    val padR = 16f * density
                    val chartW = (width - padL - padR).coerceAtLeast(1f)
                    val ratio = ((event.x - padL) / chartW).coerceIn(0f, 1f)
                    val idx = (ratio * (days.size - 1)).roundToInt().coerceIn(0, days.lastIndex)
                    if (idx != selectedIdx) {
                        selectedIdx = idx
                        onDaySelected?.invoke(days[idx])
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

    /**
     * Executive Day-by-Day Audit Card Adapter with friendly dates, 3-pillar mini proportion bar, and Grade badge.
     */
    private inner class EliteInsightDayAdapter(
        private val onClick: (DayInsight) -> Unit
    ) : RecyclerView.Adapter<EliteInsightDayAdapter.DayVH>() {

        private val items = ArrayList<DayInsight>()

        fun submitList(newItems: List<DayInsight>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        inner class DayVH(
            val root: LinearLayout,
            val tvDate: TextView,
            val tvSubStats: TextView,
            val segScreen: View,
            val segSteps: View,
            val segWorkout: View,
            val tvScoreBadge: TextView
        ) : RecyclerView.ViewHolder(root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DayVH {
            val ctx = parent.context
            val d = ctx.resources.displayMetrics.density

            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((14 * d).toInt(), (12 * d).toInt(), (14 * d).toInt(), (12 * d).toInt())
                background = ContextCompat.getDrawable(ctx, R.drawable.bg_fitness_surface)
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (8 * d).toInt()
                }
                isClickable = true
                isFocusable = true
            }

            val topRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            val leftCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val tvDate = TextView(ctx).apply {
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#F1F5F9"))
            }

            val tvSub = TextView(ctx).apply {
                textSize = 11.5f
                setTextColor(Color.parseColor("#8995A3"))
                setPadding(0, (3 * d).toInt(), 0, 0)
            }

            leftCol.addView(tvDate)
            leftCol.addView(tvSub)

            val tvBadge = TextView(ctx).apply {
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setPadding((10 * d).toInt(), (5 * d).toInt(), (10 * d).toInt(), (5 * d).toInt())
            }

            topRow.addView(leftCol)
            topRow.addView(tvBadge)

            // 3-Pillar Mini Proportion Bar (Study Cyan + Fitness Emerald + Sleep & Recovery Indigo)
            val barTrack = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (4 * d).toInt()
                ).apply {
                    topMargin = (9 * d).toInt()
                }
                background = GradientDrawable().apply {
                    cornerRadius = 2f * d
                    setColor(Color.parseColor("#17212B"))
                }
                clipToOutline = true
            }

            val s1 = View(ctx).apply {
                setBackgroundColor(Color.parseColor("#27C7C9"))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            }
            val s2 = View(ctx).apply {
                setBackgroundColor(Color.parseColor("#16D99A"))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            }
            val s3 = View(ctx).apply {
                setBackgroundColor(Color.parseColor("#818CF8"))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            }
            barTrack.addView(s1)
            barTrack.addView(s2)
            barTrack.addView(s3)

            card.addView(topRow)
            card.addView(barTrack)

            return DayVH(card, tvDate, tvSub, s1, s2, s3, tvBadge)
        }

        override fun onBindViewHolder(holder: DayVH, position: Int) {
            val item = items[position]
            val d = holder.root.context.resources.displayMetrics.density

            holder.tvDate.text = formatFriendlyDate(item.dateStr, position)

            val h = item.screenTimeMinutes / 60
            val m = item.screenTimeMinutes % 60
            val stepsStr = String.format(Locale.US, "%,d", item.steps)
            val setsStr = if (item.exerciseSets > 0) " + ${item.exerciseSets} sets" else ""
            val sleep = sleepByDate[item.dateStr]
            val sleepStr = if (sleep != null && sleep.hasValidSleep) {
                " • 🌙 ${SmartSleepEngine.formatDuration(sleep.effectiveSleepMs)} (${sleep.grade})"
            } else {
                ""
            }
            val hasSplit = hasSplitByDate[item.dateStr] == true
            val studyM = studyMinsByDate[item.dateStr] ?: 0
            val distractM = distractMinsByDate[item.dateStr] ?: 0
            holder.tvSubStats.text = if (hasSplit) {
                "📚 ${studyM / 60}h ${studyM % 60}m • 🚫 ${distractM}m • 🏃 $stepsStr$setsStr$sleepStr"
            } else {
                "📱 ${h}h ${m}m • 🏃 $stepsStr$setsStr$sleepStr"
            }

            val accent = scoreAccentColor(item.score)
            val grade = gradeFor(item.score)
            holder.tvScoreBadge.text = "${item.score} · $grade ›"
            holder.tvScoreBadge.setTextColor(accent)
            holder.tvScoreBadge.background = GradientDrawable().apply {
                cornerRadius = 10f * d
                setColor(Color.parseColor("#17212B"))
                setStroke((1 * d).toInt(), Color.argb(120, Color.red(accent), Color.green(accent), Color.blue(accent)))
            }

            (holder.segScreen.layoutParams as? LinearLayout.LayoutParams)?.weight =
                item.screenScore.toFloat().coerceAtLeast(1f)
            (holder.segSteps.layoutParams as? LinearLayout.LayoutParams)?.weight =
                item.stepScore.toFloat().coerceAtLeast(1f)
            (holder.segWorkout.layoutParams as? LinearLayout.LayoutParams)?.weight =
                item.exerciseScore.toFloat().coerceAtLeast(if (item.exerciseScore > 0) 2f else 0f)
            holder.segScreen.parent?.requestLayout()

            holder.root.setOnClickListener { onClick(item) }
        }

        override fun getItemCount(): Int = items.size
    }
}
