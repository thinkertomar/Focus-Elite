package com.focusguardpro.app

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.focusguardpro.app.insights.InsightsDashboardActivity
import com.focusguardpro.app.notification.DailySummaryScheduler
import com.focusguardpro.app.widget.FocusWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_CODE_ENABLE_ADMIN = 1001
        private const val REQUEST_CODE_DEACTIVATE = 1002
    }

    private lateinit var appListView: RecyclerView
    private var headerGearButton: LinearLayout? = null
    private var covenantBannerView: TextView? = null
    private var focusLayerStatusBannerView: LinearLayout? = null
    private var focusLayerStatusTitle: TextView? = null
    private var focusLayerStatusSubtitle: TextView? = null
    private var focusStudioCardView: LinearLayout? = null

    // Digital Wellbeing Hero Card Telemetry Views
    private var heroDeltaBadge: TextView? = null
    private var heroSegStudy: View? = null
    private var heroSegImprove: View? = null
    private var heroSegDistract: View? = null
    private var heroSegUtility: View? = null
    private var heroKpiUnlocks: TextView? = null
    private var heroKpiLaunches: TextView? = null
    private var heroKpiPeakHour: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FocusGuardApp.ensureRoomMainThreadQueries(this)
        setContentView(R.layout.activity_main)

        appListView = findViewById(R.id.appListView)
        appListView.layoutManager = LinearLayoutManager(this)

        injectHeaderGearIconAndBanners()

        try {
            WatchdogService.start(this)
            FocusForegroundMonitorService.start(this)
            FocusMonitorWorker.schedule(this)
        } catch (_: Throwable) {}

        setupPermissionsUI()
        setupLinks()

        try {
            DailySummaryScheduler.scheduleDailySummary(this)
        } catch (_: Throwable) {}

        // First-time open redirect to Instruction Tab inside Gear Icon Hub
        if (!UiThemePrefs.hasSeenFirstTimeInstructions(this)) {
            UiThemePrefs.markFirstTimeInstructionsSeen(this)
            SystemDiagnosticsAndUpdatesManager.markCurrentVersionSeen(this)
            startActivity(
                Intent(this, AuxiliarySettingsActivity::class.java).apply {
                    putExtra(AuxiliarySettingsActivity.EXTRA_INITIAL_TAB, AuxiliarySettingsActivity.TAB_INSTRUCTIONS)
                    putExtra(AuxiliarySettingsActivity.EXTRA_FIRST_TIME_WELCOME, true)
                }
            )
        } else if (SystemDiagnosticsAndUpdatesManager.hasUnseenVersionUpdate(this)) {
            SystemDiagnosticsAndUpdatesManager.postUpdateNotificationIfNeeded(this)
            val palette = UiThemePrefs.getSelectedPalette(this)
            UpdatesHealthAndFeedbackSectionBuilder.showWhatsNewDialog(
                activity = this,
                palette = palette
            ) {
                startActivity(
                    Intent(this, AuxiliarySettingsActivity::class.java).apply {
                        putExtra(AuxiliarySettingsActivity.EXTRA_INITIAL_TAB, AuxiliarySettingsActivity.TAB_UPDATES_HEALTH)
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        FocusGuardApp.ensureRoomMainThreadQueries(this)
        applyDynamicThemeToHome()
        updatePermissionButtonStates()
        updateScreenTimeSummary()
        refreshDailyAuditCard()
        loadAppList()
        refreshSuggestionCard()
        FocusWidgetService.syncTelemetryNow(this)
    }

    private fun dp(value: Float): Int =
        (value * resources.displayMetrics.density).roundToInt()

    /**
     * Dynamically injects:
     * 1) Gear Icon button ("Hub & Guide") in the top header row
     * 2) Covenant Badge (when sealed)
     * 3) Interactive Focus Layer Schedule & Calendar Holiday Lock Card right above Focus Tools
     */
    private fun injectHeaderGearIconAndBanners() {
        val screenTimeCard = findViewById<View>(R.id.screenTimeCard) ?: return
        val mainColumn = screenTimeCard.parent as? LinearLayout ?: return
        val headerRow = mainColumn.getChildAt(0) as? LinearLayout ?: return

        if (headerGearButton != null) return

        val palette = UiThemePrefs.getSelectedPalette(this)

        val existingGear = findViewById<LinearLayout>(R.id.btnHeaderHubGuide)
        val gearContainer = existingGear ?: LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(8f)
            }
            val gearIcon = ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_settings_gear)
                setColorFilter(palette.secondaryColor)
                layoutParams = LinearLayout.LayoutParams(dp(20f), dp(20f)).apply {
                    marginEnd = dp(6f)
                }
                contentDescription = "Auxiliary Settings and Instructions"
            }
            val gearLabel = TextView(this@MainActivity).apply {
                text = "Hub & Guide"
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
            }
            addView(gearIcon)
            addView(gearLabel)
            headerRow.addView(this)
        }

        gearContainer.background = GradientDrawable().apply {
            cornerRadius = dp(20f).toFloat()
            setColor(palette.cardSurfaceColor)
            setStroke(dp(1.5f), palette.accentColor)
        }
        (gearContainer.getChildAt(0) as? ImageView)?.setColorFilter(palette.secondaryColor)
        gearContainer.setOnClickListener {
            startActivity(
                Intent(this@MainActivity, AuxiliarySettingsActivity::class.java).apply {
                    putExtra(AuxiliarySettingsActivity.EXTRA_INITIAL_TAB, AuxiliarySettingsActivity.TAB_INSTRUCTIONS)
                }
            )
        }
        headerGearButton = gearContainer

        // Optional Covenant Badge right below header
        val covenantTv = TextView(this).apply {
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#FDE047"))
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12f)
            }
            setOnClickListener {
                startActivity(
                    Intent(this@MainActivity, AuxiliarySettingsActivity::class.java).apply {
                        putExtra(AuxiliarySettingsActivity.EXTRA_INITIAL_TAB, AuxiliarySettingsActivity.TAB_INSPIRE)
                    }
                )
            }
        }
        mainColumn.addView(covenantTv, 1)
        covenantBannerView = covenantTv

        // Interactive Focus Layer Criteria & Holiday Lock Banner Card
        val focusBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12f)
            }
            setOnClickListener {
                if (FocusLayerManager.isLockedUntilCalendarHoliday(this@MainActivity)) {
                    val rejectionMsg = FocusLayerManager.getSameDayHolidayRejectionNotice(this@MainActivity)
                    val extraHolidayRule = if (rejectionMsg != null) "\n\n$rejectionMsg" else "\n\n• Calendar Holidays must be scheduled ≥24 hours in advance."
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("🔒 Fortress Focus Lock Active")
                        .setMessage(
                            "Your Focus Layer (${FocusLayerManager.formatWindowSummary(this@MainActivity)}) is strictly locked.\n" +
                                "• Emergency 15m breaks & rule changes require Physical Proof-of-Effort (25 steps or 10 squats).$extraHolidayRule"
                        )
                        .setPositiveButton("⚙️ Focus Hub") { _, _ ->
                            startActivity(
                                Intent(this@MainActivity, AuxiliarySettingsActivity::class.java).apply {
                                    putExtra(AuxiliarySettingsActivity.EXTRA_INITIAL_TAB, AuxiliarySettingsActivity.TAB_AUXILIARY)
                                }
                            )
                        }
                        .setNeutralButton("🏋️ 15m Break (Proof)") { _, _ ->
                            CognitiveFrictionGate.show(
                                context = this@MainActivity,
                                title = "Unlock 15-Minute Adaptive Break",
                                reasonPrompt = "Complete physical proof-of-effort (25 verified steps or 10 squats) + commitment phrase to earn a 15-minute break."
                            ) {
                                FocusLayerManager.grantAdaptiveBreak(this@MainActivity, 15)
                                Toast.makeText(this@MainActivity, "🔓 Proof verified! 15-Minute Break granted.", Toast.LENGTH_SHORT).show()
                                applyDynamicThemeToHome()
                            }
                        }
                        .setNegativeButton("📅 Plan Future Holiday") { _, _ ->
                            startActivity(Intent(this@MainActivity, CalendarActivity::class.java))
                        }
                        .show()
                } else {
                    startActivity(
                        Intent(this@MainActivity, AuxiliarySettingsActivity::class.java).apply {
                            putExtra(AuxiliarySettingsActivity.EXTRA_INITIAL_TAB, AuxiliarySettingsActivity.TAB_AUXILIARY)
                        }
                    )
                }
            }
        }

        val shieldCircle = TextView(this).apply {
            text = "🛡️"
            textSize = 18f
            gravity = Gravity.CENTER
            background = UiThemePrefs.createAccentCircleBackground(this@MainActivity)
            layoutParams = LinearLayout.LayoutParams(dp(44f), dp(44f)).apply {
                marginEnd = dp(14f)
            }
        }
        val fTextCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val fTitle = TextView(this).apply {
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        val fSub = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, dp(2f), 0, 0)
        }
        fTextCol.addView(fTitle)
        fTextCol.addView(fSub)
        val fArrow = TextView(this).apply {
            text = "→"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(palette.secondaryColor)
            setPadding(dp(8f), 0, 0, 0)
        }
        focusBanner.addView(shieldCircle)
        focusBanner.addView(fTextCol)
        focusBanner.addView(fArrow)

        // Insert right after screenTimeCard
        val screenTimeIdx = mainColumn.indexOfChild(screenTimeCard)
        mainColumn.addView(focusBanner, (screenTimeIdx + 1).coerceAtMost(mainColumn.childCount))
        focusLayerStatusBannerView = focusBanner
        focusLayerStatusTitle = fTitle
        focusLayerStatusSubtitle = fSub

        // Quick Deep Focus Studio Launch Card (matching Home card design language)
        val focusStudioCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12f)
            }
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            background = UiThemePrefs.createCardBackground(this@MainActivity, cornerDp = 16f, isHero = false)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, FocusSessionActivity::class.java))
            }
        }
        val iconCircle = TextView(this).apply {
            text = "🎯"
            textSize = 18f
            gravity = Gravity.CENTER
            background = UiThemePrefs.createAccentCircleBackground(this@MainActivity)
            layoutParams = LinearLayout.LayoutParams(dp(44f), dp(44f)).apply {
                marginEnd = dp(14f)
            }
        }
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(TextView(this).apply {
            text = "Deep Focus Studio"
            textSize = 14.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        textCol.addView(TextView(this).apply {
            text = "40Hz Gamma soundscape • Pomodoro • Mindful gate"
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, dp(2f), 0, 0)
        })
        val startLabel = TextView(this).apply {
            text = "Start →"
            textSize = 12.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(palette.secondaryColor)
            setPadding(dp(8f), dp(4f), 0, dp(4f))
        }
        focusStudioCard.addView(iconCircle)
        focusStudioCard.addView(textCol)
        focusStudioCard.addView(startLabel)

        mainColumn.addView(focusStudioCard, (screenTimeIdx + 2).coerceAtMost(mainColumn.childCount))
        focusStudioCardView = focusStudioCard

        // Inject Digital Wellbeing Structural Telemetry Strip inside screenTimeCard
        val heroCardGroup = screenTimeCard as? LinearLayout
        if (heroCardGroup != null && heroDeltaBadge == null) {
            val deltaBadge = TextView(this).apply {
                text = "✓ Digital Wellbeing Engine"
                textSize = 11f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#38BDF8"))
                setPadding(0, dp(4f), 0, dp(8f))
            }
            heroDeltaBadge = deltaBadge
            heroCardGroup.addView(deltaBadge)

            // Multi-segment Category Proportion Bar (Study / Improve / Distract / Utility)
            val segRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(7f)
                ).apply {
                    bottomMargin = dp(12f)
                }
                background = GradientDrawable().apply {
                    cornerRadius = dp(4f).toFloat()
                    setColor(Color.parseColor("#1E293B"))
                }
                clipToOutline = true
            }
            val sBar = View(this).apply {
                setBackgroundColor(Color.parseColor("#10B981"))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            }
            val iBar = View(this).apply {
                setBackgroundColor(Color.parseColor("#38BDF8"))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            }
            val dBar = View(this).apply {
                setBackgroundColor(Color.parseColor("#F43F5E"))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            }
            val uBar = View(this).apply {
                setBackgroundColor(Color.parseColor("#64748B"))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            }
            heroSegStudy = sBar
            heroSegImprove = iBar
            heroSegDistract = dBar
            heroSegUtility = uBar
            segRow.addView(sBar)
            segRow.addView(iBar)
            segRow.addView(dBar)
            segRow.addView(uBar)
            heroCardGroup.addView(segRow)

            // 3-Column KPI Strip: Unlocks, App Opens, Peak Hour
            val kpiStrip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            fun makeKpiPill(label: String, color: Int): Pair<LinearLayout, TextView> {
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(6f), dp(7f), dp(6f), dp(7f))
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = dp(3f)
                        marginStart = dp(3f)
                    }
                    background = GradientDrawable().apply {
                        cornerRadius = dp(10f).toFloat()
                        setColor(Color.parseColor("#151E2E"))
                        setStroke(dp(1f), Color.parseColor("#26354D"))
                    }
                }
                val vTv = TextView(this).apply {
                    text = "0"
                    textSize = 14f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(color)
                }
                val lTv = TextView(this).apply {
                    text = label
                    textSize = 9.5f
                    setTextColor(Color.parseColor("#94A3B8"))
                }
                cell.addView(vTv)
                cell.addView(lTv)
                return cell to vTv
            }

            val (uCell, uTv) = makeKpiPill("UNLOCKS", Color.parseColor("#38BDF8"))
            val (oCell, oTv) = makeKpiPill("APP OPENS", Color.parseColor("#A78BFA"))
            val (pCell, pTv) = makeKpiPill("PEAK HOUR", Color.parseColor("#34D399"))
            heroKpiUnlocks = uTv
            heroKpiLaunches = oTv
            heroKpiPeakHour = pTv
            kpiStrip.addView(uCell)
            kpiStrip.addView(oCell)
            kpiStrip.addView(pCell)
            heroCardGroup.addView(kpiStrip)
        }
    }

    private fun applyDynamicThemeToHome() {
        val palette = UiThemePrefs.getSelectedPalette(this)
        val screenTimeCard = findViewById<LinearLayout>(R.id.screenTimeCard)
        val mainColumn = screenTimeCard?.parent as? LinearLayout
        val rootScroll = mainColumn?.parent as? NestedScrollView
        rootScroll?.setBackgroundColor(UiThemePrefs.getScreenBackgroundColor(this))

        // Adapt top-level header & section titles sitting directly on the screen background
        val headerTitleColor = UiThemePrefs.getScreenHeaderTextColor(this)
        val headerSubColor = UiThemePrefs.getScreenSubHeaderTextColor(this)
        val sectionAccentColor = if (UiThemePrefs.isWhiteBackgroundActive(this)) {
            Color.parseColor("#0284C7")
        } else {
            palette.accentColor
        }
        val headerRow = mainColumn?.getChildAt(0) as? LinearLayout
        val headerTextCol = headerRow?.getChildAt(1) as? LinearLayout
        (headerTextCol?.getChildAt(0) as? TextView)?.setTextColor(headerTitleColor)
        (headerTextCol?.getChildAt(1) as? TextView)?.setTextColor(headerSubColor)
        findViewById<TextView>(R.id.deactivateShieldLink)?.setTextColor(headerSubColor)
        findViewById<TextView>(R.id.backupRestoreLink)?.setTextColor(headerSubColor)

        if (mainColumn != null) {
            for (i in 0 until mainColumn.childCount) {
                val child = mainColumn.getChildAt(i)
                if (child is TextView && child != covenantBannerView && child.id != R.id.deactivateShieldLink && child.id != R.id.backupRestoreLink) {
                    if (child.textSize / resources.displayMetrics.scaledDensity <= 13f) {
                        child.setTextColor(sectionAccentColor)
                    } else {
                        child.setTextColor(headerTitleColor)
                    }
                } else if (child is LinearLayout && child.childCount == 2 && child.getChildAt(1) is TextView && child.background == null) {
                    (child.getChildAt(1) as? TextView)?.setTextColor(headerTitleColor)
                }
            }
        }

        screenTimeCard?.background = UiThemePrefs.createCardBackground(this, cornerDp = 18f, isHero = true)

        val cardIds = listOf(
            R.id.dailyTimeAuditCard,
            R.id.practiceSessionLink,
            R.id.activityLink,
            R.id.auditSummaryLink,
            R.id.insightsDashboardLink,
            R.id.pinWidgetLink,
            R.id.auditSettingsLink,
            R.id.websiteBlockLink,
            R.id.calendarLink,
            R.id.suggestionCard
        )
        for (id in cardIds) {
            findViewById<View>(id)?.background = UiThemePrefs.createCardBackground(this, cornerDp = 16f, isHero = false)
        }

        headerGearButton?.background = GradientDrawable().apply {
            cornerRadius = dp(20f).toFloat()
            setColor(palette.cardSurfaceColor)
            setStroke(dp(1.5f), palette.accentColor)
        }
        (headerGearButton?.getChildAt(0) as? ImageView)?.setColorFilter(palette.secondaryColor)

        // Update Focus Layer Criteria & Real-Time Status Banner
        val exemptionDetails = FocusLayerManager.getCalendarExemptionDetails(this)
        val isLocked = FocusLayerManager.isLockedUntilCalendarHoliday(this)
        val isScheduled = FocusLayerManager.isScheduledActiveNow(this)
        val windowSummary = FocusLayerManager.formatWindowSummary(this)
        val currentTimeStr = FocusLayerManager.getCurrentDeviceTimeFormatted()
        focusLayerStatusBannerView?.background = UiThemePrefs.createCardBackground(this, cornerDp = 16f, isHero = false)
        (focusLayerStatusBannerView?.getChildAt(0) as? View)?.background = UiThemePrefs.createAccentCircleBackground(this)
        (focusLayerStatusBannerView?.getChildAt(2) as? TextView)?.setTextColor(palette.secondaryColor)

        focusStudioCardView?.background = UiThemePrefs.createCardBackground(this, cornerDp = 16f, isHero = false)
        (focusStudioCardView?.getChildAt(0) as? View)?.background = UiThemePrefs.createAccentCircleBackground(this)
        (focusStudioCardView?.getChildAt(2) as? TextView)?.setTextColor(palette.secondaryColor)

        val graceMins = FocusLayerManager.getPreWindowGraceRemainingMinutes(this)
        val sameDayRejection = FocusLayerManager.getSameDayHolidayRejectionNotice(this)
        val clockTampered = SettingsTamperGuard.isClockTamperActive(this)

        when {
            exemptionDetails != null -> {
                focusLayerStatusTitle?.text = exemptionDetails
                focusLayerStatusTitle?.setTextColor(Color.parseColor("#38BDF8"))
                focusLayerStatusSubtitle?.text = "Schedule ($windowSummary) paused • $currentTimeStr"
            }
            graceMins > 0 -> {
                focusLayerStatusTitle?.text = "⚡ Pre-Window Grace Period ($graceMins min left)"
                focusLayerStatusTitle?.setTextColor(Color.parseColor("#38BDF8"))
                focusLayerStatusSubtitle?.text = "Adjust categories & schedule freely before Fortress Lock engages"
            }
            isLocked -> {
                focusLayerStatusTitle?.text = if (clockTampered) {
                    "🛡️ Fortress Lock Active (Monotonic Clock Guarded)"
                } else {
                    "🔒 Fortress Lock Active ($windowSummary)"
                }
                focusLayerStatusTitle?.setTextColor(Color.parseColor("#F59E0B"))
                focusLayerStatusSubtitle?.text = sameDayRejection
                    ?: "Zero-escape active ($currentTimeStr) • Tap for Proof-of-Effort break"
            }
            isScheduled -> {
                focusLayerStatusTitle?.text = "🟢 Focus Layer Active ($windowSummary)"
                focusLayerStatusTitle?.setTextColor(Color.parseColor("#34D399"))
                focusLayerStatusSubtitle?.text = "Active right now ($currentTimeStr) • Tap to customize"
            }
            else -> {
                focusLayerStatusTitle?.text = "Focus Layer: $windowSummary"
                focusLayerStatusTitle?.setTextColor(Color.WHITE)
                focusLayerStatusSubtitle?.text = "$currentTimeStr (Outside Window) • Tap to configure"
            }
        }

        if (UiThemePrefs.isCovenantSealed(this)) {
            val hours = UiThemePrefs.getCovenantHours(this)
            val wakingDays = (hours * 365f / 16f).roundToInt()
            covenantBannerView?.apply {
                visibility = View.VISIBLE
                text = "⚡ Architect of Time Covenant Active: Reclaiming ${String.format("%.1f", hours)}h/day (+$wakingDays Waking Days/Yr) • Tap for Fractal Tree"
                background = GradientDrawable().apply {
                    cornerRadius = dp(12f).toFloat()
                    setColor(palette.cardSurfaceColor)
                    setStroke(dp(1f), Color.parseColor("#F59E0B"))
                }
            }
        } else {
            covenantBannerView?.visibility = View.GONE
        }
    }

    private fun setupPermissionsUI() {
        findViewById<Button>(R.id.usageAccessButton)?.setOnClickListener {
            SettingsTamperGuard.grantPermissionSetupGrace(this, 180_000L)
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        findViewById<Button>(R.id.accessibilityButton)?.setOnClickListener {
            val criteriaDone = UiThemePrefs.isSetupCategoriesConfigured(this)
            val scheduleDone = UiThemePrefs.isSetupScheduleConfigured(this)
            if (!criteriaDone || !scheduleDone) {
                AlertDialog.Builder(this)
                    .setTitle("Recommended 4-Step Setup Order")
                    .setMessage(
                        "To ensure Smooth First-Time Setup without any Focus Lock popups:\n\n" +
                            "1. Usage Access (${if (hasUsageStatsPermission()) "✓ Done" else "Pending"})\n" +
                            "2. Define App Criteria — Study / Improve / Ignore (${if (criteriaDone) "✓ Done" else "Next Step"})\n" +
                            "3. Set Focus Schedule (${if (scheduleDone) "✓ Done" else "Pending"})\n" +
                            "4. Enable Device Admin & Accessibility Shield LAST\n\n" +
                            "Would you like to configure your App Criteria & Focus Schedule first, or enable the Accessibility Shield now?"
                    )
                    .setPositiveButton("Configure Step ${if (!criteriaDone) "2 (Apps)" else "3 (Schedule)"} First") { _, _ ->
                        if (!criteriaDone) {
                            startActivity(
                                Intent(this, CategorySettingsActivity::class.java).apply {
                                    putExtra("from_guided_setup", true)
                                }
                            )
                        } else {
                            startActivity(
                                Intent(this, AuxiliarySettingsActivity::class.java).apply {
                                    putExtra(AuxiliarySettingsActivity.EXTRA_INITIAL_TAB, AuxiliarySettingsActivity.TAB_AUXILIARY)
                                }
                            )
                        }
                    }
                    .setNeutralButton("Enable Shield Now (Grace Active)") { _, _ ->
                        UiThemePrefs.markSetupCategoriesConfigured(this)
                        UiThemePrefs.markSetupScheduleConfigured(this)
                        SettingsTamperGuard.grantPermissionSetupGrace(this, 180_000L)
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
                SettingsTamperGuard.grantPermissionSetupGrace(this, 180_000L)
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        findViewById<Button>(R.id.batteryOptButton)?.setOnClickListener {
            SettingsTamperGuard.grantPermissionSetupGrace(this, 180_000L)
            if (!isIgnoringBatteryOptimizations()) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } else {
                Toast.makeText(this, "Already unrestricted!", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<Button>(R.id.btnDeviceAdmin)?.setOnClickListener {
            SettingsTamperGuard.grantPermissionSetupGrace(this, 180_000L)
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(this, MyAdminReceiver::class.java)
            if (!dpm.isAdminActive(adminComponent)) {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                    putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Required to prevent unauthorized uninstallation during focus sessions."
                    )
                }
                @Suppress("DEPRECATION")
                startActivityForResult(intent, REQUEST_CODE_ENABLE_ADMIN)
            } else {
                @Suppress("DEPRECATION")
                startActivityForResult(
                    Intent(this, DeactivationActivity::class.java),
                    REQUEST_CODE_DEACTIVATE
                )
            }
        }
    }

    private fun setupLinks() {
        findViewById<View>(R.id.screenTimeCard)?.setOnClickListener {
            startActivity(Intent(this, UsageStatsActivity::class.java))
        }
        findViewById<View>(R.id.dailyTimeAuditCard)?.setOnClickListener {
            // Directly open Daily Time Notebook (AuditActivity) instead of the "Welcome Back" TimeSplitter popup
            startActivity(Intent(this, AuditActivity::class.java))
        }
        findViewById<View>(R.id.auditSettingsLink)?.setOnClickListener {
            startActivity(Intent(this, CategorySettingsActivity::class.java))
        }
        findViewById<View>(R.id.auditSummaryLink)?.setOnClickListener {
            startActivity(Intent(this, AuditSummaryActivity::class.java))
        }
        findViewById<View>(R.id.calendarLink)?.setOnClickListener {
            startActivity(Intent(this, CalendarActivity::class.java))
        }
        findViewById<View>(R.id.websiteBlockLink)?.setOnClickListener {
            startActivity(Intent(this, WebsiteBlockActivity::class.java))
        }
        findViewById<View>(R.id.activityLink)?.setOnClickListener {
            startActivity(Intent(this, ActivityStatsActivity::class.java))
        }
        findViewById<View>(R.id.practiceSessionLink)?.setOnClickListener {
            startActivity(Intent(this, PracticeSessionActivity::class.java))
        }
        findViewById<View>(R.id.insightsDashboardLink)?.setOnClickListener {
            startActivity(Intent(this, InsightsDashboardActivity::class.java))
        }
        findViewById<View>(R.id.pinWidgetLink)?.setOnClickListener {
            startActivity(Intent(this, WidgetStudioActivity::class.java))
        }
        findViewById<View>(R.id.deactivateShieldLink)?.setOnClickListener {
            startActivity(Intent(this, DeactivationActivity::class.java))
        }
        findViewById<View>(R.id.backupRestoreLink)?.setOnClickListener {
            startActivity(Intent(this, BackupRestoreActivity::class.java))
        }
    }

    private fun refreshDailyAuditCard() {
        val subtitle = findViewById<TextView>(R.id.tvDailyAuditSubtitle) ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val textToSet = try {
                val cal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val startOfDay = cal.timeInMillis
                val now = System.currentTimeMillis()
                val awayRecords = DaySummaryHelper.getAwayRecords(this@MainActivity, startOfDay, now)
                val totalAwayMins = DaySummaryHelper.awayStudyMinutes(awayRecords) +
                    DaySummaryHelper.awayImproveMinutes(awayRecords) +
                    DaySummaryHelper.awayDistractMinutes(awayRecords) +
                    DaySummaryHelper.awayIgnoreMinutes(awayRecords) +
                    DaySummaryHelper.awaySleepMinutes(awayRecords)

                if (totalAwayMins > 0L) {
                    "${DaySummaryHelper.formatMinutes(totalAwayMins)} logged today • Tap to open Daily Time Notebook"
                } else {
                    "Tap to open Daily Time Notebook & log activities"
                }
            } catch (_: Throwable) {
                "Log offline hours, track habits & reflect daily"
            }

            withContext(Dispatchers.Main) {
                subtitle.text = textToSet
            }
        }
    }

    private fun updatePermissionButtonStates() {
        val usageBtn = findViewById<Button>(R.id.usageAccessButton) ?: return
        val accessBtn = findViewById<Button>(R.id.accessibilityButton) ?: return
        val batteryBtn = findViewById<Button>(R.id.batteryOptButton) ?: return
        val adminBtn = findViewById<Button>(R.id.btnDeviceAdmin) ?: return
        val allSetText = findViewById<TextView>(R.id.allPermissionsSetText) ?: return

        val hasUsage = hasUsageStatsPermission()
        val hasAccess = isAccessibilityServiceEnabled()
        val hasBattery = isIgnoringBatteryOptimizations()
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(this, MyAdminReceiver::class.java)
        val hasAdmin = dpm.isAdminActive(adminComponent)

        usageBtn.text = "1. Enable Usage Access (Step 1)"
        adminBtn.text = "4A. Enable Device Admin Lock"
        accessBtn.text = "4B. Enable Accessibility Shield (Enable Last)"
        batteryBtn.text = "Optional: Disable Battery Optimization"

        usageBtn.visibility = if (hasUsage) View.GONE else View.VISIBLE
        adminBtn.visibility = if (hasAdmin) View.GONE else View.VISIBLE
        accessBtn.visibility = if (hasAccess) View.GONE else View.VISIBLE
        batteryBtn.visibility = if (hasBattery) View.GONE else View.VISIBLE

        val deactivateLink = findViewById<TextView>(R.id.deactivateShieldLink)
        if (hasUsage && hasAccess && hasBattery && hasAdmin) {
            allSetText.visibility = View.VISIBLE
            allSetText.text = "✓ All System Shields & Sensors Active (100% On-Device SQLite)"
            deactivateLink?.visibility = View.VISIBLE
        } else {
            allSetText.visibility = View.VISIBLE
            val nextHint = when {
                !hasUsage -> "Guided Setup: Step 1 → Grant Usage Access below"
                !UiThemePrefs.isSetupCategoriesConfigured(this) -> "Guided Setup: Step 2 → Tap 'Category Settings' above to define Study/Improve/Ignore apps"
                !UiThemePrefs.isSetupScheduleConfigured(this) -> "Guided Setup: Step 3 → Tap 'Focus Layer' banner above to confirm your schedule"
                else -> "Guided Setup: Step 4 → Enable Device Admin & Accessibility Shield below"
            }
            allSetText.text = nextHint
            deactivateLink?.visibility = if (hasAdmin) View.VISIBLE else View.GONE
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CODE_ENABLE_ADMIN) {
            updatePermissionButtonStates()
        } else if (requestCode == REQUEST_CODE_DEACTIVATE && resultCode == RESULT_OK) {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(this, MyAdminReceiver::class.java)
            dpm.removeActiveAdmin(adminComponent)
            Toast.makeText(this, "Protection Disabled. Waiting for sync...", Toast.LENGTH_SHORT).show()
            findViewById<Button>(R.id.btnDeviceAdmin)?.postDelayed({
                updatePermissionButtonStates()
            }, 600L)
        }
    }

    private fun updateScreenTimeSummary() {
        val screenTimeText = findViewById<TextView>(R.id.screenTimeText) ?: return
        if (!hasUsageStatsPermission()) {
            screenTimeText.text = "Grant Permission"
            heroDeltaBadge?.text = "Step 1: Grant Usage Access to unlock exact Digital Wellbeing tracking"
            heroKpiUnlocks?.text = "—"
            heroKpiLaunches?.text = "—"
            heroKpiPeakHour?.text = "—"
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val snapshot = UsageStatsHelper.getTodayWellbeingSnapshot(this@MainActivity)
            val timeText = UsageStatsHelper.formatPreciseDuration(snapshot.totalScreenTimeMs)
            val unlocksText = snapshot.unlockCount.toString()
            val launchesText = snapshot.totalAppLaunches.toString()
            val peakHourText = if (snapshot.peakHourMinutes > 0) {
                UsageStatsHelper.formatHourLabel(snapshot.peakHourIndex)
            } else {
                "—"
            }

            val diffMs = snapshot.totalScreenTimeMs - snapshot.yesterdayScreenTimeMs
            val absDiffStr = UsageStatsHelper.formatDuration(kotlin.math.abs(diffMs))
            val studyMins = snapshot.studyMs / 60_000L
            val distractMins = snapshot.distractMs / 60_000L

            val (badgeText, badgeColor) = when {
                snapshot.yesterdayScreenTimeMs <= 0L -> {
                    "✓ Digital Wellbeing Exact • Study ${studyMins}m · Distract ${distractMins}m" to Color.parseColor("#38BDF8")
                }
                diffMs < -60_000L -> {
                    "▼ $absDiffStr vs yesterday • Study ${studyMins}m · Distract ${distractMins}m" to Color.parseColor("#34D399")
                }
                diffMs > 60_000L -> {
                    "▲ $absDiffStr vs yesterday • Study ${studyMins}m · Distract ${distractMins}m" to Color.parseColor("#F59E0B")
                }
                else -> {
                    "≈ Same pace as yesterday • Study ${studyMins}m · Distract ${distractMins}m" to Color.parseColor("#38BDF8")
                }
            }

            val totalCatMs = kotlin.math.max(1L, snapshot.studyMs + snapshot.improveMs + snapshot.distractMs + snapshot.utilityMs).toFloat()
            val weightStudy = (snapshot.studyMs / totalCatMs).coerceAtLeast(if (snapshot.studyMs > 0L) 0.06f else 0f)
            val weightImprove = (snapshot.improveMs / totalCatMs).coerceAtLeast(if (snapshot.improveMs > 0L) 0.06f else 0f)
            val weightDistract = (snapshot.distractMs / totalCatMs).coerceAtLeast(if (snapshot.distractMs > 0L) 0.06f else 0f)
            val weightUtility = (snapshot.utilityMs / totalCatMs).coerceAtLeast(if (snapshot.utilityMs > 0L) 0.06f else 0f)

            withContext(Dispatchers.Main) {
                screenTimeText.text = timeText
                heroKpiUnlocks?.text = unlocksText
                heroKpiLaunches?.text = launchesText
                heroKpiPeakHour?.text = peakHourText

                heroDeltaBadge?.text = badgeText
                heroDeltaBadge?.setTextColor(badgeColor)

                (heroSegStudy?.layoutParams as? LinearLayout.LayoutParams)?.weight = weightStudy
                (heroSegImprove?.layoutParams as? LinearLayout.LayoutParams)?.weight = weightImprove
                (heroSegDistract?.layoutParams as? LinearLayout.LayoutParams)?.weight = weightDistract
                (heroSegUtility?.layoutParams as? LinearLayout.LayoutParams)?.weight = weightUtility
                if (snapshot.totalScreenTimeMs <= 0L) {
                    (heroSegStudy?.layoutParams as? LinearLayout.LayoutParams)?.weight = 1f
                }
                heroSegStudy?.parent?.requestLayout()
            }
        }
    }

    private fun loadAppList() {
        lifecycleScope.launch(Dispatchers.IO) {
            val pm = packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
            val snapshot = if (hasUsageStatsPermission()) {
                UsageStatsHelper.getTodayWellbeingSnapshot(this@MainActivity)
            } else {
                null
            }

            val usedMinsMap = HashMap<String, Int>()
            val totalBrowserMinsMap = HashMap<String, Int>()
            val launchesMap = HashMap<String, Int>()
            val studyApps = AppCategoryPrefs.getStudyApps(this@MainActivity)
            val improveApps = AppCategoryPrefs.getImprovementApps(this@MainActivity)
            val defaultCap = UiThemePrefs.getDefaultSuggestionLimitMinutes(this@MainActivity)

            val appModels = resolveInfos.mapNotNull { ri ->
                val pkg = ri.activityInfo.packageName
                if (pkg == packageName) return@mapNotNull null
                // Exclude all Android system & utility apps so system features always work 24/7
                if (SystemExclusions.isExcludedPackage(this@MainActivity, pkg)) return@mapNotNull null
                val name = ri.loadLabel(pm).toString()
                val icon = ri.loadIcon(pm)
                val isBrowser = BlockPolicyEngine.isBrowserPackage(pkg)
                val stat = snapshot?.perAppStats?.get(pkg)
                val rawPkgMins = ((stat?.durationMs ?: 0L) / 60_000L).toInt()
                val usedMins = if (isBrowser) {
                    BlockPolicyEngine.getNonStudyBrowserMinutesForPackage(this@MainActivity, pkg).toInt()
                } else {
                    rawPkgMins
                }
                usedMinsMap[pkg] = usedMins
                totalBrowserMinsMap[pkg] = rawPkgMins
                launchesMap[pkg] = stat?.launchCount ?: 0

                val limitMins = TimeLimitManager.getLimitMinutes(this@MainActivity, pkg)
                val hardBlocked = HardBlockManager.isHardBlocked(this@MainActivity, pkg)
                val isWhitelisted = studyApps.contains(pkg) ||
                    improveApps.contains(pkg) ||
                    BlockPolicyEngine.isUnblockedForToday(this@MainActivity, pkg)
                val effectiveCap = if (limitMins > 0) limitMins else defaultCap
                val reachedCap = usedMins >= effectiveCap && !isWhitelisted

                // Ensure the 30m default cap is persisted in TimeLimitManager once reached
                if (reachedCap && limitMins <= 0) {
                    try {
                        TimeLimitManager.setLimitMinutes(this@MainActivity, pkg, effectiveCap)
                    } catch (_: Exception) {}
                }

                val isBlockedNow = hardBlocked || reachedCap
                AppModel(name, pkg, icon, isBlockedNow)
            }
                .distinctBy { it.packageName }
                .sortedWith(
                    compareByDescending<AppModel> { it.isBlocked }
                        .thenByDescending { usedMinsMap[it.packageName] ?: 0 }
                        .thenBy { it.appName.lowercase() }
                )

            withContext(Dispatchers.Main) {
                appListView.adapter = AppAdapter(
                    apps = appModels,
                    usedMinutesByPkg = usedMinsMap,
                    launchCountByPkg = launchesMap,
                    totalBrowserMinutesByPkg = totalBrowserMinsMap,
                    onLimitClick = { app -> showLimitDialog(app, usedMinsMap[app.packageName] ?: 0) },
                    onToggle = { app, isChecked ->
                        val cap = UiThemePrefs.getDefaultSuggestionLimitMinutes(this@MainActivity)
                        val isBrowser = BlockPolicyEngine.isBrowserPackage(app.packageName)
                        if (!isChecked && FocusLayerManager.isLockedUntilCalendarHoliday(this@MainActivity)) {
                            // Reverting the switch immediately until Proof-of-Effort is completed
                            loadAppList()
                            CognitiveFrictionGate.show(
                                context = this@MainActivity,
                                title = "Unblock ${app.appName}",
                                reasonPrompt = "Fortress Lock is active. Complete physical proof-of-effort (25 steps or 10 squats) to unblock ${app.appName}."
                            ) {
                                BlockPolicyEngine.setAppBlocked(this@MainActivity, app.packageName, false, cap)
                                Toast.makeText(this@MainActivity, "🔓 ${app.appName} unblocked via Proof-of-Effort", Toast.LENGTH_SHORT).show()
                                loadAppList()
                                refreshSuggestionCard()
                            }
                        } else {
                            BlockPolicyEngine.setAppBlocked(this@MainActivity, app.packageName, isChecked, cap)
                            val msg = when {
                                isBrowser && isChecked ->
                                    "✓ ${app.appName}: Non-Study Websites locked (Study Websites work 24/7)"
                                isBrowser ->
                                    "${app.appName}: Study Websites work 24/7 • Non-Study 30m cap active"
                                isChecked ->
                                    "✓ ${app.appName} blocked (${TimeLimitManager.getLimitMinutes(this@MainActivity, app.packageName)}m cap)"
                                else ->
                                    "${app.appName} unblocked for today"
                            }
                            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                            refreshSuggestionCard()
                        }
                    }
                )
            }
        }
    }

    private fun refreshSuggestionCard() {
        val card = findViewById<LinearLayout>(R.id.suggestionCard) ?: return
        if (!hasUsageStatsPermission()) {
            card.visibility = View.GONE
            return
        }

        val text = findViewById<TextView>(R.id.suggestionText) ?: return
        val acceptBtn = findViewById<Button>(R.id.suggestionAcceptButton) ?: return
        val dismissBtn = findViewById<Button>(R.id.suggestionDismissButton) ?: return

        lifecycleScope.launch(Dispatchers.IO) {
            if (UiThemePrefs.isAutoEnforceSuggestions(this@MainActivity)) {
                val newlyEnforced = SmartSuggestionEngine.autoEnforce(this@MainActivity)
                withContext(Dispatchers.Main) {
                    if (newlyEnforced.isEmpty()) {
                        card.visibility = View.GONE
                        return@withContext
                    }
                    val firstCandidate = newlyEnforced.first()
                    val firstPkg = firstCandidate.packageName
                    val firstName = firstCandidate.appLabel
                    val firstMins = firstCandidate.avgMinutes
                    val capMins = UiThemePrefs.getDefaultSuggestionLimitMinutes(this@MainActivity)
                    TimeLimitManager.setLimitMinutes(this@MainActivity, firstPkg, capMins)

                    card.visibility = View.VISIBLE
                    text.text = "✓ Auto-Blocked $firstName (${firstMins}m avg/day). It now displays a Blue Tick (✓) in your Apps Blocklist."
                    acceptBtn.text = "Undo $firstName"
                    dismissBtn.text = "Keep Blocked ✓"

                    acceptBtn.setOnClickListener {
                        SmartSuggestionEngine.undoAutoEnforce(this@MainActivity, firstPkg)
                        BlockPolicyEngine.setAppBlocked(this@MainActivity, firstPkg, false)
                        Toast.makeText(this@MainActivity, "Unblocked $firstName for today", Toast.LENGTH_SHORT).show()
                        card.visibility = View.GONE
                        loadAppList()
                    }
                    dismissBtn.setOnClickListener {
                        SmartSuggestionEngine.dismiss(this@MainActivity, firstPkg)
                        card.visibility = View.GONE
                        loadAppList()
                    }
                }
                return@launch
            }

            val candidates = SmartSuggestionEngine.findCandidates(this@MainActivity)
                .filter { candidate -> !HardBlockManager.isHardBlocked(this@MainActivity, candidate.packageName) }

            withContext(Dispatchers.Main) {
                if (candidates.isEmpty()) {
                    card.visibility = View.GONE
                    return@withContext
                }

                val target = candidates.first()
                val targetPkg = target.packageName
                val appLabel = target.appLabel
                val avgMins = target.avgMinutes
                val capMins = UiThemePrefs.getDefaultSuggestionLimitMinutes(this@MainActivity)
                val remainingCount = candidates.size - 1

                card.visibility = View.VISIBLE
                val extraNote = if (remainingCount > 0) " (+$remainingCount more app suggestion)" else ""
                text.text = "You used $appLabel for ${avgMins}m+$extraNote.\nBlock ONLY $appLabel & apply a ${capMins}m daily cap with Blue Tick (✓)?"

                acceptBtn.text = "✓ Block $appLabel (${capMins}m)"
                dismissBtn.text = "Skip $appLabel"

                acceptBtn.setOnClickListener {
                    HardBlockManager.setHardBlocked(this@MainActivity, targetPkg, true)
                    TimeLimitManager.setLimitMinutes(this@MainActivity, targetPkg, capMins)
                    BlockPolicyEngine.setUnblockedForToday(this@MainActivity, targetPkg, false)
                    SmartSuggestionEngine.dismiss(this@MainActivity, targetPkg)
                    Toast.makeText(
                        this@MainActivity,
                        "✓ Blocked $appLabel (${capMins}m cap + Blue Tick applied)!",
                        Toast.LENGTH_SHORT
                    ).show()
                    loadAppList()
                    refreshSuggestionCard()
                }

                dismissBtn.setOnClickListener {
                    SmartSuggestionEngine.dismiss(this@MainActivity, targetPkg)
                    refreshSuggestionCard()
                }
            }
        }
    }

    private fun showLimitDialog(app: AppModel, usedMinutesToday: Int) {
        val currentLimit = TimeLimitManager.getLimitMinutes(this, app.packageName)
        val defaultCap = UiThemePrefs.getDefaultSuggestionLimitMinutes(this)
        val isBrowser = BlockPolicyEngine.isBrowserPackage(app.packageName)
        val stat = UsageStatsHelper.getTodayWellbeingSnapshot(this).perAppStats[app.packageName]
        val exactTimeStr = UsageStatsHelper.formatPreciseDuration(stat?.durationMs ?: (usedMinutesToday * 60_000L))
        val opensCount = stat?.launchCount ?: 0
        val longestStr = UsageStatsHelper.formatDuration(stat?.longestSessionMs ?: 0L)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(12f), dp(20f), dp(8f))
        }

        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "Minutes per day (default $defaultCap)"
            setText((if (currentLimit > 0) currentLimit else defaultCap).toString())
            setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
        }

        // Quick Preset Chips (15m, 30m, 45m, 60m)
        val presetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10f)
            }
        }
        listOf(15, 30, 45, 60).forEach { presetMins ->
            val chip = TextView(this).apply {
                text = "${presetMins}m"
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#38BDF8"))
                gravity = Gravity.CENTER
                setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(3f)
                    marginEnd = dp(3f)
                }
                background = GradientDrawable().apply {
                    cornerRadius = dp(14f).toFloat()
                    setColor(Color.parseColor("#1E293B"))
                    setStroke(dp(1f), Color.parseColor("#38BDF8"))
                }
                setOnClickListener {
                    input.setText(presetMins.toString())
                    input.setSelection(input.text.length)
                }
            }
            presetRow.addView(chip)
        }
        container.addView(presetRow)
        container.addView(input)

        val dialogMessage = if (isBrowser) {
            "Digital Wellbeing Telemetry Today:\n" +
                "• Non-Study Web Time: $usedMinutesToday min\n" +
                "• Total Browser Time: $exactTimeStr ($opensCount opens)\n" +
                "• Study Websites: Allowed 24/7 with no limit\n\n" +
                "Select a preset or enter daily Non-Study Website cap (minutes):"
        } else {
            "Digital Wellbeing Telemetry Today:\n" +
                "• Screen Time: $exactTimeStr ($opensCount opens · longest $longestStr)\n\n" +
                "Select a preset or enter daily cap in minutes:"
        }

        AlertDialog.Builder(this)
            .setTitle("Daily Time Limit — ${app.appName}")
            .setMessage(dialogMessage)
            .setView(container)
            .setPositiveButton("Save & Apply ✓") { _, _ ->
                val minutes = input.text.toString().toIntOrNull() ?: 0
                val isLooseningLimit = (currentLimit > 0 && (minutes == 0 || minutes > currentLimit))
                if (isLooseningLimit && FocusLayerManager.isLockedUntilCalendarHoliday(this)) {
                    CognitiveFrictionGate.show(
                        context = this,
                        title = "Increase Limit — ${app.appName}",
                        reasonPrompt = "Increasing or clearing a daily cap during active Fortress Lock requires Physical Proof-of-Effort."
                    ) {
                        BlockPolicyEngine.setTimeLimit(this, app.packageName, minutes)
                        if (minutes > 0 && usedMinutesToday < minutes) {
                            HardBlockManager.setHardBlocked(this, app.packageName, false)
                        }
                        Toast.makeText(
                            this,
                            if (minutes > 0) "🔓 ${app.appName} limit updated to ${minutes}m/day" else "🔓 Custom limit cleared for ${app.appName}",
                            Toast.LENGTH_SHORT
                        ).show()
                        loadAppList()
                    }
                } else {
                    BlockPolicyEngine.setTimeLimit(this, app.packageName, minutes)
                    if (!isBrowser && minutes > 0 && usedMinutesToday >= minutes) {
                        HardBlockManager.setHardBlocked(this, app.packageName, true)
                    } else if (minutes > 0 && usedMinutesToday < minutes) {
                        HardBlockManager.setHardBlocked(this, app.packageName, false)
                    }
                    Toast.makeText(
                        this,
                        if (minutes > 0) "✓ ${app.appName} limit set to ${minutes}m/day" else "Custom limit cleared for ${app.appName}",
                        Toast.LENGTH_SHORT
                    ).show()
                    loadAppList()
                }
            }
            .setNeutralButton("Clear Limit & Unblock") { _, _ ->
                if (FocusLayerManager.isLockedUntilCalendarHoliday(this)) {
                    CognitiveFrictionGate.show(
                        context = this,
                        title = "Clear Limit & Unblock — ${app.appName}",
                        reasonPrompt = "Unblocking ${app.appName} during active Fortress Lock requires Physical Proof-of-Effort."
                    ) {
                        TimeLimitManager.setLimitMinutes(this, app.packageName, 0)
                        HardBlockManager.setHardBlocked(this, app.packageName, false)
                        BlockPolicyEngine.setUnblockedForToday(this, app.packageName, true)
                        loadAppList()
                    }
                } else {
                    TimeLimitManager.setLimitMinutes(this, app.packageName, 0)
                    HardBlockManager.setHardBlocked(this, app.packageName, false)
                    BlockPolicyEngine.setUnblockedForToday(this, app.packageName, true)
                    loadAppList()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponentName = ComponentName(this, BlockerAccessibilityService::class.java)
        val enabledServicesSetting = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)
        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            val enabledService = ComponentName.unflattenFromString(componentNameString)
            if (enabledService != null && enabledService == expectedComponentName) {
                return true
            }
        }
        return false
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }
}
