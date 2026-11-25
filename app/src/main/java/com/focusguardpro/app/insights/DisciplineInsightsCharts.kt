package com.focusguardpro.app.insights

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.focusguardpro.app.DisciplineDataRepository
import com.focusguardpro.app.DisciplineTheme
import com.focusguardpro.app.SmartSleepEngine
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.max

/**
 * Compact, transparent custom Canvas visualizations & metric cards for Focus Elite's
 * Discipline Insights section (Weekly 7D & Monthly 30D).
 *
 * Strictly honest data rendering:
 * - Missing step records render as subtle hollow ticks ("No record"), never as 0-step failures.
 * - Productive vs. Distracted split renders ONLY for days with genuine UsageEvents telemetry.
 * - No opaque or arbitrary combined score is invented.
 */
object DisciplineInsightsCharts {

    private fun dp(context: Context, v: Float): Int =
        (v * context.resources.displayMetrics.density).toInt()

    fun buildDisciplineInsightsSection(
        context: Context,
        report: DisciplineDataRepository.PeriodInsightsReport,
        onOpenCalendar: () -> Unit,
        onOpenTasks: () -> Unit,
        onOpenJournal: () -> Unit,
        onOpenNotebook: (() -> Unit)? = null
    ): View {
        val theme = DisciplineTheme.resolve(context)
        val d = context.resources.displayMetrics.density
        val periodLabel = if (report.periodDays <= 7) "Last 7 Days" else "Last 30 Days"

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(context, 12f) }
        }

        // Section Header
        val secHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(context, 2f), 0, dp(context, 8f))
        }
        secHeader.addView(TextView(context).apply {
            text = "DISCIPLINE CONSISTENCY · ${periodLabel.uppercase(Locale.US)}"
            textSize = 10.5f
            setTypeface(null, Typeface.BOLD)
            letterSpacing = 0.05f
            setTextColor(theme.textSecondary)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        secHeader.addView(TextView(context).apply {
            text = "Recorded data only"
            textSize = 10.5f
            setTextColor(theme.accent)
        })
        container.addView(secHeader)

        // 1. Compact 2x2 Summary KPI Grid (Tasks, Journal, Workouts, Focus Sessions)
        val row1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(context, 7f) }
        }

        val taskRateStr = if (report.totalTasksScheduled > 0) {
            val pct = (report.totalTasksCompleted * 100) / report.totalTasksScheduled
            "$pct% (${report.totalTasksCompleted}/${report.totalTasksScheduled})"
        } else {
            "No tasks"
        }
        val taskSub = if (report.overduePendingCount > 0) {
            "${report.overduePendingCount} overdue pending"
        } else if (report.totalTasksScheduled > 0) {
            "0 overdue · on track"
        } else {
            "Tap to plan tasks"
        }
        row1.addView(buildKpiCard(
            context, theme, d,
            title = "TASK COMPLETION",
            value = taskRateStr,
            subtitle = taskSub,
            accent = if (report.overduePendingCount > 0) theme.overdueCoral else theme.taskBlue,
            marginEndPx = dp(context, 7f),
            onClick = onOpenTasks
        ))

        val journalSub = buildString {
            append("${report.totalJournalWords} words")
            report.avgEnergyRating?.let {
                append(" · E${String.format(Locale.US, "%.1f", it)}/5")
            }
            report.topMood?.let {
                append(" · $it")
            }
        }
        row1.addView(buildKpiCard(
            context, theme, d,
            title = "JOURNAL CONSISTENCY",
            value = "${report.journalDaysCount} / ${report.periodDays} days",
            subtitle = if (report.journalDaysCount > 0) journalSub else "Tap to write entry",
            accent = theme.diaryViolet,
            marginEndPx = 0,
            onClick = onOpenJournal
        ))
        container.addView(row1)

        val row2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(context, 8f) }
        }

        val sr = report.sleepRecovery
        val sleepValue = if (sr != null && sr.trackedNights > 0) {
            "Grade ${sr.grade} · ${sr.avgRecoveryScore}/100"
        } else {
            "No sleep data"
        }
        val sleepSub = if (sr != null && sr.trackedNights > 0) {
            "Avg ${SmartSleepEngine.formatDuration(sr.avgEffectiveSleepMs)} · ${sr.avgEfficiencyPct}% eff"
        } else {
            "Tap to open Notebook"
        }
        val sleepAccent = when {
            sr == null || sr.trackedNights == 0 -> Color.parseColor("#818CF8")
            sr.avgRecoveryScore >= 84 -> theme.stepGreen
            sr.avgRecoveryScore >= 68 -> Color.parseColor("#818CF8")
            sr.avgRecoveryScore >= 56 -> Color.parseColor("#F59E0B")
            else -> theme.overdueCoral
        }
        row2.addView(buildKpiCard(
            context, theme, d,
            title = "SLEEP & RECOVERY",
            value = sleepValue,
            subtitle = sleepSub,
            accent = sleepAccent,
            marginEndPx = dp(context, 7f),
            onClick = onOpenNotebook ?: onOpenCalendar
        ))

        val focusValue = if (report.focusSessionMinutes > 0) {
            val h = report.focusSessionMinutes / 60
            val m = report.focusSessionMinutes % 60
            if (h > 0) "${h}h ${m}m" else "${m}m"
        } else {
            "0m"
        }
        val focusSub = if (report.focusSessionsCompleted > 0) {
            "${report.focusSessionsCompleted} completed session${if (report.focusSessionsCompleted == 1) "" else "s"}"
        } else {
            "No deep-work sessions"
        }
        row2.addView(buildKpiCard(
            context, theme, d,
            title = "FOCUS SESSIONS",
            value = focusValue,
            subtitle = focusSub,
            accent = theme.accent,
            marginEndPx = 0,
            onClick = null
        ))
        container.addView(row2)

        // 2. Fitness Tracker Chart Card (Daily Steps + Genuine Logged Exercise Sets; walking excluded from sets)
        val unrecordedSteps = (report.periodDays - report.recordedStepDays).coerceAtLeast(0)
        val fitnessHeadline = if (report.recordedStepDays > 0 || report.totalWorkoutSets > 0) {
            "${report.stepGoalMetDays}/${report.recordedStepDays.coerceAtLeast(1)}d goal · ${report.totalWorkoutSets} sets"
        } else {
            "No fitness telemetry yet"
        }
        val fitnessMeta = buildString {
            report.avgRecordedSteps?.let {
                append("Avg ${String.format(Locale.US, "%,d", it)} steps/day")
            } ?: append("Target: ${String.format(Locale.US, "%,d", report.stepGoal)} steps")
            append(" · ${report.workoutActiveDays}d exercise (walks excluded)")
            if (unrecordedSteps > 0) {
                append(" · ${unrecordedSteps}d unrecorded")
            }
        }
        val stepCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 12f), dp(context, 10f), dp(context, 12f), dp(context, 10f))
            background = DisciplineTheme.cardDrawable(theme.surface, theme.border, 12f, d)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(context, 8f) }
        }
        stepCard.addView(buildChartHeader(context, theme, "Fitness Tracker · Steps & Sets", fitnessHeadline, theme.stepGreen))
        stepCard.addView(TextView(context).apply {
            text = fitnessMeta
            textSize = 11f
            setTextColor(theme.textSecondary)
            setPadding(0, dp(context, 2f), 0, dp(context, 8f))
        })
        val setsByDate = report.dailyExerciseSets.toMap()
        stepCard.addView(StepConsistencyBarView(context, theme, report.dailySteps, setsByDate, report.stepGoal).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(context, 96f)
            )
        })
        container.addView(stepCard)

        // 2B. Multi-Day Clinical Sleep & Recovery Chart Card (7D / 30D)
        val sleepCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 12f), dp(context, 10f), dp(context, 12f), dp(context, 10f))
            background = DisciplineTheme.cardDrawable(theme.surface, theme.border, 12f, d)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(context, 8f) }
            if (onOpenNotebook != null) {
                setOnClickListener { onOpenNotebook() }
            }
        }
        if (sr != null && sr.trackedNights > 0) {
            val sleepBadge = "Grade ${sr.grade} · ${sr.trackedNights}/${sr.periodDays} nights"
            val debtPart = if (sr.sleepDebtMinutes >= 30) {
                " · Debt ${SmartSleepEngine.formatDuration(sr.sleepDebtMinutes * 60_000L)}"
            } else ""
            val sleepMeta = "Avg ${SmartSleepEngine.formatDuration(sr.avgEffectiveSleepMs)} · ${sr.avgEfficiencyPct}% eff · ${sr.consistencyLabel}$debtPart"
            sleepCard.addView(buildChartHeader(context, theme, "Multi-Day Sleep & Recovery", sleepBadge, sleepAccent))
            sleepCard.addView(TextView(context).apply {
                text = sleepMeta
                textSize = 11f
                setTextColor(theme.textSecondary)
                setPadding(0, dp(context, 2f), 0, dp(context, 8f))
            })
            sleepCard.addView(SleepRecoveryBarView(context, theme, sr.dailyNights).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(context, 96f)
                )
            })
        } else {
            sleepCard.addView(buildChartHeader(
                context, theme,
                "Multi-Day Sleep & Recovery",
                "Waiting for overnight signals",
                Color.parseColor("#818CF8")
            ))
            sleepCard.addView(TextView(context).apply {
                text = "Overnight sleep is archived automatically each morning. Tap to review or log sleep in Daily Notebook."
                textSize = 11.5f
                setTextColor(theme.textSecondary)
                setPadding(0, dp(context, 4f), 0, 0)
            })
        }
        container.addView(sleepCard)

        // 3. Productive vs. Distracted Time Comparison Card (Strictly Real Telemetry Only)
        val prodCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 12f), dp(context, 10f), dp(context, 12f), dp(context, 10f))
            background = DisciplineTheme.cardDrawable(theme.surface, theme.border, 12f, d)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        if (report.telemetrySplitDays > 0) {
            val totalSplit = (report.totalProductiveMins + report.totalDistractedMins).coerceAtLeast(1)
            val prodRatio = (report.totalProductiveMins * 100) / totalSplit
            val prodHrs = "${report.totalProductiveMins / 60}h ${report.totalProductiveMins % 60}m"
            val distHrs = "${report.totalDistractedMins / 60}h ${report.totalDistractedMins % 60}m"

            prodCard.addView(buildChartHeader(
                context, theme,
                "Productive vs. Distracted Time",
                "$prodRatio% productive (${report.telemetrySplitDays}d tracked)",
                theme.accent
            ))
            prodCard.addView(TextView(context).apply {
                text = "Study/Improve: $prodHrs   ·   Distracted: $distHrs"
                textSize = 11.5f
                setTextColor(theme.textSecondary)
                setPadding(0, dp(context, 3f), 0, dp(context, 8f))
            })

            // Stacked horizontal comparison bar
            val barTrack = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(context, 10f)
                ).apply { bottomMargin = dp(context, 8f) }
                background = DisciplineTheme.pillDrawable(theme.surfaceAlt)
                clipToOutline = true
            }
            if (report.totalProductiveMins > 0) {
                barTrack.addView(View(context).apply {
                    setBackgroundColor(theme.stepGreen)
                    layoutParams = LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT,
                        report.totalProductiveMins.toFloat()
                    )
                })
            }
            if (report.totalDistractedMins > 0) {
                barTrack.addView(View(context).apply {
                    setBackgroundColor(theme.overdueCoral)
                    layoutParams = LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT,
                        report.totalDistractedMins.toFloat()
                    )
                })
            }
            prodCard.addView(barTrack)

            // Daily comparative mini-chart
            prodCard.addView(ProductiveDistractedBarView(context, theme, report.productiveVsDistractedDays).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(context, 84f)
                )
            })
        } else {
            prodCard.addView(buildChartHeader(
                context, theme,
                "Productive vs. Distracted Time",
                "No categorized usage yet",
                theme.textSecondary
            ))
            prodCard.addView(TextView(context).apply {
                text = "Grant Usage Access and categorize Study/Distracting apps to view real productive vs. distracted comparisons."
                textSize = 11.5f
                setTextColor(theme.textSecondary)
                setPadding(0, dp(context, 4f), 0, 0)
            })
        }
        container.addView(prodCard)

        return container
    }

    private fun buildKpiCard(
        context: Context,
        theme: DisciplineTheme.Tokens,
        d: Float,
        title: String,
        value: String,
        subtitle: String,
        accent: Int,
        marginEndPx: Int,
        onClick: (() -> Unit)?
    ): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 11f), dp(context, 9f), dp(context, 11f), dp(context, 9f))
            background = DisciplineTheme.cardDrawable(theme.surface, theme.border, 12f, d)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = marginEndPx
            }
            if (onClick != null) {
                setOnClickListener { onClick() }
            }
            addView(TextView(context).apply {
                text = title
                textSize = 9.5f
                setTypeface(null, Typeface.BOLD)
                letterSpacing = 0.04f
                setTextColor(theme.textMuted)
            })
            addView(TextView(context).apply {
                text = value
                textSize = 14.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(accent)
                setPadding(0, dp(context, 3f), 0, 0)
            })
            addView(TextView(context).apply {
                text = subtitle
                textSize = 10.5f
                setTextColor(theme.textSecondary)
                maxLines = 1
                setPadding(0, dp(context, 2f), 0, 0)
            })
        }
    }

    private fun buildChartHeader(
        context: Context,
        theme: DisciplineTheme.Tokens,
        title: String,
        badge: String,
        badgeColor: Int
    ): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = title
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(theme.textPrimary)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(context).apply {
                text = badge
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                setTextColor(badgeColor)
            })
        }
    }

    /**
     * Fitness Tracker bar chart for Weekly/Monthly steps with dashed goal line
     * plus crisp amber markers above days with genuine logged exercise sets (walking excluded).
     */
    private class StepConsistencyBarView(
        context: Context,
        private val theme: DisciplineTheme.Tokens,
        private val data: List<Pair<String, Int?>>,
        private val setsByDate: Map<String, Int>,
        private val goal: Int
    ) : View(context) {

        private val density = resources.displayMetrics.density
        private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val setMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#F59E0B")
        }
        private val goalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.2f * density
            color = ColorUtils.setAlphaComponent(theme.stepGreen, 140)
            pathEffect = DashPathEffect(floatArrayOf(4f * density, 4f * density), 0f)
        }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 9f * resources.displayMetrics.scaledDensity
            color = theme.textMuted
            textAlign = Paint.Align.CENTER
        }
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (data.isEmpty() || width <= 0 || height <= 0) return

            val padTop = 12f * density
            val padBottom = 18f * density
            val chartH = (height - padTop - padBottom).coerceAtLeast(16f * density)
            val baseY = height - padBottom

            val maxSteps = max(goal, data.mapNotNull { it.second }.maxOrNull() ?: goal).coerceAtLeast(1000)

            // Goal reference line
            val goalY = baseY - (goal.toFloat() / maxSteps) * chartH
            canvas.drawLine(0f, goalY, width.toFloat(), goalY, goalPaint)

            val n = data.size
            val slotW = width.toFloat() / n
            val barW = (slotW * 0.62f).coerceIn(3f * density, 16f * density)
            val labelStep = max(1, n / 6)

            data.forEachIndexed { idx, (dk, steps) ->
                val cx = idx * slotW + slotW / 2f
                val genuineSets = setsByDate[dk] ?: 0
                var topY = baseY - 4f * density
                if (steps == null) {
                    // Missing record: subtle muted dot at baseline (distinct from failed goal)
                    barPaint.color = ColorUtils.setAlphaComponent(theme.textMuted, 90)
                    canvas.drawCircle(cx, baseY - 2f * density, 2f * density, barPaint)
                } else {
                    val ratio = (steps.toFloat() / maxSteps).coerceIn(0.04f, 1f)
                    topY = baseY - ratio * chartH
                    barPaint.color = if (steps >= goal) {
                        theme.stepGreen
                    } else {
                        ColorUtils.setAlphaComponent(theme.accent, 175)
                    }
                    rect.set(cx - barW / 2f, topY, cx + barW / 2f, baseY)
                    canvas.drawRoundRect(rect, 3f * density, 3f * density, barPaint)
                }

                // Amber strength badge above bar ONLY on days with genuine ExerciseDao sets
                if (genuineSets > 0) {
                    val dotY = (topY - 5f * density).coerceAtLeast(4f * density)
                    val radius = if (n <= 7) 3.2f * density else 2.2f * density
                    canvas.drawCircle(cx, dotY, radius, setMarkerPaint)
                }

                if (idx % labelStep == 0 || idx == n - 1) {
                    val lbl = formatMiniDay(dk)
                    canvas.drawText(lbl, cx, height - 3f * density, labelPaint)
                }
            }
        }

        private fun formatMiniDay(dk: String): String {
            return try {
                val cal = DisciplineDataRepository.parseDateKeyToCalendar(dk)
                SimpleDateFormat(if (data.size <= 7) "EEE" else "d", Locale.getDefault()).format(cal.time)
            } catch (_: Throwable) {
                dk.takeLast(2)
            }
        }
    }

    /**
     * Multi-day clinical Sleep & Recovery bar chart (7D / 30D) with dashed 7.5h AASM target line,
     * color-coded by clinical recovery grade (A+/A emerald, B+/B indigo, C amber, D/F coral).
     */
    private class SleepRecoveryBarView(
        context: Context,
        private val theme: DisciplineTheme.Tokens,
        private val nights: List<Pair<String, SmartSleepEngine.SleepDetectionResult?>>
    ) : View(context) {

        private val density = resources.displayMetrics.density
        private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.2f * density
            color = ColorUtils.setAlphaComponent(Color.parseColor("#818CF8"), 155)
            pathEffect = DashPathEffect(floatArrayOf(4f * density, 4f * density), 0f)
        }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 9f * resources.displayMetrics.scaledDensity
            color = theme.textMuted
            textAlign = Paint.Align.CENTER
        }
        private val gradePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 8.5f * resources.displayMetrics.scaledDensity
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (nights.isEmpty() || width <= 0 || height <= 0) return

            val padTop = 14f * density
            val padBottom = 18f * density
            val chartH = (height - padTop - padBottom).coerceAtLeast(16f * density)
            val baseY = height - padBottom

            val maxHours = 10f
            val targetHours = 7.5f
            val targetY = baseY - (targetHours / maxHours) * chartH
            canvas.drawLine(0f, targetY, width.toFloat(), targetY, targetPaint)

            val n = nights.size
            val slotW = width.toFloat() / n
            val barW = (slotW * 0.60f).coerceIn(3f * density, 16f * density)
            val labelStep = max(1, n / 6)

            nights.forEachIndexed { idx, (dk, night) ->
                val cx = idx * slotW + slotW / 2f
                if (night == null || !night.hasValidSleep) {
                    barPaint.color = ColorUtils.setAlphaComponent(theme.textMuted, 85)
                    canvas.drawCircle(cx, baseY - 2f * density, 2f * density, barPaint)
                } else {
                    val hrs = (night.effectiveSleepMs / 3_600_000f).coerceIn(0.4f, maxHours)
                    val topY = baseY - (hrs / maxHours) * chartH
                    val color = when {
                        night.qualityScore >= 84 -> theme.stepGreen
                        night.qualityScore >= 68 -> Color.parseColor("#818CF8")
                        night.qualityScore >= 56 -> Color.parseColor("#F59E0B")
                        else -> theme.overdueCoral
                    }
                    barPaint.color = color
                    rect.set(cx - barW / 2f, topY, cx + barW / 2f, baseY)
                    canvas.drawRoundRect(rect, 3f * density, 3f * density, barPaint)

                    if (n <= 7) {
                        gradePaint.color = color
                        canvas.drawText(night.grade, cx, (topY - 3f * density).coerceAtLeast(9f * density), gradePaint)
                    }
                }

                if (idx % labelStep == 0 || idx == n - 1) {
                    val cal = DisciplineDataRepository.parseDateKeyToCalendar(dk)
                    val lbl = SimpleDateFormat(if (n <= 7) "EEE" else "d", Locale.getDefault()).format(cal.time)
                    canvas.drawText(lbl, cx, height - 3f * density, labelPaint)
                }
            }
        }
    }

    /**
     * Side-by-side daily comparison bars for Productive (emerald) vs. Distracted (coral) minutes.
     */
    private class ProductiveDistractedBarView(
        context: Context,
        private val theme: DisciplineTheme.Tokens,
        private val days: List<Triple<String, Int, Int>>
    ) : View(context) {

        private val density = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 9f * resources.displayMetrics.scaledDensity
            color = theme.textMuted
            textAlign = Paint.Align.CENTER
        }
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (days.isEmpty() || width <= 0 || height <= 0) return

            val padTop = 6f * density
            val padBottom = 18f * density
            val chartH = (height - padTop - padBottom).coerceAtLeast(14f * density)
            val baseY = height - padBottom

            val maxMins = days.maxOf { max(it.second, it.third) }.coerceAtLeast(60)
            val n = days.size
            val slotW = width.toFloat() / n
            val subW = (slotW * 0.28f).coerceIn(3f * density, 11f * density)
            val gap = 2f * density

            days.forEachIndexed { idx, (dk, prodM, distM) ->
                val cx = idx * slotW + slotW / 2f

                // Productive bar (left)
                if (prodM > 0) {
                    val h = (prodM.toFloat() / maxMins).coerceIn(0.05f, 1f) * chartH
                    paint.color = theme.stepGreen
                    rect.set(cx - gap / 2f - subW, baseY - h, cx - gap / 2f, baseY)
                    canvas.drawRoundRect(rect, 2.5f * density, 2.5f * density, paint)
                }
                // Distracted bar (right)
                if (distM > 0) {
                    val h = (distM.toFloat() / maxMins).coerceIn(0.05f, 1f) * chartH
                    paint.color = theme.overdueCoral
                    rect.set(cx + gap / 2f, baseY - h, cx + gap / 2f + subW, baseY)
                    canvas.drawRoundRect(rect, 2.5f * density, 2.5f * density, paint)
                }

                val cal = DisciplineDataRepository.parseDateKeyToCalendar(dk)
                val lbl = SimpleDateFormat(if (n <= 7) "EEE" else "d", Locale.getDefault()).format(cal.time)
                canvas.drawText(lbl, cx, height - 3f * density, labelPaint)
            }
        }
    }
}
