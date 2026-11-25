package com.focusguardpro.app

import android.app.TimePickerDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Multi-Day Clinical Sleep & Recovery History Screen (7D / 30D).
 *
 * Replaces the cramped legacy SleepSummaryActivity chart with:
 * - Clean 7D / 30D segmented range toggle
 * - Fused multi-day archive (auto-tracked + manually logged nights across 45 days)
 * - Non-overlapping custom Canvas chart with dashed 7.5h clinical target & interactive tap/scrub callout
 * - Clinical 5-domain recovery grade (A+ to F), efficiency %, sleep debt, and bedtime consistency
 * - Night-by-night audit rows & sleek dark-themed manual sleep logger
 */
class EliteSleepHistoryActivity : AppCompatActivity() {

    private var periodDays: Int = 7
    private var currentSummary: SmartSleepEngine.PeriodSleepRecoverySummary? = null

    private lateinit var tvDateRange: TextView
    private lateinit var btnTab7d: TextView
    private lateinit var btnTab30d: TextView
    private lateinit var tvAvgSleep: TextView
    private lateinit var tvClinicalGrade: TextView
    private lateinit var tvAvgEfficiency: TextView
    private lateinit var tvTrackedNights: TextView
    private lateinit var tvChartCallout: TextView
    private lateinit var tvDebtLine: TextView
    private lateinit var sleepChartView: InteractiveSleepChartView
    private lateinit var llNightRows: LinearLayout

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FocusGuardApp.ensureRoomMainThreadQueries(this)
        buildUi()
        loadSleepHistory(periodDays)
    }

    override fun onResume() {
        super.onResume()
        loadSleepHistory(periodDays)
    }

    private fun buildUi() {
        val isLight = UiThemePrefs.isWhiteBackgroundActive(this)
        val isBlack = UiThemePrefs.isBlackBackgroundActive(this)
        val bgColor = when {
            isLight -> Color.parseColor("#F8FAFC")
            isBlack -> Color.parseColor("#000000")
            else -> Color.parseColor("#080D12")
        }
        val surfaceColor = if (isLight) Color.WHITE else Color.parseColor("#111824")
        val borderColor = if (isLight) Color.parseColor("#E2E8F0") else Color.parseColor("#1E2A3B")
        val textPrimary = if (isLight) Color.parseColor("#0F172A") else Color.parseColor("#F1F5F9")
        val textSecondary = if (isLight) Color.parseColor("#64748B") else Color.parseColor("#8995A3")
        val indigo = Color.parseColor("#818CF8")
        val emerald = Color.parseColor("#16D99A")

        window?.statusBarColor = bgColor
        window?.navigationBarColor = bgColor

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
            fitsSystemWindows = true
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Top Bar
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(14f), dp(16f), dp(10f))
        }
        topBar.addView(TextView(this).apply {
            text = "←"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textPrimary)
            setPadding(0, dp(4f), dp(14f), dp(4f))
            setOnClickListener { finish() }
        })

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(TextView(this).apply {
            text = "Sleep & Recovery"
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textPrimary)
        })
        tvDateRange = TextView(this).apply {
            text = "Last 7 Days"
            textSize = 11.5f
            setTextColor(textSecondary)
        }
        titleCol.addView(tvDateRange)
        topBar.addView(titleCol)

        // 7D / 30D Segmented Pill Toggle
        val pillGroup = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(3f), dp(3f), dp(3f), dp(3f))
            background = GradientDrawable().apply {
                cornerRadius = dp(16f).toFloat()
                setColor(surfaceColor)
                setStroke(dp(1f), borderColor)
            }
        }
        btnTab7d = TextView(this).apply {
            text = "7D"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(13f), dp(6f), dp(13f), dp(6f))
            setOnClickListener {
                if (periodDays != 7) {
                    periodDays = 7
                    updateTabStyles(indigo, textSecondary)
                    loadSleepHistory(7)
                }
            }
        }
        btnTab30d = TextView(this).apply {
            text = "30D"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(13f), dp(6f), dp(13f), dp(6f))
            setOnClickListener {
                if (periodDays != 30) {
                    periodDays = 30
                    updateTabStyles(indigo, textSecondary)
                    loadSleepHistory(30)
                }
            }
        }
        pillGroup.addView(btnTab7d)
        pillGroup.addView(btnTab30d)
        updateTabStyles(indigo, textSecondary)
        topBar.addView(pillGroup)
        root.addView(topBar)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(4f), dp(16f), dp(28f))
        }

        // 2x2 Clinical KPI Grid
        fun buildKpiCard(title: String, accent: Int, marginEndDp: Float): Pair<LinearLayout, TextView> {
            val tvVal = TextView(this).apply {
                text = "—"
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(accent)
                setPadding(0, dp(4f), 0, 0)
            }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(14f).toFloat()
                    setColor(surfaceColor)
                    setStroke(dp(1f), borderColor)
                }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(marginEndDp)
                }
                addView(TextView(this@EliteSleepHistoryActivity).apply {
                    text = title
                    textSize = 10f
                    typeface = Typeface.DEFAULT_BOLD
                    letterSpacing = 0.05f
                    setTextColor(textSecondary)
                })
                addView(tvVal)
            }
            return card to tvVal
        }

        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8f) }
        }
        val (cAvg, tvAvg) = buildKpiCard("AVG EFFECTIVE SLEEP", emerald, 8f)
        tvAvgSleep = tvAvg
        val (cGrade, tvGr) = buildKpiCard("CLINICAL RECOVERY", indigo, 0f)
        tvClinicalGrade = tvGr
        row1.addView(cAvg)
        row1.addView(cGrade)
        content.addView(row1)

        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12f) }
        }
        val (cEff, tvEf) = buildKpiCard("AVG EFFICIENCY", Color.parseColor("#38BDF8"), 8f)
        tvAvgEfficiency = tvEf
        val (cNights, tvNi) = buildKpiCard("TRACKED NIGHTS", Color.parseColor("#A78BFA"), 0f)
        tvTrackedNights = tvNi
        row2.addView(cEff)
        row2.addView(cNights)
        content.addView(row2)

        // Interactive Non-Overlapping Sleep Duration & Grade Chart Card
        val chartCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(14f), dp(14f), dp(14f))
            background = GradientDrawable().apply {
                cornerRadius = dp(16f).toFloat()
                setColor(surfaceColor)
                setStroke(dp(1f), borderColor)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12f) }
        }

        val chartHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        chartHeader.addView(TextView(this).apply {
            text = "Nightly Sleep Architecture"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textPrimary)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        chartHeader.addView(TextView(this).apply {
            text = "7.5h clinical goal"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(indigo)
        })
        chartCard.addView(chartHeader)

        tvChartCallout = TextView(this).apply {
            text = "Tap or drag across bars to inspect any night"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(emerald)
            setPadding(0, dp(4f), 0, dp(6f))
        }
        chartCard.addView(tvChartCallout)

        sleepChartView = InteractiveSleepChartView(this, textSecondary, borderColor).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(196f)
            )
            onNightSelected = { dk, res ->
                val dateLabel = formatFriendlyDateKey(dk)
                if (res != null && res.hasValidSleep) {
                    val awPart = if (res.awakenings.isNotEmpty()) " · ${res.awakenings.size} wake-up" else ""
                    tvChartCallout.text = "$dateLabel: ${SmartSleepEngine.formatDuration(res.effectiveSleepMs)} · Grade ${res.grade} (${res.qualityScore}/100) · ${res.efficiencyPct}%$awPart"
                } else {
                    tvChartCallout.text = "$dateLabel: No overnight record — tap '+ Log a Night Manually' below"
                }
            }
        }
        chartCard.addView(sleepChartView)

        tvDebtLine = TextView(this).apply {
            text = ""
            textSize = 11.5f
            setTextColor(textSecondary)
            setPadding(0, dp(8f), 0, 0)
        }
        chartCard.addView(tvDebtLine)
        content.addView(chartCard)

        // Manual Log Button
        val btnLogManual = Button(this).apply {
            text = "+ Log or Adjust a Night Manually"
            isAllCaps = false
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(14f).toFloat()
                setColor(Color.parseColor("#4F46E5"))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48f)
            ).apply { bottomMargin = dp(16f) }
            setOnClickListener {
                showManualSleepLoggerDialog(Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis, null)
            }
        }
        content.addView(btnLogManual)

        // Night-by-Night Audit Header + Container
        content.addView(TextView(this).apply {
            text = "NIGHT-BY-NIGHT CLINICAL LOG (TAP TO EDIT)"
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.05f
            setTextColor(textSecondary)
            setPadding(0, 0, 0, dp(8f))
        })

        llNightRows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        content.addView(llNightRows)

        scroll.addView(content)
        root.addView(scroll)
        setContentView(root)
    }

    private fun updateTabStyles(accent: Int, muted: Int) {
        val activeBg = GradientDrawable().apply {
            cornerRadius = dp(12f).toFloat()
            setColor(ColorUtils.setAlphaComponent(accent, 42))
        }
        if (periodDays == 7) {
            btnTab7d.background = activeBg
            btnTab7d.setTextColor(accent)
            btnTab30d.background = null
            btnTab30d.setTextColor(muted)
        } else {
            btnTab30d.background = activeBg
            btnTab30d.setTextColor(accent)
            btnTab7d.background = null
            btnTab7d.setTextColor(muted)
        }
    }

    private fun loadSleepHistory(days: Int) {
        lifecycleScope.launch {
            val summary = withContext(Dispatchers.IO) {
                SmartSleepEngine.syncRecentNightsArchive(applicationContext, lookbackDays = days.coerceAtMost(14))
                SmartSleepEngine.getPeriodRecoverySummary(applicationContext, periodDays = days)
            }
            if (isFinishing || isDestroyed) return@launch
            currentSummary = summary
            bindSummary(summary)
        }
    }

    private fun bindSummary(summary: SmartSleepEngine.PeriodSleepRecoverySummary) {
        val startDk = summary.dailyNights.firstOrNull()?.first
        val endDk = summary.dailyNights.lastOrNull()?.first
        if (startDk != null && endDk != null) {
            tvDateRange.text = "${formatShortMonthDay(startDk)} – ${formatShortMonthDay(endDk)}"
        }

        if (summary.trackedNights > 0) {
            tvAvgSleep.text = SmartSleepEngine.formatDuration(summary.avgEffectiveSleepMs)
            tvClinicalGrade.text = "${summary.grade} · ${summary.avgRecoveryScore}/100"
            tvAvgEfficiency.text = "${summary.avgEfficiencyPct}%"
            tvTrackedNights.text = "${summary.trackedNights} / ${summary.periodDays}"
            val debtStr = if (summary.sleepDebtMinutes >= 30) {
                "Cumulative Sleep Debt: ${SmartSleepEngine.formatDuration(summary.sleepDebtMinutes * 60_000L)}"
            } else {
                "Sleep Debt: Minimal — Well Rested"
            }
            tvDebtLine.text = "${summary.consistencyLabel} · $debtStr"
        } else {
            tvAvgSleep.text = "—"
            tvClinicalGrade.text = "No Data"
            tvAvgEfficiency.text = "—"
            tvTrackedNights.text = "0 / ${summary.periodDays}"
            tvDebtLine.text = "Overnight sleep is archived automatically each morning, or log a night manually below."
        }

        sleepChartView.setNights(summary.dailyNights)

        val latestValid = summary.dailyNights.lastOrNull { it.second?.hasValidSleep == true }
        if (latestValid != null && latestValid.second != null) {
            val res = latestValid.second!!
            tvChartCallout.text = "${formatFriendlyDateKey(latestValid.first)}: ${SmartSleepEngine.formatDuration(res.effectiveSleepMs)} · Grade ${res.grade} (${res.qualityScore}/100) · ${res.efficiencyPct}% eff"
        }

        renderNightList(summary.dailyNights)
    }

    private fun renderNightList(nights: List<Pair<String, SmartSleepEngine.SleepDetectionResult?>>) {
        llNightRows.removeAllViews()
        val isLight = UiThemePrefs.isWhiteBackgroundActive(this)
        val surfaceColor = if (isLight) Color.WHITE else Color.parseColor("#111824")
        val borderColor = if (isLight) Color.parseColor("#E2E8F0") else Color.parseColor("#1E2A3B")
        val textPrimary = if (isLight) Color.parseColor("#0F172A") else Color.parseColor("#F1F5F9")
        val textSecondary = if (isLight) Color.parseColor("#64748B") else Color.parseColor("#8995A3")

        // Newest -> Oldest; show tracked nights first or all last 7 days
        val displayItems = if (periodDays <= 7) {
            nights.reversed()
        } else {
            val tracked = nights.reversed().filter { it.second?.hasValidSleep == true }
            if (tracked.isNotEmpty()) tracked else nights.reversed().take(7)
        }

        for ((dk, res) in displayItems) {
            val dayStartMs = DisciplineDataRepository.startOfDayMs(dk)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(13f).toFloat()
                    setColor(surfaceColor)
                    setStroke(dp(1f), borderColor)
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8f) }
                setOnClickListener { showManualSleepLoggerDialog(dayStartMs, res) }
            }

            val leftCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            leftCol.addView(TextView(this).apply {
                text = formatFriendlyDateKey(dk)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(textPrimary)
            })
            val subText = if (res != null && res.hasValidSleep) {
                val awStr = if (res.awakenings.isNotEmpty()) " · ${res.awakenings.size} wake-up" else ""
                "${SmartSleepEngine.formatClock(res.bedtimeMs)} → ${SmartSleepEngine.formatClock(res.wakeTimeMs)} · ${res.efficiencyPct}% eff$awStr"
            } else {
                "No overnight record · Tap to log manually"
            }
            leftCol.addView(TextView(this).apply {
                text = subText
                textSize = 11.5f
                setTextColor(textSecondary)
                setPadding(0, dp(2f), 0, 0)
            })
            row.addView(leftCol)

            if (res != null && res.hasValidSleep) {
                val accent = gradeAccent(res.qualityScore)
                val badge = TextView(this).apply {
                    text = "${SmartSleepEngine.formatDuration(res.effectiveSleepMs)} · ${res.grade}"
                    textSize = 12.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(accent)
                    setPadding(dp(10f), dp(5f), dp(10f), dp(5f))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(10f).toFloat()
                        setColor(ColorUtils.setAlphaComponent(accent, 26))
                        setStroke(dp(1f), ColorUtils.setAlphaComponent(accent, 110))
                    }
                }
                row.addView(badge)
            } else {
                row.addView(TextView(this).apply {
                    text = "+ Log"
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.parseColor("#818CF8"))
                })
            }

            llNightRows.addView(row)
        }
    }

    private fun showManualSleepLoggerDialog(
        targetDayStartMs: Long,
        existing: SmartSleepEngine.SleepDetectionResult?
    ) {
        val defaultBed = existing?.takeIf { it.hasValidSleep }?.bedtimeMs
            ?: (targetDayStartMs - 1L * 3_600_000L) // 11:00 PM prev night
        val defaultWake = existing?.takeIf { it.hasValidSleep }?.wakeTimeMs
            ?: (targetDayStartMs + 6L * 3_600_000L + 30L * 60_000L) // 6:30 AM
        var bedMs = defaultBed
        var wakeMs = defaultWake
        var awakeMins = ((existing?.awakeTotalMs ?: 0L) / 60_000L).toInt().coerceIn(0, 120)

        val dateLabel = formatFriendlyDateKey(DisciplineDataRepository.dateKeyForMs(targetDayStartMs))
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(18f), dp(20f), dp(18f))
            background = GradientDrawable().apply {
                cornerRadius = dp(20f).toFloat()
                setColor(Color.parseColor("#111824"))
                setStroke(dp(1.2f), Color.parseColor("#2A364F"))
            }
        }

        container.addView(TextView(this).apply {
            text = "Log Sleep · $dateLabel"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#F1F5F9"))
        })
        container.addView(TextView(this).apply {
            text = "Fine-tune bedtime, wake time, and any mid-night awake minutes"
            textSize = 12f
            setTextColor(Color.parseColor("#8995A3"))
            setPadding(0, dp(4f), 0, dp(14f))
        })

        val tvBed = TextView(this)
        val tvWake = TextView(this)
        val tvSummary = TextView(this)

        fun refreshLabels() {
            if (wakeMs <= bedMs) wakeMs = bedMs + 6L * 3_600_000L
            tvBed.text = "Bedtime:  ${SmartSleepEngine.formatClock(bedMs)}  (Tap to change)"
            tvWake.text = "Wake-up:  ${SmartSleepEngine.formatClock(wakeMs)}  (Tap to change)"
            val effMs = (wakeMs - bedMs - awakeMins * 60_000L).coerceAtLeast(30L * 60_000L)
            tvSummary.text = "Effective Sleep: ${SmartSleepEngine.formatDuration(effMs)} (Awake: ${awakeMins}m)"
        }

        fun stylePickerRow(tv: TextView, onClick: () -> Unit) {
            tv.textSize = 13.5f
            tv.typeface = Typeface.DEFAULT_BOLD
            tv.setTextColor(Color.parseColor("#F1F5F9"))
            tv.setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            tv.background = GradientDrawable().apply {
                cornerRadius = dp(12f).toFloat()
                setColor(Color.parseColor("#182232"))
                setStroke(dp(1f), Color.parseColor("#2A364F"))
            }
            tv.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8f) }
            tv.setOnClickListener { onClick() }
        }

        stylePickerRow(tvBed) {
            val c = Calendar.getInstance().apply { timeInMillis = bedMs }
            TimePickerDialog(this, { _, h, m ->
                val newCal = Calendar.getInstance().apply {
                    timeInMillis = targetDayStartMs
                    if (h >= 18) add(Calendar.DAY_OF_YEAR, -1)
                    set(Calendar.HOUR_OF_DAY, h)
                    set(Calendar.MINUTE, m)
                    set(Calendar.SECOND, 0)
                }
                bedMs = newCal.timeInMillis
                refreshLabels()
            }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), false).show()
        }
        stylePickerRow(tvWake) {
            val c = Calendar.getInstance().apply { timeInMillis = wakeMs }
            TimePickerDialog(this, { _, h, m ->
                val newCal = Calendar.getInstance().apply {
                    timeInMillis = targetDayStartMs
                    set(Calendar.HOUR_OF_DAY, h)
                    set(Calendar.MINUTE, m)
                    set(Calendar.SECOND, 0)
                }
                wakeMs = newCal.timeInMillis
                refreshLabels()
            }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), false).show()
        }
        container.addView(tvBed)
        container.addView(tvWake)

        // Quick Awake Minutes Chips
        val awakeScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10f) }
        }
        val awakeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val chipViews = mutableListOf<Pair<Int, TextView>>()
        fun styleAwakeChips() {
            for ((mins, tv) in chipViews) {
                val sel = mins == awakeMins
                tv.setTextColor(if (sel) Color.parseColor("#080D12") else Color.parseColor("#CBD5E1"))
                tv.background = GradientDrawable().apply {
                    cornerRadius = dp(10f).toFloat()
                    setColor(if (sel) Color.parseColor("#818CF8") else Color.parseColor("#182232"))
                }
            }
        }
        for (m in listOf(0, 5, 10, 15, 25, 40)) {
            val chip = TextView(this).apply {
                text = if (m == 0) "0m awake" else "${m}m awake"
                textSize = 11.5f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(11f), dp(7f), dp(11f), dp(7f))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(6f) }
                setOnClickListener {
                    awakeMins = m
                    styleAwakeChips()
                    refreshLabels()
                }
            }
            chipViews.add(m to chip)
            awakeRow.addView(chip)
        }
        styleAwakeChips()
        awakeScroll.addView(awakeRow)
        container.addView(awakeScroll)

        tvSummary.textSize = 13f
        tvSummary.typeface = Typeface.DEFAULT_BOLD
        tvSummary.setTextColor(Color.parseColor("#16D99A"))
        tvSummary.setPadding(0, dp(2f), 0, dp(14f))
        container.addView(tvSummary)
        refreshLabels()

        val dialog = AlertDialog.Builder(this).setView(container).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnRow.addView(Button(this).apply {
            text = "Cancel"
            isAllCaps = false
            setTextColor(Color.parseColor("#94A3B8"))
            background = GradientDrawable().apply {
                cornerRadius = dp(12f).toFloat()
                setColor(Color.parseColor("#182232"))
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(44f), 1f).apply { marginEnd = dp(8f) }
            setOnClickListener { dialog.dismiss() }
        })
        btnRow.addView(Button(this).apply {
            text = "Save & Log"
            isAllCaps = false
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(12f).toFloat()
                setColor(Color.parseColor("#4F46E5"))
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(44f), 1.3f)
            setOnClickListener {
                val b = bedMs
                val w = wakeMs
                val awMins = awakeMins.coerceAtLeast(0)
                val targetDateKey = DisciplineDataRepository.dateKeyForMs(targetDayStartMs)
                dialog.dismiss()
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        SmartSleepEngine.saveManualAdjustment(
                            context = applicationContext,
                            dateKey = targetDateKey,
                            bedtimeMs = b,
                            wakeTimeMs = w,
                            awakeMinutes = awMins
                        )
                        val updated = SmartSleepEngine.analyzeSleepForDate(
                            applicationContext,
                            targetDayStartMs
                        )
                        SmartSleepEngine.logSleepToTimeline(applicationContext, updated, targetDayStartMs)
                    }
                    Toast.makeText(this@EliteSleepHistoryActivity, "Sleep saved & recovery updated", Toast.LENGTH_SHORT).show()
                    loadSleepHistory(periodDays)
                }
            }
        })
        container.addView(btnRow)
        dialog.show()
    }

    private fun gradeAccent(score: Int): Int = when {
        score >= 84 -> Color.parseColor("#16D99A")
        score >= 68 -> Color.parseColor("#818CF8")
        score >= 56 -> Color.parseColor("#F59E0B")
        else -> Color.parseColor("#F87171")
    }

    private fun formatShortMonthDay(dateKey: String): String {
        return try {
            val cal = DisciplineDataRepository.parseDateKeyToCalendar(dateKey)
            SimpleDateFormat("MMM d", Locale.getDefault()).format(cal.time)
        } catch (_: Throwable) {
            dateKey
        }
    }

    private fun formatFriendlyDateKey(dateKey: String): String {
        val today = DisciplineDataRepository.todayKey(this)
        val yesterday = DisciplineDataRepository.addDays(today, -1)
        val short = formatShortMonthDay(dateKey)
        return when (dateKey) {
            today -> "Last Night · $short"
            yesterday -> "Yesterday · $short"
            else -> {
                try {
                    val cal = DisciplineDataRepository.parseDateKeyToCalendar(dateKey)
                    SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(cal.time)
                } catch (_: Throwable) {
                    short
                }
            }
        }
    }

    /**
     * Clean, non-overlapping interactive 7D / 30D Sleep Bar Chart.
     * - Smart X-axis tick interval (max ~6 labels so 30D dates never overlap)
     * - 7.5h dashed clinical reference line
     * - Tap/drag selection highlight + clean duration labels (7D only) so text never clips!
     */
    private inner class InteractiveSleepChartView(
        context: Context,
        private val mutedTextColor: Int,
        private val gridColor: Int
    ) : View(context) {

        private var nights: List<Pair<String, SmartSleepEngine.SleepDetectionResult?>> = emptyList()
        private var selectedIdx: Int = -1
        var onNightSelected: ((String, SmartSleepEngine.SleepDetectionResult?) -> Unit)? = null

        private val density = resources.displayMetrics.density
        private val scaledDensity = resources.displayMetrics.scaledDensity

        private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val selectedStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            color = Color.WHITE
        }
        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f * density
            color = gridColor
        }
        private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.4f * density
            color = ColorUtils.setAlphaComponent(Color.parseColor("#818CF8"), 170)
            pathEffect = DashPathEffect(floatArrayOf(5f * density, 4f * density), 0f)
        }
        private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 10f * scaledDensity
            color = mutedTextColor
            textAlign = Paint.Align.CENTER
        }
        private val yAxisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 9.5f * scaledDensity
            color = mutedTextColor
            textAlign = Paint.Align.LEFT
        }
        private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 9.5f * scaledDensity
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        private val rect = RectF()

        fun setNights(list: List<Pair<String, SmartSleepEngine.SleepDetectionResult?>>) {
            nights = list
            selectedIdx = list.indexOfLast { it.second?.hasValidSleep == true }.takeIf { it >= 0 } ?: list.lastIndex
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (nights.isEmpty() || width <= 0 || height <= 0) return

            val padLeft = 28f * density
            val padRight = 10f * density
            val padTop = 18f * density
            val padBottom = 24f * density
            val chartW = (width - padLeft - padRight).coerceAtLeast(1f)
            val chartH = (height - padTop - padBottom).coerceAtLeast(30f * density)
            val baseY = height - padBottom

            val maxHours = max(10f, (nights.mapNotNull { it.second?.effectiveSleepMs }.maxOrNull() ?: 0L) / 3_600_000f + 0.8f)

            // Horizontal Y-axis gridlines (4h, 8h)
            for (hMark in listOf(4f, 8f)) {
                if (hMark <= maxHours) {
                    val y = baseY - (hMark / maxHours) * chartH
                    canvas.drawLine(padLeft, y, width - padRight, y, gridPaint)
                    canvas.drawText("${hMark.toInt()}h", 2f * density, y + 3.5f * density, yAxisPaint)
                }
            }

            // 7.5h AASM Clinical Target dashed line
            val targetY = baseY - (7.5f / maxHours) * chartH
            canvas.drawLine(padLeft, targetY, width - padRight, targetY, targetPaint)

            val n = nights.size
            val slotW = chartW / n
            val barW = (slotW * 0.62f).coerceIn(4f * density, 22f * density)
            // Keep at most 6 evenly spaced X-axis labels so 30D dates NEVER overlap
            val labelStep = if (n <= 7) 1 else max(5, (n / 5))

            nights.forEachIndexed { idx, (dk, night) ->
                val cx = padLeft + idx * slotW + slotW / 2f
                val isSelected = idx == selectedIdx

                if (night == null || !night.hasValidSleep) {
                    barPaint.color = ColorUtils.setAlphaComponent(mutedTextColor, 85)
                    canvas.drawCircle(cx, baseY - 3f * density, 2.2f * density, barPaint)
                } else {
                    val hrs = (night.effectiveSleepMs / 3_600_000f).coerceIn(0.4f, maxHours)
                    val topY = baseY - (hrs / maxHours) * chartH
                    val color = gradeAccent(night.qualityScore)
                    barPaint.color = if (isSelected) color else ColorUtils.setAlphaComponent(color, 215)
                    rect.set(cx - barW / 2f, topY, cx + barW / 2f, baseY)
                    canvas.drawRoundRect(rect, 4f * density, 4f * density, barPaint)
                    if (isSelected) {
                        canvas.drawRoundRect(rect, 4f * density, 4f * density, selectedStrokePaint)
                    }

                    // Show crisp short duration labels ONLY on 7D view so nothing ever clips or collides
                    if (n <= 7) {
                        valuePaint.color = color
                        val totalM = (night.effectiveSleepMs / 60_000L).toInt()
                        val lbl = "${totalM / 60}h${if (totalM % 60 > 0) "${totalM % 60}" else ""}"
                        canvas.drawText(lbl, cx, (topY - 4f * density).coerceAtLeast(11f * density), valuePaint)
                    }
                }

                val showAxisLabel = if (n <= 7) {
                    true
                } else {
                    (idx % labelStep == 0 && (n - 1 - idx) >= 3) || idx == n - 1
                }
                if (showAxisLabel) {
                    val cal = DisciplineDataRepository.parseDateKeyToCalendar(dk)
                    val fmt = if (n <= 7) "EEE" else "MMM d"
                    val lbl = SimpleDateFormat(fmt, Locale.getDefault()).format(cal.time)
                    axisPaint.color = if (isSelected) Color.parseColor("#F1F5F9") else mutedTextColor
                    val safeX = cx.coerceIn(padLeft + 14f * density, width - padRight - 16f * density)
                    canvas.drawText(lbl, safeX, height - 5f * density, axisPaint)
                }
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (nights.isEmpty()) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    val padLeft = 28f * density
                    val padRight = 10f * density
                    val chartW = (width - padLeft - padRight).coerceAtLeast(1f)
                    val ratio = ((event.x - padLeft) / chartW).coerceIn(0f, 0.9999f)
                    val idx = (ratio * nights.size).toInt().coerceIn(0, nights.lastIndex)
                    if (idx != selectedIdx) {
                        selectedIdx = idx
                        val (dk, night) = nights[idx]
                        onNightSelected?.invoke(dk, night)
                        invalidate()
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }
}
