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
 * Strava-grade Pace (min/km), Speed (km/h), and Split Telemetry Chart View.
 * Renders a smooth cubic-Bezier pace curve with gradient fill, split bar indicators,
 * dashed average-pace reference line, fastest split highlight badge, and interactive touch inspection.
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
    private var chartSubtitle: String = "PACE CURVE (min/km) & SPLIT SPEED"

    var onSplitSelected: ((SplitPoint, Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }

    private val avgLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        pathEffect = DashPathEffect(floatArrayOf(6f * density, 4f * density), 0f)
    }

    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
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
        textSize = 10.5f * scaledDensity
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * scaledDensity
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val curvePath = Path()
    private val fillPath = Path()
    private val barRect = RectF()
    private val badgeRect = RectF()

    fun setSplits(data: List<SplitPoint>, subtitle: String = "PACE CURVE (min/km) & SPLIT SPEED") {
        this.splits = data
        this.chartSubtitle = subtitle
        this.selectedIndex = if (data.isNotEmpty()) {
            // Highlight fastest split by default
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

        val textPrimary = if (isLightMode) Color.parseColor("#0F172A") else Color.parseColor("#F8FAFC")
        val textSecondary = if (isLightMode) Color.parseColor("#64748B") else Color.parseColor("#94A3B8")
        val gridColor = if (isLightMode) Color.parseColor("#E2E8F0") else Color.parseColor("#1E293B")
        val accentOrange = Color.parseColor("#FC4C02") // Strava signature performance orange
        val accentCyan = Color.parseColor("#00E5FF")
        val accentEmerald = Color.parseColor("#10B981")

        gridPaint.color = gridColor
        avgLinePaint.color = accentCyan

        val padLeft = 14f * density
        val padRight = 14f * density
        val padTop = 36f * density
        val padBottom = 30f * density

        val chartW = (w - padLeft - padRight).coerceAtLeast(1f)
        val chartH = (h - padTop - padBottom).coerceAtLeast(1f)

        // Header subtitle + selected split telemetry
        labelPaint.color = textSecondary
        labelPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(chartSubtitle, padLeft, 18f * density, labelPaint)

        if (splits.isEmpty()) {
            labelPaint.textAlign = Paint.Align.CENTER
            labelPaint.color = textSecondary
            canvas.drawText(
                "Start a Walk/Run or complete steps to plot live Strava pace splits",
                w / 2f,
                h / 2f + 4f * density,
                labelPaint
            )
            return
        }

        // Draw horizontal grid lines
        for (i in 0..3) {
            val y = padTop + (chartH * i / 3f)
            canvas.drawLine(padLeft, y, w - padRight, y, gridPaint)
        }

        // In pace charts, LOWER paceSecondsPerKm = FASTER = HIGHER on the Y axis
        val paces = splits.map { it.paceSecondsPerKm.coerceIn(120f, 1800f) }
        val fastestPace = (paces.minOrNull() ?: 360f)
        val slowestPace = (paces.maxOrNull() ?: 600f)
        val paceSpan = max(30f, slowestPace - fastestPace)
        val minBound = max(90f, fastestPace - paceSpan * 0.22f)
        val maxBound = slowestPace + paceSpan * 0.22f
        val avgPace = paces.average().toFloat()

        fun paceToY(paceSec: Float): Float {
            // Faster pace (smaller sec/km) sits higher (closer to padTop)
            val normalized = ((maxBound - paceSec.coerceIn(minBound, maxBound)) / (maxBound - minBound)).coerceIn(0f, 1f)
            return padTop + chartH * (1f - normalized)
        }

        val count = splits.size
        val stepX = if (count > 1) chartW / (count - 1).toFloat() else 0f

        val points = splits.mapIndexed { idx, split ->
            val x = if (count == 1) padLeft + chartW / 2f else padLeft + idx * stepX
            val y = paceToY(split.paceSecondsPerKm)
            PointF(x, y)
        }

        // Draw subtle split speed columns in background
        val barWidth = min(22f * density, (chartW / max(1, count * 2)))
        splits.forEachIndexed { idx, split ->
            val pt = points[idx]
            val isFastest = split.paceSecondsPerKm <= fastestPace + 0.5f
            val isSelected = idx == selectedIndex
            barPaint.color = when {
                isSelected -> Color.argb(65, 252, 76, 2)
                isFastest -> Color.argb(55, 16, 185, 129)
                isLightMode -> Color.argb(35, 15, 23, 42)
                else -> Color.argb(38, 148, 163, 184)
            }
            barRect.set(
                pt.x - barWidth / 2f,
                pt.y,
                pt.x + barWidth / 2f,
                padTop + chartH
            )
            canvas.drawRoundRect(barRect, 6f * density, 6f * density, barPaint)
        }

        // Draw average pace dashed reference line
        val avgY = paceToY(avgPace)
        canvas.drawLine(padLeft, avgY, w - padRight, avgY, avgLinePaint)
        labelPaint.color = accentCyan
        labelPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("AVG ${formatPace(avgPace)}/km", w - padRight, (avgY - 5f * density).coerceAtLeast(padTop + 10f * density), labelPaint)

        // Build smooth cubic Bezier path
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
                Color.argb(115, 252, 76, 2),
                Color.argb(40, 252, 76, 2),
                Color.argb(0, 252, 76, 2)
            ),
            floatArrayOf(0f, 0.65f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(fillPath, fillPaint)

        curvePaint.color = accentOrange
        canvas.drawPath(curvePath, curvePaint)

        // Draw split points, X-axis labels, and selected split callout
        val labelStep = max(1, count / 7)
        points.forEachIndexed { idx, pt ->
            val split = splits[idx]
            val isFastest = split.paceSecondsPerKm <= fastestPace + 0.5f
            val isSelected = idx == selectedIndex

            if (idx % labelStep == 0 || idx == count - 1 || isSelected) {
                labelPaint.color = if (isSelected) accentOrange else textSecondary
                labelPaint.textAlign = Paint.Align.CENTER
                canvas.drawText(split.splitLabel, pt.x, h - 8f * density, labelPaint)
            }

            // Outer halo for selected or fastest point
            if (isSelected || isFastest) {
                dotPaint.color = if (isFastest) Color.argb(70, 16, 185, 129) else Color.argb(70, 252, 76, 2)
                canvas.drawCircle(pt.x, pt.y, 8f * density, dotPaint)
            }

            dotPaint.color = when {
                isFastest -> accentEmerald
                isSelected -> accentOrange
                else -> textPrimary
            }
            canvas.drawCircle(pt.x, pt.y, 4.2f * density, dotPaint)
        }

        // Draw top-right selected split telemetry pill
        val activeIdx = selectedIndex.coerceIn(0, splits.lastIndex)
        val activeSplit = splits[activeIdx]
        val summaryText = String.format(
            Locale.US,
            "%s: %s/km • %.1f km/h",
            activeSplit.splitLabel,
            formatPace(activeSplit.paceSecondsPerKm),
            activeSplit.speedKmh
        )
        valuePaint.color = Color.WHITE
        val badgeW = valuePaint.measureText(summaryText) + 16f * density
        val badgeH = 22f * density
        badgeRect.set(w - padRight - badgeW, 4f * density, w - padRight, 4f * density + badgeH)
        badgeBgPaint.color = accentOrange
        canvas.drawRoundRect(badgeRect, 11f * density, 11f * density, badgeBgPaint)
        valuePaint.textAlign = Paint.Align.CENTER
        canvas.drawText(
            summaryText,
            badgeRect.centerX(),
            badgeRect.centerY() + 4f * density,
            valuePaint
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (splits.isEmpty()) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val padLeft = 14f * density
                val padRight = 14f * density
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
