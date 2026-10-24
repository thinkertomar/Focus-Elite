package com.focusguardpro.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import java.util.Locale
import kotlin.math.min

/**
 * Preferences & High-DPI Canvas Renderer for the Home Screen Command Deck Widget
 * and Interactive Widget Studio.
 */
object CommandDeckWidgetPrefs {

    private const val PREFS_NAME = "command_deck_widget_prefs"
    private const val KEY_SKIN = "widget_skin"
    private const val KEY_SCREEN_BUDGET_MINS = "screen_budget_mins"
    private const val KEY_STEP_GOAL = "daily_step_goal"
    private const val KEY_QUICK_FOCUS_MINS = "quick_focus_mins"
    private const val KEY_AUTO_40HZ = "auto_40hz_enabled"

    enum class WidgetSkin(
        val id: String,
        val title: String,
        val subtitle: String,
        val bgStartColor: Int,
        val bgEndColor: Int,
        val panelColor: Int,
        val borderColor: Int,
        val outerRingColor: Int,
        val innerRingColor: Int,
        val accentColor: Int,
        val textPrimary: Int,
        val textSecondary: Int
    ) {
        CYBER_OBSIDIAN(
            id = "CYBER_OBSIDIAN",
            title = "Cyber Obsidian",
            subtitle = "Electric Cyan & Emerald Neon",
            bgStartColor = Color.parseColor("#09090B"),
            bgEndColor = Color.parseColor("#12131C"),
            panelColor = Color.parseColor("#181924"),
            borderColor = Color.parseColor("#38BDF8"),
            outerRingColor = Color.parseColor("#38BDF8"),
            innerRingColor = Color.parseColor("#10B981"),
            accentColor = Color.parseColor("#F59E0B"),
            textPrimary = Color.WHITE,
            textSecondary = Color.parseColor("#94A3B8")
        ),
        EMERALD_MATRIX(
            id = "EMERALD_MATRIX",
            title = "Emerald Matrix",
            subtitle = "Biometric Mint & Deep Forest",
            bgStartColor = Color.parseColor("#04120C"),
            bgEndColor = Color.parseColor("#092218"),
            panelColor = Color.parseColor("#0F2E22"),
            borderColor = Color.parseColor("#10B981"),
            outerRingColor = Color.parseColor("#34D399"),
            innerRingColor = Color.parseColor("#38BDF8"),
            accentColor = Color.parseColor("#A7F3D0"),
            textPrimary = Color.WHITE,
            textSecondary = Color.parseColor("#86EFAC")
        ),
        SOLAR_GOLD(
            id = "SOLAR_GOLD",
            title = "Solar Gold",
            subtitle = "Chronograph Amber & Warm Onyx",
            bgStartColor = Color.parseColor("#130E05"),
            bgEndColor = Color.parseColor("#211808"),
            panelColor = Color.parseColor("#2C200C"),
            borderColor = Color.parseColor("#F59E0B"),
            outerRingColor = Color.parseColor("#FBBF24"),
            innerRingColor = Color.parseColor("#FB923C"),
            accentColor = Color.parseColor("#38BDF8"),
            textPrimary = Color.WHITE,
            textSecondary = Color.parseColor("#FDE68A")
        ),
        MINIMAL_FROST(
            id = "MINIMAL_FROST",
            title = "Minimal Frost",
            subtitle = "Monochrome Titanium & Ice Blue",
            bgStartColor = Color.parseColor("#18181B"),
            bgEndColor = Color.parseColor("#27272A"),
            panelColor = Color.parseColor("#323236"),
            borderColor = Color.parseColor("#A1A1AA"),
            outerRingColor = Color.parseColor("#E4E4E7"),
            innerRingColor = Color.parseColor("#38BDF8"),
            accentColor = Color.parseColor("#C084FC"),
            textPrimary = Color.WHITE,
            textSecondary = Color.parseColor("#D4D4D8")
        );

        companion object {
            fun fromId(id: String?): WidgetSkin =
                values().firstOrNull { it.id == id } ?: CYBER_OBSIDIAN
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getSelectedSkin(context: Context): WidgetSkin =
        WidgetSkin.fromId(prefs(context).getString(KEY_SKIN, WidgetSkin.CYBER_OBSIDIAN.id))

    fun setSelectedSkin(context: Context, skin: WidgetSkin) {
        prefs(context).edit().putString(KEY_SKIN, skin.id).apply()
    }

    fun getScreenTimeBudgetMinutes(context: Context): Int =
        prefs(context).getInt(KEY_SCREEN_BUDGET_MINS, 180).coerceIn(30, 600)

    fun setScreenTimeBudgetMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_SCREEN_BUDGET_MINS, minutes.coerceIn(30, 600)).apply()
    }

    fun getDailyStepGoal(context: Context): Int =
        prefs(context).getInt(KEY_STEP_GOAL, 8000).coerceIn(1000, 30000)

    fun setDailyStepGoal(context: Context, steps: Int) {
        prefs(context).edit().putInt(KEY_STEP_GOAL, steps.coerceIn(1000, 30000)).apply()
    }

    fun getQuickFocusMinutes(context: Context): Int =
        prefs(context).getInt(KEY_QUICK_FOCUS_MINS, 25).coerceIn(5, 120)

    fun setQuickFocusMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_QUICK_FOCUS_MINS, minutes.coerceIn(5, 120)).apply()
    }

    fun isAuto40HzEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_40HZ, true)

    fun setAuto40HzEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_40HZ, enabled).apply()
    }

    data class TelemetrySnapshot(
        val skin: WidgetSkin,
        val screenTimeMinutes: Int,
        val screenBudgetMinutes: Int,
        val dailySteps: Int,
        val stepGoal: Int,
        val isFocusActive: Boolean,
        val isStrictLocked: Boolean,
        val exemptionText: String?,
        val windowSummary: String,
        val sessionState: FocusSessionManager.SessionState,
        val sessionRemainingFormatted: String,
        val sessionSoundName: String,
        val quickFocusMinutes: Int,
        val auto40Hz: Boolean,
        val todayFocusMinutes: Int
    )

    fun captureTelemetry(context: Context): TelemetrySnapshot {
        FocusSessionManager.init(context)
        val skin = getSelectedSkin(context)
        val screenTimeMs = try {
            UsageStatsHelper.getTodayScreenTimeMillis(context)
        } catch (_: Throwable) {
            0L
        }
        val screenMins = (screenTimeMs / 60000L).toInt().coerceAtLeast(0)
        val budgetMins = getScreenTimeBudgetMinutes(context)

        val steps = try {
            StepPrefs.getDailySteps(context).coerceAtLeast(0)
        } catch (_: Throwable) {
            0
        }
        val stepGoal = getDailyStepGoal(context)

        val isFocusActive = try {
            FocusLayerManager.isActiveNow(context)
        } catch (_: Throwable) {
            false
        }
        val isStrict = try {
            FocusLayerManager.isLockedUntilCalendarHoliday(context)
        } catch (_: Throwable) {
            false
        }
        val exemption = try {
            FocusLayerManager.getCalendarExemptionDetails(context)
        } catch (_: Throwable) {
            null
        }
        val windowSummary = try {
            FocusLayerManager.formatWindowSummary(context)
        } catch (_: Throwable) {
            "9:00 AM - 6:00 PM"
        }

        val snap = FocusSessionManager.snapshot.value
        val todayFocusMins = FocusSessionManager.getTodayFocusMinutes(context)

        return TelemetrySnapshot(
            skin = skin,
            screenTimeMinutes = screenMins,
            screenBudgetMinutes = budgetMins,
            dailySteps = steps,
            stepGoal = stepGoal,
            isFocusActive = isFocusActive,
            isStrictLocked = isStrict,
            exemptionText = exemption,
            windowSummary = windowSummary,
            sessionState = snap.state,
            sessionRemainingFormatted = snap.formattedRemaining,
            sessionSoundName = snap.soundMode.displayName,
            quickFocusMinutes = getQuickFocusMinutes(context),
            auto40Hz = isAuto40HzEnabled(context),
            todayFocusMinutes = todayFocusMins
        )
    }

    /**
     * Renders the 3-Zone All-in-One Command Deck onto an anti-aliased Bitmap
     * used both by RemoteViews on the Android Launcher and the live Widget Studio preview.
     */
    fun renderCommandDeckBitmap(
        context: Context,
        widthPx: Int = 880,
        heightPx: Int = 340,
        animFraction: Float = 1f
    ): Bitmap {
        val data = captureTelemetry(context)
        val skin = data.skin
        val w = widthPx.coerceAtLeast(320)
        val h = heightPx.coerceAtLeast(160)
        val scale = h / 340f

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        // 1. Outer Rounded Bezel Background
        val cardRect = RectF(4f * scale, 4f * scale, w - 4f * scale, h - 4f * scale)
        val cornerRadius = 42f * scale

        paint.shader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            skin.bgStartColor, skin.bgEndColor,
            Shader.TileMode.CLAMP
        )
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(cardRect, cornerRadius, cornerRadius, paint)
        paint.shader = null

        // Outer glowing border
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f * scale
        paint.color = skin.borderColor
        paint.alpha = 210
        canvas.drawRoundRect(cardRect, cornerRadius, cornerRadius, paint)
        paint.alpha = 255

        // Zone column dividers
        val leftZoneEnd = w * 0.33f
        val centerZoneEnd = w * 0.68f

        paint.strokeWidth = 1.5f * scale
        paint.color = Color.argb(45, 255, 255, 255)
        canvas.drawLine(leftZoneEnd, 28f * scale, leftZoneEnd, h - 28f * scale, paint)
        canvas.drawLine(centerZoneEnd, 28f * scale, centerZoneEnd, h - 28f * scale, paint)

        // =========================================================================
        // ZONE 1 (LEFT): DUAL CONCENTRIC NEON RINGS (Screen Time vs Steps)
        // =========================================================================
        val ringCx = leftZoneEnd * 0.5f
        val ringCy = h * 0.45f
        val outerRadius = min(leftZoneEnd * 0.36f, h * 0.33f)
        val innerRadius = outerRadius * 0.72f
        val outerStroke = 13f * scale
        val innerStroke = 11f * scale

        val outerRect = RectF(ringCx - outerRadius, ringCy - outerRadius, ringCx + outerRadius, ringCy + outerRadius)
        val innerRect = RectF(ringCx - innerRadius, ringCy - innerRadius, ringCx + innerRadius, ringCy + innerRadius)

        // Track circles
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = outerStroke
        paint.color = Color.argb(40, 255, 255, 255)
        canvas.drawArc(outerRect, 0f, 360f, false, paint)

        paint.strokeWidth = innerStroke
        canvas.drawArc(innerRect, 0f, 360f, false, paint)

        // Outer Arc: Screen Time Budget %
        val screenRatio = (data.screenTimeMinutes.toFloat() / data.screenBudgetMinutes.coerceAtLeast(1)).coerceIn(0f, 1f)
        val outerSweep = (if (screenRatio == 0f) 0.06f else screenRatio) * 360f * animFraction
        paint.strokeWidth = outerStroke
        paint.color = if (screenRatio >= 1f) Color.parseColor("#EF4444") else skin.outerRingColor
        canvas.drawArc(outerRect, -90f, outerSweep, false, paint)

        // Inner Arc: Daily Steps %
        val stepRatio = (data.dailySteps.toFloat() / data.stepGoal.coerceAtLeast(1)).coerceIn(0f, 1f)
        val innerSweep = (if (stepRatio == 0f) 0.08f else stepRatio) * 360f * animFraction
        paint.strokeWidth = innerStroke
        paint.color = skin.innerRingColor
        canvas.drawArc(innerRect, -90f, innerSweep, false, paint)

        // Center Telemetry Readout inside Rings
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textPaint.textSize = 23f * scale
        textPaint.color = skin.textPrimary
        val scrHrs = data.screenTimeMinutes / 60
        val scrMins = data.screenTimeMinutes % 60
        val scrFormatted = if (scrHrs > 0) "${scrHrs}h${scrMins}m" else "${scrMins}m"
        canvas.drawText(scrFormatted, ringCx, ringCy - 2f * scale, textPaint)

        textPaint.textSize = 16.5f * scale
        textPaint.color = skin.innerRingColor
        val stepsFormatted = String.format(Locale.US, "%,d", data.dailySteps)
        canvas.drawText("$stepsFormatted stp", ringCx, ringCy + 20f * scale, textPaint)

        // Bottom Legend under Rings
        textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textPaint.textSize = 14.5f * scale
        textPaint.color = skin.textSecondary
        val budgetHrs = data.screenBudgetMinutes / 60f
        val budgetStr = if (data.screenBudgetMinutes % 60 == 0) "${data.screenBudgetMinutes / 60}h" else String.format(Locale.US, "%.1fh", budgetHrs)
        canvas.drawText("SCR / $budgetStr  •  GOAL ${data.stepGoal / 1000}K", ringCx, h - 22f * scale, textPaint)

        // =========================================================================
        // ZONE 2 (CENTER): LIVE FOCUS LAYER & SHIELD TOGGLE
        // =========================================================================
        val centerLeft = leftZoneEnd + 22f * scale
        val centerRight = centerZoneEnd - 22f * scale
        val centerMidX = (centerLeft + centerRight) * 0.5f

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textPaint.textSize = 15f * scale
        textPaint.color = skin.borderColor
        canvas.drawText("FOCUS COMMAND DECK", centerLeft, 48f * scale, textPaint)

        // Status Headline
        val statusText: String
        val statusColor: Int
        when {
            data.exemptionText != null -> {
                statusText = "🏖️ HOLIDAY / EXEMPT"
                statusColor = Color.parseColor("#38BDF8")
            }
            data.isStrictLocked -> {
                statusText = "🔒 STRICT SHIELD LOCKED"
                statusColor = Color.parseColor("#F59E0B")
            }
            data.isFocusActive -> {
                statusText = "🟢 FOCUS SHIELD ACTIVE"
                statusColor = Color.parseColor("#10B981")
            }
            else -> {
                statusText = "🛡️ SHIELD STANDBY"
                statusColor = skin.textPrimary
            }
        }

        textPaint.textSize = 21f * scale
        textPaint.color = statusColor
        canvas.drawText(statusText, centerLeft, 88f * scale, textPaint)

        // Schedule Window Subtitle
        textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        textPaint.textSize = 16f * scale
        textPaint.color = skin.textSecondary
        val shortWindow = if (data.windowSummary.length > 26) data.windowSummary.take(26) + "…" else data.windowSummary
        canvas.drawText(shortWindow, centerLeft, 120f * scale, textPaint)

        // Today's Deep Focus Metric
        textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textPaint.textSize = 15.5f * scale
        textPaint.color = skin.accentColor
        canvas.drawText("⚡ Deep Work Today: ${data.todayFocusMinutes}m", centerLeft, 154f * scale, textPaint)

        // Interactive Shield Toggle Button Pill
        val shieldBtnRect = RectF(centerLeft, h - 88f * scale, centerRight, h - 24f * scale)
        paint.style = Paint.Style.FILL
        paint.color = skin.panelColor
        canvas.drawRoundRect(shieldBtnRect, 18f * scale, 18f * scale, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * scale
        paint.color = statusColor
        canvas.drawRoundRect(shieldBtnRect, 18f * scale, 18f * scale, paint)

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textPaint.textSize = 16.5f * scale
        textPaint.color = Color.WHITE
        val shieldBtnLabel = if (data.isFocusActive) "🛡️ Manage Active Shield" else "🛡️ Toggle Focus Shield"
        canvas.drawText(shieldBtnLabel, centerMidX, shieldBtnRect.centerY() + 6f * scale, textPaint)

        // =========================================================================
        // ZONE 3 (RIGHT): 1-TAP 25M + 40HZ GAMMA LAUNCH & STUDIO BUTTON
        // =========================================================================
        val rightLeft = centerZoneEnd + 20f * scale
        val rightRight = w - 24f * scale
        val rightMidX = (rightLeft + rightRight) * 0.5f

        // Top Big Action Card: 1-Tap Deep Focus + 40Hz
        val focusBtnRect = RectF(rightLeft, 28f * scale, rightRight, h - 102f * scale)
        val isSessionRunning = data.sessionState == FocusSessionManager.SessionState.RUNNING ||
            data.sessionState == FocusSessionManager.SessionState.PAUSED

        paint.style = Paint.Style.FILL
        if (isSessionRunning) {
            paint.shader = LinearGradient(
                focusBtnRect.left, focusBtnRect.top, focusBtnRect.right, focusBtnRect.bottom,
                Color.parseColor("#065F46"), Color.parseColor("#047857"),
                Shader.TileMode.CLAMP
            )
        } else {
            paint.shader = LinearGradient(
                focusBtnRect.left, focusBtnRect.top, focusBtnRect.right, focusBtnRect.bottom,
                skin.outerRingColor, skin.innerRingColor,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(focusBtnRect, 24f * scale, 24f * scale, paint)
        paint.shader = null

        textPaint.textAlign = Paint.Align.CENTER
        if (isSessionRunning) {
            textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textPaint.textSize = 28f * scale
            textPaint.color = Color.WHITE
            canvas.drawText(data.sessionRemainingFormatted, rightMidX, focusBtnRect.centerY() - 6f * scale, textPaint)

            textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textPaint.textSize = 14.5f * scale
            textPaint.color = Color.parseColor("#A7F3D0")
            canvas.drawText("🌊 40Hz FLOW ON", rightMidX, focusBtnRect.centerY() + 26f * scale, textPaint)
        } else {
            textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textPaint.textSize = 23f * scale
            textPaint.color = Color.parseColor("#09090B")
            val hzTag = if (data.auto40Hz) "+ 40Hz" else "FLOW"
            canvas.drawText("⚡ ${data.quickFocusMinutes}m $hzTag", rightMidX, focusBtnRect.centerY() - 6f * scale, textPaint)

            textPaint.textSize = 14f * scale
            textPaint.color = Color.parseColor("#18181B")
            canvas.drawText("1-TAP DEEP FOCUS", rightMidX, focusBtnRect.centerY() + 22f * scale, textPaint)
        }

        // Bottom Pill: Open Widget Studio
        val studioBtnRect = RectF(rightLeft, h - 88f * scale, rightRight, h - 24f * scale)
        paint.style = Paint.Style.FILL
        paint.color = skin.panelColor
        canvas.drawRoundRect(studioBtnRect, 18f * scale, 18f * scale, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * scale
        paint.color = Color.argb(90, 255, 255, 255)
        canvas.drawRoundRect(studioBtnRect, 18f * scale, 18f * scale, paint)

        textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textPaint.textSize = 15.5f * scale
        textPaint.color = skin.textPrimary
        canvas.drawText("🎨 Studio & Skins", rightMidX, studioBtnRect.centerY() + 6f * scale, textPaint)

        return bitmap
    }
}
