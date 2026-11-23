package com.focusguardpro.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Clean, minimalist horizontal overnight Sleep Timeline / Hypnogram bar.
 * Clearly differentiates continuous Asleep segments (deep indigo-blue) from
 * Mid-Night Awakening gaps (warm amber-coral) with bedtime/wake-up time anchors
 * and interactive tap-to-inspect tooltip.
 */
class SleepTimelineBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var segments: List<SmartSleepEngine.SleepSegment> = emptyList()
    private var bedtimeMs: Long = 0L
    private var wakeTimeMs: Long = 0L
    private var selectedIndex: Int = -1

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val sleepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#6366F1")
    }
    private val sleepSelectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#818CF8")
    }
    private val awakePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#F59E0B")
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * scaledDensity
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val tooltipBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val tooltipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * scaledDensity
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val barRect = RectF()
    private val segRect = RectF()
    private val tooltipRect = RectF()

    fun setSleepData(result: SmartSleepEngine.SleepDetectionResult) {
        this.segments = result.segments
        this.bedtimeMs = result.bedtimeMs
        this.wakeTimeMs = result.wakeTimeMs
        // Auto-highlight first awakening if present so user immediately sees it
        this.selectedIndex = result.segments.indexOfFirst { it.isAwake }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val isLight = UiThemePrefs.isWhiteBackgroundActive(context)
        val textSecondary = if (isLight) Color.parseColor("#475569") else Color.parseColor("#94A3B8")
        val trackBg = if (isLight) Color.parseColor("#E2E8F0") else Color.parseColor("#182235")

        val padH = 4f * density
        val barTop = 30f * density
        val barHeight = 22f * density
        val barBottom = barTop + barHeight
        val barWidth = (w - padH * 2f).coerceAtLeast(1f)
        val radius = 8f * density

        trackPaint.color = trackBg
        barRect.set(padH, barTop, w - padH, barBottom)
        canvas.drawRoundRect(barRect, radius, radius, trackPaint)

        val totalSpan = (wakeTimeMs - bedtimeMs).coerceAtLeast(1L)
        if (segments.isEmpty() || wakeTimeMs <= bedtimeMs) {
            labelPaint.color = textSecondary
            labelPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("No overnight sleep timeline yet", w / 2f, barTop + barHeight / 2f + 4f * density, labelPaint)
            return
        }

        // Draw Asleep & Awake segments proportionally
        segments.forEachIndexed { idx, seg ->
            val leftFrac = ((seg.startMs - bedtimeMs).toFloat() / totalSpan.toFloat()).coerceIn(0f, 1f)
            val rightFrac = ((seg.endMs - bedtimeMs).toFloat() / totalSpan.toFloat()).coerceIn(leftFrac, 1f)
            val left = padH + leftFrac * barWidth
            var right = padH + rightFrac * barWidth
            // Ensure brief awakenings stay clearly visible (at least 4dp wide)
            if (seg.isAwake && (right - left) < 4.5f * density) {
                val mid = (left + right) / 2f
                val half = 2.25f * density
                segRect.set(
                    (mid - half).coerceAtLeast(padH),
                    barTop - 2f * density,
                    (mid + half).coerceAtMost(w - padH),
                    barBottom + 2f * density
                )
            } else {
                val vInset = if (seg.isAwake) -2f * density else 0f
                segRect.set(left, barTop + vInset, right, barBottom - vInset)
            }

            val paint = when {
                seg.isAwake -> awakePaint
                idx == selectedIndex -> sleepSelectedPaint
                else -> sleepPaint
            }
            val segRadius = if (seg.isAwake) 4f * density else radius
            canvas.drawRoundRect(segRect, segRadius, segRadius, paint)
        }

        // Draw selected segment tooltip pill above bar
        if (selectedIndex in segments.indices) {
            val sel = segments[selectedIndex]
            val midMs = (sel.startMs + sel.endMs) / 2L
            val midX = padH + ((midMs - bedtimeMs).toFloat() / totalSpan.toFloat()).coerceIn(0f, 1f) * barWidth
            val prefix = if (sel.isAwake) "Awake" else "Asleep"
            val text = "$prefix ${SmartSleepEngine.formatClock(sel.startMs)}–${SmartSleepEngine.formatClock(sel.endMs)} (${SmartSleepEngine.formatDuration(sel.durationMs)})"
            val pillW = tooltipTextPaint.measureText(text) + 18f * density
            val pillH = 22f * density
            val left = (midX - pillW / 2f).coerceIn(padH, (w - padH - pillW).coerceAtLeast(padH))
            val top = 2f * density
            tooltipRect.set(left, top, left + pillW, top + pillH)

            tooltipBgPaint.color = if (sel.isAwake) Color.parseColor("#3B240A") else Color.parseColor("#1E2448")
            canvas.drawRoundRect(tooltipRect, 11f * density, 11f * density, tooltipBgPaint)

            tooltipTextPaint.color = if (sel.isAwake) Color.parseColor("#FBBF24") else Color.parseColor("#C7D2FE")
            canvas.drawText(text, tooltipRect.centerX(), tooltipRect.centerY() + 4f * density, tooltipTextPaint)
        }

        // Bottom time anchors: Bedtime | Midpoint | Wake-up
        val labelY = barBottom + 16f * density
        labelPaint.color = textSecondary

        labelPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(SmartSleepEngine.formatClock(bedtimeMs), padH, labelY, labelPaint)

        labelPaint.textAlign = Paint.Align.CENTER
        val midClock = SmartSleepEngine.formatClock((bedtimeMs + wakeTimeMs) / 2L)
        canvas.drawText(midClock, w / 2f, labelY, labelPaint)

        labelPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(SmartSleepEngine.formatClock(wakeTimeMs), w - padH, labelY, labelPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (segments.isEmpty() || wakeTimeMs <= bedtimeMs) return super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE) {
            val padH = 4f * density
            val barWidth = (width - padH * 2f).coerceAtLeast(1f)
            val frac = ((event.x - padH) / barWidth).coerceIn(0f, 1f)
            val targetMs = bedtimeMs + ((wakeTimeMs - bedtimeMs) * frac).toLong()
            val hit = segments.indexOfFirst { targetMs in it.startMs..it.endMs }
            if (hit >= 0 && hit != selectedIndex) {
                selectedIndex = hit
                invalidate()
            }
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            performClick()
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
