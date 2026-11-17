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
 * Renders the unified smooth cubic-Bezier curve graph + synchronized hourly/daily distribution
 * strip with an interactive 3-metric callout pill (e.g. "6–7 AM: 2.0 km • 22m • 118 kcal").
 */
class UnifiedActivityCurveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class CurvePoint(
        val timeLabel: String,         // e.g. "6 AM" or "Mon"
        val windowRangeLabel: String,  // e.g. "6:00–7:00 AM"
        val primaryValue: Float,       // metric plotted on curve (km, kcal, or moveMin)
        val distanceKm: Double,
        val moveMinutes: Int,
        val caloriesKcal: Int
    )

    private var points: List<CurvePoint> = emptyList()
    private var selectedIndex: Int = -1
    private var accentColor: Int = Color.parseColor("#00E5FF")
    private var secondaryColor: Int = Color.parseColor("#10B981")
    private var primaryUnit: String = "km"

    var onPointSelected: ((CurvePoint, Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }

    private val guideLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
        pathEffect = DashPathEffect(floatArrayOf(4f * density, 4f * density), 0f)
    }

    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.2f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 7f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val distBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val axisTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10.5f * scaledDensity
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val calloutTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11.5f * scaledDensity
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val calloutBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val calloutBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
    }

    private val curvePath = Path()
    private val fillPath = Path()
    private val barRect = RectF()
    private val calloutRect = RectF()

    fun setTelemetryData(
        data: List<CurvePoint>,
        accentHex: String = "#00E5FF",
        secondaryHex: String = "#10B981",
        unit: String = "km"
    ) {
        this.points = data
        this.accentColor = Color.parseColor(accentHex)
        this.secondaryColor = Color.parseColor(secondaryHex)
        this.primaryUnit = unit

        // Automatically select the peak active window (e.g., 6-7 AM)
        val peakIdx = data.indices.maxByOrNull { data[it].primaryValue } ?: -1
        this.selectedIndex = if (peakIdx >= 0 && data[peakIdx].primaryValue > 0f) {
            peakIdx
        } else {
            data.lastIndex
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val isLight = UiThemePrefs.isWhiteBackgroundActive(context)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val textPrimary = if (isLight) Color.parseColor("#0F172A") else Color.parseColor("#F8FAFC")
        val textSecondary = if (isLight) Color.parseColor("#64748B") else Color.parseColor("#94A3B8")
        val gridColor = if (isLight) Color.parseColor("#E2E8F0") else Color.parseColor("#182638")

        gridPaint.color = gridColor

        val padLeft = 14f * density
        val padRight = 14f * density
        val padTop = 42f * density
        val distStripHeight = 42f * density
        val axisLabelHeight = 22f * density
        val curveBottom = h - distStripHeight - axisLabelHeight
        val curveHeight = (curveBottom - padTop).coerceAtLeast(40f * density)
        val chartW = (w - padLeft - padRight).coerceAtLeast(1f)

        if (points.isEmpty()) {
            axisTextPaint.color = textSecondary
            axisTextPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(
                "Move with your phone to plot your live activity curve",
                w / 2f,
                h / 2f,
                axisTextPaint
            )
            return
        }

        // Subtle horizontal grid lines in the curve zone
        for (i in 0..2) {
            val gy = padTop + (curveHeight * i / 2f)
            canvas.drawLine(padLeft, gy, w - padRight, gy, gridPaint)
        }

        val maxVal = (points.maxOfOrNull { it.primaryValue } ?: 1f).coerceAtLeast(0.1f)
        val count = points.size
        val stepX = if (count > 1) chartW / (count - 1).toFloat() else 0f

        val coords = points.mapIndexed { idx, pt ->
            val x = if (count == 1) padLeft + chartW / 2f else padLeft + idx * stepX
            val ratio = (pt.primaryValue / (maxVal * 1.12f)).coerceIn(0f, 1f)
            val y = curveBottom - ratio * curveHeight
            PointF(x, y)
        }

        // Build smooth cubic-Bezier curve and gradient fill
        curvePath.reset()
        fillPath.reset()
        if (coords.size == 1) {
            val c = coords[0]
            curvePath.moveTo(padLeft, c.y)
            curvePath.lineTo(w - padRight, c.y)
            fillPath.moveTo(padLeft, curveBottom)
            fillPath.lineTo(padLeft, c.y)
            fillPath.lineTo(w - padRight, c.y)
            fillPath.lineTo(w - padRight, curveBottom)
            fillPath.close()
        } else {
            curvePath.moveTo(coords[0].x, coords[0].y)
            fillPath.moveTo(coords[0].x, curveBottom)
            fillPath.lineTo(coords[0].x, coords[0].y)

            for (i in 0 until coords.size - 1) {
                val p0 = coords[i]
                val p1 = coords[i + 1]
                val ctrlX1 = p0.x + (p1.x - p0.x) * 0.45f
                val ctrlX2 = p0.x + (p1.x - p0.x) * 0.55f
                curvePath.cubicTo(ctrlX1, p0.y, ctrlX2, p1.y, p1.x, p1.y)
                fillPath.cubicTo(ctrlX1, p0.y, ctrlX2, p1.y, p1.x, p1.y)
            }
            fillPath.lineTo(coords.last().x, curveBottom)
            fillPath.close()
        }

        fillPaint.shader = LinearGradient(
            0f, padTop, 0f, curveBottom,
            intArrayOf(
                Color.argb(125, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)),
                Color.argb(42, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)),
                Color.argb(0, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
            ),
            floatArrayOf(0f, 0.65f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(fillPath, fillPaint)

        // Outer neon glow + crisp inner spline
        glowPaint.color = Color.argb(55, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
        canvas.drawPath(curvePath, glowPaint)

        curvePaint.color = accentColor
        canvas.drawPath(curvePath, curvePaint)

        // X-axis time labels between curve and bottom distribution strip
        val labelY = curveBottom + 15f * density
        val labelInterval = max(1, count / 5)
        coords.forEachIndexed { idx, pt ->
            if (idx % labelInterval == 0 || idx == count - 1) {
                axisTextPaint.color = if (idx == selectedIndex) accentColor else textSecondary
                axisTextPaint.textAlign = Paint.Align.CENTER
                canvas.drawText(points[idx].timeLabel, pt.x, labelY, axisTextPaint)
            }
        }

        // Bottom Hourly / Window Synced Distribution Bars (matches mockup)
        val stripTop = curveBottom + axisLabelHeight + 4f * density
        val stripBottom = h - 4f * density
        val maxBarH = (stripBottom - stripTop).coerceAtLeast(12f * density)
        val barW = min(14f * density, (chartW / max(1f, count * 1.6f))).coerceAtLeast(5f * density)

        coords.forEachIndexed { idx, pt ->
            val v = points[idx].primaryValue
            val ratio = if (v > 0f) (v / maxVal).coerceIn(0.18f, 1f) else 0.1f
            val bh = ratio * maxBarH
            val isSel = idx == selectedIndex
            distBarPaint.color = when {
                isSel -> accentColor
                v > 0f -> Color.argb(145, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
                isLight -> Color.parseColor("#E2E8F0")
                else -> Color.parseColor("#162436")
            }
            barRect.set(
                pt.x - barW / 2f,
                stripBottom - bh,
                pt.x + barW / 2f,
                stripBottom
            )
            canvas.drawRoundRect(barRect, 4f * density, 4f * density, distBarPaint)
        }

        // Selected point vertical guide + glowing dot + synchronized 3-metric callout pill
        val activeIdx = selectedIndex.coerceIn(0, coords.lastIndex)
        val selCoord = coords[activeIdx]
        val selPt = points[activeIdx]

        guideLinePaint.color = Color.argb(130, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
        canvas.drawLine(selCoord.x, selCoord.y, selCoord.x, curveBottom, guideLinePaint)

        dotPaint.color = Color.argb(85, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
        canvas.drawCircle(selCoord.x, selCoord.y, 9f * density, dotPaint)
        dotPaint.color = Color.WHITE
        canvas.drawCircle(selCoord.x, selCoord.y, 4.5f * density, dotPaint)
        dotPaint.color = accentColor
        canvas.drawCircle(selCoord.x, selCoord.y, 2.5f * density, dotPaint)

        val calloutText = String.format(
            Locale.US,
            "%s: %.2f km • %dm • %d kcal",
            selPt.windowRangeLabel,
            selPt.distanceKm,
            selPt.moveMinutes,
            selPt.caloriesKcal
        )
        val pillW = calloutTextPaint.measureText(calloutText) + 20f * density
        val pillH = 26f * density
        val pillLeft = (selCoord.x - pillW / 2f).coerceIn(padLeft, w - padRight - pillW)
        val pillTop = (selCoord.y - pillH - 10f * density).coerceAtLeast(4f * density)
        calloutRect.set(pillLeft, pillTop, pillLeft + pillW, pillTop + pillH)

        calloutBgPaint.color = if (isLight) Color.parseColor("#0F172A") else Color.parseColor("#112233")
        calloutBorderPaint.color = accentColor
        canvas.drawRoundRect(calloutRect, 13f * density, 13f * density, calloutBgPaint)
        canvas.drawRoundRect(calloutRect, 13f * density, 13f * density, calloutBorderPaint)

        calloutTextPaint.color = Color.WHITE
        calloutTextPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(
            calloutText,
            calloutRect.centerX(),
            calloutRect.centerY() + 4f * density,
            calloutTextPaint
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (points.isEmpty()) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val padLeft = 14f * density
                val padRight = 14f * density
                val chartW = (width - padLeft - padRight).coerceAtLeast(1f)
                val nearest = if (points.size == 1) {
                    0
                } else {
                    val stepX = chartW / (points.size - 1).toFloat()
                    points.indices.minByOrNull { idx ->
                        val x = padLeft + idx * stepX
                        abs(event.x - x)
                    } ?: 0
                }
                if (nearest != selectedIndex) {
                    selectedIndex = nearest
                    onPointSelected?.invoke(points[nearest], nearest)
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
