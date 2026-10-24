package com.focusguardpro.app

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Custom Android Canvas View that draws the Dual Concentric Neon Rings
 * visualizing Daily Screen Time (Outer Ring) vs. Daily Physical Steps (Inner Ring)
 * with live Health Connect integration.
 */
class DualNeonRingsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val viewScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var syncJob: Job? = null
    private var sweepAnimator: ValueAnimator? = null

    var animProgress: Float = 1f
        private set

    var screenUsedMinutes: Int = 0
        private set
    var screenBudgetMinutes: Int = 180
        private set
    var dailySteps: Int = 0
        private set
    var dailyStepGoal: Int = 8000
        private set
    var isHealthConnectLinked: Boolean = false
        private set
    var lastSyncSourceLabel: String = "Sensor + Health Connect"
        private set

    private val outerRect = RectF()
    private val innerRect = RectF()
    private val cardRect = RectF()
    private val badgeRect = RectF()

    private val cardBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#11131A")
    }
    private val cardBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val badgeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private val telemetryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == FocusWidgetService.ACTION_TELEMETRY_UPDATED) {
                loadCachedTelemetry()
                invalidate()
            }
        }
    }

    init {
        loadCachedTelemetry()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        try {
            val filter = IntentFilter(FocusWidgetService.ACTION_TELEMETRY_UPDATED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(telemetryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(telemetryReceiver, filter)
            }
        } catch (_: Throwable) {}
        refreshTelemetryAndSyncHealthConnect(animate = true)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        try {
            context.unregisterReceiver(telemetryReceiver)
        } catch (_: Throwable) {}
        sweepAnimator?.cancel()
        viewScope.coroutineContext.cancelChildren()
    }

    fun loadCachedTelemetry() {
        val screenMs = try {
            UsageStatsHelper.getTodayScreenTimeMillis(context)
        } catch (_: Throwable) {
            0L
        }
        screenUsedMinutes = (screenMs / 60000L).toInt().coerceAtLeast(0)
        screenBudgetMinutes = CommandDeckWidgetPrefs.getScreenTimeBudgetMinutes(context).coerceAtLeast(15)
        dailySteps = try {
            StepPrefs.getDailySteps(context).toInt().coerceAtLeast(0)
        } catch (_: Throwable) {
            0
        }
        dailyStepGoal = CommandDeckWidgetPrefs.getDailyStepGoal(context).coerceAtLeast(1000)
    }

    /**
     * Queries Health Connect for today's verified step count and updates local cache + Canvas.
     */
    fun refreshTelemetryAndSyncHealthConnect(
        animate: Boolean = true,
        onComplete: ((steps: Int, screenMins: Int, hcLinked: Boolean) -> Unit)? = null
    ) {
        loadCachedTelemetry()
        if (animate) {
            animateSweep()
        } else {
            invalidate()
        }

        syncJob?.cancel()
        syncJob = viewScope.launch {
            val result = withContext(Dispatchers.IO) {
                var hcPerm = false
                var hcSteps: Long? = null
                try {
                    hcPerm = HealthConnectManager.hasStepsPermission(context)
                    if (hcPerm) {
                        hcSteps = HealthConnectManager.getTodaySteps(context)
                    }
                } catch (_: Throwable) {}

                val currentCached = try {
                    StepPrefs.getDailySteps(context)
                } catch (_: Throwable) {
                    0L
                }

                val resolvedSteps = if (hcSteps != null && hcSteps > 0L) {
                    StepPrefs.setDailySteps(context, hcSteps.toInt())
                    hcSteps.toInt()
                } else {
                    currentCached.toInt()
                }

                val screenMs = try {
                    UsageStatsHelper.getTodayScreenTimeMillis(context)
                } catch (_: Throwable) {
                    0L
                }
                val resolvedScreenMins = (screenMs / 60000L).toInt().coerceAtLeast(0)
                Triple(resolvedSteps, resolvedScreenMins, hcPerm)
            }

            dailySteps = result.first
            screenUsedMinutes = result.second
            isHealthConnectLinked = result.third
            lastSyncSourceLabel = if (isHealthConnectLinked) {
                "💚 Health Connect Live"
            } else {
                "⚡ Pedometer + Health Connect"
            }
            invalidate()
            onComplete?.invoke(dailySteps, screenUsedMinutes, isHealthConnectLinked)
        }
    }

    fun animateSweep() {
        sweepAnimator?.cancel()
        sweepAnimator = ValueAnimator.ofFloat(0.08f, 1f).apply {
            duration = 700L
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                animProgress = animator.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 10f || h <= 10f) return

        val density = resources.displayMetrics.density
        val pad = 4f * density
        val corner = 18f * density
        val skin = CommandDeckWidgetPrefs.getSelectedSkin(context)

        // 1. Background Surface & Subtle Neon Border
        cardRect.set(pad, pad, w - pad, h - pad)
        cardBgPaint.color = skin.bgStartColor
        canvas.drawRoundRect(cardRect, corner, corner, cardBgPaint)

        cardBorderPaint.strokeWidth = 1.4f * density
        cardBorderPaint.color = skin.borderColor
        canvas.drawRoundRect(cardRect, corner, corner, cardBorderPaint)

        // 2. Compute Ratios
        val rawScreenRatio = (screenUsedMinutes.toFloat() / screenBudgetMinutes.toFloat()).coerceIn(0f, 1f)
        val rawStepRatio = (dailySteps.toFloat() / dailyStepGoal.toFloat()).coerceIn(0f, 1f)

        val screenRatio = (rawScreenRatio * animProgress).coerceIn(0.03f, 1f)
        val stepRatio = (rawStepRatio * animProgress).coerceIn(0.03f, 1f)

        // Outer Ring Color (Cyan -> Amber -> Coral as screen budget is consumed)
        val outerColor = when {
            rawScreenRatio >= 1.0f -> Color.parseColor("#EF4444")
            rawScreenRatio >= 0.8f -> Color.parseColor("#F59E0B")
            else -> skin.outerRingColor
        }
        val innerColor = skin.innerRingColor

        // 3. Ring Center & Radii (Left 38% of card)
        val ringAreaWidth = min(w * 0.40f, h * 1.05f)
        val cx = pad + ringAreaWidth * 0.52f
        val cy = h * 0.50f
        val maxRadius = min(ringAreaWidth * 0.42f, (h - pad * 4f) * 0.42f)

        val outerStroke = (maxRadius * 0.21f).coerceAtLeast(6f * density)
        val innerStroke = (maxRadius * 0.19f).coerceAtLeast(5.5f * density)
        val outerRadius = maxRadius
        val innerRadius = maxRadius - outerStroke - (5f * density)

        outerRect.set(cx - outerRadius, cy - outerRadius, cx + outerRadius, cy + outerRadius)
        innerRect.set(cx - innerRadius, cy - innerRadius, cx + innerRadius, cy + innerRadius)

        // Draw Subtle Concentric Tracks
        trackPaint.color = Color.parseColor("#232736")
        trackPaint.strokeWidth = outerStroke
        canvas.drawArc(outerRect, 0f, 360f, false, trackPaint)

        trackPaint.strokeWidth = innerStroke
        canvas.drawArc(innerRect, 0f, 360f, false, trackPaint)

        // Draw Outer Ring Halo + Neon Arc (Screen Time vs Budget)
        val outerSweep = 360f * screenRatio
        glowPaint.color = outerColor
        glowPaint.alpha = 65
        glowPaint.strokeWidth = outerStroke * 1.65f
        canvas.drawArc(outerRect, -90f, outerSweep, false, glowPaint)

        ringPaint.color = outerColor
        ringPaint.alpha = 255
        ringPaint.strokeWidth = outerStroke
        canvas.drawArc(outerRect, -90f, outerSweep, false, ringPaint)

        // Outer Ring Endpoint Dot
        val outerEndRad = Math.toRadians((-90f + outerSweep).toDouble())
        val outerTipX = (cx + outerRadius * cos(outerEndRad)).toFloat()
        val outerTipY = (cy + outerRadius * sin(outerEndRad)).toFloat()
        orbPaint.color = Color.WHITE
        canvas.drawCircle(outerTipX, outerTipY, outerStroke * 0.30f, orbPaint)

        // Draw Inner Ring Halo + Neon Arc (Daily Steps vs Target)
        val innerSweep = 360f * stepRatio
        glowPaint.color = innerColor
        glowPaint.alpha = 65
        glowPaint.strokeWidth = innerStroke * 1.65f
        canvas.drawArc(innerRect, -90f, innerSweep, false, glowPaint)

        ringPaint.color = innerColor
        ringPaint.alpha = 255
        ringPaint.strokeWidth = innerStroke
        canvas.drawArc(innerRect, -90f, innerSweep, false, ringPaint)

        // Inner Ring Endpoint Dot
        val innerEndRad = Math.toRadians((-90f + innerSweep).toDouble())
        val innerTipX = (cx + innerRadius * cos(innerEndRad)).toFloat()
        val innerTipY = (cy + innerRadius * sin(innerEndRad)).toFloat()
        orbPaint.color = Color.WHITE
        canvas.drawCircle(innerTipX, innerTipY, innerStroke * 0.30f, orbPaint)

        // 4. Center Ring Readout (Vitality / Balance Percentage)
        val stepPct = (rawStepRatio * 100f).roundToInt()
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textPaint.color = Color.WHITE
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = (innerRadius * 0.44f).coerceIn(11f * density, 16f * density)
        canvas.drawText("${stepPct}%", cx, cy + (2f * density), textPaint)

        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        textPaint.color = Color.parseColor("#94A3B8")
        textPaint.textSize = (innerRadius * 0.25f).coerceIn(8f * density, 10.5f * density)
        canvas.drawText("STEPS", cx, cy + (13f * density), textPaint)

        // 5. Right-Side Telemetry Details & Health Connect Status Pill
        val rightX = cx + outerRadius + (16f * density)
        val rightMaxW = (w - pad - rightX - (10f * density)).coerceAtLeast(80f)

        // Health Connect Status Badge
        val badgeH = 20f * density
        val badgeTop = pad + (11f * density)
        val badgeW = min(rightMaxW, 178f * density)
        badgeRect.set(rightX, badgeTop, rightX + badgeW, badgeTop + badgeH)

        badgePaint.color = if (isHealthConnectLinked) Color.parseColor("#062E22") else Color.parseColor("#172554")
        canvas.drawRoundRect(badgeRect, badgeH / 2f, badgeH / 2f, badgePaint)

        badgeStrokePaint.strokeWidth = 1f * density
        badgeStrokePaint.color = if (isHealthConnectLinked) Color.parseColor("#10B981") else Color.parseColor("#38BDF8")
        canvas.drawRoundRect(badgeRect, badgeH / 2f, badgeH / 2f, badgeStrokePaint)

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textPaint.textSize = 9.5f * density
        textPaint.color = if (isHealthConnectLinked) Color.parseColor("#34D399") else Color.parseColor("#7DD3FC")
        canvas.drawText(lastSyncSourceLabel, rightX + (9f * density), badgeTop + (13.5f * density), textPaint)

        // Outer Ring Legend (Screen Time vs Budget)
        val row1Y = badgeTop + badgeH + (19f * density)
        orbPaint.color = outerColor
        canvas.drawCircle(rightX + (5f * density), row1Y - (4f * density), 4.5f * density, orbPaint)

        textPaint.color = Color.parseColor("#94A3B8")
        textPaint.textSize = 10.5f * density
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("OUTER RING • SCREEN BUDGET", rightX + (14f * density), row1Y, textPaint)

        val usedH = screenUsedMinutes / 60
        val usedM = screenUsedMinutes % 60
        val budH = screenBudgetMinutes / 60
        val budM = screenBudgetMinutes % 60
        val usedStr = if (usedH > 0) "${usedH}h ${usedM}m" else "${usedM}m"
        val budStr = if (budH > 0) "${budH}h ${budM}m" else "${budM}m"
        val screenPct = (rawScreenRatio * 100f).roundToInt()

        textPaint.color = Color.WHITE
        textPaint.textSize = 13f * density
        canvas.drawText("$usedStr / $budStr ($screenPct%)", rightX + (14f * density), row1Y + (15.5f * density), textPaint)

        // Inner Ring Legend (Daily Steps vs Target via Health Connect)
        val row2Y = row1Y + (35f * density)
        orbPaint.color = innerColor
        canvas.drawCircle(rightX + (5f * density), row2Y - (4f * density), 4.5f * density, orbPaint)

        textPaint.color = Color.parseColor("#94A3B8")
        textPaint.textSize = 10.5f * density
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("INNER RING • DAILY STEPS", rightX + (14f * density), row2Y, textPaint)

        val fmt = NumberFormat.getIntegerInstance(Locale.US)
        val stepsFormatted = fmt.format(dailySteps)
        val goalFormatted = fmt.format(dailyStepGoal)

        textPaint.color = Color.parseColor("#34D399")
        textPaint.textSize = 13f * density
        canvas.drawText("$stepsFormatted / $goalFormatted ($stepPct%)", rightX + (14f * density), row2Y + (15.5f * density), textPaint)
    }
}
