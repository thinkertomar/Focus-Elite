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
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Clean, minimalist rounded vertical pill-bar chart inspired by modern fitness timelines:
 * - Day mode: full 24-hour distribution with "12 AM · 4 AM · 8 AM · 12 PM · 4 PM · 8 PM · 12 AM" axis
 * - Week / Month mode: crisp daily pill bars with evenly spaced labels
 * - Right-aligned Y-axis reference labels (mid & max) with subtle horizontal hairlines
 * - Interactive tap/scrub to inspect any bar's synchronized Move Min · Distance · Calories
 */
class CleanPillBarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class BarPoint(
        val axisLabel: String,         // e.g. "6 AM" or "Mon"
        val rangeLabel: String,        // e.g. "6–7 AM" or "Nov 22"
        val primaryValue: Float,       // metric plotted (min, km, kcal)
        val distanceKm: Double,
        val moveMinutes: Int,
        val caloriesKcal: Int
    )

    private var points: List<BarPoint> = emptyList()
    private var isDayMode: Boolean = true
    private var barColor: Int = Color.parseColor("#8AB4F8")
    private var barHighlightColor: Int = Color.parseColor("#A8C7FA")
    private var unit: String = "min"
    private var selectedIndex: Int = -1

    var onBarSelected: ((BarPoint, Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }

    private val axisTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * scaledDensity
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }

    private val yAxisTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11.5f * scaledDensity
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        textAlign = Paint.Align.RIGHT
    }

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val calloutBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val calloutTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * scaledDensity
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val barRect = RectF()
    private val calloutRect = RectF()

    fun setBarData(
        data: List<BarPoint>,
        dayMode: Boolean,
        barColorHex: String = "#8AB4F8",
        unitLabel: String = "min"
    ) {
        this.points = data
        this.isDayMode = dayMode
        this.barColor = Color.parseColor(barColorHex)
        this.barHighlightColor = blendLighter(this.barColor, 0.25f)
        this.unit = unitLabel
        this.selectedIndex = -1
        invalidate()
    }

    private fun blendLighter(color: Int, factor: Float): Int {
        val r = (Color.red(color) + (255 - Color.red(color)) * factor).toInt().coerceIn(0, 255)
        val g = (Color.green(color) + (255 - Color.green(color)) * factor).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) + (255 - Color.blue(color)) * factor).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val isLight = UiThemePrefs.isWhiteBackgroundActive(context)
        val gridColor = if (isLight) Color.parseColor("#E2E8F0") else Color.parseColor("#2A2E37")
        val baselineColor = if (isLight) Color.parseColor("#CBD5E1") else Color.parseColor("#3A3F4B")
        val axisLabelColor = if (isLight) Color.parseColor("#64748B") else Color.parseColor("#9AA0A6")
        val dotColor = if (isLight) Color.parseColor("#CBD5E1") else Color.parseColor("#4B5260")

        val padLeft = 10f * density
        val yAxisReserve = 34f * density
        val padRight = yAxisReserve
        val padTop = 32f * density
        val axisBottomReserve = if (isDayMode) 36f * density else 26f * density
        val chartBottom = h - axisBottomReserve
        val chartHeight = (chartBottom - padTop).coerceAtLeast(48f * density)
        val chartWidth = (w - padLeft - padRight).coerceAtLeast(1f)

        val rawMax = points.maxOfOrNull { it.primaryValue } ?: 0f
        val niceMax = computeNiceCeiling(rawMax, unit)

        // Draw 3 clean horizontal reference lines (top = max, mid = max/2, bottom = 0)
        gridPaint.color = gridColor
        canvas.drawLine(padLeft, padTop, w - padRight, padTop, gridPaint)
        val midY = padTop + chartHeight / 2f
        canvas.drawLine(padLeft, midY, w - padRight, midY, gridPaint)

        gridPaint.color = baselineColor
        canvas.drawLine(padLeft, chartBottom, w - padRight, chartBottom, gridPaint)

        // Right-aligned Y-axis labels (top & mid)
        yAxisTextPaint.color = axisLabelColor
        val yTextX = w - 4f * density
        canvas.drawText(formatYAxis(niceMax, unit), yTextX, padTop + 4f * density, yAxisTextPaint)
        canvas.drawText(formatYAxis(niceMax / 2f, unit), yTextX, midY + 4f * density, yAxisTextPaint)

        if (points.isEmpty()) return

        val count = points.size
        val slotW = chartWidth / count.toFloat()
        val barW = when {
            count >= 24 -> min(slotW * 0.48f, 6.2f * density).coerceAtLeast(3.2f * density)
            count >= 14 -> min(slotW * 0.52f, 8f * density).coerceAtLeast(4f * density)
            else -> min(slotW * 0.42f, 16f * density).coerceAtLeast(8f * density)
        }
        val topCorner = barW / 2f

        // Draw slender rounded pill bars
        points.forEachIndexed { idx, pt ->
            val v = pt.primaryValue
            if (v > 0f) {
                val ratio = (v / niceMax).coerceIn(0f, 1f)
                val barH = max(4f * density, ratio * chartHeight)
                val cx = padLeft + slotW * (idx + 0.5f)
                barRect.set(
                    cx - barW / 2f,
                    chartBottom - barH,
                    cx + barW / 2f,
                    chartBottom
                )
                barPaint.color = if (idx == selectedIndex) {
                    barHighlightColor
                } else if (selectedIndex >= 0) {
                    Color.argb(120, Color.red(barColor), Color.green(barColor), Color.blue(barColor))
                } else {
                    barColor
                }
                canvas.drawRoundRect(barRect, topCorner, topCorner, barPaint)
            }
        }

        // X-axis labels
        axisTextPaint.color = axisLabelColor
        axisTextPaint.textAlign = Paint.Align.CENTER
        if (isDayMode && count == 24) {
            // Exact Google-Fit style 24h ticks: 12 AM · 4 AM · 8 AM · 12 PM · 4 PM · 8 PM · 12 AM
            val anchors = listOf(
                0 to ("12" to "AM"),
                4 to ("4" to "AM"),
                8 to ("8" to "AM"),
                12 to ("12" to "PM"),
                16 to ("4" to "PM"),
                20 to ("8" to "PM"),
                24 to ("12" to "AM")
            )
            val line1Y = chartBottom + 15f * density
            val line2Y = chartBottom + 29f * density
            anchors.forEach { (hourIdx, pair) ->
                val x = (padLeft + (hourIdx / 24f) * chartWidth)
                    .coerceIn(padLeft + 10f * density, w - padRight - 6f * density)
                canvas.drawText(pair.first, x, line1Y, axisTextPaint)
                canvas.drawText(pair.second, x, line2Y, axisTextPaint)
            }
            // Subtle midpoint dots at 2 AM, 6 AM, 10 AM, 2 PM, 6 PM, 10 PM
            dotPaint.color = dotColor
            listOf(2, 6, 10, 14, 18, 22).forEach { hDot ->
                val dx = padLeft + (hDot / 24f) * chartWidth
                canvas.drawCircle(dx, chartBottom + 12f * density, 2.2f * density, dotPaint)
            }
        } else {
            val step = max(1, ceil(count / 6.0).toInt())
            val yLabel = chartBottom + 17f * density
            points.forEachIndexed { idx, pt ->
                if (idx % step == 0 || idx == count - 1) {
                    val cx = padLeft + slotW * (idx + 0.5f)
                    axisTextPaint.color = if (idx == selectedIndex) barColor else axisLabelColor
                    canvas.drawText(pt.axisLabel, cx, yLabel, axisTextPaint)
                }
            }
        }

        // Optional clean top tooltip pill when user taps a bar
        if (selectedIndex in points.indices) {
            val sel = points[selectedIndex]
            val cx = padLeft + slotW * (selectedIndex + 0.5f)
            val label = when (unit) {
                "km" -> String.format(Locale.US, "%s  ·  %.2f km  ·  %dm  ·  %d Cal", sel.rangeLabel, sel.distanceKm, sel.moveMinutes, sel.caloriesKcal)
                "kcal" -> String.format(Locale.US, "%s  ·  %d Cal  ·  %.2f km  ·  %dm", sel.rangeLabel, sel.caloriesKcal, sel.distanceKm, sel.moveMinutes)
                else -> String.format(Locale.US, "%s  ·  %d min  ·  %.2f km  ·  %d Cal", sel.rangeLabel, sel.moveMinutes, sel.distanceKm, sel.caloriesKcal)
            }
            val pillW = calloutTextPaint.measureText(label) + 18f * density
            val pillH = 22f * density
            val left = (cx - pillW / 2f).coerceIn(padLeft, (w - padRight - pillW).coerceAtLeast(padLeft))
            val top = 3f * density
            calloutRect.set(left, top, left + pillW, top + pillH)

            calloutBgPaint.color = if (isLight) Color.parseColor("#0F172A") else Color.parseColor("#232730")
            canvas.drawRoundRect(calloutRect, 11f * density, 11f * density, calloutBgPaint)

            calloutTextPaint.color = Color.WHITE
            canvas.drawText(label, calloutRect.centerX(), calloutRect.centerY() + 4f * density, calloutTextPaint)
        }
    }

    private fun computeNiceCeiling(rawMax: Float, unit: String): Float {
        if (rawMax <= 0f) {
            return when (unit) {
                "km" -> 2.0f
                "kcal" -> 200f
                else -> 24f
            }
        }
        return when (unit) {
            "km" -> {
                val candidates = floatArrayOf(0.5f, 1f, 2f, 4f, 6f, 8f, 10f, 15f, 20f, 30f, 50f)
                candidates.firstOrNull { it >= rawMax * 1.05f } ?: (ceil(rawMax / 10f) * 10f)
            }
            "kcal" -> {
                val candidates = floatArrayOf(40f, 80f, 120f, 200f, 300f, 400f, 600f, 800f, 1000f, 1500f, 2000f, 3000f)
                candidates.firstOrNull { it >= rawMax * 1.05f } ?: (ceil(rawMax / 500f) * 500f)
            }
            else -> {
                val candidates = floatArrayOf(12f, 24f, 30f, 40f, 60f, 90f, 120f, 180f, 240f)
                candidates.firstOrNull { it >= rawMax * 1.05f } ?: (ceil(rawMax / 30f) * 30f)
            }
        }
    }

    private fun formatYAxis(v: Float, unit: String): String {
        return when (unit) {
            "km" -> if (v >= 10f) String.format(Locale.US, "%.0f", v) else String.format(Locale.US, "%.1f", v)
            else -> v.toInt().toString()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (points.isEmpty()) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val padLeft = 10f * density
                val padRight = 34f * density
                val chartW = (width - padLeft - padRight).coerceAtLeast(1f)
                val slotW = chartW / points.size.toFloat()
                val idx = ((event.x - padLeft) / slotW).toInt().coerceIn(0, points.lastIndex)
                if (idx != selectedIndex) {
                    selectedIndex = idx
                    onBarSelected?.invoke(points[idx], idx)
                    invalidate()
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_UP -> {
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
