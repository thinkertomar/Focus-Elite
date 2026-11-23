package com.focusguardpro.app

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat

/**
 * Compact, AMOLED-friendly design tokens shared across Focus Elite's
 * Calendar, Daily Journal, Task Management, and Discipline Insights modules.
 * Dynamically adapts to every UiThemePrefs palette and Light / Dark / AMOLED mode.
 */
/**
 * Marker interface for activities that apply [DisciplineTheme] tokens directly across
 * Light, Dark, AMOLED, and all color palettes without ViewTree auto-recoloring collisions.
 */
interface SelfThemedDisciplineActivity

object DisciplineTheme {

    data class Tokens(
        val isLight: Boolean,
        val isAmoled: Boolean,
        val bg: Int,
        val surface: Int,
        val surfaceAlt: Int,
        val border: Int,
        val accent: Int,
        val accentSoft: Int,
        val accentOnFill: Int,
        val textPrimary: Int,
        val textSecondary: Int,
        val textMuted: Int,
        // Semantic indicator palette (consistent across Calendar, Tasks, Journal, Insights)
        val stepGreen: Int,
        val workoutCyan: Int,
        val holidayAmber: Int,
        val diaryViolet: Int,
        val taskBlue: Int,
        val overdueCoral: Int,
        val priorityHigh: Int,
        val priorityNormal: Int,
        val priorityLow: Int
    )

    fun resolve(context: Context): Tokens {
        val isLight = UiThemePrefs.isWhiteBackgroundActive(context)
        val isAmoled = UiThemePrefs.isBlackBackgroundActive(context)
        val palette = UiThemePrefs.getSelectedPalette(context)

        // Ocean Cyan default unless user explicitly chose another colored palette
        val rawAccent = when (palette.id) {
            "black", "white" -> Color.parseColor("#00B8D4")
            else -> palette.accentColor
        }

        val accent = if (isLight && ColorUtils.calculateLuminance(rawAccent) > 0.55) {
            Color.parseColor("#00838F")
        } else {
            rawAccent
        }

        val bg = when {
            isLight -> Color.parseColor("#F5F8FA")
            isAmoled -> Color.parseColor("#000000")
            else -> Color.parseColor("#060B10")
        }

        val surface = when {
            isLight -> Color.parseColor("#FFFFFF")
            isAmoled -> Color.parseColor("#0B0F14")
            else -> Color.parseColor("#0E151E")
        }

        val surfaceAlt = when {
            isLight -> Color.parseColor("#EDF2F7")
            isAmoled -> Color.parseColor("#131A22")
            else -> Color.parseColor("#16212E")
        }

        val border = when {
            isLight -> Color.parseColor("#DCE5EE")
            isAmoled -> Color.parseColor("#1C2530")
            else -> Color.parseColor("#1F2D3D")
        }

        val accentSoft = if (isLight) {
            ColorUtils.setAlphaComponent(accent, 28)
        } else {
            ColorUtils.setAlphaComponent(accent, 36)
        }

        val accentOnFill = if (ColorUtils.calculateLuminance(accent) > 0.45) {
            Color.parseColor("#04131A")
        } else {
            Color.WHITE
        }

        return Tokens(
            isLight = isLight,
            isAmoled = isAmoled,
            bg = bg,
            surface = surface,
            surfaceAlt = surfaceAlt,
            border = border,
            accent = accent,
            accentSoft = accentSoft,
            accentOnFill = accentOnFill,
            textPrimary = if (isLight) Color.parseColor("#0F172A") else Color.parseColor("#F1F5F9"),
            textSecondary = if (isLight) Color.parseColor("#475569") else Color.parseColor("#94A3B8"),
            textMuted = if (isLight) Color.parseColor("#64748B") else Color.parseColor("#64748B"),
            stepGreen = if (isLight) Color.parseColor("#059669") else Color.parseColor("#10B981"),
            workoutCyan = if (isLight) Color.parseColor("#0284C7") else Color.parseColor("#00D4FF"),
            holidayAmber = if (isLight) Color.parseColor("#D97706") else Color.parseColor("#F59E0B"),
            diaryViolet = if (isLight) Color.parseColor("#7C3AED") else Color.parseColor("#A78BFA"),
            taskBlue = if (isLight) Color.parseColor("#2563EB") else Color.parseColor("#38BDF8"),
            overdueCoral = if (isLight) Color.parseColor("#DC2626") else Color.parseColor("#FB7185"),
            priorityHigh = if (isLight) Color.parseColor("#DC2626") else Color.parseColor("#F87171"),
            priorityNormal = accent,
            priorityLow = if (isLight) Color.parseColor("#64748B") else Color.parseColor("#94A3B8")
        )
    }

    /**
     * Styles status and navigation bars safely.
     * MUST be called AFTER setContentView(...) so AppCompat's sub-decor is initialized first.
     */
    fun applySystemBars(activity: Activity, tokens: Tokens) {
        try {
            val window = activity.window ?: return
            @Suppress("DEPRECATION")
            window.statusBarColor = tokens.bg
            @Suppress("DEPRECATION")
            window.navigationBarColor = tokens.bg
            val decor = window.decorView
            val controller = WindowCompat.getInsetsController(window, decor)
            controller.isAppearanceLightStatusBars = tokens.isLight
            controller.isAppearanceLightNavigationBars = tokens.isLight
        } catch (_: Throwable) {}
    }

    fun cardDrawable(
        fillColor: Int,
        strokeColor: Int,
        radiusDp: Float,
        density: Float,
        strokeWidthDp: Float = 1f
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * density
            setColor(fillColor)
            if (strokeWidthDp > 0f) {
                setStroke((strokeWidthDp * density).toInt().coerceAtLeast(1), strokeColor)
            }
        }
    }

    fun pillDrawable(
        fillColor: Int,
        strokeColor: Int? = null,
        density: Float = 1f
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 999f
            setColor(fillColor)
            if (strokeColor != null) {
                setStroke((1f * density).toInt().coerceAtLeast(1), strokeColor)
            }
        }
    }

    fun circleDrawable(
        fillColor: Int,
        strokeColor: Int? = null,
        strokeWidthPx: Int = 0
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fillColor)
            if (strokeColor != null && strokeWidthPx > 0) {
                setStroke(strokeWidthPx, strokeColor)
            }
        }
    }
}
