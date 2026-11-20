package com.focusguardpro.app

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Focus Elite Performance Pace (min/km), Speed (km/h), and Split Telemetry Chart View.
 * Uses the unified Focus Elite palette (#16D99A Emerald, #27C7C9 Teal, #26313D Grid, #F1F5F9 Text).
 */
class StravaPaceChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class SplitPoint(
        val splitLabel: String,         // e.g. "KM 1", "KM 2", or "Set 1"
        val paceSecondsPerKm: Float,    // e.g. 345f -> 5:45 /km
        val speedKmh: Float,            // e.g. 10.4 km/h
        val durationSeconds: Int,       // split duration
        val cadenceSpm: Int = 0,        // steps per minute
        val elevationGainM: Float = 0f  // elevation gain in meters
    )

    private var splits: List<SplitPoint> = emptyList()
    private var selectedIndex: Int = -1
    private var isLightMode: Boolean = false
    private var chartSubtitle: String = "PACE CURVE (min/km)"

    var onSplitSelected: ((SplitPoint, Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }

    private val avgLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
        pathEffect = DashPathEffect(floatArrayOf(5f * density, 4f * density), 0f)
    }

    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.6f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f * scaledDensity
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10.5f * scaledDensity
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val curvePath = Path()
    private val fillPath = Path()
    private val barRect = RectF()
    private val badgeRect = RectF()

    fun setSplits(data: List<SplitPoint>, subtitle: String = "PACE CURVE (min/km)") {
        this.splits = data
        this.chartSubtitle = subtitle
        this.selectedIndex = if (data.isNotEmpty()) {
            data.indices.minByOrNull { data[it].paceSecondsPerKm } ?: (data.size - 1)
        } else {
            -1
        }
        this.isLightMode = UiThemePrefs.getMode(context) == UiThemePrefs.MODE_WHITE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        isLightMode = UiThemePrefs.getMode(context) == UiThemePrefs.MODE_WHITE

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val textPrimary = if (isLightMode) Color.parseColor("#0F172A") else Color.parseColor("#F1F5F9")
        val textSecondary = if (isLightMode) Color.parseColor("#64748B") else Color.parseColor("#8995A3")
        val gridColor = if (isLightMode) Color.parseColor("#E2E8F0") else Color.parseColor("#26313D")
        val accentTeal = Color.parseColor("#27C7C9")
        val accentEmerald = Color.parseColor("#16D99A")

        gridPaint.color = gridColor
        avgLinePaint.color = accentTeal

        val padLeft = 44f * density
        val padRight = 12f * density
        val padTop = 30f * density
        val padBottom = 22f * density

        val chartW = (w - padLeft - padRight).coerceAtLeast(1f)
        val chartH = (h - padTop - padBottom).coerceAtLeast(1f)

        labelPaint.color = textSecondary
        labelPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(chartSubtitle, 6f * density, 15f * density, labelPaint)

        if (splits.isEmpty()) {
            return
        }

        val paces = splits.map { it.paceSecondsPerKm.coerceIn(120f, 1800f) }
        val fastestPace = (paces.minOrNull() ?: 360f)
        val slowestPace = (paces.maxOrNull() ?: 600f)
        val paceSpan = max(30f, slowestPace - fastestPace)
        val minBound = max(90f, fastestPace - paceSpan * 0.22f)
        val maxBound = slowestPace + paceSpan * 0.22f
        val avgPace = paces.average().toFloat()

        // Lower min/km = faster movement = higher position on the Y-axis
        fun paceToY(paceSec: Float): Float {
            val normalized = ((maxBound - paceSec.coerceIn(minBound, maxBound)) / (maxBound - minBound)).coerceIn(0f, 1f)
            return padTop + chartH * (1f - normalized)
        }

        // Numerical Y-axis reference grid (Top = faster pace / lower min/km, Bottom = slower pace)
        val yTicks = floatArrayOf(minBound, (minBound + maxBound) / 2f, maxBound)
        labelPaint.textAlign = Paint.Align.RIGHT
        yTicks.forEachIndexed { i, tickPace ->
            val y = padTop + (chartH * i / 2f)
            canvas.drawLine(padLeft, y, w - padRight, y, gridPaint)
            labelPaint.color = if (i == 0) accentEmerald else textSecondary
            canvas.drawText(formatPace(tickPace), padLeft - 6f * density, y + 3.5f * density, labelPaint)
        }

        val count = splits.size
        val stepX = if (count > 1) chartW / (count - 1).toFloat() else 0f

        val points = splits.mapIndexed { idx, split ->
            val x = if (count == 1) padLeft + chartW / 2f else padLeft + idx * stepX
            val y = paceToY(split.paceSecondsPerKm)
            PointF(x, y)
        }

        val barWidth = min(18f * density, (chartW / max(1f, count * 2f)))
        splits.forEachIndexed { idx, split ->
            val pt = points[idx]
            val isFastest = split.paceSecondsPerKm <= fastestPace + 0.5f
            val isSelected = idx == selectedIndex
            barPaint.color = when {
                isSelected -> Color.argb(55, 39, 199, 201)
                isFastest -> Color.argb(50, 22, 217, 154)
                isLightMode -> Color.argb(30, 15, 23, 42)
                else -> Color.argb(32, 137, 149, 163)
            }
            barRect.set(
                pt.x - barWidth / 2f,
                pt.y,
                pt.x + barWidth / 2f,
                padTop + chartH
            )
            canvas.drawRoundRect(barRect, 4f * density, 4f * density, barPaint)
        }

        val avgY = paceToY(avgPace)
        canvas.drawLine(padLeft, avgY, w - padRight, avgY, avgLinePaint)
        labelPaint.color = accentTeal
        labelPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("Avg ${formatPace(avgPace)}", w - padRight, avgY - 4f * density, labelPaint)

        curvePath.reset()
        fillPath.reset()
        if (points.size == 1) {
            val p = points[0]
            curvePath.moveTo(padLeft, p.y)
            curvePath.lineTo(w - padRight, p.y)
            fillPath.moveTo(padLeft, padTop + chartH)
            fillPath.lineTo(padLeft, p.y)
            fillPath.lineTo(w - padRight, p.y)
            fillPath.lineTo(w - padRight, padTop + chartH)
            fillPath.close()
        } else {
            curvePath.moveTo(points[0].x, points[0].y)
            fillPath.moveTo(points[0].x, padTop + chartH)
            fillPath.lineTo(points[0].x, points[0].y)

            for (i in 0 until points.size - 1) {
                val p0 = points[i]
                val p1 = points[i + 1]
                val ctrlX1 = p0.x + (p1.x - p0.x) * 0.45f
                val ctrlX2 = p0.x + (p1.x - p0.x) * 0.55f
                curvePath.cubicTo(ctrlX1, p0.y, ctrlX2, p1.y, p1.x, p1.y)
                fillPath.cubicTo(ctrlX1, p0.y, ctrlX2, p1.y, p1.x, p1.y)
            }
            fillPath.lineTo(points.last().x, padTop + chartH)
            fillPath.close()
        }

        fillPaint.shader = LinearGradient(
            0f, padTop, 0f, padTop + chartH,
            intArrayOf(
                Color.argb(95, 39, 199, 201),
                Color.argb(28, 39, 199, 201),
                Color.argb(0, 39, 199, 201)
            ),
            floatArrayOf(0f, 0.65f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(fillPath, fillPaint)

        curvePaint.color = accentTeal
        canvas.drawPath(curvePath, curvePaint)

        val labelStep = max(1, count / 6)
        points.forEachIndexed { idx, pt ->
            val split = splits[idx]
            val isFastest = split.paceSecondsPerKm <= fastestPace + 0.5f
            val isSelected = idx == selectedIndex

            if (idx % labelStep == 0 || idx == count - 1 || isSelected) {
                labelPaint.color = if (isSelected) accentEmerald else textSecondary
                labelPaint.textAlign = Paint.Align.CENTER
                canvas.drawText(split.splitLabel, pt.x, h - 5f * density, labelPaint)
            }

            if (isSelected || isFastest) {
                dotPaint.color = if (isFastest) Color.argb(65, 22, 217, 154) else Color.argb(65, 39, 199, 201)
                canvas.drawCircle(pt.x, pt.y, 6.5f * density, dotPaint)
            }

            dotPaint.color = when {
                isFastest -> accentEmerald
                isSelected -> accentTeal
                else -> textPrimary
            }
            canvas.drawCircle(pt.x, pt.y, 3.5f * density, dotPaint)
        }

        val activeIdx = selectedIndex.coerceIn(0, splits.lastIndex)
        val activeSplit = splits[activeIdx]
        val summaryText = String.format(
            Locale.US,
            "%s: %s/km • %.1f km/h",
            activeSplit.splitLabel,
            formatPace(activeSplit.paceSecondsPerKm),
            activeSplit.speedKmh
        )
        valuePaint.color = Color.parseColor("#080D12")
        val badgeW = valuePaint.measureText(summaryText) + 14f * density
        val badgeH = 19f * density
        badgeRect.set(w - padRight - badgeW, 3f * density, w - padRight, 3f * density + badgeH)
        badgeBgPaint.color = accentEmerald
        canvas.drawRoundRect(badgeRect, 9.5f * density, 9.5f * density, badgeBgPaint)
        valuePaint.textAlign = Paint.Align.CENTER
        canvas.drawText(
            summaryText,
            badgeRect.centerX(),
            badgeRect.centerY() + 3.5f * density,
            valuePaint
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (splits.isEmpty()) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val padLeft = 10f * density
                val padRight = 10f * density
                val chartW = (width - padLeft - padRight).coerceAtLeast(1f)
                val nearest = if (splits.size == 1) {
                    0
                } else {
                    val stepX = chartW / (splits.size - 1).toFloat()
                    splits.indices.minByOrNull { idx ->
                        val x = padLeft + idx * stepX
                        abs(event.x - x)
                    } ?: 0
                }
                if (nearest != selectedIndex) {
                    selectedIndex = nearest
                    onSplitSelected?.invoke(splits[nearest], nearest)
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

    companion object {
        fun formatPace(paceSecondsPerKm: Float): String {
            if (paceSecondsPerKm <= 0f || paceSecondsPerKm.isNaN() || paceSecondsPerKm.isInfinite()) {
                return "—:—"
            }
            val clamped = paceSecondsPerKm.toInt().coerceIn(60, 3599)
            val mins = clamped / 60
            val secs = clamped % 60
            return String.format(Locale.US, "%d:%02d", mins, secs)
        }
    }
}
