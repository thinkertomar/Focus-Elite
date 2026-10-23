package com.focusguardpro.app

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable

object UiThemePrefs {
    private const val PREFS_NAME = "focus_elite_ui_theme_prefs"

    private const val KEY_THEME_ID = "theme_id"
    private const val KEY_CARD_GLOW = "card_glow_border"
    private const val KEY_COMPACT_LIST = "compact_app_list"
    private const val KEY_SHOW_BLUE_TICK_STATUS = "show_blue_tick_status"
    private const val KEY_AUTO_ENFORCE_SUGGESTIONS = "auto_enforce_suggestions"
    private const val KEY_DEFAULT_LIMIT_MINUTES = "default_suggestion_limit_mins"
    private const val KEY_FIRST_TIME_INSTRUCTIONS_SHOWN = "first_time_instructions_v2_shown"
    private const val KEY_COVENANT_SEALED = "architect_covenant_sealed"
    private const val KEY_COVENANT_HOURS = "architect_covenant_daily_hours"

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
            id = "cyan",
            name = "Ocean Cyan",
            subtitle = "Cryo-Focus Azure & Deep Navy",
            accentHex = "#00B8D4",
            secondaryHex = "#84FFFF",
            backgroundHex = "#071018",
            cardSurfaceHex = "#101E2B",
            heroTopHex = "#0C354A",
            heroBottomHex = "#101E2B"
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
            .getString(KEY_THEME_ID, "violet") ?: "violet"
        return PALETTES.find { it.id == id } ?: PALETTES.first()
    }

    fun setSelectedPalette(context: Context, id: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME_ID, id)
            .apply()
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
        val density = context.resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerDp * density
            if (isHero) {
                orientation = GradientDrawable.Orientation.TL_BR
                colors = intArrayOf(palette.heroTopColor, palette.heroBottomColor)
            } else {
                setColor(palette.cardSurfaceColor)
            }
            val strokeColor = if (glow) {
                Color.argb(
                    if (isHero) 150 else 80,
                    Color.red(palette.accentColor),
                    Color.green(palette.accentColor),
                    Color.blue(palette.accentColor)
                )
            } else {
                Color.parseColor("#252B42")
            }
            setStroke((1.2f * density).toInt().coerceAtLeast(1), strokeColor)
        }
    }

    fun createAccentCircleBackground(context: Context): GradientDrawable {
        val palette = getSelectedPalette(context)
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(
                Color.argb(
                    45,
                    Color.red(palette.accentColor),
                    Color.green(palette.accentColor),
                    Color.blue(palette.accentColor)
                )
            )
            setStroke(
                (1f * context.resources.displayMetrics.density).toInt().coerceAtLeast(1),
                Color.argb(
                    110,
                    Color.red(palette.accentColor),
                    Color.green(palette.accentColor),
                    Color.blue(palette.accentColor)
                )
            )
        }
    }
}
