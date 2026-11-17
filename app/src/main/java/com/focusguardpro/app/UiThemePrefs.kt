package com.focusguardpro.app

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView

object UiThemePrefs {
    private const val PREFS_NAME = "focus_elite_ui_theme_prefs"

    // Default to "cyan" (Ocean Cyan) using v2 key so all installs default to Ocean Cyan
    private const val KEY_THEME_ID = "theme_id_v2"
    private const val KEY_BG_MODE = "screen_bg_mode_v1"
    private const val KEY_CARD_GLOW = "card_glow_border"
    private const val KEY_COMPACT_LIST = "compact_app_list"
    private const val KEY_SHOW_BLUE_TICK_STATUS = "show_blue_tick_status"
    private const val KEY_AUTO_ENFORCE_SUGGESTIONS = "auto_enforce_suggestions"
    private const val KEY_DEFAULT_LIMIT_MINUTES = "default_suggestion_limit_mins"
    private const val KEY_FIRST_TIME_INSTRUCTIONS_SHOWN = "first_time_instructions_v2_shown"
    private const val KEY_COVENANT_SEALED = "architect_covenant_sealed"
    private const val KEY_COVENANT_HOURS = "architect_covenant_daily_hours"

    const val BG_MODE_THEME = "theme"
    const val BG_MODE_BLACK = "black"
    const val BG_MODE_WHITE = "white"
    const val MODE_WHITE = BG_MODE_WHITE

    fun getMode(context: Context): String =
        if (isWhiteBackgroundActive(context)) BG_MODE_WHITE else getBackgroundMode(context)

    fun applyToActivity(activity: Activity) =
        applyWindowAndViewTreeTheme(activity)

    private const val TAG_ORIGINAL_TEXT_COLOR = 0x7F0B9901
    private const val TAG_RV_LISTENER_ATTACHED = 0x7F0B9902

    data class ThemePalette(
        val id: String,
        val name: String,
        val subtitle: String,
        val accentHex: String,
        val secondaryHex: String,
        val backgroundHex: String,
        val cardSurfaceHex: String,
        val heroTopHex: String,
        val heroBottomHex: String
    ) {
        val accentColor: Int get() = Color.parseColor(accentHex)
        val secondaryColor: Int get() = Color.parseColor(secondaryHex)
        val backgroundColor: Int get() = Color.parseColor(backgroundHex)
        val cardSurfaceColor: Int get() = Color.parseColor(cardSurfaceHex)
        val heroTopColor: Int get() = Color.parseColor(heroTopHex)
        val heroBottomColor: Int get() = Color.parseColor(heroBottomHex)
    }

    val PALETTES: List<ThemePalette> = listOf(
        ThemePalette(
            id = "cyan",
            name = "Ocean Cyan",
            subtitle = "Cryo-Focus Azure & Deep Navy (Default)",
            accentHex = "#00B8D4",
            secondaryHex = "#84FFFF",
            backgroundHex = "#071018",
            cardSurfaceHex = "#101E2B",
            heroTopHex = "#0C354A",
            heroBottomHex = "#101E2B"
        ),
        ThemePalette(
            id = "black",
            name = "Pure Black (AMOLED)",
            subtitle = "True #000000 Pitch Black & Obsidian Cards",
            accentHex = "#00B8D4",
            secondaryHex = "#84FFFF",
            backgroundHex = "#000000",
            cardSurfaceHex = "#090A0F",
            heroTopHex = "#061A26",
            heroBottomHex = "#090A0F"
        ),
        ThemePalette(
            id = "white",
            name = "Pure White (Daylight)",
            subtitle = "True #FFFFFF Pure White & Clean Light Cards",
            accentHex = "#0284C7",
            secondaryHex = "#0369A1",
            backgroundHex = "#FFFFFF",
            cardSurfaceHex = "#F8FAFC",
            heroTopHex = "#E0F2FE",
            heroBottomHex = "#F8FAFC"
        ),
        ThemePalette(
            id = "violet",
            name = "Electric Violet",
            subtitle = "Deep Obsidian & Signature Purple",
            accentHex = "#7C4DFF",
            secondaryHex = "#B388FF",
            backgroundHex = "#0B0E17",
            cardSurfaceHex = "#151929",
            heroTopHex = "#251B4D",
            heroBottomHex = "#151929"
        ),
        ThemePalette(
            id = "emerald",
            name = "Cyber Emerald",
            subtitle = "Bio-Hacker Matrix Green",
            accentHex = "#00E676",
            secondaryHex = "#69F0AE",
            backgroundHex = "#07120E",
            cardSurfaceHex = "#10211B",
            heroTopHex = "#0E3A2A",
            heroBottomHex = "#10211B"
        ),
        ThemePalette(
            id = "gold",
            name = "Solar Sovereign",
            subtitle = "Warm Amber Gold & Charcoal",
            accentHex = "#FFB300",
            secondaryHex = "#FFE57F",
            backgroundHex = "#120F08",
            cardSurfaceHex = "#211B10",
            heroTopHex = "#3E2E0E",
            heroBottomHex = "#211B10"
        ),
        ThemePalette(
            id = "crimson",
            name = "Crimson Spartan",
            subtitle = "High-Urgency Tactical Red",
            accentHex = "#FF5252",
            secondaryHex = "#FF8A80",
            backgroundHex = "#14090B",
            cardSurfaceHex = "#241216",
            heroTopHex = "#441720",
            heroBottomHex = "#241216"
        ),
        ThemePalette(
            id = "rose",
            name = "Synthwave Rose",
            subtitle = "Midnight Magenta & Neon Pink",
            accentHex = "#FF4081",
            secondaryHex = "#FF80AB",
            backgroundHex = "#130914",
            cardSurfaceHex = "#221226",
            heroTopHex = "#3F1746",
            heroBottomHex = "#221226"
        )
    )

    fun getSelectedPalette(context: Context): ThemePalette {
        val id = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_THEME_ID, "cyan") ?: "cyan"
        return PALETTES.find { it.id == id } ?: PALETTES.first()
    }

    fun setSelectedPalette(context: Context, id: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_THEME_ID, id)
        if (id == "black") {
            prefs.putString(KEY_BG_MODE, BG_MODE_BLACK)
        } else if (id == "white") {
            prefs.putString(KEY_BG_MODE, BG_MODE_WHITE)
        } else {
            val currentBg = getBackgroundMode(context)
            if (currentBg == BG_MODE_BLACK || currentBg == BG_MODE_WHITE) {
                prefs.putString(KEY_BG_MODE, BG_MODE_THEME)
            }
        }
        prefs.apply()
    }

    fun getBackgroundMode(context: Context): String {
        val palette = getSelectedPalette(context)
        val defaultMode = when (palette.id) {
            "black" -> BG_MODE_BLACK
            "white" -> BG_MODE_WHITE
            else -> BG_MODE_THEME
        }
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_BG_MODE, defaultMode) ?: defaultMode
    }

    fun setBackgroundMode(context: Context, mode: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BG_MODE, mode)
            .apply()
    }

    fun isWhiteBackgroundActive(context: Context): Boolean {
        val bgMode = getBackgroundMode(context)
        val palette = getSelectedPalette(context)
        return bgMode == BG_MODE_WHITE || (bgMode == BG_MODE_THEME && palette.id == "white")
    }

    fun isBlackBackgroundActive(context: Context): Boolean {
        val bgMode = getBackgroundMode(context)
        val palette = getSelectedPalette(context)
        return bgMode == BG_MODE_BLACK || (bgMode == BG_MODE_THEME && palette.id == "black")
    }

    fun getScreenBackgroundColor(context: Context): Int {
        val palette = getSelectedPalette(context)
        return when {
            isWhiteBackgroundActive(context) -> Color.parseColor("#FFFFFF")
            isBlackBackgroundActive(context) -> Color.parseColor("#000000")
            else -> palette.backgroundColor
        }
    }

    fun getCardSurfaceColor(context: Context): Int {
        val palette = getSelectedPalette(context)
        return when {
            isWhiteBackgroundActive(context) -> Color.parseColor("#F8FAFC")
            isBlackBackgroundActive(context) -> Color.parseColor("#090A0F")
            else -> palette.cardSurfaceColor
        }
    }

    fun getKpiCellBackgroundColor(context: Context): Int {
        return when {
            isWhiteBackgroundActive(context) -> Color.parseColor("#FFFFFF")
            isBlackBackgroundActive(context) -> Color.parseColor("#0D1117")
            else -> Color.parseColor("#151E2E")
        }
    }

    fun getKpiCellStrokeColor(context: Context): Int {
        return when {
            isWhiteBackgroundActive(context) -> Color.parseColor("#CBD5E1")
            isBlackBackgroundActive(context) -> Color.parseColor("#1E293B")
            else -> Color.parseColor("#26354D")
        }
    }

    fun getScreenHeaderTextColor(context: Context): Int {
        return if (isWhiteBackgroundActive(context)) {
            Color.parseColor("#0F172A")
        } else {
            Color.WHITE
        }
    }

    fun getScreenSubHeaderTextColor(context: Context): Int {
        return if (isWhiteBackgroundActive(context)) {
            Color.parseColor("#475569")
        } else {
            Color.parseColor("#94A3B8")
        }
    }

    fun getAdaptiveSecondaryAccentColor(context: Context): Int {
        val palette = getSelectedPalette(context)
        return if (isWhiteBackgroundActive(context)) {
            Color.parseColor("#0284C7")
        } else {
            palette.secondaryColor
        }
    }

    fun isCardGlowEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_CARD_GLOW, true)
    }

    fun setCardGlowEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_CARD_GLOW, enabled)
            .apply()
    }

    fun isCompactListEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_COMPACT_LIST, false)
    }

    fun setCompactListEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_COMPACT_LIST, enabled)
            .apply()
    }

    fun isShowBlueTickStatusEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_BLUE_TICK_STATUS, true)
    }

    fun setShowBlueTickStatusEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SHOW_BLUE_TICK_STATUS, enabled)
            .apply()
    }

    fun isAutoEnforceSuggestions(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_ENFORCE_SUGGESTIONS, false)
    }

    fun setAutoEnforceSuggestions(context: Context, autoEnforce: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_ENFORCE_SUGGESTIONS, autoEnforce)
            .apply()
    }

    fun getDefaultSuggestionLimitMinutes(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_DEFAULT_LIMIT_MINUTES, 30)
            .coerceIn(10, 180)
    }

    fun setDefaultSuggestionLimitMinutes(context: Context, minutes: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_DEFAULT_LIMIT_MINUTES, minutes.coerceIn(10, 180))
            .apply()
    }

    private const val KEY_SETUP_CATEGORIES_DONE = "guided_setup_categories_done"
    private const val KEY_SETUP_SCHEDULE_DONE = "guided_setup_schedule_done"

    fun hasSeenFirstTimeInstructions(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_FIRST_TIME_INSTRUCTIONS_SHOWN, false)
    }

    fun markFirstTimeInstructionsSeen(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_FIRST_TIME_INSTRUCTIONS_SHOWN, true)
            .apply()
    }

    fun isSetupCategoriesConfigured(context: Context): Boolean {
        val flag = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SETUP_CATEGORIES_DONE, false)
        if (flag) return true
        return AppCategoryPrefs.getStudyApps(context).isNotEmpty() ||
            AppCategoryPrefs.getImprovementApps(context).isNotEmpty() ||
            AppCategoryPrefs.getIgnoredApps(context).isNotEmpty()
    }

    fun markSetupCategoriesConfigured(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SETUP_CATEGORIES_DONE, true)
            .apply()
    }

    fun isSetupScheduleConfigured(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SETUP_SCHEDULE_DONE, false)
    }

    fun markSetupScheduleConfigured(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SETUP_SCHEDULE_DONE, true)
            .apply()
    }

    fun isCovenantSealed(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_COVENANT_SEALED, false)
    }

    fun setCovenantSealed(context: Context, sealed: Boolean, dailyHours: Float = 2.5f) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_COVENANT_SEALED, sealed)
            .putFloat(KEY_COVENANT_HOURS, dailyHours)
            .apply()
    }

    fun getCovenantHours(context: Context): Float {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat(KEY_COVENANT_HOURS, 2.5f)
    }

    fun createCardBackground(context: Context, cornerDp: Float = 16f, isHero: Boolean = false): GradientDrawable {
        val palette = getSelectedPalette(context)
        val glow = isCardGlowEnabled(context)
        val isWhite = isWhiteBackgroundActive(context)
        val isBlack = isBlackBackgroundActive(context)
        val density = context.resources.displayMetrics.density

        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerDp * density
            when {
                isWhite && isHero -> {
                    orientation = GradientDrawable.Orientation.TL_BR
                    colors = intArrayOf(Color.parseColor("#E0F2FE"), Color.parseColor("#F8FAFC"))
                }
                isWhite -> {
                    setColor(Color.parseColor("#F8FAFC"))
                }
                isBlack && isHero -> {
                    orientation = GradientDrawable.Orientation.TL_BR
                    colors = intArrayOf(Color.parseColor("#061824"), Color.parseColor("#090A0F"))
                }
                isBlack -> {
                    setColor(Color.parseColor("#090A0F"))
                }
                isHero -> {
                    orientation = GradientDrawable.Orientation.TL_BR
                    colors = intArrayOf(palette.heroTopColor, palette.heroBottomColor)
                }
                else -> {
                    setColor(palette.cardSurfaceColor)
                }
            }

            val strokeColor = when {
                isWhite && glow -> Color.argb(
                    if (isHero) 170 else 115,
                    Color.red(palette.accentColor),
                    Color.green(palette.accentColor),
                    Color.blue(palette.accentColor)
                )
                isWhite -> Color.parseColor("#CBD5E1")
                isBlack && glow -> Color.argb(
                    if (isHero) 160 else 90,
                    Color.red(palette.accentColor),
                    Color.green(palette.accentColor),
                    Color.blue(palette.accentColor)
                )
                isBlack -> Color.parseColor("#1E293B")
                glow -> Color.argb(
                    if (isHero) 170 else 95,
                    Color.red(palette.accentColor),
                    Color.green(palette.accentColor),
                    Color.blue(palette.accentColor)
                )
                else -> Color.parseColor("#252B42")
            }
            setStroke((1.2f * density).toInt().coerceAtLeast(1), strokeColor)
        }
    }

    fun createAccentCircleBackground(context: Context): GradientDrawable {
        val palette = getSelectedPalette(context)
        val isWhite = isWhiteBackgroundActive(context)
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(
                Color.argb(
                    if (isWhite) 32 else 45,
                    Color.red(palette.accentColor),
                    Color.green(palette.accentColor),
                    Color.blue(palette.accentColor)
                )
            )
            setStroke(
                (1f * context.resources.displayMetrics.density).toInt().coerceAtLeast(1),
                Color.argb(
                    if (isWhite) 130 else 110,
                    Color.red(palette.accentColor),
                    Color.green(palette.accentColor),
                    Color.blue(palette.accentColor)
                )
            )
        }
    }

    /**
     * Applies Pure White (#FFFFFF Light Mode), Pure Black (#000000 AMOLED Mode), or Theme Default
     * to the Activity's system bars, root background, cards, TextViews, EditTexts, and RecyclerViews.
     */
    fun applyWindowAndViewTreeTheme(activity: Activity, rootView: View? = null) {
        try {
            val screenBg = getScreenBackgroundColor(activity)
            val isWhite = isWhiteBackgroundActive(activity)
            val window = activity.window
            if (window != null) {
                window.statusBarColor = screenBg
                window.navigationBarColor = screenBg
                window.setBackgroundDrawable(ColorDrawable(screenBg))
                try {
                    val controller = WindowInsetsControllerCompat(window, window.decorView)
                    controller.isAppearanceLightStatusBars = isWhite
                    controller.isAppearanceLightNavigationBars = isWhite
                } catch (_: Throwable) {}
            }

            val contentRoot = rootView
                ?: (window?.decorView?.findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0))
                ?: return

            contentRoot.setBackgroundColor(screenBg)
            recolorViewTree(activity, contentRoot, isRoot = true)
        } catch (_: Throwable) {}
    }

    fun recolorViewTree(context: Context, view: View?, isRoot: Boolean = false) {
        if (view == null) return
        val isWhite = isWhiteBackgroundActive(context)
        val isBlack = isBlackBackgroundActive(context)
        val screenBg = getScreenBackgroundColor(context)

        if (isRoot || view is ScrollView || view is NestedScrollView) {
            view.setBackgroundColor(screenBg)
        } else if (view is ViewGroup && view !is RecyclerView) {
            val bg = view.background
            if (bg is ColorDrawable) {
                val c = bg.color
                if (Color.alpha(c) > 200 && isDarkSurfaceColor(c)) {
                    if (isWhite) {
                        view.background = createCardBackground(context, cornerDp = 14f, isHero = false)
                    } else if (isBlack) {
                        view.setBackgroundColor(Color.parseColor("#090A0F"))
                    }
                }
            }
        }

        if (view is EditText) {
            if (isWhite) {
                view.setTextColor(Color.parseColor("#0F172A"))
                view.setHintTextColor(Color.parseColor("#64748B"))
                val density = context.resources.displayMetrics.density
                view.background = GradientDrawable().apply {
                    cornerRadius = 12f * density
                    setColor(Color.parseColor("#F1F5F9"))
                    setStroke((1f * density).toInt().coerceAtLeast(1), Color.parseColor("#CBD5E1"))
                }
            } else {
                view.setTextColor(Color.WHITE)
                view.setHintTextColor(Color.parseColor("#94A3B8"))
            }
            return
        }

        if (view is Button) {
            // Keep solid colored buttons with high-contrast white text
            return
        }

        if (view is TextView) {
            // Self-styled pill buttons, tab pills, and status badges with their own background drawable
            // manage their own contrast text color directly.
            if (view.background != null) {
                return
            }

            val savedOrig = view.getTag(TAG_ORIGINAL_TEXT_COLOR) as? Int
            val currentColor = view.currentTextColor
            val origColor = savedOrig ?: currentColor.also {
                view.setTag(TAG_ORIGINAL_TEXT_COLOR, it)
            }

            if (isWhite) {
                val adapted = adaptTextColorForWhiteMode(origColor)
                if (adapted != currentColor) {
                    view.setTextColor(adapted)
                }
            } else if (savedOrig != null && currentColor != savedOrig) {
                view.setTextColor(savedOrig)
            }
            return
        }

        if (view is RecyclerView) {
            for (i in 0 until view.childCount) {
                recolorViewTree(context, view.getChildAt(i), isRoot = false)
            }
            if (view.getTag(TAG_RV_LISTENER_ATTACHED) != true) {
                view.setTag(TAG_RV_LISTENER_ATTACHED, true)
                view.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
                    override fun onChildViewAttachedToWindow(child: View) {
                        recolorViewTree(view.context, child, isRoot = false)
                    }
                    override fun onChildViewDetachedFromWindow(child: View) {}
                })
            }
            return
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                recolorViewTree(context, view.getChildAt(i), isRoot = false)
            }
        }
    }

    private fun isDarkSurfaceColor(color: Int): Boolean {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        return r < 55 && g < 65 && b < 85
    }

    private fun isLightSurfaceColor(color: Int): Boolean {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        return r > 225 && g > 230 && b > 235
    }

    private fun adaptTextColorForWhiteMode(color: Int): Int {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        // Pure white or near-white primary text -> Deep Slate Black (#0F172A)
        if (r >= 220 && g >= 225 && b >= 230) {
            return Color.parseColor("#0F172A")
        }
        // Light slate / muted gray secondary text (#94A3B8, #CBD5E1, #64748B) -> Readable Slate (#475569)
        if (r in 100..219 && g in 110..224 && b in 130..235 && kotlin.math.abs(r - g) < 30 && kotlin.math.abs(g - b) < 35) {
            return Color.parseColor("#475569")
        }
        // Pale cyan / neon cyan on white -> Crisp Sky Blue (#0284C7)
        if (b >= 210 && g >= 200 && r <= 160) {
            return Color.parseColor("#0284C7")
        }
        // Pale yellow (#FDE047, #FFE57F) on white -> Warm Amber (#B45309)
        if (r >= 235 && g >= 200 && b <= 140) {
            return Color.parseColor("#B45309")
        }
        // Pale mint (#D1FAE5, #69F0AE) on white -> Deep Emerald (#047857)
        if (g >= 215 && r <= 215 && b in 140..235) {
            return Color.parseColor("#047857")
        }
        return color
    }
}

